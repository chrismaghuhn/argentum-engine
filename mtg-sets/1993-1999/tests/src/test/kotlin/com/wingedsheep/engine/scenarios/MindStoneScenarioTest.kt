package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Mind Stone (WTH #153) — "{T}: Add {C}. {1}, {T}, Sacrifice this artifact: Draw a card."
 */
class MindStoneScenarioTest : FunSpec({

    val manaAbilityId = MindStone.activatedAbilities[0].id
    val sacrificeAndDrawAbilityId = MindStone.activatedAbilities[1].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + MindStone)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("tapping Mind Stone adds one colorless mana") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val stone = driver.putPermanentOnBattlefield(player, "Mind Stone")

        val result = driver.submit(
            ActivateAbility(player, stone, manaAbilityId)
        )

        result.isSuccess shouldBe true
        driver.isTapped(stone) shouldBe true
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.colorless shouldBe 1
    }

    test("paying one and sacrificing Mind Stone draws exactly one card") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val stone = driver.putPermanentOnBattlefield(player, "Mind Stone")
        val handBefore = driver.getHandSize(player)
        val graveyardBefore = driver.getGraveyard(player).size
        driver.giveColorlessMana(player, 1)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = stone,
                abilityId = sacrificeAndDrawAbilityId,
                paymentStrategy = PaymentStrategy.FromPool
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Mind Stone") shouldBe null
        driver.getGraveyard(player).size shouldBe graveyardBefore + 1
        driver.getHandSize(player) shouldBe handBefore + 1
    }
})
