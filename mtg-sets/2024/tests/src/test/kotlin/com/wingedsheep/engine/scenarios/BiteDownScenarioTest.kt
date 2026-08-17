package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Bite Down (DMU #155) — {1}{G} Instant.
 *
 * "Target creature you control deals damage equal to its power to target creature or planeswalker
 *  you don't control."
 *
 * The DMU ruling also matters here: if either target is illegal as Bite Down resolves, the source
 * creature deals no damage.
 */
class BiteDownScenarioTest : FunSpec({

    val indestructibleTarget = card("Bite Down Indestructible Target") {
        manaCost = "{2}"
        typeLine = "Creature — Test"
        power = 2
        toughness = 2
        keywords(Keyword.INDESTRUCTIBLE)
    }

    val testWalker = card("Bite Down Test Walker") {
        manaCost = "{2}"
        typeLine = "Legendary Planeswalker — Test"
        startingLoyalty = 5
    }

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(indestructibleTarget, testWalker))
        driver.initMirrorMatch(Deck.of("Forest" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.damageMarked(entityId: EntityId): Int =
        state.getEntity(entityId)?.get<DamageComponent>()?.amount ?: 0

    fun GameTestDriver.castBiteDown(
        sourceController: EntityId,
        source: EntityId,
        target: EntityId,
    ) {
        val bite = putCardInHand(sourceController, "Bite Down")
        giveMana(sourceController, Color.GREEN, 2)
        castSpell(sourceController, bite, listOf(source, target)).isSuccess shouldBe true
    }

    test("uses the source creature's power against an opponent's creature and is one-sided") {
        val driver = newDriver()
        val source = driver.putCreatureOnBattlefield(driver.player1, "Hill Giant") // 3/3
        val target = driver.putCreatureOnBattlefield(driver.player2, "Horned Turtle") // 1/4

        driver.castBiteDown(driver.player1, source, target)
        driver.bothPass()

        driver.damageMarked(target) shouldBe 3
        driver.damageMarked(source) shouldBe 0
        driver.findPermanent(driver.player2, "Horned Turtle") shouldBe target
    }

    test("can target an opponent's planeswalker as the second target") {
        val driver = newDriver()
        val source = driver.putCreatureOnBattlefield(driver.player1, "Hill Giant") // 3 power
        val walker = driver.putPermanentOnBattlefield(driver.player2, testWalker.name)
        driver.addComponent(walker, CountersComponent(mapOf(CounterType.LOYALTY to 5)))

        driver.castBiteDown(driver.player1, source, walker)
        driver.bothPass()

        driver.state.getEntity(walker)?.get<CountersComponent>()?.getCount(CounterType.LOYALTY) shouldBe 2
        driver.findPermanent(driver.player2, testWalker.name) shouldBe walker
        driver.damageMarked(source) shouldBe 0
    }

    test("rejects a source creature controlled by the opponent") {
        val driver = newDriver()
        val opponentSource = driver.putCreatureOnBattlefield(driver.player2, "Hill Giant")
        val opponentTarget = driver.putCreatureOnBattlefield(driver.player2, "Horned Turtle")
        val bite = driver.putCardInHand(driver.player1, "Bite Down")
        driver.giveMana(driver.player1, Color.GREEN, 2)

        driver.castSpell(driver.player1, bite, listOf(opponentSource, opponentTarget)).isSuccess shouldBe false
    }

    test("rejects a second target controlled by the caster") {
        val driver = newDriver()
        val source = driver.putCreatureOnBattlefield(driver.player1, "Hill Giant")
        val ownTarget = driver.putCreatureOnBattlefield(driver.player1, "Horned Turtle")
        val bite = driver.putCardInHand(driver.player1, "Bite Down")
        driver.giveMana(driver.player1, Color.GREEN, 2)

        driver.castSpell(driver.player1, bite, listOf(source, ownTarget)).isSuccess shouldBe false
    }

    test("deals no damage when a target becomes illegal before resolution") {
        val driver = newDriver()
        val source = driver.putCreatureOnBattlefield(driver.player1, "Hill Giant")
        val target = driver.putCreatureOnBattlefield(driver.player2, "Horned Turtle")

        driver.castBiteDown(driver.player1, source, target)
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.damageMarked(source) shouldBe 0
        driver.findPermanent(driver.player1, "Hill Giant") shouldBe source
    }

    test("does not destroy an indestructible opponent creature while still marking damage") {
        val driver = newDriver()
        val source = driver.putCreatureOnBattlefield(driver.player1, "Hill Giant") // 3/3
        val target = driver.putCreatureOnBattlefield(driver.player2, indestructibleTarget.name) // 2/2

        driver.castBiteDown(driver.player1, source, target)
        driver.bothPass()

        driver.findPermanent(driver.player2, indestructibleTarget.name) shouldBe target
        driver.damageMarked(target) shouldBe 3
        driver.damageMarked(source) shouldBe 0
    }
})
