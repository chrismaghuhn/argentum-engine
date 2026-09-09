package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class AbilityActivatedSourceAuthorityHistoryCTest : FunSpec({
    val self = EntityId.of("p1")
    val opponent = EntityId.of("p2")
    val source = EntityId.of("activated-source")

    fun state() = GameState(
        entities = mapOf(
            self to ComponentContainer.EMPTY,
            opponent to ComponentContainer.EMPTY,
            source to ComponentContainer.EMPTY,
        ),
        zones = mapOf(
            ZoneKey(self, Zone.BATTLEFIELD) to listOf(source),
        ),
        turnOrder = listOf(self, opponent),
        objectIdentityStamps = mapOf(source to 101L),
    )

    val event = AbilityActivatedEvent(
        sourceId = source,
        sourceName = "private activated source name",
        controllerId = self,
    )

    test("AbilityActivatedEvent uses the existing BEFORE_OBJECT History-C contract") {
        val transition = CommittedRulesTransition(
            beforeState = state(),
            afterState = state(),
            events = listOf(event),
            sourceStepCount = 1,
        )
        val projector = PerspectiveEventProjector(com.wingedsheep.engine.registry.CardRegistry())

        listOf(self, opponent).forEach { perspectivePlayerId ->
            val projection = projector.project(
                events = transition.events,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = transition.beforeState,
                afterState = transition.afterState,
            )
            projection.isComplete shouldBe true
            projection.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            projection.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ABILITY_ACTIVATED
            projection.batch.canonicalJson() shouldNotContain source.value
            projection.batch.canonicalJson() shouldNotContain event.sourceName

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
            candidate.beforeWitness shouldBe HistoryCObjectWitness(source, 101L)
            candidate.afterWitness shouldBe null

            HistoryCReferenceAuthority.validate(
                transition = transition,
                projection = projection,
                envelope = produced.envelope,
            ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        }
    }
})
