package com.wingedsheep.gym

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.LandPlayedEvent
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerResult
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerV1
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

private data class Step1141Failure(
    val successfulChoices: Int,
    val committedStep: Int,
    val failureCode: HistoryCFailureCode,
    val before: GameState,
    val after: GameState,
    val rawEvents: List<GameEvent>,
    val projections: List<PerspectiveEventProjectionResult>,
    val cardCycledChoices: Int?,
    val cardCycledStep: Int?,
    val emptyAttackersChoices: Int?,
    val emptyAttackersStep: Int?,
)

/** Test-only characterization of the real post-SAME_INCARNATION Step-1141 boundary. */
class AbilityTriggeredStep1141EndpointCharacterizationTest : FunSpec({
    fun registry(): CardRegistry = CardRegistry().apply {
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
        }
    }

    fun lockedDeck(repositoryRoot: Path, fileName: String): List<String> = Files.readAllLines(
        repositoryRoot.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName),
    )
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }

    fun runLockedFailure(): Step1141Failure {
        val cardRegistry = registry()
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }
        val akiri = lockedDeck(repositoryRoot, "akiri-v0.1.txt")
        val chevill = lockedDeck(repositoryRoot, "chevill-v0.1.txt")
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
            cardRegistry = cardRegistry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        var observation = gym.reset(
            gameConfig = config,
            maxSteps = 4_000,
            semanticEpisodeId = "ability-triggered-step-1141-characterization-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var cardCycledChoices: Int? = null
        var cardCycledStep: Int? = null
        var emptyAttackersChoices: Int? = null
        var emptyAttackersStep: Int? = null
        var failure: HistoryDOperationException? = null
        var beforeFailure: GameState? = null
        var failingRawEvents: List<GameEvent> = emptyList()
        var failingProjections: List<PerspectiveEventProjectionResult?> = emptyList()

        while (!observation.terminated && !observation.truncated && failure == null) {
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            val beforeChoice = environment.state
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
                            response = endpointStep1141DecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$successfulChoices: $choice")
                } as TrainingObservation
                successfulChoices++
                if (cardCycledChoices == null && environment.lastStepEvents.any { it is CardCycledEvent }) {
                    cardCycledChoices = successfulChoices
                    cardCycledStep = environment.stepCount
                }
                if (emptyAttackersChoices == null && environment.lastStepEvents.any { event ->
                        event is AttackersDeclaredEvent &&
                            event.attackers.isEmpty() &&
                            event.declaredAttacks.isEmpty()
                    }
                ) {
                    emptyAttackersChoices = successfulChoices
                    emptyAttackersStep = environment.stepCount
                }
            } catch (exception: HistoryDOperationException) {
                failure = exception
                beforeFailure = beforeChoice
                failingRawEvents = environment.lastStepEvents.toList()
                failingProjections = environment.playerIds.map { playerId ->
                    gym.lastCommittedPerspectiveEventProjection(playerId)
                }
            }
        }

        val historyDFailure = checkNotNull(failure)
        return Step1141Failure(
            successfulChoices = successfulChoices,
            committedStep = environment.stepCount,
            failureCode = historyDFailure.failure.code,
            before = checkNotNull(beforeFailure),
            after = environment.state,
            rawEvents = failingRawEvents,
            projections = failingProjections.map(::checkNotNull),
            cardCycledChoices = cardCycledChoices,
            cardCycledStep = cardCycledStep,
            emptyAttackersChoices = emptyAttackersChoices,
            emptyAttackersStep = emptyAttackersStep,
        )
    }

    fun producerStatus(
        before: GameState,
        after: GameState,
        event: GameEvent,
        sourceStepCount: Int,
    ): String {
        val transition = CommittedRulesTransition(
            beforeState = before,
            afterState = after,
            events = listOf(event),
            sourceStepCount = sourceStepCount,
        )
        val projection = PerspectiveEventProjector(CardRegistry()).project(
            events = transition.events,
            perspectivePlayerId = EntityId.of("p1"),
            beforeState = before,
            afterState = after,
        )
        return when (val result = HistoryCReferenceEnvelopeProducerV1.produce(transition, projection)) {
            is HistoryCReferenceEnvelopeProducerResult.Accepted -> "ACCEPTED"
            is HistoryCReferenceEnvelopeProducerResult.Rejected -> result.failure.code.name
        }
    }

    test("pins the live Step-1141 source lifecycle without choosing an endpoint") {
        val failure = runLockedFailure()
        val rawEventTypes = failure.rawEvents.map { it::class.simpleName ?: "UnknownGameEvent" }
        val zoneChange = failure.rawEvents.filterIsInstance<ZoneChangeEvent>().single()
        val landPlayed = failure.rawEvents.filterIsInstance<LandPlayedEvent>().single()
        val abilityTriggered = failure.rawEvents.filterIsInstance<AbilityTriggeredEvent>().single()

        failure.successfulChoices shouldBe 1_140
        failure.committedStep shouldBe 1_141
        failure.failureCode shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
        failure.cardCycledChoices shouldBe 93
        failure.cardCycledStep shouldBe 93
        failure.emptyAttackersChoices shouldBe 466
        failure.emptyAttackersStep shouldBe 466
        rawEventTypes shouldBe listOf("ZoneChangeEvent", "LandPlayedEvent", "AbilityTriggeredEvent")

        failure.projections.size shouldBe 2
        failure.projections.forEach { projection ->
            projection.isComplete shouldBe true
            projection.classifications.map { it.rawEventType } shouldBe rawEventTypes
            projection.classifications.all { it.disposition == PerspectiveEventDisposition.EMITTED } shouldBe true
        }

        val sourceBeforeWitness = failure.before.hasEntity(abilityTriggered.sourceId) &&
            failure.before.objectIdentityStamps[abilityTriggered.sourceId] != null
        val sourceAfterWitness = failure.after.hasEntity(abilityTriggered.sourceId) &&
            failure.after.objectIdentityStamps[abilityTriggered.sourceId] != null
        val sourceBeforeStamp = failure.before.objectIdentityStamps[abilityTriggered.sourceId]
        val sourceAfterStamp = failure.after.objectIdentityStamps[abilityTriggered.sourceId]
        val sameIncarnation = sourceBeforeStamp != null &&
            sourceAfterStamp != null &&
            sourceBeforeStamp == sourceAfterStamp
        val changedIncarnation = sourceBeforeStamp != null &&
            sourceAfterStamp != null &&
            sourceBeforeStamp != sourceAfterStamp
        val sourceMatchesZoneChange = abilityTriggered.sourceId == zoneChange.entityId
        val sourceMatchesLandPlayed = abilityTriggered.sourceId == landPlayed.cardId
        val abilityTriggeredC = producerStatus(
            before = failure.before,
            after = failure.after,
            event = abilityTriggered,
            sourceStepCount = failure.committedStep,
        )
        val zoneChangeC = producerStatus(
            before = failure.before,
            after = failure.after,
            event = zoneChange,
            sourceStepCount = failure.committedStep,
        )
        val landPlayedC = producerStatus(
            before = failure.before,
            after = failure.after,
            event = landPlayed,
            sourceStepCount = failure.committedStep,
        )
        val sameIncarnationConstraintCausesFailure = changedIncarnation &&
            abilityTriggeredC == HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA.name

        println(
            "ABILITY_TRIGGERED_STEP1141_CHARACTERIZATION " +
                "successfulChoices=${failure.successfulChoices} " +
                "committedStep=${failure.committedStep} " +
                "failure=${failure.failureCode} " +
                "rawEvents=$rawEventTypes " +
                "zoneFrom=${zoneChange.fromZone?.name ?: "NONE"} " +
                "zoneTo=${zoneChange.toZone.name} " +
                "sourceBeforeWitness=$sourceBeforeWitness " +
                "sourceAfterWitness=$sourceAfterWitness " +
                "sameIncarnation=$sameIncarnation " +
                "changedIncarnation=$changedIncarnation " +
                "sourceMatchesZoneChange=$sourceMatchesZoneChange " +
                "sourceMatchesLandPlayed=$sourceMatchesLandPlayed " +
                "zoneChangeC=$zoneChangeC " +
                "landPlayedC=$landPlayedC " +
                "abilityTriggeredC=$abilityTriggeredC " +
                "currentSameIncarnationConstraintCausesFailure=$sameIncarnationConstraintCausesFailure",
        )
    }
})

private fun endpointStep1141DecisionResponse(
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
