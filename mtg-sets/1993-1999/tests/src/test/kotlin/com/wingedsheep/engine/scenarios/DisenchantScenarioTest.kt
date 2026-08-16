package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.lea.cards.Disenchant
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Disenchant (LEA #18) — "Destroy target artifact or enchantment."
 *
 * The scenarios pin both branches of the target restriction and the actual zone change. The
 * creature negative case ensures the definition does not accidentally broaden the target filter
 * to every permanent.
 */
class DisenchantScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Disenchant)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castDisenchant(driver: GameTestDriver, caster: com.wingedsheep.sdk.model.EntityId,
                       target: com.wingedsheep.sdk.model.EntityId) {
        val spell = driver.putCardInHand(caster, "Disenchant")
        driver.giveMana(caster, Color.WHITE, 2)
        driver.castSpellWithTargets(caster, spell, listOf(ChosenTarget.Permanent(target)))
            .isSuccess shouldBe true
        driver.bothPass()
    }

    test("destroys a target artifact") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val artifact = driver.putPermanentOnBattlefield(opponent, "Mind Stone")

        castDisenchant(driver, you, artifact)

        driver.findPermanent(opponent, "Mind Stone") shouldBe null
    }

    test("destroys a target enchantment") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val enchantment = driver.putPermanentOnBattlefield(opponent, "Pacifism")

        castDisenchant(driver, you, enchantment)

        driver.findPermanent(opponent, "Pacifism") shouldBe null
    }

    test("cannot target a creature") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val spell = driver.putCardInHand(you, "Disenchant")
        driver.giveMana(you, Color.WHITE, 2)

        val result = driver.castSpellWithTargets(you, spell, listOf(ChosenTarget.Permanent(creature)))

        result.isSuccess shouldBe false
        driver.findPermanent(opponent, "Grizzly Bears") shouldBe creature
    }
})
