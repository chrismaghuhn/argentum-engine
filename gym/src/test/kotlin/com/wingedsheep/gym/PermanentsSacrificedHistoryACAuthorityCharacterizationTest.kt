package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.ActionProcessor
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
import com.wingedsheep.engine.core.PermanentsSacrificedEvent
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.zones.SacrificeExecutor
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventClassification
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventUnsupportedReason
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCIdentityDisclosure
import com.wingedsheep.gym.history.HistoryCReferenceEndpointAuthority
import com.wingedsheep.gym.history.HistoryCReferenceKind
import com.wingedsheep.gym.history.HistoryCReferenceSlotRole
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.SacrificeEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

private data class SacrificeProjectionFact(
    val rawEventType: String,
    val family: PerspectiveEventFamily?,
    val disposition: PerspectiveEventDisposition,
    val reason: PerspectiveEventUnsupportedReason?,
)

private data class SacrificedObjectWitnessFact(
    val beforeWitness: Boolean,
    val afterWitness: Boolean,
    val beforeStamp: Long?,
    val afterStamp: Long?,
)

private data class Step3522SacrificeEvidence(
    val beforeState: GameState,
    val afterState: GameState,
    val events: List<GameEvent>,
    val sacrifice: PermanentsSacrificedEvent,
    val failure: HistoryDOperationException,
    val successfulChoices: Int,
    val committedStep: Int,
)

/** Test-only characterization of the A/C contract needed by PermanentsSacrificedEvent. */
class PermanentsSacrificedHistoryACAuthorityCharacterizationTest : FunSpec({
    test("pins Step-3522 sacrifice fields, A gap, and per-object C authority facts") {
        val evidence = runLockedStep3522()
        val eventNames = evidence.events.map { it::class.simpleName ?: "UnknownGameEvent" }

        evidence.successfulChoices shouldBe 3_521
        evidence.committedStep shouldBe 3_522
        evidence.failure.failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
        eventNames shouldBe listOf(
            "TappedEvent",
            "TappedEvent",
            "ManaSpentEvent",
            "TappedEvent",
            "PermanentsSacrificedEvent",
            "ZoneChangeEvent",
            "AbilityActivatedEvent",
        )

        val sacrificeOrdinal = eventNames.indexOf("PermanentsSacrificedEvent")
        sacrificeOrdinal shouldBe 4
        evidence.sacrifice.playerId.value.isNotBlank() shouldBe true
        evidence.beforeState.turnOrder.contains(evidence.sacrifice.playerId) shouldBe true
        evidence.sacrifice.permanentIds.isNotEmpty() shouldBe true
        evidence.sacrifice.permanentIds.size shouldBe 1
        evidence.sacrifice.permanentNames shouldBe emptyList()

        val actorRoles = evidence.beforeState.turnOrder.map { playerId ->
            if (evidence.sacrifice.playerId == playerId) "SELF" else "OTHER"
        }
        actorRoles.toSet() shouldBe setOf("SELF", "OTHER")

        val witnessFacts = evidence.sacrifice.permanentIds.map { entityId ->
            SacrificedObjectWitnessFact(
                beforeWitness = evidence.beforeState.hasEntity(entityId),
                afterWitness = evidence.afterState.hasEntity(entityId),
                beforeStamp = evidence.beforeState.objectIdentityStamps[entityId],
                afterStamp = evidence.afterState.objectIdentityStamps[entityId],
            )
        }
        witnessFacts.forEach { fact ->
            fact.beforeWitness shouldBe true
            (fact.beforeStamp != null) shouldBe true
            if (fact.afterWitness) {
                (fact.afterStamp != null) shouldBe true
                (fact.afterStamp != fact.beforeStamp) shouldBe true
            }
        }

        // The event is emitted as the sacrifice operation, before the following per-object zone
        // transition events. Its semantic C contract therefore needs one pre-sacrifice witness
        // per permanent, in the raw event's explicit list order.
        val proposedCandidates = evidence.sacrifice.permanentIds.mapIndexed { index, _ ->
            ProposedSacrificeCandidate(
                role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                roleOrdinal = index,
                referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
                endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                producerDisclosure = HistoryCIdentityDisclosure.OPAQUE,
                rank = index,
            )
        }
        proposedCandidates.map { it.roleOrdinal } shouldBe evidence.sacrifice.permanentIds.indices.toList()
        proposedCandidates.map { it.rank } shouldBe evidence.sacrifice.permanentIds.indices.toList()
        proposedCandidates.all {
            it.role == HistoryCReferenceSlotRole.EVENT_SUBJECT &&
                it.referenceKind == HistoryCReferenceKind.CARD_OR_RULES_OBJECT &&
                it.endpointAuthority == HistoryCReferenceEndpointAuthority.BEFORE_OBJECT &&
                it.producerDisclosure == HistoryCIdentityDisclosure.OPAQUE
        } shouldBe true

        val projector = PerspectiveEventProjector(cardRegistry())
        val expectedFacts = listOf(
            SacrificeProjectionFact(
                rawEventType = "TappedEvent",
                family = PerspectiveEventFamily.TAPPED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            SacrificeProjectionFact(
                rawEventType = "TappedEvent",
                family = PerspectiveEventFamily.TAPPED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            SacrificeProjectionFact(
                rawEventType = "ManaSpentEvent",
                family = PerspectiveEventFamily.MANA_SPENT,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            SacrificeProjectionFact(
                rawEventType = "TappedEvent",
                family = PerspectiveEventFamily.TAPPED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            SacrificeProjectionFact(
                rawEventType = "PermanentsSacrificedEvent",
                family = null,
                disposition = PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
                reason = PerspectiveEventUnsupportedReason.REQUIRES_SEMANTIC_REFERENCE_C,
            ),
            SacrificeProjectionFact(
                rawEventType = "ZoneChangeEvent",
                family = PerspectiveEventFamily.ZONE_CHANGED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            SacrificeProjectionFact(
                rawEventType = "AbilityActivatedEvent",
                family = PerspectiveEventFamily.ABILITY_ACTIVATED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
        )

        val perspectiveFacts = listOf(0, 1).map { playerIndex ->
            val perspectivePlayerId = environmentPlayerId(evidence, playerIndex)
            val projection = projector.project(
                events = evidence.events,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = evidence.beforeState,
                afterState = evidence.afterState,
            )
            projection.isComplete shouldBe false
            projectionFacts(projection)
        }
        perspectiveFacts.forEach { it shouldBe expectedFacts }
        perspectiveFacts.distinct().size shouldBe 1

        val neighboringZoneChange = evidence.events[sacrificeOrdinal + 1]
            .shouldBeInstanceOf<ZoneChangeEvent>()
        neighboringZoneChange.fromZone shouldBe Zone.BATTLEFIELD
        neighboringZoneChange.toZone shouldBe Zone.GRAVEYARD
        (neighboringZoneChange.lastKnown != null) shouldBe true

        // No model-facing A payload is currently created for this event. The only raw scalar
        // facts suitable for a future A family are actor role and sacrificed count. A synthetic
        // named event confirms that the current incomplete projection contains neither names nor
        // object coordinates; identity and event-time witnesses remain C-owned.
        val privacyProbeNames = listOf("Private Sacrificed Permanent A", "Private Sacrificed Permanent B")
        val privacyProbe = PermanentsSacrificedEvent(
            playerId = evidence.sacrifice.playerId,
            permanentIds = listOf(
                EntityId("private-sacrificed-runtime-a"),
                EntityId("private-sacrificed-runtime-b"),
            ),
            permanentNames = privacyProbeNames,
        )
        listOf(0, 1).forEach { playerIndex ->
            val projected = projector.project(
                events = listOf(privacyProbe),
                perspectivePlayerId = environmentPlayerId(evidence, playerIndex),
                beforeState = evidence.beforeState,
                afterState = evidence.afterState,
            )
            projected.isComplete shouldBe false
            projected.batch.entries shouldBe emptyList()
            val json = projected.batch.canonicalJson()
            json.contains("private-sacrificed-runtime-a") shouldBe false
            json.contains("private-sacrificed-runtime-b") shouldBe false
            privacyProbeNames.forEach { name -> json.contains(name) shouldBe false }
        }

        // Automatic C cannot be reached while A is incomplete; this is the current fail-closed
        // boundary, not evidence that neighboring ZoneChangeEvents own the sacrifice reference.
        val source = CommittedPerspectiveEventSource(cardRegistry())
        source.capture(
            CommittedRulesTransition(
                beforeState = evidence.beforeState,
                afterState = evidence.afterState,
                events = evidence.events,
                sourceStepCount = evidence.committedStep,
            ),
        )
        listOf(0, 1).forEach { playerIndex ->
            val perspectivePlayerId = environmentPlayerId(evidence, playerIndex)
            source.lastCommittedAutomaticReferenceProjection(
                semanticEpisodeId = "permanents-sacrificed-history-authority",
                perspectivePlayerId = perspectivePlayerId,
                registry = com.wingedsheep.gym.history.PerspectiveAliasRegistryV1(
                    semanticEpisodeId = "permanents-sacrificed-history-authority",
                    perspectivePlayerId = perspectivePlayerId,
                ),
            ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Rejected>()
                .failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
        }

        println(
            "PERMANENTS_SACRIFICED_HISTORY_AC_CHARACTERIZATION " +
                "successfulChoices=${evidence.successfulChoices} " +
                "committedStep=${evidence.committedStep} " +
                "rawEventCount=${evidence.events.size} " +
                "sacrificedCount=${evidence.sacrifice.permanentIds.size} " +
                "nameCount=${evidence.sacrifice.permanentNames.size} " +
                "perspectives=${perspectiveFacts.size} " +
                "aReason=${expectedFacts[sacrificeOrdinal].reason} " +
                "beforeWitnesses=${witnessFacts.count { it.beforeWitness }} " +
                "afterWitnesses=${witnessFacts.count { it.afterWitness }} " +
                "beforeStamps=${witnessFacts.count { it.beforeStamp != null }} " +
                "afterStamps=${witnessFacts.count { it.afterStamp != null }} " +
                "candidatePositions=${proposedCandidates.map { it.rank }} " +
                "candidateEndpoint=${proposedCandidates.map { it.endpointAuthority }} " +
                "rawFields=playerId,permanentIds,permanentNames " +
                "eventAuthorityMetadata=ABSENT " +
                "orderingAuthority=NOT_YET_CHARACTERIZED " +
                "rulesMetadataSufficiency=NO " +
                "cCurrent=NOT_REACHED",
        )
    }

    test("pins real multi-permanent sacrifice ordering and pre-sacrifice incarnation facts") {
        val player = EntityId("multi-sacrifice-player")
        val firstPermanent = EntityId("first-sacrifice-runtime-id")
        val secondPermanent = EntityId("second-sacrifice-runtime-id")
        val before = GameState(
            entities = mapOf(
                player to ComponentContainer.EMPTY,
                firstPermanent to ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "Mountain",
                        name = "Mountain",
                        manaCost = ManaCost.ZERO,
                        typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                        ownerId = player,
                    ),
                    ControllerComponent(player),
                ),
                secondPermanent to ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "Forest",
                        name = "Forest",
                        manaCost = ManaCost.ZERO,
                        typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                        ownerId = player,
                    ),
                    ControllerComponent(player),
                ),
            ),
            zones = mapOf(
                ZoneKey(player, Zone.BATTLEFIELD) to listOf(firstPermanent, secondPermanent),
                ZoneKey(player, Zone.GRAVEYARD) to emptyList(),
            ),
            turnOrder = listOf(player),
            objectIdentityStamps = mapOf(firstPermanent to 10L, secondPermanent to 11L),
            nextObjectIdentityStamp = 12L,
        )
        EngineServices(cardRegistry())
        val result = SacrificeExecutor().execute(
            state = before,
            effect = SacrificeEffect(filter = GameObjectFilter.Land, count = 2),
            context = EffectContext(sourceId = null, controllerId = player),
        )

        result.error shouldBe null
        val sacrifice = result.events.filterIsInstance<PermanentsSacrificedEvent>().single()
        sacrifice.permanentIds shouldBe listOf(firstPermanent, secondPermanent)
        sacrifice.permanentNames.size shouldBe 2
        val zoneChanges = result.events.filterIsInstance<com.wingedsheep.engine.core.ZoneChangeEvent>()
        zoneChanges.size shouldBe 2
        zoneChanges.map { it.entityId } shouldBe sacrifice.permanentIds
        zoneChanges.forEach { zoneChange ->
            zoneChange.fromZone shouldBe Zone.BATTLEFIELD
            zoneChange.toZone shouldBe Zone.GRAVEYARD
            (zoneChange.lastKnown != null) shouldBe true
        }
        result.events.indexOf(sacrifice) shouldBe 0
        result.events.indexOf(zoneChanges.first()) shouldBe 1
        val after = result.state

        sacrifice.permanentIds.forEach { entityId ->
            before.getZone(player, Zone.BATTLEFIELD).contains(entityId) shouldBe true
            after.getZone(player, Zone.BATTLEFIELD).contains(entityId) shouldBe false
            after.getZone(player, Zone.GRAVEYARD).contains(entityId) shouldBe true
            before.hasEntity(entityId) shouldBe true
            after.hasEntity(entityId) shouldBe true
            (before.objectIdentityStamps[entityId] != null) shouldBe true
            (after.objectIdentityStamps[entityId] != null) shouldBe true
            (before.objectIdentityStamps[entityId] != after.objectIdentityStamps[entityId]) shouldBe true
        }

        val cShape = sacrifice.permanentIds.indices.map { index ->
            ProposedSacrificeCandidate(
                role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                roleOrdinal = index,
                referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
                endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                producerDisclosure = HistoryCIdentityDisclosure.OPAQUE,
                rank = index,
            )
        }
        cShape.map { it.role } shouldBe listOf(
            HistoryCReferenceSlotRole.EVENT_SUBJECT,
            HistoryCReferenceSlotRole.EVENT_SUBJECT,
        )
        cShape.map { it.roleOrdinal } shouldBe listOf(0, 1)
        cShape.map { it.rank } shouldBe listOf(0, 1)
        cShape.map { it.endpointAuthority }.distinct() shouldBe listOf(
            HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
        )

        println(
            "PERMANENTS_SACRIFICED_MULTI_CHARACTERIZATION " +
                "sacrificedCount=${sacrifice.permanentIds.size} " +
                "zoneChangeCount=${zoneChanges.size} " +
                "beforeWitnesses=${sacrifice.permanentIds.count { before.hasEntity(it) }} " +
                "afterWitnesses=${sacrifice.permanentIds.count { after.hasEntity(it) }} " +
                "incarnationChanges=${sacrifice.permanentIds.count { before.objectIdentityStamps[it] != after.objectIdentityStamps[it] }} " +
                "candidateRoles=${cShape.map { it.role }} " +
                "candidateRanks=${cShape.map { it.rank }} " +
                "candidateEndpoint=${cShape.map { it.endpointAuthority }} " +
                "rawFields=playerId,permanentIds,permanentNames " +
                "eventAuthorityMetadata=ABSENT " +
                "orderingAuthority=NOT_YET_CHARACTERIZED " +
                "rulesMetadataSufficiency=NO",
        )
    }

    test("characterizes the unordered sacrifice-choice response order") {
        val first = runUnorderedSacrificeSelection(listOf(0, 1))
        val reversed = runUnorderedSacrificeSelection(listOf(1, 0))

        first.decisionOrdered shouldBe false
        reversed.decisionOrdered shouldBe false
        first.selectedOptionIndices.toSet() shouldBe setOf(0, 1)
        reversed.selectedOptionIndices.toSet() shouldBe setOf(0, 1)
        first.sacrificeEventOptionIndices shouldBe listOf(0, 1)
        reversed.sacrificeEventOptionIndices shouldBe listOf(1, 0)
        (first.sacrificeEventOptionIndices != reversed.sacrificeEventOptionIndices) shouldBe true
        first.zoneChangeEventOptionIndices shouldBe first.sacrificeEventOptionIndices
        reversed.zoneChangeEventOptionIndices shouldBe reversed.sacrificeEventOptionIndices

        println(
            "PERMANENTS_SACRIFICED_UNORDERED_ORDER_CHARACTERIZATION " +
                "decisionOrdered=false " +
                "firstResponseOrder=[0,1] " +
                "firstEventOrder=${first.sacrificeEventOptionIndices} " +
                "reversedResponseOrder=[1,0] " +
                "reversedEventOrder=${reversed.sacrificeEventOptionIndices} " +
                "selectionOrderSemanticAuthority=NONE " +
                "currentEventOrderBehavior=RESPONSE_ORDER_PRESERVED " +
                "historyCandidateOrdering=NOT_AUTHORIZED",
        )
    }
})

private data class ProposedSacrificeCandidate(
    val role: HistoryCReferenceSlotRole,
    val roleOrdinal: Int,
    val referenceKind: HistoryCReferenceKind,
    val endpointAuthority: HistoryCReferenceEndpointAuthority,
    val producerDisclosure: HistoryCIdentityDisclosure,
    val rank: Int,
)

private data class UnorderedSacrificeSelectionRun(
    val decisionOrdered: Boolean,
    val selectedOptionIndices: List<Int>,
    val sacrificeEventOptionIndices: List<Int>,
    val zoneChangeEventOptionIndices: List<Int>,
)

private fun runUnorderedSacrificeSelection(
    responseOptionIndices: List<Int>,
): UnorderedSacrificeSelectionRun {
    val registry = cardRegistry()
    val player = EntityId("unordered-sacrifice-player")
    val options = listOf(
        EntityId("unordered-sacrifice-option-a"),
        EntityId("unordered-sacrifice-option-b"),
        EntityId("unordered-sacrifice-option-c"),
    )
    val before = GameState(
        entities = buildMap {
            put(player, ComponentContainer.EMPTY)
            options.forEachIndexed { index, entityId ->
                put(
                    entityId,
                    ComponentContainer.of(
                        CardComponent(
                            cardDefinitionId = listOf("Mountain", "Forest", "Island")[index],
                            name = listOf("Mountain", "Forest", "Island")[index],
                            manaCost = ManaCost.ZERO,
                            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                            ownerId = player,
                        ),
                        ControllerComponent(player),
                    ),
                )
            }
        },
        zones = mapOf(
            ZoneKey(player, Zone.BATTLEFIELD) to options,
            ZoneKey(player, Zone.GRAVEYARD) to emptyList(),
        ),
        activePlayerId = player,
        priorityPlayerId = player,
        turnOrder = listOf(player),
        objectIdentityStamps = options.mapIndexed { index, entityId -> entityId to (20L + index) }.toMap(),
        nextObjectIdentityStamp = 23L,
    )
    EngineServices(registry)
    val paused = SacrificeExecutor().execute(
        state = before,
        effect = SacrificeEffect(filter = GameObjectFilter.Land, count = 2),
        context = EffectContext(sourceId = null, controllerId = player),
    )
    paused.error shouldBe null
    val decision = paused.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
    decision.ordered shouldBe false
    decision.options shouldBe options
    val selected = responseOptionIndices.map { decision.options[it] }
    val resumed = ActionProcessor(registry).process(
        paused.state,
        SubmitDecision(
            playerId = player,
            response = CardsSelectedResponse(decision.id, selected),
        ),
    ).result
    resumed.error shouldBe null
    val sacrifice = resumed.events.filterIsInstance<PermanentsSacrificedEvent>().single()
    val zoneChanges = resumed.events.filterIsInstance<ZoneChangeEvent>()
    zoneChanges.size shouldBe 2
    return UnorderedSacrificeSelectionRun(
        decisionOrdered = decision.ordered,
        selectedOptionIndices = selected.map(options::indexOf),
        sacrificeEventOptionIndices = sacrifice.permanentIds.map(options::indexOf),
        zoneChangeEventOptionIndices = zoneChanges.map { options.indexOf(it.entityId) },
    )
}

private fun cardRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun runLockedStep3522(): Step3522SacrificeEvidence {
    val registry = cardRegistry()
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
        semanticEpisodeId = "permanents-sacrificed-history-ac-authority-seed-0",
    ).observation as TrainingObservation
    val policy = DeterministicExternalPolicy()
    var policyState = DeterministicPolicyState(policySeed = 0x41L)
    var successfulChoices = 0
    var failure: HistoryDOperationException? = null
    var failingBeforeState: GameState? = null
    var failingAfterState: GameState? = null
    var failingEvents: List<GameEvent> = emptyList()

    while (!observation.terminated && !observation.truncated && failure == null) {
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
        } catch (exception: HistoryDOperationException) {
            failure = exception
            failingBeforeState = beforeState
            failingAfterState = environment.state
            failingEvents = environment.lastStepEvents.toList()
        }
    }

    val historyDFailure = checkNotNull(failure)
    val beforeState = checkNotNull(failingBeforeState)
    val afterState = checkNotNull(failingAfterState)
    val sacrifice = failingEvents.filterIsInstance<PermanentsSacrificedEvent>().single()
    return Step3522SacrificeEvidence(
        beforeState = beforeState,
        afterState = afterState,
        events = failingEvents,
        sacrifice = sacrifice,
        failure = historyDFailure,
        successfulChoices = successfulChoices,
        committedStep = environment.stepCount,
    )
}

private fun environmentPlayerId(
    evidence: Step3522SacrificeEvidence,
    playerIndex: Int,
): com.wingedsheep.sdk.model.EntityId = evidence.beforeState.turnOrder[playerIndex]

private fun projectionFacts(
    projection: PerspectiveEventProjectionResult,
): List<SacrificeProjectionFact> {
    var emittedOrdinal = 0
    return projection.classifications.map { classification: PerspectiveEventClassification ->
        val family = if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
            projection.batch.entries[emittedOrdinal++].eventFamily
        } else {
            null
        }
        SacrificeProjectionFact(
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
