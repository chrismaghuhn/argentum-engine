package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.SpellFizzledEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.mechanics.StateBasedActionChecker
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Regression evidence for the copy-specific SpellFizzledEvent lifecycle. */
class SpellFizzledEmitterAuthorityCharacterizationTest : FunSpec({
    test("a copied spell fizzles through normal disposition before phantom-copy SBA cleanup") {
        val player = EntityId("copy-fizzle-player")
        val opponent = EntityId("copy-fizzle-opponent")
        val copyId = EntityId("copy-fizzle-stack-object")
        val invalidTarget = EntityId("copy-fizzle-invalid-target")
        val requirement = TargetObject(filter = TargetFilter(GameObjectFilter.Creature))
        val card = CardComponent(
            cardDefinitionId = "copy-fizzle-card",
            name = "Copy Fizzle",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine.instant(),
            oracleText = "",
            ownerId = player,
        )
        val copy = ComponentContainer.of(
            card,
            OwnerComponent(player),
            ControllerComponent(player),
            SpellOnStackComponent(casterId = player),
            CopyOfComponent(
                originalCardDefinitionId = "original-spell",
                copiedCardDefinitionId = "copied-spell",
            ),
            TargetsComponent(
                targets = listOf(ChosenTarget.Permanent(invalidTarget)),
                targetRequirements = listOf(requirement),
            ),
        )
        val before = GameState(
            activePlayerId = player,
            priorityPlayerId = player,
            turnOrder = listOf(player, opponent),
        )
            .withEntity(player, ComponentContainer.of(PlayerComponent("P1"), LifeTotalComponent(20)))
            .withEntity(opponent, ComponentContainer.of(PlayerComponent("P2"), LifeTotalComponent(20)))
            .withEntity(copyId, copy)
            .copy(
                stack = listOf(copyId),
                objectIdentityStamps = mapOf(copyId to 101L),
                nextObjectIdentityStamp = 102L,
            )

        val result = StackResolver(CardRegistry()).resolveTop(before)

        result.error shouldBe null
        result.events.map { it::class.simpleName ?: "UnknownGameEvent" } shouldBe
            listOf("SpellFizzledEvent", "ZoneChangeEvent")
        val event = result.events.filterIsInstance<SpellFizzledEvent>().single()
        event.spellEntityId shouldBe copyId
        event.reason shouldBe "All targets are invalid"
        result.events.filterIsInstance<ZoneChangeEvent>().single().toZone shouldBe Zone.GRAVEYARD

        before.stack shouldBe listOf(copyId)
        before.objectIdentityStamps[copyId] shouldBe 101L
        result.state.hasEntity(copyId) shouldBe true
        result.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).contains(copyId) shouldBe true
        result.state.objectIdentityStamps[copyId] shouldBe 102L

        val afterSba = StateBasedActionChecker(cardRegistry = CardRegistry()).checkAndApply(result.state)
        afterSba.error shouldBe null
        afterSba.state.hasEntity(copyId) shouldBe false
    }
})
