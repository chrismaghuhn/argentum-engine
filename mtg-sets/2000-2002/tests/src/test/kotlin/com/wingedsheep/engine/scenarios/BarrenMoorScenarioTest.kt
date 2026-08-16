package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ons.cards.BarrenMoor
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Barren Moor (ONS #312) — enters tapped; {T}: Add {B}; Cycling {B}.
 */
class BarrenMoorScenarioTest : FunSpec({

    val manaAbilityId = BarrenMoor.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BarrenMoor)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Barren Moor enters tapped and produces black mana after untapping") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val landCard = driver.putCardInHand(player, "Barren Moor")

        driver.playLand(player, landCard).isSuccess shouldBe true
        val land = driver.findPermanent(player, "Barren Moor")
        land shouldNotBe null
        driver.isTapped(land!!) shouldBe true

        driver.untapPermanent(land)
        driver.submit(
            ActivateAbility(playerId = player, sourceId = land, abilityId = manaAbilityId)
        ).isSuccess shouldBe true
        driver.isTapped(land) shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!.black shouldBe 1
    }

    test("cycling Barren Moor pays {B}, discards it, and draws a card") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val moor = driver.putCardInHand(player, "Barren Moor")
        driver.putCardOnTopOfLibrary(player, "Forest")
        driver.giveMana(player, Color.BLACK, 1)

        val result = driver.submit(CycleCard(playerId = player, cardId = moor))

        withClue("Cycling should resolve without a pending decision: ${result.error}") {
            result.isSuccess shouldBe true
        }
        driver.getGraveyardCardNames(player) shouldContain "Barren Moor"
        driver.findCardInHand(player, "Forest") shouldNotBe null
    }

    test("Barren Moor cannot cycle without {B}") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val moor = driver.putCardInHand(player, "Barren Moor")

        val result = driver.submit(CycleCard(playerId = player, cardId = moor))

        result.isSuccess shouldBe false
        driver.findCardInHand(player, "Barren Moor") shouldBe moor
    }
})
