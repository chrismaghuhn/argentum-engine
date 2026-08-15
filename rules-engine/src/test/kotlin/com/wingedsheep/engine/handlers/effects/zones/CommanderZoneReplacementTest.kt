package com.wingedsheep.engine.handlers.effects.zones

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.MoveToZoneEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Red conformance tests for CR 903.9b. The pending zone-change decision must
 * happen before the physical hand/library transition, including COMMAND -> HAND.
 */
class CommanderZoneReplacementTest : FunSpec({

    val playerId = EntityId.generate()
    val commanderId = EntityId.generate()
    val executor = MoveToZoneEffectExecutor(CardRegistry())

    fun stateWithCommanderIn(zone: Zone): GameState {
        val commander = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Test Commander",
                name = "Test Commander",
                manaCost = ManaCost.parse("{2}{R}"),
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                oracleText = "",
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
            CommanderComponent(ownerId = playerId),
        )
        return GameState(format = Format.Commander())
            .withEntity(playerId, ComponentContainer.EMPTY)
            .withEntity(commanderId, commander)
            .addToZone(ZoneKey(playerId, zone), commanderId)
            .copy(turnOrder = listOf(playerId))
    }

    fun moveToHand(state: GameState) = executor.execute(
        state = state,
        effect = MoveToZoneEffect(
            target = EffectTarget.ContextTarget(0),
            destination = Zone.HAND,
        ),
        context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            targets = listOf(ChosenTarget.Permanent(commanderId)),
        ),
    )

    test("commander from battlefield to hand pauses before moving") {
        val result = moveToHand(stateWithCommanderIn(Zone.BATTLEFIELD))

        result.isPaused shouldBe true
        result.state.getZone(ZoneKey(playerId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }

    test("CZ-13: commander from command zone to hand also pauses") {
        val result = moveToHand(stateWithCommanderIn(Zone.COMMAND))

        result.isPaused shouldBe true
        result.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }
})
