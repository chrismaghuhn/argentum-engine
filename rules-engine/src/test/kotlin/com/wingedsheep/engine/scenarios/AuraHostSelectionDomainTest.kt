package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.CreateTokenCopyAuraHostContinuation
import com.wingedsheep.engine.core.MoveCollectionAuraTargetContinuation
import com.wingedsheep.engine.core.PutOntoBattlefieldAttachedToChosenContinuation
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectFromCollectionContinuation
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.core.AbilityFlag
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantHexproofToController
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.GrantShroudToController
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ProtectionScope
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectFromCollectionEffect
import com.wingedsheep.sdk.scripting.effects.SelectionMode
import com.wingedsheep.sdk.scripting.effects.SelectionRestriction
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
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
        colorIdentity = "G"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature"
        auraTarget = Targets.Creature
    }

    private val playerAura = card("Test Aura for Players") {
        manaCost = "{1}{W}"
        colorIdentity = "W"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant player"
        auraTarget = Targets.Player
    }

    private val opponentAura = card("Test Aura for Opponents") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant opponent"
        auraTarget = Targets.Opponent
    }

    private val creatureOrPlayerAura = card("Test Aura for Creatures or Players") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature or player"
        auraTarget = Targets.CreatureOrPlayer
    }

    private val shroudedCreature = card("Test Shrouded Creature") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Creature — Illusion"
        oracleText = "Shroud"
        power = 2
        toughness = 2
        keywords(Keyword.SHROUD)
    }

    private val playerShroudSource = card("Test Player Shroud Source") {
        manaCost = "{2}"
        typeLine = "Artifact"
        oracleText = "You have shroud."
        staticAbility { ability = GrantShroudToController }
    }

    private val opponentHexproofSource = card("Test Opponent Hexproof Source") {
        manaCost = "{2}"
        typeLine = "Artifact"
        oracleText = "You have hexproof."
        staticAbility { ability = GrantHexproofToController }
    }

    private val protectedCreature = card("Test Green-Protected Creature") {
        manaCost = "{1}{W}"
        colorIdentity = "W"
        typeLine = "Creature — Human"
        oracleText = "Protection from green"
        power = 2
        toughness = 2
        keywordAbility(KeywordAbility.Protection(ProtectionScope.Color(Color.GREEN)))
    }

    private val cantBeEnchantedCreature = card("Test Creature That Cannot Be Enchanted") {
        manaCost = "{1}{W}"
        colorIdentity = "W"
        typeLine = "Creature — Human"
        oracleText = "This creature can't be enchanted."
        power = 2
        toughness = 2
        staticAbility {
            ability = GrantKeyword(AbilityFlag.CANT_BE_ENCHANTED.name, GroupFilter.source())
        }
    }

    private val ordinaryCreatureTargetingProbe = card("Ordinary Creature Targeting Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Destroy target creature."
        spell {
            val target = target("target creature", Targets.Creature)
            effect = Effects.Destroy(target)
        }
    }

    private val explicitAttachProbe = card("Explicit Aura Attach Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Return target Aura card from your graveyard to the battlefield attached to a creature you control."
        spell {
            val aura = target(
                "target Aura card",
                TargetObject(
                    filter = TargetFilter(
                        baseFilter = GameObjectFilter.Enchantment.withSubtype("Aura"),
                        zone = Zone.GRAVEYARD,
                    )
                )
            )
            effect = Effects.PutOntoBattlefieldAttachedToChosen(
                target = aura,
                hostFilter = GameObjectFilter.Creature.youControl(),
            )
        }
    }

    private val auraCopyProbe = card("Aura Copy Characteristics Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Create two token copies of target Aura, except they are black."
        spell {
            val aura = target(
                "target Aura permanent",
                TargetObject(
                    filter = TargetFilter(
                        baseFilter = GameObjectFilter.Enchantment.withSubtype("Aura")
                    )
                )
            )
            effect = Effects.CreateTokenCopyOfTarget(
                target = aura,
                count = 2,
                overrideColors = setOf(Color.BLACK),
            )
        }
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

    private val multiAuraSelectionProbe = card("Multi-Aura Selection Probe") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Choose up to two Aura cards from the top of your library and put them onto the battlefield."
        spell {
            effect = CompositeEffect(
                listOf(
                    GatherCardsEffect(
                        source = CardSource.TopOfLibrary(DynamicAmount.Fixed(2)),
                        storeAs = "looked"
                    ),
                    SelectFromCollectionEffect(
                        from = "looked",
                        selection = SelectionMode.ChooseUpTo(DynamicAmount.Fixed(2)),
                        filter = GameObjectFilter.Enchantment.withSubtype("Aura"),
                        showAllCards = true,
                        storeSelected = "selected",
                        storeRemainder = "remainder"
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
        cardRegistry.register(playerAura)
        cardRegistry.register(opponentAura)
        cardRegistry.register(creatureOrPlayerAura)
        cardRegistry.register(shroudedCreature)
        cardRegistry.register(playerShroudSource)
        cardRegistry.register(opponentHexproofSource)
        cardRegistry.register(protectedCreature)
        cardRegistry.register(cantBeEnchantedCreature)
        cardRegistry.register(ordinaryCreatureTargetingProbe)
        cardRegistry.register(explicitAttachProbe)
        cardRegistry.register(auraCopyProbe)
        cardRegistry.register(planeswalkerAura)
        cardRegistry.register(malformedAura)
        cardRegistry.register(equipment)
        cardRegistry.register(battlefieldSelectionProbe)
        cardRegistry.register(unrestrictedSelectionProbe)
        cardRegistry.register(multiAuraSelectionProbe)
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

        test("ignores creature shroud at both the collection and attachment-host decisions") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Shrouded Creature")
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
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Shrouded Creature"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain auraId
            game.selectCards(listOf(auraId)).error shouldBe null

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.getValue(0) shouldContain hostId
            game.selectTargets(listOf(hostId)).error shouldBe null
        }

        test("ignores player shroud for a TargetPlayer Aura host") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Player Shroud Source")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Players")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Players"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain auraId
            game.selectCards(listOf(auraId)).error shouldBe null

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.getValue(0) shouldContain game.player1Id
            game.selectTargets(listOf(game.player1Id)).error shouldBe null
        }

        test("ignores opponent hexproof for a TargetOpponent Aura host") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(2, "Test Opponent Hexproof Source")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Opponents")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Opponents"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain auraId
            game.selectCards(listOf(auraId)).error shouldBe null

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.getValue(0) shouldContain game.player2Id
            game.selectTargets(listOf(game.player2Id)).error shouldBe null
        }

        test("ignores shroud for the specialized creature-or-player union requirement") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Player Shroud Source")
                .withCardOnBattlefield(2, "Test Player Shroud Source")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Creatures or Players")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures or Players"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain auraId
            game.selectCards(listOf(auraId)).error shouldBe null

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.getValue(0) shouldContain game.player1Id
            game.selectTargets(listOf(game.player1Id)).error shouldBe null
        }

        test("keeps protection from the Aura's color as an attachment restriction") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Green-Protected Creature")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Creatures")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldNotContain auraId
            selection.nonSelectableOptions shouldContain auraId
        }

        test("keeps a projected can't-be-enchanted restriction at attachment time") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Creature That Cannot Be Enchanted")
                .withCardInHand(1, "Battlefield Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Creatures")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }

            game.castSpell(1, "Battlefield Aura Selection Probe")
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldNotContain auraId
            selection.nonSelectableOptions shouldContain auraId
        }

        test("does not bypass shroud for ordinary spell targeting") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Shrouded Creature")
                .withCardInHand(1, "Ordinary Creature Targeting Probe")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val hostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Shrouded Creature"
            }

            val result = game.castSpell(1, "Ordinary Creature Targeting Probe", hostId)
            result.error shouldNotBe null
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

        test("revalidates Aura host legality when the host response is submitted") {
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
            game.selectCards(listOf(auraId)).error shouldBe null
            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.legalTargets.getValue(0) shouldContain hostId

            val originalContinuation = game.state.continuationStack
                .filterIsInstance<MoveCollectionAuraTargetContinuation>()
                .single()
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }
            val encodedContinuation = json.encodeToString(
                ContinuationFrame.serializer(),
                originalContinuation
            )
            val decodedContinuation = json.decodeFromString<ContinuationFrame>(encodedContinuation)
                .shouldBeInstanceOf<MoveCollectionAuraTargetContinuation>()
            decodedContinuation shouldBe originalContinuation
            game.state = game.state.copy(
                continuationStack = game.state.continuationStack.map { frame ->
                    if (frame is MoveCollectionAuraTargetContinuation) decodedContinuation else frame
                }
            )

            game.state = game.state.removeFromZone(
                ZoneKey(game.player1Id, Zone.BATTLEFIELD),
                hostId
            )
            val result = game.execute(
                SubmitDecision(
                    playerId = hostDecision.playerId,
                    response = TargetsResponse(hostDecision.id, mapOf(0 to listOf(hostId)))
                )
            )

            result.error shouldNotBe null
            game.state.pendingDecision shouldBe hostDecision
            game.state.getBattlefield(game.player1Id) shouldNotContain auraId
        }

        test("revalidates an explicit attachment effect's host filter on resume") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInGraveyard(1, "Test Aura for Creatures")
                .withCardInHand(1, "Explicit Aura Attach Probe")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getGraveyard(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }
            val probeId = game.state.getHand(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Explicit Aura Attach Probe"
            }
            val hostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Grizzly Bears"
            }

            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = probeId,
                    targets = listOf(ChosenTarget.Card(auraId, game.player1Id, Zone.GRAVEYARD)),
                )
            ).error shouldBe null
            game.resolveStack()
            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            val originalContinuation = game.state.continuationStack
                .filterIsInstance<PutOntoBattlefieldAttachedToChosenContinuation>()
                .single()
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }
            val encodedContinuation = json.encodeToString(
                ContinuationFrame.serializer(),
                originalContinuation,
            )
            val decodedContinuation = json.decodeFromString<ContinuationFrame>(encodedContinuation)
                .shouldBeInstanceOf<PutOntoBattlefieldAttachedToChosenContinuation>()
            decodedContinuation shouldBe originalContinuation
            game.state = game.state.copy(
                continuationStack = game.state.continuationStack.map { frame ->
                    if (frame is PutOntoBattlefieldAttachedToChosenContinuation) decodedContinuation else frame
                }
            )

            // The stale response still names a battlefield permanent, but it no longer satisfies
            // the effect's original "creature you control" host domain.
            game.state = game.state.updateEntity(hostId) {
                it.with(ControllerComponent(game.player2Id))
            }
            val result = game.execute(
                SubmitDecision(
                    playerId = hostDecision.playerId,
                    response = TargetsResponse(hostDecision.id, mapOf(0 to listOf(hostId))),
                )
            )

            result.error shouldNotBe null
            game.state.pendingDecision shouldBe hostDecision
            game.state.getBattlefield(game.player1Id) shouldNotContain auraId
        }

        test("uses effective copy colors when deriving an Aura token host domain") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Aura for Creatures")
                .withCardOnBattlefield(1, "Test Green-Protected Creature")
                .withCardInHand(1, "Aura Copy Characteristics Probe")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }
            val protectedHostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Green-Protected Creature"
            }

            game.castSpell(1, "Aura Copy Characteristics Probe", auraId).error shouldBe null
            game.resolveStack()

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            withClue("the black copy is not prevented by protection from green") {
                hostDecision.legalTargets.getValue(0) shouldContain protectedHostId
            }
        }

        test("rejects a stale Aura token host response without consuming the continuation") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Test Aura for Creatures")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Aura Copy Characteristics Probe")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val auraId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }
            val hostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Grizzly Bears"
            }

            game.castSpell(1, "Aura Copy Characteristics Probe", auraId).error shouldBe null
            game.resolveStack()
            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            val continuation = game.state.continuationStack
                .filterIsInstance<CreateTokenCopyAuraHostContinuation>()
                .single()
            continuation.remaining shouldBe 2
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }
            val encodedContinuation = json.encodeToString(
                ContinuationFrame.serializer(),
                continuation,
            )
            val decodedContinuation = json.decodeFromString<ContinuationFrame>(encodedContinuation)
                .shouldBeInstanceOf<CreateTokenCopyAuraHostContinuation>()
            decodedContinuation shouldBe continuation
            game.state = game.state.copy(
                continuationStack = game.state.continuationStack.map { frame ->
                    if (frame is CreateTokenCopyAuraHostContinuation) decodedContinuation else frame
                }
            )

            game.state = game.state.removeFromZone(
                ZoneKey(game.player1Id, Zone.BATTLEFIELD),
                hostId,
            )
            val result = game.execute(
                SubmitDecision(
                    playerId = hostDecision.playerId,
                    response = TargetsResponse(hostDecision.id, mapOf(0 to listOf(hostId))),
                )
            )

            result.error shouldNotBe null
            game.state.pendingDecision shouldBe hostDecision
            game.state.continuationStack shouldContain continuation
            game.state.getBattlefield(game.player1Id) shouldNotContain hostId
        }

        test("does not reuse a prior host response for a malformed remaining Aura") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, "Multi-Aura Selection Probe")
                .withCardInLibrary(1, "Test Aura for Creatures")
                .withCardInLibrary(1, "Test Aura Without Host Requirement")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val creatureAuraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura for Creatures"
            }
            val malformedAuraId = game.state.getZone(game.player1Id, Zone.LIBRARY).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Test Aura Without Host Requirement"
            }
            val hostId = game.state.getBattlefield(game.player1Id).single { entityId ->
                game.state.getEntity(entityId)?.get<CardComponent>()?.name == "Grizzly Bears"
            }

            game.castSpell(1, "Multi-Aura Selection Probe")
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldContain creatureAuraId
            selection.options shouldContain malformedAuraId
            game.selectCards(listOf(creatureAuraId, malformedAuraId)).error shouldBe null

            val hostDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseTargetsDecision>()
            hostDecision.context.sourceId shouldBe creatureAuraId
            game.selectTargets(listOf(hostId)).error shouldBe null

            game.state.pendingDecision shouldBe null
            game.state.getBattlefield(game.player1Id) shouldContain creatureAuraId
            game.state.getBattlefield(game.player1Id) shouldNotContain malformedAuraId
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
