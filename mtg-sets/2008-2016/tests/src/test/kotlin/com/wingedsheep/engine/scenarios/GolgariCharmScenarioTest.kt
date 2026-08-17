package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rtr.cards.GolgariCharm
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Golgari Charm (RTR #164).
 *
 * Exercises each printed mode, both target domains, the controller restriction
 * on regeneration, the -1/-1 state-based action, and end-of-turn expiry.
 */
class GolgariCharmScenarioTest : FunSpec({

    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(GolgariCharm)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.castCharm(
        player: EntityId,
        mode: Int,
        targets: List<EntityId> = emptyList()
    ) {
        giveMana(player, Color.BLACK, 1)
        giveMana(player, Color.GREEN, 1)
        val charm = putCardInHand(player, "Golgari Charm")
        val chosenTargets = targets.map(ChosenTarget::Permanent)
        submit(
            CastSpell(
                playerId = player,
                cardId = charm,
                targets = chosenTargets,
                paymentStrategy = PaymentStrategy.FromPool,
                chosenModes = listOf(mode),
                modeTargetsOrdered = listOf(chosenTargets)
            )
        ).isSuccess shouldBe true
        bothPass()
    }

    fun GameTestDriver.advanceToNextPrecombatMain() {
        passPriorityUntil(Step.END, maxPasses = 200)
        bothPass()
        passPriorityUntil(Step.PRECOMBAT_MAIN, maxPasses = 200)
    }

    test("mode one gives every creature -1/-1 until end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opposingCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val fragileCreature = driver.putCreatureOnBattlefield(player, "Savannah Lions")
        val enchantment = driver.putPermanentOnBattlefield(player, "Test Enchantment")

        driver.castCharm(player, mode = 0)

        projector.project(driver.state).getPower(ownCreature) shouldBe 2
        projector.project(driver.state).getToughness(ownCreature) shouldBe 2
        projector.project(driver.state).getPower(opposingCreature) shouldBe 2
        driver.getPermanents(player) shouldNotContain fragileCreature
        driver.getPermanents(player) shouldContain enchantment

        driver.advanceToNextPrecombatMain()
        projector.project(driver.state).getPower(ownCreature) shouldBe 3
        projector.project(driver.state).getToughness(ownCreature) shouldBe 3
    }

    test("mode two destroys only the chosen enchantment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val enchantment = driver.putPermanentOnBattlefield(opponent, "Test Enchantment")
        val creature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")

        driver.castCharm(player, mode = 1, targets = listOf(enchantment))

        driver.getPermanents(opponent) shouldNotContain enchantment
        driver.getPermanents(opponent) shouldContain creature
        driver.getGraveyardCardNames(opponent) shouldContain "Test Enchantment"
    }

    test("mode three grants regeneration shields to your creatures only") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opposingCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")

        driver.castCharm(player, mode = 2)

        val ownDoomBlade = driver.putCardInHand(player, "Doom Blade")
        driver.giveMana(player, Color.BLACK, 2)
        driver.castSpell(player, ownDoomBlade, listOf(ownCreature))
        driver.bothPass()
        driver.getPermanents(player) shouldContain ownCreature
        driver.isTapped(ownCreature) shouldBe true

        val opposingDoomBlade = driver.putCardInHand(player, "Doom Blade")
        driver.giveMana(player, Color.BLACK, 2)
        driver.castSpell(player, opposingDoomBlade, listOf(opposingCreature))
        driver.bothPass()
        driver.getPermanents(opponent) shouldNotContain opposingCreature
    }

    test("requires an enchantment target for the destruction mode") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(driver.getOpponent(player), "Centaur Courser")
        val charm = driver.putCardInHand(player, "Golgari Charm")
        driver.giveMana(player, Color.BLACK, 1)
        driver.giveMana(player, Color.GREEN, 1)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = charm,
                targets = listOf(ChosenTarget.Permanent(creature)),
                paymentStrategy = PaymentStrategy.FromPool,
                chosenModes = listOf(1),
                modeTargetsOrdered = listOf(listOf(ChosenTarget.Permanent(creature)))
            )
        )
        result.isSuccess shouldBe false
    }
})
