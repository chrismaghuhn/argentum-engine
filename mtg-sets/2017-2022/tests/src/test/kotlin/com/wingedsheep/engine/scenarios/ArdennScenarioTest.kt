package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CollectionFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Test-local composition characterization for issue #47. This is intentionally not a production
 * Ardenn definition; it proves that the generic pipeline vocabulary composes the historical card
 * shape without an Ardenn-specific executor or handler.
 */
class ArdennScenarioTest : ScenarioTestBase() {

    private val transfer = card("Test Ardenn Composition Transfer") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Attach any number of selected attachments to target permanent or player."
        spell {
            target = Targets.PermanentOrPlayer
            effect = Effects.Pipeline {
                val candidates = gather(CardSource.ControlledPermanents(), name = "ardenn_candidates")
                val legal = filter(
                    candidates,
                    CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
                    name = "ardenn_legal",
                )
                val selected = chooseAnyNumber(legal, name = "ardenn_selected")
                attach(selected, EffectTarget.ContextTarget(0))
            }
        }
    }

    private val equipmentA = card("Test Ardenn Equipment A") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val equipmentB = card("Test Ardenn Equipment B") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val opponentEquipment = card("Test Ardenn Opponent Equipment") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val playerAura = card("Test Ardenn Player Aura") {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant player"
        auraTarget = Targets.Player
    }

    private val creatureAura = card("Test Ardenn Creature Aura") {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature"
        auraTarget = Targets.Creature
    }

    init {
        cardRegistry.register(
            listOf(transfer, equipmentA, equipmentB, opponentEquipment, playerAura, creatureAura)
        )

        test("mixed controlled attachments use one order boundary and exclude opponent-owned sources") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardOnBattlefield(2, "Centaur Courser")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withCardAttachedTo(2, opponentEquipment.name, "Centaur Courser")
                .withCardInHand(1, transfer.name)
                .withActivePlayer(1)
                .build()

            val source = game.findPermanent("Grizzly Bears")!!
            val destination = game.findPermanents("Hill Giant")[0]
            val selectedA = game.findPermanent(equipmentA.name)!!
            val selectedB = game.findPermanent(equipmentB.name)!!
            val opponentEquipmentId = game.findPermanent(opponentEquipment.name)!!

            game.castSpell(1, transfer.name, destination).error shouldBe null
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options.shouldContainExactlyInAnyOrder(selectedA, selectedB)
            game.selectCards(listOf(selectedA, selectedB)).error shouldBe null

            val order = game.getPendingDecision().shouldBeInstanceOf<OrderObjectsDecision>()
            game.state.getEntity(selectedA)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.state.getEntity(selectedB)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.submitObjectOrdering(listOf(selectedB, selectedA)).error shouldBe null

            game.state.getEntity(selectedA)?.get<AttachedToComponent>()?.targetId shouldBe destination
            game.state.getEntity(selectedB)?.get<AttachedToComponent>()?.targetId shouldBe destination
            game.state.getEntity(opponentEquipmentId)?.get<AttachedToComponent>()?.targetId shouldBe
                game.findPermanent("Centaur Courser")!!
            game.state.getEntity(destination)?.get<AttachmentsComponent>()?.attachedIds
                .orEmpty().shouldContainExactlyInAnyOrder(selectedA, selectedB)
            order.objects.toSet() shouldBe setOf(selectedA, selectedB)
        }

        test("player-target composition keeps player-enchanting Auras and rejects creature Auras") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, playerAura.name, "Grizzly Bears")
                .withCardAttachedTo(1, creatureAura.name, "Grizzly Bears")
                .withCardInHand(1, transfer.name)
                .withActivePlayer(1)
                .build()

            val playerAuraId = game.findPermanent(playerAura.name)!!
            val creatureAuraId = game.findPermanent(creatureAura.name)!!
            game.castSpellTargetingPlayer(1, transfer.name, 2).error shouldBe null
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldBe listOf(playerAuraId)
            game.selectCards(listOf(playerAuraId)).error shouldBe null
            game.getPendingDecision() shouldBe null
            game.state.getEntity(playerAuraId)?.get<AttachedToComponent>()?.targetId shouldBe game.player2Id
            game.state.getEntity(creatureAuraId)?.get<AttachedToComponent>()?.targetId shouldBe
                game.findPermanent("Grizzly Bears")!!
        }

        test("declining the explicit any-number selection leaves every attachment unchanged") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardInHand(1, transfer.name)
                .withActivePlayer(1)
                .build()

            val source = game.findPermanent("Grizzly Bears")!!
            val destination = game.findPermanent("Hill Giant")!!
            val equipment = game.findPermanent(equipmentA.name)!!
            game.castSpell(1, transfer.name, destination).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(emptyList()).error shouldBe null
            game.getPendingDecision() shouldBe null
            game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.state.getEntity(destination)?.get<AttachmentsComponent>()?.attachedIds.orEmpty()
                .contains(equipment) shouldBe false
        }
    }
}
