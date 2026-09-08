package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.core.CardsSelectedResponse
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
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventUnsupportedReason
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** Safe, test-only characterization of the first History-D failure after the accepted closures. */
class Step457HistoryDFailureCharacterizationTest : FunSpec({
    test("pins the first post-Step-128 History-D failure") {
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
            maxSteps = 2_000,
            semanticEpisodeId = "step-457-history-d-failure-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var cardCycledChoices: Int? = null
        var cardCycledStep: Int? = null
        var failure: HistoryDOperationException? = null
        var failingRawEventTypes: List<String> = emptyList()
        var failingProjections: List<PerspectiveEventProjectionResult?> = emptyList()

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
                            response = step457DecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$successfulChoices: $choice")
                } as TrainingObservation
                successfulChoices++
                if (cardCycledChoices == null &&
                    environment.lastStepEvents.any { it is CardCycledEvent }
                ) {
                    cardCycledChoices = successfulChoices
                    cardCycledStep = environment.stepCount
                }
            } catch (exception: HistoryDOperationException) {
                failure = exception
                failingRawEventTypes = environment.lastStepEvents.map { it::class.simpleName ?: "UnknownGameEvent" }
                failingProjections = environment.playerIds.map { playerId ->
                    gym.lastCommittedPerspectiveEventProjection(playerId)
                }
            }
        }

        val historyDFailure = checkNotNull(failure)
        successfulChoices shouldBe 456
        environment.stepCount shouldBe 457
        cardCycledChoices shouldBe 93
        cardCycledStep shouldBe 93
        historyDFailure.failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
        failingRawEventTypes shouldBe listOf(
            "StatsModifiedEvent",
            "KeywordGrantedEvent",
            "KeywordGrantedEvent",
            "AbilityResolvedEvent",
        )

        val projections = failingProjections.map(::checkNotNull)
        projections.size shouldBe 2
        projections.forEach { projection ->
            projection.isComplete shouldBe false
            projection.classifications.map { it.rawEventType } shouldBe failingRawEventTypes
            projection.classifications.map { it.disposition } shouldBe listOf(
                PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
                PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
                PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
                PerspectiveEventDisposition.EMITTED,
            )
            projection.classifications.map { it.reason } shouldBe listOf(
                PerspectiveEventUnsupportedReason.REQUIRES_SEMANTIC_REFERENCE_C,
                PerspectiveEventUnsupportedReason.REQUIRES_SEMANTIC_REFERENCE_C,
                PerspectiveEventUnsupportedReason.REQUIRES_SEMANTIC_REFERENCE_C,
                null,
            )
            projection.batch.entries.map { it.eventFamily } shouldBe listOf(
                PerspectiveEventFamily.ABILITY_RESOLVED,
            )
        }

        println(
            "STEP457_CHARACTERIZATION " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "failure=${historyDFailure.failure.code} " +
                "rawEvents=$failingRawEventTypes " +
                "perspectives=" + projections.map { projection ->
                    projection.classifications.map { classification ->
                        "${classification.rawEventType}:${classification.disposition}:${classification.reason}"
                    }
                },
        )
    }
})

private fun step457DecisionResponse(
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
