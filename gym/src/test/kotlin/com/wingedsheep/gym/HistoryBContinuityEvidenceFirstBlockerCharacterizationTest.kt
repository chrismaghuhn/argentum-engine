package com.wingedsheep.gym

import com.wingedsheep.engine.mechanics.KnownInformationLedger
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.KnownInformationAcquisitionReason
import com.wingedsheep.engine.state.components.player.KnownInformationAudience
import com.wingedsheep.engine.state.components.player.KnownInformationFactKind
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventClassification
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCReferenceAuthority
import com.wingedsheep.gym.history.HistoryCReferenceAuthorityResult
import com.wingedsheep.gym.history.HistoryCReferenceEndpointAuthority
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerResult
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeProducerV1
import com.wingedsheep.gym.history.HistoryCReferenceKind
import com.wingedsheep.gym.history.HistoryCLifecycleStateV1
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.gym.history.HistoryCObjectWitness
import com.wingedsheep.gym.history.PerspectiveAliasRegistryV1
import com.wingedsheep.gym.history.PerspectiveReferenceProjectorV1
import com.wingedsheep.gym.history.PerspectiveReferenceProjectionResult
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path

/** Regression proving the first broad-corpus History-B continuity gap is closed by the ledger. */
class HistoryBContinuityEvidenceFirstBlockerCharacterizationTest : FunSpec({
    test("crosses the first History-B continuity blocker") {
        val registry = bContinuityRegistry()
        val root = bContinuityRepositoryRoot()
        val akiri = bContinuityLockedDeck(root, "akiri-v0.1.txt")
        val chevill = bContinuityLockedDeck(root, "chevill-v0.1.txt")
        val config = GameConfig(
            players = listOf(
                PlayerConfig(
                    name = "Akiri",
                    deck = com.wingedsheep.sdk.model.Deck(bContinuityExpandDeck(akiri.drop(1))),
                    startingLife = 40,
                    commanderCardName = akiri.first(),
                ),
                PlayerConfig(
                    name = "Chevill",
                    deck = com.wingedsheep.sdk.model.Deck(bContinuityExpandDeck(chevill.drop(1))),
                    startingLife = 40,
                    commanderCardName = chevill.first(),
                ),
            ),
            startingHandSize = 7,
            skipMulligans = true,
            useHandSmoother = false,
            startingPlayerIndex = 1,
            format = Format.Commander(),
            seed = 2L,
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
            semanticEpisodeId = "pre-c1-b1-Akiri-vs-Chevill-seed-2-start-1",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(
            policySeed = 2L * 1_000_003L + 1L * 97_409L + 0x41L * 65_537L,
        )
        var successfulChoices = 0
        var failure: HistoryDOperationException? = null
        var failingBefore: GameState? = null
        var failingAfter: GameState? = null
        var failingEvents: List<GameEvent> = emptyList()
        var failingHistory: HistoryCLifecycleStateV1? = null

        while (!observation.terminated && !observation.truncated && failure == null &&
            successfulChoices < 928
        ) {
            val before = environment.state
            val historyBefore = gym.historyCLifecycleState()
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
                            response = bContinuityDecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error(
                        "Policy gap at choice=$successfulChoices: ${choice.code} ${choice.reason}",
                    )
                } as TrainingObservation
                successfulChoices++
                if (successfulChoices == 928) {
                    failingBefore = before
                    failingAfter = environment.state
                    failingEvents = environment.lastStepEvents.toList()
                    failingHistory = historyBefore
                }
            } catch (exception: HistoryDOperationException) {
                failure = exception
                failingBefore = before
                failingAfter = environment.state
                failingEvents = environment.lastStepEvents.toList()
                failingHistory = historyBefore
            }
        }

        successfulChoices shouldBe 928
        environment.stepCount shouldBe 928
        val observedFailure = failure
        observedFailure shouldBe null
        observation.terminated shouldBe false
        observation.truncated shouldBe false

        val before = checkNotNull(failingBefore)
        val after = checkNotNull(failingAfter)
        val history = checkNotNull(failingHistory)
        val transition = CommittedRulesTransition(
            beforeState = before,
            afterState = after,
            events = failingEvents,
            sourceStepCount = environment.stepCount,
        )
        val eventNames = failingEvents.map { it::class.simpleName ?: "UnknownGameEvent" }
        eventNames shouldBe listOf("StepChangedEvent", "CardsDrawnEvent", "StepChangedEvent")
        val drawnEvent = failingEvents.filterIsInstance<CardsDrawnEvent>().single()
        drawnEvent.playerId shouldBe EntityId("e1")
        drawnEvent.count shouldBe 1
        drawnEvent.cardIds shouldBe listOf(EntityId("e175"))
        failingEvents.filterIsInstance<ZoneChangeEvent>().isEmpty() shouldBe true
        before.getLibrary(EntityId("e1")).first() shouldBe EntityId("e175")
        val perspectives = environment.playerIds.map { perspectivePlayerId ->
            val projection = checkNotNull(gym.lastCommittedPerspectiveEventProjection(perspectivePlayerId))
            val produced = HistoryCReferenceEnvelopeProducerV1.produce(transition, projection)
            val authority = when (produced) {
                is HistoryCReferenceEnvelopeProducerResult.Accepted ->
                    HistoryCReferenceAuthority.validate(transition, projection, produced.envelope)

                is HistoryCReferenceEnvelopeProducerResult.Rejected -> null
            }
            val bProjection = when (val acceptedAuthority =
                authority as? HistoryCReferenceAuthorityResult.Accepted
            ) {
                null -> null
                else -> PerspectiveReferenceProjectorV1(registry).project(
                    semanticEpisodeId = history.semanticEpisodeId,
                    perspectivePlayerId = perspectivePlayerId,
                    transition = transition,
                    evidence = acceptedAuthority.evidence,
                    registry = history.registries.getValue(perspectivePlayerId),
                )
            }
            PerspectiveCharacterization(
                perspectivePlayerId = perspectivePlayerId,
                projection = projection,
                produced = produced,
                authority = authority,
                bProjection = bProjection,
                registry = history.registries.getValue(perspectivePlayerId),
            )
        }
        environment.playerIds.map { it.value } shouldBe listOf("e0", "e1")

        perspectives.forEach { characterization ->
            characterization.projection.isComplete shouldBe true
            characterization.projection.batch.entries.map { it.eventFamily.name } shouldBe listOf(
                "STEP_CHANGED",
                "CARDS_DRAWN",
                "STEP_CHANGED",
            )
            characterization.projection.classifications.map { it.disposition } shouldBe
                List(3) { PerspectiveEventDisposition.EMITTED }
            characterization.produced.shouldBeInstanceOfAccepted()
            (characterization.authority as? HistoryCReferenceAuthorityResult.Accepted) shouldNotBe null
            val acceptedEnvelope = characterization.produced as HistoryCReferenceEnvelopeProducerResult.Accepted
            acceptedEnvelope.envelope.candidates.size shouldBe 1
            val candidate = acceptedEnvelope.envelope.candidates.single()
            candidate.slot.eventOrdinal shouldBe 1
            candidate.slot.role.name shouldBe "EVENT_SUBJECT"
            candidate.referenceKind shouldBe HistoryCReferenceKind.CARD_OR_RULES_OBJECT
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
            candidate.beforeWitness shouldBe null
            val currentWitness = checkNotNull(candidate.afterWitness)
            currentWitness shouldBe HistoryCObjectWitness(EntityId("e175"), 313L)
            val previousWitnesses = characterization.registry.activeBindings.keys.filter {
                it.entityId == currentWitness.entityId
            }
            previousWitnesses shouldNotBe emptyList<HistoryCObjectWitness>()
            previousWitnesses.any { it != currentWitness } shouldBe true
            val beforeStamp = checkNotNull(before.objectIdentityStamps[currentWitness.entityId])
            val afterStamp = checkNotNull(after.objectIdentityStamps[currentWitness.entityId])
            beforeStamp shouldBe 174L
            beforeStamp shouldNotBe afterStamp
            afterStamp shouldBe currentWitness.objectIdentityStamp
            val previousWitness = HistoryCObjectWitness(currentWitness.entityId, beforeStamp)
            previousWitnesses shouldBe listOf(previousWitness)
            val previousBinding = characterization.registry.activeBindings.getValue(previousWitness)
            previousBinding.alias.canonical() shouldBe when (characterization.perspectivePlayerId.value) {
                "e0" -> "o97"
                "e1" -> "o84"
                else -> error("Unexpected perspective")
            }
            witnessLocation(before, after, currentWitness.entityId) shouldBe
                "before=[LIBRARY:e1],after=[HAND:e1]"
            val exactFacts = KnownInformationLedger
                .forPlayer(after, characterization.perspectivePlayerId)
                .activeFacts
                .filter {
                    it.subjectEntityId == currentWitness.entityId &&
                        it.objectIdentityStamp == currentWitness.objectIdentityStamp
                }
            val beforeFacts = KnownInformationLedger
                .forPlayer(before, characterization.perspectivePlayerId)
                .activeFacts
                .filter {
                    it.subjectEntityId == currentWitness.entityId &&
                        it.objectIdentityStamp == beforeStamp
                }
            if (characterization.perspectivePlayerId == EntityId("e0")) {
                beforeFacts.isEmpty() shouldBe false
            }
            exactFacts.map { it.factKind } shouldBe listOf(
                KnownInformationFactKind.IDENTITY,
                KnownInformationFactKind.ZONE_MEMBERSHIP,
            )
            exactFacts.forEach { fact ->
                fact.objectIdentityStamp shouldBe currentWitness.objectIdentityStamp
                fact.knownZone shouldBe com.wingedsheep.sdk.core.Zone.HAND
                fact.audience shouldBe KnownInformationAudience.PUBLIC
                fact.acquisitionReason shouldBe KnownInformationAcquisitionReason.VISIBLE_ZONE_TRANSITION
            }
            beforeFacts.map { it.factKind } shouldBe listOf(
                KnownInformationFactKind.IDENTITY,
                KnownInformationFactKind.ZONE_MEMBERSHIP,
                KnownInformationFactKind.POSITION_OR_ORDER,
            )
            beforeFacts.forEach { fact ->
                fact.audience shouldBe KnownInformationAudience.PUBLIC
                when (fact.factKind) {
                    KnownInformationFactKind.IDENTITY,
                    KnownInformationFactKind.ZONE_MEMBERSHIP,
                    -> fact.acquisitionReason shouldBe KnownInformationAcquisitionReason.PUBLIC_REVEAL

                    KnownInformationFactKind.POSITION_OR_ORDER -> {
                        fact.acquisitionReason shouldBe
                            KnownInformationAcquisitionReason.CONTINUOUS_IDENTITY_VISIBILITY
                        fact.knownPosition shouldBe 0
                    }
                }
                fact.knownZone shouldBe com.wingedsheep.sdk.core.Zone.LIBRARY
            }
            println(
                "HISTORY_B_CONTINUITY_FACTS " +
                    "perspective=${characterization.perspectivePlayerId.value} " +
                    "currentWitness=$currentWitness " +
                    "previousWitnesses=$previousWitnesses " +
                    "beforeFacts=${beforeFacts.map {
                        it.factKind.name + ":" + it.knownZone + ":" + it.knownPosition + ":" +
                            it.audience.name + ":" + it.acquisitionReason.name + ":epoch=" + it.acquiredAtEpoch
                    }} " +
                    "afterFacts=${exactFacts.map { it.factKind.name + ":" + it.knownZone }}",
            )
            printCharacterization(
                characterization = characterization,
                before = before,
                after = after,
            )
        }

        perspectives.map { it.bProjection.failureCodeOrNull() } shouldBe listOf(
            null,
            null,
        )
        val committedHistory = checkNotNull(gym.historyCLifecycleState())
        committedHistory.registries.values.forEach { registryState ->
            registryState.activeBindings[HistoryCObjectWitness(EntityId("e175"), 313L)] shouldNotBe null
            registryState.activeBindings[HistoryCObjectWitness(EntityId("e175"), 174L)] shouldBe null
        }

        println(
            "HISTORY_B_CONTINUITY_FIRST_BLOCKER " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "failure=${observedFailure?.failure?.code} " +
                "rawEvents=$eventNames " +
                "perspectives=${perspectives.size} " +
                "aComplete=${perspectives.map { it.projection.isComplete }} " +
                "bFailure=${perspectives.map { it.bProjection.failureCodeOrNull() }}",
        )
    }
})

private data class PerspectiveCharacterization(
    val perspectivePlayerId: EntityId,
    val projection: PerspectiveEventProjectionResult,
    val produced: HistoryCReferenceEnvelopeProducerResult,
    val authority: HistoryCReferenceAuthorityResult?,
    val bProjection: PerspectiveReferenceProjectionResult?,
    val registry: PerspectiveAliasRegistryV1,
)

private fun printCharacterization(
    characterization: PerspectiveCharacterization,
    before: GameState,
    after: GameState,
) {
    val envelope = (characterization.produced as? HistoryCReferenceEnvelopeProducerResult.Accepted)
        ?.envelope
    val relevantBindings = envelope?.candidates
        ?.flatMap { listOfNotNull(it.beforeWitness, it.afterWitness) }
        ?.firstOrNull()
        ?.let { witness ->
            characterization.registry.activeBindings
                .filterKeys { it.entityId == witness.entityId }
                .map { (bindingWitness, binding) ->
                    "${bindingWitness.entityId.value}@${bindingWitness.objectIdentityStamp}->${binding.alias.canonical()}"
                }
        }
    println(
        "HISTORY_B_PERSPECTIVE " +
            "perspective=${characterization.perspectivePlayerId.value} " +
            "a=${projectionFacts(characterization.projection)} " +
            "relevantBindings=$relevantBindings " +
            "candidates=${envelope?.candidates?.mapIndexed { index, candidate ->
                val witness = candidate.beforeWitness ?: candidate.afterWitness
                val location = witness?.let { witnessLocation(before, after, it.entityId) }
                "#$index/${candidate.slot.eventOrdinal}/${candidate.slot.role}/" +
                    "${candidate.referenceKind}/${candidate.endpointAuthority}/" +
                    "${candidate.beforeWitness}/${candidate.afterWitness}/location=$location"
            }} " +
            "bFailure=${characterization.bProjection.failureCodeOrNull()}",
    )
}

private fun projectionFacts(projection: PerspectiveEventProjectionResult): List<String> {
    var emittedOrdinal = 0
    return projection.classifications.map { classification: PerspectiveEventClassification ->
        val family = if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
            projection.batch.entries[emittedOrdinal++].eventFamily.name
        } else {
            "-"
        }
        "${classification.rawEventType}:$family/${classification.disposition}/${classification.reason}"
    }
}

private fun PerspectiveReferenceProjectionResult?.failureCodeOrNull(): HistoryCFailureCode? =
    (this as? PerspectiveReferenceProjectionResult.Rejected)?.failure?.code

private fun HistoryCReferenceEnvelopeProducerResult.shouldBeInstanceOfAccepted() {
    (this as? HistoryCReferenceEnvelopeProducerResult.Accepted) shouldNotBe null
}

private fun PerspectiveReferenceProjectionResult?.shouldBeRejected(code: HistoryCFailureCode) {
    val rejected = this as? PerspectiveReferenceProjectionResult.Rejected
    rejected shouldNotBe null
    rejected!!.failure.code shouldBe code
}

private fun witnessLocation(
    before: GameState,
    after: GameState,
    entityId: EntityId,
): String = listOf("before" to before, "after" to after).joinToString(",") { (label, state) ->
    val zones = state.zones.entries
        .filter { (_, ids) -> entityId in ids }
        .map { (key, _) -> "${key.zoneType}:${key.ownerId.value}" }
    val stack = if (entityId in state.stack) "STACK" else null
    "$label=${(zones + listOfNotNull(stack)).ifEmpty { listOf("ABSENT") }}"
}

private fun bContinuityRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun bContinuityRepositoryRoot(): Path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
    .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }

private fun bContinuityLockedDeck(root: Path, fileName: String): List<String> = Files.readAllLines(
    root.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName),
)
    .filter { it.matches(Regex("^\\d{3}\\t.*")) }
    .map { it.substringAfterLast('\t') }

private fun bContinuityExpandDeck(cards: List<String>): List<String> =
    cards.groupingBy { it }.eachCount().flatMap { (card, count) ->
        List(count) { card }
    }

private fun bContinuityDecisionResponse(
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
