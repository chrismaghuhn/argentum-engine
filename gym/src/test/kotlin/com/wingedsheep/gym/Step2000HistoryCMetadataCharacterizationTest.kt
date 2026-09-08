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
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
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

/** Test-only characterization of the first post-Empty-Attackers C metadata blocker. */
class Step2000HistoryCMetadataCharacterizationTest : FunSpec({
    test("pins the C metadata blocker beyond the maxSteps boundary") {
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
            semanticEpisodeId = "step-2000-history-c-metadata-seed-0",
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
                            response = step2000DecisionResponse(decisionId, choice.selection),
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
        val before = checkNotNull(beforeFailure)
        val after = environment.state
        val projections = failingProjections.map(::checkNotNull)
        val rawEventTypes = failingRawEvents.map { it::class.simpleName ?: "UnknownGameEvent" }

        successfulChoices shouldBe 1999
        environment.stepCount shouldBe 2_000
        cardCycledChoices shouldBe 93
        cardCycledStep shouldBe 93
        emptyAttackersChoices shouldBe 466
        emptyAttackersStep shouldBe 466
        historyDFailure.failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
        rawEventTypes shouldBe listOf(
            "ZoneChangeEvent",
            "ZoneChangeEvent",
            "ResolvedEvent",
            "AbilityTriggeredEvent",
        )
        projections.size shouldBe 2
        projections.forEach { projection ->
            projection.isComplete shouldBe true
            projection.classifications.map { it.rawEventType } shouldBe rawEventTypes
            projection.classifications.all { it.disposition == PerspectiveEventDisposition.EMITTED } shouldBe true
        }

        data class CProbe(
            val status: String,
            val candidateCount: Int,
            val relationCount: Int,
        )

        fun cProbe(
            events: List<GameEvent>,
            projection: PerspectiveEventProjectionResult,
        ): CProbe = when (
            val result = HistoryCReferenceEnvelopeProducerV1.produce(
                transition = CommittedRulesTransition(
                    beforeState = before,
                    afterState = after,
                    events = events,
                    sourceStepCount = environment.stepCount,
                ),
                projection = projection,
            )
        ) {
            is HistoryCReferenceEnvelopeProducerResult.Accepted ->
                CProbe(
                    status = "ACCEPTED",
                    candidateCount = result.envelope.candidates.size,
                    relationCount = result.envelope.relations.size,
                )

            is HistoryCReferenceEnvelopeProducerResult.Rejected -> CProbe(
                status = result.failure.code.name,
                candidateCount = -1,
                relationCount = -1,
            )
        }

        data class EventProbe(
            val eventType: String,
            val family: String,
            val disposition: String,
            val reason: String?,
            val c: CProbe,
        )

        val individualProbes = failingRawEvents.mapIndexed { ordinal, event ->
            val eventProjection = PerspectiveEventProjector(registry).project(
                events = listOf(event),
                perspectivePlayerId = projections.first().batch.perspectivePlayerId,
                beforeState = before,
                afterState = after,
            )
            val classification = eventProjection.classifications.single()
            val eventFamily = eventProjection.batch.entries.single().eventFamily
            EventProbe(
                eventType = "#$ordinal ${event::class.simpleName}",
                family = eventFamily.name,
                disposition = classification.disposition.name,
                reason = classification.reason?.name,
                c = cProbe(listOf(event), eventProjection),
            )
        }
        val prefixProbes = (1..failingRawEvents.size).map { prefixSize ->
            val events = failingRawEvents.take(prefixSize)
            val projection = PerspectiveEventProjector(registry).project(
                events = events,
                perspectivePlayerId = projections.first().batch.perspectivePlayerId,
                beforeState = before,
                afterState = after,
            )
            cProbe(events, projection)
        }

        val fullCProbe = cProbe(failingRawEvents, projections.first())
        fullCProbe.status shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA.name
        individualProbes.size shouldBe 4
        prefixProbes.size shouldBe 4
        individualProbes.map { it.c.status } shouldBe listOf(
            "ACCEPTED",
            "ACCEPTED",
            "ACCEPTED",
            HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA.name,
        )
        individualProbes.take(3).forEach { probe ->
            probe.c.candidateCount shouldBe 1
            probe.c.relationCount shouldBe 0
            probe.disposition shouldBe PerspectiveEventDisposition.EMITTED.name
            probe.reason shouldBe null
        }
        prefixProbes.map { it.status } shouldBe listOf(
            "ACCEPTED",
            "ACCEPTED",
            "ACCEPTED",
            HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA.name,
        )

        val firstBlockingProbe = individualProbes.first { it.c.status != "ACCEPTED" }
        firstBlockingProbe.eventType shouldBe "#3 AbilityTriggeredEvent"
        firstBlockingProbe.family shouldBe "ABILITY_TRIGGERED"
        firstBlockingProbe.disposition shouldBe PerspectiveEventDisposition.EMITTED.name
        firstBlockingProbe.reason shouldBe null
        firstBlockingProbe.c.candidateCount shouldBe -1
        firstBlockingProbe.c.relationCount shouldBe -1

        val abilityTriggeredEvent = failingRawEvents[3] as AbilityTriggeredEvent
        fun hasEventTimeWitness(state: GameState, entityId: EntityId): Boolean =
            state.hasEntity(entityId) && state.objectIdentityStamps[entityId] != null

        val sourceWitnessBefore = hasEventTimeWitness(before, abilityTriggeredEvent.sourceId)
        val sourceWitnessAfter = hasEventTimeWitness(after, abilityTriggeredEvent.sourceId)
        sourceWitnessBefore shouldBe true
        sourceWitnessAfter shouldBe false

        println(
            "STEP2000_CHARACTERIZATION " +
                "maxSteps=4000 " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "failure=${historyDFailure.failure.code} " +
                "rawEvents=$rawEventTypes " +
                "cardCycledChoices=$cardCycledChoices cardCycledStep=$cardCycledStep " +
                "emptyAttackersChoices=$emptyAttackersChoices emptyAttackersStep=$emptyAttackersStep " +
                "individualC=" + individualProbes.joinToString(";") { probe ->
                    "${probe.eventType} A=${probe.family}:${probe.disposition}:${probe.reason} " +
                        "C=${probe.c.status}(candidates=${probe.c.candidateCount},relations=${probe.c.relationCount})"
                } + " " +
                "prefixC=" + prefixProbes.mapIndexed { index, probe ->
                    "prefix=${index + 1} C=${probe.status}(candidates=${probe.candidateCount},relations=${probe.relationCount})"
                }.joinToString(";") + " " +
                "abilityTriggeredSourceWitnessBefore=$sourceWitnessBefore " +
                "abilityTriggeredSourceWitnessAfter=$sourceWitnessAfter " +
                "A=COMPLETE B=NOT_REQUIRED C=CANDIDATE_PRODUCTION D=WRAPPER",
        )
    }
})

private fun step2000DecisionResponse(
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
