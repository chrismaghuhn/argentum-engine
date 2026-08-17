package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.tmp.cards.BountyHunter
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Bounty Hunter (TMP #110).
 *
 * Oracle: "{T}: Put a bounty counter on target nonblack creature. {T}: Destroy target creature
 * with a bounty counter on it."
 */
class BountyHunterScenarioTest : io.kotest.core.spec.style.FunSpec({

    fun GameTestDriver.bountyCount(id: EntityId): Int =
        state.getEntity(id)?.get<CountersComponent>()?.getCount(CounterType.BOUNTY) ?: 0

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(BountyHunter)
        return driver
    }

    test("marks a nonblack creature and another Hunter destroys the marked creature") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)

        val marker = driver.putCreatureOnBattlefield(you, "Bounty Hunter")
        val destroyer = driver.putCreatureOnBattlefield(you, "Bounty Hunter")
        val target = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        driver.removeSummoningSickness(marker)
        driver.removeSummoningSickness(destroyer)

        driver.submitSuccess(
            ActivateAbility(
                playerId = you,
                sourceId = marker,
                abilityId = BountyHunter.activatedAbilities[0].id,
                targets = listOf(ChosenTarget.Permanent(target)),
            )
        )
        driver.bothPass()

        driver.bountyCount(target) shouldBe 1
        driver.submitSuccess(
            ActivateAbility(
                playerId = you,
                sourceId = destroyer,
                abilityId = BountyHunter.activatedAbilities[1].id,
                targets = listOf(ChosenTarget.Permanent(target)),
            )
        )
        driver.bothPass()

        driver.findPermanent(opponent, "Grizzly Bears") shouldBe null
        driver.getGraveyardCardNames(opponent) shouldContain "Grizzly Bears"
    }

    test("the marker ability excludes black creatures") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val hunter = driver.putCreatureOnBattlefield(you, "Bounty Hunter")
        val blackCreature = driver.putCreatureOnBattlefield(opponent, "Black Knight")

        val result = driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = hunter,
                abilityId = BountyHunter.activatedAbilities[0].id,
                targets = listOf(ChosenTarget.Permanent(blackCreature)),
            )
        )

        result.isSuccess shouldBe false
        driver.bountyCount(blackCreature) shouldBe 0
    }

    test("the destroy ability excludes unmarked creatures") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val hunter = driver.putCreatureOnBattlefield(you, "Bounty Hunter")
        val unmarked = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")

        val result = driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = hunter,
                abilityId = BountyHunter.activatedAbilities[1].id,
                targets = listOf(ChosenTarget.Permanent(unmarked)),
            )
        )

        result.isSuccess shouldBe false
        driver.findPermanent(opponent, "Grizzly Bears") shouldNotBe null
    }

})
