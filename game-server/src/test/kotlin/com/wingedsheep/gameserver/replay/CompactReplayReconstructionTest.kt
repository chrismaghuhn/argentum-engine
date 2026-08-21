package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gameserver.session.SpectatorSeat
import com.wingedsheep.gameserver.session.SpectatorStateBuilder
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * Proves the compact replay format is lossless: a game driven through the real [GameSession]
 * recording path, rebuilt from nothing but its captured setup + action stream, re-simulates to a
 * byte-identical final [com.wingedsheep.engine.state.GameState] and produces one viewer frame per
 * input. This is the load-bearing guarantee behind storing inputs instead of snapshots — if entity
 * ids, the RNG, or any read weren't deterministic, the reconstructed state would diverge.
 */
class CompactReplayReconstructionTest : ScenarioTestBase() {

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    init {
        test("a recorded game reconstructs to a byte-identical final state and full frame stream") {
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val p1 = EntityId.of("player-1")
            val p2 = EntityId.of("player-2")
            session.addPlayer(PlayerSession(mockWs("ws1"), p1, "Alice"), mapOf("Forest" to 40))
            session.addPlayer(PlayerSession(mockWs("ws2"), p2, "Bob"), mapOf("Forest" to 40))
            session.startGame()

            // Leave the mulligan phase (first keep bottoms nothing).
            session.keepHand(p1)
            session.keepHand(p2)

            // Drive several turns purely by passing priority. Draws at each turn are seed-driven, so
            // this still exercises RNG threading and per-turn entity creation — exactly what would
            // diverge if reconstruction weren't deterministic.
            repeat(60) {
                val state = session.getStateForTesting() ?: return@repeat
                if (state.gameOver) return@repeat
                val priority = state.priorityPlayerId ?: return@repeat
                session.executeAutoPass(priority)
            }

            val setup = session.getReplaySetup().shouldNotBeNull()
            val actions = session.getRecordedActions()
            actions.shouldNotBeEmpty()
            val liveFinal = session.getStateForTesting().shouldNotBeNull()

            val replay = CompactReplay(
                gameId = session.sessionId,
                players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
                startedAt = Instant.now().toString(),
                endedAt = Instant.now().toString(),
                winnerName = null,
                setup = setup,
                actions = actions,
            )

            // The durable gzip+base64 codec round-trips the exact record.
            ReplayCodec.decode(ReplayCodec.encode(replay)) shouldBe replay

            val reconstructor = ReplayReconstructor(cardRegistry, null)

            val reconFinal = reconstructor.reconstructStateAt(replay, actions.size).shouldNotBeNull()

            // The deterministic core of the game re-simulates byte-for-byte: every entity, every
            // zone, the RNG, the entity-id counter, and the stack. (Only `pendingDecision` /
            // `continuationStack` can differ, and solely in their UUID routing ids, which the engine
            // mints non-deterministically and which never reach the player-visible projection.)
            reconFinal.entities shouldBe liveFinal.entities
            reconFinal.zones shouldBe liveFinal.zones
            reconFinal.rng shouldBe liveFinal.rng
            reconFinal.nextEntityId shouldBe liveFinal.nextEntityId
            reconFinal.stack shouldBe liveFinal.stack
            reconFinal.turnNumber shouldBe liveFinal.turnNumber

            // The player-visible spectator projection (what the replay viewer actually shows) is
            // identical — it carries no decision id, so it matches exactly.
            val builder = SpectatorStateBuilder(cardRegistry, ClientStateTransformer(cardRegistry))
            val seats = setup.players.map { SpectatorSeat(EntityId.of(it.playerId), it.name) }
            builder.buildState(reconFinal, seats, setup.seatRoster, session.sessionId) shouldBe
                builder.buildState(liveFinal, seats, setup.seatRoster, session.sessionId)

            // The viewer stream is initial-frame + one-per-action, with no mid-replay divergence
            // (a divergence would truncate the delta list short).
            val reconstructed = reconstructor.reconstruct(replay)
            reconstructed.frameCount shouldBe (1 + actions.size)

            // Rules diagnostics are transient: parity is proven by the real replay fold, not by
            // adding a diagnostics field to CompactReplay or changing its version. A v3 tail
            // checkpoint is required before an empty result can prove zero unsupported paths.
            val exactReplay = replay.copy(
                checkpoints = listOf(
                    ReplayCheckpoint(
                        afterActionCount = actions.size,
                        fingerprint = ReplayFingerprint.of(liveFinal, replay.version),
                    )
                )
            )
            val reconstructedDiagnostics = reconstructor.reconstructDiagnostics(exactReplay)
            reconstructedDiagnostics.fidelity shouldBe ReplayFidelity.EXACT
            reconstructedDiagnostics.diagnostics shouldBe emptyList()
            reconstructedDiagnostics.divergedAtAction shouldBe null
            reconstructedDiagnostics.failure shouldBe null
        }

        test("a divergent replay without a diagnostic is not a zero-unsupported proof") {
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val p1 = EntityId.of("divergence-player-1")
            val p2 = EntityId.of("divergence-player-2")
            session.addPlayer(PlayerSession(mockWs("divergence-ws1"), p1, "Alice"), mapOf("Forest" to 40))
            session.addPlayer(PlayerSession(mockWs("divergence-ws2"), p2, "Bob"), mapOf("Forest" to 40))
            session.startGame()
            session.keepHand(p1)
            session.keepHand(p2)
            repeat(4) {
                val state = session.getStateForTesting().shouldNotBeNull()
                state.priorityPlayerId?.let(session::executeAutoPass)
            }

            val setup = session.getReplaySetup().shouldNotBeNull()
            val replay = CompactReplay(
                gameId = session.sessionId,
                players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
                startedAt = Instant.now().toString(),
                endedAt = Instant.now().toString(),
                winnerName = null,
                setup = setup,
                actions = session.getRecordedActions() + PassPriority(EntityId("not-a-player")),
            )

            val reconstructedDiagnostics = ReplayReconstructor(cardRegistry, null)
                .reconstructDiagnostics(replay)

            reconstructedDiagnostics.fidelity shouldBe ReplayFidelity.DIVERGED
            reconstructedDiagnostics.diagnostics shouldBe emptyList()
            reconstructedDiagnostics.divergedAtAction shouldBe replay.actions.lastIndex
            reconstructedDiagnostics.failure.shouldNotBeNull()
        }

        test("a game whose state was injected (no recorded setup) is not replayable") {
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            // No startGame()/addPlayer flow: simulate a dev-scenario injection.
            session.getReplaySetup() shouldBe null
            session.getReplayFrameCount() shouldBe 0
        }
    }
}
