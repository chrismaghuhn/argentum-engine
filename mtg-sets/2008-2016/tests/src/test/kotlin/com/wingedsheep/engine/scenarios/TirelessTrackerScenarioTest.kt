package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.soi.cards.TirelessTracker
import com.wingedsheep.mtg.sets.tokens.PredefinedTokens
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tireless Tracker (SOI #233).
 *
 * Oracle: "Landfall — Whenever a land you control enters, investigate. (Create a Clue token. It's
 * an artifact with \"{2}, Sacrifice this token: Draw a card.\") Whenever you sacrifice a Clue, put
 * a +1/+1 counter on this creature."
 *
 * The scenarios cover both triggers and the Clue token's actual sacrifice path.
 */
class TirelessTrackerScenarioTest : io.kotest.core.spec.style.FunSpec({

    val clueSacrificeAbilityId = PredefinedTokens.Clue.activatedAbilities.single().id

    fun GameTestDriver.plusOneCounters(id: EntityId): Int =
        state.getEntity(id)?.get<CountersComponent>()?.getCount(CounterType.PLUS_ONE_PLUS_ONE) ?: 0

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(TirelessTracker)
        driver.registerCard(PredefinedTokens.Clue)
        return driver
    }

    test("a land you control entering investigates") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!

        driver.putCreatureOnBattlefield(you, "Tireless Tracker")
        val land = driver.putCardInHand(you, "Forest")
        driver.playLand(you, land).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(you, "Clue") shouldNotBe null
    }

    test("sacrificing the investigated Clue puts a counter on the Tracker") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!

        val tracker = driver.putCreatureOnBattlefield(you, "Tireless Tracker")
        val land = driver.putCardInHand(you, "Forest")
        driver.playLand(you, land).isSuccess shouldBe true
        driver.bothPass()

        val clue = driver.findPermanent(you, "Clue")!!
        driver.giveColorlessMana(you, 2)
        driver.submitSuccess(
            ActivateAbility(playerId = you, sourceId = clue, abilityId = clueSacrificeAbilityId)
        )
        driver.bothPass()
        driver.bothPass()

        driver.findPermanent(you, "Clue") shouldBe null
        driver.plusOneCounters(tracker) shouldBe 1
    }

    test("an opponent's land does not investigate for the Tracker's controller") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)

        driver.putCreatureOnBattlefield(you, "Tireless Tracker")
        driver.putPermanentOnBattlefield(opponent, "Forest")
        driver.bothPass()

        driver.findPermanent(you, "Clue") shouldBe null
    }
})
