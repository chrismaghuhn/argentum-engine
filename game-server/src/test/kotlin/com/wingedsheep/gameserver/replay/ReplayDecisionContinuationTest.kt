package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * Decision-boundary proof for A6: the replay must reproduce both the paused decision position and
 * the continuation after the recorded response, at the same A4 observation boundary used by Gym.
 */
class ReplayDecisionContinuationTest : ScenarioTestBase() {

    private val choiceCard = card("A6 Replay Choice") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Choose a number from 1 to 2. You gain 1 life."
        spell {
            effect = Effects.ChooseNumberThen(
                then = Effects.GainLife(1),
                minValue = 1,
                maxValue = 2,
                prompt = "Choose a number from 1 to 2",
            )
        }
    }

    private data class ObservationBoundary(
        val structural: TrainingObservation,
        val stateDigest: String,
        val semanticEngineActions: List<LegalAction>,
        val semanticObservationActions: List<LegalActionView>,
    )

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun observationBoundary(
        state: GameState,
        perspectivePlayerId: EntityId,
        observationBuilder: ObservationBuilder,
        legalActionEnumerator: LegalActionEnumerator,
    ): ObservationBoundary {
        val actor = state.pendingDecision?.playerId ?: state.priorityPlayerId
        val legalActions = if (state.pendingDecision == null && actor != null) {
            legalActionEnumerator.enumerate(state, actor, EnumerationMode.ACTIONS_ONLY)
        } else {
            emptyList()
        }
        val observation = observationBuilder.build(
            state = state,
            perspectivePlayerId = perspectivePlayerId,
            legalActions = legalActions,
        ).observation as TrainingObservation
        val normalizedWithoutDigest = observation.copy(
            pendingDecision = observation.pendingDecision?.copy(decisionId = "D0"),
            stateDigest = "",
        )
        val normalized = normalizedWithoutDigest.copy(
            stateDigest = StateDigest.compute(normalizedWithoutDigest),
        )
        return ObservationBoundary(
            structural = normalized.copy(legalActions = emptyList(), stateDigest = ""),
            stateDigest = normalized.stateDigest,
            semanticEngineActions = legalActions
                .map { it.copy(description = "") }
                .sortedBy { it.toString() },
            semanticObservationActions = observation.legalActions
                .map { it.copy(actionId = 0, description = "") }
                .sortedBy { it.toString() },
        )
    }

    private fun awaitP1MainPriority(session: GameSession, p1: EntityId) {
        repeat(80) {
            val state = session.getStateForTesting().shouldNotBeNull()
            if (state.activePlayerId == p1 &&
                state.priorityPlayerId == p1 &&
                state.step == com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN
            ) return
            state.priorityPlayerId?.let { session.executeAutoPass(it) }
        }
        error("p1 never reached precombat main priority")
    }

    private fun replayFrom(
        session: GameSession,
        snapshot: ReplayRecordingSnapshot,
    ): CompactReplay = CompactReplay(
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

    init {
        test("replay preserves a pending decision, its candidate set, and its continuation") {
            cardRegistry.register(choiceCard)

            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val p1 = EntityId.of("decision-p1")
            val p2 = EntityId.of("decision-p2")
            session.addPlayer(PlayerSession(mockWs("decision-ws1"), p1, "Alice"), mapOf(choiceCard.name to 40))
            session.addPlayer(PlayerSession(mockWs("decision-ws2"), p2, "Bob"), mapOf(choiceCard.name to 40))
            session.startGame()
            session.keepHand(p1)
            session.keepHand(p2)
            awaitP1MainPriority(session, p1)

            val liveBeforeCast = session.getStateForTesting().shouldNotBeNull()
            val choiceCardId = liveBeforeCast.getHand(p1).first { entityId ->
                liveBeforeCast.getEntity(entityId)?.get<CardComponent>()?.name == choiceCard.name
            }
            val cast = session.getLegalActions(p1)
                .map { it.action }
                .filterIsInstance<CastSpell>()
                .first { it.cardId == choiceCardId }
            (session.executeAction(p1, cast) !is GameSession.ActionResult.Failure) shouldBe true

            var passes = 0
            while (session.getStateForTesting().shouldNotBeNull().pendingDecision == null && passes++ < 10) {
                val priority = session.getStateForTesting().shouldNotBeNull().priorityPlayerId
                    ?: error("stack resolution lost priority before the choice decision")
                session.executeAutoPass(priority)
            }

            val livePending = session.getStateForTesting().shouldNotBeNull()
            val pendingDecision = livePending.pendingDecision
            pendingDecision.shouldNotBeNull()
            val chosenDecision = pendingDecision as? ChooseNumberDecision
                ?: error("expected the replay test card to pause for ChooseNumberDecision")
            val pendingActionCount = session.getRecordedActions().size

            val observationBuilder = ObservationBuilder(cardRegistry = cardRegistry)
            val legalActionEnumerator = LegalActionEnumerator.create(cardRegistry)
            val livePendingBoundary = observationBoundary(
                livePending,
                p1,
                observationBuilder,
                legalActionEnumerator,
            )

            val pendingSnapshot = session.replayRecordingSnapshot().shouldNotBeNull()
            val fullPrefixReplay = replayFrom(session, pendingSnapshot).copy(checkpoints = emptyList())
            val reconstructedPending = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(fullPrefixReplay, pendingActionCount)
                .shouldNotBeNull()

            ReplayFingerprint.of(reconstructedPending, 3) shouldBe ReplayFingerprint.of(livePending, 3)
            val reconstructedPendingBoundary = observationBoundary(
                reconstructedPending,
                p1,
                observationBuilder,
                legalActionEnumerator,
            )
            livePendingBoundary.structural shouldBe reconstructedPendingBoundary.structural
            livePendingBoundary.stateDigest shouldBe reconstructedPendingBoundary.stateDigest
            livePendingBoundary.semanticEngineActions shouldBe reconstructedPendingBoundary.semanticEngineActions
            livePendingBoundary.semanticObservationActions shouldBe
                reconstructedPendingBoundary.semanticObservationActions
            livePendingBoundary.semanticObservationActions.shouldNotBeEmpty()

            session.executeAction(
                p1,
                SubmitDecision(p1, NumberChosenResponse(chosenDecision.id, 1)),
            )
                .let { (it !is GameSession.ActionResult.Failure) shouldBe true }

            val liveFinal = session.getStateForTesting().shouldNotBeNull()
            val finalSnapshot = session.replayRecordingSnapshot().shouldNotBeNull()
            val replay = replayFrom(session, finalSnapshot)
            replay.actions.last() shouldBe SubmitDecision(
                p1,
                NumberChosenResponse(chosenDecision.id, 1),
            )

            val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(replay)
            reconstructed.fidelity shouldBe ReplayFidelity.EXACT
            val reconstructedFinal = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(replay, replay.actions.size)
                .shouldNotBeNull()
            ReplayFingerprint.of(reconstructedFinal, 3) shouldBe ReplayFingerprint.of(liveFinal, 3)

            val liveFinalBoundary = observationBoundary(liveFinal, p1, observationBuilder, legalActionEnumerator)
            val reconstructedFinalBoundary = observationBoundary(
                reconstructedFinal,
                p1,
                observationBuilder,
                legalActionEnumerator,
            )
            liveFinalBoundary.structural shouldBe reconstructedFinalBoundary.structural
            liveFinalBoundary.stateDigest shouldBe reconstructedFinalBoundary.stateDigest
            liveFinalBoundary.semanticEngineActions shouldBe reconstructedFinalBoundary.semanticEngineActions
            liveFinalBoundary.semanticObservationActions shouldBe
                reconstructedFinalBoundary.semanticObservationActions
        }
    }
}
