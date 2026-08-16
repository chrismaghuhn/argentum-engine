package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.apc.cards.BattlefieldForge
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Battlefield Forge (APC #139) — {T}: Add {C}; or {T}: Add {R}/{W}, and deal 1 damage to you.
 */
class BattlefieldForgeScenarioTest : FunSpec({

    val colorlessAbilityId = BattlefieldForge.activatedAbilities[0].id
    val redAbilityId = BattlefieldForge.activatedAbilities[1].id
    val whiteAbilityId = BattlefieldForge.activatedAbilities[2].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BattlefieldForge)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("the colorless ability adds {C} and deals no damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forge = driver.putPermanentOnBattlefield(player, "Battlefield Forge")
        val before = driver.getLifeTotal(player)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = forge, abilityId = colorlessAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.colorless shouldBe 1
        driver.getLifeTotal(player) shouldBe before
        driver.isTapped(forge) shouldBe true
    }

    test("the red ability adds {R} and deals 1 damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forge = driver.putPermanentOnBattlefield(player, "Battlefield Forge")
        val before = driver.getLifeTotal(player)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = forge, abilityId = redAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.red shouldBe 1
        driver.getLifeTotal(player) shouldBe before - 1
        driver.isTapped(forge) shouldBe true
    }

    test("the white ability adds {W} and deals 1 damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val forge = driver.putPermanentOnBattlefield(player, "Battlefield Forge")
        val before = driver.getLifeTotal(player)

        driver.submit(
            ActivateAbility(playerId = player, sourceId = forge, abilityId = whiteAbilityId)
        ).isSuccess shouldBe true

        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.white shouldBe 1
        driver.getLifeTotal(player) shouldBe before - 1
        driver.isTapped(forge) shouldBe true
    }
})
