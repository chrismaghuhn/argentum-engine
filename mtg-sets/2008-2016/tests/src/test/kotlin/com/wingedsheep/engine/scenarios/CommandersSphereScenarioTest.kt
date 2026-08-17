package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.c14.cards.CommandersSphere
import com.wingedsheep.mtg.sets.definitions.iko.cards.ChevillBaneOfMonsters
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Commander's Sphere (C14 #54): commander-identity mana and sacrifice-to-draw. */
class CommandersSphereScenarioTest : FunSpec({

    val manaAbilityId = CommandersSphere.activatedAbilities[0].id
    val sacrificeAbilityId = CommandersSphere.activatedAbilities[1].id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + CommandersSphere + ChevillBaneOfMonsters)
        driver.initMultiplayer(
            decks = List(2) { Deck.of("Forest" to 40) },
            format = Format.Commander(),
            commanders = List(2) { ChevillBaneOfMonsters.name },
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("mana ability exposes commander colors and adds the chosen color") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sphere = driver.putPermanentOnBattlefield(player, "Commander's Sphere")

        val activation = driver.submit(ActivateAbility(player, sphere, manaAbilityId))
        activation.isPaused shouldBe true

        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseColorDecision>()
        decision.availableColors shouldBe setOf(Color.BLACK, Color.GREEN)
        driver.submitDecision(player, ColorChosenResponse(decision.id, Color.BLACK)).isSuccess shouldBe true

        driver.isTapped(sphere) shouldBe true
        driver.state.getEntity(player)?.get<ManaPoolComponent>()?.black shouldBe 1
    }

    test("sacrificing it draws a card without a mana payment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sphere = driver.putPermanentOnBattlefield(player, "Commander's Sphere")
        val handBefore = driver.getHandSize(player)
        val graveyardBefore = driver.getGraveyard(player).size

        val activation = driver.submit(ActivateAbility(player, sphere, sacrificeAbilityId))
        activation.isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Commander's Sphere") shouldBe null
        driver.getGraveyard(player).size shouldBe graveyardBefore + 1
        driver.getHandSize(player) shouldBe handBefore + 1
    }
})
