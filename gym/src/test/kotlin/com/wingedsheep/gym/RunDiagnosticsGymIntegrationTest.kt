package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.StepRequest
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.rundiagnostics.CoalescingStatusPublisher
import com.wingedsheep.rundiagnostics.DiagnosticsRecorder
import com.wingedsheep.rundiagnostics.MonotonicClock
import com.wingedsheep.rundiagnostics.RunStatusCodec
import com.wingedsheep.rundiagnostics.RunStatusV1
import com.wingedsheep.rundiagnostics.StageRefV1
import com.wingedsheep.rundiagnostics.supervisor.StatusReadResult
import com.wingedsheep.rundiagnostics.supervisor.StatusSidecarReader
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.system.measureNanoTime
import java.util.concurrent.atomic.AtomicLong

class RunDiagnosticsGymIntegrationTest : FunSpec({

    test("a successful strict transition advances only authoritative Gym progress") {
        val recorder = recorder()
        val environment = GameEnvironment.create(registry(), executionMode = GameEnvironmentMode.TRUSTED)
        val gym = gym(environment, recorder)
        gym.reset(config())

        val before = checkNotNull(recorder.snapshot())
        val pass = passAction(gym)
        gym.step(pass.actionId)

        val after = checkNotNull(recorder.snapshot())
        after.progress.authoritativeTransitionCount shouldBe 1L
        after.progress.semanticDecisionCount shouldBe null
        after.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence + 1
        after.currentStage shouldBe GymDiagnosticsStageV1.RUNNING

        shouldThrow<IllegalArgumentException> { gym.step(pass.actionId) }
        val afterRejected = checkNotNull(recorder.snapshot())
        afterRejected.progress.authoritativeTransitionCount shouldBe 1L
        afterRejected.progress.usefulProgressSequence shouldBe after.progress.usefulProgressSequence
    }

    test("forked or hypothetical Gym work cannot advance the authoritative recorder") {
        val recorder = recorder()
        val environment = GameEnvironment.create(registry(), executionMode = GameEnvironmentMode.TRUSTED)
        val gym = gym(environment, recorder)
        gym.reset(config())
        val beforeFork = checkNotNull(recorder.snapshot())

        val fork = gym.fork().shouldBeInstanceOf<GameGymEnv>()
        fork.step(passAction(fork).actionId)

        checkNotNull(recorder.snapshot()) shouldBe beforeFork
        environment.stepCount shouldBe 0
    }

    test("MultiEnvService owns one optional recorder per real game environment") {
        val recorders = mutableMapOf<String, DiagnosticsRecorder>()
        val service = MultiEnvService(
            cardRegistry = registry(),
            diagnosticsRecorderFactory = { envId ->
                recorder().also { recorders[envId.value] = it }
            },
        )
        val config = EnvConfig(
            players = listOf(
                PlayerSpec("Alice", DeckSpec.Explicit(mapOf("Forest" to 40)), playerId = EntityId("d4-service-p1")),
                PlayerSpec("Bob", DeckSpec.Explicit(mapOf("Forest" to 40)), playerId = EntityId("d4-service-p2")),
            ),
            startingHandSize = 0,
            startingPlayerIndex = 0,
            seed = 20260907L,
            maxSteps = 3,
        )

        try {
            val created = service.create(config)
            val pass = created.observation.observation.legalActions.first { it.kind == "PassPriority" }
            service.step(StepRequest(created.envId, pass.actionId))

            val status = checkNotNull(recorders.getValue(created.envId.value).snapshot())
            status.progress.authoritativeTransitionCount shouldBe 1L
        } finally {
            service.dispose(recorders.keys.map { com.wingedsheep.gym.service.EnvId(it) })
            recorders.values.forEach(DiagnosticsRecorder::close)
        }
    }

    test("diagnostics-enabled and disabled Gym runs have identical authoritative output") {
        val disabled = runGym(null)
        val recorder = recorder()
        val enabled = runGym(recorder)

        enabled.output shouldBe disabled.output
        enabled.stepCount shouldBe disabled.stepCount
        enabled.closure shouldBe disabled.closure
        checkNotNull(recorder.snapshot()).progress.authoritativeTransitionCount shouldBe enabled.stepCount.toLong()
    }

    test("diagnostics callback failure is non-fatal to the real Gym transition") {
        val control = runGym(null)
        val enabled = runGym(ThrowingRecorder())

        enabled.output shouldBe control.output
        enabled.stepCount shouldBe control.stepCount
        enabled.closure shouldBe control.closure
    }

    test("a real Gym status is published and remains operationally private") {
        val recorder = recorder()
        val environment = GameEnvironment.create(registry(), executionMode = GameEnvironmentMode.TRUSTED)
        val gym = gym(environment, recorder)
        gym.reset(config())
        gym.step(passAction(gym).actionId)

        val directory = Files.createTempDirectory("d4-gym-status-")
        val statusPath = directory.resolve("run-status.json")
        val publisher = CoalescingStatusPublisher(
            target = statusPath,
            statusSupplier = { checkNotNull(recorder.snapshot()) },
        )
        try {
            publisher.publishNow().shouldBeInstanceOf<com.wingedsheep.rundiagnostics.StatusPublicationResult.Published>()
            val read = StatusSidecarReader(statusPath).read().shouldBeInstanceOf<StatusReadResult.Available>()
            read.status.progress.authoritativeTransitionCount shouldBe 1L
            read.status.currentStage shouldBe GymDiagnosticsStageV1.RUNNING

            val encoded = RunStatusCodec.encode(read.status).toString(Charsets.UTF_8)
            listOf(
                "GameState",
                "PlayerObservation",
                "CompleteLegalDomain",
                "GameAction",
                "chosenAction",
                "hiddenHand",
                "libraryContents",
                "reward",
                "cardName",
            ).forEach { forbidden -> encoded shouldNotContain forbidden }
        } finally {
            publisher.close()
            recorder.close()
        }
    }

    test("bounded overhead characterization separates disabled, scalar, and sidecar paths") {
        val disabledNanos = measureGymNanos(null)
        val scalarRecorder = recorder()
        val scalarNanos = try {
            measureGymNanos(scalarRecorder)
        } finally {
            scalarRecorder.close()
        }

        val sidecarRecorder = recorder()
        val directory = Files.createTempDirectory("d4-gym-overhead-")
        val publisher = CoalescingStatusPublisher(
            target = directory.resolve("run-status.json"),
            statusSupplier = { checkNotNull(sidecarRecorder.snapshot()) },
        )
        val sidecarNanos = try {
            measureGymNanos(sidecarRecorder, publisher)
        } finally {
            publisher.close()
            sidecarRecorder.close()
        }

        println(
            "D4_OVERHEAD_NANOS disabled=$disabledNanos scalar=$scalarNanos sidecar=$sidecarNanos " +
                "iterations=3 scope=bounded-local-characterization",
        )
        (disabledNanos > 0L) shouldBe true
        (scalarNanos > 0L) shouldBe true
        (sidecarNanos > 0L) shouldBe true
    }
})

private data class GymOutcome(
    val output: GameState,
    val stepCount: Int,
    val closure: EpisodeClosureV1?,
)

private fun registry(): CardRegistry = CardRegistry().apply {
    register(PortalSet.cards)
    register(PortalSet.basicLands)
}

private fun config(): GameConfig = GameConfig(
    players = listOf(
        PlayerConfig("Alice", Deck.of("Forest" to 40), playerId = EntityId("d4-gym-p1")),
        PlayerConfig("Bob", Deck.of("Forest" to 40), playerId = EntityId("d4-gym-p2")),
    ),
    startingHandSize = 0,
    skipMulligans = true,
    startingPlayerIndex = 0,
    seed = 20260907L,
)

private fun gym(environment: GameEnvironment, recorder: DiagnosticsRecorder?): GameGymEnv = GameGymEnv(
    environment = environment,
    perspectivePlayerIndex = 0,
    observationBuilder = ObservationBuilder(cardRegistry = registry()),
    diagnosticsRecorder = recorder,
)

private fun passAction(gym: GameGymEnv) = gym.observe().observation.legalActions
    .first { it.kind == "PassPriority" }

private fun runGym(recorder: DiagnosticsRecorder?): GymOutcome {
    val environment = GameEnvironment.create(registry(), executionMode = GameEnvironmentMode.TRUSTED)
    val gym = gym(environment, recorder)
    gym.reset(config())
    repeat(3) {
        if (gym.isTerminal || gym.isTruncated) return@repeat
        gym.step(passAction(gym).actionId)
    }
    return GymOutcome(environment.state, environment.stepCount, environment.episodeClosure)
}

private fun measureGymNanos(
    recorder: DiagnosticsRecorder?,
    publisher: CoalescingStatusPublisher? = null,
): Long {
    val environment = GameEnvironment.create(registry(), executionMode = GameEnvironmentMode.TRUSTED)
    val gym = gym(environment, recorder)
    return measureNanoTime {
        gym.reset(config())
        repeat(3) {
            gym.step(passAction(gym).actionId)
            publisher?.requestPublish()
            publisher?.awaitIdle(Duration.ofSeconds(1))
        }
    }
}

private fun recorder(): DiagnosticsRecorder = DiagnosticsRecorder.enabled(
    diagnosticRunId = "d4-gym-run",
    sourceCommit = "a".repeat(40),
    workloadType = "gym",
    initialStage = GymDiagnosticsStageV1.INITIALIZING,
    processId = ProcessHandle.current().pid(),
    wallClock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
    monotonicClock = TestMonotonicClock(),
)

private class TestMonotonicClock : MonotonicClock {
    private val now = AtomicLong(0)

    override fun nowNanos(): Long = now.getAndAdd(1_000_000L)
}

private class ThrowingRecorder : DiagnosticsRecorder {
    override val enabled: Boolean = true

    override fun heartbeatTick(): Long = error("diagnostics failure")

    override fun advanceStage(stage: StageRefV1): Unit = error("diagnostics failure")

    override fun recordUsefulProgress(
        episodeOrdinal: Long?,
        engineProgressDelta: Long?,
        authoritativeTransitionDelta: Long?,
        semanticDecisionDelta: Long?,
        trajectoryDecisionDelta: Long?,
        replayFramesVerifiedDelta: Long?,
        episodesAdmittedDelta: Long?,
        bytesSerializedDelta: Long?,
        shardsFinalizedDelta: Long?,
    ): Unit = error("diagnostics failure")

    override fun recordArtifactCounters(counters: List<com.wingedsheep.rundiagnostics.ArtifactCounterV1>) =
        error("diagnostics failure")

    override fun snapshot(): RunStatusV1? = null

    override fun recentHistory(): List<com.wingedsheep.rundiagnostics.ProgressHistoryEntryV1> = emptyList()

    override fun close() = Unit
}
