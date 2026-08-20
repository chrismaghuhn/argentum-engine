package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.conflux.cards.PathToExile
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Path to Exile (CON #15) — exile plus the target controller's optional basic-land search. */
class PathToExileScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + PathToExile)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castPath(driver: GameTestDriver, caster: com.wingedsheep.sdk.model.EntityId, victim: com.wingedsheep.sdk.model.EntityId): com.wingedsheep.sdk.model.EntityId {
        val spell = driver.putCardInHand(caster, "Path to Exile")
        driver.giveMana(caster, Color.WHITE, 1)
        driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spell,
                targets = listOf(ChosenTarget.Permanent(victim)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        return spell
    }

    test("exiles a creature, asks its controller, and searches that controller's library explicitly") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val victimController = driver.getOpponent(caster)
        val victim = driver.putCreatureOnBattlefield(victimController, "Grizzly Bears")
        val forest = driver.putCardOnTopOfLibrary(victimController, "Forest")

        castPath(driver, caster, victim)

        val may = driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        may.playerId shouldBe victimController
        driver.submitYesNo(victimController, true).error shouldBe null

        val search = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        search.playerId shouldBe victimController
        search.options shouldContain forest
        driver.submitCardSelection(victimController, listOf(forest)).isSuccess shouldBe true

        driver.getExile(victimController) shouldContain victim
        driver.state.getBattlefield(victimController) shouldContain forest
        driver.state.getEntity(forest)?.get<TappedComponent>() shouldBe TappedComponent
        driver.state.getLibrary(victimController) shouldNotContain forest
        driver.state.getEntity(victim)?.get<CardComponent>()?.name shouldBe "Grizzly Bears"
    }

    test("the controller may decline the search, while noncreatures remain illegal targets") {
        val driver = createDriver()
        val caster = driver.activePlayer!!
        val victimController = driver.getOpponent(caster)
        val victim = driver.putCreatureOnBattlefield(victimController, "Grizzly Bears")
        val forest = driver.putCardOnTopOfLibrary(victimController, "Forest")
        val noncreature = driver.putPermanentOnBattlefield(victimController, "Commander's Sphere")
        val spell = driver.putCardInHand(caster, "Path to Exile")
        driver.giveMana(caster, Color.WHITE, 1)

        driver.submit(
            CastSpell(
                playerId = caster,
                cardId = spell,
                targets = listOf(ChosenTarget.Permanent(noncreature)),
            )
        ).isSuccess shouldBe false

        castPath(driver, caster, victim)
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>().playerId shouldBe victimController
        driver.submitYesNo(victimController, false).isSuccess shouldBe true

        driver.getExile(victimController) shouldContain victim
        driver.state.getLibrary(victimController) shouldContain forest
        driver.state.getBattlefield(victimController) shouldNotContain forest
    }

})
