package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.cmd.cards.ScavengingOoze
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Scavenging Ooze (CMD #170).
 *
 * Oracle: "{G}: Exile target card from a graveyard. If it was a creature card, put a +1/+1
 * counter on this creature and you gain 1 life."
 *
 * The scenarios pin the any-player graveyard target, the exile, and the creature-card-only
 * counter/life rider.
 */
class ScavengingOozeScenarioTest : io.kotest.core.spec.style.FunSpec({

    val abilityId = ScavengingOoze.activatedAbilities.single().id

    fun GameTestDriver.plusOneCounters(id: EntityId): Int =
        state.getEntity(id)?.get<CountersComponent>()?.getCount(CounterType.PLUS_ONE_PLUS_ONE) ?: 0

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(ScavengingOoze)
        return driver
    }

    test("exiling a creature card from either graveyard grows the Ooze and gains life") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)
        val ooze = driver.putCreatureOnBattlefield(activePlayer, "Scavenging Ooze")
        val creatureCard = driver.putCardInGraveyard(opponent, "Grizzly Bears")
        driver.giveMana(activePlayer, com.wingedsheep.sdk.core.Color.GREEN, 1)

        val result = driver.submit(
            ActivateAbility(
                playerId = activePlayer,
                sourceId = ooze,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Card(creatureCard, opponent, Zone.GRAVEYARD))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        driver.getGraveyardCardNames(opponent) shouldBe emptyList()
        driver.getExile(opponent) shouldContain creatureCard
        driver.plusOneCounters(ooze) shouldBe 1
        driver.getLifeTotal(activePlayer) shouldBe 21
    }

    test("exiling a noncreature card does not add a counter or life") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val ooze = driver.putCreatureOnBattlefield(activePlayer, "Scavenging Ooze")
        val landCard = driver.putCardInGraveyard(activePlayer, "Forest")
        driver.giveMana(activePlayer, com.wingedsheep.sdk.core.Color.GREEN, 1)

        val result = driver.submit(
            ActivateAbility(
                playerId = activePlayer,
                sourceId = ooze,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Card(landCard, activePlayer, Zone.GRAVEYARD))
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        driver.getGraveyardCardNames(activePlayer) shouldBe emptyList()
        driver.getExile(activePlayer) shouldContain landCard
        driver.plusOneCounters(ooze) shouldBe 0
        driver.getLifeTotal(activePlayer) shouldBe 20
    }
})
