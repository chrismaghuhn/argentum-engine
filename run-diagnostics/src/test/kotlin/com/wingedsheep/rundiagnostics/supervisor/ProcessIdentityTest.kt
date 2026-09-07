package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ProcessIdentityTest : FunSpec({
    test("accepts a live process whose PID and start instant match") {
        val source = FakeProcessHandleSource(
            ProcessHandleObservation(FIXTURE_PID, alive = true, startInstant = FIXTURE_START),
        )

        val result = ProcessIdentityChecker(source).observe(FIXTURE_PID, FIXTURE_START)

        result.liveness shouldBe ProcessLiveness.ALIVE
    }

    test("rejects PID reuse when the observed start instant differs") {
        val source = FakeProcessHandleSource(
            ProcessHandleObservation(
                FIXTURE_PID,
                alive = true,
                startInstant = Instant.parse("2026-09-07T00:01:00Z"),
            ),
        )

        val result = ProcessIdentityChecker(source).observe(FIXTURE_PID, FIXTURE_START)

        result.liveness shouldBe ProcessLiveness.IDENTITY_MISMATCH
    }

    test("reports an absent process as exited without attaching to another PID") {
        val source = FakeProcessHandleSource(null)

        val result = ProcessIdentityChecker(source).observe(FIXTURE_PID, FIXTURE_START)

        result.liveness shouldBe ProcessLiveness.PROCESS_EXITED
    }

    test("reports unknown when process identity access is denied") {
        val source = object : ProcessHandleSource {
            override fun observe(pid: Long): ProcessHandleObservation? = throw SecurityException("denied")
        }

        val result = ProcessIdentityChecker(source).observe(FIXTURE_PID, FIXTURE_START)

        result.liveness shouldBe ProcessLiveness.UNKNOWN
    }
})

private class FakeProcessHandleSource(
    private val observation: ProcessHandleObservation?,
) : ProcessHandleSource {
    override fun observe(pid: Long): ProcessHandleObservation? = observation
}
