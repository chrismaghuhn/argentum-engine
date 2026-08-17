package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mh1.cards.TalismanOfResilience
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Talisman of Resilience (MH1 #234)
 * {T}: Add {C}.
 * {T}: Add {B} or {G}. This artifact deals 1 damage to you.
 */
class TalismanOfResilienceScenarioTest : FunSpec({

    val colorlessAbilityId = TalismanOfResilience.activatedAbilities[0].id
    val blackAbilityId = TalismanOfResilience.activatedAbilities[1].id
    val greenAbilityId = TalismanOfResilience.activatedAbilities[2].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCard(TalismanOfResilience)
        return driver
    }

    test("the colorless ability adds colorless mana without damaging you") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val talisman = driver.putPermanentOnBattlefield(activePlayer, "Talisman of Resilience")
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

    test("the black ability adds black mana and deals 1 damage") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val talisman = driver.putPermanentOnBattlefield(activePlayer, "Talisman of Resilience")
        driver.untapPermanent(talisman)
        val before = driver.getLifeTotal(activePlayer)

        val result = driver.submit(
            ActivateAbility(playerId = activePlayer, sourceId = talisman, abilityId = blackAbilityId)
        )
        result.isSuccess shouldBe true

        val pool = driver.state.getEntity(activePlayer)?.get<ManaPoolComponent>()
        pool?.black shouldBe 1
        driver.getLifeTotal(activePlayer) shouldBe (before - 1)
    }

    test("the green ability adds green mana and deals 1 damage") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val activePlayer = driver.activePlayer!!
        val talisman = driver.putPermanentOnBattlefield(activePlayer, "Talisman of Resilience")
        driver.untapPermanent(talisman)
        val before = driver.getLifeTotal(activePlayer)

        val result = driver.submit(
            ActivateAbility(playerId = activePlayer, sourceId = talisman, abilityId = greenAbilityId)
        )
        result.isSuccess shouldBe true

        val pool = driver.state.getEntity(activePlayer)?.get<ManaPoolComponent>()
        pool?.green shouldBe 1
        driver.getLifeTotal(activePlayer) shouldBe (before - 1)
    }
})
