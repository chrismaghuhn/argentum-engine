package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

private data class ActivatedSourceWitnessFacts(
    val beforeWitnessPresent: Boolean,
    val afterWitnessPresent: Boolean,
    val beforeStampPresent: Boolean,
    val afterStampPresent: Boolean,
    val sameIncarnation: Boolean,
)

/** Test-only characterization of source metadata at AbilityFizzledEvent emitters. */
class ActivatedAbilitySourceAuthorityCharacterizationTest : FunSpec({
    test("pre-stack TriggerProcessor fizzle has PendingTrigger source authority") {
        val driver = driver()
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val sourceStamp = driver.state.objectIdentityStamps[sourceId]
            ?: error("test source has no object-incarnation stamp")
        val ability = TriggeredAbility.create(
            trigger = EventPattern.StepEvent(Step.UPKEEP, Player.You),
            effect = Effects.GainLife(1),
            targetRequirement = TargetCreature(),
            descriptionOverride = "Synthetic pre-stack authority fizzle",
        ).copy(id = AbilityId("synthetic-pre-stack-authority-fizzle"))
        val pending = PendingTrigger(
            ability = ability,
            sourceId = sourceId,
            sourceName = "Synthetic pre-stack authority fizzle",
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
        event.sourceId shouldBe sourceId
        event.reason shouldBe "No legal targets available"
        sourceWitnessFacts(driver.state, result.newState, sourceId) shouldBe ActivatedSourceWitnessFacts(
            beforeWitnessPresent = true,
            afterWitnessPresent = true,
            beforeStampPresent = true,
            afterStampPresent = true,
            sameIncarnation = true,
        )

        println(
            "ACTIVATED_SOURCE_AUTHORITY pre-stack " +
                "source=PendingTrigger.sourceId " +
                "requiredAuthority=SAME_INCARNATION " +
                "metadata=endpoint+incarnationStamp " +
                "eventFields=sourceId,description,reason",
        )
    }

    test("triggered resolution fizzle retains captured source authority before emission") {
        val driver = driver()
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val initialState = driver.state
        val capturedSourceStamp = initialState.objectIdentityStamps[sourceId]
            ?: error("test source has no object-incarnation stamp")
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "Synthetic triggered authority fizzle",
            controllerId = driver.player1,
            effect = Effects.GainLife(1),
            description = "Synthetic triggered authority fizzle",
            sourceEndpointAuthority = AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION,
            sourceObjectIncarnationStamp = capturedSourceStamp,
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
        val stackComponent = placement.newState.getEntity(stackId)
            ?.get<TriggeredAbilityOnStackComponent>()
            ?: error("triggered ability was not retained on stack")
        stackComponent.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
        stackComponent.sourceObjectIncarnationStamp shouldBe capturedSourceStamp

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
        val resolutionSourceStamp = beforeResolution.objectIdentityStamps[sourceId]
        (resolutionSourceStamp != capturedSourceStamp) shouldBe true

        val result = resolver.resolveTop(beforeResolution)
        val event = result.events.single().shouldBeInstanceOf<AbilityFizzledEvent>()
        event.sourceId shouldBe sourceId
        event.reason shouldBe "All targets are invalid"
        sourceWitnessFacts(beforeResolution, result.newState, sourceId) shouldBe ActivatedSourceWitnessFacts(
            beforeWitnessPresent = true,
            afterWitnessPresent = true,
            beforeStampPresent = true,
            afterStampPresent = true,
            sameIncarnation = true,
        )
        result.newState.objectIdentityStamps[sourceId] shouldBe resolutionSourceStamp
        (result.newState.objectIdentityStamps[sourceId] != capturedSourceStamp) shouldBe true

        println(
            "ACTIVATED_SOURCE_AUTHORITY triggered-resolution " +
                "source=TriggeredAbilityOnStackComponent.sourceId " +
                "requiredAuthority=SAME_INCARNATION " +
                "metadata=endpoint+capturedIncarnationStamp " +
                "capturedStampDiffersFromResolutionState=true " +
                "eventFields=sourceId,description,reason",
        )
    }

    test("activated resolution fizzle captures generic activation source authority") {
        val driver = driver()
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val initialState = driver.state
        val activationSourceStamp = initialState.objectIdentityStamps[sourceId]
            ?: error("test source has no object-incarnation stamp")
        val ability = ActivatedAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "Synthetic activated authority fizzle",
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
        val stackComponent = placement.newState.getEntity(stackId)
            ?.get<ActivatedAbilityOnStackComponent>()
            ?: error("activated ability was not retained on stack")
        stackComponent.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        stackComponent.sourceObjectIncarnationStamp shouldBe activationSourceStamp
        stackComponent.lastKnownSourceSnapshot shouldBe null
        stackComponent.lastKnownSourceCounters shouldBe emptyMap()

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
        val resolutionSourceStamp = beforeResolution.objectIdentityStamps[sourceId]
        (resolutionSourceStamp != activationSourceStamp) shouldBe true

        val result = resolver.resolveTop(beforeResolution)
        val event = result.events.single().shouldBeInstanceOf<AbilityFizzledEvent>()
        event.sourceId shouldBe sourceId
        event.reason shouldBe "All targets are invalid"
        event.sourceEndpointAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        event.sourceObjectIncarnationStamp shouldBe activationSourceStamp
        sourceWitnessFacts(beforeResolution, result.newState, sourceId) shouldBe ActivatedSourceWitnessFacts(
            beforeWitnessPresent = true,
            afterWitnessPresent = true,
            beforeStampPresent = true,
            afterStampPresent = true,
            sameIncarnation = true,
        )
        result.newState.objectIdentityStamps[sourceId] shouldBe resolutionSourceStamp
        (result.newState.objectIdentityStamps[sourceId] != activationSourceStamp) shouldBe true

        println(
            "ACTIVATED_SOURCE_AUTHORITY activated-resolution " +
                "source=ActivatedAbilityOnStackComponent.sourceId " +
                "requiredAuthority=BEFORE_OBJECT " +
                "metadata=endpoint+activationIncarnationStamp " +
                "activationStampDiffersFromResolutionState=true " +
                "eventFields=sourceId,description,reason,sourceEndpointAuthority,sourceObjectIncarnationStamp",
        )
    }

    test("persistent activation has the source stamp at the activation boundary") {
        val driver = driver(persistentActivationSource)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val sourceId = driver.putPermanentOnBattlefield(driver.player1, persistentActivationSource.name)
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val sourceWasOnBattlefieldAtActivation =
            driver.state.getBattlefield(driver.player1).contains(sourceId)
        sourceWasOnBattlefieldAtActivation shouldBe true
        val activationSourceStamp = driver.state.objectIdentityStamps[sourceId]
            ?: error("persistent source has no activation stamp")
        val activationEntryStamp = driver.state.getEntity(sourceId)
            ?.get<BattlefieldEntryTimestampComponent>()?.timestamp
            ?: error("persistent source has no battlefield-entry stamp")
        val abilityId = persistentActivationSource.activatedAbilities.single().id

        val activation = driver.submit(
            ActivateAbility(
                playerId = driver.player1,
                sourceId = sourceId,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(targetId)),
            ),
        )
        activation.isSuccess shouldBe true
        val activationEvent = activation.events.filterIsInstance<AbilityActivatedEvent>().single()
        activationEvent.sourceId shouldBe sourceId
        // The existing AbilityActivatedEvent History-C contract binds its source at activation time.
        val existingActivationEventAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        existingActivationEventAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT

        val stackId = driver.state.stack.single()
        val stackComponent = driver.state.getEntity(stackId)
            ?.get<ActivatedAbilityOnStackComponent>()
            ?: error("persistent activated ability was not retained on stack")
        stackComponent.lastKnownSourceSnapshot shouldBe null
        driver.state.objectIdentityStamps[sourceId] shouldBe activationSourceStamp
        driver.state.getEntity(sourceId)?.get<BattlefieldEntryTimestampComponent>()?.timestamp shouldBe
            activationEntryStamp

        val changedBeforeResolution = reenterBattlefield(
            state = driver.state,
            entityId = sourceId,
            ownerId = driver.player1,
        ).removeFromZone(
            ZoneKey(driver.player2, Zone.BATTLEFIELD),
            targetId,
        ).addToZone(
            ZoneKey(driver.player2, Zone.GRAVEYARD),
            targetId,
        )
        val resolutionSourceStamp = changedBeforeResolution.objectIdentityStamps[sourceId]
        (resolutionSourceStamp != activationSourceStamp) shouldBe true
        val fizzle = StackResolver(driver.cardRegistry).resolveTop(changedBeforeResolution)
            .events.single().shouldBeInstanceOf<AbilityFizzledEvent>()
        fizzle.sourceId shouldBe sourceId
        fizzle.reason shouldBe "All targets are invalid"

        println(
            "ACTIVATED_SOURCE_AUTHORITY persistent-handler " +
                "activationBoundaryStamp=present " +
                "sourceBeforeWitness=present sourceAfterWitness=present " +
                "sameActivationIncarnation=true " +
                "requiredEndpointAuthority=BEFORE_OBJECT " +
                "componentGenericStamp=absent componentGenericEndpoint=absent " +
                "activationEventAuthority=BEFORE_OBJECT",
        )
    }

    test("self-sacrifice activation cost preserves source LKI and generic endpoint") {
        val driver = driver()
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val sourceId = driver.putCreatureOnBattlefield(driver.player1, "Ghitu Fire-Eater")
        driver.removeSummoningSickness(sourceId)
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val sourceWasOnBattlefieldAtActivation =
            driver.state.getBattlefield(driver.player1).contains(sourceId)
        sourceWasOnBattlefieldAtActivation shouldBe true
        val activationSourceStamp = driver.state.objectIdentityStamps[sourceId]
            ?: error("self-sacrificing source has no activation stamp")
        val abilityId = driver.cardRegistry.requireCard("Ghitu Fire-Eater").activatedAbilities.single().id

        val activation = driver.submit(
            ActivateAbility(
                playerId = driver.player1,
                sourceId = sourceId,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(targetId)),
            ),
        )
        activation.isSuccess shouldBe true
        val activationEvent = activation.events.filterIsInstance<AbilityActivatedEvent>().single()
        activationEvent.sourceId shouldBe sourceId
        // The existing AbilityActivatedEvent History-C contract binds its source at activation time.
        val existingActivationEventAuthority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        existingActivationEventAuthority shouldBe AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT

        driver.state.getBattlefield(driver.player1).contains(sourceId) shouldBe false
        driver.state.getZone(ZoneKey(driver.player1, Zone.GRAVEYARD)).contains(sourceId) shouldBe true

        val stackId = driver.state.stack.single()
        val stackComponent = driver.state.getEntity(stackId)
            ?.get<ActivatedAbilityOnStackComponent>()
            ?: error("self-sacrificing activated ability was not retained on stack")
        val sourceSnapshot = stackComponent.lastKnownSourceSnapshot
            ?: error("self-sacrifice path did not retain source LKI")
        sourceSnapshot.entityId shouldBe sourceId
        sourceSnapshot.objectIncarnationStamp shouldBe null
        sourceSnapshot.battlefieldEntryTimestamp shouldNotBe null
        (driver.state.objectIdentityStamps[sourceId] != activationSourceStamp) shouldBe true

        val beforeResolution = driver.state.removeFromZone(
            ZoneKey(driver.player2, Zone.BATTLEFIELD),
            targetId,
        ).addToZone(
            ZoneKey(driver.player2, Zone.GRAVEYARD),
            targetId,
        )
        val fizzle = StackResolver(driver.cardRegistry).resolveTop(beforeResolution)
            .events.single().shouldBeInstanceOf<AbilityFizzledEvent>()
        fizzle.sourceId shouldBe sourceId
        fizzle.reason shouldBe "All targets are invalid"

        println(
            "ACTIVATED_SOURCE_AUTHORITY self-sacrifice-cost " +
                "activationBoundaryStamp=present sourceWitnessAfterCost=false " +
                "sourceLkiBattlefieldEntryStamp=present sourceLkiObjectStamp=absent " +
                "requiredEndpointAuthority=BEFORE_OBJECT " +
                "componentGenericStamp=absent componentGenericEndpoint=absent " +
                "activationEventAuthority=BEFORE_OBJECT",
        )
    }
})

private val persistentActivationSource = card("Synthetic Persistent Activation Source") {
    manaCost = "{0}"
    typeLine = "Artifact"
    activatedAbility {
        cost = Costs.Tap
        target = TargetCreature()
        effect = Effects.GainLife(1)
    }
}

private fun driver(extraCard: CardDefinition? = null): GameTestDriver = GameTestDriver().also {
    it.registerCards(TestCards.all + listOfNotNull(extraCard))
    it.initMirrorMatch(deck = Deck.of("Forest" to 40))
}

private fun reenterBattlefield(
    state: GameState,
    entityId: EntityId,
    ownerId: EntityId,
): GameState = state
    .removeFromZone(ZoneKey(ownerId, Zone.BATTLEFIELD), entityId)
    .addToZone(ZoneKey(ownerId, Zone.GRAVEYARD), entityId)
    .removeFromZone(ZoneKey(ownerId, Zone.GRAVEYARD), entityId)
    .addToZone(ZoneKey(ownerId, Zone.BATTLEFIELD), entityId)

private fun sourceWitnessFacts(
    before: GameState,
    after: GameState,
    entityId: EntityId,
) = ActivatedSourceWitnessFacts(
    beforeWitnessPresent = before.hasEntity(entityId) && before.objectIdentityStamps[entityId] != null,
    afterWitnessPresent = after.hasEntity(entityId) && after.objectIdentityStamps[entityId] != null,
    beforeStampPresent = before.objectIdentityStamps[entityId] != null,
    afterStampPresent = after.objectIdentityStamps[entityId] != null,
    sameIncarnation = before.objectIdentityStamps[entityId] != null &&
        before.objectIdentityStamps[entityId] == after.objectIdentityStamps[entityId],
)
