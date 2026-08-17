package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.isd.cards.ClifftopRetreat
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Clifftop Retreat (ISD #238) — enters tapped unless you control a Mountain or Plains;
 * {T}: Add {R} or {W}.
 */
class ClifftopRetreatScenarioTest : FunSpec({

    val redAbilityId = ClifftopRetreat.activatedAbilities[0].id
    val whiteAbilityId = ClifftopRetreat.activatedAbilities[1].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + ClifftopRetreat)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("Clifftop Retreat enters tapped when you control neither a Mountain nor a Plains") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val retreatCard = driver.putCardInHand(player, "Clifftop Retreat")

        driver.playLand(player, retreatCard).isSuccess shouldBe true
        val retreat = driver.findPermanent(player, "Clifftop Retreat")
        retreat shouldNotBe null
        driver.isTapped(retreat!!) shouldBe true
    }

    test("Clifftop Retreat enters untapped when you control a Mountain") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        driver.putLandOnBattlefield(player, "Mountain")
        val retreatCard = driver.putCardInHand(player, "Clifftop Retreat")

        driver.playLand(player, retreatCard).isSuccess shouldBe true
        val retreat = driver.findPermanent(player, "Clifftop Retreat")
        retreat shouldNotBe null
        driver.isTapped(retreat!!) shouldBe false
    }

    test("Clifftop Retreat's red and white abilities produce the selected color") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val retreat = driver.putPermanentOnBattlefield(player, "Clifftop Retreat")

        driver.submit(
            ActivateAbility(playerId = player, sourceId = retreat, abilityId = redAbilityId)
        ).isSuccess shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!.red shouldBe 1

        driver.untapPermanent(retreat)
        driver.submit(
            ActivateAbility(playerId = player, sourceId = retreat, abilityId = whiteAbilityId)
        ).isSuccess shouldBe true
        driver.state.getEntity(player)?.get<com.wingedsheep.engine.state.components.player.ManaPoolComponent>()!!.white shouldBe 1
    }
})
