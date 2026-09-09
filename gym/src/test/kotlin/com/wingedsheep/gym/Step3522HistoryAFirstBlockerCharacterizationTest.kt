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
import com.wingedsheep.engine.core.PermanentsSacrificedEvent
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.contract.PerspectiveEventClassification
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventUnsupportedReason
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

private data class Step3522ProjectionFact(
    val rawEventType: String,
    val family: PerspectiveEventFamily?,
    val disposition: PerspectiveEventDisposition,
    val reason: PerspectiveEventUnsupportedReason?,
)

/** Crossing regression for the former History-A blocker at locked Step 3522. */
class Step3522HistoryAFirstBlockerCharacterizationTest : FunSpec({
    test("crosses the former Step-3522 History-A blocker for both perspectives") {
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
            semanticEpisodeId = "step-3522-history-a-first-blocker-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var step3522Reached = false
        var crossingBeforeState: GameState? = null
        var crossingAfterState: GameState? = null
        var crossingEvents: List<GameEvent> = emptyList()

        while (!observation.terminated && !observation.truncated && !step3522Reached) {
            val beforeState = environment.state
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
                            response = step3522DecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$successfulChoices: $choice")
                } as TrainingObservation
                successfulChoices++
                if (environment.stepCount == 3_522 &&
                    environment.lastStepEvents.any { it is PermanentsSacrificedEvent }
                ) {
                    step3522Reached = true
                    crossingBeforeState = beforeState
                    crossingAfterState = environment.state
                    crossingEvents = environment.lastStepEvents.toList()
                }
            } catch (exception: HistoryDOperationException) {
                throw AssertionError(
                    "History-D failed before the former Step-3522 crossing",
                    exception,
                )
            }
        }

        val beforeState = checkNotNull(crossingBeforeState)
        val afterState = checkNotNull(crossingAfterState)
        val eventsAtCrossing = crossingEvents
        step3522Reached shouldBe true
        successfulChoices shouldBe 3_522
        environment.stepCount shouldBe 3_522
        eventsAtCrossing.map { it::class.simpleName ?: "UnknownGameEvent" } shouldBe listOf(
            "TappedEvent",
            "TappedEvent",
            "ManaSpentEvent",
            "TappedEvent",
            "PermanentsSacrificedEvent",
            "ZoneChangeEvent",
            "AbilityActivatedEvent",
        )

        val expectedFacts = listOf(
            Step3522ProjectionFact(
                rawEventType = "TappedEvent",
                family = PerspectiveEventFamily.TAPPED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step3522ProjectionFact(
                rawEventType = "TappedEvent",
                family = PerspectiveEventFamily.TAPPED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step3522ProjectionFact(
                rawEventType = "ManaSpentEvent",
                family = PerspectiveEventFamily.MANA_SPENT,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step3522ProjectionFact(
                rawEventType = "TappedEvent",
                family = PerspectiveEventFamily.TAPPED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step3522ProjectionFact(
                rawEventType = "PermanentsSacrificedEvent",
                family = PerspectiveEventFamily.PERMANENTS_SACRIFICED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step3522ProjectionFact(
                rawEventType = "ZoneChangeEvent",
                family = PerspectiveEventFamily.ZONE_CHANGED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            Step3522ProjectionFact(
                rawEventType = "AbilityActivatedEvent",
                family = PerspectiveEventFamily.ABILITY_ACTIVATED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
        )

        val projector = com.wingedsheep.gym.contract.PerspectiveEventProjector(registry)
        val perspectiveFacts = environment.playerIds.map { perspectivePlayerId ->
            val projection = projector.project(
                events = eventsAtCrossing,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = beforeState,
                afterState = afterState,
            )
            projection.isComplete shouldBe true
            projectionFacts(projection)
        }
        perspectiveFacts.forEach { it shouldBe expectedFacts }
        perspectiveFacts.distinct().size shouldBe 1

        expectedFacts.none {
            it.disposition == PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY
        } shouldBe true

        println(
            "STEP3522_HISTORY_A_CHARACTERIZATION " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "rawEvents=${eventsAtCrossing.map { it::class.simpleName ?: "UnknownGameEvent" }} " +
                "perspectives=${perspectiveFacts.size} " +
                "firstUnsupportedRawEvent=NONE " +
                "aReason=NONE",
        )
    }
})

private fun projectionFacts(
    projection: PerspectiveEventProjectionResult,
): List<Step3522ProjectionFact> {
    var emittedOrdinal = 0
    return projection.classifications.map { classification: PerspectiveEventClassification ->
        val family = if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
            projection.batch.entries[emittedOrdinal++].eventFamily
        } else {
            null
        }
        Step3522ProjectionFact(
            rawEventType = classification.rawEventType,
            family = family,
            disposition = classification.disposition,
            reason = classification.reason,
        )
    }
}

private fun step3522DecisionResponse(
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
