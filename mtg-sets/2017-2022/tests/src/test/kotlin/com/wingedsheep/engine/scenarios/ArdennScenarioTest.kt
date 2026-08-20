package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Focused GREEN scenarios for the production Ardenn card definition (CMR #10). */
class ArdennScenarioTest : ScenarioTestBase() {

    private companion object {
        const val ARDENN = "Ardenn, Intrepid Archaeologist"
        const val OWN_HOST = "Grizzly Bears"
        const val ARTIFACT_HOST = "Ardenn Artifact Host"
        const val OPPONENT_TARGET = "Hill Giant"
        const val VALID_AURA = "Ardenn Valid Aura"
        const val INVALID_AURA = "Ardenn Invalid Aura"
        const val EQUIPMENT_A = "Ardenn Equipment A"
        const val EQUIPMENT_B = "Ardenn Equipment B"
        const val OPPONENT_EQUIPMENT = "Ardenn Opponent Equipment"
        const val PLAYER_AURA = "Ardenn Player Aura"
        const val CREATURE_AURA = "Ardenn Creature Aura"
    }

    private val validAura = card(VALID_AURA) {
        manaCost = "{1}{W}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature"
        auraTarget = Targets.Creature
    }

    private val invalidAura = card(INVALID_AURA) {
        manaCost = "{1}{U}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant artifact"
        auraTarget = Targets.Artifact
    }

    private val equipmentA = card(EQUIPMENT_A) {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val equipmentB = card(EQUIPMENT_B) {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val opponentEquipment = card(OPPONENT_EQUIPMENT) {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val playerAura = card(PLAYER_AURA) {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant player"
        auraTarget = Targets.Player
    }

    private val creatureAura = card(CREATURE_AURA) {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature"
        auraTarget = Targets.Creature
    }

    private val artifactHost = card(ARTIFACT_HOST) {
        manaCost = "{0}"
        typeLine = "Artifact"
        oracleText = ""
    }

    init {
        cardRegistry.register(
            listOf(
                validAura,
                invalidAura,
                equipmentA,
                equipmentB,
                opponentEquipment,
                playerAura,
                creatureAura,
                artifactHost,
            )
        )

        test("moves selected mixed attachments through one order boundary and excludes opponent sources") {
            val game = scenarioWithAttachments()
            val target = game.permanent(OPPONENT_TARGET)

            val may = game.beginArdenn()
            may.playerId shouldBe game.player1Id
            game.answerYesNo(true).error shouldBe null
            game.chooseArdennTarget(target)
            game.selectTargets(listOf(target)).error shouldBe null
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            val selectedA = game.permanent(EQUIPMENT_A)
            val selectedB = game.permanent(EQUIPMENT_B)
            val optionNames = selection.options.mapNotNull { game.cardNameOf(it) }.toSet()
            optionNames shouldBe setOf(EQUIPMENT_A, EQUIPMENT_B, VALID_AURA)
            selection.options shouldContain selectedA
            selection.options shouldContain selectedB
            selection.options shouldNotContain game.permanent(INVALID_AURA)
            selection.options shouldNotContain game.permanent(OPPONENT_EQUIPMENT)

            game.selectCards(listOf(selectedA, selectedB)).error shouldBe null
            val order = game.getPendingDecision().shouldBeInstanceOf<OrderObjectsDecision>()
            game.submitObjectOrdering(listOf(selectedB, selectedA)).error shouldBe null

            game.state.getEntity(selectedA)?.get<AttachedToComponent>()?.targetId shouldBe target
            game.state.getEntity(selectedB)?.get<AttachedToComponent>()?.targetId shouldBe target
            game.state.getEntity(target)?.get<AttachmentsComponent>()?.attachedIds
                .orEmpty().shouldContainExactlyInAnyOrder(selectedA, selectedB)
            order.objects.toSet() shouldBe setOf(selectedA, selectedB)
        }

        test("player target keeps player-enchanting Auras and rejects creature Auras") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, ARDENN)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, PLAYER_AURA, "Grizzly Bears")
                .withCardAttachedTo(1, CREATURE_AURA, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val playerAuraId = game.permanent(PLAYER_AURA)
            val creatureAuraId = game.permanent(CREATURE_AURA)
            val may = game.beginArdenn()
            game.answerYesNo(true).error shouldBe null
            game.chooseArdennTarget(game.player2Id)
            game.selectTargets(listOf(game.player2Id)).error shouldBe null
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldBe listOf(playerAuraId)
            game.selectCards(listOf(playerAuraId)).error shouldBe null
            game.getPendingDecision() shouldBe null
            game.state.getEntity(playerAuraId)?.get<AttachedToComponent>()?.targetId shouldBe game.player2Id
            game.state.getEntity(creatureAuraId)?.get<AttachedToComponent>()?.targetId shouldBe
                game.permanent("Grizzly Bears")
            withClue("the optional trigger was offered to Ardenn's controller") {
                may.playerId shouldBe game.player1Id
            }
        }

        test("declining the explicit any-number selection leaves every attachment unchanged") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, ARDENN)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, EQUIPMENT_A, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val source = game.permanent("Grizzly Bears")
            val destination = game.permanent("Hill Giant")
            val equipment = game.permanent(EQUIPMENT_A)
            game.beginArdenn()
            game.answerYesNo(true).error shouldBe null
            game.chooseArdennTarget(destination)
            game.selectTargets(listOf(destination)).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(emptyList()).error shouldBe null
            game.getPendingDecision() shouldBe null

            game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.state.getEntity(destination)?.get<AttachmentsComponent>()?.attachedIds
                .orEmpty() shouldNotContain equipment
        }

        test("declining Ardenn's optional trigger leaves the board untouched") {
            val game = scenarioWithAttachments(includeInvalidAura = false)
            val aura = game.permanent(VALID_AURA)
            val equipment = game.permanent(EQUIPMENT_A)
            val source = game.permanent(OWN_HOST)

            game.beginArdenn()
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.state.getEntity(aura)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.hasPendingDecision() shouldBe false
        }
    }

    private fun scenarioWithAttachments(includeInvalidAura: Boolean = true): TestGame {
        var builder = scenario()
            .withPlayers()
            .withCardOnBattlefield(1, ARDENN)
            .withCardOnBattlefield(1, OWN_HOST)
            .withCardAttachedTo(1, VALID_AURA, OWN_HOST)
            .withCardAttachedTo(1, EQUIPMENT_A, OWN_HOST)
            .withCardAttachedTo(1, EQUIPMENT_B, OWN_HOST)
            .withCardOnBattlefield(2, OPPONENT_TARGET)
            .withCardOnBattlefield(2, "Centaur Courser")
            .withCardAttachedTo(2, OPPONENT_EQUIPMENT, "Centaur Courser")
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)

        if (includeInvalidAura) {
            builder = builder
                .withCardOnBattlefield(1, ARTIFACT_HOST)
                .withCardAttachedTo(1, INVALID_AURA, ARTIFACT_HOST)
        }
        return builder.build()
    }

    private fun TestGame.beginArdenn(): YesNoDecision {
        var iterations = 0
        while (
            state.pendingDecision == null &&
            (state.phase != Phase.COMBAT || state.step != Step.BEGIN_COMBAT)
        ) {
            passPriority().error shouldBe null
            iterations++
            check(iterations < 30) { "Could not reach Ardenn's beginning-of-combat trigger" }
        }

        if (state.pendingDecision == null) {
            resolveStack()
        }
        return getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
    }

    private fun TestGame.chooseArdennTarget(target: EntityId): ChooseTargetsDecision {
        val decision = getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
        decision.legalTargets.values.flatten() shouldContain target
        return decision
    }

    private fun TestGame.permanent(name: String): EntityId =
        state.getBattlefield().single { id ->
            state.getEntity(id)?.get<CardComponent>()?.name == name
        }

    private fun TestGame.cardNameOf(id: EntityId): String? =
        state.getEntity(id)?.get<CardComponent>()?.name
}
