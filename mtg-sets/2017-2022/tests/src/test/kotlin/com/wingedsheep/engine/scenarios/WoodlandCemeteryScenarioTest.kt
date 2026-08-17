package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.isd.cards.WoodlandCemetery
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Woodland Cemetery (ISD #249)
 * Enters tapped unless its controller has a Swamp or Forest; {T}: add {B} or {G}.
 */
class WoodlandCemeteryScenarioTest : FunSpec({

    val blackAbilityId = WoodlandCemetery.activatedAbilities[0].id
    val greenAbilityId = WoodlandCemetery.activatedAbilities[1].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(WoodlandCemetery)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("enters tapped without a Swamp or Forest") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val cemetery = driver.putCardInHand(player, "Woodland Cemetery")

        driver.playLand(player, cemetery).isSuccess shouldBe true
        val battlefieldCemetery = driver.findPermanent(player, "Woodland Cemetery")
        battlefieldCemetery shouldNotBe null
        driver.isTapped(battlefieldCemetery!!) shouldBe true
    }

    test("enters untapped when its controller has a Forest") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putLandOnBattlefield(player, "Forest")
        val cemetery = driver.putCardInHand(player, "Woodland Cemetery")

        driver.playLand(player, cemetery).isSuccess shouldBe true
        val battlefieldCemetery = driver.findPermanent(player, "Woodland Cemetery")
        battlefieldCemetery shouldNotBe null
        driver.isTapped(battlefieldCemetery!!) shouldBe false
    }

    test("its mana abilities add black or green mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val cemetery = driver.putPermanentOnBattlefield(player, "Woodland Cemetery")

        driver.submit(ActivateAbility(player, cemetery, blackAbilityId)).isSuccess shouldBe true
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.black shouldBe 1

        driver.untapPermanent(cemetery)
        driver.submit(ActivateAbility(player, cemetery, greenAbilityId)).isSuccess shouldBe true
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.green shouldBe 1
    }
})
