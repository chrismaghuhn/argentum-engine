package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.effects.PayOrSufferEffect
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * A6 continuation/reconstruction proof for the pinned Pay-or-Suffer cost semantics.
 *
 * Two copies of this zero-cost artifact enter in a real GameSession. The first cannot pay its
 * "another artifact" cost and survives via the suffer branch; the second pauses for a real card
 * selection and pays by sacrificing the first. The recorded action stream then crosses the durable
 * codec and reconstructs to an exact v3 fingerprint.
 */
class Sync04PayOrSufferReplayTest : ScenarioTestBase() {

    private val payCard = CardDefinition(
        name = "Sync-04 Pay-or-Suffer Replay Card",
        manaCost = ManaCost.parse("{0}"),
        typeLine = TypeLine.artifactCreature(setOf(Subtype("Construct"))),
        oracleText = "When this creature enters, sacrifice it unless you sacrifice another artifact.",
        creatureStats = CreatureStats(1, 1),
        script = CardScript.creature(
            TriggeredAbility.create(
                trigger = EventPattern.ZoneChangeEvent(to = Zone.BATTLEFIELD),
                binding = TriggerBinding.SELF,
                effect = PayOrSufferEffect(
                    cost = Costs.pay.SacrificeAnother(GameObjectFilter.Artifact),
                    suffer = Effects.GainLife(1),
                ),
            ),
        ),
    )

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun replayFrom(session: GameSession, snapshot: ReplayRecordingSnapshot): CompactReplay =
        CompactReplay(
            version = snapshot.version,
            gameId = session.sessionId,
            players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
            startedAt = (snapshot.startedAt ?: Instant.now()).toString(),
            endedAt = Instant.now().toString(),
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

    private fun passUntil(
        session: GameSession,
        activePlayer: com.wingedsheep.sdk.model.EntityId,
        predicate: (com.wingedsheep.engine.state.GameState) -> Boolean,
    ) {
        repeat(40) {
            val state = session.getStateForTesting().shouldNotBeNull()
            if (state.activePlayerId == activePlayer && state.step == Step.PRECOMBAT_MAIN && predicate(state)) return
            state.priorityPlayerId?.let(session::executeAutoPass)
                ?: error("no priority while waiting for the Pay-or-Suffer state")
        }
        error("Pay-or-Suffer replay fixture did not reach its expected state")
    }

    init {
        test("SacrificeAnother continuation survives codec and reconstructs exactly") {
            cardRegistry.register(payCard)

            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val p1 = com.wingedsheep.sdk.model.EntityId.of("sync04-pay-p1")
            val p2 = com.wingedsheep.sdk.model.EntityId.of("sync04-pay-p2")
            val deck = mapOf(payCard.name to 40)
            session.addPlayer(PlayerSession(mockWs("sync04-pay-ws1"), p1, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("sync04-pay-ws2"), p2, "Bob"), deck)
            session.startGame()
            session.keepHand(p1)
            session.keepHand(p2)

            passUntil(session, p1) { state ->
                state.priorityPlayerId == p1 && state.pendingDecision == null && state.stack.isEmpty()
            }

            fun castOne(): CastSpell {
                val actions = session.getLegalActions(p1).map { it.action }
                val state = session.getStateForTesting().shouldNotBeNull()
                return actions.filterIsInstance<CastSpell>().firstOrNull()
                    ?: error(
                        "expected a second Pay-or-Suffer cast; " +
                            "actions=${actions.map { it::class.simpleName }} " +
                            "hand=${session.getHand(p1)} " +
                            "active=${state.activePlayerId} priority=${state.priorityPlayerId} " +
                            "phase=${state.phase} turn=${state.turnNumber}",
                    )
            }

            (session.executeAction(p1, castOne()) is GameSession.ActionResult.Success) shouldBe true
            passUntil(session, p1) { state ->
                state.priorityPlayerId == p1 && state.pendingDecision == null && state.stack.isEmpty() &&
                    state.getBattlefield().count { id -> state.getEntity(id)?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name == payCard.name } == 1
            }
            val firstPermanent = session.getStateForTesting()!!.getBattlefield().first { id ->
                session.getStateForTesting()!!.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name == payCard.name
            }

            (session.executeAction(p1, castOne()) is GameSession.ActionResult.Success) shouldBe true
            passUntil(session, p1) { it.pendingDecision is SelectCardsDecision }
            val pending = session.getStateForTesting()!!.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
            pending.options.shouldNotBeEmpty()
            pending.options shouldBe listOf(firstPermanent)

            (session.executeAction(
                p1,
                SubmitDecision(p1, CardsSelectedResponse(pending.id, listOf(firstPermanent))),
            ) is GameSession.ActionResult.Success) shouldBe true
            passUntil(session, p1) { state -> state.priorityPlayerId == p1 && state.pendingDecision == null && state.stack.isEmpty() }

            val snapshot = session.replayRecordingSnapshot().shouldNotBeNull()
            val replay = replayFrom(session, snapshot)
            ReplayCodec.decode(ReplayCodec.encode(replay)) shouldBe replay

            val liveFinal = session.getStateForTesting().shouldNotBeNull()
            val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(replay)
            withClue("the Pay-or-Suffer continuation must reconstruct as an exact replay") {
                reconstructed.fidelity shouldBe ReplayFidelity.EXACT
                reconstructed.frameCount shouldBe 1 + replay.actions.size
            }
            val reconstructedFinal = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(replay, replay.actions.size)
                .shouldNotBeNull()
            ReplayFingerprint.of(reconstructedFinal, CompactReplay.CURRENT_VERSION) shouldBe
                ReplayFingerprint.of(liveFinal, CompactReplay.CURRENT_VERSION)
        }
    }
}
