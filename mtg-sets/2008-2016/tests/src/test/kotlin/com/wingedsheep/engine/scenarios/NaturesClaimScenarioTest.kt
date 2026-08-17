package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Nature's Claim (WWK #108): destroy an artifact or enchantment; its controller gains 4 life. */
class NaturesClaimScenarioTest : io.kotest.core.spec.style.FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 20), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("destroys the target and gains life for its controller") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val target = driver.putPermanentOnBattlefield(opponent, "Test Enchantment")
        val spell = driver.putCardInHand(caster, "Nature's Claim")
        driver.giveMana(caster, Color.GREEN, 1)

        val result = driver.castSpell(caster, spell, listOf(target))
        result.isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(opponent, "Test Enchantment") shouldBe null
        driver.getLifeTotal(opponent) shouldBe 24
    }

    test("cannot target a creature") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val spell = driver.putCardInHand(caster, "Nature's Claim")
        driver.giveMana(caster, Color.GREEN, 1)

        driver.castSpell(caster, spell, listOf(creature)).isSuccess shouldBe false
        driver.findPermanent(opponent, "Grizzly Bears") shouldNotBe null
    }
})
