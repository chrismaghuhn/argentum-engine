package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.references.Player
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * RED-first characterization for Ardenn, Intrepid Archaeologist (CMR #10).
 *
 * Current Oracle, verified against Scryfall:
 *
 * At the beginning of combat on your turn, you may attach any number of Auras and Equipment you
 * control to target permanent or player.
 * Partner (You can have two commanders if both have partner.)
 *
 * The test-only card deliberately stops after the explicit attachment selection. The engine has
 * no generic primitive that can then attach an arbitrary selected mixture of existing Auras and
 * Equipment to one target permanent or player while re-checking each attachment's legality. Do
 * not replace this characterization with card-specific effect code.
 */
class ArdennScenarioTest : ScenarioTestBase() {

    private companion object {
        const val ARDENN = "Ardenn, Intrepid Archaeologist [characterization]"
        const val OWN_HOST = "Ardenn Own Host"
        const val ARTIFACT_HOST = "Ardenn Artifact Host"
        const val OPPONENT_TARGET = "Ardenn Opponent Target"
        const val VALID_AURA = "Ardenn Valid Aura"
        const val INVALID_AURA = "Ardenn Invalid Aura"
        const val VALID_EQUIPMENT = "Ardenn Valid Equipment"
        const val OPPONENT_EQUIPMENT = "Ardenn Opponent Equipment"
    }

    private val characterizedArdenn = card(ARDENN) {
        manaCost = "{2}{W}"
        colorIdentity = "W"
        typeLine = "Legendary Creature — Kor Scout"
        oracleText = "At the beginning of combat on your turn, you may attach any number of Auras " +
            "and Equipment you control to target permanent or player.\n" +
            "Partner (You can have two commanders if both have partner.)"
        power = 2
        toughness = 2

        triggeredAbility {
            trigger = Triggers.BeginCombat
            optional = true
            target = Targets.PermanentOrPlayer
            effect = Effects.Pipeline {
                val candidates = gather(
                    source = CardSource.ControlledPermanents(
                        player = Player.You,
                        filter = GameObjectFilter.Enchantment.withSubtype("Aura") or
                            GameObjectFilter.Artifact.withSubtype("Equipment"),
                    ),
                    name = "ardenn_candidates",
                )
                chooseAnyNumber(
                    from = candidates,
                    prompt = "Choose any number of Auras and Equipment to attach",
                    alwaysPrompt = true,
                    name = "ardenn_selected",
                )
            }
        }
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

    private val validEquipment = card(VALID_EQUIPMENT) {
        manaCost = "{2}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {1}"
    }

    private val opponentEquipment = card(OPPONENT_EQUIPMENT) {
        manaCost = "{2}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {1}"
    }

    private val artifactHost = card(ARTIFACT_HOST) {
        manaCost = "{2}"
        typeLine = "Artifact"
        oracleText = ""
    }

    private val ownHost = card(OWN_HOST) {
        manaCost = "{1}{G}"
        typeLine = "Creature — Bear"
        oracleText = ""
        power = 2
        toughness = 2
    }

    private val opponentTarget = card(OPPONENT_TARGET) {
        manaCost = "{1}{R}"
        typeLine = "Creature — Goblin"
        oracleText = ""
        power = 2
        toughness = 2
    }

    init {
        cardRegistry.register(characterizedArdenn)
        cardRegistry.register(validAura)
        cardRegistry.register(invalidAura)
        cardRegistry.register(validEquipment)
        cardRegistry.register(opponentEquipment)
        cardRegistry.register(artifactHost)
        cardRegistry.register(ownHost)
        cardRegistry.register(opponentTarget)
    }

    private fun scenarioWithAttachments(includeInvalidAura: Boolean = true): TestGame {
        var builder = scenario()
            .withPlayers()
            .withCardOnBattlefield(1, ARDENN)
            .withCardOnBattlefield(1, OWN_HOST)
            .withCardAttachedTo(1, VALID_AURA, OWN_HOST)
            .withCardAttachedTo(1, VALID_EQUIPMENT, OWN_HOST)
            .withCardOnBattlefield(2, OPPONENT_TARGET)
            .withCardAttachedTo(2, OPPONENT_EQUIPMENT, OPPONENT_TARGET)
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)

        if (includeInvalidAura) {
            builder = builder
                .withCardOnBattlefield(1, ARTIFACT_HOST)
                .withCardAttachedTo(1, INVALID_AURA, ARTIFACT_HOST)
        }

        return builder.build()
    }

    /** Pass priority only; never auto-answer the consent, target, or collection decision. */
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

    init {
        test("offers only controller-owned and legally attachable candidates, never collection order") {
            val game = scenarioWithAttachments()
            val target = game.permanent(OPPONENT_TARGET)

            val may = game.beginArdenn()
            may.playerId shouldBe game.player1Id
            game.answerYesNo(true).error shouldBe null
            game.chooseArdennTarget(target)
            game.selectTargets(listOf(target)).error shouldBe null
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            val optionNames = selection.options.mapNotNull { game.cardNameOf(it) }.toSet()

            withClue("the explicit domain contains the controller's valid Aura and Equipment") {
                optionNames shouldBe setOf(VALID_AURA, VALID_EQUIPMENT)
            }
            withClue("an Aura with no legal enchant target is not offered") {
                selection.options shouldNotContain game.permanent(INVALID_AURA)
            }
            withClue("an opponent-controlled Equipment is not offered") {
                selection.options shouldNotContain game.permanent(OPPONENT_EQUIPMENT)
            }
        }

        test("moves exactly the explicitly selected Aura/Equipment to an opponent permanent") {
            val game = scenarioWithAttachments(includeInvalidAura = false)
            val target = game.permanent(OPPONENT_TARGET)
            val aura = game.permanent(VALID_AURA)
            val equipment = game.permanent(VALID_EQUIPMENT)

            val may = game.beginArdenn()
            may.playerId shouldBe game.player1Id
            game.answerYesNo(true).error shouldBe null
            game.chooseArdennTarget(target)
            game.selectTargets(listOf(target)).error shouldBe null
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain aura
            selection.options shouldContain equipment

            // Selecting Equipment explicitly proves that no implicit first()/collection-order
            // choice may move the Aura as a side effect.
            game.selectCards(listOf(equipment)).error shouldBe null
            game.resolveStack()

            withClue("the selected Equipment attaches to the targeted opponent permanent") {
                game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe target
                game.state.getEntity(target)?.get<AttachmentsComponent>()?.attachedIds
                    .orEmpty() shouldContain equipment
            }
            withClue("the unselected Aura remains on its original host") {
                game.state.getEntity(aura)?.get<AttachedToComponent>()?.targetId shouldBe
                    game.permanent(OWN_HOST)
            }
        }

        test("accepts a player target and filters attachments by target legality") {
            val game = scenarioWithAttachments(includeInvalidAura = false)

            val may = game.beginArdenn()
            may.playerId shouldBe game.player1Id
            game.answerYesNo(true).error shouldBe null
            game.chooseArdennTarget(game.player2Id)
            game.selectTargets(listOf(game.player2Id)).error shouldBe null
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldBe emptyList()
        }

        test("declining the optional trigger leaves attachments unchanged") {
            val game = scenarioWithAttachments(includeInvalidAura = false)
            val aura = game.permanent(VALID_AURA)
            val equipment = game.permanent(VALID_EQUIPMENT)
            val ownHost = game.permanent(OWN_HOST)

            val may = game.beginArdenn()
            may.playerId shouldBe game.player1Id
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.state.getEntity(aura)?.get<AttachedToComponent>()?.targetId shouldBe ownHost
            game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe ownHost
            game.hasPendingDecision() shouldBe false
        }
    }
}
