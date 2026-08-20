package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.jou.cards.TempleOfMalady
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Temple of Malady (JOU #165)
 * Enters tapped; when it enters, scry 1; {T}: add {B} or {G}.
 */
class TempleOfMaladyScenarioTest : FunSpec({

    val blackAbilityId = TempleOfMalady.activatedAbilities[0].id
    val greenAbilityId = TempleOfMalady.activatedAbilities[1].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(TempleOfMalady)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("enters tapped and its scry trigger can keep the top card") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val temple = driver.putCardInHand(player, "Temple of Malady")
        val topCard = driver.putCardOnTopOfLibrary(player, "Forest")

        driver.playLand(player, temple).isSuccess shouldBe true
        driver.isTapped(temple) shouldBe true
        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.options shouldBe listOf(topCard)
        driver.submitCardSelection(player, emptyList()).error shouldBe null
        driver.state.getLibrary(player).first() shouldBe topCard
    }

    test("the scry trigger can put the top card on the bottom") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val temple = driver.putCardInHand(player, "Temple of Malady")
        val topCard = driver.putCardOnTopOfLibrary(player, "Forest")

        driver.playLand(player, temple).isSuccess shouldBe true
        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        driver.submitCardSelection(player, listOf(topCard)).isSuccess shouldBe true

        driver.state.getLibrary(player).last() shouldBe topCard
    }

    test("its mana abilities add black or green mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val temple = driver.putPermanentOnBattlefield(player, "Temple of Malady")

        driver.submit(ActivateAbility(player, temple, blackAbilityId)).isSuccess shouldBe true
        val afterBlack = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        afterBlack.black shouldBe 1

        driver.untapPermanent(temple)
        driver.submit(ActivateAbility(player, temple, greenAbilityId)).isSuccess shouldBe true
        val afterGreen = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        afterGreen.green shouldBe 1
    }
})
