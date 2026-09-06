package com.wingedsheep.rundiagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DiagnosticsRecorderTest : FunSpec({
    test("uses process-relative monotonic elapsed time") {
        val clock = ControlledMonotonicClock(10_000)
        val elapsed = ElapsedMonotonicClock(clock)

        elapsed.nowElapsedNanos() shouldBe 0
        clock.advanceBy(375)
        elapsed.nowElapsedNanos() shouldBe 375
    }

    test("heartbeat advances without useful progress") {
        val clock = ControlledMonotonicClock(20_000)
        val recorder = newRecorder(monotonicClock = clock)
        val before = recorder.snapshot()!!

        clock.advanceBy(100)
        recorder.heartbeatTick()
        clock.advanceBy(100)
        recorder.heartbeatTick()

        val after = recorder.snapshot()!!
        after.heartbeatSequence shouldBe 2
        after.progress.engineProgressCount shouldBe before.progress.engineProgressCount
        after.progress.authoritativeTransitionCount shouldBe before.progress.authoritativeTransitionCount
        after.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.close()
    }

    test("records only explicitly owned scalar deltas") {
        val clock = ControlledMonotonicClock(30_000)
        val recorder = newRecorder(monotonicClock = clock)
        val initialUsefulSequence = recorder.snapshot()!!.progress.usefulProgressSequence

        clock.advanceBy(50)
        recorder.recordUsefulProgress(
            authoritativeTransitionDelta = 2,
            semanticDecisionDelta = 1,
            episodeOrdinal = 0,
        )
        clock.advanceBy(25)
        recorder.recordUsefulProgress(authoritativeTransitionDelta = 3, episodeOrdinal = 1)

        val progress = recorder.snapshot()!!.progress
        progress.engineProgressCount shouldBe null
        progress.authoritativeTransitionCount shouldBe 5
        progress.semanticDecisionCount shouldBe 1
        progress.episodeOrdinal shouldBe 1
        progress.lastUsefulProgressElapsedNanos shouldBe 75
        progress.usefulProgressSequence shouldBe initialUsefulSequence + 2
        recorder.close()
    }

    test("all-null useful-progress reports do not refresh useful progress") {
        val recorder = newRecorder()
        val before = recorder.snapshot()!!
        val historyBefore = recorder.recentHistory()

        recorder.recordUsefulProgress()

        val after = recorder.snapshot()!!
        after.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        after.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.recentHistory() shouldBe historyBefore
        recorder.close()
    }

    test("all-zero useful-progress reports do not refresh useful progress") {
        val recorder = newRecorder()
        val before = recorder.snapshot()!!

        recorder.recordUsefulProgress(
            engineProgressDelta = 0,
            authoritativeTransitionDelta = 0,
            semanticDecisionDelta = 0,
            trajectoryDecisionDelta = 0,
            replayFramesVerifiedDelta = 0,
            episodesAdmittedDelta = 0,
            bytesSerializedDelta = 0,
            shardsFinalizedDelta = 0,
        )

        val after = recorder.snapshot()!!
        after.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        after.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.close()
    }

    test("zero initializes an unknown counter without useful advancement") {
        val clock = ControlledMonotonicClock(40_000)
        val recorder = newRecorder(monotonicClock = clock)
        val before = recorder.snapshot()!!

        recorder.recordUsefulProgress(engineProgressDelta = 0)

        val initialized = recorder.snapshot()!!
        initialized.progress.engineProgressCount shouldBe 0
        initialized.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        initialized.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos

        clock.advanceBy(10)
        recorder.recordUsefulProgress(engineProgressDelta = 1)

        val advanced = recorder.snapshot()!!
        advanced.progress.engineProgressCount shouldBe 1
        advanced.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence + 1
        advanced.progress.lastUsefulProgressElapsedNanos shouldBe 10
        recorder.close()
    }

    test("artifact snapshots do not count as useful progress") {
        val recorder = newRecorder()
        val before = recorder.snapshot()!!
        val artifact = ArtifactCounterV1(
            artifactKind = "safe-artifact",
            logicalName = "current",
            bytesWritten = 123,
            itemsFinalized = 4,
        )

        recorder.recordArtifactCounters(listOf(artifact))
        val first = recorder.snapshot()!!
        recorder.recordArtifactCounters(listOf(artifact))
        val repeated = recorder.snapshot()!!

        first.latestArtifactCounters shouldBe listOf(artifact)
        repeated.latestArtifactCounters shouldBe listOf(artifact)
        repeated.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        repeated.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.close()
    }

    test("changed artifact snapshots remain non-useful without an explicit progress event") {
        val recorder = newRecorder()
        val before = recorder.snapshot()!!

        recorder.recordArtifactCounters(
            listOf(
                ArtifactCounterV1(
                    artifactKind = "safe-artifact",
                    logicalName = "current",
                    bytesWritten = 124,
                    itemsFinalized = 4,
                ),
            ),
        )

        val after = recorder.snapshot()!!
        after.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        after.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.close()
    }

    test("episode ordinal gauge updates remain non-useful without a counter delta") {
        val recorder = newRecorder()
        val before = recorder.snapshot()!!

        recorder.recordUsefulProgress(episodeOrdinal = 0)
        recorder.recordUsefulProgress(episodeOrdinal = 1)

        val after = recorder.snapshot()!!
        after.progress.episodeOrdinal shouldBe 1
        after.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        after.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.close()
    }

    test("heartbeat continues while non-progress reports leave useful progress unchanged") {
        val clock = ControlledMonotonicClock(50_000)
        val recorder = newRecorder(monotonicClock = clock)
        val before = recorder.snapshot()!!

        recorder.recordUsefulProgress()
        recorder.recordUsefulProgress(engineProgressDelta = 0)
        recorder.recordArtifactCounters(
            listOf(
                ArtifactCounterV1(
                    artifactKind = "safe-artifact",
                    logicalName = "current",
                    bytesWritten = 123,
                    itemsFinalized = 4,
                ),
            ),
        )
        clock.advanceBy(100)
        recorder.heartbeatTick()

        val after = recorder.snapshot()!!
        after.heartbeatSequence shouldBe 1
        after.progress.usefulProgressSequence shouldBe before.progress.usefulProgressSequence
        after.progress.lastUsefulProgressElapsedNanos shouldBe before.progress.lastUsefulProgressElapsedNanos
        recorder.close()
    }

    test("rejects negative monotonic counters and deltas") {
        shouldThrow<IllegalArgumentException> {
            ProgressVectorV1(authoritativeTransitionCount = -1)
        }

        val recorder = newRecorder()
        shouldThrow<IllegalArgumentException> {
            recorder.recordUsefulProgress(authoritativeTransitionDelta = -1)
        }
        recorder.snapshot()!!.progress.authoritativeTransitionCount shouldBe null
        recorder.close()
    }

    test("retains only the configured bounded history") {
        val recorder = newRecorder(historyCapacity = 2)

        recorder.recordUsefulProgress(authoritativeTransitionDelta = 1)
        recorder.advanceStage(sampleStage("POLICY_DECISION"))
        recorder.heartbeatTick()

        recorder.recentHistory() shouldHaveSize 2
        recorder.recentHistory().last().kind shouldBe ProgressHistoryKind.HEARTBEAT
        recorder.close()
    }

    test("disabled recorder is a no-op without a status snapshot") {
        val recorder = DiagnosticsRecorder.disabled()

        recorder.enabled shouldBe false
        recorder.heartbeatTick() shouldBe 0
        recorder.recordUsefulProgress(engineProgressDelta = 1)
        recorder.snapshot() shouldBe null
        recorder.recentHistory() shouldBe emptyList()
        recorder.close()
    }
})

internal class ControlledMonotonicClock(start: Long) : MonotonicClock {
    private var current = start

    override fun nowNanos(): Long = current

    fun advanceBy(delta: Long) {
        current += delta
    }
}

internal fun newRecorder(
    monotonicClock: MonotonicClock = ControlledMonotonicClock(1_000),
    historyCapacity: Int = 8,
): DiagnosticsRecorder = DiagnosticsRecorder.enabled(
    diagnosticRunId = "diagnostic-test-run",
    sourceCommit = "c75c950bad818225113fb398ddded4b1f360af2c",
    workloadType = "TEST_WORKLOAD",
    initialStage = sampleStage(),
    processId = null,
    wallClock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC),
    monotonicClock = monotonicClock,
    historyCapacity = historyCapacity,
)

internal fun sampleStage(name: String = "BOOTSTRAP"): StageRefV1 = StageRefV1(
    stageFamilySchemaIdentity = "test-workload-stage@v1",
    stageName = name,
)

internal fun sampleStatus(): RunStatusV1 = RunStatusV1(
    diagnosticRunId = "diagnostic-test-run",
    sourceCommit = "c75c950bad818225113fb398ddded4b1f360af2c",
    workloadType = "TEST_WORKLOAD",
    processId = null,
    processStartWallClock = "2026-09-06T00:00:00Z",
    heartbeatSequence = 0,
    heartbeatWallClock = null,
    monotonicAgeData = MonotonicAgeDataV1(
        heartbeatElapsedNanos = null,
        stageStartedElapsedNanos = 0,
        lastUsefulProgressElapsedNanos = null,
    ),
    currentStage = sampleStage(),
    stageSequence = 0,
    stageStartedWallClock = "2026-09-06T00:00:00Z",
    progress = ProgressVectorV1(),
)
