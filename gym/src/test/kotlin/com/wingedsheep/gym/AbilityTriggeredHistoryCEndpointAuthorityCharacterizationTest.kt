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
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCIdentityDisclosure
import com.wingedsheep.gym.history.HistoryCObjectWitness
import com.wingedsheep.gym.history.HistoryCOrderAuthority
import com.wingedsheep.gym.history.HistoryCOrderProof
import com.wingedsheep.gym.history.HistoryCReferenceAuthority
import com.wingedsheep.gym.history.HistoryCReferenceAuthorityResult
import com.wingedsheep.gym.history.HistoryCReferenceCandidateV1
import com.wingedsheep.gym.history.HistoryCReferenceEndpointAuthority
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerResult
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerV1
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeV1
import com.wingedsheep.gym.history.HistoryCReferenceKind
import com.wingedsheep.gym.history.HistoryCReferenceSlot
import com.wingedsheep.gym.history.HistoryCReferenceSlotRole
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.gym.history.PerspectiveAliasRegistryV1
import com.wingedsheep.gym.history.PerspectiveReferenceProjectionResult
import com.wingedsheep.gym.history.PerspectiveReferenceProjectorV1
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

private data class EndpointProducerProbe(
    val status: String,
    val candidateCount: Int,
    val relationCount: Int,
)

private data class LockedEndpointFailure(
    val successfulChoices: Int,
    val committedStep: Int,
    val failureCode: HistoryCFailureCode,
    val rawEvents: List<GameEvent>,
    val projections: List<PerspectiveEventProjectionResult>,
    val cardCycledChoices: Int?,
    val cardCycledStep: Int?,
    val emptyAttackersChoices: Int?,
    val emptyAttackersStep: Int?,
)

/** Test-only characterization of the current AbilityTriggeredEvent endpoint contract. */
class AbilityTriggeredHistoryCEndpointAuthorityCharacterizationTest : FunSpec({
    val perspective = EntityId.of("p1")
    val controller = EntityId.of("p2")
    val source = EntityId.of("trigger-source-runtime-id")

    fun sourceState(present: Boolean, stamp: Long = 1L): GameState = GameState(
        entities = buildMap {
            put(perspective, ComponentContainer.EMPTY)
            put(controller, ComponentContainer.EMPTY)
            if (present) {
                put(
                    source,
                    ComponentContainer.of(
                        CardComponent(
                            cardDefinitionId = "mountain",
                            name = "Mountain",
                            manaCost = ManaCost.ZERO,
                            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                            ownerId = controller,
                        ),
                    ),
                )
            }
        },
        stack = if (present) listOf(source) else emptyList(),
        zones = emptyMap(),
        turnOrder = listOf(perspective, controller),
        objectIdentityStamps = if (present) mapOf(source to stamp) else emptyMap(),
    )

    fun abilityEvent() = AbilityTriggeredEvent(
        sourceId = source,
        sourceName = "private source name",
        controllerId = controller,
        description = "private ability description",
    )

    fun transition(before: GameState, after: GameState, events: List<GameEvent> = listOf(abilityEvent())) =
        CommittedRulesTransition(
            beforeState = before,
            afterState = after,
            events = events,
            sourceStepCount = 1,
        )

    fun aProjection(
        committedTransition: CommittedRulesTransition,
        perspectivePlayerId: EntityId = perspective,
        registry: CardRegistry = CardRegistry(),
    ) = PerspectiveEventProjector(registry).project(
        events = committedTransition.events,
        perspectivePlayerId = perspectivePlayerId,
        beforeState = committedTransition.beforeState,
        afterState = committedTransition.afterState,
    )

    fun producerProbe(
        committedTransition: CommittedRulesTransition,
        perspectivePlayerId: EntityId = perspective,
        registry: CardRegistry = CardRegistry(),
    ): EndpointProducerProbe {
        val projection = aProjection(committedTransition, perspectivePlayerId, registry)
        return when (
            val result = HistoryCReferenceEnvelopeProducerV1.produce(
                transition = committedTransition,
                projection = projection,
            )
        ) {
            is HistoryCReferenceEnvelopeProducerResult.Accepted -> EndpointProducerProbe(
                status = "ACCEPTED",
                candidateCount = result.envelope.candidates.size,
                relationCount = result.envelope.relations.size,
            )

            is HistoryCReferenceEnvelopeProducerResult.Rejected -> EndpointProducerProbe(
                status = result.failure.code.name,
                candidateCount = -1,
                relationCount = -1,
            )
        }
    }

    fun candidate(
        beforeWitness: HistoryCObjectWitness? = null,
        afterWitness: HistoryCObjectWitness? = null,
        endpointAuthority: HistoryCReferenceEndpointAuthority,
    ) = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(
            eventOrdinal = 0,
            role = HistoryCReferenceSlotRole.SOURCE,
            roleOrdinal = 0,
        ),
        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        beforeWitness = beforeWitness,
        afterWitness = afterWitness,
        identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = 0,
        ),
        semanticDescriptor = buildJsonObject { put("type", "object_reference") },
        endpointAuthority = endpointAuthority,
    )

    fun authorityStatus(
        committedTransition: CommittedRulesTransition,
        reference: HistoryCReferenceCandidateV1,
        perspectivePlayerId: EntityId = perspective,
    ): String {
        val projection = aProjection(committedTransition, perspectivePlayerId)
        return when (
            val result = HistoryCReferenceAuthority.validate(
                transition = committedTransition,
                projection = projection,
                envelope = HistoryCReferenceEnvelopeV1(
                    perspectivePlayerId = perspectivePlayerId,
                    candidates = listOf(reference),
                ),
            )
        ) {
            is HistoryCReferenceAuthorityResult.Accepted -> "ACCEPTED"
            is HistoryCReferenceAuthorityResult.Rejected -> result.failure.code.name
        }
    }

    fun projectorStatus(
        committedTransition: CommittedRulesTransition,
        reference: HistoryCReferenceCandidateV1,
        semanticEpisodeId: String,
    ): String {
        val projection = aProjection(committedTransition)
        val evidence = when (
            val result = HistoryCReferenceAuthority.validate(
                transition = committedTransition,
                projection = projection,
                envelope = HistoryCReferenceEnvelopeV1(
                    perspectivePlayerId = perspective,
                    candidates = listOf(reference),
                ),
            )
        ) {
            is HistoryCReferenceAuthorityResult.Rejected -> return "NOT_REACHED:${result.failure.code.name}"
            is HistoryCReferenceAuthorityResult.Accepted -> result.evidence
        }
        return when (
            val result = PerspectiveReferenceProjectorV1(CardRegistry()).project(
                semanticEpisodeId = semanticEpisodeId,
                perspectivePlayerId = perspective,
                transition = committedTransition,
                evidence = evidence,
                registry = PerspectiveAliasRegistryV1(
                    semanticEpisodeId = semanticEpisodeId,
                    perspectivePlayerId = perspective,
                ),
            )
        ) {
            is PerspectiveReferenceProjectionResult.Accepted -> "ACCEPTED"
            is PerspectiveReferenceProjectionResult.Rejected -> result.failure.code.name
        }
    }

    test("characterizes producer and raw-authority endpoint cases") {
        val cases = listOf(
            "BEFORE_ONLY" to transition(
                before = sourceState(present = true, stamp = 11L),
                after = sourceState(present = false),
            ),
            "AFTER_ONLY" to transition(
                before = sourceState(present = false),
                after = sourceState(present = true, stamp = 12L),
            ),
            "BOTH_SAME_INCARNATION" to transition(
                before = sourceState(present = true, stamp = 13L),
                after = sourceState(present = true, stamp = 13L),
            ),
            "CHANGED_INCARNATION" to transition(
                before = sourceState(present = true, stamp = 14L),
                after = sourceState(present = true, stamp = 15L),
            ),
        )

        val producerResults = cases.map { (name, committedTransition) ->
            name to producerProbe(committedTransition)
        }
        producerResults.map { it.second.status } shouldBe listOf(
            "ACCEPTED",
            "ACCEPTED",
            "ACCEPTED",
            HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA.name,
        )
        producerResults.take(3).forEach { (_, result) ->
            result.candidateCount shouldBe 1
            result.relationCount shouldBe 0
        }

        val beforeOnly = cases.first().second
        val afterOnly = cases[1].second
        val bothSame = cases[2].second
        val changed = cases[3].second
        val beforeWitness = HistoryCObjectWitness(source, 11L)
        val afterWitness = HistoryCObjectWitness(source, 12L)

        authorityStatus(
            beforeOnly,
            candidate(
                beforeWitness = beforeWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
        ) shouldBe "ACCEPTED"

        authorityStatus(
            beforeOnly,
            candidate(
                beforeWitness = beforeWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.UNSPECIFIED,
            ),
        ) shouldBe "ACCEPTED"

        authorityStatus(
            afterOnly,
            candidate(
                afterWitness = afterWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
            ),
        ) shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH.name

        authorityStatus(
            afterOnly,
            candidate(
                afterWitness = afterWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
        ) shouldBe "ACCEPTED"

        authorityStatus(
            afterOnly,
            candidate(
                afterWitness = afterWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.UNSPECIFIED,
            ),
        ) shouldBe "ACCEPTED"

        authorityStatus(
            bothSame,
            candidate(
                beforeWitness = HistoryCObjectWitness(source, 13L),
                afterWitness = HistoryCObjectWitness(source, 13L),
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
            ),
        ) shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH.name

        authorityStatus(
            changed,
            candidate(
                beforeWitness = HistoryCObjectWitness(source, 14L),
                afterWitness = HistoryCObjectWitness(source, 15L),
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
        ) shouldBe HistoryCFailureCode.CROSS_INCARNATION_REFERENCE_UNSUPPORTED.name

        projectorStatus(
            beforeOnly,
            candidate(
                beforeWitness = beforeWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
            semanticEpisodeId = "ability-triggered-endpoint-before",
        ) shouldBe "ACCEPTED"

        projectorStatus(
            afterOnly,
            candidate(
                afterWitness = afterWitness,
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
            semanticEpisodeId = "ability-triggered-endpoint-after",
        ) shouldBe "ACCEPTED"

        projectorStatus(
            bothSame,
            candidate(
                beforeWitness = HistoryCObjectWitness(source, 13L),
                afterWitness = HistoryCObjectWitness(source, 13L),
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
            semanticEpisodeId = "ability-triggered-endpoint-same",
        ) shouldBe "ACCEPTED"

        authorityStatus(
            bothSame,
            candidate(
                beforeWitness = HistoryCObjectWitness(source, 13L),
                afterWitness = HistoryCObjectWitness(source, 13L),
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
            ),
        ) shouldBe "ACCEPTED"

        val acceptedAfter = HistoryCReferenceAuthority.validate(
            transition = afterOnly,
            projection = aProjection(afterOnly),
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = perspective,
                candidates = listOf(
                    candidate(
                        afterWitness = afterWitness,
                        endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
                    ),
                ),
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        acceptedAfter.evidence.candidates.single().endpointAuthority shouldBe
            HistoryCReferenceEndpointAuthority.SAME_INCARNATION

        println(
            "ABILITY_TRIGGERED_ENDPOINT_MATRIX " +
                "producer=" + producerResults.joinToString(";") { (name, result) ->
                    "$name:${result.status}(candidates=${result.candidateCount},relations=${result.relationCount})"
                } + " " +
                "rawAuthority=BEFORE_ONLY:ACCEPTED;" +
                "AFTER_ONLY:AFTER_OBJECT:RAW_EVENT_REFERENCE_MISMATCH;" +
                "AFTER_ONLY:SAME_INCARNATION:ACCEPTED;" +
                "BOTH_SAME_INCARNATION:ACCEPTED;" +
                "CHANGED_INCARNATION:CROSS_INCARNATION_REFERENCE_UNSUPPORTED " +
                "projector=BEFORE_ONLY:ACCEPTED;AFTER_ONLY:ACCEPTED;BOTH_SAME_INCARNATION:ACCEPTED " +
                "producerEndpoint=SAME_INCARNATION",
        )
    }

    fun lockedRegistry(): CardRegistry = CardRegistry().apply {
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

    fun lockedFailure(): LockedEndpointFailure {
        val registry = lockedRegistry()
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
            semanticEpisodeId = "ability-triggered-history-c-endpoint-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var cardCycledChoices: Int? = null
        var cardCycledStep: Int? = null
        var emptyAttackersChoices: Int? = null
        var emptyAttackersStep: Int? = null
        var failure: HistoryDOperationException? = null
        var failingRawEvents: List<GameEvent> = emptyList()
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
                            response = endpointDecisionResponse(decisionId, choice.selection),
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
                failingRawEvents = environment.lastStepEvents.toList()
                failingProjections = environment.playerIds.map { playerId ->
                    gym.lastCommittedPerspectiveEventProjection(playerId)
                }
            }
        }

        val historyDFailure = checkNotNull(failure)
        return LockedEndpointFailure(
            successfulChoices = successfulChoices,
            committedStep = environment.stepCount,
            failureCode = historyDFailure.failure.code,
            rawEvents = failingRawEvents,
            projections = failingProjections.map(::checkNotNull),
            cardCycledChoices = cardCycledChoices,
            cardCycledStep = cardCycledStep,
            emptyAttackersChoices = emptyAttackersChoices,
            emptyAttackersStep = emptyAttackersStep,
        )
    }

    test("crosses the former Step-2000 endpoint blocker before the next failure") {
        val failure = lockedFailure()
        val rawEventTypes = failure.rawEvents.map { it::class.simpleName ?: "UnknownGameEvent" }

        failure.successfulChoices shouldBe 1_140
        failure.committedStep shouldBe 1_141
        failure.failureCode shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
        failure.cardCycledChoices shouldBe 93
        failure.cardCycledStep shouldBe 93
        failure.emptyAttackersChoices shouldBe 466
        failure.emptyAttackersStep shouldBe 466
        rawEventTypes shouldBe listOf(
            "ZoneChangeEvent",
            "LandPlayedEvent",
            "AbilityTriggeredEvent",
        )

        failure.projections.size shouldBe 2
        failure.projections.forEach { projection ->
            projection.isComplete shouldBe true
            projection.classifications.map { it.rawEventType } shouldBe rawEventTypes
            projection.classifications.all { it.disposition == PerspectiveEventDisposition.EMITTED } shouldBe true
        }

        println(
            "ABILITY_TRIGGERED_LOCKED_CROSSING " +
                "maxSteps=4000 " +
                "successfulChoices=${failure.successfulChoices} " +
                "committedStep=${failure.committedStep} " +
                "failure=${failure.failureCode} " +
                "rawEvents=$rawEventTypes " +
                "A=COMPLETE B=NOT_REQUIRED C=NEXT_FAILURE_NOT_IN_SCOPE D=WRAPPER",
        )
    }
})

private fun endpointDecisionResponse(
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
