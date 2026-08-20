package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.cmr.cards.WarRoom
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Focused behavioral evidence for War Room's two activated abilities.
 *
 * Current Oracle (Scryfall):
 *
 *   {T}: Add {C}.
 *   {3}, {T}, Pay life equal to the number of colors in your commanders' color identity: Draw a card.
 *
 * Scryfall rulings used here:
 * - color identity is fixed before the game begins, even while a commander is in a hidden zone;
 * - a player without a commander cannot activate the draw ability;
 * - a commander with no colors contributes zero life to the cost.
 */
class WarRoomScenarioTest : FunSpec({

    val monoCommander = card("Test Mono Commander") {
        manaCost = "{G}"
        colorIdentity = "G"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
    }

    fun createDriver(withCommander: Boolean): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + monoCommander)
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
        val warRoomId = driver.putLandOnBattlefield(player, WarRoom.name)
        val manaAbilityId = driver.cardRegistry.getCard(WarRoom.name)!!.activatedAbilities[0].id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = warRoomId, abilityId = manaAbilityId)
        ).error shouldBe null

        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.colorless shouldBe 1
    }

    test("a player without a commander cannot activate the draw ability") {
        val driver = createDriver(withCommander = false)
        val player = driver.activePlayer!!
        val warRoomId = driver.putLandOnBattlefield(player, WarRoom.name)
        driver.giveColorlessMana(player, 3)
        val drawAbilityId = driver.cardRegistry.getCard(WarRoom.name)!!.activatedAbilities[1].id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = warRoomId, abilityId = drawAbilityId)
        ).error shouldNotBe null
    }

    test("a one-color commander makes the draw ability payable with one life") {
        val driver = createDriver(withCommander = true)
        val player = driver.activePlayer!!
        driver.setLifeTotal(player, 1)
        val warRoomId = driver.putLandOnBattlefield(player, WarRoom.name)
        driver.giveColorlessMana(player, 3)
        val drawAbilityId = driver.cardRegistry.getCard(WarRoom.name)!!.activatedAbilities[1].id

        driver.submit(
            ActivateAbility(playerId = player, sourceId = warRoomId, abilityId = drawAbilityId)
        ).error shouldBe null
    }
})
