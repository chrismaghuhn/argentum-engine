package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.cmd.cards.ChaosWarp
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Chaos Warp — current Oracle behavior against the existing generic library pipeline.
 *
 * The canonical definition is housed under CMD, its earliest printing; later scaffolded sets
 * contribute Printing rows only.
 */
class ChaosWarpScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(ChaosWarp)
        driver.initMirrorMatch(deck = Deck.of("Grizzly Bears" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("uses the current Oracle text") {
        ChaosWarp.oracleText shouldBe
            "The owner of target permanent shuffles it into their library, then reveals the top " +
                "card of their library. If it's a permanent card, they put it onto the battlefield."
    }

    test("shuffles the target into its owner's library and puts a revealed permanent onto the battlefield") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val owner = driver.getOpponent(caster)
        val target = driver.putPermanentOnBattlefield(owner, "Mind Stone")
        driver.putCardOnTopOfLibrary(owner, "Grizzly Bears")
        val warp = driver.putCardInHand(caster, "Chaos Warp")
        val shufflesBefore = driver.events.count { it is LibraryShuffledEvent && it.playerId == owner }

        driver.giveMana(caster, Color.RED, 1)
        driver.giveColorlessMana(caster, 2)
        driver.castSpell(caster, warp, listOf(target)).error shouldBe null
        driver.bothPass()

        // The target is shuffled before the reveal, so it may itself be the revealed permanent.
        // The test must therefore accept either the target returning to the battlefield or a
        // Grizzly Bears card being revealed, while still proving that a permanent was put there.
        val targetReturned = driver.findPermanent(owner, "Mind Stone") != null
        val creatureRevealed = driver.findPermanent(owner, "Grizzly Bears") != null
        (targetReturned xor creatureRevealed) shouldBe true
        if (targetReturned) {
            driver.state.getLibrary(owner) shouldNotContain target
        } else {
            driver.state.getLibrary(owner) shouldContain target
        }
        driver.events.count { it is LibraryShuffledEvent && it.playerId == owner } shouldBe shufflesBefore + 1
    }

    test("leaves a revealed nonpermanent top card in its owner's library") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val owner = driver.getOpponent(caster)
        val target = driver.putPermanentOnBattlefield(owner, "Mind Stone")
        val revealedNonpermanent = driver.putCardOnTopOfLibrary(owner, "Lightning Bolt")
        val warp = driver.putCardInHand(caster, "Chaos Warp")

        driver.giveMana(caster, Color.RED, 1)
        driver.giveColorlessMana(caster, 2)
        driver.castSpell(caster, warp, listOf(target)).error shouldBe null
        driver.bothPass()

        driver.state.getLibrary(owner) shouldContain revealedNonpermanent
        driver.findPermanent(owner, "Lightning Bolt") shouldBe null
    }

    test("cannot target a nonpermanent card") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val owner = driver.getOpponent(caster)
        val nonpermanent = driver.putCardInHand(owner, "Lightning Bolt")
        val warp = driver.putCardInHand(caster, "Chaos Warp")

        driver.giveMana(caster, Color.RED, 1)
        driver.giveColorlessMana(caster, 2)
        val result = driver.castSpell(caster, warp, listOf(nonpermanent))

        result.isSuccess shouldBe false
        driver.findCardInHand(caster, "Chaos Warp") shouldNotBe null
        driver.state.getHand(owner) shouldContain nonpermanent
    }
})
