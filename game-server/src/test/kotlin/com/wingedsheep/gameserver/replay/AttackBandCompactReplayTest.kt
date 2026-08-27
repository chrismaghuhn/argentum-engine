package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gameserver.session.SpectatorSeat
import com.wingedsheep.gameserver.session.SpectatorStateBuilder
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * Production CompactReplay coverage for a non-empty DeclareAttackers band. The live session and
 * the reconstruction both traverse GameSession/ActionProcessor and the codec, so a UUID-backed
 * band id would fail the exact semantic trajectory assertion.
 */
class AttackBandCompactReplayTest : ScenarioTestBase() {

    private val replayBandCard = CardDefinition.creature(
        name = "A6 Replay Banding Witness",
        manaCost = ManaCost.parse("{0}"),
        subtypes = setOf(Subtype("Witness")),
        power = 1,
        toughness = 1,
        oracleText = "Banding, haste",
        keywords = setOf(Keyword.BANDING, Keyword.HASTE),
    )

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun stateOf(session: GameSession): GameState =
        session.getStateForTesting().shouldNotBeNull()

    private fun advanceUntil(
        session: GameSession,
        capture: () -> Unit,
        predicate: (GameState) -> Boolean,
    ) {
        repeat(200) {
            val state = stateOf(session)
            if (predicate(state)) return
            val priority = state.priorityPlayerId
                ?: error("replay band fixture lost priority at ${state.step}")
            when (val result = session.executeAutoPass(priority)) {
                is GameSession.ActionResult.Failure -> error(result.reason)
                else -> capture()
            }
        }
        error("replay band fixture did not reach its expected state")
    }

    init {
        test("non-empty band survives production CompactReplay codec and exact reconstruction") {
            cardRegistry.register(replayBandCard)
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val p1 = EntityId.of("a6-replay-p1")
            val p2 = EntityId.of("a6-replay-p2")
            val deck = mapOf(replayBandCard.name to 40)
            session.addPlayer(PlayerSession(mockWs("a6-replay-ws1"), p1, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("a6-replay-ws2"), p2, "Bob"), deck)
            session.startGame()

            val liveFrames = mutableListOf<ServerMessage.SpectatorStateUpdate>()
            fun capture() {
                liveFrames += session.buildSpectatorState()
                    ?: error("started replay session did not build a spectator frame")
            }
            capture()

            session.keepHand(p1)
            capture()
            session.keepHand(p2)
            capture()

            val attacker = stateOf(session).activePlayerId.shouldNotBeNull()
            val defender = stateOf(session).turnOrder.first { it != attacker }
            advanceUntil(session, ::capture) { state ->
                state.activePlayerId == attacker &&
                    state.priorityPlayerId == attacker &&
                    state.step == Step.PRECOMBAT_MAIN &&
                    state.pendingDecision == null &&
                    state.stack.isEmpty()
            }

            fun castReplayCreature() {
                val cardId = session.getHand(attacker).firstOrNull()
                    ?: error("replay band fixture ran out of opening-hand creatures")
                session.executeAction(attacker, CastSpell(attacker, cardId))
                    .let { result ->
                        if (result is GameSession.ActionResult.Failure) error(result.reason)
                    }
                capture()
                val expectedCount = stateOf(session).getBattlefield(attacker).count { id ->
                    stateOf(session).getEntity(id)?.get<CardComponent>()?.name == replayBandCard.name
                }
                advanceUntil(session, ::capture) { state ->
                    state.activePlayerId == attacker &&
                        state.priorityPlayerId == attacker &&
                        state.step == Step.PRECOMBAT_MAIN &&
                        state.pendingDecision == null &&
                        state.stack.isEmpty() &&
                        state.getBattlefield(attacker).count { id ->
                            state.getEntity(id)?.get<CardComponent>()?.name == replayBandCard.name
                        } >= expectedCount
                }
            }

            castReplayCreature()
            castReplayCreature()

            advanceUntil(session, ::capture) { state ->
                state.activePlayerId == attacker &&
                    state.priorityPlayerId == attacker &&
                    state.step == Step.DECLARE_ATTACKERS &&
                    state.pendingDecision == null &&
                    state.stack.isEmpty()
            }

            val attackers = stateOf(session).getBattlefield(attacker)
                .filter { id ->
                    stateOf(session).getEntity(id)?.get<CardComponent>()?.name == replayBandCard.name
                }
                .sortedBy { stateOf(session).objectIdentityStamps.getValue(it) }
            attackers.size shouldBe 2
            val declaration = DeclareAttackers(
                playerId = attacker,
                attackers = attackers.associateWith { defender },
                bands = listOf(attackers.toSet()),
            )
            session.executeAction(attacker, declaration).let { result ->
                if (result is GameSession.ActionResult.Failure) error(result.reason)
            }
            capture()

            val snapshot = session.replayRecordingSnapshot().shouldNotBeNull()
            snapshot.actions.shouldNotBeEmpty()
            val replay = CompactReplay(
                version = snapshot.version,
                gameId = session.sessionId,
                players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
                startedAt = (snapshot.startedAt ?: Instant.EPOCH).toString(),
                endedAt = Instant.EPOCH.toString(),
                winnerName = null,
                setup = snapshot.setup,
                actions = snapshot.actions,
                yields = snapshot.yields,
                pinnedCards = session.getPinnedCards(),
                checkpoints = ReplayCheckpointPolicy.withV3Tail(
                    checkpoints = snapshot.checkpoints,
                    actionCount = snapshot.actions.size,
                    fingerprint = snapshot.fingerprint,
                ),
            )
            val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
            decoded shouldBe replay

            val reconstructor = ReplayReconstructor(cardRegistry, null)
            val reconstructed = reconstructor.reconstruct(decoded)
            reconstructed.fidelity shouldBe ReplayFidelity.EXACT
            reconstructed.frameCount shouldBe liveFrames.size

            val liveFinal = stateOf(session)
            val reconstructedFinal = reconstructor.reconstructStateAt(decoded, decoded.actions.size)
                .shouldNotBeNull()
            ReplayFingerprint.of(reconstructedFinal, decoded.version) shouldBe
                ReplayFingerprint.of(liveFinal, decoded.version)
            reconstructedFinal.getEntity(attackers[0])
                ?.get<AttackingComponent>()?.bandId shouldBe "combat-band-0"
            reconstructedFinal.getEntity(attackers[1])
                ?.get<AttackingComponent>()?.bandId shouldBe "combat-band-0"

            val seats = decoded.setup.players.map { SpectatorSeat(EntityId(it.playerId), it.name) }
            val builder = SpectatorStateBuilder(cardRegistry, ClientStateTransformer(cardRegistry))
            val reconstructedFrames = (0..decoded.actions.size).map { frame ->
                val frameState = reconstructor.reconstructStateAt(decoded, frame).shouldNotBeNull()
                builder.buildState(frameState, seats, decoded.setup.seatRoster, decoded.gameId)
            }
            reconstructedFrames shouldBe liveFrames

            reconstructor.reconstructDiagnostics(decoded).let { diagnostics ->
                diagnostics.fidelity shouldBe ReplayFidelity.EXACT
                diagnostics.diagnostics shouldBe emptyList()
                diagnostics.divergedAtAction shouldBe null
                diagnostics.failure shouldBe null
            }
        }
    }
}
