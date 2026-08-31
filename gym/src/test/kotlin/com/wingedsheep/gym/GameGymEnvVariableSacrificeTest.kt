package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TimingRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Actual GameGymEnv contract for normal variable-sacrifice spell casts. */
class GameGymEnvVariableSacrificeTest : FunSpec({

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun variableSacrificeSpell() = CardDefinition.instant(
        name = "Variable Spell Cost Gym Probe",
        manaCost = ManaCost.parse("{1}"),
        oracleText = "As an additional cost to cast this spell, sacrifice any number of creatures.",
        script = CardScript(
            additionalCosts = listOf(
                Costs.additional.SacrificePermanents(
                    filter = GameObjectFilter.Creature,
                    minCount = 0,
                )
            )
        )
    )

    fun variableSacrificeCreature() = CardDefinition.creature(
        name = "Variable Spell Gym Creature",
        manaCost = ManaCost.parse("{1}"),
        subtypes = emptySet(),
        power = 1,
        toughness = 1,
    )

    fun variableSacrificeManaCreature() = CardDefinition.creature(
        name = "Variable Spell Gym Mana Creature",
        manaCost = ManaCost.parse("{1}"),
        subtypes = emptySet(),
        power = 1,
        toughness = 1,
        keywords = setOf(Keyword.HASTE),
        script = CardScript(
            activatedAbilities = listOf(
                ActivatedAbility(
                    id = AbilityId.generate(),
                    cost = Costs.Tap,
                    effect = Effects.AddMana(Color.GREEN),
                    timing = TimingRule.ManaAbility,
                    isManaAbility = true,
                )
            )
        ),
    )

    fun prepare(battlefield: List<String>): Pair<GameGymEnv, LegalActionView> {
        val spell = variableSacrificeSpell()
        val creature = variableSacrificeCreature()
        val manaCreature = variableSacrificeManaCreature()
        val cardRegistry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(spell)
            register(creature)
            register(manaCreature)
        }
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            spell.name to 1,
                            "Mountain" to 8,
                            creature.name to 3,
                            manaCreature.name to 1,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                    seed = 67067L,
                )
        )
        var setupSteps = 0
        while (environment.state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().firstOrNull { it.action is PassPriority }
                ?: error("Expected a priority pass while preparing the Gym state")
            environment.step(pass.action)
            if (++setupSteps > 20) error("Could not reach PRECOMBAT_MAIN")
        }

        val playerId = environment.playerIds.first()
        val handKey = ZoneKey(playerId, Zone.HAND)
        val libraryKey = ZoneKey(playerId, Zone.LIBRARY)
        var state = environment.state
        for (id in state.getZone(handKey).toList()) {
            state = state.moveToZone(id, handKey, libraryKey)
        }

        fun moveNamed(name: String, zone: Zone) {
            val id = state.getZone(libraryKey).firstOrNull { entityId ->
                state.getEntity(entityId)?.get<CardComponent>()?.name == name
            } ?: error("Could not find '$name' in the prepared library")
            state = state.moveToZone(id, libraryKey, ZoneKey(playerId, zone))
        }

        moveNamed(spell.name, Zone.HAND)
        battlefield.forEach { moveNamed(it, Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        val observation = gym.observe().observation as TrainingObservation
        val view = observation.legalActions.single { action ->
            action.kind == "CastSpell" && action.description == "Cast ${spell.name}"
        }
        return gym to view
    }

    fun paymentPlan(view: LegalActionView) =
        view.paymentDomain
            ?.let(::paymentPlanV3FromPublic)
            ?: error("Expected a complete PaymentDomainV5 for ${view.description}")

    fun payload(view: LegalActionView, selected: List<EntityId>) = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        val plan = paymentPlan(view)
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(
                PaymentStrategy.serializer(),
                PaymentStrategy.ExplicitV3(paymentPlan = plan),
            ),
        )
        put(
            "additionalCostPayment",
            buildJsonObject {
                put(
                    "variableCostPermanents",
                    buildJsonArray { selected.forEach { add(JsonPrimitive(it.value)) } }
                )
            }
        )
    }

    test("zero-selection payment is explicit and accepted by the structured Gym action") {
        val (gym, view) = prepare(battlefield = listOf("Mountain"))

        view.validSacrificeTargets shouldBe emptyList()
        view.sacrificeMinCount shouldBe 0
        view.sacrificeMaxCount shouldBe 0
        view.requiresStructuredAction shouldBe true
        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "additionalCostPayment")
        view.requiresStructuredAction shouldBe view.requiredPayloadFields.isNotEmpty()
        view.actionSemantics shouldNotBe null

        val before = gym.environment.stepCount
        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, buildJsonObject {
                view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            })
        }
        gym.environment.stepCount shouldBe before

        gym.step(view.actionId, payload(view, emptyList()))
        gym.environment.stepCount shouldBe before + 1
    }

    test("valid N selection is published, while missing and out-of-domain payloads fail closed") {
        val (gym, view) = prepare(
            battlefield = listOf(
                "Mountain",
                "Variable Spell Gym Creature",
                "Variable Spell Gym Creature",
            )
        )
        view.validSacrificeTargets shouldHaveSize 2
        view.sacrificeMinCount shouldBe 0
        view.sacrificeMaxCount shouldBe 2
        view.requiresStructuredAction shouldBe true
        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "additionalCostPayment")
        view.requiresStructuredAction shouldBe view.requiredPayloadFields.isNotEmpty()

        val before = gym.environment.stepCount
        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, buildJsonObject {
                view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            })
        }
        gym.environment.stepCount shouldBe before

        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, payload(view, listOf(EntityId("not-a-candidate"))))
        }
        gym.environment.stepCount shouldBe before

        gym.step(view.actionId, payload(view, view.validSacrificeTargets.take(1)))
        gym.environment.stepCount shouldBe before + 1
    }

    test("a mana source may fund the cast before being sacrificed through strict Gym") {
        val (gym, view) = prepare(
            battlefield = listOf("Variable Spell Gym Mana Creature")
        )
        view.validSacrificeTargets.size shouldBe 1
        val source = view.validSacrificeTargets.single()
        paymentPlan(view).activations.single().sourceId shouldBe source
        val before = gym.environment.stepCount

        gym.step(view.actionId, payload(view, listOf(source)))

        gym.environment.stepCount shouldBe before + 1
        gym.environment.state.getZone(ZoneKey(gym.environment.playerIds.first(), Zone.GRAVEYARD))
            .contains(source) shouldBe true
    }
})
