package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.RunStatusCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

class SupervisorContractsTest : FunSpec({
    test("validates bounded supervisor configuration") {
        SupervisorConfigV1(
            targetPid = FIXTURE_PID,
            statusPath = "status/run-status.json",
            diagnosticsDirectory = "diagnostics",
        )

        shouldThrow<IllegalArgumentException> {
            SupervisorConfigV1(
                targetPid = 0,
                statusPath = "status/run-status.json",
                diagnosticsDirectory = "diagnostics",
            )
        }
        shouldThrow<IllegalArgumentException> {
            SupervisorConfigV1(
                targetPid = FIXTURE_PID,
                statusPath = "status/run-status.json",
                diagnosticsDirectory = "diagnostics",
                sampleIntervalMillis = 0,
            )
        }
        shouldThrow<IllegalArgumentException> {
            SupervisorConfigV1(
                targetPid = FIXTURE_PID,
                statusPath = "status/run-status.json",
                diagnosticsDirectory = "diagnostics",
                threadDumpCount = 6,
            )
        }
    }

    test("reads a bounded strict RunStatusV1 sidecar") {
        val directory = Files.createTempDirectory("run-diagnostics-status-reader-")
        try {
            val path = directory.resolve("run-status.json")
            Files.write(path, RunStatusCodec.encode(fixtureStatus()))

            val result = StatusSidecarReader(path).read()

            result.shouldBeInstanceOf<StatusReadResult.Available>().status.diagnosticRunId shouldBe
                "supervisor-test-run"
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("reports missing, malformed, and oversized sidecars without throwing") {
        val directory = Files.createTempDirectory("run-diagnostics-status-failures-")
        try {
            val missing = StatusSidecarReader(directory.resolve("missing.json")).read()
            missing.shouldBeInstanceOf<StatusReadResult.Unavailable>().code shouldBe
                SupervisorFailureCode.STATUS_MISSING

            val malformedPath = directory.resolve("malformed.json")
            Files.writeString(malformedPath, "not-json")
            val malformed = StatusSidecarReader(malformedPath).read()
            malformed.shouldBeInstanceOf<StatusReadResult.Unavailable>().code shouldBe
                SupervisorFailureCode.STATUS_SCHEMA_INVALID

            val oversizedPath = directory.resolve("oversized.json")
            Files.write(oversizedPath, ByteArray(32) { '{'.code.toByte() })
            val oversized = StatusSidecarReader(oversizedPath, maxBytes = 16).read()
            oversized.shouldBeInstanceOf<StatusReadResult.Unavailable>().code shouldBe
                SupervisorFailureCode.STATUS_TOO_LARGE
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})
