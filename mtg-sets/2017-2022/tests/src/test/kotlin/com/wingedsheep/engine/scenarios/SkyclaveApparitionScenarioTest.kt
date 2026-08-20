package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.znr.cards.SkyclaveApparition
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Scenario coverage for Skyclave Apparition (ZNR #39).
 *
 * The ETB is an optional target restricted to an opposing, nonland, nontoken permanent with
 * mana value at most four. Its leaves trigger uses the linked card's owner, not the Apparition's
 * controller, and creates a blue Illusion whose power and toughness equal the exiled card's mana
 * value.
 */
class SkyclaveApparitionScenarioTest : FunSpec({

    fun driver(): GameTestDriver = GameTestDriver().apply {
        registerCards(TestCards.all + SkyclaveApparition)
        initMirrorMatch(deck = Deck.of("Plains" to 60), startingLife = 20)
        passPriorityUntil(Step.PRECOMBAT_MAIN)
    }

    fun resolveStack(driver: GameTestDriver) {
        var guard = 0
        while ((driver.stackSize > 0 || driver.pendingDecision != null) && guard++ < 40) {
            if (driver.pendingDecision != null) driver.autoResolveDecision() else driver.bothPass()
        }
        guard shouldNotBe 40
    }

    test("ETB exposes only an opposing nonland nontoken permanent with mana value four or less") {
        val d = driver()
        val me = d.activePlayer!!
        val opp = d.getOpponent(me)

        val eligible = d.putPermanentOnBattlefield(opp, "Mind Stone")
        val tooExpensive = d.putPermanentOnBattlefield(opp, "Air Elemental")
        val land = d.putLandOnBattlefield(opp, "Plains")
        val ownPermanent = d.putPermanentOnBattlefield(me, "Mind Stone")

        val apparition = d.putCardInHand(me, "Skyclave Apparition")
        d.giveMana(me, Color.WHITE, 2)
        d.giveColorlessMana(me, 1)
        d.castSpell(me, apparition).isSuccess shouldBe true
        d.bothPass()

        val decision = d.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val legal = decision.legalTargets.getValue(0)
        legal shouldContain eligible
        legal shouldNotContain tooExpensive
        legal shouldNotContain land
        legal shouldNotContain ownPermanent

        d.submitTargetSelection(me, listOf(eligible)).isSuccess shouldBe true
        resolveStack(d)
        d.getExile(opp) shouldContain eligible
        d.getPermanents(opp) shouldNotContain eligible
    }

    test("declining the optional ETB target leaves the battlefield unchanged") {
        val d = driver()
        val me = d.activePlayer!!
        val opp = d.getOpponent(me)
        val permanent = d.putPermanentOnBattlefield(opp, "Mind Stone")

        val apparition = d.putCardInHand(me, "Skyclave Apparition")
        d.giveMana(me, Color.WHITE, 2)
        d.giveColorlessMana(me, 1)
        d.castSpell(me, apparition).isSuccess shouldBe true
        d.bothPass()
        d.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        d.submitTargetSelection(me, emptyList()).isSuccess shouldBe true
        resolveStack(d)

        d.getPermanents(opp) shouldContain permanent
        d.getExile(opp) shouldNotContain permanent
    }

    test("leaving creates the mana-value Illusion for the exiled card's owner") {
        val d = driver()
        val me = d.activePlayer!!
        val opp = d.getOpponent(me)
        val exiled = d.putPermanentOnBattlefield(opp, "Mind Stone") // mana value 2

        val apparitionCard = d.putCardInHand(me, "Skyclave Apparition")
        d.giveMana(me, Color.WHITE, 2)
        d.giveColorlessMana(me, 1)
        d.castSpell(me, apparitionCard).isSuccess shouldBe true
        d.bothPass()
        d.submitTargetSelection(me, listOf(exiled)).isSuccess shouldBe true
        resolveStack(d)

        val apparition = d.findPermanent(me, "Skyclave Apparition")!!
        val bolt = d.putCardInHand(me, "Lightning Bolt")
        d.giveMana(me, Color.RED, 1)
        d.castSpell(me, bolt, listOf(apparition)).isSuccess shouldBe true
        resolveStack(d)

        d.findPermanent(me, "Skyclave Apparition") shouldBe null
        d.getExile(opp) shouldContain exiled
        val illusion = d.getPermanents(opp).firstOrNull { d.getCardName(it)?.contains("Illusion") == true }
        illusion shouldNotBe null
        d.state.projectedState.getPower(illusion!!) shouldBe 2
        d.state.projectedState.getToughness(illusion) shouldBe 2
        d.getPermanents(me).none { d.getCardName(it)?.contains("Illusion") == true } shouldBe true
    }
})
