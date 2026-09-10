package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.SpellFizzledEvent
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SpellFizzledEventHistoryClosureTest : FunSpec({
    val self = EntityId("spell-fizzle-p1")
    val opponent = EntityId("spell-fizzle-p2")
    val spellId = EntityId("spell-fizzle-stack-object")
    val cardName = "Private Spell Display Name"
    val beforeStamp = 101L
    val afterStamp = 102L

    fun event(reason: String = "All targets are invalid") = SpellFizzledEvent(
        spellEntityId = spellId,
        cardName = cardName,
        reason = reason,
    )

    fun beforeState() = GameState(
        entities = mapOf(
            self to ComponentContainer.EMPTY,
            opponent to ComponentContainer.EMPTY,
            spellId to ComponentContainer.EMPTY,
        ),
        zones = mapOf(
            ZoneKey(self, Zone.STACK) to listOf(spellId),
        ),
        turnOrder = listOf(self, opponent),
        stack = listOf(spellId),
        objectIdentityStamps = mapOf(spellId to beforeStamp),
    )

    fun afterState() = beforeState().copy(
        zones = mapOf(
            ZoneKey(self, Zone.GRAVEYARD) to listOf(spellId),
        ),
        stack = emptyList(),
        objectIdentityStamps = mapOf(spellId to afterStamp),
    )

    fun transition(
        rawEvent: SpellFizzledEvent = event(),
        before: GameState = beforeState(),
        after: GameState = afterState(),
    ) = CommittedRulesTransition(
        beforeState = before,
        afterState = after,
        events = listOf(rawEvent),
        sourceStepCount = 639,
    )

    fun projection(
        transition: CommittedRulesTransition,
        perspectivePlayerId: EntityId,
    ) = PerspectiveEventProjector(CardRegistry()).project(
        events = transition.events,
        perspectivePlayerId = perspectivePlayerId,
        beforeState = transition.beforeState,
        afterState = transition.afterState,
    )

    test("projects a stable SpellFizzled family and reason for both perspectives") {
        val expectedPayload = buildJsonObject {
            put("type", "spell_fizzled")
            put("reason", "ALL_TARGETS_INVALID")
        }
        val committed = transition()

        listOf(self, opponent).forEach { perspectivePlayerId ->
            val projected = projection(committed, perspectivePlayerId)

            projected.isComplete shouldBe true
            projected.classifications.single().rawEventType shouldBe "SpellFizzledEvent"
            projected.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            projected.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.SPELL_FIZZLED
            projected.batch.entries.single().semanticPayload shouldBe expectedPayload
            projected.batch.canonicalJson() shouldNotContain spellId.value
            projected.batch.canonicalJson() shouldNotContain cardName
            projected.batch.canonicalJson() shouldNotContain "All targets are invalid"
        }
    }

    test("binds the spell subject to the committed before-state stack witness") {
        val committed = transition()

        listOf(self, opponent).forEach { perspectivePlayerId ->
            val projected = projection(committed, perspectivePlayerId)
            val produced = HistoryCReferenceEnvelopeProducerV1.produce(committed, projected)
                .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
            val candidate = produced.envelope.candidates.single()

            candidate.slot shouldBe HistoryCReferenceSlot(
                eventOrdinal = 0,
                role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                roleOrdinal = 0,
            )
            candidate.referenceKind shouldBe HistoryCReferenceKind.STACK_OBJECT
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.BEFORE_OBJECT
            candidate.beforeWitness shouldBe HistoryCObjectWitness(spellId, beforeStamp)
            candidate.afterWitness shouldBe null
            candidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.TRANSITION_STATE

            HistoryCReferenceAuthority.validate(
                transition = committed,
                projection = projected,
                envelope = produced.envelope,
            ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        }
    }

    test("fails closed when the before-state stack witness is unavailable") {
        val committed = transition(
            before = beforeState().copy(
                entities = mapOf(
                    self to ComponentContainer.EMPTY,
                    opponent to ComponentContainer.EMPTY,
                ),
                zones = emptyMap(),
                stack = emptyList(),
                objectIdentityStamps = emptyMap(),
            ),
        )
        val projected = projection(committed, self)

        HistoryCReferenceEnvelopeProducerV1.produce(committed, projected)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }

    test("fails closed for an unrecognized raw reason") {
        val committed = transition(rawEvent = event(reason = "unrecognized diagnostic text"))
        val projected = projection(committed, self)

        projected.isComplete shouldBe false
        projected.classifications.single().disposition shouldBe
            PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY
        HistoryCReferenceEnvelopeProducerV1.produce(committed, projected)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
    }
})
