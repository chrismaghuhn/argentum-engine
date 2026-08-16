package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariRotFarm
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Golgari Rot Farm (RAV #278) — enters tapped; when it enters, return a land you control;
 * {T}: Add {B}{G}.
 */
class GolgariRotFarmScenarioTest : FunSpec({

    val manaAbilityId = GolgariRotFarm.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + GolgariRotFarm)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("entering Rot Farm asks the controller which land to return") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val farmCard = driver.putCardInHand(player, "Golgari Rot Farm")

        val play = driver.playLand(player, farmCard)
        withClue("Playing Rot Farm failed: ${play.error}") { (play.isSuccess || play.isPaused) shouldBe true }
        val farm = driver.findPermanent(player, "Golgari Rot Farm")
        farm shouldNotBe null
        driver.isTapped(farm!!) shouldBe true

        if (driver.pendingDecision == null) driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        decision.legalTargets.getValue(0) shouldContain forest
        driver.submitTargetSelection(player, listOf(forest)).isSuccess shouldBe true
        while (driver.stackSize > 0) driver.bothPass()

        driver.findPermanent(player, "Forest") shouldBe null
        driver.getHand(player) shouldContain forest
        driver.findPermanent(player, "Golgari Rot Farm") shouldNotBe null
    }

    test("Rot Farm may return itself when it is the only land") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val farmCard = driver.putCardInHand(player, "Golgari Rot Farm")

        val play = driver.playLand(player, farmCard)
        withClue("Playing Rot Farm failed: ${play.error}") { (play.isSuccess || play.isPaused) shouldBe true }
        if (driver.pendingDecision == null) driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        decision.legalTargets.getValue(0) shouldContain farmCard
        driver.submitTargetSelection(player, listOf(farmCard)).isSuccess shouldBe true
        while (driver.stackSize > 0) driver.bothPass()

        driver.findPermanent(player, "Golgari Rot Farm") shouldBe null
        driver.getHand(player) shouldContain farmCard
    }

    test("tapping Rot Farm adds one black and one green mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val farm = driver.putPermanentOnBattlefield(player, "Golgari Rot Farm")
        driver.untapPermanent(farm)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = farm, abilityId = manaAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
        pool.black shouldBe 1
        pool.green shouldBe 1
        driver.isTapped(farm) shouldBe true
    }
})
