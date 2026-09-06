package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SupervisorCliTest : FunSpec({
    test("parses the required external-supervisor arguments and bounded overrides") {
        val config = SupervisorCli.parse(
            arrayOf(
                "--pid", "1234",
                "--status", "status/run-status.json",
                "--diagnostics-dir", "diagnostics",
                "--heartbeat-timeout-ms", "1000",
                "--useful-progress-timeout-ms", "2000",
                "--thread-dumps", "2",
                "--once",
                "--artifact", "artifact.bin",
            ),
        )

        config.targetPid shouldBe 1234
        config.heartbeatTimeoutMillis shouldBe 1_000
        config.usefulProgressTimeoutMillis shouldBe 2_000
        config.threadDumpCount shouldBe 2
        config.once shouldBe true
        config.safeArtifactPaths shouldContain "artifact.bin"
    }

    test("rejects missing, unknown, and unsafe CLI arguments") {
        shouldThrow<SupervisorCliException> {
            SupervisorCli.parse(arrayOf("--pid", "1234"))
        }
        shouldThrow<SupervisorCliException> {
            SupervisorCli.parse(
                arrayOf("--pid", "1234", "--status", "status.json", "--diagnostics-dir", "diagnostics", "--unknown"),
            )
        }
        shouldThrow<SupervisorCliException> {
            SupervisorCli.parse(
                arrayOf("--pid", "0", "--status", "status.json", "--diagnostics-dir", "diagnostics"),
            )
        }
    }
})
