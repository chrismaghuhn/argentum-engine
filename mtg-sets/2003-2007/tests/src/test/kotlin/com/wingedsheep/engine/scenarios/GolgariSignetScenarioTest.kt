package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Golgari Signet (RAV #262) — {1}, {T}: Add {B}{G}.
 */
class GolgariSignetScenarioTest : FunSpec({

    val abilityId = GolgariSignet.activatedAbilities.single().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + GolgariSignet)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("paying {1} and tapping Golgari Signet adds one black and one green mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")
        driver.giveColorlessMana(player, 1)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = signet,
                abilityId = abilityId,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )

        result.isSuccess shouldBe true
        driver.isTapped(signet) shouldBe true
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.black shouldBe 1
        pool.green shouldBe 1
        pool.colorless shouldBe 0
    }

    test("Golgari Signet cannot activate without the generic mana cost") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val signet = driver.putPermanentOnBattlefield(player, "Golgari Signet")

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = signet,
                abilityId = abilityId,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )

        result.isSuccess shouldBe false
        driver.isTapped(signet) shouldBe false
    }
})
