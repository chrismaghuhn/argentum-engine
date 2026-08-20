package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** End-to-end coverage for Astor, Bearer of Blades (DMU #194). */
class AstorBearerOfBladesScenarioTest : FunSpec({

    val testEquipment = card("Astor Test Equipment") {
        manaCost = "{1}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equipped creature gets +1/+0.\nEquip {3}"
        equipAbility("{3}")
    }

    val testVehicle = card("Astor Test Vehicle") {
        manaCost = "{3}"
        typeLine = "Artifact — Vehicle"
        power = 4
        toughness = 4
        oracleText = "Crew 3"
        keywordAbility(KeywordAbility.crew(3))
    }

    val testCrewer = card("Astor Test Crewer") {
        manaCost = "{3}"
        typeLine = "Creature — Human"
        power = 3
        toughness = 3
    }

    val astorName = "Astor, Bearer of Blades"

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(testEquipment, testVehicle, testCrewer))
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            startingPlayer = 0,
            skipMulligans = true,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun libraryNames(driver: GameTestDriver, player: EntityId): List<String> =
        driver.state.getZone(ZoneKey(player, Zone.LIBRARY)).mapNotNull { id ->
            driver.state.getEntity(id)?.get<CardComponent>()?.name
        }

    test("ETB choice and exact Equipment and Vehicle grants remain player-visible") {
        val driver = newDriver()
        val active = driver.activePlayer!!

        // RED gate: this must fail before the production card exists, without importing a missing
        // card definition or manufacturing a test-only Astor replacement.
        driver.cardRegistry.getCard(astorName) shouldNotBe null

        val equipment = driver.putPermanentOnBattlefield(active, testEquipment.name)
        val vehicle = driver.putPermanentOnBattlefield(active, testVehicle.name)
        val crewer = driver.putCreatureOnBattlefield(active, testCrewer.name)

        // putCardOnTopOfLibrary prepends; the seven IDs below are the complete looked-at slice.
        val nonMatching = listOf(
            driver.putCardOnTopOfLibrary(active, "Forest"),
            driver.putCardOnTopOfLibrary(active, "Island"),
            driver.putCardOnTopOfLibrary(active, "Swamp"),
            driver.putCardOnTopOfLibrary(active, "Mountain"),
            driver.putCardOnTopOfLibrary(active, "Grizzly Bears"),
        )
        val topVehicle = driver.putCardOnTopOfLibrary(active, testVehicle.name)
        val topEquipment = driver.putCardOnTopOfLibrary(active, testEquipment.name)

        val astor = driver.putCardInHand(active, astorName)
        driver.giveMana(active, Color.RED)
        driver.giveMana(active, Color.WHITE)
        driver.giveColorlessMana(active, 2)
        driver.castSpell(active, astor).isSuccess shouldBe true
        driver.bothPass() // resolve Astor; its ETB trigger is placed on the stack
        driver.bothPass() // resolve the ETB trigger into the explicit look/reveal decision

        val decision = driver.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
        decision.minSelections shouldBe 0
        decision.maxSelections shouldBe 1
        decision.options shouldContainExactlyInAnyOrder listOf(topEquipment, topVehicle)
        decision.nonSelectableOptions shouldContainExactlyInAnyOrder nonMatching

        driver.submitCardSelection(active, listOf(topVehicle)).isSuccess shouldBe true
        driver.getHand(active) shouldContain topVehicle
        // The unselected matching Equipment is part of the random-order remainder too.
        libraryNames(driver, active).takeLast(6) shouldContainExactlyInAnyOrder
            (nonMatching + topEquipment).map { id -> driver.state.getEntity(id)?.get<CardComponent>()?.name!! }

        // Astor grants a new equip {1} ability; it must not be represented as a global cost
        // reduction on the Equipment's printed equip {3} ability.
        driver.giveColorlessMana(active, 1)
        val printedEquipId = testEquipment.activatedAbilities.single().id
        val grantedEquip = driver.legalActions(active)
            .filter { legal ->
                legal.action is ActivateAbility &&
                    (legal.action as ActivateAbility).sourceId == equipment &&
                    legal.affordable
            }
            .single()
        (grantedEquip.action as ActivateAbility).abilityId shouldNotBe printedEquipId
        driver.submit(
            ActivateAbility(
                playerId = active,
                sourceId = equipment,
                abilityId = (grantedEquip.action as ActivateAbility).abilityId,
                targets = listOf(
                    com.wingedsheep.engine.state.components.stack.ChosenTarget.Permanent(crewer)
                ),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe crewer

        // Crew remains a dynamic tap-and-power choice. The granted Crew 1 action must be
        // enumerated separately from the Vehicle's printed Crew 3 action.
        val crewActions = driver.legalActions(active)
            .filter { legal ->
                legal.action is CrewVehicle &&
                    (legal.action as CrewVehicle).vehicleId == vehicle
            }
        crewActions.map { it.tapForPowerRequired }.toSet() shouldBe setOf(1, 3)
        val crewOne = crewActions.single { it.tapForPowerRequired == 1 }
        val selectableCrew = crewOne.tapForPowerCreatures
            ?: error("Crew 1 action must expose its selectable tap-for-power creatures")
        selectableCrew.map { it.entityId } shouldContain crewer
        // Preserve the enumerated origin key: the printed Crew 3 and granted Crew 1 are
        // intentionally distinct legal actions.
        val crewOneAction = crewOne.action.shouldBeInstanceOf<CrewVehicle>()
        driver.submit(crewOneAction.copy(crewCreatures = listOf(crewer))).isSuccess shouldBe true
        driver.bothPass()
        driver.state.projectedState.isCreature(vehicle) shouldBe true
    }
})
