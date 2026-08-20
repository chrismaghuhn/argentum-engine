package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mh1.cards.TalismanOfConviction
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Talisman of Conviction (MH1 #230)
 * {T}: Add {C}.
 * {T}: Add {R} or {W}. This artifact deals 1 damage to you.
 */
class TalismanOfConvictionScenarioTest : FunSpec({

    val colorlessAbilityId = TalismanOfConviction.activatedAbilities[0].id
    val redAbilityId = TalismanOfConviction.activatedAbilities[1].id
    val whiteAbilityId = TalismanOfConviction.activatedAbilities[2].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(TalismanOfConviction)
        return driver
    }

    test("the colorless ability adds colorless mana without damaging you") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val talisman = driver.putPermanentOnBattlefield(activePlayer, "Talisman of Conviction")
        driver.untapPermanent(talisman)
        val before = driver.getLifeTotal(activePlayer)

        val result = driver.submit(
            ActivateAbility(playerId = activePlayer, sourceId = talisman, abilityId = colorlessAbilityId)
        )
        result.isSuccess shouldBe true

        val pool = driver.state.getEntity(activePlayer)?.get<ManaPoolComponent>()
        pool?.colorless shouldBe 1
        driver.getLifeTotal(activePlayer) shouldBe before
    }

    test("the red ability adds red mana and deals 1 damage") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val talisman = driver.putPermanentOnBattlefield(activePlayer, "Talisman of Conviction")
        driver.untapPermanent(talisman)
        val before = driver.getLifeTotal(activePlayer)

        val result = driver.submit(
            ActivateAbility(playerId = activePlayer, sourceId = talisman, abilityId = redAbilityId)
        )
        result.isSuccess shouldBe true

        val pool = driver.state.getEntity(activePlayer)?.get<ManaPoolComponent>()
        pool?.red shouldBe 1
        driver.getLifeTotal(activePlayer) shouldBe (before - 1)
    }

    test("the white ability adds white mana and deals 1 damage") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val talisman = driver.putPermanentOnBattlefield(activePlayer, "Talisman of Conviction")
        driver.untapPermanent(talisman)
        val before = driver.getLifeTotal(activePlayer)

        val result = driver.submit(
            ActivateAbility(playerId = activePlayer, sourceId = talisman, abilityId = whiteAbilityId)
        )
        result.isSuccess shouldBe true

        val pool = driver.state.getEntity(activePlayer)?.get<ManaPoolComponent>()
        pool?.white shouldBe 1
        driver.getLifeTotal(activePlayer) shouldBe (before - 1)
    }
})
