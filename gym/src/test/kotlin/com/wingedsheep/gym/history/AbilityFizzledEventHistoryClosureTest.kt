package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AbilityFizzledEventHistoryClosureTest : FunSpec({
    val self = EntityId("p1")
    val opponent = EntityId("p2")
    val sourceId = EntityId("ability-fizzle-runtime-id")
    val description = "Hidden ability description"
    val reason = "All targets are invalid"

    fun projection(perspectivePlayerId: EntityId) = PerspectiveEventProjector(CardRegistry()).project(
        events = listOf(
            AbilityFizzledEvent(
                sourceId = sourceId,
                description = description,
                reason = reason,
                sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.AFTER_OBJECT,
                sourceObjectIncarnationStamp = 17L,
            ),
        ),
        perspectivePlayerId = perspectivePlayerId,
    )

    test("AbilityFizzledEvent projects only its stable reason for both perspectives") {
        val projections = listOf(self, opponent).map(::projection)
        val expectedPayload = buildJsonObject {
            put("type", "ability_fizzled")
            put("reason", reason)
        }

        projections.forEach { result ->
            result.isComplete shouldBe true
            result.classifications.single().rawEventType shouldBe "AbilityFizzledEvent"
            result.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            result.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ABILITY_FIZZLED
            result.batch.entries.single().semanticPayload shouldBe expectedPayload
            result.batch.canonicalJson() shouldNotContain sourceId.value
            result.batch.canonicalJson() shouldNotContain description
        }

        projections.map { it.batch.entries.single().semanticPayload }.distinct().size shouldBe 1
        projection(self).batch.canonicalJson() shouldBe projections.first().batch.canonicalJson()
    }

    test("AbilityFizzledEvent uses event-owned source authority in History-C") {
        val before = GameState(
            entities = mapOf(
                self to ComponentContainer.EMPTY,
                opponent to ComponentContainer.EMPTY,
            ),
            turnOrder = listOf(self, opponent),
        )
        val after = before
        val event = AbilityFizzledEvent(
            sourceId = sourceId,
            description = description,
            reason = reason,
            sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
            sourceObjectIncarnationStamp = 17L,
        )
        val transition = CommittedRulesTransition(
            beforeState = before,
            afterState = after,
            events = listOf(event),
            sourceStepCount = 2767,
        )
        val projector = PerspectiveEventProjector(CardRegistry())

        listOf(self, opponent).forEach { perspectivePlayerId ->
            val projection = projector.project(
                events = transition.events,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = before,
                afterState = after,
            )
            val produced = HistoryCReferenceEnvelopeProducerV1.produce(transition, projection)
                .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
            val candidate = produced.envelope.candidates.single()

            candidate.slot shouldBe HistoryCReferenceSlot(
                eventOrdinal = 0,
                role = HistoryCReferenceSlotRole.SOURCE,
                roleOrdinal = 0,
            )
            candidate.referenceKind shouldBe HistoryCReferenceKind.CARD_OR_RULES_OBJECT
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.BEFORE_OBJECT
            candidate.beforeWitness shouldBe HistoryCObjectWitness(sourceId, 17L)
            candidate.afterWitness shouldBe null
            candidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.EVENT_OWNED
            candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE

            HistoryCReferenceAuthority.validate(
                transition = transition,
                projection = projection,
                envelope = produced.envelope,
            ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        }
    }

    test("AbilityFizzledEvent missing or invalid authority is rejected fail closed") {
        val state = GameState(
            entities = mapOf(
                self to ComponentContainer.EMPTY,
                opponent to ComponentContainer.EMPTY,
            ),
            turnOrder = listOf(self, opponent),
        )
        val projector = PerspectiveEventProjector(CardRegistry())

        listOf(
            AbilityFizzledEvent(
                sourceId = sourceId,
                description = description,
                reason = reason,
            ),
            AbilityFizzledEvent(
                sourceId = sourceId,
                description = description,
                reason = reason,
                sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
                sourceObjectIncarnationStamp = 0L,
            ),
        ).forEach { event ->
            val transition = CommittedRulesTransition(
                beforeState = state,
                afterState = state,
                events = listOf(event),
                sourceStepCount = 2767,
            )
            val projection = projector.project(
                events = transition.events,
                perspectivePlayerId = self,
                beforeState = state,
                afterState = state,
            )
            HistoryCReferenceEnvelopeProducerV1.produce(transition, projection)
                .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
                .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
        }
    }

    test("History-C rejects a fizzle without its authoritative source stamp") {
        val sourceState = GameState(
            entities = mapOf(
                self to ComponentContainer.EMPTY,
                opponent to ComponentContainer.EMPTY,
                sourceId to ComponentContainer.EMPTY,
            ),
            turnOrder = listOf(self, opponent),
            objectIdentityStamps = mapOf(sourceId to 17L),
        )
        val event = AbilityFizzledEvent(
            sourceId = sourceId,
            description = description,
            reason = reason,
            sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
        )
        val transition = CommittedRulesTransition(
            beforeState = sourceState,
            afterState = sourceState,
            events = listOf(event),
            sourceStepCount = 2767,
        )
        val projector = PerspectiveEventProjector(CardRegistry())
        val projection = projector.project(
            events = transition.events,
            perspectivePlayerId = self,
            beforeState = sourceState,
            afterState = sourceState,
        )
        val transitionStateCandidate = HistoryCReferenceCandidateV1(
            slot = HistoryCReferenceSlot(
                eventOrdinal = 0,
                role = HistoryCReferenceSlotRole.SOURCE,
                roleOrdinal = 0,
            ),
            referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
            beforeWitness = HistoryCObjectWitness(sourceId, 17L),
            afterWitness = null,
            identityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
            orderProof = HistoryCOrderProof(
                authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                rank = 0,
            ),
            semanticDescriptor = kotlinx.serialization.json.buildJsonObject {
                put("type", "object_reference")
            },
            endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
            witnessProvenance = HistoryCReferenceWitnessProvenance.TRANSITION_STATE,
        )

        HistoryCReferenceAuthority.validate(
            transition = transition,
            projection = projection,
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = self,
                candidates = listOf(transitionStateCandidate),
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }
})
