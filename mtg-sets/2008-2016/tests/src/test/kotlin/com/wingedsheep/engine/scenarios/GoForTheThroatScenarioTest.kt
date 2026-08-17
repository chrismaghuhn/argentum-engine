package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Go for the Throat (MBS #43): destroy target nonartifact creature. */
class GoForTheThroatScenarioTest : io.kotest.core.spec.style.FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 20), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("destroys a nonartifact creature") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val spell = driver.putCardInHand(caster, "Go for the Throat")
        driver.giveMana(caster, Color.BLACK, 2)

        driver.castSpell(caster, spell, listOf(creature)).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(opponent, "Grizzly Bears") shouldBe null
        driver.getGraveyardCardNames(opponent) shouldContain "Grizzly Bears"
    }

    test("cannot target an artifact creature") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val creature = driver.putCreatureOnBattlefield(opponent, "Artifact Creature")
        val spell = driver.putCardInHand(caster, "Go for the Throat")
        driver.giveMana(caster, Color.BLACK, 2)

        driver.castSpell(caster, spell, listOf(creature)).isSuccess shouldBe false
        driver.findPermanent(opponent, "Artifact Creature") shouldNotBe null
    }
})
