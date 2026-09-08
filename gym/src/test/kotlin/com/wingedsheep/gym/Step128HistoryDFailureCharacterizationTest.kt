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

/**
 * RED characterization for the first History-D failure after the accepted Step-111 crossing.
 * It records only public event class, family, disposition, reason, and bounded counters.
 */
class Step128HistoryDFailureCharacterizationTest : FunSpec({
    test("pins the first post-Step-111 History-D failure") {
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
            semanticEpisodeId = "step-128-history-d-failure-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var cardCycledChoices: Int? = null
        var cardCycledStep: Int? = null
        var step111Passed = false
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
                            response = step128DecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$successfulChoices")
                } as TrainingObservation
                successfulChoices++
                if (cardCycledChoices == null &&
                    environment.lastStepEvents.any { it is CardCycledEvent }
                ) {
                    cardCycledChoices = successfulChoices
                    cardCycledStep = environment.stepCount
                }
                if (environment.stepCount >= 111) {
                    step111Passed = true
                }
            } catch (exception: HistoryDOperationException) {
                failure = exception
            }
        }

        val historyDFailure = failure
            ?: error("The bounded locked path unexpectedly completed without a History-D failure")
        val rawEvents = environment.lastStepEvents.map { it::class.simpleName ?: "UnknownGameEvent" }
        val expectedRawEvents = listOf(
            "ManaSpentEvent",
            "SpellCastEvent",
            "CommitCrimeEvent",
            "TargetsChosenEvent",
            "BecomesTargetEvent",
        )
        val expectedDispositions = listOf(
            PerspectiveEventDisposition.EMITTED,
            PerspectiveEventDisposition.EMITTED,
            PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
            PerspectiveEventDisposition.EMITTED,
            PerspectiveEventDisposition.EMITTED,
        )
        val expectedReasons = listOf(
            null,
            null,
            PerspectiveEventUnsupportedReason.REQUIRES_BOTH_B_AND_C,
            null,
            null,
        )
        val expectedFamilies = listOf(
            PerspectiveEventFamily.MANA_SPENT,
            PerspectiveEventFamily.SPELL_CAST,
            PerspectiveEventFamily.TARGETS_CHOSEN,
            PerspectiveEventFamily.BECAME_TARGET,
        )

        successfulChoices shouldBe 127
        environment.stepCount shouldBe 128
        cardCycledChoices shouldBe 93
        cardCycledStep shouldBe 93
        step111Passed shouldBe true
        historyDFailure.failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
        rawEvents shouldBe expectedRawEvents

        environment.playerIds.forEach { perspectivePlayerId ->
            val projection = checkNotNull(gym.lastCommittedPerspectiveEventProjection(perspectivePlayerId))
            projection.isComplete shouldBe false
            projection.classifications.map { it.rawEventType } shouldBe expectedRawEvents
            projection.classifications.map { it.disposition } shouldBe expectedDispositions
            projection.classifications.map { it.reason } shouldBe expectedReasons
            projection.batch.entries.map { it.eventFamily } shouldBe expectedFamilies
            projection.diagnostics.map { it.rawEventType to it.reason } shouldBe listOf(
                "CommitCrimeEvent" to PerspectiveEventUnsupportedReason.REQUIRES_BOTH_B_AND_C,
            )
        }

        println(
            "STEP128_FAILURE " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "historyDFailure=${historyDFailure.failure.code} " +
                "rawEvents=${rawEvents.joinToString(",")} " +
                "historyA=" + environment.playerIds.joinToString("|") { perspectivePlayerId ->
                    val projection = checkNotNull(gym.lastCommittedPerspectiveEventProjection(perspectivePlayerId))
                    projection.classifications.joinToString(",") { classification ->
                        "${classification.rawEventType}:${classification.disposition}:" +
                            (classification.reason?.name ?: "NONE")
                    }
                },
        )
    }
})

private fun step128DecisionResponse(
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
