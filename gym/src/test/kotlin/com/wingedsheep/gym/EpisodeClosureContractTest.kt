package com.wingedsheep.gym

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.LossReason
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.AnyTarget
import com.wingedsheep.sdk.scripting.targets.TargetChooser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

class EpisodeClosureContractTest : FunSpec({

    val unsupportedTargetCard = card("A0 Unsupported Opponent Target") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            target = AnyTarget(chooser = TargetChooser.Opponent)
            effect = Effects.GainLife(1)
        }
    }

    fun registry(includeUnsupportedTarget: Boolean = false): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        if (includeUnsupportedTarget) register(unsupportedTargetCard)
    }

    fun config(startingPlayerIndex: Int = 0): GameConfig = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 20)),
            PlayerConfig("Bob", Deck.of("Mountain" to 20)),
        ),
        startingHandSize = 2,
        skipMulligans = true,
        startingPlayerIndex = startingPlayerIndex,
    )

    fun moveCardToBattlefield(
        environment: GameEnvironment,
        playerId: EntityId,
        cardName: String,
    ) {
        val cardId = (environment.state.getHand(playerId) + environment.state.getLibrary(playerId))
            .first { id -> environment.state.getEntity(id)?.get<CardComponent>()?.name == cardName }
        val from = environment.state.zones.entries.first { (_, ids) -> cardId in ids }.key
        environment.restore(
            state = environment.state.moveToZone(
                cardId,
                from,
                ZoneKey(playerId, Zone.BATTLEFIELD),
            ),
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
        )
    }

    fun unsupportedDecision(playerId: EntityId) = AssignDamageDecision(
        id = "a0-unsupported-damage",
        playerId = playerId,
        prompt = "Assign damage",
        context = DecisionContext(),
        attackerId = EntityId("attacker"),
        availablePower = 1,
        orderedTargets = listOf(EntityId("target")),
        defenderId = null,
        minimumAssignments = mapOf(EntityId("target") to 1),
        defaultAssignments = mapOf(EntityId("target") to 1),
        hasTrample = false,
        hasDeathtouch = false,
    )

    test("typed closure is versioned, serializable, and does not carry exception text") {
        EpisodeClosureV1.SCHEMA_VERSION shouldBe 1
        val closure = EpisodeClosureV1.Failed(
            stepCount = 4,
            reason = EpisodeFailureReason.OBSERVATION_FAILURE,
        )
        closure.kind shouldBe EpisodeClosureV1.Kind.FAILED
        closure.stepCount shouldBe 4

        val json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = false
        }
        val encoded = json.encodeToString(EpisodeClosureV1.serializer(), closure)
        encoded.contains("exception", ignoreCase = true).shouldBeFalse()
        json.decodeFromString(EpisodeClosureV1.serializer(), encoded) shouldBe closure
    }

    test("natural terminal closure preserves an authoritative winner") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val winner = environment.playerIds.first()
        val loser = environment.playerIds.last()

        environment.restore(
            state = environment.state.updateEntity(loser) {
                it.with(PlayerLostComponent(LossReason.LIFE_ZERO))
            },
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
        )
        val pass = environment.legalActions().first { it.action is PassPriority }.action
        environment.step(pass)
        val result = environment.step(
            environment.legalActions().first { it.action is PassPriority }.action,
        )

        result.terminated.shouldBeTrue()
        val closure = result.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.GameTerminal>()
        closure.kind shouldBe EpisodeClosureV1.Kind.GAME_TERMINAL
        closure.winnerId shouldBe winner
        closure.reason shouldBe GameEndReason.LIFE_ZERO
    }

    test("natural draw closure is terminal without fabricating a winner") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())

        environment.restore(
            state = environment.state.updateEntity(environment.playerIds[0]) {
                it.with(PlayerLostComponent(LossReason.LIFE_ZERO))
            }.updateEntity(environment.playerIds[1]) {
                it.with(PlayerLostComponent(LossReason.LIFE_ZERO))
            },
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
        )
        val pass = environment.legalActions().first { it.action is PassPriority }.action
        environment.step(pass)
        val result = environment.step(
            environment.legalActions().first { it.action is PassPriority }.action,
        )

        result.terminated.shouldBeTrue()
        val closure = result.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.GameTerminal>()
        closure.kind shouldBe EpisodeClosureV1.Kind.GAME_TERMINAL
        closure.winnerId shouldBe null
        closure.reason shouldBe GameEndReason.DRAW
    }

    test("horizon closure is interrupted and retains the legacy truncated result") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config(), maxSteps = 1)
        val pass = environment.legalActions().first { it.action is PassPriority }.action

        val result = environment.step(pass)

        result.terminated.shouldBeFalse()
        result.truncated.shouldBeTrue()
        result.reward.values.all { it == 0.0 }.shouldBeTrue()
        result.episodeClosure shouldBe EpisodeClosureV1.Interrupted(
            stepCount = 1,
            reason = EpisodeInterruptionReason.HORIZON_REACHED,
        )
    }

    test("unsupported observation diagnostics classify the episode as failed") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        environment.restore(
            state = environment.state.copy(
                pendingDecision = unsupportedDecision(environment.playerIds.first()),
            ),
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
        )
        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))

        val failure = shouldThrow<UnsupportedPathFailure> { gym.observe() }

        failure.diagnostics.single().code shouldBe DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING
        val closure = gym.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.Failed>()
        closure.kind shouldBe EpisodeClosureV1.Kind.FAILED
        closure.reason shouldBe EpisodeFailureReason.UNSUPPORTED_DIAGNOSTIC
        environment.isTerminal.shouldBeFalse()
        environment.isTruncated.shouldBeFalse()
    }

    test("public-choice rejection classifies the episode without changing diagnostic evidence") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val beforeDiagnostics = environment.diagnostics
        val wrongPlayer = EntityId("not-a-player")

        shouldThrow<IllegalArgumentException> {
            environment.step(PassPriority(wrongPlayer))
        }

        environment.diagnostics shouldBe beforeDiagnostics
        val closure = environment.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.Failed>()
        closure.reason shouldBe EpisodeFailureReason.PUBLIC_CHOICE_REJECTED
    }

    test("Gym adapter exceptions classify the episode as observation failure") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        environment.restore(
            state = environment.state.copy(gameOver = true),
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
        )
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 99,
            observationBuilder = ObservationBuilder(cardRegistry = registry()),
        )

        shouldThrow<IllegalStateException> { gym.observe() }

        val closure = gym.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.Failed>()
        closure.reason shouldBe EpisodeFailureReason.OBSERVATION_FAILURE
    }

    test("a committed transition followed by a failed observation is not terminal") {
        val cardRegistry = registry(includeUnsupportedTarget = true)
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            config(startingPlayerIndex = 1).copy(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20, unsupportedTargetCard.name to 1)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
            ),
        )
        val alice = environment.playerIds.first()
        moveCardToBattlefield(environment, alice, unsupportedTargetCard.name)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        val before = gym.observe()
        val pass = before.observation.legalActions.first { it.kind == "PassPriority" }
        val stateBefore = environment.state
        val stepCountBefore = environment.stepCount

        shouldThrow<UnsupportedPathFailure> { gym.step(pass.actionId) }

        environment.state shouldNotBe stateBefore
        environment.stepCount shouldBe stepCountBefore + 1
        environment.isTerminal.shouldBeFalse()
        environment.isTruncated.shouldBeFalse()
        val closure = gym.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.Failed>()
        closure.reason shouldBe EpisodeFailureReason.UNSUPPORTED_DIAGNOSTIC
    }

    test("reset clears prior failure closure and a fork preserves it by value") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        shouldThrow<IllegalArgumentException> {
            environment.step(PassPriority(EntityId("not-a-player")))
        }
        val failed = environment.episodeClosure
            .shouldBeInstanceOf<EpisodeClosureV1.Failed>()

        val fork = environment.fork()
        fork.episodeClosure shouldBe failed

        environment.reset(config())
        environment.episodeClosure.shouldBeNull()
    }

    test("snapshot restore preserves an explicit failure closure") {
        val environment = GameEnvironment.create(registry())
        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))
        gym.reset(config())
        gym.observe()

        shouldThrow<IllegalArgumentException> { gym.step(Int.MAX_VALUE) }
        val failed = gym.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.Failed>()
        val codec = com.wingedsheep.gym.service.SnapshotCodec()
        val handle = gym.snapshot(codec)

        gym.restore(codec, handle)

        gym.episodeClosure shouldBe failed
        gym.diagnostics shouldBe EpisodeDiagnostics.EMPTY
    }

    test("a failed closure remains failed when a later step reaches the horizon") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config(), maxSteps = 1)

        shouldThrow<IllegalArgumentException> {
            environment.step(PassPriority(EntityId("not-a-player")))
        }
        val failed = environment.episodeClosure.shouldBeInstanceOf<EpisodeClosureV1.Failed>()

        val valid = environment.legalActions().first { it.action is PassPriority }.action
        val result = environment.step(valid)

        result.terminated.shouldBeFalse()
        result.truncated.shouldBeTrue()
        result.episodeClosure shouldBe failed
    }
})
