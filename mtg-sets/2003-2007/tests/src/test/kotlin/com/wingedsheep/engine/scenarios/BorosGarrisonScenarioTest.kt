package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.BorosGarrison
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Boros Garrison (RAV #275) — enters tapped; when it enters, return a land you control;
 * {T}: Add {R}{W}.
 */
class BorosGarrisonScenarioTest : FunSpec({

    val manaAbilityId = BorosGarrison.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BorosGarrison)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("entering Boros Garrison asks the controller which land to return") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val garrisonCard = driver.putCardInHand(player, "Boros Garrison")

        val play = driver.playLand(player, garrisonCard)
        withClue("Playing Boros Garrison failed: ${play.error}") { (play.isSuccess || play.isPaused) shouldBe true }
        val garrison = driver.findPermanent(player, "Boros Garrison")
        garrison shouldNotBe null
        driver.isTapped(garrison!!) shouldBe true

        if (driver.pendingDecision == null) driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        decision.legalTargets.getValue(0) shouldContain forest
        driver.submitTargetSelection(player, listOf(forest)).isSuccess shouldBe true
        while (driver.stackSize > 0) driver.bothPass()

        driver.findPermanent(player, "Forest") shouldBe null
        driver.getHand(player) shouldContain forest
        driver.findPermanent(player, "Boros Garrison") shouldNotBe null
    }

    test("Boros Garrison may return itself when it is the only land") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val garrisonCard = driver.putCardInHand(player, "Boros Garrison")

        val play = driver.playLand(player, garrisonCard)
        withClue("Playing Boros Garrison failed: ${play.error}") { (play.isSuccess || play.isPaused) shouldBe true }
        if (driver.pendingDecision == null) driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        decision.legalTargets.getValue(0) shouldContain garrisonCard
        driver.submitTargetSelection(player, listOf(garrisonCard)).isSuccess shouldBe true
        while (driver.stackSize > 0) driver.bothPass()

        driver.findPermanent(player, "Boros Garrison") shouldBe null
        driver.getHand(player) shouldContain garrisonCard
    }

    test("tapping Boros Garrison adds one red and one white mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val garrison = driver.putPermanentOnBattlefield(player, "Boros Garrison")
        driver.untapPermanent(garrison)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = garrison, abilityId = manaAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!
        pool.red shouldBe 1
        pool.white shouldBe 1
        driver.isTapped(garrison) shouldBe true
    }
})
