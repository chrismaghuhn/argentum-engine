package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.sth.StrongholdSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class GameGymEnvStrictExecutionTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(StrongholdSet.cards)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 1, "Shock" to 1)),
            PlayerConfig("Bob", Deck.of("Mountain" to 1, "Shock" to 1)),
        ),
        startingHandSize = 2,
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    val wardedBear = card("Strict Warded Bear") {
        manaCost = "{R}"
        typeLine = "Creature — Bear"
        power = 2
        toughness = 2
        keywordAbility(KeywordAbility.ward("{1}"))
    }

    fun wardRegistry() = registry().apply {
        register(wardedBear)
    }

    fun wardConfig() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 14, "Strict Warded Bear" to 1)),
            PlayerConfig("Bob", Deck.of("Mountain" to 14, "Shock" to 1)),
        ),
        startingHandSize = 2,
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    fun cardName(environment: GameEnvironment, entityId: EntityId): String? =
        environment.state.getEntity(entityId)?.get<CardComponent>()?.name

    fun paymentPayload(view: LegalActionView): JsonObject {
        val domain = view.paymentDomain ?: error("Expected a PaymentDomainV5 for ${view.description}")
        val plan = paymentPlanV3FromPublic(domain)
            ?: error("Expected a complete PaymentDomainV5 plan for ${view.description}")
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
        return buildJsonObject {
            view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                json.encodeToJsonElement(PaymentStrategy.serializer(), PaymentStrategy.ExplicitV3(plan)),
            )
        }
    }

    fun targetedPaymentPayload(view: LegalActionView, targetType: String, targetId: EntityId): JsonObject =
        buildJsonObject {
            paymentPayload(view).forEach { (key, value) -> put(key, value) }
            val targetField = if (targetType == "Player") "playerId" else "entityId"
            put(
                "targets",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", targetType)
                        put(targetField, targetId.value)
                    })
                },
            )
        }

    fun moveCardsIntoHand(environment: GameEnvironment, playerId: EntityId, names: List<String>) {
        var state = environment.state
        names.groupingBy { it }.eachCount().forEach { (name, requiredCount) ->
            val inHand = state.getHand(playerId).count { cardName(environment, it) == name }
            repeat(requiredCount - inHand) {
                val cardId = state.getZone(playerId, Zone.LIBRARY).firstOrNull { id ->
                    state.getEntity(id)?.get<CardComponent>()?.name == name
                } ?: error("Expected $requiredCount copies of $name for $playerId")
                state = state.moveToZone(
                    cardId,
                    ZoneKey(playerId, Zone.LIBRARY),
                    ZoneKey(playerId, Zone.HAND),
                )
            }
        }
        environment.restore(state, environment.playerIds, environment.stepCount)
    }

    fun findAction(
        gym: GameGymEnv,
        environment: GameEnvironment,
        actor: EntityId,
        kind: String,
        predicate: (LegalActionView) -> Boolean = { true },
    ): LegalActionView {
        var observed = gym.observe()
        repeat(400) {
            if (environment.agentToAct == actor) {
                observed.observation.legalActions
                    .firstOrNull { it.kind == kind && predicate(it) }
                    ?.let { return it }
            }
            val emptyCombat = observed.observation.legalActions.firstOrNull {
                it.kind in setOf("DeclareAttackers", "DeclareBlockers", "OrderBlockers") &&
                    it.requiresStructuredAction
            }
            if (emptyCombat != null) {
                // Setup crosses real combat priority boundaries. Submit the empty declaration
                // explicitly; this helper is not part of the production Gym policy.
                gym.step(emptyCombat.actionId, emptyCombat.actionSemantics!!)
                observed = gym.observe()
                return@repeat
            }
            val pass = observed.observation.legalActions.firstOrNull { it.kind == "PassPriority" }
                ?: error(
                    "Expected a pass action while looking for $kind for $actor; " +
                        "agent=${environment.agentToAct}, pending=${environment.pendingDecision}, " +
                        "actions=${observed.observation.legalActions}"
                )
            observed = gym.step(pass.actionId)
        }
        error("Did not find $kind for $actor within 400 strict actions")
    }

    fun passCurrent(gym: GameGymEnv) {
        val pass = gym.observe().observation.legalActions.firstOrNull { it.kind == "PassPriority" }
            ?: error("Expected PassPriority, got ${gym.observe().observation.legalActions}")
        gym.step(pass.actionId)
    }

    test("a spell on the stack gives the opponent priority instead of auto-passing") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )

        gym.reset(config())
        var observed = gym.observe()
        var land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        var setupSteps = 0
        while (land == null && setupSteps++ < 20) {
            val pass = observed.observation.legalActions.first { it.kind == "PassPriority" }
            observed = gym.step(pass.actionId)
            land = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        }
        val selectedLand = land ?: error(
            "Expected a PlayLand action during setup; " +
                "agent=${environment.agentToAct}, step=${environment.state.step}, " +
                "actions=${observed.observation.legalActions}"
        )
        gym.step(selectedLand.actionId)

        val afterLand = gym.observe()
        val shock = afterLand.observation.legalActions.firstOrNull { it.kind == "CastSpell" }
            ?: error(
                "Expected a CastSpell action after playing a land; " +
                    "agent=${environment.agentToAct}, step=${environment.state.step}, " +
                    "actions=${afterLand.observation.legalActions}"
            )
        val opponent = environment.playerIds[1]
        val payload = targetedPaymentPayload(shock, "Player", opponent)

        gym.step(shock.actionId, payload)

        environment.state.stack.shouldNotBeEmpty()
        environment.agentToAct shouldBe environment.playerIds[0]

        val casterPass = gym.observe().observation.legalActions.first {
            it.kind == "PassPriority"
        }
        gym.step(casterPass.actionId)

        environment.state.stack.shouldNotBeEmpty()
        environment.agentToAct shouldBe opponent
    }

    test("legacy GameEnvironment.step keeps simulator quiet-state behavior") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(config())

        var land = environment.legalActions().firstOrNull { it.action is com.wingedsheep.engine.core.PlayLand }
        var setupSteps = 0
        while (land == null && setupSteps++ < 20) {
            val pass = environment.legalActions().first { it.action is com.wingedsheep.engine.core.PassPriority }
            environment.step(pass.action)
            land = environment.legalActions().firstOrNull {
                it.action is com.wingedsheep.engine.core.PlayLand
            }
        }
        val selectedLand = land ?: error("Expected a PlayLand action during legacy setup")
        environment.step(selectedLand.action)

        val shock = environment.legalActions().first { action ->
            action.action is CastSpell && cardName(environment, (action.action as CastSpell).cardId) == "Shock"
        }
        val submitted = (shock.action as CastSpell).copy(
            targets = listOf(ChosenTarget.Player(environment.playerIds[1]))
        )
        environment.step(submitted)

        environment.state.stack.shouldBeEmpty()
    }

    test("strict Gym execution leaves a real auto-pay mana choice pending") {
        val cardRegistry = wardRegistry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(wardConfig())
        val alice = environment.playerIds[0]
        val bob = environment.playerIds[1]
        moveCardsIntoHand(environment, alice, listOf("Mountain", "Strict Warded Bear"))
        moveCardsIntoHand(environment, bob, listOf("Mountain", "Mountain", "Shock"))

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )

        val aliceLand = findAction(gym, environment, alice, "PlayLand")
        gym.step(aliceLand.actionId)
        val bearCast = findAction(gym, environment, alice, "CastSpell") {
            it.description.contains("Strict Warded Bear")
        }
        gym.step(bearCast.actionId, paymentPayload(bearCast))

        // Resolve the creature, one explicit priority pass at a time.
        passCurrent(gym)
        passCurrent(gym)

        val bobFirstLand = findAction(gym, environment, bob, "PlayLand")
        gym.step(bobFirstLand.actionId)
        val bobSecondLand = findAction(gym, environment, bob, "PlayLand")
        gym.step(bobSecondLand.actionId)

        val bearId = environment.state.getBattlefield(alice).first { cardName(environment, it) == wardedBear.name }
        val shock = findAction(gym, environment, bob, "CastSpell") {
            it.description.contains("Shock")
        }
        val payload = targetedPaymentPayload(shock, "Permanent", bearId)
        gym.step(shock.actionId, payload)

        // The two passes resolve the Ward trigger, but Strict must expose the source menu even
        // though the solver already found a legal auto-pay suggestion.
        passCurrent(gym)
        passCurrent(gym)
        val pending = environment.pendingDecision.shouldBeInstanceOf<SelectManaSourcesDecision>()
        pending.playerId shouldBe bob
        pending.autoPaySuggestion.shouldNotBeEmpty()
    }
})
