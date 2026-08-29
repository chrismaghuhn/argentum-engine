package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.engine.mechanics.mana.CostCalculator
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.mechanics.mana.supportsPaymentPlanV1
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.CertifiedFloatingManaSourceBucketDomainV2
import com.wingedsheep.gym.contract.CertifiedFloatingManaSourceColorBucketDomainV3
import com.wingedsheep.gym.contract.CertifiedFloatingManaBucketDomainV4
import com.wingedsheep.mtg.sets.definitions.gtc.cards.BorosCharm
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.CostModification
import com.wingedsheep.sdk.scripting.CostReductionSource
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.ModifySpellCost
import com.wingedsheep.sdk.scripting.ReduceEquipCost
import com.wingedsheep.sdk.scripting.SpellCostTarget
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.effects.Mode
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

/** Focused coverage for action-authoritative historical V4 and current V5 payment inputs. */
class GameGymEnvPaymentDomainAuthorityTest : FunSpec({

    val sourceWithTapPayment = card("Gym Action Payment Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice()
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Composite(Costs.Tap, Costs.Mana("{1}{R}"))
            effect = Effects.GainLife(1)
        }
    }

    val sourceWithTrackedMana = card("Gym Tracking Restricted Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice()
            manaAbility = true
            restrictions = listOf(ActivationRestriction.OncePerTurn)
        }
        activatedAbility {
            cost = Costs.Mana("{G}")
            effect = Effects.GainLife(1)
        }
    }

    val fixedCostEquipment = card("Gym Fixed Cost Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{1}")
    }

    val targetPowerEquipment = card("Gym Target Power Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{3}", genericCostReduction = DynamicAmounts.targetPower())
    }

    val targetRestrictedEquipGrant = card("Gym Target Restricted Equip Grant") {
        typeLine = "Creature — Human"
        power = 2
        toughness = 2
        staticAbility {
            ability = ReduceEquipCost(amount = 1, onlyIfTargetIsSource = true)
        }
    }

    val targetRestrictedEquipment = card("Gym Target Restricted Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{2}")
    }

    val grantedAnyColorManaAbility = ActivatedAbility(
        id = AbilityId("test-granted-any-color-mana"),
        cost = Costs.Tap,
        effect = Effects.AddManaOfChoice(),
        isManaAbility = true,
        timing = TimingRule.ManaAbility,
    )

    val staticManaGrant = card("Gym Static Mana Grant") {
        typeLine = "Enchantment"
        staticAbility {
            ability = GrantActivatedAbility(
                ability = grantedAnyColorManaAbility,
                filter = GroupFilter(GameObjectFilter.Land.youControl()),
            )
        }
    }

    val unsupportedXSpell = card("Gym Unsupported X Spell") {
        manaCost = "{X}{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val ordinarySpell = card("Gym Ordinary Fixed Spell") {
        manaCost = "{1}{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val ordinaryTargetedSpell = card("Gym Ordinary Targeted Spell") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        spell {
            target = Targets.Creature
            effect = Effects.GainLife(1)
        }
    }

    val targetDependentSpell = card("Gym Target Dependent Spell") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        staticAbility {
            ability = ModifySpellCost(
                target = SpellCostTarget.SelfCast,
                modification = CostModification.ReduceGenericBy(
                    CostReductionSource.FixedIfAnyTargetMatches(
                        amount = 1,
                        filter = GameObjectFilter.Creature.withKeyword(Keyword.FLYING),
                    ),
                ),
            )
        }
        spell {
            target = Targets.Creature
            effect = Effects.GainLife(1)
        }
    }

    val optionalTargetDependentSpell = card("Gym Optional Target Dependent Spell") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        staticAbility {
            ability = ModifySpellCost(
                target = SpellCostTarget.SelfCast,
                modification = CostModification.ReduceGenericBy(
                    CostReductionSource.FixedIfAnyTargetMatches(
                        amount = 1,
                        filter = GameObjectFilter.Creature.withKeyword(Keyword.FLYING),
                    ),
                ),
            )
        }
        spell {
            target = TargetCreature(optional = true)
            effect = Effects.GainLife(1)
        }
    }

    val targetIndependentModalSpell = card("Gym Target Independent Modal Spell") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        spell {
            modal(chooseCount = 1) {
                mode("Target creature gains 1 life") {
                    target("target creature", Targets.Creature)
                    effect = Effects.GainLife(1)
                }
                mode("Gain 1 life") {
                    effect = Effects.GainLife(1)
                }
            }
        }
    }

    val targetDependentModalSpell = card("Gym Target Dependent Modal Spell") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        staticAbility {
            ability = ModifySpellCost(
                target = SpellCostTarget.SelfCast,
                modification = CostModification.ReduceGenericBy(
                    CostReductionSource.FixedIfAnyTargetMatches(
                        amount = 1,
                        filter = GameObjectFilter.Creature.withKeyword(Keyword.FLYING),
                    ),
                ),
            )
        }
        spell {
            modal(chooseCount = 1) {
                mode("Target creature gains 1 life") {
                    target("target creature", Targets.Creature)
                    effect = Effects.GainLife(1)
                }
                mode("Gain 1 life") {
                    effect = Effects.GainLife(1)
                }
            }
        }
    }

    val targetDependentAutoModalSpell = card("Gym Target Dependent Auto Modal Spell") {
        manaCost = "{1}{U}"
        colorIdentity = "U"
        typeLine = "Instant"
        staticAbility {
            ability = ModifySpellCost(
                target = SpellCostTarget.SelfCast,
                modification = CostModification.IncreaseGenericIfAnyTargetMatches(
                    amount = 1,
                    filter = GameObjectFilter.Any,
                ),
            )
        }
        spell {
            modal(chooseCount = 1) {
                mode("Target opponent gains 1 life") {
                    target("target opponent", Targets.Opponent)
                    effect = Effects.GainLife(1)
                }
                mode("Gain 1 life") {
                    effect = Effects.GainLife(1)
                }
            }
        }
    }

    val modeExtraManaSpell = CardDefinition(
        name = "Gym Mode Extra Mana Spell",
        manaCost = com.wingedsheep.sdk.core.ManaCost.parse("{U}"),
        typeLine = TypeLine.instant(),
        oracleText = "Choose one — Pay {1}: Gain 1 life.",
        script = CardScript.spell(
            effect = ModalEffect(
                modes = listOf(
                    Mode.noTarget(Effects.GainLife(1), "Pay {1}: Gain 1 life")
                        .copy(additionalManaCost = "{1}"),
                ),
                chooseCount = 1,
                minChooseCount = 1,
            ),
        ),
    )

    val modeExtraCostSpell = CardDefinition(
        name = "Gym Mode Extra Cost Spell",
        manaCost = com.wingedsheep.sdk.core.ManaCost.parse("{U}"),
        typeLine = TypeLine.instant(),
        oracleText = "Choose one — Sacrifice a creature: Gain 1 life.",
        script = CardScript.spell(
            effect = ModalEffect(
                modes = listOf(
                    Mode.noTarget(Effects.GainLife(1), "Sacrifice a creature: Gain 1 life")
                        .copy(
                            additionalCosts = listOf(
                                Costs.additional.SacrificePermanent(
                                    filter = GameObjectFilter.Creature,
                                    count = 1,
                                ),
                            ),
                        ),
                ),
                chooseCount = 1,
                minChooseCount = 1,
            ),
        ),
    )

    val flyingTarget = card("Gym Flying Target") {
        typeLine = "Creature — Bird"
        power = 1
        toughness = 1
        keywords(Keyword.FLYING)
    }

    val groundTarget = card("Gym Ground Target") {
        typeLine = "Creature — Bear"
        power = 2
        toughness = 2
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(sourceWithTapPayment)
        register(sourceWithTrackedMana)
        register(fixedCostEquipment)
        register(targetPowerEquipment)
        register(targetRestrictedEquipGrant)
        register(targetRestrictedEquipment)
        register(staticManaGrant)
        register(unsupportedXSpell)
        register(ordinarySpell)
        register(ordinaryTargetedSpell)
        register(targetDependentSpell)
        register(optionalTargetDependentSpell)
        register(targetIndependentModalSpell)
        register(targetDependentModalSpell)
        register(targetDependentAutoModalSpell)
        register(modeExtraManaSpell)
        register(modeExtraCostSpell)
        register(flyingTarget)
        register(groundTarget)
        register(BorosCharm)
    }

    fun prepared(
        cardName: String,
        includeGroundTarget: Boolean = false,
        includeMountain: Boolean = false,
        includeFlyingTarget: Boolean = false,
        includeTargetRestrictedGrant: Boolean = false,
    ): Triple<GameEnvironment, EntityId, EntityId> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val aliceDeckCards = mutableListOf(cardName to 1, "Mountain" to 8)
        if (includeGroundTarget) aliceDeckCards += groundTarget.name to 1
        if (includeFlyingTarget) aliceDeckCards += flyingTarget.name to 1
        if (includeTargetRestrictedGrant) aliceDeckCards += targetRestrictedEquipGrant.name to 1
        val aliceDeck = Deck.of(*aliceDeckCards.toTypedArray())
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", aliceDeck),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91173L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        val sourceId = state.entities.entries.first { (id, container) ->
            id in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                container.get<CardComponent>()?.name == cardName
        }.key
        val sourceZone = state.zones.entries.first { (_, ids) -> sourceId in ids }.key
        state = state.moveToZone(sourceId, sourceZone, ZoneKey(player, Zone.BATTLEFIELD))
        fun moveNamedToBattlefield(name: String) {
            val targetId = state.entities.entries.first { (id, container) ->
                id in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val targetZone = state.zones.entries.first { (_, ids) -> targetId in ids }.key
            state = state.moveToZone(targetId, targetZone, ZoneKey(player, Zone.BATTLEFIELD))
        }
        if (includeGroundTarget) moveNamedToBattlefield(groundTarget.name)
        if (includeFlyingTarget) moveNamedToBattlefield(flyingTarget.name)
        if (includeTargetRestrictedGrant) moveNamedToBattlefield(targetRestrictedEquipGrant.name)
        if (includeMountain) {
            moveNamedToBattlefield("Mountain")
        }
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, sourceId)
    }

    fun preparedWithTwoForests(cardName: String): Triple<GameEnvironment, EntityId, List<EntityId>> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(cardName to 1, "Forest" to 2, "Mountain" to 6)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91175L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            state = state.moveToZone(id, from, ZoneKey(player, Zone.BATTLEFIELD))
            return id
        }

        moveNamed(cardName)
        val forests = listOf(moveNamed("Forest"), moveNamed("Forest"))
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, forests)
    }

    fun preparedWithForestAndStaticManaGrant(): Pair<Triple<GameEnvironment, EntityId, EntityId>, EntityId> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            sourceWithTapPayment.name to 1,
                            "Forest" to 1,
                            staticManaGrant.name to 1,
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91174L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            state = state.moveToZone(id, from, ZoneKey(player, Zone.BATTLEFIELD))
            return id
        }

        val actionSourceId = moveNamed(sourceWithTapPayment.name)
        val forestId = moveNamed("Forest")
        moveNamed(staticManaGrant.name)
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, actionSourceId) to forestId
    }

    fun preparedTargetDependentCast(): Triple<GameEnvironment, EntityId, Pair<EntityId, EntityId>> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            targetDependentSpell.name to 1,
                            ordinaryTargetedSpell.name to 1,
                            flyingTarget.name to 1,
                            groundTarget.name to 1,
                            "Island" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91175L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            val targetZone = ZoneKey(player, destination)
            if (from != targetZone) state = state.moveToZone(id, from, targetZone)
            return id
        }

        moveNamed(targetDependentSpell.name, Zone.HAND)
        moveNamed(ordinaryTargetedSpell.name, Zone.HAND)
        val flyingId = moveNamed(flyingTarget.name, Zone.BATTLEFIELD)
        val groundId = moveNamed(groundTarget.name, Zone.BATTLEFIELD)
        repeat(2) { moveNamed("Island", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, flyingId to groundId)
    }

    fun preparedTargetDependentModalCast(): Triple<GameEnvironment, EntityId, Pair<EntityId, EntityId>> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            targetDependentModalSpell.name to 1,
                            flyingTarget.name to 1,
                            groundTarget.name to 1,
                            "Island" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91177L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            val targetZone = ZoneKey(player, destination)
            if (from != targetZone) state = state.moveToZone(id, from, targetZone)
            return id
        }

        val modalId = moveNamed(targetDependentModalSpell.name, Zone.HAND)
        val flyingId = moveNamed(flyingTarget.name, Zone.BATTLEFIELD)
        val groundId = moveNamed(groundTarget.name, Zone.BATTLEFIELD)
        repeat(2) { moveNamed("Island", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, flyingId to groundId).also {
            check(modalId in environment.state.getZone(player, Zone.HAND))
        }
    }

    fun preparedOptionalTargetDependentCast(): Triple<GameEnvironment, EntityId, Pair<EntityId, EntityId>> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            optionalTargetDependentSpell.name to 1,
                            flyingTarget.name to 1,
                            groundTarget.name to 1,
                            "Island" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91181L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            val targetZone = ZoneKey(player, destination)
            if (from != targetZone) state = state.moveToZone(id, from, targetZone)
            return id
        }

        val cardId = moveNamed(optionalTargetDependentSpell.name, Zone.HAND)
        val flyingId = moveNamed(flyingTarget.name, Zone.BATTLEFIELD)
        val groundId = moveNamed(groundTarget.name, Zone.BATTLEFIELD)
        repeat(2) { moveNamed("Island", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, flyingId to groundId).also {
            check(cardId in environment.state.getZone(player, Zone.HAND))
        }
    }

    fun preparedBorosCharmCast(): Triple<GameEnvironment, EntityId, EntityId> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            BorosCharm.name to 1,
                            "Mountain" to 1,
                            "Plains" to 1,
                            "Island" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 91176L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            val targetZone = ZoneKey(player, destination)
            if (from != targetZone) state = state.moveToZone(id, from, targetZone)
            return id
        }

        val cardId = moveNamed(BorosCharm.name, Zone.HAND)
        moveNamed("Mountain", Zone.BATTLEFIELD)
        moveNamed("Plains", Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, cardId)
    }

    fun preparedModalCard(cardName: String, seed: Long): Triple<GameEnvironment, EntityId, EntityId> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            cardName to 1,
                            "Mountain" to 1,
                            "Plains" to 1,
                            "Island" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = seed,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val id = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val from = state.zones.entries.first { (_, ids) -> id in ids }.key
            val targetZone = ZoneKey(player, destination)
            if (from != targetZone) state = state.moveToZone(id, from, targetZone)
            return id
        }

        val cardId = moveNamed(cardName, Zone.HAND)
        moveNamed("Mountain", Zone.BATTLEFIELD)
        moveNamed("Plains", Zone.BATTLEFIELD)
        repeat(2) { moveNamed("Island", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, cardId)
    }

    test("historical PaymentDomainV4 excludes the ability source when the action pays its tap cost") {
        val (environment, player, sourceId) = prepared(sourceWithTapPayment.name)
        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Tap and pay for the test ability",
            manaCostString = "{1}{R}",
        )

        val domain = ObservationBuilder(cardRegistry = registry())
            .paymentDomainFor(environment.state, legalAction)

        domain shouldNotBe null
        domain!!.sourceActivations.any { it.sourceId == sourceId } shouldBe false
    }

    test("unsupported CastSpell payment shapes emit PAYMENT_DOMAIN_UNSUPPORTED") {
        val (environment, player, cardId) = prepared(unsupportedXSpell.name)
        val state = environment.state.moveToZone(
            cardId,
            ZoneKey(player, Zone.BATTLEFIELD),
            ZoneKey(player, Zone.HAND),
        )
        environment.restore(state, environment.playerIds, environment.stepCount)
        val legalAction = LegalAction(
            action = CastSpell(player, cardId),
            actionType = "CastSpell",
            description = "Cast the unsupported X spell",
            affordable = true,
            manaCostString = "{X}{B}",
            hasXCost = true,
        )

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(legalAction),
        )

        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("unaffordable fixed mana actions stay visible without a payment domain or diagnostic") {
        val (environment, player, sourceId) = prepared(
            sourceWithTapPayment.name,
            includeMountain = true,
        )
        val legalAction = LegalAction(
            action = ActivateAbility(
                playerId = player,
                sourceId = sourceId,
                abilityId = sourceWithTapPayment.activatedAbilities[1].id,
            ),
            actionType = "ActivateAbility",
            description = "Show the unaffordable fixed mana ability",
            affordable = false,
            manaCostString = "{1}{R}",
        )

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(legalAction),
        )

        result.diagnostics.any { it.code == DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED } shouldBe false
        result.observation.legalActions shouldHaveSize 1
        result.observation.legalActions.single().affordable shouldBe false
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("Boros Charm choose-one CastSpellMode publishes a fixed PaymentDomainV4") {
        val (environment, player, cardId) = preparedBorosCharmCast()
        val legalAction = environment.legalActions().first {
            it.actionType == "CastSpellMode" &&
                (it.action as? CastSpell)?.cardId == cardId
        }
        val action = legalAction.action.shouldBeInstanceOf<CastSpell>()

        action.chosenModes shouldHaveSize 1
        legalAction.manaCostString shouldBe "{R}{W}"

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldNotBe null
    }

    test("targeted fixed-cost CastSpellMode remains supported when its cost is target-independent") {
        val (environment, player, cardId) = preparedBorosCharmCast()
        val legalAction = environment.legalActions().first {
            it.actionType == "CastSpellMode" &&
                (it.action as? CastSpell)?.cardId == cardId &&
                (it.action as? CastSpell)?.chosenModes == listOf(0)
        }

        legalAction.requiresTargets shouldBe true
        legalAction.validTargets shouldNotBe null
        legalAction.manaCostString shouldBe "{R}{W}"

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.requiredCost shouldBe "{R}{W}"
    }

    test("target-dependent CastSpellMode does not publish an optimistic payment domain") {
        val (environment, player, targets) = preparedTargetDependentModalCast()
        val modalId = environment.state.getZone(player, Zone.HAND).single { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == targetDependentModalSpell.name
        }
        val action = environment.legalActions().first {
            it.actionType == "CastSpellMode" &&
                (it.action as? CastSpell)?.cardId == modalId &&
                (it.action as? CastSpell)?.chosenModes == listOf(0)
        }

        action.manaCostString shouldBe "{U}"
        action.validTargets!!.toSet() shouldBe setOf(targets.first, targets.second)

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(action),
        )

        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("no-target target-dependent CastSpellMode does not publish an optimistic payment domain") {
        val (environment, player, _) = preparedTargetDependentModalCast()
        val modalId = environment.state.getZone(player, Zone.HAND).single { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == targetDependentModalSpell.name
        }
        val legalAction = environment.legalActions().first {
            it.actionType == "CastSpellMode" &&
                (it.action as? CastSpell)?.cardId == modalId &&
                (it.action as? CastSpell)?.chosenModes == listOf(1)
        }
        val action = legalAction.action.shouldBeInstanceOf<CastSpell>()

        legalAction.actionType shouldBe "CastSpellMode"
        environment.state.getEntity(action.cardId)?.get<CardComponent>()?.name shouldBe
            targetDependentModalSpell.name
        action.chosenModes shouldBe listOf(1)
        action.targets shouldBe emptyList()
        legalAction.manaCostString shouldBe "{U}"

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(legalAction),
        )

        result.diagnostics.any { it.code == DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED } shouldBe true
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("optional zero-target CastSpell checks the actual empty-target cost branch") {
        val (environment, player, targets) = preparedOptionalTargetDependentCast()
        val cardId = environment.state.getZone(player, Zone.HAND).single { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == optionalTargetDependentSpell.name
        }
        val legalAction = environment.legalActions().first {
            it.actionType == "CastSpell" &&
                (it.action as? CastSpell)?.cardId == cardId
        }
        val action = legalAction.action.shouldBeInstanceOf<CastSpell>()

        legalAction.minTargets shouldBe 0
        legalAction.targetCount shouldBe 1
        legalAction.requiresTargets shouldBe true
        action.targets shouldBe emptyList()
        legalAction.validTargets!!.toSet() shouldBe setOf(targets.first, targets.second)
        legalAction.manaCostString shouldBe "{U}"

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(legalAction),
        )

        result.diagnostics.any { it.code == DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED } shouldBe true
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("auto-selected CastSpellMode targets participate in target-cost finality") {
        val (environment, player, cardId) = preparedModalCard(targetDependentAutoModalSpell.name, 91180L)
        val legalAction = environment.legalActions().firstOrNull {
            it.actionType == "CastSpellMode" &&
                (it.action as? CastSpell)?.cardId == cardId &&
                (it.action as? CastSpell)?.chosenModes == listOf(0)
        } ?: error("Expected auto-selected modal action: ${environment.legalActions()}")
        val action = legalAction.action.shouldBeInstanceOf<CastSpell>()

        legalAction.validTargets shouldBe null
        action.targets shouldHaveSize 1
        action.modeTargetsOrdered shouldBe listOf(action.targets)

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(legalAction),
        )

        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("mode-specific additional mana and additional costs remain fail-closed") {
        fun assertUnsupported(cardName: String, manaCost: String, seed: Long) {
            val (environment, player, cardId) = preparedModalCard(cardName, seed)
            val legalAction = LegalAction(
                action = CastSpell(player, cardId, chosenModes = listOf(0)),
                actionType = "CastSpellMode",
                description = "Cast the unsupported modal shape",
                manaCostString = manaCost,
            )

            val result = ObservationBuilder(cardRegistry = registry()).build(
                environment.state,
                player,
                listOf(legalAction),
            )

            result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
            result.observation.legalActions.single().paymentDomain shouldBe null
        }

        assertUnsupported(modeExtraManaSpell.name, "{1}{U}", 91178L)
        assertUnsupported(modeExtraCostSpell.name, "{U}", 91179L)
    }

    test("inert alternative-payment flags do not hide a fixed CastSpell cost") {
        val (environment, player, cardId) = prepared(ordinarySpell.name)
        val state = environment.state.moveToZone(
            cardId,
            ZoneKey(player, Zone.BATTLEFIELD),
            ZoneKey(player, Zone.HAND),
        )
        environment.restore(state, environment.playerIds, environment.stepCount)
        val legalAction = LegalAction(
            action = CastSpell(player, cardId),
            actionType = "CastSpell",
            description = "Cast the ordinary fixed spell",
            manaCostString = "{1}{B}",
            hasConvoke = true,
            convokeCreatures = emptyList(),
            hasDelve = true,
            delveCards = emptyList(),
            hasTapForGeneric = true,
            tapForGenericPermanents = emptyList(),
            hasHarmonize = true,
            harmonizeCreatures = emptyList(),
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.requiredCost shouldBe "{1}{B}"
    }

    test("target-dependent CastSpell cost does not publish the optimistic payment domain") {
        val (environment, player, targets) = preparedTargetDependentCast()
        val action = environment.legalActions().first {
            val cast = it.action as? CastSpell
            cast != null && cast.cardId in environment.state.getZone(player, Zone.HAND)
        }

        action.manaCostString shouldBe "{U}"
        action.validTargets!!.toSet() shouldBe setOf(targets.first, targets.second)

        val calculator = CostCalculator(registry())
        val cardDef = registry().requireCard(targetDependentSpell.name)
        calculator.calculateEffectiveCost(
            environment.state,
            cardDef,
            player,
            chosenTargets = listOf(targets.first),
        ).toString() shouldBe "{U}"
        calculator.calculateEffectiveCost(
            environment.state,
            cardDef,
            player,
            chosenTargets = listOf(targets.second),
        ).toString() shouldBe "{1}{U}"

        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(action),
        )

        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        result.observation.legalActions.single().paymentDomain shouldBe null
    }

    test("targeted CastSpell without target-dependent cost still publishes PaymentDomainV4") {
        val (environment, player, targets) = preparedTargetDependentCast()
        val action = environment.legalActions().first {
            val cast = it.action as? CastSpell
            cast != null &&
                cast.cardId in environment.state.getZone(player, Zone.HAND) &&
                environment.state.getEntity(cast.cardId)
                    ?.get<CardComponent>()
                    ?.name == ordinaryTargetedSpell.name
        }

        action.validTargets!!.toSet() shouldBe setOf(targets.first, targets.second)
        val view = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(action),
        ).observation.legalActions.single()

        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.requiredCost shouldBe "{1}{U}"
    }

    test("fixed Equip {1} publishes its target and PaymentDomainV5 contracts") {
        val (environment, player, sourceId) = prepared(
            fixedCostEquipment.name,
            includeGroundTarget = true,
            includeMountain = true,
        )
        val targetId = environment.state.getZone(player, Zone.BATTLEFIELD).first { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == groundTarget.name
        }
        val legalAction = environment.legalActions().first { candidate ->
            val activateAbility = candidate.action as? ActivateAbility
            activateAbility?.playerId == player &&
                activateAbility.sourceId == sourceId &&
                activateAbility.abilityId == fixedCostEquipment.activatedAbilities.single().id
        }
        legalAction.affordable shouldBe true

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        val targetDomain = view.targetDomain
        targetDomain shouldNotBe null
        val targetRequirement = targetDomain!!.requirements.single()
        targetRequirement.minTargets shouldBe 1
        targetRequirement.maxTargets shouldBe 1
        targetRequirement.candidates shouldBe listOf(targetId)
        view.manaCost shouldBe "{1}"
        view.affordable shouldBe true
        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.version shouldBe 5
        view.paymentDomain!!.requiredCost shouldBe "{1}"
    }

    test("target-dependent Equip cost keeps every target but publishes no payment domain") {
        val (environment, player, sourceId) = prepared(
            targetPowerEquipment.name,
            includeGroundTarget = true,
            includeFlyingTarget = true,
            includeMountain = true,
        )
        val groundId = environment.state.getZone(player, Zone.BATTLEFIELD).first { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == groundTarget.name
        }
        val flyingId = environment.state.getZone(player, Zone.BATTLEFIELD).first { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == flyingTarget.name
        }
        val action = environment.legalActions().first { candidate ->
            val activateAbility = candidate.action as? ActivateAbility
            activateAbility?.playerId == player && activateAbility.sourceId == sourceId
        }

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(action))
            .observation
            .legalActions
            .single()

        view.targetDomain!!.requirements.single().candidates.toSet() shouldBe setOf(groundId, flyingId)
        view.paymentDomain shouldBe null
    }

    test("target-restricted Equip reduction fails closed for the whole public target domain") {
        val (environment, player, sourceId) = prepared(
            targetRestrictedEquipment.name,
            includeGroundTarget = true,
            includeTargetRestrictedGrant = true,
            includeMountain = true,
        )
        val grantId = environment.state.getZone(player, Zone.BATTLEFIELD).first { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == targetRestrictedEquipGrant.name
        }
        val groundId = environment.state.getZone(player, Zone.BATTLEFIELD).first { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == groundTarget.name
        }
        val action = environment.legalActions().first { candidate ->
            val activateAbility = candidate.action as? ActivateAbility
            activateAbility?.playerId == player && activateAbility.sourceId == sourceId
        }

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(action))
            .observation
            .legalActions
            .single()

        view.targetDomain!!.requirements.single().candidates.toSet() shouldBe setOf(grantId, groundId)
        view.paymentDomain shouldBe null
    }

    test("PaymentDomainV4 is fail-closed when floating mana has hidden provenance") {
        val (environment, player, sourceId) = prepared(sourceWithTapPayment.name)
        val stateWithProvenance = environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    red = 1,
                    manaBySource = mapOf(EntityId("floating-source") to 1),
                ),
            )
        }
        environment.restore(stateWithProvenance, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Tap and pay for the test ability",
            manaCostString = "{1}{R}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldBe null
    }

    test("historical PaymentDomainV4 publishes a certified single-unit joint floating bucket") {
        val (environment, player, sourceId) = prepared(sourceWithTapPayment.name)
        val stateWithProvenance = environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    green = 1,
                    manaBySource = mapOf(sourceId to 1),
                    manaBySubtype = mapOf(Subtype.FOREST to 1),
                    manaBySourceAndColor = mapOf(
                        sourceId to mapOf(PaymentManaColor.GREEN to 1),
                    ),
                    manaByFloatingBucket = mapOf(
                        FloatingManaBucketKeyV1(
                            sourceId,
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 1,
                    ),
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                    manaProvenanceKnownTo = setOf(player),
                ),
            )
        }
        environment.restore(stateWithProvenance, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Pay with certified floating mana",
            manaCostString = "{1}{R}",
        )

        val domain = ObservationBuilder(cardRegistry = registry())
            .paymentDomainFor(environment.state, legalAction)
            ?: error("expected a certified payment domain")
        domain.version shouldBe 4
        domain.currentPool.certifiedFloatingBuckets shouldBe listOf(
            CertifiedFloatingManaBucketDomainV4(
                sourceId,
                PaymentManaColor.GREEN,
                listOf("Forest"),
                1,
            ),
        )
    }

    test("historical PaymentDomainV4 publishes every visible homogeneous joint bucket in stable order") {
        val (environment, player, forestIds) = preparedWithTwoForests(sourceWithTapPayment.name)
        val stateWithProvenance = environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    green = 2,
                    manaBySource = mapOf(forestIds[1] to 1, forestIds[0] to 1),
                    manaBySubtype = mapOf(Subtype.FOREST to 2),
                    manaBySourceAndColor = mapOf(
                        forestIds[0] to mapOf(PaymentManaColor.GREEN to 1),
                        forestIds[1] to mapOf(PaymentManaColor.GREEN to 1),
                    ),
                    manaByFloatingBucket = mapOf(
                        FloatingManaBucketKeyV1(
                            forestIds[0],
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 1,
                        FloatingManaBucketKeyV1(
                            forestIds[1],
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 1,
                    ),
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                    manaProvenanceKnownTo = setOf(player),
                ),
            )
        }
        environment.restore(stateWithProvenance, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = environment.state.getBattlefield(player).first { it !in forestIds },
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Pay with two certified floating buckets",
            manaCostString = "{1}{R}",
        )

        val candidate = ObservationBuilder(cardRegistry = registry())
            .paymentDomainFor(environment.state, legalAction)
            ?.currentPool
            ?.certifiedFloatingBuckets
            ?: error("expected the V4 certified floating domain")

        candidate.map { it.sourceId } shouldBe forestIds.sortedBy { it.value }
        candidate.map { it.poolColor } shouldBe listOf(PaymentManaColor.GREEN, PaymentManaColor.GREEN)
        candidate.map { it.sourceSubtypes } shouldBe listOf(listOf("Forest"), listOf("Forest"))
        candidate.map { it.amount } shouldBe listOf(1, 1)
    }

    test("historical PaymentDomainV4 publishes exact heterogeneous joint buckets") {
        val (environment, player, forestIds) = preparedWithTwoForests(sourceWithTapPayment.name)
        val stateWithProvenance = environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    black = 1,
                    green = 3,
                    manaBySource = mapOf(forestIds[0] to 1, forestIds[1] to 3),
                    manaBySubtype = mapOf(Subtype.FOREST to 4),
                    manaBySourceAndColor = mapOf(
                        forestIds[0] to mapOf(PaymentManaColor.BLACK to 1),
                        forestIds[1] to mapOf(PaymentManaColor.GREEN to 3),
                    ),
                    manaByFloatingBucket = mapOf(
                        FloatingManaBucketKeyV1(
                            forestIds[0],
                            PaymentManaColor.BLACK,
                            setOf(Subtype.FOREST),
                        ) to 1,
                        FloatingManaBucketKeyV1(
                            forestIds[1],
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 3,
                    ),
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                    manaProvenanceKnownTo = setOf(player),
                ),
            )
        }
        environment.restore(stateWithProvenance, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = environment.state.getBattlefield(player).first { it !in forestIds },
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Publish heterogeneous floating buckets",
            manaCostString = "{1}{R}",
        )

        val domain = ObservationBuilder(cardRegistry = registry())
            .paymentDomainFor(environment.state, legalAction)
            ?: error("expected a V4 payment domain")

        domain.version shouldBe 4
        domain.currentPool.certifiedFloatingBuckets shouldBe listOf(
            CertifiedFloatingManaBucketDomainV4(
                forestIds[0], PaymentManaColor.BLACK, listOf("Forest"), 1,
            ),
            CertifiedFloatingManaBucketDomainV4(
                forestIds[1], PaymentManaColor.GREEN, listOf("Forest"), 3,
            ),
        )
    }

    test("PaymentDomainV4 fails closed when joint snapshots are not authoritative known information") {
        val (environment, player, sourceId) = prepared(sourceWithTapPayment.name)
        val stateWithProvenance = environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    green = 1,
                    manaBySource = mapOf(sourceId to 1),
                    manaBySubtype = mapOf(Subtype.FOREST to 1),
                    manaBySourceAndColor = mapOf(
                        sourceId to mapOf(PaymentManaColor.GREEN to 1),
                    ),
                    manaByFloatingBucket = mapOf(
                        FloatingManaBucketKeyV1(
                            sourceId,
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 1,
                    ),
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                    manaProvenanceKnownTo = emptySet(),
                ),
            )
        }
        environment.restore(stateWithProvenance, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Do not publish unknown production-time subtype visibility",
            manaCostString = "{1}{R}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldBe null
    }

    test("PaymentDomainV4 does not publish a candidate for an invisible source reference") {
        val (environment, player, sourceId) = prepared(sourceWithTapPayment.name)
        val hiddenSourceId = EntityId("hidden-source-reference")
        val stateWithProvenance = environment.state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    green = 1,
                    manaBySource = mapOf(hiddenSourceId to 1),
                    manaBySubtype = mapOf(Subtype.FOREST to 1),
                ),
            )
        }
        environment.restore(stateWithProvenance, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Do not expose hidden provenance",
            manaCostString = "{1}{R}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldBe null
    }

    test("PaymentDomainV4 does not publish a face-down public source identity") {
        val (environment, player, sourceId) = prepared(sourceWithTapPayment.name)
        val opponent = environment.playerIds.single { it != player }
        val opponentMountain = environment.state.entities.entries.first { (id, container) ->
            id in environment.state.getZone(opponent, Zone.HAND) + environment.state.getZone(opponent, Zone.LIBRARY) &&
                container.get<CardComponent>()?.name == "Mountain"
        }.key
        val opponentMountainZone = environment.state.zones.entries.first { (_, ids) ->
            opponentMountain in ids
        }.key
        var state = environment.state.moveToZone(
            opponentMountain,
            opponentMountainZone,
            ZoneKey(opponent, Zone.BATTLEFIELD),
        )
        state = state.updateEntity(opponentMountain) { it.with(FaceDownComponent) }
        state = state.updateEntity(player) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            container.with(
                pool.copy(
                    green = 1,
                    manaBySource = mapOf(opponentMountain to 1),
                    manaBySubtype = mapOf(Subtype.FOREST to 1),
                ),
            )
        }
        environment.restore(state, environment.playerIds, environment.stepCount)

        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Do not expose face-down source provenance",
            manaCostString = "{1}{R}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldBe null
    }

    test("PaymentDomainV4 is fail-closed for mana abilities with activation tracking") {
        val (environment, player, sourceId) = prepared(sourceWithTrackedMana.name)
        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTrackedMana.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Pay through a tracking-restricted source",
            manaCostString = "{G}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldBe null
    }

    test("current V5 publishes intrinsic and statically granted mana on one land in Rules order") {
        val (prepared, forestId) = preparedWithForestAndStaticManaGrant()
        val environment = prepared.first
        val player = prepared.second
        val sourceId = prepared.third

        val forestActions = environment.legalActions().filter { legalAction ->
            val action = legalAction.action as? ActivateAbility
            legalAction.isManaAbility && action?.sourceId == forestId
        }
        forestActions.map { (it.action as ActivateAbility).abilityId }.toSet() shouldBe setOf(
            AbilityId.intrinsicMana('G'),
            grantedAnyColorManaAbility.id,
        )
        ManaSolver(registry())
            .findAvailableManaSources(environment.state, player)
            .single { it.entityId == forestId }
            .supportsPaymentPlanV1() shouldBe false

        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = sourceWithTapPayment.activatedAbilities[1].id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Pay through a source with an intrinsic/granted land combination",
            manaCostString = "{1}{R}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        val domain = view.paymentDomain.shouldNotBeNull()
        domain.sourceActivationOptions.count { it.sourceId == forestId } shouldBe 3
        domain.sourceActivationOptions.any {
            it.manaAbilityKey == ManaAbilityIdentity.intrinsic(Color.GREEN)
        } shouldBe true
        domain.sourceActivationOptions.any {
            it.manaAbilityKey == ManaAbilityIdentity.key(grantedAnyColorManaAbility)
        } shouldBe true
    }
})
