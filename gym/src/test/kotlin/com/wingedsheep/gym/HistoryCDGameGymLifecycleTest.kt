package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.HistoryCOperationException
import com.wingedsheep.gym.history.HistoryCReferenceEnvelopeV1
import com.wingedsheep.gym.history.HistoryCSnapshotCodecV1
import com.wingedsheep.gym.history.PerspectiveReferenceProjectionResult
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.gym.service.HistoryCContinuationAuthorityV1
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class HistoryCDGameGymLifecycleTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 60)),
            PlayerConfig("Bob", Deck.of("Mountain" to 60)),
        ),
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    fun gym(): GameGymEnv {
        val cardRegistry = registry()
        return GameGymEnv(
            environment = GameEnvironment.create(cardRegistry),
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
    }

    test("HISTC-D-05/D-06 reset creates a new namespace and rejects active reuse") {
        val gym = gym()
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val first = gym.historyCLifecycleState()!!
        first.semanticEpisodeId shouldBe "episode-a"
        first.registries.values.all { it.nextAliasOrdinal == 0L } shouldBe true

        val sameEpisode = shouldThrow<HistoryCOperationException> {
            gym.reset(config(), semanticEpisodeId = "episode-a")
        }
        sameEpisode.failure.code shouldBe HistoryCFailureCode.HISTORY_C_EPISODE_ALREADY_ACTIVE
        gym.historyCLifecycleState() shouldBe first

        val missingIdentity = shouldThrow<HistoryCOperationException> {
            gym.reset(config())
        }
        missingIdentity.failure.code shouldBe HistoryCFailureCode.HISTORY_C_EPISODE_ALREADY_ACTIVE
        gym.historyCLifecycleState() shouldBe first

        gym.reset(config(), semanticEpisodeId = "episode-b")
        val second = gym.historyCLifecycleState()!!
        second.semanticEpisodeId shouldBe "episode-b"
        second.registries.values.all { it.nextAliasOrdinal == 0L } shouldBe true
        second.registries.values.all { it.activeBindings.isEmpty() && it.retiredAliases.isEmpty() } shouldBe true
    }

    test("HISTC-D-07 trusted restore rejects a legacy state-only snapshot atomically") {
        val gym = gym()
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val codec = SnapshotCodec()
        val beforeDigest = gym.observe().observation.stateDigest
        val legacy = codec.save(
            state = gym.environment.state,
            playerIds = gym.environment.playerIds,
            stepCount = gym.environment.stepCount,
            maxSteps = gym.environment.maxSteps,
        )

        val failure = shouldThrow<HistoryCOperationException> {
            gym.restore(codec, legacy)
        }
        failure.failure.code shouldBe HistoryCFailureCode.HISTORY_C_SNAPSHOT_MISSING
        gym.historyCLifecycleState()?.semanticEpisodeId shouldBe "episode-a"
        gym.environment.stepCount shouldBe 0
        gym.observe().observation.stateDigest shouldBe beforeDigest
    }

    test("HISTC-D-02 snapshot restore preserves the active History-C episode namespace") {
        val gym = gym()
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)

        gym.reset(config(), semanticEpisodeId = "episode-b")
        gym.restore(codec, handle)

        val restored = gym.historyCLifecycleState()!!
        restored.semanticEpisodeId shouldBe "episode-a"
        restored.registries.values.all { it.nextAliasOrdinal == 0L } shouldBe true
        restored.registries.values.all { it.activeBindings.isEmpty() && it.retiredAliases.isEmpty() } shouldBe true
    }

    test("HISTC-D-16 corrupt History-C snapshot rejects without changing game or lifecycle state") {
        val gym = gym()
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val codec = SnapshotCodec()
        val valid = HistoryCSnapshotCodecV1.encode(gym.historyCLifecycleState()!!, stepCount = 0)
        val corrupted = valid.copyOf().also { bytes -> bytes[bytes.lastIndex] = '!'.code.toByte() }
        val handle = codec.save(
            state = gym.environment.state,
            playerIds = gym.environment.playerIds,
            stepCount = gym.environment.stepCount,
            maxSteps = gym.environment.maxSteps,
            historyCContinuation = corrupted,
            historyCContinuationAuthority = HistoryCContinuationAuthorityV1.TRUSTED_COMMITTED,
        )
        val beforeDigest = gym.observe().observation.stateDigest

        val failure = shouldThrow<HistoryCOperationException> {
            gym.restore(codec, handle)
        }
        failure.failure.code shouldBe HistoryCFailureCode.INVALID_HISTORY_C_SNAPSHOT_STATE
        gym.historyCLifecycleState()?.semanticEpisodeId shouldBe "episode-a"
        gym.environment.stepCount shouldBe 0
        gym.observe().observation.stateDigest shouldBe beforeDigest
    }

    test("HISTC-D-REVIEW-01 speculative fork snapshot cannot be restored into trusted parent") {
        val parent = gym()
        parent.reset(config(), semanticEpisodeId = "episode-a")
        val fork = parent.fork() as GameGymEnv
        val pass = fork.observe().observation.legalActions.first { it.kind == "PassPriority" }
        fork.step(pass.actionId)
        val codec = SnapshotCodec()
        val speculativeSnapshot = fork.snapshot(codec)
        fork.restore(codec, speculativeSnapshot)
        fork.historyCLifecycleState()?.semanticEpisodeId shouldBe "episode-a"
        val beforeDigest = parent.observe().observation.stateDigest
        val beforeLifecycle = parent.historyCLifecycleState()

        val failure = shouldThrow<HistoryCOperationException> {
            parent.restore(codec, speculativeSnapshot)
        }
        failure.failure.code shouldBe HistoryCFailureCode.FORK_OR_SPECULATIVE_SOURCE
        parent.observe().observation.stateDigest shouldBe beforeDigest
        parent.historyCLifecycleState() shouldBe beforeLifecycle
    }

    test("HISTC-D-REVIEW-02 snapshot preserves the last committed reference source") {
        val gym = gym()
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val perspective = gym.environment.playerIds.first()
        val envelope = HistoryCReferenceEnvelopeV1(
            perspectivePlayerId = perspective,
            candidates = emptyList(),
        )
        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)
        gym.lastCommittedReferenceProjection(perspective, envelope)
            .shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Accepted>()

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        gym.restore(codec, handle)

        gym.lastCommittedReferenceProjection(perspective, envelope)
            .shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Accepted>()
    }

    test("HISTC-D-03/D-04 fork copies immutable lifecycle state but cannot commit History-C") {
        val gym = gym()
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val parentBeforeFork = gym.historyCLifecycleState()
        val fork = gym.fork() as GameGymEnv

        fork.historyCLifecycleState() shouldBe parentBeforeFork
        val forkProjection = fork.lastCommittedReferenceProjection(
            perspectivePlayerId = fork.environment.playerIds.first(),
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = fork.environment.playerIds.first(),
                candidates = emptyList(),
            ),
        )
        val failure = forkProjection as PerspectiveReferenceProjectionResult.Rejected
        failure.failure.code shouldBe HistoryCFailureCode.FORK_OR_SPECULATIVE_SOURCE
        gym.historyCLifecycleState() shouldBe parentBeforeFork
    }
})
