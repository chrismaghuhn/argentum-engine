package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.m11.cards.SunTitan
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Sun Titan (M11 #35).
 *
 * "Vigilance
 * Whenever this creature enters or attacks, you may return target permanent card with mana value
 * 3 or less from your graveyard to the battlefield."
 */
class SunTitanScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + SunTitan + BasiliskCollar)
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveStack(driver: GameTestDriver) {
        var guard = 0
        while (guard++ < 30 && driver.state.stack.isNotEmpty() && !driver.isPaused) {
            driver.bothPass()
        }
    }

    fun resolveMayReturn(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId, target: com.wingedsheep.sdk.model.EntityId) {
        var guard = 0
        while (guard++ < 5 && !driver.isPaused && driver.state.stack.isNotEmpty()) {
            driver.bothPass()
        }

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, true)

        val targetDecision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        targetDecision.legalTargets[0].orEmpty() shouldContain target
        driver.submitDecision(
            player,
            TargetsResponse(targetDecision.id, mapOf(0 to listOf(target))),
        )
        resolveStack(driver)
    }

    test("enters: returns only a target permanent with mana value 3 or less") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val eligible = driver.putCardInGraveyard(you, "Basilisk Collar")
        val nonPermanent = driver.putCardInGraveyard(you, "Lightning Bolt")
        val tooExpensive = driver.putCardInGraveyard(you, "Centaur Courser")
        val sunTitan = driver.putCardInHand(you, "Sun Titan")

        driver.giveMana(you, Color.WHITE, 2)
        driver.giveColorlessMana(you, 4)
        driver.castSpell(you, sunTitan)
        driver.bothPass() // resolve Sun Titan
        resolveMayReturn(driver, you, eligible)

        driver.findPermanent(you, "Basilisk Collar") shouldBe eligible
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldNotContain eligible
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldContain nonPermanent
        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldContain tooExpensive
    }

    test("enters: declining the may return leaves the graveyard unchanged") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val eligible = driver.putCardInGraveyard(you, "Basilisk Collar")
        val sunTitan = driver.putCardInHand(you, "Sun Titan")

        driver.giveMana(you, Color.WHITE, 2)
        driver.giveColorlessMana(you, 4)
        driver.castSpell(you, sunTitan)
        driver.bothPass()
        var guard = 0
        while (guard++ < 5 && !driver.isPaused && driver.state.stack.isNotEmpty()) driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(you, false)
        resolveStack(driver)

        driver.state.getZone(you, com.wingedsheep.sdk.core.Zone.GRAVEYARD) shouldContain eligible
        driver.findPermanent(you, "Basilisk Collar") shouldBe null
    }

    test("attacks: the same may return ability triggers on attack") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val eligible = driver.putCardInGraveyard(you, "Basilisk Collar")
        val sunTitan = driver.putCreatureOnBattlefield(you, "Sun Titan")
        driver.removeSummoningSickness(sunTitan)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        val attack = driver.declareAttackers(you, listOf(sunTitan), opponent)
        withClue("attack declaration should commit and pause only for Sun Titan's trigger: ${attack.error}") {
            attack.error shouldBe null
        }
        resolveMayReturn(driver, you, eligible)

        driver.findPermanent(you, "Basilisk Collar") shouldBe eligible
    }
})
