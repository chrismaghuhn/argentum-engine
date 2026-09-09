package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private data class SourceWitnessFacts(
    val beforeWitnessPresent: Boolean,
    val afterWitnessPresent: Boolean,
    val beforeStampPresent: Boolean,
    val afterStampPresent: Boolean,
    val sameIncarnation: Boolean,
)

/** Test-only regression matrix for the Rules-owned metadata at every AbilityFizzledEvent emitter. */
class AbilityFizzledEmitterAuthorityCharacterizationTest : FunSpec({
    test("pre-stack TriggerProcessor fizzle carries PendingTrigger source authority") {
        val driver = driver()
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val sourceStamp = driver.state.objectIdentityStamps[sourceId]
            ?: error("test source has no object-incarnation stamp")
        val ability = TriggeredAbility.create(
            trigger = EventPattern.StepEvent(Step.UPKEEP, Player.You),
            effect = Effects.GainLife(1),
            targetRequirement = TargetCreature(),
            descriptionOverride = "Synthetic pre-stack fizzle",
        ).copy(id = AbilityId("synthetic-pre-stack-fizzle"))
        val pending = PendingTrigger(
            ability = ability,
            sourceId = sourceId,
            sourceName = "Synthetic pre-stack fizzle",
            controllerId = driver.player1,
            triggerContext = TriggerContext(
                sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION,
            ),
            sourceObjectIncarnationStamp = sourceStamp,
        )
        pending.effectiveSourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
        pending.effectiveSourceObjectIncarnationStamp(driver.state) shouldBe sourceStamp

        val result = TriggerProcessor(driver.cardRegistry, StackResolver(driver.cardRegistry))
            .processTargetedTrigger(driver.state, pending, TargetCreature())
        val event = result.events.single().shouldBeInstanceOf<AbilityFizzledEvent>()

        result.error shouldBe null
        result.pendingDecision shouldBe null
        result.newState shouldBe driver.state
        event.sourceId shouldBe sourceId
        event.reason shouldBe "No legal targets available"
        event.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
        event.sourceObjectIncarnationStamp shouldBe sourceStamp
        sourceWitnessFacts(driver.state, result.newState, sourceId) shouldBe SourceWitnessFacts(
            beforeWitnessPresent = true,
            afterWitnessPresent = true,
            beforeStampPresent = true,
            afterStampPresent = true,
            sameIncarnation = true,
        )

        println(
            "ABILITY_FIZZLED_EMITTER pre-stack " +
                "source=PendingTrigger.sourceId " +
                "authority=SAME_INCARNATION " +
                "metadata=[PendingTrigger.endpoint,PendingTrigger.stamp] " +
                "eventFields=sourceId,description,reason,sourceEndpointAuthority,sourceObjectIncarnationStamp " +
                "eventAuthority=true eventStamp=true",
        )
    }

    test("triggered resolution fizzle carries component authority into the event") {
        val driver = driver()
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val initialState = driver.state
        val sourceStamp = initialState.objectIdentityStamps[sourceId]
            ?: error("test source has no object-incarnation stamp")
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "Synthetic triggered resolution fizzle",
            controllerId = driver.player1,
            effect = Effects.GainLife(1),
            description = "Synthetic triggered resolution fizzle",
            sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION,
            sourceObjectIncarnationStamp = sourceStamp,
        )
        val resolver = StackResolver(driver.cardRegistry)
        val placement = resolver.putTriggeredAbility(
            state = initialState,
            ability = ability,
            targets = listOf(ChosenTarget.Permanent(targetId)),
            targetRequirements = listOf(TargetCreature()),
        )
        placement.error shouldBe null
        val stackId = placement.newState.stack.single()
        val stacked = placement.newState.getEntity(stackId)
            ?.get<TriggeredAbilityOnStackComponent>()
            ?: error("triggered ability was not retained on stack")
        stacked.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
        stacked.sourceObjectIncarnationStamp shouldBe sourceStamp

        val beforeResolution = reenterBattlefield(
            state = placement.newState,
            entityId = sourceId,
            ownerId = driver.player1,
        ).removeFromZone(
            ZoneKey(driver.player2, Zone.BATTLEFIELD),
            targetId,
        ).addToZone(
            ZoneKey(driver.player2, Zone.GRAVEYARD),
            targetId,
        )
        val currentSourceStamp = beforeResolution.objectIdentityStamps[sourceId]
        currentSourceStamp shouldBeNotEqualTo sourceStamp

        val result = resolver.resolveTop(beforeResolution)
        val event = result.events.single().shouldBeInstanceOf<AbilityFizzledEvent>()
        event.sourceId shouldBe sourceId
        event.reason shouldBe "All targets are invalid"
        event.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
        event.sourceObjectIncarnationStamp shouldBe sourceStamp
        sourceWitnessFacts(beforeResolution, result.newState, sourceId) shouldBe SourceWitnessFacts(
            beforeWitnessPresent = true,
            afterWitnessPresent = true,
            beforeStampPresent = true,
            afterStampPresent = true,
            sameIncarnation = true,
        )
        result.newState.objectIdentityStamps[sourceId] shouldBe currentSourceStamp
        result.newState.objectIdentityStamps[sourceId] shouldBeNotEqualTo sourceStamp
        stacked.sourceObjectIncarnationStamp shouldBe sourceStamp

        println(
            "ABILITY_FIZZLED_EMITTER triggered-resolution " +
                "source=TriggeredAbilityOnStackComponent.sourceId " +
                "authority=SAME_INCARNATION " +
                "metadata=[TriggeredAbilityOnStackComponent.endpoint,TriggeredAbilityOnStackComponent.stamp] " +
                "capturedStampDiffersFromResolutionState=true " +
                "eventFields=sourceId,description,reason,sourceEndpointAuthority,sourceObjectIncarnationStamp " +
                "eventAuthority=true eventStamp=true",
        )
    }

    test("activated resolution fizzle carries captured source authority") {
        val driver = driver()
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val initialState = driver.state
        val activationSourceStamp = initialState.objectIdentityStamps[sourceId]
            ?: error("test source has no object-incarnation stamp")
        val ability = ActivatedAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "Synthetic activated resolution fizzle",
            controllerId = driver.player1,
            effect = Effects.GainLife(1),
        )
        val resolver = StackResolver(driver.cardRegistry)
        val placement = resolver.putActivatedAbility(
            state = initialState,
            ability = ability,
            targets = listOf(ChosenTarget.Permanent(targetId)),
            targetRequirements = listOf(TargetCreature()),
            targetLockState = initialState,
        )
        placement.error shouldBe null
        val stackId = placement.newState.stack.single()
        val stacked = placement.newState.getEntity(stackId)
            ?.get<ActivatedAbilityOnStackComponent>()
            ?: error("activated ability was not retained on stack")
        stacked.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        stacked.sourceObjectIncarnationStamp shouldBe activationSourceStamp
        stacked.lastKnownSourceSnapshot shouldBe null
        stacked.lastKnownSourceCounters shouldBe emptyMap()

        val beforeResolution = reenterBattlefield(
            state = placement.newState,
            entityId = sourceId,
            ownerId = driver.player1,
        ).removeFromZone(
            ZoneKey(driver.player2, Zone.BATTLEFIELD),
            targetId,
        ).addToZone(
            ZoneKey(driver.player2, Zone.GRAVEYARD),
            targetId,
        )
        val currentSourceStamp = beforeResolution.objectIdentityStamps[sourceId]
        currentSourceStamp shouldBeNotEqualTo activationSourceStamp

        val result = resolver.resolveTop(beforeResolution)
        val event = result.events.single().shouldBeInstanceOf<AbilityFizzledEvent>()
        event.sourceId shouldBe sourceId
        event.reason shouldBe "All targets are invalid"
        event.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        event.sourceObjectIncarnationStamp shouldBe activationSourceStamp
        sourceWitnessFacts(beforeResolution, result.newState, sourceId) shouldBe SourceWitnessFacts(
            beforeWitnessPresent = true,
            afterWitnessPresent = true,
            beforeStampPresent = true,
            afterStampPresent = true,
            sameIncarnation = true,
        )
        result.newState.objectIdentityStamps[sourceId] shouldBe currentSourceStamp
        result.newState.objectIdentityStamps[sourceId] shouldBeNotEqualTo activationSourceStamp

        println(
            "ABILITY_FIZZLED_EMITTER activated-resolution " +
                "source=ActivatedAbilityOnStackComponent.sourceId " +
                "authority=BEFORE_OBJECT " +
                "metadata=[ActivatedAbilityOnStackComponent.endpoint,ActivatedAbilityOnStackComponent.stamp] " +
                "activationStampDiffersFromResolutionState=true " +
                "eventFields=sourceId,description,reason,sourceEndpointAuthority,sourceObjectIncarnationStamp " +
                "eventAuthority=true eventStamp=true",
        )
    }
})

private fun driver(): GameTestDriver = GameTestDriver().also {
    it.registerCards(TestCards.all)
    it.initMirrorMatch(deck = Deck.of("Forest" to 40))
}

private fun reenterBattlefield(
    state: GameState,
    entityId: com.wingedsheep.sdk.model.EntityId,
    ownerId: com.wingedsheep.sdk.model.EntityId,
): GameState = state
    .removeFromZone(ZoneKey(ownerId, Zone.BATTLEFIELD), entityId)
    .addToZone(ZoneKey(ownerId, Zone.GRAVEYARD), entityId)
    .removeFromZone(ZoneKey(ownerId, Zone.GRAVEYARD), entityId)
    .addToZone(ZoneKey(ownerId, Zone.BATTLEFIELD), entityId)

private fun sourceWitnessFacts(before: GameState, after: GameState, entityId: com.wingedsheep.sdk.model.EntityId) =
    SourceWitnessFacts(
        beforeWitnessPresent = before.hasEntity(entityId) && before.objectIdentityStamps[entityId] != null,
        afterWitnessPresent = after.hasEntity(entityId) && after.objectIdentityStamps[entityId] != null,
        beforeStampPresent = before.objectIdentityStamps[entityId] != null,
        afterStampPresent = after.objectIdentityStamps[entityId] != null,
        sameIncarnation = before.objectIdentityStamps[entityId] != null &&
            before.objectIdentityStamps[entityId] == after.objectIdentityStamps[entityId],
    )

private infix fun Long?.shouldBeNotEqualTo(other: Long?) {
    (this != other) shouldBe true
}
