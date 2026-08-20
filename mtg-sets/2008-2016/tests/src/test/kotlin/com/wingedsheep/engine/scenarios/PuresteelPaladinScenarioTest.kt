package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.sdk.core.Color
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
 * Puresteel Paladin (NPH #20).
 *
 * This is intentionally written before the production definition. The registry assertion in
 * every test is the RED gate: the scenarios must fail because the exact card is not registered,
 * never because a test-only Puresteel replacement was installed.
 *
 * The post-RED assertions keep the two Oracle abilities separate:
 * - the Equipment ETB ability is a player-controlled may-draw;
 * - Metalcraft grants a distinct equip {0} ability to each Equipment you control once you control
 *   three or more artifacts, while preserving each printed equip ability;
 * - all target and payment choices are submitted explicitly.
 */
class PuresteelPaladinScenarioTest : FunSpec({

    val puresteelName = "Puresteel Paladin"

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            startingPlayer = 0,
            skipMulligans = true,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun assertProductionCardIsPresent(driver: GameTestDriver) {
        // RED gate: this must fail before the NPH production card is added.
        driver.cardRegistry.getCard(puresteelName) shouldNotBe null
    }

    fun resolveStack(driver: GameTestDriver) {
        var guard = 0
        while (driver.state.stack.isNotEmpty() && !driver.isPaused && guard++ < 30) {
            driver.bothPass()
        }
    }

    fun equipActions(
        driver: GameTestDriver,
        playerId: EntityId,
        equipmentId: EntityId,
    ): List<LegalAction> = driver.legalActions(playerId).filter { legal ->
        val action = legal.action as? ActivateAbility ?: return@filter false
        action.sourceId == equipmentId
    }

    test("Equipment ETB presents an explicit may-draw decision and draws after yes") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, puresteelName)
        val equipment = driver.putCardInHand(player, "Bonesplitter")
        driver.putCardOnTopOfLibrary(player, "Forest")
        driver.giveColorlessMana(player, 1)
        val libraryBefore = driver.state.getLibrary(player).size

        driver.castSpell(player, equipment).error shouldBe null
        driver.bothPass() // Resolve Bonesplitter; Puresteel's trigger is now on the stack.
        driver.bothPass() // Resolve the trigger into the explicit may decision.

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, true).error shouldBe null
        resolveStack(driver)

        driver.state.getLibrary(player).size shouldBe libraryBefore - 1
    }

    test("declining the Equipment ETB may-draw leaves the library unchanged") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, puresteelName)
        val equipment = driver.putCardInHand(player, "Bonesplitter")
        driver.putCardOnTopOfLibrary(player, "Forest")
        driver.giveColorlessMana(player, 1)
        val libraryBefore = driver.state.getLibrary(player).size

        driver.castSpell(player, equipment).error shouldBe null
        driver.bothPass()
        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, false).error shouldBe null
        resolveStack(driver)

        driver.state.getLibrary(player).size shouldBe libraryBefore
    }

    test("a non-Equipment entering does not trigger Puresteel's draw") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, puresteelName)
        val creature = driver.putCardInHand(player, "Grizzly Bears")
        driver.putCardOnTopOfLibrary(player, "Forest")
        driver.giveColorlessMana(player, 1)
        driver.giveMana(player, Color.GREEN)
        val libraryBefore = driver.state.getLibrary(player).size

        driver.castSpell(player, creature).error shouldBe null
        driver.bothPass()

        driver.pendingDecision shouldBe null
        driver.state.getLibrary(player).size shouldBe libraryBefore
    }

    test("Metalcraft is off at two artifacts and does not expose equip {0}") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, puresteelName)
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        driver.putPermanentOnBattlefield(player, "Basilisk Collar")
        driver.giveColorlessMana(player, 1)

        val costs = equipActions(driver, player, equipment).mapNotNull { it.manaCostString }.toSet()

        costs shouldContain "{1}"
        costs shouldNotContain "{0}"
    }

    test("Metalcraft grants equip {0} to three different Equipment and keeps printed costs") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, puresteelName)
        val equipmentByName = listOf(
            "Bonesplitter" to "{1}",
            "Basilisk Collar" to "{2}",
            "Loxodon Warhammer" to "{3}",
        ).map { (name, printedCost) ->
            Triple(driver.putPermanentOnBattlefield(player, name), name, printedCost)
        }
        driver.giveColorlessMana(player, 3)

        equipmentByName.forEach { (equipment, name, printedCost) ->
            val costs = equipActions(driver, player, equipment)
                .mapNotNull { it.manaCostString }
                .toSet()
            withClue("$name must receive the distinct metalcraft equip {0} ability") {
                costs shouldContain "{0}"
            }
            withClue("$name must retain its printed equip $printedCost ability") {
                costs shouldContain printedCost
            }
        }
    }

    test("opponent-controlled artifacts do not satisfy your Metalcraft threshold") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        driver.putCreatureOnBattlefield(player, puresteelName)
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        driver.putPermanentOnBattlefield(player, "Basilisk Collar")
        driver.putPermanentOnBattlefield(opponent, "Loxodon Warhammer")
        driver.giveColorlessMana(player, 1)

        val costs = equipActions(driver, player, equipment).mapNotNull { it.manaCostString }.toSet()

        costs shouldContain "{1}"
        costs shouldNotContain "{0}"
    }

    test("losing the third artifact removes the dynamic equip {0} ability") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        driver.putCreatureOnBattlefield(player, puresteelName)
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        driver.putPermanentOnBattlefield(player, "Basilisk Collar")
        val thirdArtifact = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")
        driver.giveColorlessMana(player, 3)

        equipActions(driver, player, equipment)
            .mapNotNull { it.manaCostString }
            .toSet() shouldContain "{0}"

        driver.moveToGraveyard(thirdArtifact)

        equipActions(driver, player, equipment)
            .mapNotNull { it.manaCostString }
            .toSet() shouldNotContain "{0}"
    }

    test("the free equip action exposes explicit target legality and rejects an opponent") {
        val driver = newDriver()
        assertProductionCardIsPresent(driver)
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        driver.putCreatureOnBattlefield(player, puresteelName)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        driver.putPermanentOnBattlefield(player, "Basilisk Collar")
        driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")

        val freeEquip = equipActions(driver, player, equipment)
            .first { it.manaCostString == "{0}" }
        freeEquip.validTargets.orEmpty() shouldContain ownCreature
        freeEquip.validTargets.orEmpty() shouldNotContain opponentCreature

        val freeEquipAbility = freeEquip.action as ActivateAbility
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = freeEquipAbility.abilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = freeEquipAbility.abilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature
    }
})
