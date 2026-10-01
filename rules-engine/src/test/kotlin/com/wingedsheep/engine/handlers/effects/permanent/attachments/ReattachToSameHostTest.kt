package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.shouldBe

/**
 * CR 701.3b: attaching an Equipment to the object it is already attached to does nothing.
 *
 * "Nothing" includes the host's list of attachments: re-equipping one of two Equipment onto the
 * creature already wearing both used to drop it from that list and append it again, so every
 * free re-equip produced a position that differed from the last only in list order. The engine AI
 * reads that as progress, and with Puresteel Paladin making equip cost {0} it shuffled the same
 * Equipment around the same creature until the game hit its step cap.
 */
class ReattachToSameHostTest : ScenarioTestBase() {

    private val equipmentA = card("Test Same-Host Equipment A") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val equipmentB = card("Test Same-Host Equipment B") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private fun GameState.attachmentsOf(host: EntityId) = getEntity(host)?.get<AttachmentsComponent>()?.attachedIds

    init {
        cardRegistry.register(listOf(equipmentA, equipmentB))

        test("equipping the creature an Equipment already equips leaves the attachment untouched") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withActivePlayer(1)
                .build()
            val bears = game.findPermanent("Grizzly Bears")!!
            val first = game.findPermanent(equipmentA.name)!!
            val second = game.findPermanent(equipmentB.name)!!
            game.state.attachmentsOf(bears) shouldBe listOf(first, second)

            val equip = cardRegistry.getCard(equipmentA.name)!!.script.activatedAbilities.first()
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = first,
                    abilityId = equip.id,
                    targets = listOf(ChosenTarget.Permanent(bears)),
                )
            ).error shouldBe null
            game.resolveStack()

            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe bears
            game.state.attachmentsOf(bears) shouldBe listOf(first, second)
        }

        test("an attach-target-Equipment effect onto its current host leaves the attachment untouched") {
            // Brass Squire: "{T}: Attach target Equipment you control to target creature you control."
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Brass Squire", summoningSickness = false)
                .withCardOnBattlefield(1, "Grizzly Bears", summoningSickness = false)
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withActivePlayer(1)
                .build()
            val squire = game.findPermanent("Brass Squire")!!
            val bears = game.findPermanent("Grizzly Bears")!!
            val first = game.findPermanent(equipmentA.name)!!
            val second = game.findPermanent(equipmentB.name)!!

            val attach = cardRegistry.getCard("Brass Squire")!!.script.activatedAbilities.first()
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = squire,
                    abilityId = attach.id,
                    targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(bears)),
                )
            ).error shouldBe null
            game.resolveStack()

            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe bears
            game.state.attachmentsOf(bears) shouldBe listOf(first, second)
        }
    }
}
