package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mir.cards.FireDiamond
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Fire Diamond (MIR #302) — This artifact enters tapped. {T}: Add {R}.
 */
class FireDiamondScenarioTest : FunSpec({

    val abilityId = FireDiamond.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + FireDiamond)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Fire Diamond enters tapped and produces red mana after it is untapped") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val diamondInHand = driver.putCardInHand(player, "Fire Diamond")
        driver.giveMana(player, Color.RED, 2)

        driver.castSpell(player, diamondInHand).isSuccess shouldBe true
        driver.bothPass()

        val diamond = driver.findPermanent(player, "Fire Diamond")
        diamond shouldNotBe null
        driver.isTapped(diamond!!) shouldBe true

        driver.untapPermanent(diamond)
        driver.submit(
            ActivateAbility(playerId = player, sourceId = diamond, abilityId = abilityId)
        ).isSuccess shouldBe true

        driver.isTapped(diamond) shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!.red shouldBe 1
    }

    test("Fire Diamond cannot activate while its enters-tapped state remains") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val diamond = driver.putPermanentOnBattlefield(player, "Fire Diamond")
        driver.tapPermanent(diamond)

        val result = driver.submit(
            ActivateAbility(playerId = player, sourceId = diamond, abilityId = abilityId)
        )

        result.isSuccess shouldBe false
    }
})
