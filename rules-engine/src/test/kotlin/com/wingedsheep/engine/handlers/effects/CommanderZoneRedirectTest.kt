package com.wingedsheep.engine.handlers.effects

import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Supertype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * `ZoneMovementUtils.checkZoneChangeRedirect` does not implement the post-move 903.9a choice.
 * Graveyard/exile transitions always reach their requested zone first; the SBA then prompts the
 * owner or answers YES automatically when `alwaysDivertToCommand` is enabled. Hand/library moves
 * remain inside the pending 903.9b replacement pipeline.
 */
class CommanderZoneRedirectTest : FunSpec({

    val ownerId = EntityId.generate()
    val cmdrId = EntityId.generate()

    val alwaysDivertCommander = Format.Commander(alwaysDivertToCommand = true)

    fun stateWithCommander(format: Format, commanderZone: Zone): GameState {
        val cardContainer = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Test Commander",
                name = "Test Commander",
                manaCost = ManaCost.parse("{2}{R}"),
                typeLine = TypeLine(
                    supertypes = setOf(Supertype.LEGENDARY),
                    cardTypes = setOf(CardType.CREATURE),
                    subtypes = setOf(Subtype("Human")),
                ),
                oracleText = "",
                baseStats = CreatureStats(2, 2),
                colors = setOf(com.wingedsheep.sdk.core.Color.RED),
                ownerId = ownerId,
                spellEffect = null,
            ),
            OwnerComponent(ownerId),
            CommanderComponent(ownerId = ownerId),
        )
        return GameState(format = format)
            .withEntity(ownerId, ComponentContainer.EMPTY)
            .withEntity(cmdrId, cardContainer)
            .addToZone(ZoneKey(ownerId, commanderZone), cmdrId)
            .copy(turnOrder = listOf(ownerId))
    }

    test("destroyed commander reaches graveyard before the 903.9a SBA") {
        val state = stateWithCommander(alwaysDivertCommander, Zone.BATTLEFIELD)
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.BATTLEFIELD, Zone.GRAVEYARD,
        )
        result.destinationZone shouldBe Zone.GRAVEYARD
    }

    test("milled commander reaches graveyard before the 903.9a SBA") {
        val state = stateWithCommander(alwaysDivertCommander, Zone.LIBRARY)
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.LIBRARY, Zone.GRAVEYARD,
        )
        result.destinationZone shouldBe Zone.GRAVEYARD
    }

    test("exiled commander reaches exile before the 903.9a SBA") {
        val state = stateWithCommander(alwaysDivertCommander, Zone.BATTLEFIELD)
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.BATTLEFIELD, Zone.EXILE,
        )
        result.destinationZone shouldBe Zone.EXILE
    }

    test("bounced commander is decided by the 903.9b replacement pipeline") {
        val state = stateWithCommander(alwaysDivertCommander, Zone.BATTLEFIELD)
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.BATTLEFIELD, Zone.HAND,
        )
        result.destinationZone shouldBe Zone.HAND
    }

    test("commander leaving the command zone is not redirected back") {
        val state = stateWithCommander(alwaysDivertCommander, Zone.COMMAND)
        // The commander is on the stack heading toward the battlefield (cast resolution).
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.COMMAND, Zone.BATTLEFIELD,
        )
        result.destinationZone shouldBe Zone.BATTLEFIELD
    }

    test("alwaysDivertToCommand = false leaves the destination unchanged") {
        val state = stateWithCommander(
            Format.Commander(alwaysDivertToCommand = false),
            Zone.BATTLEFIELD,
        )
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.BATTLEFIELD, Zone.GRAVEYARD,
        )
        result.destinationZone shouldBe Zone.GRAVEYARD
    }

    test("default Commander format defers to the SBA — no synchronous redirect") {
        // The new default (alwaysDivertToCommand = false) hands the choice to
        // CommanderZoneChoiceCheck, so the replacement-time check must not silently divert.
        val state = stateWithCommander(Format.Commander(), Zone.BATTLEFIELD)
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.BATTLEFIELD, Zone.GRAVEYARD,
        )
        result.destinationZone shouldBe Zone.GRAVEYARD
    }

    test("Standard format does not redirect even with CommanderComponent attached") {
        val state = stateWithCommander(Format.Standard, Zone.BATTLEFIELD)
        val result = ZoneMovementUtils.checkZoneChangeRedirect(
            state, cmdrId, Zone.BATTLEFIELD, Zone.GRAVEYARD,
        )
        result.destinationZone shouldBe Zone.GRAVEYARD
    }
})
