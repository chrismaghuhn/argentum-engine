package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.SpellFizzledEvent
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
import com.wingedsheep.engine.mechanics.StateBasedActionChecker
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.ReplacementEffectSourceComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.RedirectZoneChange
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Rules regression coverage for the stack disposition of an instant/sorcery copy.
 *
 * These expectations are intentionally RED on the accepted characterization parent: CR 608.2b
 * removes an all-illegal-target spell from the stack and puts it into its owner's graveyard, and
 * CR 704.5e then makes a spell copy cease to exist as a state-based action once it is in that zone.
 * The paused-resolution case is a regression expectation for the later Fix-06 timing boundary.
 */
class SpellCopyFizzleZoneLifecycleCharacterizationTest : FunSpec({

    data class Fixture(
        val state: GameState,
        val playerId: EntityId,
        val spellId: EntityId,
        val stamp: Long,
    )

    fun fixture(
        copy: Boolean,
        withInvalidTarget: Boolean = true,
        resolvingEffect: Effect? = null,
    ): Fixture {
        val playerId = EntityId.generate()
        val opponentId = EntityId.generate()
        val spellId = EntityId.generate()
        val invalidTargetId = EntityId.generate()
        val card = CardComponent(
            cardDefinitionId = "spell-copy-fizzle-characterization",
            name = "Spell Copy Fizzle Characterization",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine.instant(),
            oracleText = "",
            ownerId = playerId,
        )

        var spell = ComponentContainer.of(
            card,
            OwnerComponent(playerId),
            ControllerComponent(playerId),
            SpellOnStackComponent(
                casterId = playerId,
                resolvingSpellEffectOverride = resolvingEffect,
            ),
        )
        if (copy) {
            spell = spell.with(
                CopyOfComponent(
                    originalCardDefinitionId = "original-spell",
                    copiedCardDefinitionId = "copied-spell",
                )
            )
        }
        if (withInvalidTarget) {
            spell = spell.with(
                TargetsComponent(
                    targets = listOf(ChosenTarget.Permanent(invalidTargetId)),
                    targetRequirements = listOf(
                        TargetObject(filter = TargetFilter(GameObjectFilter.Creature))
                    ),
                )
            )
        }

        val stamp = 101L
        val state = GameState(
            activePlayerId = playerId,
            priorityPlayerId = playerId,
            turnOrder = listOf(playerId, opponentId),
        )
            .withEntity(playerId, ComponentContainer.of(PlayerComponent("P1"), LifeTotalComponent(20)))
            .withEntity(opponentId, ComponentContainer.of(PlayerComponent("P2"), LifeTotalComponent(20)))
            .withEntity(spellId, spell)
            .copy(
                stack = listOf(spellId),
                objectIdentityStamps = mapOf(spellId to stamp),
                nextObjectIdentityStamp = stamp + 1L,
            )
        return Fixture(state, playerId, spellId, stamp)
    }

    fun withGraveyardRedirect(fixture: Fixture): GameState {
        val redirectSourceId = EntityId.generate()
        val redirect = RedirectZoneChange(
            newDestination = Zone.EXILE,
            appliesTo = EventPattern.ZoneChangeEvent(
                filter = GameObjectFilter.Any,
                from = Zone.STACK,
                to = Zone.GRAVEYARD,
            ),
        )
        val redirectSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "spell-copy-fizzle-replacement",
                name = "Spell Copy Fizzle Replacement",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                oracleText = "",
                ownerId = fixture.playerId,
            ),
            OwnerComponent(fixture.playerId),
            ControllerComponent(fixture.playerId),
            ReplacementEffectSourceComponent(listOf(redirect)),
        )
        return fixture.state
            .withEntity(redirectSourceId, redirectSource)
            .addToZone(ZoneKey(fixture.playerId, Zone.BATTLEFIELD), redirectSourceId)
    }

    test("copy fizzle uses normal disposition before phantom-copy SBA cleanup") {
        val fixture = fixture(copy = true)
        val result = StackResolver(CardRegistry()).resolveTop(fixture.state)

        result.error shouldBe null
        result.events.map { it::class.simpleName } shouldBe
            listOf("SpellFizzledEvent", "ZoneChangeEvent")
        result.events.filterIsInstance<SpellFizzledEvent>().single().spellEntityId shouldBe fixture.spellId
        result.events.filterIsInstance<ZoneChangeEvent>().single().toZone shouldBe Zone.GRAVEYARD

        result.state.stack shouldBe emptyList()
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
        result.state.continuationStack shouldBe emptyList()

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(fixture.spellId) shouldBe false
    }

    test("StackResolver-produced copy uses normal fizzle disposition") {
        val sourceFixture = fixture(copy = false)
        val copyResult = StackResolver(CardRegistry()).putSpellCopy(
            state = sourceFixture.state,
            sourceSpellId = sourceFixture.spellId,
        )

        copyResult.error shouldBe null
        val copyId = copyResult.state.stack.last()
        copyResult.state.getEntity(copyId)?.has<CopyOfComponent>() shouldBe true
        val copyStamp = copyResult.state.objectIdentityStamps[copyId]

        val result = StackResolver(CardRegistry()).resolveTop(copyResult.state)

        result.error shouldBe null
        result.events.map { it::class.simpleName } shouldBe
            listOf("SpellFizzledEvent", "ZoneChangeEvent")
        result.state.hasEntity(copyId) shouldBe true
        result.state.getZone(ZoneKey(sourceFixture.playerId, Zone.GRAVEYARD)).contains(copyId) shouldBe true
        result.state.objectIdentityStamps[copyId] shouldNotBe copyStamp

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(copyId) shouldBe false
    }

    test("ordinary non-copy fizzle is the zone-transition control") {
        val fixture = fixture(copy = false)
        val result = StackResolver(CardRegistry()).resolveTop(fixture.state)

        result.error shouldBe null
        result.events.map { it::class.simpleName } shouldBe
            listOf("SpellFizzledEvent", "ZoneChangeEvent")
        val zoneChange = result.events.filterIsInstance<ZoneChangeEvent>().single()
        zoneChange.toZone shouldBe Zone.GRAVEYARD
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
        result.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
    }

    test("successful copied spell resolution uses normal disposition before phantom-copy SBA cleanup") {
        val fixture = fixture(copy = true, withInvalidTarget = false)
        val result = StackResolver(CardRegistry()).resolveTop(fixture.state)

        result.error shouldBe null
        result.events.filterIsInstance<ZoneChangeEvent>().single().toZone shouldBe Zone.GRAVEYARD
        result.state.stack shouldBe emptyList()
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe 102L

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(fixture.spellId) shouldBe false
    }

    test("paused copied spell stays on the resolving object before the pending effect resumes") {
        val fixture = fixture(
            copy = true,
            withInvalidTarget = false,
            resolvingEffect = MayEffect(Effects.DrawCards(1)),
        )
        val result = StackResolver(CardRegistry()).resolveTop(fixture.state)

        result.error shouldBe null
        result.isPaused shouldBe true
        result.events.filterIsInstance<ZoneChangeEvent>() shouldBe emptyList()
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe fixture.stamp
    }

    test("copy fizzle honors a replacement that redirects stack to graveyard") {
        val fixture = fixture(copy = true)
        val state = withGraveyardRedirect(fixture)

        ZoneMovementUtils.checkZoneChangeRedirect(
            state = state,
            entityId = fixture.spellId,
            fromZone = Zone.STACK,
            toZone = Zone.GRAVEYARD,
        ).destinationZone shouldBe Zone.EXILE

        val result = StackResolver(CardRegistry()).resolveTop(state)
        result.error shouldBe null
        result.events.filterIsInstance<ZoneChangeEvent>().single().toZone shouldBe Zone.EXILE
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.EXILE)).contains(fixture.spellId) shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldNotBe fixture.stamp

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(fixture.spellId) shouldBe false
    }

    test("generic stack-to-zone transition creates the destination incarnation and event") {
        val fixture = fixture(copy = true, withInvalidTarget = false)
        val transition = ZoneTransitionService.moveToZoneWithReplacements(
            state = fixture.state,
            entityId = fixture.spellId,
            destinationZone = Zone.GRAVEYARD,
            fromZoneKey = ZoneKey(fixture.playerId, Zone.STACK),
        )

        transition.error shouldBe null
        transition.events.map { it::class.simpleName } shouldBe listOf("ZoneChangeEvent")
        transition.events.filterIsInstance<ZoneChangeEvent>().single().fromZone shouldBe Zone.STACK
        transition.events.filterIsInstance<ZoneChangeEvent>().single().toZone shouldBe Zone.GRAVEYARD
        transition.state.hasEntity(fixture.spellId) shouldBe true
        transition.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        transition.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
        transition.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
    }

    test("existing phantom-copy SBA removes a copy that has already entered a non-stack zone") {
        val fixture = fixture(copy = true, withInvalidTarget = false)
        val inGraveyard = fixture.state
            .copy(stack = emptyList())
            .addToZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD), fixture.spellId)

        inGraveyard.hasEntity(fixture.spellId) shouldBe true
        val sba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(inGraveyard)

        sba.error shouldBe null
        sba.events shouldBe emptyList()
        sba.state.hasEntity(fixture.spellId) shouldBe false
        sba.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
    }

    test("countered copied spell uses the existing zone transition and phantom-copy control") {
        val fixture = fixture(copy = true, withInvalidTarget = false)
        val result = StackResolver(CardRegistry()).counterSpell(fixture.state, fixture.spellId)

        result.error shouldBe null
        result.events.map { it::class.simpleName } shouldBe
            listOf("SpellCounteredEvent", "ZoneChangeEvent")
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.objectIdentityStamps[fixture.spellId] shouldBe 102L
        result.state.getEntity(fixture.spellId)?.has<CopyOfComponent>() shouldBe true

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(fixture.spellId) shouldBe false
    }
})
