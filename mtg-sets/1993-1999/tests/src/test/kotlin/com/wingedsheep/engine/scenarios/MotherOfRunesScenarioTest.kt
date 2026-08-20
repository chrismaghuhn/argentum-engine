package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ulg.cards.MotherOfRunes
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Characterization scaffold for Mother of Runes (ULG #14). */
class MotherOfRunesScenarioTest : FunSpec({

    val abilityId = MotherOfRunes.activatedAbilities.single().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + MotherOfRunes)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun chooseRed(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId) {
        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseColorDecision>()
        driver.submitDecision(player, ColorChosenResponse(decision.id, Color.RED)).isSuccess shouldBe true
    }

    test("tapping Mother of Runes presents a color choice and grants protection only to the chosen creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val target = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val other = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val mother = driver.putCreatureOnBattlefield(player, "Mother of Runes")
        driver.removeSummoningSickness(mother)

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = mother,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(target)),
            )
        )
        result.isSuccess shouldBe true
        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<ChooseColorDecision>()
        chooseRed(driver, player)

        val projected = projector.project(driver.state)
        projected.hasKeyword(target, "PROTECTION_FROM_RED") shouldBe true
        projected.hasKeyword(target, "PROTECTION_FROM_BLUE") shouldBe false
        projected.hasKeyword(other, "PROTECTION_FROM_RED") shouldBe false
        projected.hasKeyword(opponentCreature, "PROTECTION_FROM_RED") shouldBe false
    }

    test("only your creatures are legal targets, the source taps, and the grant expires at end of turn") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val mother = driver.putCreatureOnBattlefield(player, "Mother of Runes")
        driver.removeSummoningSickness(mother)

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = mother,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = mother,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        chooseRed(driver, player)

        driver.state.getEntity(mother)?.get<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe com.wingedsheep.engine.state.components.battlefield.TappedComponent
        projector.project(driver.state).hasKeyword(ownCreature, "PROTECTION_FROM_RED") shouldBe true

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = mother,
                abilityId = abilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature)),
            )
        ).isSuccess shouldBe false

        driver.passPriorityUntil(Step.UPKEEP)
        projector.project(driver.state).hasKeyword(ownCreature, "PROTECTION_FROM_RED") shouldBe false
    }

})
