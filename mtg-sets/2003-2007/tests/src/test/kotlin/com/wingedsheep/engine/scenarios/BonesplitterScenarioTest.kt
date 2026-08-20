package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Bonesplitter (MRD #146) — "Equipped creature gets +2/+0. Equip {1}."
 *
 * These scenarios exercise the printed Equipment bonus through the real Equip activation and
 * verify that moving the Equipment clears both sides of the previous attachment link.
 */
class BonesplitterScenarioTest : FunSpec({

    val equipAbilityId = Bonesplitter.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Bonesplitter)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equip(driver: GameTestDriver, player: EntityId, equipment: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    test("Equip gives the creature exactly +2/+0") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")

        equip(driver, player, equipment, creature)

        projector.getProjectedPower(driver.state, creature) shouldBe 5
        projector.getProjectedToughness(driver.state, creature) shouldBe 3
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe creature
        driver.state.getEntity(creature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldContain equipment
    }

    test("re-equipping removes the old bonus and leaves one coherent attachment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val firstCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val secondCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")

        equip(driver, player, equipment, firstCreature)
        projector.getProjectedPower(driver.state, firstCreature) shouldBe 5

        equip(driver, player, equipment, secondCreature)

        projector.getProjectedPower(driver.state, firstCreature) shouldBe 3
        projector.getProjectedPower(driver.state, secondCreature) shouldBe 5
        driver.state.getEntity(firstCreature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldNotContain equipment
        driver.state.getEntity(secondCreature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldContain equipment
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe secondCreature
    }

    test("Equip only targets your creatures, only works at sorcery timing, and detaches from a leaving host") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")

        equip(driver, player, equipment, ownCreature)
        projector.getProjectedPower(driver.state, ownCreature) shouldBe 5

        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe ownCreature

        val caster = driver.priorityPlayer!!
        val lightningBolt = driver.putCardInHand(caster, "Lightning Bolt")
        driver.giveMana(caster, com.wingedsheep.sdk.core.Color.RED, 1)
        driver.castSpellWithTargets(
            caster,
            lightningBolt,
            listOf(ChosenTarget.Permanent(ownCreature)),
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.state.getEntity(equipment)?.get<AttachedToComponent>() shouldBe null
        driver.findPermanent(player, "Centaur Courser") shouldBe null
    }
})
