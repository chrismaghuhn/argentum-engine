package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.TimingRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * RED characterization for War Room (CMR #361).
 *
 * Current Oracle (Scryfall):
 *
 *   {T}: Add {C}.
 *   {3}, {T}, Pay life equal to the number of colors in your commanders' color identity: Draw a card.
 *
 * The colorless mana mode is expressible with existing primitives. The test-only copy below uses
 * the closest existing life-cost primitive, a fixed two-life payment, only to make the missing
 * generic axis observable. It is intentionally not a production card definition: War Room's
 * activation cost must be evaluated from the activating player's commander registry before
 * legal actions are exposed and before the cost is paid.
 *
 * Scryfall rulings used here:
 * - color identity is fixed before the game begins, even while a commander is in a hidden zone;
 * - a player without a commander cannot activate the draw ability;
 * - a commander with no colors contributes zero life to the cost.
 */
class WarRoomScenarioTest : FunSpec({

    val warRoom = card("War Room") {
        typeLine = "Land"
        colorIdentity = ""
        oracleText = "{T}: Add {C}.\n" +
            "{3}, {T}, Pay life equal to the number of colors in your commanders' color identity: Draw a card."

        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }

        activatedAbility {
            // Characterization only: this fixed value cannot model War Room's commander-derived cost.
            cost = Costs.Composite(Costs.Mana("{3}"), Costs.Tap, Costs.PayLife(2))
            effect = Effects.DrawCards(1)
        }
    }

    val monoCommander = card("Test Mono Commander") {
        manaCost = "{G}"
        colorIdentity = "G"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
    }

    fun createDriver(withCommander: Boolean): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(warRoom, monoCommander))
        driver.initMultiplayer(
            decks = listOf(Deck.of("Forest" to 40), Deck.of("Forest" to 40)),
            format = if (withCommander) Format.Commander() else Format.Standard,
            commanders = if (withCommander) {
                listOf(monoCommander.name, monoCommander.name)
            } else {
                emptyList()
            },
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("the mana ability produces exactly one colorless mana") {
        val driver = createDriver(withCommander = true)
        val player = driver.activePlayer!!
        val warRoomId = driver.putLandOnBattlefield(player, warRoom.name)
        val manaAbilityId = driver.cardRegistry.getCard(warRoom.name)!!.activatedAbilities[0].id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = warRoomId, abilityId = manaAbilityId)
        ).error shouldBe null

        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.colorless shouldBe 1
    }

    test("a player without a commander cannot activate the draw ability") {
        val driver = createDriver(withCommander = false)
        val player = driver.activePlayer!!
        val warRoomId = driver.putLandOnBattlefield(player, warRoom.name)
        driver.giveColorlessMana(player, 3)
        val drawAbilityId = driver.cardRegistry.getCard(warRoom.name)!!.activatedAbilities[1].id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = warRoomId, abilityId = drawAbilityId)
        ).error shouldNotBe null
    }

    test("a one-color commander makes the draw ability payable with one life") {
        val driver = createDriver(withCommander = true)
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 1)
        val warRoomId = driver.putLandOnBattlefield(player, warRoom.name)
        driver.giveColorlessMana(player, 3)
        val drawAbilityId = driver.cardRegistry.getCard(warRoom.name)!!.activatedAbilities[1].id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = warRoomId, abilityId = drawAbilityId)
        ).error shouldBe null
    }
})
