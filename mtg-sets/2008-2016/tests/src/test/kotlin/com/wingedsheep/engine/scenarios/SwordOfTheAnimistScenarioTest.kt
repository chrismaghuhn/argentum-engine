package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.LibrarySearchedEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ori.cards.SwordOfTheAnimist
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Sword of the Animist (ORI #240).
 *
 * "Equipped creature gets +1/+1.
 * Whenever equipped creature attacks, you may search your library for a basic land card,
 * put it onto the battlefield tapped, then shuffle.
 * Equip {2}"
 */
class SwordOfTheAnimistScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + SwordOfTheAnimist)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), skipMulligans = true, startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equip(driver: GameTestDriver, player: EntityId, sword: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 2)
        driver.submitSuccess(
            ActivateAbility(
                playerId = player,
                sourceId = sword,
                abilityId = SwordOfTheAnimist.activatedAbilities.first().id,
                targets = listOf(ChosenTarget.Permanent(creature)),
            ),
        )
        driver.bothPass()
    }

    fun resolveStack(driver: GameTestDriver) {
        var guard = 0
        while (guard++ < 30 && driver.state.stack.isNotEmpty() && !driver.isPaused) {
            driver.bothPass()
        }
    }

    test("equip grants +1/+1, transfers cleanly, and rejects illegal timing/targets") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opposingCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val sword = driver.putPermanentOnBattlefield(player, "Sword of the Animist")

        equip(driver, player, sword, first)
        driver.state.projectedState.getPower(first) shouldBe 4
        driver.state.projectedState.getToughness(first) shouldBe 4
        driver.state.projectedState.getPower(second) shouldBe 3
        driver.state.getEntity(sword)?.get<AttachedToComponent>()?.targetId shouldBe first

        equip(driver, player, sword, second)
        driver.state.projectedState.getPower(first) shouldBe 3
        driver.state.projectedState.getPower(second) shouldBe 4
        driver.state.getEntity(sword)?.get<AttachedToComponent>()?.targetId shouldBe second

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = sword,
                abilityId = SwordOfTheAnimist.activatedAbilities.first().id,
                targets = listOf(ChosenTarget.Permanent(opposingCreature)),
            ),
        ).isSuccess shouldBe false

        driver.state.getEntity(sword)?.get<AttachedToComponent>()?.targetId shouldBe second
    }

    test("attacking equipped creature may fetch a basic land onto the battlefield tapped") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val nonBasic = driver.putCardOnTopOfLibrary(player, "Lightning Bolt")
        val forest = driver.putCardOnTopOfLibrary(player, "Forest")
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        driver.removeSummoningSickness(creature)
        val sword = driver.putPermanentOnBattlefield(player, "Sword of the Animist")
        equip(driver, player, sword, creature)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        val attack = driver.declareAttackers(player, listOf(creature), opponent)
        withClue("declaring the equipped creature's attack should succeed: ${attack.error}") {
            attack.error shouldBe null
        }
        resolveStack(driver)

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, true)
        val selection = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        selection.options shouldContain forest
        selection.options shouldNotContain nonBasic
        driver.submitCardSelection(player, listOf(forest))
        resolveStack(driver)

        driver.findPermanent(player, "Forest") shouldBe forest
        driver.isTapped(forest) shouldBe true
        driver.state.getLibrary(player) shouldNotContain forest
        driver.events.filterIsInstance<LibrarySearchedEvent>().any { it.playerId == player } shouldBe true
        driver.events.filterIsInstance<LibraryShuffledEvent>().any { it.playerId == player } shouldBe true
    }

    test("declining the attack trigger leaves the basic land in the library") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val forest = driver.putCardOnTopOfLibrary(player, "Forest")
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        driver.removeSummoningSickness(creature)
        val sword = driver.putPermanentOnBattlefield(player, "Sword of the Animist")
        equip(driver, player, sword, creature)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(player, listOf(creature), opponent).error shouldBe null
        resolveStack(driver)

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, false)
        resolveStack(driver)

        driver.state.getLibrary(player) shouldContain forest
        driver.findPermanent(player, "Forest") shouldBe null
    }
})
