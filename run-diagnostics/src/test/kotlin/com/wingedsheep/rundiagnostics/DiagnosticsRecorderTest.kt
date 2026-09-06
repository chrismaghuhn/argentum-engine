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
