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
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardOrder
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.ConditionalOnCollectionEffect
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectFromCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectTargetEffect
import com.wingedsheep.sdk.scripting.effects.SelectionMode
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * RED-first scenario coverage for Armored Skyhunter (CMR #11).
 *
 * Current Oracle:
 * Flying
 * Whenever this creature attacks, look at the top six cards of your library. You may put an Aura
 * or Equipment card from among them onto the battlefield. If an Equipment is put onto the
 * battlefield this way, you may attach it to a creature you control. Put the rest of those cards
 * on the bottom of your library in a random order.
 *
 * The production card is intentionally absent while this characterization is written. The
 * test-only copy below uses only the existing generic pipeline primitives. The final test pins
 * the Scryfall ruling that an Aura with no legal enchant target must not be offered as a selectable
 * card at all.
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

    private val characterizedArmoredSkyhunter = card(SKYHUNTER) {
        manaCost = "{3}{W}"
        colorIdentity = "W"
        typeLine = "Creature — Cat Knight"
        oracleText = "Flying\nWhenever this creature attacks, look at the top six cards of your library. " +
            "You may put an Aura or Equipment card from among them onto the battlefield. If an " +
            "Equipment is put onto the battlefield this way, you may attach it to a creature you " +
            "control. Put the rest of those cards on the bottom of your library in a random order."
        power = 3
        toughness = 3
        keywords(Keyword.FLYING)

        triggeredAbility {
            trigger = Triggers.Attacks
            effect = Effects.Composite(
                listOf(
                    GatherCardsEffect(
                        source = CardSource.TopOfLibrary(DynamicAmount.Fixed(6)),
                        storeAs = "skyhunter_looked",
                    ),
                    SelectFromCollectionEffect(
                        from = "skyhunter_looked",
                        selection = SelectionMode.ChooseUpTo(DynamicAmount.Fixed(1)),
                        filter = GameObjectFilter.Enchantment.withSubtype("Aura") or
                            GameObjectFilter.Artifact.withSubtype("Equipment"),
                        showAllCards = true,
                        storeSelected = "skyhunter_selected",
                        storeRemainder = "skyhunter_rest",
                        prompt = "You may put an Aura or Equipment card onto the battlefield",
                        selectedLabel = "Put onto the battlefield",
                        remainderLabel = "Put on the bottom of your library",
                    ),
                    MoveCollectionEffect(
                        from = "skyhunter_selected",
                        destination = CardDestination.ToZone(Zone.BATTLEFIELD),
                    ),
                    ConditionalOnCollectionEffect(
                        collection = "skyhunter_selected",
                        filter = GameObjectFilter.Artifact.withSubtype("Equipment"),
                        ifNotEmpty = MayEffect(
                            effect = Effects.Composite(
                                listOf(
                                    SelectTargetEffect(
                                        requirement = TargetObject(
                                            filter = TargetFilter(GameObjectFilter.Creature.youControl()),
                                        ),
                                        storeAs = "skyhunter_host",
                                    ),
                                    Effects.AttachTargetEquipmentToCreature(
                                        equipmentTarget = EffectTarget.PipelineTarget("skyhunter_selected"),
                                        creatureTarget = EffectTarget.PipelineTarget("skyhunter_host"),
                                    ),
                                ),
                            ),
                            descriptionOverride = "You may attach it to a creature you control",
                        ),
                    ),
                    MoveCollectionEffect(
                        from = "skyhunter_rest",
                        destination = CardDestination.ToZone(
                            Zone.LIBRARY,
                            placement = ZonePlacement.Bottom,
                        ),
                        order = CardOrder.Random,
                    ),
                ),
            )
        }
    }

    init {
        cardRegistry.register(impossibleAura)
        cardRegistry.register(characterizedArmoredSkyhunter)
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

    init {
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

        val selection = game.attackAndResolveUntilSelection()
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
}
