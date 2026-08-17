package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectFromCollectionContinuation
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectFromCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectionMode
import com.wingedsheep.sdk.scripting.effects.SelectionRestriction
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * RED characterization for the generic Aura host-legality seam.
 *
 * The battlefield pipeline opts into host legality explicitly: an Aura's printed enchant
 * restriction is evaluated against the current battlefield before the acting player receives the
 * selectable domain. The no-restriction probe below preserves ordinary collection selection.
 */
class AuraHostSelectionDomainTest : ScenarioTestBase() {

    private val creatureAura = card("Test Aura for Creatures") {
        manaCost = "{1}{G}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature"
        auraTarget = Targets.Creature
    }

    private val planeswalkerAura = card("Test Aura for Planeswalkers") {
        manaCost = "{1}{W}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant planeswalker"
        auraTarget = Targets.Planeswalker
    }

    private val malformedAura = card("Test Aura Without Host Requirement") {
        manaCost = "{1}{U}"
        typeLine = "Enchantment — Aura"
        oracleText = ""
    }

    private val equipment = card("Test Equipment Without Aura Requirement") {
        manaCost = "{1}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {1}"
        equipAbility("{1}")
    }

    private val battlefieldSelectionProbe = card("Battlefield Aura Selection Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Look at the top two cards of your library and put an Aura onto the battlefield."
        spell {
            effect = CompositeEffect(
                listOf(
                    GatherCardsEffect(
                        source = CardSource.TopOfLibrary(DynamicAmount.Fixed(2)),
                        storeAs = "looked"
                    ),
                    SelectFromCollectionEffect(
                        from = "looked",
                        selection = SelectionMode.ChooseUpTo(DynamicAmount.Fixed(1)),
                        filter = GameObjectFilter.Enchantment.withSubtype("Aura"),
                        showAllCards = true,
                        storeSelected = "selected",
                        storeRemainder = "remainder",
                        restrictions = listOf(SelectionRestriction.AuraMustHaveLegalHost)
                    ),
                    MoveCollectionEffect(
                        from = "selected",
                        destination = CardDestination.ToZone(Zone.BATTLEFIELD)
                    ),
                    MoveCollectionEffect(
                        from = "remainder",
                        destination = CardDestination.ToZone(
                            Zone.LIBRARY,
                            placement = ZonePlacement.Bottom
                        )
                    )
                )
            )
        }
    }

    private val unrestrictedSelectionProbe = card("Unrestricted Aura Selection Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Look at the top two cards of your library and choose an Aura card."
        spell {
            effect = CompositeEffect(
                listOf(
                    GatherCardsEffect(
                        source = CardSource.TopOfLibrary(DynamicAmount.Fixed(2)),
                        storeAs = "looked"
                    ),
                    SelectFromCollectionEffect(
                        from = "looked",
                        selection = SelectionMode.ChooseUpTo(DynamicAmount.Fixed(1)),
                        filter = GameObjectFilter.Enchantment.withSubtype("Aura"),
                        showAllCards = true,
                        storeSelected = "selected",
                        storeRemainder = "remainder"
                    )
                )
            )
        }
    }

    private val artifactSelectionProbe = card("Artifact Selection Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Look at the top two cards of your library and choose an artifact card."
        spell {
            effect = CompositeEffect(
                listOf(
                    GatherCardsEffect(
                        source = CardSource.TopOfLibrary(DynamicAmount.Fixed(2)),
                        storeAs = "looked"
                    ),
                    SelectFromCollectionEffect(
                        from = "looked",
                        selection = SelectionMode.ChooseUpTo(DynamicAmount.Fixed(1)),
                        filter = GameObjectFilter.Artifact,
                        showAllCards = true,
                        storeSelected = "selected",
                        storeRemainder = "remainder",
                        restrictions = listOf(SelectionRestriction.AuraMustHaveLegalHost)
                    )
                )
            )
        }
    }

    init {
        cardRegistry.register(creatureAura)
        cardRegistry.register(planeswalkerAura)
        cardRegistry.register(malformedAura)
        cardRegistry.register(equipment)
        cardRegistry.register(battlefieldSelectionProbe)
        cardRegistry.register(unrestrictedSelectionProbe)
        cardRegistry.register(artifactSelectionProbe)

        test("does not offer an Aura with no legal host for a battlefield move") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Planeswalkers")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()?.name ==
                    "Test Aura for Planeswalkers"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            withClue("an Aura with no legal host cannot be chosen for the battlefield move") {
                selection.options shouldNotContain auraId
            }
            withClue("the looked-at Aura remains visible as a non-selectable card") {
                selection.nonSelectableOptions shouldContain auraId
            }
        }

        test("keeps an Aura with a legal host and excludes a hostless Aura from the domain") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Creatures")
                .withCardInLibrary(1, "Test Aura for Planeswalkers")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val creatureAuraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }
            val planeswalkerAuraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Planeswalkers"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldBe listOf(creatureAuraId)
            selection.nonSelectableOptions shouldContain planeswalkerAuraId
        }

        test("does not apply the Aura restriction when a collection effect does not request it") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Unrestricted Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Planeswalkers")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Planeswalkers"
            }

            game.castSpell(1, "Unrestricted Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain auraId
        }

        test("fails closed when an Aura has no target requirement") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura Without Host Requirement")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura Without Host Requirement"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldNotContain auraId
            selection.nonSelectableOptions shouldContain auraId
        }

        test("fails closed when an Aura definition is missing") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Planeswalkers")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Planeswalkers"
            }
            val auraEntity = game.state.getEntity(auraId)!!
            val auraComponent = auraEntity.get<CardComponent>()!!
            game.state = game.state.withEntity(
                auraId,
                auraEntity.with(auraComponent.copy(cardDefinitionId = "missing-aura-definition"))
            )

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldNotContain auraId
            selection.nonSelectableOptions shouldContain auraId
        }

        test("does not apply Aura host legality to Equipment") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Artifact Selection Probe")
                .withCardInLibrary(1, "Test Equipment Without Aura Requirement")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val equipmentId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Equipment Without Aura Requirement"
            }

            game.castSpell(1, "Artifact Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain equipmentId
        }

        test("revalidates Aura host legality from the serialized continuation") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Creatures")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }
            val hostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Grizzly Bears"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain auraId

            game.state = game.state.removeFromZone(
                ZoneKey(game.player1Id, Zone.BATTLEFIELD),
                hostId
            )
            val result = game.execute(
                SubmitDecision(
                    playerId = selection.playerId,
                    response = CardsSelectedResponse(selection.id, listOf(auraId))
                )
            )

            result.error shouldNotBe null
            game.state.pendingDecision shouldBe selection
        }

        test("does not expose host candidates through the actor-only selection payload") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Planeswalkers")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Planeswalkers"
            }
            val hostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Grizzly Bears"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            val cardInfo = selection.cardInfo ?: error("hidden collection selection should carry actor card info")

            cardInfo.keys shouldContain auraId
            cardInfo.keys shouldNotContain hostId
            cardInfo.values.map { it.name } shouldNotContain "Grizzly Bears"

            val opponentView = game.getClientState(2)
            opponentView.cards.keys shouldNotContain auraId
        }

        test("serializes and replays the Aura restriction deterministically across a fork") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Planeswalkers")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            val originalContinuation = game.state.continuationStack
                .filterIsInstance<SelectFromCollectionContinuation>()
                .single()
                .shouldBeInstanceOf<SelectFromCollectionContinuation>()
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }

            val encodedContinuation = json.encodeToString(
                ContinuationFrame.serializer(),
                originalContinuation
            )
            val decodedContinuation = json.decodeFromString<ContinuationFrame>(encodedContinuation)
                .shouldBeInstanceOf<SelectFromCollectionContinuation>()
            decodedContinuation shouldBe originalContinuation
            encodedContinuation.contains("AuraMustHaveLegalHost") shouldBe true
            encodedContinuation shouldBe json.encodeToString(
                ContinuationFrame.serializer(),
                decodedContinuation
            )

            val forkedState = game.state.copy(
                continuationStack = game.state.continuationStack.map { frame ->
                    if (frame is SelectFromCollectionContinuation) decodedContinuation else frame
                }
            )
            game.state = forkedState
            val result = game.execute(
                SubmitDecision(
                    playerId = selection.playerId,
                    response = CardsSelectedResponse(selection.id, emptyList())
                )
            )

            result.error shouldBe null
            game.state.pendingDecision shouldBe null
        }
    }
}
