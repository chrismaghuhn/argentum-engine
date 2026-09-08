package com.wingedsheep.gym

import com.wingedsheep.engine.core.AbilityTriggeredEvent
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
import com.wingedsheep.engine.core.TargetsChosenEvent
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.BecomesTargetEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryCReferenceAuthority
import com.wingedsheep.gym.history.HistoryCReferenceAuthorityResult
import com.wingedsheep.gym.history.HistoryCReferenceEndpointAuthority
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeV1
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerResult
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerV1
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCReferenceKind
import com.wingedsheep.gym.history.HistoryCReferenceSlotRole
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.gym.history.PerspectiveReferenceProjectionResult
import com.wingedsheep.gym.history.PerspectiveReferenceProjectorV1
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * RED characterization for the next locked History-D failure after CardCycledEvent closure.
 * Diagnostics report only event/family/authority metadata, never runtime identities or card names.
 * The smallest future closure is C-only: retain opaque references for public Rules stack objects
 * that have no card definition, without widening A or adding unrelated event families.
 */
class Step111HistoryDFailureCharacterizationTest : FunSpec({
    test("pins the first post-CardCycled History-D failure at locked step 111") {
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
            semanticEpisodeId = "step-111-history-d-failure-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var choices = 0
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
                            response = step111DecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error("Policy gap at choice=$choices: $choice")
                } as TrainingObservation
                choices++
                if (cardCycledChoices == null &&
                    environment.lastStepEvents.any { it is CardCycledEvent }
                ) {
                    cardCycledChoices = choices
                    cardCycledStep = environment.stepCount
                }
            } catch (exception: HistoryDOperationException) {
                failure = exception
            }
        }

        val observedFailure = failure
            ?: error("The locked path unexpectedly completed without the Step-111 failure")
        val source = committedPerspectiveEventSource(gym)
        val transition = checkNotNull(source.snapshotState().transition)
        val lifecycle = checkNotNull(gym.historyCLifecycleState())
        val perspectiveResults = environment.playerIds.mapIndexed { perspectiveIndex, playerId ->
            val result = source.lastCommittedAutomaticReferenceProjection(
                semanticEpisodeId = lifecycle.semanticEpisodeId,
                perspectivePlayerId = playerId,
                registry = lifecycle.registries.getValue(playerId),
            )
            println(
                "STEP111_PERSPECTIVE " +
                    "index=$perspectiveIndex " +
                    "result=${result::class.simpleName} " +
                    "failure=${(result as? AutomaticHistoryCReferenceProjectionResult.Rejected)
                        ?.failure?.code}",
            )
            Triple(perspectiveIndex, playerId, result)
        }
        val rejectedPerspectives = perspectiveResults.filter { (_, _, result) ->
            result is AutomaticHistoryCReferenceProjectionResult.Rejected
        }
        rejectedPerspectives.size shouldBe environment.playerIds.size
        perspectiveResults.map { (_, _, result) ->
            result.shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Rejected>().failure.code
        } shouldBe environment.playerIds.map { HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH }
        val rejectedPerspective = rejectedPerspectives.first()
        val rejectedPlayer = rejectedPerspective.second
        val rejectedProjection = checkNotNull(source.projectLast(rejectedPlayer))
        printProjectionDiagnostics(transition.events, rejectedProjection)

        val envelope = when (
            val produced = HistoryCReferenceEnvelopeProducerV1.produce(
                transition = transition,
                projection = rejectedProjection,
            )
        ) {
            is HistoryCReferenceEnvelopeProducerResult.Accepted -> produced.envelope
            is HistoryCReferenceEnvelopeProducerResult.Rejected -> error(
                "Unexpected C producer rejection: ${produced.failure.code}",
            )
        }
        val evidence = when (
            val validated = HistoryCReferenceAuthority.validate(
                transition = transition,
                projection = rejectedProjection,
                envelope = envelope,
            )
        ) {
            is HistoryCReferenceAuthorityResult.Accepted -> validated.evidence
            is HistoryCReferenceAuthorityResult.Rejected -> error(
                "Unexpected C authority rejection: ${validated.failure.code}",
            )
        }
        val cResult = PerspectiveReferenceProjectorV1(registry).project(
            semanticEpisodeId = lifecycle.semanticEpisodeId,
            perspectivePlayerId = rejectedPlayer,
            transition = transition,
            evidence = evidence,
            registry = lifecycle.registries.getValue(rejectedPlayer),
        )
        val cFailure = cResult.shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Rejected>()
        val candidateResults = printCandidateDiagnostics(
            transition = transition,
            projection = rejectedProjection,
            envelope = envelope,
            lifecycle = lifecycle,
            registry = registry,
            perspectivePlayerId = rejectedPlayer,
        )

        val targetsChosen = transition.events.filterIsInstance<TargetsChosenEvent>().single()
        val becomesTarget = transition.events.filterIsInstance<BecomesTargetEvent>().single()
        println(
            "STEP111_OBJECT_RELATION " +
                "targetsStackEqualsBecomesSource=${targetsChosen.stackObjectId == becomesTarget.sourceEntityId} " +
                "targetsStackHasCardComponent=${transition.afterState.getEntity(targetsChosen.stackObjectId)
                    ?.get<CardComponent>() != null} " +
                "becomesSourceHasCardComponent=${transition.afterState.getEntity(becomesTarget.sourceEntityId)
                    ?.get<CardComponent>() != null} " +
                "becomesTargetHasCardComponent=${transition.afterState.getEntity(becomesTarget.targetEntityId)
                    ?.get<CardComponent>() != null}",
        )

        println(
            "STEP111_FAILURE " +
                "choices=$choices " +
                "stepCount=${environment.stepCount} " +
                "historyD=${observedFailure.failure.code} " +
                "historyC=${cFailure.failure.code} " +
                "partialHistoryEntries=${gym.perspectiveHistory(environment.playerIds.first()).entries.size}",
        )
        println(
            "STEP111_OWNERSHIP " +
                "A=COMPLETE " +
                "C_PRODUCER=ACCEPTED " +
                "C_AUTHORITY=ACCEPTED " +
                "C_PROJECTOR=${cFailure.failure.code} " +
                "D_WRAPPER=${observedFailure.failure.code}",
        )
        cardCycledChoices shouldBe 93
        cardCycledStep shouldBe 93
        choices shouldBe 110
        environment.stepCount shouldBe 111
        transition.events.map { it::class.simpleName } shouldBe listOf(
            "DecisionSubmittedEvent",
            "AbilityTriggeredEvent",
            "TargetsChosenEvent",
            "BecomesTargetEvent",
        )
        rejectedProjection.classifications.all {
            it.disposition == PerspectiveEventDisposition.EMITTED && it.reason == null
        } shouldBe true
        rejectedProjection.batch.entries.map { it.eventFamily } shouldBe listOf(
            PerspectiveEventFamily.DECISION_SUBMITTED,
            PerspectiveEventFamily.ABILITY_TRIGGERED,
            PerspectiveEventFamily.TARGETS_CHOSEN,
            PerspectiveEventFamily.BECAME_TARGET,
        )
        envelope.candidates.map { it.slot.eventOrdinal } shouldBe listOf(1, 2, 3, 3)
        envelope.candidates.map { it.slot.role } shouldBe listOf(
            HistoryCReferenceSlotRole.SOURCE,
            HistoryCReferenceSlotRole.EVENT_SUBJECT,
            HistoryCReferenceSlotRole.SOURCE,
            HistoryCReferenceSlotRole.TARGET,
        )
        envelope.candidates.map { it.referenceKind } shouldBe listOf(
            HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            HistoryCReferenceKind.STACK_OBJECT,
            HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        )
        envelope.candidates.map { it.endpointAuthority } shouldBe listOf(
            HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
            HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
            HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
            HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
        )
        candidateResults shouldBe listOf(
            "ACCEPTED",
            "C:IDENTITY_AUTHORITY_MISMATCH",
            "C:IDENTITY_AUTHORITY_MISMATCH",
            "ACCEPTED",
        )
        (targetsChosen.stackObjectId == becomesTarget.sourceEntityId) shouldBe true
        transition.afterState.stack.contains(targetsChosen.stackObjectId) shouldBe true
        transition.afterState.getEntity(targetsChosen.stackObjectId)
            ?.get<CardComponent>() shouldBe null
        transition.afterState.getEntity(becomesTarget.sourceEntityId)
            ?.get<CardComponent>() shouldBe null
        transition.afterState.getEntity(becomesTarget.targetEntityId)
            ?.get<CardComponent>() shouldNotBe null
        becomesTarget.targetIsSpell shouldBe false
        becomesTarget.sourceIsSpell shouldBe false
        becomesTarget.targetIsPlayer shouldBe false
        observedFailure.failure.code shouldBe cFailure.failure.code
        observedFailure.failure.code shouldBe HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH
        rejectedProjection.isComplete shouldBe true
    }
})

private fun committedPerspectiveEventSource(gym: GameGymEnv): CommittedPerspectiveEventSource {
    val field = GameGymEnv::class.java.getDeclaredField("committedPerspectiveEventSource")
    field.isAccessible = true
    return field.get(gym) as CommittedPerspectiveEventSource
}

private fun printProjectionDiagnostics(
    rawEvents: List<GameEvent>,
    projection: PerspectiveEventProjectionResult,
) {
    var projectedOrdinal = 0
    projection.classifications.forEachIndexed { rawIndex, classification ->
        val event = rawEvents[rawIndex]
        val family = if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
            projection.batch.entries[projectedOrdinal++].eventFamily.name
        } else {
            "-"
        }
        println(
            "STEP111_RAW " +
                "rawIndex=$rawIndex " +
                "event=${event::class.simpleName} " +
                "family=$family " +
                "disposition=${classification.disposition} " +
                "reason=${classification.reason} " +
                "details=${eventDetails(event)}",
        )
    }
}

private fun printCandidateDiagnostics(
    transition: CommittedRulesTransition,
    projection: PerspectiveEventProjectionResult,
    envelope: HistoryCReferenceEnvelopeV1,
    lifecycle: com.wingedsheep.gym.history.HistoryCLifecycleStateV1,
    registry: CardRegistry,
    perspectivePlayerId: EntityId,
): List<String> = buildList {
    val emittedEvents = transition.events.filterIndexed { index, _ ->
        projection.classifications[index].disposition == PerspectiveEventDisposition.EMITTED
    }
    envelope.candidates.forEachIndexed { candidateIndex, candidate ->
        val rawEvent = emittedEvents[candidate.slot.eventOrdinal]
        val singleEnvelope = envelope.copy(candidates = listOf(candidate))
        val singleResult = when (
            val authority = HistoryCReferenceAuthority.validate(
                transition = transition,
                projection = projection,
                envelope = singleEnvelope,
            )
        ) {
            is HistoryCReferenceAuthorityResult.Rejected -> "AUTHORITY:${authority.failure.code}"
            is HistoryCReferenceAuthorityResult.Accepted -> {
                val projected = PerspectiveReferenceProjectorV1(registry).project(
                    semanticEpisodeId = lifecycle.semanticEpisodeId,
                    perspectivePlayerId = perspectivePlayerId,
                    transition = transition,
                    evidence = authority.evidence,
                    registry = lifecycle.registries.getValue(perspectivePlayerId),
                )
                when (projected) {
                    is PerspectiveReferenceProjectionResult.Accepted -> "ACCEPTED"
                    is PerspectiveReferenceProjectionResult.Rejected ->
                        "C:${projected.failure.code}"
                }
            }
        }
        println(
            "STEP111_CANDIDATE " +
                "index=$candidateIndex " +
                "eventOrdinal=${candidate.slot.eventOrdinal} " +
                "event=${rawEvent::class.simpleName} " +
                "family=${projection.batch.entries[candidate.slot.eventOrdinal].eventFamily} " +
                "role=${candidate.slot.role} " +
                "referenceKind=${candidate.referenceKind} " +
                "beforeWitness=${candidate.beforeWitness != null} " +
                "afterWitness=${candidate.afterWitness != null} " +
                "identity=${candidate.identityDisclosure} " +
                "endpoint=${candidate.endpointAuthority} " +
                "singleResult=$singleResult",
        )
        add(singleResult)
    }
}

private fun eventDetails(event: GameEvent): String = when (event) {
    is AbilityTriggeredEvent -> "causedByAttack=${event.causedByAttack}"
    is TargetsChosenEvent -> "stackObject=true"
    is BecomesTargetEvent -> "targetIsSpell=${event.targetIsSpell},sourceIsSpell=${event.sourceIsSpell},targetIsPlayer=${event.targetIsPlayer}"
    is CardCycledEvent -> "cardCycled=true"
    else -> ""
}

private fun step111DecisionResponse(
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
