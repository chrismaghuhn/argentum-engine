package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.mtg.sets.definitions.dmu.cards.TearAsunder
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.ChoiceSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tear Asunder (DMU #183).
 *
 * "Kicker {1}{B}. Exile target artifact or enchantment. If this spell was kicked, exile target
 * nonland permanent instead."
 */
class TearAsunderScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + TearAsunder)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castTearAsunder(
        caster: com.wingedsheep.sdk.model.EntityId,
        cardId: com.wingedsheep.sdk.model.EntityId,
        target: com.wingedsheep.sdk.model.EntityId,
        kicked: Boolean,
    ) {
        giveMana(caster, Color.GREEN, 1)
        giveColorlessMana(caster, 1)
        if (kicked) giveMana(caster, Color.BLACK, 1)
        if (kicked) giveColorlessMana(caster, 1)
        submit(
            CastSpell(
                playerId = caster,
                cardId = cardId,
                targets = listOf(ChosenTarget.Permanent(target)),
                declaredCostSlot = if (kicked) ChoiceSlot.KICKED else null,
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        ).error shouldBe null
        bothPass()
    }

    test("unkicked exiles an artifact") {
        val driver = newDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val artifact = driver.putPermanentOnBattlefield(opponent, "Mind Stone")
        val spell = driver.putCardInHand(caster, "Tear Asunder")

        driver.castTearAsunder(caster, spell, artifact, kicked = false)

        driver.findPermanent(opponent, "Mind Stone") shouldBe null
        driver.getExile(opponent) shouldBe listOf(artifact)
    }

    test("unkicked cannot target a creature") {
        val driver = newDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val creature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val spell = driver.putCardInHand(caster, "Tear Asunder")
        driver.giveMana(caster, Color.GREEN, 1)
        driver.giveColorlessMana(caster, 1)

        val result = driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spell,
                targets = listOf(ChosenTarget.Permanent(creature)),
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        )

        result.isSuccess shouldBe false
        driver.findPermanent(opponent, "Centaur Courser") shouldBe creature
    }

    test("unkicked can exile an enchantment") {
        val driver = newDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val enchantment = driver.putPermanentOnBattlefield(opponent, "Garruk's Uprising")
        val spell = driver.putCardInHand(caster, "Tear Asunder")

        driver.castTearAsunder(caster, spell, enchantment, kicked = false)

        driver.findPermanent(opponent, "Garruk's Uprising") shouldBe null
        driver.getExile(opponent) shouldBe listOf(enchantment)
    }

    test("kicked exiles a nonland permanent and cannot target a land") {
        val driver = newDriver()
        val caster = driver.activePlayer!!
        val opponent = driver.getOpponent(caster)
        val creature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val spell = driver.putCardInHand(caster, "Tear Asunder")

        driver.castTearAsunder(caster, spell, creature, kicked = true)

        driver.findPermanent(opponent, "Centaur Courser") shouldBe null
        driver.getExile(opponent) shouldBe listOf(creature)

        val land = driver.putLandOnBattlefield(opponent, "Forest")
        val secondSpell = driver.putCardInHand(caster, "Tear Asunder")
        driver.giveMana(caster, Color.GREEN, 1)
        driver.giveColorlessMana(caster, 1)
        driver.giveMana(caster, Color.BLACK, 1)
        driver.giveColorlessMana(caster, 1)
        val result = driver.submit(
            CastSpell(
                playerId = caster,
                cardId = secondSpell,
                targets = listOf(ChosenTarget.Permanent(land)),
                declaredCostSlot = ChoiceSlot.KICKED,
                paymentStrategy = PaymentStrategy.AutoPay,
            )
        )

        result.isSuccess shouldBe false
        driver.findPermanent(opponent, "Forest") shouldBe land
    }
})
