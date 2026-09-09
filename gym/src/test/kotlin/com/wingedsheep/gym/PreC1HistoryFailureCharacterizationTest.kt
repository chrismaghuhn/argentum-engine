package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression characterization for the former CardCycledEvent full-history blocker on the locked
 * seed-0 path. It records only public event class names and counts; it does not expose state or
 * card data. The bounded path is expected to encounter a later, unrelated full-history failure.
 */
class PreC1HistoryFailureCharacterizationTest : FunSpec({
    test("passes the former CardCycledEvent blocker before a later full-history failure") {
        val registry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }
        fun lockedDeck(fileName: String): List<String> = Files.readAllLines(
            repositoryRoot.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName),
        )
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }

        val akiri = lockedDeck("akiri-v0.1.txt")
        val chevill = lockedDeck("chevill-v0.1.txt")
        val config = GameConfig(
            players = listOf(
                com.wingedsheep.engine.core.PlayerConfig(
                    name = "Akiri",
                    deck = Deck(akiri.drop(1)),
                    startingLife = 40,
                    commanderCardName = akiri.first(),
                ),
                com.wingedsheep.engine.core.PlayerConfig(
                    name = "Chevill",
                    deck = Deck(chevill.drop(1)),
                    startingLife = 40,
                    commanderCardName = chevill.first(),
                ),
            ),
            startingHandSize = 7,
            skipMulligans = true,
            startingPlayerIndex = 0,
            format = Format.Commander(),
            seed = 0L,
        )
        val environment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        var observation = gym.reset(
            gameConfig = config,
            maxSteps = 4_000,
            semanticEpisodeId = "pre-c1-history-failure-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var choices = 0
        var cardCycledProjection: PerspectiveEventProjectionResult? = null
        var cardCycledChoices: Int? = null
        var cardCycledStep: Int? = null
        var failure: HistoryDOperationException? = null

        while (!observation.terminated && !observation.truncated && failure == null) {
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            try {
                observation = when (choice) {
                    is SemanticChoice.Action -> {
                        if (choice.payload == null) {
                            gym.step(choice.actionId).observation
                        } else {
                            gym.step(choice.actionId, choice.payload).observation
                        }
                    }

                    is SemanticChoice.Structured -> {
                        val pending = checkNotNull(observation.pendingDecision)
                        val decisionId = checkNotNull(pending.decisionId)
                        gym.submitDecision(
                            response = historyFailureDecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$choices: $choice")
                } as TrainingObservation
                choices++
                if (cardCycledProjection == null &&
                    environment.lastStepEvents.any { it is CardCycledEvent }
                ) {
                    cardCycledProjection = gym.lastCommittedPerspectiveEventProjection(
                        environment.playerIds.first(),
                    )
                    cardCycledChoices = choices
                    cardCycledStep = environment.stepCount
                }
            } catch (exception: HistoryDOperationException) {
                failure = exception
            }
        }

        val formerBlockerProjection = cardCycledProjection
            ?: error("The locked path did not reach the former CardCycledEvent blocker")
        val laterFailure = failure
            ?: error("The bounded locked path unexpectedly completed without a later failure")
        val partialHistory = gym.perspectiveHistory(environment.playerIds.first())
        val partialHistoryCanonicalBytes = partialHistory.canonicalJson().toByteArray(Charsets.UTF_8).size
        val lastProjectionDiagnostics = gym
            .lastCommittedPerspectiveEventProjection(environment.playerIds.first())
            ?.diagnostics
            .orEmpty()
        cardCycledChoices shouldBe 93
        cardCycledStep shouldBe 93
        formerBlockerProjection.isComplete shouldBe true
        formerBlockerProjection.batch.entries.any {
            it.eventFamily == PerspectiveEventFamily.CARD_CYCLED
        } shouldBe true
        formerBlockerProjection.diagnostics.any {
            it.rawEventType == CardCycledEvent::class.simpleName
        } shouldBe false
        partialHistory.entries.any {
            it.eventFamily == PerspectiveEventFamily.CARD_CYCLED
        } shouldBe true
        (choices > 92) shouldBe true
        (environment.stepCount > 93) shouldBe true
        println(
            "PRE_C1_HISTORY_AFTER_CARDCYCLED " +
                "choices=$choices " +
                "stepCount=${environment.stepCount} " +
                "laterFailure=${laterFailure.message} " +
                "lastEventTypes=${environment.lastStepEvents.map { it::class.simpleName }} " +
                "lastEventCount=${environment.lastStepEvents.size} " +
                "projectionDiagnostics=$lastProjectionDiagnostics " +
                "partialHistoryEntries=${partialHistory.entries.size} " +
                "partialHistoryCanonicalBytes=$partialHistoryCanonicalBytes " +
                "partialHistoryReferences=${partialHistory.entries.sumOf { it.references.size }} " +
                "partialHistoryRelations=${partialHistory.entries.sumOf { it.relations.size }}",
        )
    }
})

private fun historyFailureDecisionResponse(
    decisionId: String,
    selection: SemanticDecision,
): DecisionResponse = when (selection) {
    is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
    is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
    is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Distribution -> DistributionResponse(decisionId, selection.selected)
    is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
    is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
    is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Replacement -> ReplacementChosenResponse(decisionId, selection.from, selection.to)
    is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
    is SemanticDecision.Damage -> CombatResolutionResponse(
        decisionId = decisionId,
        edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
    )
    is SemanticDecision.Payment -> selection.toDecisionResponse(decisionId)
}
