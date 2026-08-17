package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.FreeFirstEquipEachTurn
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantDynamicStatsEffect
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.values.AttachmentKind
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.values.EntityNumericProperty
import com.wingedsheep.sdk.scripting.values.EntityReference
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec

/**
 * Bruenor Battlehammer (AFR #219).
 *
 * Current Oracle:
 * - Each creature you control gets +2/+0 for each Equipment attached to it.
 * - You may pay {0} rather than pay the equip cost of the first equip ability you activate each
 *   turn.
 *
 * The first clause is characterized with the trusted dynamic-stat and attachment primitives. The
 * second test is intentionally RED: FreeFirstEquipEachTurn currently rewrites the first equip
 * cost to {0} before the player can choose, so the normal equip-cost alternative is not exposed at
 * the legal-action boundary. Bruenor must remain unimplemented until that reusable payment choice
 * is externally controlled.
 */
class BruenorBattlehammerScenarioTest : FunSpec({

    val bruenorFixture = card("Bruenor Battlehammer") {
        manaCost = "{2}{R}{W}"
        colorIdentity = "RW"
        typeLine = "Legendary Creature — Dwarf Warrior"
        oracleText = "Each creature you control gets +2/+0 for each Equipment attached to it.\n" +
            "You may pay {0} rather than pay the equip cost of the first equip ability you activate each turn."
        power = 5
        toughness = 3

        staticAbility {
            ability = GrantDynamicStatsEffect(
                filter = GroupFilter(GameObjectFilter.Creature.youControl()),
                powerBonus = DynamicAmount.Multiply(
                    DynamicAmount.EntityProperty(
                        EntityReference.AffectedEntity,
                        EntityNumericProperty.AttachmentCount(AttachmentKind.EQUIPMENT)
                    ),
                    2
                ),
                toughnessBonus = DynamicAmount.Fixed(0),
            )
        }
        staticAbility {
            ability = FreeFirstEquipEachTurn
        }
    }

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + bruenorFixture + Bonesplitter + BasiliskCollar)
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

    test("counts every attached Equipment on each creature you control") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)

        val bruenor = driver.putCreatureOnBattlefield(you, "Bruenor Battlehammer")
        val equippedCreature = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val bruenorEquipment = driver.putPermanentOnBattlefield(you, "Basilisk Collar")
        val creatureEquipment = driver.putPermanentOnBattlefield(you, "Basilisk Collar")

        driver.attachEquipment(bruenorEquipment, bruenor)
        driver.attachEquipment(creatureEquipment, equippedCreature)

        withClue("Bruenor gets +2/+0 for its one attached Equipment") {
            driver.state.projectedState.getPower(bruenor) shouldBe 7
        }
        withClue("another creature you control gets +2/+0 for its one attached Equipment") {
            driver.state.projectedState.getPower(equippedCreature) shouldBe 5
        }
        withClue("the static ability does not affect an opponent's creature") {
            driver.state.projectedState.getPower(opponentCreature) shouldBe 3
        }
    }

    test("exposes both the free and normal first-equip payment choices") {
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
        equipActions.flatMap { it.validTargets.orEmpty() } shouldContain creature
        withClue("Bruenor's may choice must be visible to the activating player") {
            offeredCosts shouldContain "{0}"
            offeredCosts shouldContain "{1}"
        }
    }
})
