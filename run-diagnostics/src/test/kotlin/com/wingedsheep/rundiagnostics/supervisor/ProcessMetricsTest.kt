package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ProcessMetricsTest : FunSpec({
    test("parses Linux proc CPU, RSS, and thread metrics from bounded files") {
        val procRoot = Files.createTempDirectory("run-diagnostics-proc-")
        try {
            val processDirectory = procRoot.resolve(FIXTURE_PID.toString())
            Files.createDirectories(processDirectory)
            Files.writeString(
                processDirectory.resolve("stat"),
                "1234 (worker name) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25",
            )
            Files.writeString(
                processDirectory.resolve("status"),
                "Name: worker\nThreads: 7\nVmRSS: 4096 kB\n",
            )

            val result = LinuxProcessMetricsSampler(procRoot, clockTicksPerSecond = 100)
                .sample(FIXTURE_PID)

            result.availability shouldBe EvidenceAvailability.AVAILABLE
            result.threadCount shouldBe 7
            result.rssBytes shouldBe 4_194_304
            result.cpuTimeNanos shouldBe 230_000_000L
        } finally {
            procRoot.toFile().deleteRecursively()
        }
    }

    test("reports missing Linux proc entries as unavailable") {
        val procRoot = Files.createTempDirectory("run-diagnostics-proc-missing-")
        try {
            val result = LinuxProcessMetricsSampler(procRoot).sample(FIXTURE_PID)

            result.availability shouldBe EvidenceAvailability.MISSING
        } finally {
            procRoot.toFile().deleteRecursively()
        }
    }

    test("parses fixed Windows process output without accepting arbitrary commands") {
        val runner = RecordingBoundedCommandRunner("1234|2.5|4096|7|2026-09-07T00:00:00Z")
        val sampler = WindowsProcessMetricsSampler(runner, powershellExecutable = "powershell.exe")

        val result = sampler.sample(FIXTURE_PID)

        result.availability shouldBe EvidenceAvailability.AVAILABLE
        result.cpuTimeNanos shouldBe 2_500_000_000L
        result.rssBytes shouldBe 4096
        result.threadCount shouldBe 7
        runner.command.first() shouldBe "powershell.exe"
        runner.command.any { it.contains(FIXTURE_PID.toString()) } shouldBe true
    }

    test("samples the current Windows test JVM through the bounded provider") {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return@test

        val result = WindowsProcessMetricsSampler(JdkBoundedCommandRunner())
            .sample(ProcessHandle.current().pid())

        result.availability shouldBe EvidenceAvailability.AVAILABLE
        (result.cpuTimeNanos != null) shouldBe true
        (result.rssBytes != null) shouldBe true
        (result.threadCount != null) shouldBe true
    }
})

private class RecordingBoundedCommandRunner(
    private val output: String,
) : BoundedCommandRunner {
    lateinit var command: List<String>

    override fun run(command: List<String>, timeoutMillis: Long, maxBytes: Int): BoundedCommandResult {
        this.command = command
        return BoundedCommandResult(
            availability = EvidenceAvailability.AVAILABLE,
            exitCode = 0,
            output = output,
        )
    }
}
