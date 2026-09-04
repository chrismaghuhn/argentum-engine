package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReplayChosenInputBindingV1Test : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(registry: CardRegistry): GameEnvironment {
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 70L,
            ),
        )
        return environment
    }

    fun actionFixture(): Triple<CompleteLegalDomainV1, JsonObject, PassPriority> {
        val registry = registry()
        val environment = environment(registry)
        val rawAction = environment.legalActions()
            .first { it.action is PassPriority }
            .action as PassPriority
        val observation = ObservationBuilder(cardRegistry = registry).build(
            state = environment.state,
            perspectivePlayerId = rawAction.playerId,
            legalActions = environment.legalActions(),
        ).observation as TrainingObservation
        val domain = CompleteLegalDomainV1.from(observation)
        val actionIndex = observation.legalActions.indexOfFirst {
            it.actionSemantics?.get("type") == JsonPrimitive("PassPriority")
        }
        require(actionIndex >= 0)
        return Triple(domain, domain.candidates[actionIndex], rawAction)
    }

    fun responseCandidate(base: JsonObject, choice: Boolean): JsonObject = buildJsonObject {
        base.forEach { (key, value) ->
            when (key) {
                "actionSemantics" -> put("actionSemantics", buildJsonObject {
                    put("type", "YesNoResponse")
                    put("choice", choice)
                })

                "isDecisionOption" -> put(key, true)
                else -> put(key, value)
            }
        }
    }

    test("recorded actions reuse the A3 chosen-action vocabulary and contain no routing handle") {
        val (domain, candidate, action) = actionFixture()

        val chosen = ChosenSemanticActionV1.fromRecordedAction(domain, candidate, action)

        chosen.choicePayload shouldBe JsonObject(emptyMap())
        chosen.canonicalJson() shouldNotContain "actionId"
        chosen.canonicalJson() shouldNotContain "decisionId"
        chosen.canonicalJson() shouldNotContain "GameState"
    }

    test("folded response projection removes only the decision nonce") {
        val (actionDomain, base, action) = actionFixture()
        val responseDomain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            decisionKind = PendingDecisionKind.YES_NO,
            shape = DecisionShape(),
            candidates = listOf(
                responseCandidate(base, choice = true),
                responseCandidate(base, choice = false),
            ),
        )

        val first = ChosenSemanticResponseV1.from(
            responseDomain,
            YesNoResponse(decisionId = "nonce-a", choice = true),
        )
        val second = ChosenSemanticResponseV1.from(
            responseDomain,
            YesNoResponse(decisionId = "nonce-b", choice = true),
        )
        first shouldBe second

        val input = ReplayChosenInputV1(
            replayActionIndex = 0,
            perspectivePlayerId = action.playerId,
            chosenSemanticAction = ChosenSemanticActionV1.fromRecordedAction(actionDomain, base, action),
        )
        val binding = ReplayChosenInputBindingV1(
            replayContentIdentity = ReplayContentIdentityV1(
                replayVersion = 5,
                value = "a".repeat(64),
            ),
            replayActionCount = 1,
            chosenInputs = listOf(input),
        )

        val encoded = Json.encodeToString(ReplayChosenInputBindingV1.serializer(), binding)
        Json.decodeFromString(ReplayChosenInputBindingV1.serializer(), encoded) shouldBe binding
        encoded shouldNotContain "decisionId"
        encoded shouldNotContain "GameState"
    }

    test("binding versions, schemas, and coordinates fail closed") {
        val (_, candidate, action) = actionFixture()
        val domain = actionFixture().first
        val input = ReplayChosenInputV1(
            replayActionIndex = 0,
            perspectivePlayerId = EntityId(action.playerId.value),
            chosenSemanticAction = ChosenSemanticActionV1.fromRecordedAction(domain, candidate, action),
        )
        val identity = ReplayContentIdentityV1(replayVersion = 5, value = "b".repeat(64))
        val binding = ReplayChosenInputBindingV1(
            replayContentIdentity = identity,
            replayActionCount = 1,
            chosenInputs = listOf(input),
        )

        shouldThrow<IllegalArgumentException> { binding.copy(version = 2) }
        shouldThrow<IllegalArgumentException> {
            binding.copy(schemaIdentity = "future-replay-chosen-input@v2")
        }
        shouldThrow<IllegalArgumentException> {
            binding.copy(chosenInputs = listOf(input.copy(replayActionIndex = 1)))
        }
        shouldThrow<IllegalArgumentException> {
            ReplayChosenInputV1(
                replayActionIndex = 0,
                perspectivePlayerId = action.playerId,
            )
        }
        shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.fromRecordedAction(
                domain = domain,
                candidate = candidate,
                action = SubmitDecision(action.playerId, YesNoResponse("nonce", true)),
            )
        }
    }
})
