package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.MonotonicClock
import com.wingedsheep.rundiagnostics.MonotonicAgeDataV1
import com.wingedsheep.rundiagnostics.ProgressVectorV1
import com.wingedsheep.rundiagnostics.RunStatusV1
import com.wingedsheep.rundiagnostics.StageRefV1
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal const val FIXTURE_PID = 1234L
internal val FIXTURE_START: Instant = Instant.parse("2026-09-07T00:00:00Z")
internal val FIXTURE_WALL_CLOCK: Clock = Clock.fixed(FIXTURE_START, ZoneOffset.UTC)

internal class MutableSupervisorClock(start: Long = 0) : MonotonicClock {
    var elapsedNanos: Long = start

    override fun nowNanos(): Long = elapsedNanos
}

internal fun fixtureStage(name: String = "BOOTSTRAP"): StageRefV1 = StageRefV1(
    stageFamilySchemaIdentity = "test-supervisor-stage@v1",
    stageName = name,
)

internal fun fixtureStatus(
    heartbeatSequence: Long = 1,
    usefulProgressSequence: Long = 1,
    stageSequence: Long = 0,
    processId: Long? = FIXTURE_PID,
    processStartWallClock: Instant = FIXTURE_START,
): RunStatusV1 = RunStatusV1(
    diagnosticRunId = "supervisor-test-run",
    sourceCommit = "unknown",
    workloadType = "TEST_WORKLOAD",
    processId = processId,
    processStartWallClock = processStartWallClock.toString(),
    heartbeatSequence = heartbeatSequence,
    heartbeatWallClock = processStartWallClock.toString(),
    monotonicAgeData = MonotonicAgeDataV1(
        heartbeatElapsedNanos = 1,
        stageStartedElapsedNanos = 0,
        lastUsefulProgressElapsedNanos = 1,
    ),
    currentStage = fixtureStage(if (stageSequence == 0L) "BOOTSTRAP" else "WORK"),
    stageSequence = stageSequence,
    stageStartedWallClock = processStartWallClock.toString(),
    progress = ProgressVectorV1(
        usefulProgressSequence = usefulProgressSequence,
        authoritativeTransitionCount = usefulProgressSequence,
        lastUsefulProgressElapsedNanos = 1,
    ),
)
