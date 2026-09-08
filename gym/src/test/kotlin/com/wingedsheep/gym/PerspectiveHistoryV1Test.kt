package com.wingedsheep.gym

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.history.HistoryCFailureCode
import com.wingedsheep.gym.history.PerspectiveHistorySnapshotCodecV1
import com.wingedsheep.gym.history.PerspectiveHistorySnapshotDecodeResult
import com.wingedsheep.gym.history.PerspectiveHistorySnapshotEnvelopeV1
import com.wingedsheep.gym.history.PerspectiveHistoryStateV1
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.readLines

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

    test("HISTD bounded exact Akiri/Chevill path remains history-enabled") {
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("docs/ml/curriculum").toFile().isDirectory }
        fun lockedDeck(fileName: String): List<String> = Path.of(
            repositoryRoot.toString(),
            "docs",
            "ml",
            "curriculum",
            fileName,
        ).readLines()
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }

        val akiri = lockedDeck("akiri-v0.1.txt")
        val chevill = lockedDeck("chevill-v0.1.txt")
        val cardRegistry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        name = "Akiri",
                        deck = Deck(akiri.drop(1)),
                        startingLife = 40,
                        commanderCardName = akiri.first(),
                    ),
                    PlayerConfig(
                        name = "Chevill",
                        deck = Deck(chevill.drop(1)),
                        startingLife = 40,
                        commanderCardName = chevill.first(),
                    ),
                ),
                startingHandSize = 7,
                skipMulligans = true,
                startingPlayerIndex = 0,
                format = Format.Commander(),
                seed = 0xD15EA5E5L,
            ),
            semanticEpisodeId = "episode-exact-pair",
        )

        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var observation = gym.observe().observation as TrainingObservation
        repeat(64) {
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            when (choice) {
                is SemanticChoice.Action -> {
                    val result = if (choice.payload == null) {
                        gym.step(choice.actionId)
                    } else {
                        gym.step(choice.actionId, choice.payload)
                    }
                    observation = result.observation as TrainingObservation
                }

                is SemanticChoice.Structured -> return@repeat
                is SemanticChoice.Gap -> error("Exact-pair characterization reached ${choice.code}")
            }
        }
        gym.perspectiveHistory(environment.playerIds.first()).entries.shouldNotBeEmpty()
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

    test("HISTD-11 checkpoint restore plus identical suffix preserves history bytes") {
        fun newGym(): GameGymEnv {
            val cardRegistry = registry()
            val environment = GameEnvironment.create(cardRegistry)
            return GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ).also { it.reset(config(), semanticEpisodeId = "episode-history") }
        }

        val gym = newGym()
        val player = gym.environment.playerIds.first()
        val prefixAction = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(prefixAction.actionId)
        val codec = SnapshotCodec()
        val checkpoint = gym.snapshot(codec)

        val uninterruptedAction = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(uninterruptedAction.actionId)
        val uninterrupted = gym.perspectiveHistory(player).canonicalJson()

        gym.restore(codec, checkpoint)
        val restoredAction = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(restoredAction.actionId)
        gym.perspectiveHistory(player).canonicalJson() shouldBe uninterrupted
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
        fun run(secondDeckCard: String): String {
            val cardRegistry = registry()
            val environment = GameEnvironment.create(cardRegistry)
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            )
            gym.reset(
                config(secondDeckCard = secondDeckCard),
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
