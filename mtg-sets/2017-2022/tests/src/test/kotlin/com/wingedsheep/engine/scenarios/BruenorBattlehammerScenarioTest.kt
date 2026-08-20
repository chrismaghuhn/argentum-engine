package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.afr.cards.BruenorBattlehammer
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EquipPaymentChoice
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Bruenor Battlehammer (AFR #219).
 *
 * Covers both Oracle abilities: the Equipment-count power bonus and the explicit free/normal
 * payment choices for the first equip ability activated each turn.
 */
class BruenorBattlehammerScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BruenorBattlehammer + Bonesplitter + BasiliskCollar)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.attachEquipment(equipmentId: EntityId, creatureId: EntityId) {
        val updated = state
            .updateEntity(equipmentId) { it.with(AttachedToComponent(creatureId)) }
            .updateEntity(creatureId) { container ->
                val attached = container.get<AttachmentsComponent>()?.attachedIds.orEmpty()
                container.with(AttachmentsComponent(attached + equipmentId))
            }
        replaceState(updated)
    }

    test("each creature you control gets +2/+0 for each Equipment attached to it") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)

        val bruenor = driver.putCreatureOnBattlefield(you, "Bruenor Battlehammer")
        val equippedCreature = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val firstEquipment = driver.putPermanentOnBattlefield(you, "Basilisk Collar")
        val secondEquipment = driver.putPermanentOnBattlefield(you, "Basilisk Collar")
        val creatureEquipment = driver.putPermanentOnBattlefield(you, "Basilisk Collar")

        driver.attachEquipment(firstEquipment, bruenor)
        driver.attachEquipment(secondEquipment, bruenor)
        driver.attachEquipment(creatureEquipment, equippedCreature)

        withClue("Bruenor gets +4/+0 for its two attached Equipment") {
            driver.state.projectedState.getPower(bruenor) shouldBe 9
        }
        withClue("another creature you control gets +2/+0 for its one attached Equipment") {
            driver.state.projectedState.getPower(equippedCreature) shouldBe 5
        }
        withClue("the static ability does not affect an opponent's creature") {
            driver.state.projectedState.getPower(opponentCreature) shouldBe 3
        }
    }

    test("offers free and normal payment choices for the first equip ability each turn") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(you, "Bonesplitter")
        driver.putPermanentOnBattlefield(you, "Bruenor Battlehammer")
        driver.giveColorlessMana(you, 1)

        val equipId = Bonesplitter.activatedAbilities.first().id
        val equipActions = driver.legalActions(you)
            .filter { legal ->
                val action = legal.action as? ActivateAbility ?: return@filter false
                action.sourceId == equipment && action.abilityId == equipId
            }

        val offeredCosts = equipActions.mapNotNull { it.manaCostString }.toSet()
        val offeredModes = equipActions.mapNotNull {
            (it.action as ActivateAbility).alternativePayment?.equipPayment
        }.toSet()

        equipActions.flatMap { it.validTargets.orEmpty() } shouldContain creature
        offeredCosts shouldContain "{0}"
        offeredCosts shouldContain "{1}"
        offeredModes shouldBe setOf(EquipPaymentChoice.NORMAL, EquipPaymentChoice.FREE_FIRST_EQUIP)
    }
})
