package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.supportsPaymentPlanV1
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Focused coverage for action-authoritative PaymentDomainV1 inputs. */
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

    val equipmentWithTarget = card("Gym Target Dependent Equipment") {
        typeLine = "Artifact — Equipment"
        equipAbility("{1}")
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

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(sourceWithTapPayment)
        register(sourceWithTrackedMana)
        register(equipmentWithTarget)
        register(staticManaGrant)
    }

    fun prepared(cardName: String): Triple<GameEnvironment, EntityId, EntityId> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(cardName to 1, "Mountain" to 8)),
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
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, sourceId)
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

    test("PaymentDomainV1 excludes the ability source when the action also pays its tap cost") {
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

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.sourceActivations.any { it.sourceId == sourceId } shouldBe false
    }

    test("target-dependent equip payment does not publish an optimistic domain") {
        val (environment, player, sourceId) = prepared(equipmentWithTarget.name)
        val action = ActivateAbility(
            playerId = player,
            sourceId = sourceId,
            abilityId = equipmentWithTarget.activatedAbilities.single().id,
        )
        val legalAction = LegalAction(
            action = action,
            actionType = "ActivateAbility",
            description = "Equip the test equipment",
            requiresTargets = true,
            validTargets = listOf(EntityId("target-creature")),
            manaCostString = "{1}",
        )

        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        view.paymentDomain shouldBe null
    }

    test("PaymentDomainV1 is fail-closed when floating mana has hidden provenance") {
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

    test("PaymentDomainV1 is fail-closed for mana abilities with activation tracking") {
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

    test("intrinsic and statically granted mana on one land never publishes only the granted ability") {
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

        view.paymentDomain shouldBe null
    }
})
