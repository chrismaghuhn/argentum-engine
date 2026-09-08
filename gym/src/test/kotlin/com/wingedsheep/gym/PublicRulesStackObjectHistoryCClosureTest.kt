package com.wingedsheep.gym

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.TargetsChosenEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCIdentityDisclosure
import com.wingedsheep.gym.history.HistoryCReferenceEndpointAuthority
import com.wingedsheep.gym.history.HistoryCReferenceKind
import com.wingedsheep.gym.history.HistoryCReferenceSlotRole
import com.wingedsheep.gym.history.PerspectiveAliasRegistryV1
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class PublicRulesStackObjectHistoryCClosureTest : FunSpec({
    val perspective = EntityId.of("p1")
    val controller = EntityId.of("p2")
    val objectId = EntityId.of("rules-object-runtime-id")

    fun state(
        objectId: EntityId,
        zone: Zone,
        stamp: Long = 1L,
    ): GameState = GameState(
        entities = mapOf(
            perspective to ComponentContainer.EMPTY,
            controller to ComponentContainer.EMPTY,
            objectId to ComponentContainer.EMPTY,
        ),
        zones = if (zone == Zone.STACK) {
            emptyMap()
        } else {
            mapOf(ZoneKey(controller, zone) to listOf(objectId))
        },
        stack = if (zone == Zone.STACK) listOf(objectId) else emptyList(),
        turnOrder = listOf(perspective, controller),
        objectIdentityStamps = mapOf(objectId to stamp),
    )

    fun project(
        event: GameEvent,
        before: GameState,
        after: GameState,
        perspectivePlayerId: EntityId = perspective,
    ): AutomaticHistoryCReferenceProjectionResult =
        CommittedPerspectiveEventSource(CardRegistry()).also { source ->
            source.capture(
                CommittedRulesTransition(
                    beforeState = before,
                    afterState = after,
                    events = listOf(event),
                    sourceStepCount = 1,
                ),
            )
        }.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "public-rules-stack-object-closure",
            perspectivePlayerId = perspectivePlayerId,
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "public-rules-stack-object-closure",
                perspectivePlayerId = perspectivePlayerId,
            ),
        )

    test("public non-card stack object remains an opaque C reference") {
        listOf(perspective, controller).forEach { perspectivePlayerId ->
            val result = project(
                event = TargetsChosenEvent(
                    chooserId = controller,
                    stackObjectId = objectId,
                    sourceName = "Triggered ability",
                ),
                before = state(objectId, Zone.STACK),
                after = state(objectId, Zone.STACK),
                perspectivePlayerId = perspectivePlayerId,
            ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()

            val candidate = result.evidence.candidates.single()
            candidate.slot.role shouldBe HistoryCReferenceSlotRole.EVENT_SUBJECT
            candidate.referenceKind shouldBe HistoryCReferenceKind.STACK_OBJECT
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
            candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
            candidate.afterWitness?.objectIdentityStamp shouldBe 1L

            result.evidence.eventBatch.entries.single().eventFamily shouldBe
                PerspectiveEventFamily.TARGETS_CHOSEN
            result.projection.referenceOccurrences.single().identityDisclosure shouldBe
                HistoryCIdentityDisclosure.OPAQUE
            result.projection.referenceOccurrences.single().cardDefinitionId shouldBe null
            result.evidence.eventBatch.canonicalJson() shouldNotContain objectId.value
        }
    }

    test("public card-like object without CardComponent remains fail-closed") {
        val result = project(
            event = AbilityTriggeredEvent(
                sourceId = objectId,
                sourceName = "Malformed card source",
                controllerId = controller,
                description = "A source without card identity",
            ),
            before = state(objectId, Zone.BATTLEFIELD),
            after = state(objectId, Zone.BATTLEFIELD),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Rejected>()

        result.failure.code shouldBe HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH
    }
})
