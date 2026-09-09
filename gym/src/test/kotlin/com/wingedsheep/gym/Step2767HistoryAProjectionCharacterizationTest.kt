package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

private data class Step2767AClassification(
    val rawEventType: String,
    val eventFamily: PerspectiveEventFamily?,
    val disposition: PerspectiveEventDisposition,
    val reason: PerspectiveEventUnsupportedReason?,
)

/** Test-only characterization of the first post-Step-128 History-A projection gap. */
class Step2767HistoryAProjectionCharacterizationTest : FunSpec({
    test("pins the first incomplete History-A projection for both perspectives") {
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
            semanticEpisodeId = "step-2767-history-a-projection-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var failure: HistoryDOperationException? = null
        var failingRawEventTypes: List<String> = emptyList()

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
                            response = step2767DecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$successfulChoices: $choice")
                } as TrainingObservation
                successfulChoices++
            } catch (exception: HistoryDOperationException) {
                failure = exception
                failingRawEventTypes = environment.lastStepEvents.map {
                    it::class.simpleName ?: "UnknownGameEvent"
                }
            }
        }

        val historyDFailure = checkNotNull(failure)
        successfulChoices shouldBe 2_766
        environment.stepCount shouldBe 2_767
        historyDFailure.failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
        failingRawEventTypes shouldBe listOf(
            "ResolvedEvent",
            "ZoneChangeEvent",
            "AbilityFizzledEvent",
        )

        val expected = listOf(
            Step2767AClassification(
                rawEventType = "ResolvedEvent",
                eventFamily = PerspectiveEventFamily.RESOLVED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step2767AClassification(
                rawEventType = "ZoneChangeEvent",
                eventFamily = PerspectiveEventFamily.ZONE_CHANGED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step2767AClassification(
                rawEventType = "AbilityFizzledEvent",
                eventFamily = null,
                disposition = PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
                reason = PerspectiveEventUnsupportedReason.UNCHARACTERIZED,
            ),
        )

        val matrices = environment.playerIds.map { perspectivePlayerId ->
            val projection = checkNotNull(gym.lastCommittedPerspectiveEventProjection(perspectivePlayerId))
            projection.isComplete shouldBe false
            projection shouldHaveExpectedClassifications expected
            projection.classificationSummary()
        }
        matrices shouldHaveSize 2
        matrices[0] shouldBe expected
        matrices[1] shouldBe expected

        println(
            "STEP2767_HISTORY_A_CHARACTERIZATION " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "failure=${historyDFailure.failure.code} " +
                "rawEvents=$failingRawEventTypes " +
                "perspectives=$matrices",
        )
    }
})

private fun PerspectiveEventProjectionResult.classificationSummary(): List<Step2767AClassification> {
    var emittedEntryIndex = 0
    return classifications.map { classification ->
        val eventFamily = if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
            batch.entries[emittedEntryIndex++].eventFamily
        } else {
            null
        }
        Step2767AClassification(
            rawEventType = classification.rawEventType,
            eventFamily = eventFamily,
            disposition = classification.disposition,
            reason = classification.reason,
        )
    }
}

private infix fun PerspectiveEventProjectionResult.shouldHaveExpectedClassifications(
    expected: List<Step2767AClassification>,
) {
    classificationSummary() shouldBe expected
}

private fun step2767DecisionResponse(
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
