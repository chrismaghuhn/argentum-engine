package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.hou.cards.Abrade
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Abrade (HOU #83) — choose one: deal 3 damage to a creature or destroy an artifact.
 */
class AbradeScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Abrade)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castWithMode(
        driver: GameTestDriver,
        player: com.wingedsheep.sdk.model.EntityId,
        spell: com.wingedsheep.sdk.model.EntityId,
        mode: Int,
        target: com.wingedsheep.sdk.model.EntityId
    ) {
        val chosenTarget = ChosenTarget.Permanent(target)
        driver.submit(
            CastSpell(
                playerId = player,
                cardId = spell,
                targets = listOf(chosenTarget),
                chosenModes = listOf(mode),
                modeTargetsOrdered = listOf(listOf(chosenTarget))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    test("creature mode deals 3 damage to the target creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val abrade = driver.putCardInHand(player, "Abrade")
        driver.giveMana(player, Color.RED, 1)
        driver.giveColorlessMana(player, 1)

        castWithMode(driver, player, abrade, mode = 0, target = creature)

        driver.findPermanent(opponent, "Grizzly Bears") shouldBe null
    }

    test("artifact mode destroys the target artifact") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val artifact = driver.putPermanentOnBattlefield(opponent, "Mind Stone")
        val abrade = driver.putCardInHand(player, "Abrade")
        driver.giveMana(player, Color.RED, 1)
        driver.giveColorlessMana(player, 1)

        castWithMode(driver, player, abrade, mode = 1, target = artifact)

        driver.findPermanent(opponent, "Mind Stone") shouldBe null
    }

    test("mode and target choices are explicit and creature mode exposes only creature targets") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val artifact = driver.putPermanentOnBattlefield(opponent, "Mind Stone")
        val abrade = driver.putCardInHand(player, "Abrade")
        driver.giveMana(player, Color.RED, 1)
        driver.giveColorlessMana(player, 1)

        // Omitting choices must pause for the player's mode decision; the engine may not
        // silently choose the first mode or target.
        driver.submit(CastSpell(playerId = player, cardId = abrade)).isPaused shouldBe true
        val modeDecision = driver.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
        modeDecision.options shouldBe listOf(
            "Abrade deals 3 damage to target creature",
            "Destroy target artifact",
        )
        driver.state.getHand(player) shouldContain abrade

        // After the player explicitly chooses the creature mode, target selection is still
        // a separate decision and its legal domain excludes the artifact.
        driver.submitDecision(player, OptionChosenResponse(modeDecision.id, optionIndex = 0))
        val targetDecision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val legalTargets = targetDecision.legalTargets.getValue(0)
        legalTargets shouldContain creature
        legalTargets shouldNotContain artifact
    }

    test("creature mode cannot target a noncreature artifact") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val artifact = driver.putPermanentOnBattlefield(opponent, "Mind Stone")
        val abrade = driver.putCardInHand(player, "Abrade")
        driver.giveMana(player, Color.RED, 1)
        driver.giveColorlessMana(player, 1)
        val target = ChosenTarget.Permanent(artifact)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = abrade,
                targets = listOf(target),
                chosenModes = listOf(0),
                modeTargetsOrdered = listOf(listOf(target)),
            )
        )

        result.isSuccess shouldBe false
        driver.findPermanent(opponent, "Mind Stone") shouldBe artifact
    }

    test("artifact mode cannot target a creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        val abrade = driver.putCardInHand(player, "Abrade")
        driver.giveMana(player, Color.RED, 1)
        driver.giveColorlessMana(player, 1)
        val target = ChosenTarget.Permanent(creature)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = abrade,
                targets = listOf(target),
                chosenModes = listOf(1),
                modeTargetsOrdered = listOf(listOf(target))
            )
        )

        result.isSuccess shouldBe false
        driver.findPermanent(opponent, "Grizzly Bears") shouldBe creature
    }
})
