package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.chk.cards.SakuraTribeElder
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Sakura-Tribe Elder (CHK #239)
 * Sacrifice this creature: Search your library for a basic land card, put that card onto the
 * battlefield tapped, then shuffle.
 */
class SakuraTribeElderScenarioTest : FunSpec({

    val abilityId = SakuraTribeElder.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(SakuraTribeElder)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("sacrifices itself, searches a basic land, puts it tapped onto the battlefield, and shuffles") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val elder = driver.putCreatureOnBattlefield(player, "Sakura-Tribe Elder")
        val matching = driver.putCardOnTopOfLibrary(player, "Swamp")
        val invalid = driver.putCardOnTopOfLibrary(player, "Grizzly Bears")

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = elder,
                abilityId = abilityId
            )
        ).isSuccess shouldBe true
        driver.findPermanent(player, "Sakura-Tribe Elder") shouldBe null
        driver.state.getGraveyard(player) shouldContain elder

        driver.bothPass()

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.options shouldContain matching
        decision.options shouldNotContain invalid
        driver.submitCardSelection(player, listOf(matching)).isSuccess shouldBe true

        val swamp = driver.findPermanent(player, "Swamp")
        swamp shouldNotBe null
        driver.isTapped(swamp!!) shouldBe true
        driver.state.getLibrary(player) shouldNotContain matching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }

    test("sacrifices itself and still shuffles when no basic land is present") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val elder = driver.putCreatureOnBattlefield(player, "Sakura-Tribe Elder")
        val nonmatching = driver.putCardOnTopOfLibrary(player, "Grizzly Bears")

        val shufflesBefore = driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        }

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = elder,
                abilityId = abilityId
            )
        ).isSuccess shouldBe true
        driver.findPermanent(player, "Sakura-Tribe Elder") shouldBe null
        driver.bothPass()

        driver.pendingDecision shouldBe null
        driver.state.getLibrary(player) shouldContain nonmatching
        driver.events.count {
            it is LibraryShuffledEvent && it.playerId == player
        } shouldBe shufflesBefore + 1
    }
})
