package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.`5dn`.cards.WayfarersBauble
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Wayfarer's Bauble (5DN #165) — {2}, {T}, Sacrifice this artifact: search for a basic land,
 * put it onto the battlefield tapped, then shuffle.
 */
class WayfarersBaubleScenarioTest : FunSpec({

    val abilityId = WayfarersBauble.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + WayfarersBauble)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("sacrificing Wayfarer's Bauble searches a basic land onto the battlefield tapped") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val bauble = driver.putPermanentOnBattlefield(player, "Wayfarer's Bauble")
        val mountain = driver.putCardOnTopOfLibrary(player, "Mountain")
        driver.giveColorlessMana(player, 2)

        val activation = driver.submit(
            ActivateAbility(playerId = player, sourceId = bauble, abilityId = abilityId)
        )
        activation.isSuccess shouldBe true
        driver.findPermanent(player, "Wayfarer's Bauble") shouldBe null
        driver.getGraveyardCardNames(player) shouldContain "Wayfarer's Bauble"

        driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.options shouldContain mountain
        decision.minSelections shouldBe 0
        decision.maxSelections shouldBe 1
        driver.submitCardSelection(player, listOf(mountain)).isSuccess shouldBe true

        driver.findPermanent(player, "Mountain") shouldNotBe null
        val foundMountain = driver.findPermanent(player, "Mountain")!!
        driver.isTapped(foundMountain) shouldBe true
    }

    test("Wayfarer's Bauble cannot activate without {2}") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val bauble = driver.putPermanentOnBattlefield(player, "Wayfarer's Bauble")
        driver.giveColorlessMana(player, 1)

        val result = driver.submit(
            ActivateAbility(playerId = player, sourceId = bauble, abilityId = abilityId)
        )

        result.isSuccess shouldBe false
        driver.findPermanent(player, "Wayfarer's Bauble") shouldNotBe null
    }
})
