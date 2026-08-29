package com.wingedsheep.engine.mana

import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Regression coverage for paid mana-source reachability in [ManaSolver]. */
class ManaSolverSelfFundingTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + GolgariSignet)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        return driver
    }

    test("MANA-SOLVER-SELF-FUNDING-01 rejects a signet as its own activation payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solver = ManaSolver(driver.cardRegistry)
        val cost = ManaCost.parse("{G}")

        solver.solve(driver.state, player, cost) shouldBe null
    }

    test("MANA-SOLVER-SELF-FUNDING-01 canPay rejects a signet as its own activation payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        ManaSolver(driver.cardRegistry).canPay(
            driver.state,
            player,
            ManaCost.parse("{G}"),
        ) shouldBe false
    }

    test("an independent Forest funds the Signet before its output pays the outer cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solution = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{B}{G}"),
        )

        val nonNullSolution = solution.shouldNotBeNull()
        nonNullSolution.sources.map { it.entityId } shouldContainExactly listOf(forest, signet)
        nonNullSolution.manaProduced.keys shouldContainExactly listOf(signet)
    }

    test("an initial Forest can seed a chain of two paid sources") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forest = driver.putLandOnBattlefield(player, "Forest")
        val firstSignet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        val secondSignet = driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solution = ManaSolver(driver.cardRegistry).solve(
            driver.state,
            player,
            ManaCost.parse("{B}{B}"),
        )

        val nonNullSolution = solution.shouldNotBeNull()
        nonNullSolution.sources.map { it.entityId } shouldContainExactly listOf(forest, firstSignet, secondSignet)
        nonNullSolution.manaProduced.keys.toList() shouldContainExactly listOf(firstSignet, secondSignet)
    }

    test("two paid mana sources cannot bootstrap one another from an empty pool") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Golgari Signet")
        driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val solver = ManaSolver(driver.cardRegistry)
        val cost = ManaCost.parse("{B}{B}")

        solver.canPay(driver.state, player, cost) shouldBe false
        solver.solve(driver.state, player, cost) shouldBe null
    }
})
