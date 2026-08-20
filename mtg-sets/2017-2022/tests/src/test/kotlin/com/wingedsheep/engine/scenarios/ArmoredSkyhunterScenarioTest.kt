package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Focused behavioral coverage for the production Armored Skyhunter definition (CMR #11).
 *
 * The tests cover the private top-six look, the Aura/Equipment selection domain, the two
 * resolution-time may decisions, Aura attachment, and the ruling that an Aura with no legal
 * enchant target cannot be selected.
 */
class ArmoredSkyhunterScenarioTest : ScenarioTestBase() {

    private companion object {
        const val SKYHUNTER = "Armored Skyhunter"
        const val HOST = "Grizzly Bears"
        const val SECOND_HOST = "Hill Giant"
        const val EQUIPMENT = "Bonesplitter"
        const val SECOND_EQUIPMENT = "Basilisk Collar"
        const val AURA = "Pacifism"
        const val IMPOSSIBLE_AURA = "Test Aura for Planeswalkers"
    }

    private val impossibleAura = card(IMPOSSIBLE_AURA) {
        manaCost = "{1}{W}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant planeswalker"
        auraTarget = Targets.Planeswalker
    }

    init {
        cardRegistry.register(impossibleAura)

        test("has flying and privately exposes exactly the top six with only Aura or Equipment selectable") {
            val topCards = listOf(EQUIPMENT, AURA, "Plains", "Forest", "Grizzly Bears", "Lightning Bolt")
            val game = scenarioWithTopCards(topCards)
            val skyhunter = game.skyhunterId()
            val libraryIds = game.libraryIds()

            game.state.projectedState.hasKeyword(skyhunter, Keyword.FLYING) shouldBe true

            val decision = game.attackAndResolveUntilSelection()
            withClue("only Aura and Equipment cards are selectable") {
                decision.options.mapNotNull { cardNameOf(game, it) }.toSet() shouldBe setOf(EQUIPMENT, AURA)
            }
            withClue("the other four cards remain visible but not selectable") {
                decision.nonSelectableOptions.mapNotNull { cardNameOf(game, it) }.toSet() shouldBe
                    setOf("Plains", "Forest", "Grizzly Bears", "Lightning Bolt")
            }
            withClue("the look is private to the controller") {
                libraryIds.forEach { cardId ->
                    game.state.getEntity(cardId)?.get<RevealedToComponent>()?.isRevealedTo(game.player1Id) shouldBe true
                    game.state.getEntity(cardId)?.get<RevealedToComponent>()?.isRevealedTo(game.player2Id) shouldBe false
                }
            }
        }

        test("puts a chosen Equipment onto the battlefield, then offers an explicit may and host choice") {
            val topCards = listOf(EQUIPMENT, SECOND_EQUIPMENT, "Plains", "Forest", "Grizzly Bears", "Lightning Bolt")
            val game = scenarioWithTopCards(topCards, extraHosts = true)
            val equipment = game.libraryIds().first()
            val host = game.findPermanent(HOST)!!

            val selection = game.attackAndResolveUntilSelection()
            selection.options shouldContain equipment
            game.selectCards(listOf(equipment)).error shouldBe null
            game.resolveStack()

            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(true).error shouldBe null

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.values.flatten() shouldContain host
            game.selectTargets(listOf(host)).error shouldBe null
            game.resolveStack()

            withClue("the chosen Equipment is on the battlefield") {
                game.findPermanent(EQUIPMENT) shouldBe equipment
            }
            withClue("the Equipment is attached only after the explicit may and host choices") {
                game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe host
                game.state.getEntity(host)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain equipment
            }
            withClue("the unchosen Equipment and four noneligible cards are bottomed") {
                game.librarySize(1) shouldBe 5
                game.isOnBattlefield(SECOND_EQUIPMENT) shouldBe false
            }
        }

        test("declining the Equipment attachment leaves it on the battlefield unattached") {
            val topCards = listOf(EQUIPMENT, SECOND_EQUIPMENT, "Plains", "Forest", "Grizzly Bears", "Lightning Bolt")
            val game = scenarioWithTopCards(topCards)
            val equipment = game.libraryIds().first()

            game.attackAndResolveUntilSelection()
            game.selectCards(listOf(equipment)).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(false).error shouldBe null
            game.resolveStack()

            game.findPermanent(EQUIPMENT) shouldBe equipment
            game.state.getEntity(equipment)?.get<AttachedToComponent>() shouldBe null
        }

        test("puts a chosen Aura onto the battlefield attached to a creature chosen at resolution") {
            val topCards = listOf(AURA, "Plains", "Forest", "Grizzly Bears", "Lightning Bolt", "Island")
            val game = scenarioWithTopCards(topCards)
            val aura = game.libraryIds().first()
            val host = game.findPermanent(HOST)!!

            val selection = game.attackAndResolveUntilSelection()
            selection.options shouldContain aura
            game.selectCards(listOf(aura)).error shouldBe null
            game.resolveStack()

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.values.flatten() shouldContain host
            game.selectTargets(listOf(host)).error shouldBe null
            game.resolveStack()

            game.findPermanent(AURA) shouldBe aura
            game.state.getEntity(aura)?.get<AttachedToComponent>()?.targetId shouldBe host
            game.state.getEntity(host)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain aura
        }

        test("does not offer an Aura that has no legal enchant target") {
            val topCards = listOf(IMPOSSIBLE_AURA, "Plains", "Forest", "Grizzly Bears", "Lightning Bolt", "Island")
            val game = scenarioWithTopCards(topCards)
            val aura = game.libraryIds().first()

            val selection = game.attackAndResolveUntilSelection()

            withClue("an Aura with no legal host cannot be chosen") {
                selection.options shouldNotContain aura
            }
            withClue("the impossible Aura is still part of the private looked-at set") {
                selection.nonSelectableOptions shouldContain aura
            }
        }
    }

    private fun scenarioWithTopCards(
        topCards: List<String>,
        extraHosts: Boolean = false,
    ): TestGame {
        var builder = scenario()
            .withPlayers()
            .withCardOnBattlefield(1, SKYHUNTER)
            .withCardOnBattlefield(1, HOST)

        if (extraHosts) {
            builder = builder.withCardOnBattlefield(1, SECOND_HOST)
        }

        topCards.forEach { cardName ->
            builder = builder.withCardInLibrary(1, cardName)
        }

        return builder
            .withActivePlayer(1)
            .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            .build()
    }

    private fun TestGame.skyhunterId() = findPermanent(SKYHUNTER)!!

    private fun cardNameOf(game: TestGame, entityId: com.wingedsheep.sdk.model.EntityId): String? =
        game.state.getEntity(entityId)?.get<CardComponent>()?.name

    private fun TestGame.attackAndResolveUntilSelection(): SelectCardsDecision {
        declareAttackers(mapOf(SKYHUNTER to 2)).error shouldBe null
        resolveStack()
        return getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
    }

    private fun TestGame.libraryIds(): List<com.wingedsheep.sdk.model.EntityId> =
        state.getZone(ZoneKey(player1Id, Zone.LIBRARY))
}
