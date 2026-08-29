package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.mechanics.mana.PaymentManaProductionProfile
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariRotFarm
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.DampLandManaProduction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Gym-level contract coverage for the canonical fixed-output PaymentDomainV5 wire shape. */
class GameGymEnvPaymentBundleTest : FunSpec({

    val fixedCostSpell = card("Gym Fixed Bundle Payment Spell") {
        manaCost = "{1}{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val dampedBundleSpell = card("Gym Damped Bundle Payment Spell") {
        manaCost = "{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val dampedBundleLand = card("Gym Damped Bundle Land") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK).then(Effects.AddColorlessMana(1))
            manaAbility = true
        }
    }

    val dampingSphere = card("Gym Damping Sphere") {
        typeLine = "Artifact"
        staticAbility {
            ability = DampLandManaProduction
        }
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(GolgariRotFarm)
        register(fixedCostSpell)
        register(dampedBundleSpell)
        register(dampedBundleLand)
        register(dampingSphere)
    }

    fun prepared(): Triple<GameEnvironment, com.wingedsheep.sdk.model.EntityId, Pair<com.wingedsheep.sdk.model.EntityId, com.wingedsheep.sdk.model.EntityId>> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            fixedCostSpell.name to 1,
                            GolgariRotFarm.name to 1,
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 93301L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, targetZone: Zone): com.wingedsheep.sdk.model.EntityId {
            val entityId = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> entityId in ids }.key
            if (sourceZone.zoneType != targetZone) {
                state = state.moveToZone(entityId, sourceZone, ZoneKey(player, targetZone))
            }
            return entityId
        }

        val spellId = moveNamed(fixedCostSpell.name, Zone.HAND)
        val rotFarmId = moveNamed(GolgariRotFarm.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, spellId to rotFarmId)
    }

    fun preparedDampedBundle(): Triple<GameEnvironment, com.wingedsheep.sdk.model.EntityId, com.wingedsheep.sdk.model.EntityId> {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            dampedBundleSpell.name to 1,
                            dampedBundleLand.name to 1,
                            dampingSphere.name to 1,
                            "Mountain" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 93302L,
            ),
        )

        val player = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, targetZone: Zone): com.wingedsheep.sdk.model.EntityId {
            val entityId = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(player, Zone.HAND) + state.getZone(player, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> entityId in ids }.key
            if (sourceZone.zoneType != targetZone) {
                state = state.moveToZone(entityId, sourceZone, ZoneKey(player, targetZone))
            }
            return entityId
        }

        val spellId = moveNamed(dampedBundleSpell.name, Zone.HAND)
        moveNamed(dampedBundleLand.name, Zone.BATTLEFIELD)
        moveNamed(dampingSphere.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)
        return Triple(environment, player, spellId)
    }

    test("PaymentDomainV5 publishes one ordered fixed bundle for Golgari Rot Farm") {
        val (environment, player, ids) = prepared()
        val legalAction = environment.legalActions().first {
            it.actionType == "CastSpell" && (it.action as? CastSpell)?.cardId == ids.first
        }
        val view = ObservationBuilder(cardRegistry = registry())
            .build(environment.state, player, listOf(legalAction))
            .observation
            .legalActions
            .single()

        val activation = view.paymentDomain!!.sourceActivationOptions.single { it.sourceId == ids.second }
        activation.productionChoices.single().let { choice ->
            choice.producedColor shouldBe PaymentManaColor.BLACK
            choice.amount shouldBe 1
            choice.bonusChoice shouldBe null
            choice.fixedOutputs?.map { it.index } shouldBe listOf(0, 1)
            choice.fixedOutputs?.map { it.color } shouldBe listOf(
                PaymentManaColor.BLACK,
                PaymentManaColor.GREEN,
            )
            choice.fixedOutputs?.map { it.amount } shouldBe listOf(1, 1)
        }
    }

    test("DampLandManaProduction closes the V5 domain for a fixed bundle with a colorless bonus") {
        val (environment, player, spellId) = preparedDampedBundle()
        val legalAction = environment.legalActions().first {
            it.actionType == "CastSpell" && (it.action as? CastSpell)?.cardId == spellId
        }
        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state,
            player,
            listOf(legalAction),
        )

        result.observation.legalActions.single().paymentDomain shouldBe null
        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        val sourceId = environment.state.getZone(player, Zone.BATTLEFIELD).single { id ->
            environment.state.getEntity(id)?.get<CardComponent>()?.name == dampedBundleLand.name
        }
        val profile = ManaSolver(registry()).findAvailableManaSources(environment.state, player)
            .single { it.entityId == sourceId }
            .paymentManaProductionProfiles.values.single()
            .shouldBeInstanceOf<PaymentManaProductionProfile.Unsupported>()
        profile.reason shouldBe "DampLandManaProduction changes the selected source production"
    }
})
