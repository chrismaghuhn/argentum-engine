package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.PermanentsSacrificedEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Focused GREEN contract tests for the authorized single-event sacrifice History closure. */
class PermanentsSacrificedEventHistoryClosureTest : FunSpec({
    val self = EntityId("sacrifice-history-self")
    val opponent = EntityId("sacrifice-history-opponent")
    val first = EntityId("sacrifice-history-object-a")
    val second = EntityId("sacrifice-history-object-b")
    val firstName = "Private Sacrifice A"
    val secondName = "Private Sacrifice B"

    fun card(name: String, faceDown: Boolean = false): ComponentContainer {
        val container = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = name,
                name = name,
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                ownerId = self,
            ),
        )
        return if (faceDown) container.with(FaceDownComponent) else container
    }

    fun state(
        ids: List<EntityId>,
        zone: Zone,
        stamps: Map<EntityId, Long>,
        includeCardComponents: Boolean = true,
        faceDown: Boolean = false,
    ) = GameState(
        entities = buildMap {
            put(self, ComponentContainer.EMPTY)
            put(opponent, ComponentContainer.EMPTY)
            if (includeCardComponents) {
                put(first, card("Mountain", faceDown))
                put(second, card("Forest", faceDown))
            } else {
                put(first, ComponentContainer.EMPTY)
                put(second, ComponentContainer.EMPTY)
            }
        },
        zones = mapOf(ZoneKey(self, zone) to ids),
        turnOrder = listOf(self, opponent),
        objectIdentityStamps = stamps,
    )

    fun event(
        ids: List<EntityId>,
        stamps: List<Long?> = ids.mapIndexed { index, _ -> 100L + index },
    ) = PermanentsSacrificedEvent(
        playerId = self,
        permanentIds = ids,
        permanentNames = ids.map { id -> if (id == first) firstName else secondName },
        permanentObjectIncarnationStamps = stamps,
    )

    fun transition(
        ids: List<EntityId>,
        stamps: List<Long?> = ids.mapIndexed { index, _ -> 100L + index },
        includeCardComponents: Boolean = true,
        faceDown: Boolean = false,
    ) = CommittedRulesTransition(
        beforeState = state(
            ids = ids,
            zone = Zone.BATTLEFIELD,
            stamps = ids.mapIndexed { index, id -> id to (100L + index) }.toMap(),
            includeCardComponents = includeCardComponents,
            faceDown = faceDown,
        ),
        afterState = state(
            ids = ids,
            zone = Zone.GRAVEYARD,
            stamps = ids.mapIndexed { index, id -> id to (200L + index) }.toMap(),
            includeCardComponents = includeCardComponents,
            faceDown = faceDown,
        ),
        events = listOf(
            event(ids, stamps),
        ),
        sourceStepCount = 3_522,
    )

    test("projects only actor role and count for both perspectives") {
        val committed = transition(listOf(first, second))
        val projector = PerspectiveEventProjector(CardRegistry())

        listOf(self, opponent).forEach { perspective ->
            val projection = projector.project(
                events = committed.events,
                perspectivePlayerId = perspective,
                beforeState = committed.beforeState,
                afterState = committed.afterState,
            )

            projection.isComplete shouldBe true
            projection.classifications.single().rawEventType shouldBe "PermanentsSacrificedEvent"
            projection.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            projection.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.PERMANENTS_SACRIFICED
            projection.batch.entries.single().semanticPayload shouldBe buildJsonObject {
                put("type", "permanents_sacrificed")
                put("playerRole", if (perspective == self) "SELF" else "OTHER")
                put("count", 2)
            }
            projection.batch.canonicalJson() shouldNotContain first.value
            projection.batch.canonicalJson() shouldNotContain second.value
            projection.batch.canonicalJson() shouldNotContain firstName
            projection.batch.canonicalJson() shouldNotContain secondName
            projection.batch.canonicalJson() shouldNotContain "100"
            projection.batch.canonicalJson() shouldNotContain "101"
            projection.batch.canonicalJson() shouldBe projector.project(
                events = committed.events,
                perspectivePlayerId = perspective,
                beforeState = committed.beforeState,
                afterState = committed.afterState,
            ).batch.canonicalJson()
        }
    }

    test("single sacrifice source uses event-owned before witness through C") {
        val committed = transition(listOf(first))
        val projection = PerspectiveEventProjector(CardRegistry()).project(
            events = committed.events,
            perspectivePlayerId = self,
            beforeState = committed.beforeState,
            afterState = committed.afterState,
        )
        val produced = HistoryCReferenceEnvelopeProducerV1.produce(committed, projection)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val candidate = produced.envelope.candidates.single()

        candidate.slot shouldBe HistoryCReferenceSlot(
            eventOrdinal = 0,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = 0,
        )
        candidate.referenceKind shouldBe HistoryCReferenceKind.CARD_OR_RULES_OBJECT
        candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.BEFORE_OBJECT
        candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
        candidate.beforeWitness shouldBe HistoryCObjectWitness(first, 100L)
        candidate.afterWitness shouldBe null
        candidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.EVENT_OWNED
        candidate.orderProof shouldBe HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = 0,
        )

        HistoryCReferenceAuthority.validate(
            transition = committed,
            projection = projection,
            envelope = produced.envelope,
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
    }

    test("multi-sacrifice candidates reach C but remain B-symmetry fail-closed") {
        val committed = transition(listOf(first, second), faceDown = true)
        val projection = PerspectiveEventProjector(CardRegistry()).project(
            events = committed.events,
            perspectivePlayerId = self,
            beforeState = committed.beforeState,
            afterState = committed.afterState,
        )
        val produced = HistoryCReferenceEnvelopeProducerV1.produce(committed, projection)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        produced.envelope.candidates.map { it.slot.role } shouldBe listOf(
            HistoryCReferenceSlotRole.EVENT_SUBJECT,
            HistoryCReferenceSlotRole.EVENT_SUBJECT,
        )
        produced.envelope.candidates.map { it.slot.roleOrdinal } shouldBe listOf(0, 1)
        produced.envelope.candidates.map { it.orderProof.rank } shouldBe listOf(0, 1)
        produced.envelope.candidates.map { it.endpointAuthority }.distinct() shouldBe listOf(
            HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
        )

        HistoryCReferenceAuthority.validate(
            transition = committed,
            projection = projection,
            envelope = produced.envelope,
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()

        val result = PerspectiveReferenceProjectorV1(CardRegistry()).project(
            semanticEpisodeId = "permanents-sacrificed-history-closure",
            perspectivePlayerId = self,
            transition = committed,
            evidence = HistoryCReferenceEvidenceV1(
                perspectivePlayerId = self,
                eventBatch = projection.batch,
                candidates = produced.envelope.candidates,
            ),
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "permanents-sacrificed-history-closure",
                perspectivePlayerId = self,
            ),
        )
        result.shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.UNORDERED_SYMMETRY
    }

    test("missing event-owned sacrifice metadata fails closed before C allocation") {
        val committed = transition(listOf(first)).copy(
            events = listOf(
                event(listOf(first), stamps = emptyList()),
            ),
        )
        val projection = PerspectiveEventProjector(CardRegistry()).project(
            events = committed.events,
            perspectivePlayerId = self,
            beforeState = committed.beforeState,
            afterState = committed.afterState,
        )
        HistoryCReferenceEnvelopeProducerV1.produce(committed, projection)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }

    test("raw authority rejects malformed single-sacrifice candidates") {
        val committed = transition(listOf(first))
        val projection = PerspectiveEventProjector(CardRegistry()).project(
            events = committed.events,
            perspectivePlayerId = self,
            beforeState = committed.beforeState,
            afterState = committed.afterState,
        )
        val produced = HistoryCReferenceEnvelopeProducerV1.produce(committed, projection)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val valid = produced.envelope.candidates.single()
        val mutations = listOf(
            valid.copy(
                beforeWitness = HistoryCObjectWitness(second, 200L),
                afterWitness = null,
            ),
            valid.copy(slot = valid.slot.copy(role = HistoryCReferenceSlotRole.TARGET)),
            valid.copy(referenceKind = HistoryCReferenceKind.STACK_OBJECT),
            valid.copy(slot = valid.slot.copy(roleOrdinal = 1)),
            valid.copy(beforeWitness = null, afterWitness = null),
            valid.copy(
                identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                cardDefinitionId = "Wrong Definition",
            ),
        )

        mutations.forEach { malformed ->
            HistoryCReferenceAuthority.validate(
                transition = committed,
                projection = projection,
                envelope = produced.envelope.copy(candidates = listOf(malformed)),
            ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
        }
    }
})
