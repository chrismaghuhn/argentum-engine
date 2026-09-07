package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.PerspectiveHistorySnapshotCodecV1
import com.wingedsheep.gym.history.PerspectiveHistorySnapshotDecodeResult
import com.wingedsheep.gym.history.PerspectiveHistorySnapshotEnvelopeV1
import com.wingedsheep.gym.history.PerspectiveHistoryStateV1
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

class PerspectiveHistoryV1Test : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config(
        firstDeckCard: String = "Mountain",
        secondDeckCard: String = "Mountain",
    ) = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of(firstDeckCard to 60)),
            PlayerConfig("Bob", Deck.of(secondDeckCard to 60)),
        ),
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    test("HISTD-01 committed transition appends one contiguous perspective history") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config(), semanticEpisodeId = "episode-history")
        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }

        gym.step(pass.actionId)

        val history = gym.perspectiveHistory(environment.playerIds.first())
        val firstCanonical = history.canonicalJson()
        val firstDigest = history.semanticDigest()
        gym.observe()
        gym.observe()
        gym.perspectiveHistory(environment.playerIds.first()).canonicalJson() shouldBe firstCanonical
        gym.perspectiveHistory(environment.playerIds.first()).semanticDigest() shouldBe firstDigest
        history.entries.shouldNotBeEmpty()
        history.entries.map { it.perspectiveHistoryOrdinal } shouldBe
            history.entries.indices.map { it.toLong() }
    }

    test("HISTD-02 snapshot restore preserves the complete history prefix") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config(), semanticEpisodeId = "episode-history")
        val firstPass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(firstPass.actionId)
        val prefix = gym.perspectiveHistory(environment.playerIds.first()).canonicalJson()
        val codec = SnapshotCodec()
        val snapshot = gym.snapshot(codec)

        val secondPass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(secondPass.actionId)
        gym.restore(codec, snapshot)

        gym.perspectiveHistory(environment.playerIds.first()).canonicalJson() shouldBe prefix
    }

    test("HISTD-12 speculative fork cannot append trusted perspective history") {
        val cardRegistry = registry()
        val parent = GameGymEnv(
            environment = GameEnvironment.create(cardRegistry),
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        parent.reset(config(), semanticEpisodeId = "episode-history")
        val before = parent.perspectiveHistory(parent.environment.playerIds.first()).canonicalJson()
        val fork = parent.fork() as GameGymEnv
        val pass = fork.observe().observation.legalActions.first { it.kind == "PassPriority" }

        fork.step(pass.actionId)
        parent.perspectiveHistory(parent.environment.playerIds.first()).canonicalJson() shouldBe before
    }

    test("HISTD-13 failed external action does not append history") {
        val cardRegistry = registry()
        val gym = GameGymEnv(
            environment = GameEnvironment.create(cardRegistry),
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config(), semanticEpisodeId = "episode-history")
        val player = gym.environment.playerIds.first()
        val before = gym.perspectiveHistory(player).canonicalJson()
        shouldThrow<IllegalArgumentException> { gym.step(Int.MAX_VALUE) }
        gym.perspectiveHistory(player).canonicalJson() shouldBe before
    }

    test("HISTD-10 reset creates a fresh empty history namespace") {
        val cardRegistry = registry()
        val gym = GameGymEnv(
            environment = GameEnvironment.create(cardRegistry),
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config(), semanticEpisodeId = "episode-a")
        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)
        gym.reset(config(), semanticEpisodeId = "episode-b")

        val history = gym.perspectiveHistory(gym.environment.playerIds.first())
        history.semanticEpisodeId shouldBe "episode-b"
        history.entries shouldBe emptyList()
    }

    test("HISTD-04 hidden-only setup differences do not change perspective history") {
        fun run(firstDeckCard: String): String {
            val cardRegistry = registry()
            val environment = GameEnvironment.create(cardRegistry)
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            )
            gym.reset(
                config(firstDeckCard = firstDeckCard),
                semanticEpisodeId = "episode-history",
            )
            val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
            gym.step(pass.actionId)
            return gym.perspectiveHistory(environment.playerIds.first()).canonicalJson()
        }

        run("Mountain") shouldBe run("Island")
    }

    test("HISTD-15 canonical history excludes internal coordinates and witnesses") {
        val cardRegistry = registry()
        val gym = GameGymEnv(
            environment = GameEnvironment.create(cardRegistry),
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config(), semanticEpisodeId = "episode-history")
        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)
        val canonical = gym.perspectiveHistory(gym.environment.playerIds.first()).canonicalJson()
        listOf(
            "objectIdentityStamp",
            "candidateIndex",
            "rawEventOrdinal",
            "rawEventIndex",
            "actionId",
            "decisionId",
            "KnownInformationLedger",
        ).forEach { forbidden -> canonical shouldNotContain forbidden }
    }

    test("HISTD-16 history snapshot schema and integrity fail closed") {
        val players = listOf(com.wingedsheep.sdk.model.EntityId("p1"))
        val state = PerspectiveHistoryStateV1.start("episode-history", players)
        val encoded = PerspectiveHistorySnapshotCodecV1.encode(
            state = state,
            stepCount = 0,
            projectionGeneration = 0L,
        )
        val envelope = Json.decodeFromString<PerspectiveHistorySnapshotEnvelopeV1>(
            encoded.toString(StandardCharsets.UTF_8),
        )

        fun decode(candidate: PerspectiveHistorySnapshotEnvelopeV1): HistoryCFailureCode =
            PerspectiveHistorySnapshotCodecV1.decode(
                encoded = PerspectiveHistorySnapshotCodecV1.encodeEnvelope(candidate),
                expectedPlayerIds = players,
                expectedStepCount = 0,
                expectedProjectionGeneration = 0L,
            ).shouldBeInstanceOf<PerspectiveHistorySnapshotDecodeResult.Rejected>()
                .failure.code

        decode(envelope.copy(version = 99)) shouldBe
            HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_VERSION
        decode(envelope.copy(schemaIdentity = "future-schema")) shouldBe
            HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_SCHEMA_IDENTITY
        PerspectiveHistorySnapshotCodecV1.decode(
            encoded = Json.encodeToString(envelope.copy(integritySha256 = "0".repeat(64)))
                .toByteArray(StandardCharsets.UTF_8),
            expectedPlayerIds = players,
            expectedStepCount = 0,
            expectedProjectionGeneration = 0L,
        ).shouldBeInstanceOf<PerspectiveHistorySnapshotDecodeResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.INVALID_HISTORY_D_SNAPSHOT_INTEGRITY
    }
})
