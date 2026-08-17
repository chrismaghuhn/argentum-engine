package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mbs.cards.BrassSquire
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Brass Squire (MBS #101).
 *
 * Oracle: "{T}: Attach target Equipment you control to target creature you control."
 */
class BrassSquireScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(BrassSquire, Bonesplitter))
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun activate(
        driver: GameTestDriver,
        playerId: com.wingedsheep.sdk.model.EntityId,
        sourceId: com.wingedsheep.sdk.model.EntityId,
        equipmentId: com.wingedsheep.sdk.model.EntityId,
        creatureId: com.wingedsheep.sdk.model.EntityId,
    ) {
        driver.submitSuccess(
            ActivateAbility(
                playerId = playerId,
                sourceId = sourceId,
                abilityId = BrassSquire.activatedAbilities.single().id,
                targets = listOf(
                    ChosenTarget.Permanent(equipmentId),
                    ChosenTarget.Permanent(creatureId),
                ),
            )
        )
        driver.bothPass()
    }

    test("attaches a controlled Equipment to a controlled creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val squire = driver.putCreatureOnBattlefield(player, "Brass Squire")
        val creature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        driver.removeSummoningSickness(squire)

        activate(driver, player, squire, equipment, creature)

        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe creature
        driver.state.getEntity(creature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldContain equipment
    }

    test("moves the Equipment and clears its previous attachment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val squire = driver.putCreatureOnBattlefield(player, "Brass Squire")
        val firstCreature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val secondCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        driver.removeSummoningSickness(squire)

        activate(driver, player, squire, equipment, firstCreature)
        driver.untapPermanent(squire)
        activate(driver, player, squire, equipment, secondCreature)

        driver.state.getEntity(firstCreature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldNotContain equipment
        driver.state.getEntity(secondCreature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldContain equipment
        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe secondCreature
    }

    test("requires both targets to be controlled by the activating player") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val squire = driver.putCreatureOnBattlefield(player, "Brass Squire")
        val ownCreature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val ownEquipment = driver.putPermanentOnBattlefield(player, "Bonesplitter")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val opponentEquipment = driver.putPermanentOnBattlefield(opponent, "Bonesplitter")
        driver.removeSummoningSickness(squire)

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = squire,
                abilityId = BrassSquire.activatedAbilities.single().id,
                targets = listOf(
                    ChosenTarget.Permanent(opponentEquipment),
                    ChosenTarget.Permanent(ownCreature),
                ),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(opponentEquipment)?.get<AttachedToComponent>() shouldBe null

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = squire,
                abilityId = BrassSquire.activatedAbilities.single().id,
                targets = listOf(
                    ChosenTarget.Permanent(ownEquipment),
                    ChosenTarget.Permanent(opponentCreature),
                ),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(ownEquipment)?.get<AttachedToComponent>() shouldBe null
        driver.state.getEntity(ownCreature)?.get<AttachmentsComponent>()?.attachedIds
            .orEmpty() shouldNotContain ownEquipment
        driver.state.getEntity(opponentCreature) shouldNotBe null
    }
})
