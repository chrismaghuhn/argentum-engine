package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.blc.cards.ArcaneSignet
import com.wingedsheep.mtg.sets.definitions.iko.cards.ChevillBaneOfMonsters
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Arcane Signet (CMR #297) — {T}: Add one mana of any color in your commander's color identity.
 */
class ArcaneSignetScenarioTest : FunSpec({

    val abilityId = ArcaneSignet.activatedAbilities.single().id

    fun createCommanderGame(): Pair<GameTestDriver, EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + ArcaneSignet + ChevillBaneOfMonsters)
        val players = driver.initMultiplayer(
            decks = List(2) { Deck.of("Forest" to 40) },
            format = Format.Commander(),
            commanders = List(2) { ChevillBaneOfMonsters.name },
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver to players[0]
    }

    test("Arcane Signet produces a chosen color from the commander's identity") {
        val (driver, player) = createCommanderGame()
        val signet = driver.putPermanentOnBattlefield(player, "Arcane Signet")

        val activation = driver.submit(
            ActivateAbility(playerId = player, sourceId = signet, abilityId = abilityId)
        )
        activation.isPaused shouldBe true

        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseColorDecision>()
        decision.playerId shouldBe player
        decision.availableColors shouldBe setOf(Color.BLACK, Color.GREEN)
        driver.submitDecision(player, ColorChosenResponse(decision.id, Color.BLACK)).isSuccess shouldBe true

        driver.isTapped(signet) shouldBe true
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>()!!
        pool.black shouldBe 1
        pool.green shouldBe 0
        pool.colorless shouldBe 0
    }

    test("Arcane Signet cannot produce mana until it is untapped") {
        val (driver, player) = createCommanderGame()
        val signet = driver.putPermanentOnBattlefield(player, "Arcane Signet")
        driver.tapPermanent(signet)

        val result = driver.submit(
            ActivateAbility(playerId = player, sourceId = signet, abilityId = abilityId)
        )

        result.isSuccess shouldBe false
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.let { pool ->
            pool.black shouldBe 0
            pool.green shouldBe 0
        }
    }
})
