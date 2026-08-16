package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Llanowar Wastes (APC #141) — {T}: Add {C}; or {T}: Add {B}/{G}, and deal 1 damage to you.
 */
class LlanowarWastesScenarioTest : FunSpec({

    val colorlessAbilityId = LlanowarWastes.activatedAbilities[0].id
    val blackAbilityId = LlanowarWastes.activatedAbilities[1].id
    val greenAbilityId = LlanowarWastes.activatedAbilities[2].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LlanowarWastes)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("the colorless ability adds {C} and deals no damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val wastes = driver.putPermanentOnBattlefield(player, "Llanowar Wastes")
        val before = driver.getLifeTotal(player)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = wastes, abilityId = colorlessAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.colorless shouldBe 1
        driver.getLifeTotal(player) shouldBe before
        driver.isTapped(wastes) shouldBe true
    }

    test("the black ability adds {B} and deals 1 damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val wastes = driver.putPermanentOnBattlefield(player, "Llanowar Wastes")
        val before = driver.getLifeTotal(player)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = wastes, abilityId = blackAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.black shouldBe 1
        driver.getLifeTotal(player) shouldBe before - 1
        driver.isTapped(wastes) shouldBe true
    }

    test("the green ability adds {G} and deals 1 damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val wastes = driver.putPermanentOnBattlefield(player, "Llanowar Wastes")
        val before = driver.getLifeTotal(player)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = wastes, abilityId = greenAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.green shouldBe 1
        driver.getLifeTotal(player) shouldBe before - 1
        driver.isTapped(wastes) shouldBe true
    }
})
