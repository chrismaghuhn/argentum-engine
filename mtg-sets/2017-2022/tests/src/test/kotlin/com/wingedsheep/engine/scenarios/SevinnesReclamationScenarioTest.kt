package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.c19.cards.SevinnesReclamation
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Scenario evidence for Sevinne's Reclamation (C19 #5). */
class SevinnesReclamationScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + SevinnesReclamation + BasiliskCollar + Bonesplitter)
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveUntilPausedOrEmpty(driver: GameTestDriver) {
        var guard = 0
        while (guard++ < 30 && driver.state.stack.isNotEmpty() && !driver.isPaused) {
            driver.bothPass()
        }
    }

    test("normal cast targets only your permanent card with mana value 3 or less") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val eligible = driver.putCardInGraveyard(you, "Basilisk Collar")
        val nonPermanent = driver.putCardInGraveyard(you, "Lightning Bolt")
        val tooExpensive = driver.putCardInGraveyard(you, "Hill Giant")
        val opponentPermanent = driver.putCardInGraveyard(opponent, "Bonesplitter")
        val spell = driver.putCardInHand(you, "Sevinne's Reclamation")

        fun tryTarget(targetId: com.wingedsheep.sdk.model.EntityId, ownerId: com.wingedsheep.sdk.model.EntityId) =
            driver.castSpellWithTargets(
                you,
                spell,
                listOf(ChosenTarget.Card(targetId, ownerId, Zone.GRAVEYARD)),
            )

        driver.giveMana(you, Color.WHITE, 1)
        driver.giveColorlessMana(you, 2)
        tryTarget(nonPermanent, you).error shouldNotBe null
        tryTarget(tooExpensive, you).error shouldNotBe null
        tryTarget(opponentPermanent, opponent).error shouldNotBe null

        tryTarget(eligible, you).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.findPermanent(you, "Basilisk Collar") shouldBe eligible
        driver.state.getZone(you, Zone.GRAVEYARD) shouldNotContain eligible
        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain nonPermanent
        driver.state.getZone(you, Zone.GRAVEYARD) shouldContain tooExpensive
        driver.state.getZone(opponent, Zone.GRAVEYARD) shouldContain opponentPermanent
    }

    test("flashback copies the spell and pauses for an explicit new target") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val first = driver.putCardInGraveyard(you, "Basilisk Collar")
        val second = driver.putCardInGraveyard(you, "Bonesplitter")
        val spell = driver.putCardInGraveyard(you, "Sevinne's Reclamation")

        driver.giveMana(you, Color.WHITE, 1)
        driver.giveColorlessMana(you, 4)
        driver.submit(
            CastSpell(
                playerId = you,
                cardId = spell,
                targets = listOf(ChosenTarget.Card(first, you, Zone.GRAVEYARD)),
                useAlternativeCost = true,
                alternativeCostType = AlternativeCostType.FLASHBACK,
                paymentStrategy = PaymentStrategy.FromPool,
            ),
        ).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(you, true).error shouldBe null
        val copyTargetDecision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        copyTargetDecision.legalTargets.values.flatten() shouldContain second
        copyTargetDecision.legalTargets.values.flatten() shouldNotContain first

        driver.submitTargetSelection(you, listOf(second)).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.findPermanent(you, "Basilisk Collar") shouldBe first
        driver.findPermanent(you, "Bonesplitter") shouldBe second
        driver.state.getZone(you, Zone.EXILE) shouldContain spell
    }
})
