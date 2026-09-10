package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.SpellFizzledEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
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
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.RedirectZoneChange
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Post-Fix-06 evidence for SpellFizzledEvent's pre-disposition subject authority. */
class SpellFizzledPostRulesAuthorityRecharacterizationTest : FunSpec({

    data class Fixture(
        val state: GameState,
        val playerId: EntityId,
        val spellId: EntityId,
        val beforeStamp: Long,
    )

    fun fixture(copy: Boolean): Fixture {
        val playerId = EntityId("post-rules-fizzle-player")
        val opponentId = EntityId("post-rules-fizzle-opponent")
        val spellId = EntityId(if (copy) "post-rules-fizzle-copy" else "post-rules-fizzle-spell")
        val invalidTargetId = EntityId("post-rules-fizzle-invalid-target")
        val card = CardComponent(
            cardDefinitionId = "post-rules-fizzle-card",
            name = "Post-Rules Fizzle Spell",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine.instant(),
            oracleText = "",
            ownerId = playerId,
        )

        var spell = ComponentContainer.of(
            card,
            OwnerComponent(playerId),
            ControllerComponent(playerId),
            SpellOnStackComponent(casterId = playerId),
            TargetsComponent(
                targets = listOf(ChosenTarget.Permanent(invalidTargetId)),
                targetRequirements = listOf(
                    TargetObject(filter = TargetFilter(GameObjectFilter.Creature))
                ),
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

        val beforeStamp = 101L
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
                objectIdentityStamps = mapOf(spellId to beforeStamp),
                nextObjectIdentityStamp = beforeStamp + 1L,
            )

        return Fixture(state, playerId, spellId, beforeStamp)
    }

    fun withExileRedirect(fixture: Fixture): GameState {
        val redirectSourceId = EntityId("post-rules-fizzle-redirect")
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
                cardDefinitionId = "post-rules-fizzle-replacement",
                name = "Post-Rules Fizzle Replacement",
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

    fun assertPreDispositionSubject(
        result: com.wingedsheep.engine.core.ExecutionResult,
        fixture: Fixture,
        destination: Zone,
    ) {
        result.error shouldBe null
        result.events.map { it::class.simpleName ?: "UnknownGameEvent" } shouldBe
            listOf("SpellFizzledEvent", "ZoneChangeEvent")

        val fizzle = result.events.filterIsInstance<SpellFizzledEvent>().single()
        val zoneChange = result.events.filterIsInstance<ZoneChangeEvent>().single()
        fizzle.spellEntityId shouldBe fixture.spellId
        zoneChange.entityId shouldBe fixture.spellId
        // The existing event schema leaves fromZone null for stack disposition; the committed
        // before-state stack membership is the authority for the pre-disposition subject.
        zoneChange.fromZone shouldBe null
        zoneChange.toZone shouldBe destination
        result.events.indexOf(fizzle) shouldBe 0
        result.events.indexOf(zoneChange) shouldBe 1

        // The raw event identifies the before-state stack object; the destination is a new object.
        fixture.state.objectIdentityStamps[fixture.spellId] shouldBe fixture.beforeStamp
        result.state.objectIdentityStamps[fixture.spellId] shouldNotBe fixture.beforeStamp
        result.state.getEntity(fixture.spellId)?.has<SpellOnStackComponent>() shouldBe false
        result.state.stack shouldBe emptyList()
    }

    test("ordinary all-illegal-target fizzle precedes destination incarnation") {
        val fixture = fixture(copy = false)
        val result = StackResolver(CardRegistry()).resolveTop(fixture.state)

        assertPreDispositionSubject(result, fixture, Zone.GRAVEYARD)
    }

    test("copied all-illegal-target fizzle uses the same before subject then SBA cleanup") {
        val fixture = fixture(copy = true)
        val result = StackResolver(CardRegistry()).resolveTop(fixture.state)

        assertPreDispositionSubject(result, fixture, Zone.GRAVEYARD)
        result.state.hasEntity(fixture.spellId) shouldBe true
        result.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe true
        result.state.getEntity(fixture.spellId)?.has<CopyOfComponent>() shouldBe true

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(fixture.spellId) shouldBe false
        afterSba.state.getZone(ZoneKey(fixture.playerId, Zone.GRAVEYARD)).contains(fixture.spellId) shouldBe false
    }

    test("replacement redirect preserves the pre-disposition subject authority") {
        val fixture = fixture(copy = false)
        val state = withExileRedirect(fixture)

        ZoneMovementUtils.checkZoneChangeRedirect(
            state = state,
            entityId = fixture.spellId,
            fromZone = Zone.STACK,
            toZone = Zone.GRAVEYARD,
        ).destinationZone shouldBe Zone.EXILE

        val result = StackResolver(CardRegistry()).resolveTop(state)
        assertPreDispositionSubject(result, fixture, Zone.EXILE)
    }
})
