package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dom.cards.DanithaCapashenParagon
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Danitha Capashen, Paragon (DOM #12).
 *
 * Oracle:
 * "First strike, vigilance, lifelink
 * Aura and Equipment spells you cast cost {1} less to cast."
 *
 * The scenario proves the three intrinsic keywords, the reduction for an
 * Equipment spell, and the negative case that a non-Aura/non-Equipment spell
 * is not reduced.
 */
class DanithaCapashenParagonScenarioTest : FunSpec({

    val GainTwoLife = CardDefinition.sorcery(
        name = "A8 Gain Two Life",
        manaCost = ManaCost.parse("{2}"),
        oracleText = "You gain 2 life.",
        script = CardScript.spell(effect = Effects.GainLife(2)),
    )

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCards(listOf(DanithaCapashenParagon, BasiliskCollar, GainTwoLife))
        return driver
    }

    test("has first strike, vigilance, and lifelink") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 30), skipMulligans = true)
        val you = driver.activePlayer!!
        val danitha = driver.putCreatureOnBattlefield(you, "Danitha Capashen, Paragon")

        val projected = driver.state.projectedState
        projected.hasKeyword(danitha, Keyword.FIRST_STRIKE) shouldBe true
        projected.hasKeyword(danitha, Keyword.VIGILANCE) shouldBe true
        projected.hasKeyword(danitha, Keyword.LIFELINK) shouldBe true
    }

    test("reduces an Equipment spell by one generic mana") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 30), skipMulligans = true)
        val you = driver.activePlayer!!
        driver.putCreatureOnBattlefield(you, "Danitha Capashen, Paragon")
        driver.passPriorityUntil(com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN)

        val collar = driver.putCardInHand(you, "Basilisk Collar")
        val result = driver.castSpell(you, collar)

        withClue("Basilisk Collar normally costs {1}; Danitha should make it free: ${result.error}") {
            result.error shouldBe null
        }
    }

    test("does not reduce a non-Aura, non-Equipment spell") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 30), skipMulligans = true)
        val you = driver.activePlayer!!
        driver.putCreatureOnBattlefield(you, "Danitha Capashen, Paragon")
        driver.passPriorityUntil(com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN)

        val spell = driver.putCardInHand(you, "A8 Gain Two Life")
        driver.giveColorlessMana(you, 1)
        val result = driver.castSpell(you, spell)

        withClue("a {2} non-Aura/non-Equipment spell must still require {2}: ${result.error}") {
            result.error shouldBe "Insufficient mana in pool to cast this spell"
        }
    }
})
