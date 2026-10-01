package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityResolvedEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A resolved ability whose source no longer exists — a token that died for its own dies trigger
 * (Undying Malice on an Elephant token) — binds the source through the incarnation the event owns,
 * as a fizzled ability does. Before upstream-sync-05 the fork kept a swept token's entity alive
 * by accident and the after-object witness happened to resolve.
 */
class AbilityResolvedDepartedSourceHistoryCTest : FunSpec({
    val self = EntityId("p1")
    val opponent = EntityId("p2")
    val sourceId = EntityId("resolved-source-runtime-id")
    val description = "When this creature dies, return it to the battlefield tapped"

    val players = GameState(
        entities = mapOf(
            self to ComponentContainer.EMPTY,
            opponent to ComponentContainer.EMPTY,
        ),
        turnOrder = listOf(self, opponent),
    )

    fun produce(transition: CommittedRulesTransition, perspectivePlayerId: EntityId):
        Pair<com.wingedsheep.gym.contract.PerspectiveEventProjectionResult, HistoryCReferenceEnvelopeProducerResult> {
        val projection = PerspectiveEventProjector(CardRegistry()).project(
            events = transition.events,
            perspectivePlayerId = perspectivePlayerId,
            beforeState = transition.beforeState,
            afterState = transition.afterState,
        )
        return projection to HistoryCReferenceEnvelopeProducerV1.produce(transition, projection)
    }

    test("a resolved ability whose source departed binds its event-owned incarnation") {
        val transition = CommittedRulesTransition(
            beforeState = players,
            afterState = players,
            events = listOf(
                AbilityResolvedEvent(
                    sourceId = sourceId,
                    description = description,
                    sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
                    sourceObjectIncarnationStamp = 17L,
                ),
            ),
            sourceStepCount = 2002,
        )

        listOf(self, opponent).forEach { perspectivePlayerId ->
            val (projection, produced) = produce(transition, perspectivePlayerId)
            val envelope = produced.shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>().envelope
            val candidate = envelope.candidates.single()

            candidate.slot.role shouldBe HistoryCReferenceSlotRole.SOURCE
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.BEFORE_OBJECT
            candidate.beforeWitness shouldBe HistoryCObjectWitness(sourceId, 17L)
            candidate.afterWitness shouldBe null
            candidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.EVENT_OWNED
            candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE

            HistoryCReferenceAuthority.validate(transition, projection, envelope)
                .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        }
    }

    test("a resolved ability whose source remains keeps its after-object witness") {
        val withSource = players.copy(
            entities = players.entities + (sourceId to ComponentContainer.EMPTY),
            objectIdentityStamps = mapOf(sourceId to 23L),
        )
        val transition = CommittedRulesTransition(
            beforeState = withSource,
            afterState = withSource,
            events = listOf(
                AbilityResolvedEvent(
                    sourceId = sourceId,
                    description = description,
                    sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
                    sourceObjectIncarnationStamp = 17L,
                ),
            ),
            sourceStepCount = 2002,
        )

        val (projection, produced) = produce(transition, self)
        val envelope = produced.shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>().envelope
        val candidate = envelope.candidates.single()
        candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
        candidate.afterWitness shouldBe HistoryCObjectWitness(sourceId, 23L)
        candidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.TRANSITION_STATE
        HistoryCReferenceAuthority.validate(transition, projection, envelope)
            .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
    }

    test("a departed source without its authoritative stamp stays fail closed") {
        val transition = CommittedRulesTransition(
            beforeState = players,
            afterState = players,
            events = listOf(AbilityResolvedEvent(sourceId = sourceId, description = description)),
            sourceStepCount = 2002,
        )

        produce(transition, self).second
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }
})
