package com.wingedsheep.rundiagnostics.supervisor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class JvmDiagnosticsTest : FunSpec({
    test("builds only fixed target-PID jcmd arguments") {
        val directory = Files.createTempDirectory("run-diagnostics-jcmd-")
        try {
            val jcmd = toolPath(directory, "jcmd")
            Files.createFile(jcmd)
            val runner = RecordingJvmBoundedCommandRunner("jcmd output")
            val commandRunner = JdkJvmCommandRunner(directory, runner)

            val result = commandRunner.run(FIXTURE_PID, JvmCommandKind.THREAD_PRINT, 1_000, 4_096)

            result.availability shouldBe EvidenceAvailability.AVAILABLE
            runner.command shouldBe listOf(jcmd.toString(), FIXTURE_PID.toString(), "Thread.print", "-l")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("reports a missing JVM tool without spawning a command") {
        val directory = Files.createTempDirectory("run-diagnostics-jcmd-missing-")
        try {
            val runner = RecordingJvmBoundedCommandRunner("unused")
            val result = JdkJvmCommandRunner(directory, runner)
                .run(FIXTURE_PID, JvmCommandKind.GC_HEAP_INFO, 1_000, 4_096)

            result.availability shouldBe EvidenceAvailability.NOT_CONFIGURED
            result.failureCode shouldBe SupervisorFailureCode.COMMAND_TOOL_MISSING
            runner.invocations shouldBe 0
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("recognizes a deadlock only from explicit JVM evidence") {
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(
                JvmCommandResult(
                    kind = JvmCommandKind.THREAD_PRINT,
                    availability = EvidenceAvailability.AVAILABLE,
                    output = "Found one Java-level deadlock:\nthread-a waits for thread-b",
                ),
            ),
        )

        evidence.deadlockDetected shouldBe true
    }

    test("recognizes stable waiting stacks across bounded dumps") {
        val output = "worker\njava.lang.Thread.State: WAITING (parking)\n at worker.Loop.run(Loop.kt:1)"
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(
                JvmCommandResult(JvmCommandKind.THREAD_PRINT, EvidenceAvailability.AVAILABLE, output = output),
                JvmCommandResult(JvmCommandKind.THREAD_PRINT, EvidenceAvailability.AVAILABLE, output = output),
                JvmCommandResult(JvmCommandKind.THREAD_PRINT, EvidenceAvailability.AVAILABLE, output = output),
            ),
        )

        evidence.threadDumpCount shouldBe 3
        evidence.stableWaitStack shouldBe true
        evidence.stableHotStack shouldBe false
    }

    test("does not label stable runnable stacks as waiting evidence") {
        val output = "worker\njava.lang.Thread.State: RUNNABLE\n at worker.Loop.run(Loop.kt:1)"
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(
                JvmCommandResult(JvmCommandKind.THREAD_PRINT, EvidenceAvailability.AVAILABLE, output = output),
                JvmCommandResult(JvmCommandKind.THREAD_PRINT, EvidenceAvailability.AVAILABLE, output = output),
            ),
        )

        evidence.stableWaitStack shouldBe false
        evidence.stableHotStack shouldBe true
    }

    test("correlates a stable hot stack to its own thread when an unrelated wait stack changes") {
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(
                threadDumpResult(multiThreadDump("worker.Hot.loop(Hot.kt:1)", "worker.Wait.await(Wait.kt:1)")),
                threadDumpResult(multiThreadDump("worker.Hot.loop(Hot.kt:1)", "worker.Wait.await(Wait.kt:2)")),
            ),
        )

        evidence.stableHotStack shouldBe true
        evidence.stableWaitStack shouldBe false
        evidence.ambiguousThreadStateEvidence shouldBe false
    }

    test("correlates a stable wait stack to its own thread when an unrelated runnable stack changes") {
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(
                threadDumpResult(multiThreadDump("worker.Hot.loop(Hot.kt:1)", "worker.Wait.await(Wait.kt:1)")),
                threadDumpResult(multiThreadDump("worker.Hot.loop(Hot.kt:2)", "worker.Wait.await(Wait.kt:1)")),
            ),
        )

        evidence.stableHotStack shouldBe false
        evidence.stableWaitStack shouldBe true
        evidence.ambiguousThreadStateEvidence shouldBe false
    }

    test("marks independently stable hot and wait threads as contradictory evidence") {
        val dump = multiThreadDump("worker.Hot.loop(Hot.kt:1)", "worker.Wait.await(Wait.kt:1)")
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(threadDumpResult(dump), threadDumpResult(dump), threadDumpResult(dump)),
        )

        evidence.stableHotStack shouldBe true
        evidence.stableWaitStack shouldBe true
        evidence.ambiguousThreadStateEvidence shouldBe true
    }

    test("does not infer GC pressure from raw GC.heap_info text") {
        val evidence = JvmEvidenceAnalyzer.analyze(
            listOf(
                JvmCommandResult(
                    kind = JvmCommandKind.GC_HEAP_INFO,
                    availability = EvidenceAvailability.AVAILABLE,
                    output = """
                        garbage-first heap   total 1024M, used 768M [0x0000000100000000, 0x0000000140000000)
                         region size 4M, 12 young (48M), 2 survivors (8M)
                        Metaspace       used 32M, committed 34M, reserved 1088M
                    """.trimIndent(),
                ),
            ),
        )

        evidence.gcPressure shouldBe null
    }

    test("falls back to bounded jstack capture when jcmd thread print is unavailable") {
        val runner = RecordingFallbackJvmRunner()
        val config = SupervisorConfigV1(
            targetPid = FIXTURE_PID,
            statusPath = "status.json",
            diagnosticsDirectory = "diagnostics",
            threadDumpCount = 1,
            threadDumpIntervalMillis = 1,
            captureTimeoutMillis = 10,
        )

        JvmEvidenceCollector(config, runner, SupervisorSleeper { }).capture(FIXTURE_PID)

        runner.kinds shouldBe listOf(
            JvmCommandKind.THREAD_PRINT,
            JvmCommandKind.JSTACK,
            JvmCommandKind.GC_HEAP_INFO,
            JvmCommandKind.VM_FLAGS,
        )
    }

    test("bounds a real diagnostic child command and captures output") {
        val command = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd.exe", "/c", "echo", "bounded")
        } else {
            listOf("sh", "-c", "printf bounded")
        }

        val result = JdkBoundedCommandRunner().run(command, timeoutMillis = 2_000, maxBytes = 1_024)

        result.availability shouldBe EvidenceAvailability.AVAILABLE
        result.output!!.lowercase() shouldContain "bounded"
    }

    test("terminates only a timed-out diagnostic child and reports the timeout") {
        val command = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd.exe", "/c", "ping -n 10 127.0.0.1 > nul")
        } else {
            listOf("sh", "-c", "sleep 2")
        }

        val result = JdkBoundedCommandRunner().run(command, timeoutMillis = 50, maxBytes = 1_024)

        result.availability shouldBe EvidenceAvailability.TIMED_OUT
        result.failureCode shouldBe SupervisorFailureCode.COMMAND_TIMED_OUT
    }

    test("reports oversized command output without retaining it unboundedly") {
        val output = "0123456789ABCDEFGHIJ"
        val command = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd.exe", "/c", "echo $output")
        } else {
            listOf("sh", "-c", "printf $output")
        }

        val result = JdkBoundedCommandRunner().run(command, timeoutMillis = 2_000, maxBytes = 8)

        result.availability shouldBe EvidenceAvailability.FAILED
        result.failureCode shouldBe SupervisorFailureCode.COMMAND_OUTPUT_TOO_LARGE
    }
})

private fun threadDumpResult(output: String) = JvmCommandResult(
    kind = JvmCommandKind.THREAD_PRINT,
    availability = EvidenceAvailability.AVAILABLE,
    output = output,
)

private fun multiThreadDump(hotFrame: String, waitFrame: String): String = """
    "hot-worker" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable
       java.lang.Thread.State: RUNNABLE
        at $hotFrame
    "waiting-worker" #2 prio=5 os_prio=0 tid=0x2 nid=0x2 waiting on condition
       java.lang.Thread.State: WAITING (parking)
        at $waitFrame
""".trimIndent()

private fun toolPath(directory: Path, name: String): Path =
    if (System.getProperty("os.name").lowercase().contains("windows")) directory.resolve("$name.exe")
    else directory.resolve(name)

private class RecordingJvmBoundedCommandRunner(
    private val output: String,
) : BoundedCommandRunner {
    lateinit var command: List<String>
    var invocations: Int = 0

    override fun run(command: List<String>, timeoutMillis: Long, maxBytes: Int): BoundedCommandResult {
        this.command = command
        invocations++
        return BoundedCommandResult(
            availability = EvidenceAvailability.AVAILABLE,
            exitCode = 0,
            output = output,
            capturedBytes = output.length,
        )
    }
}

private class RecordingFallbackJvmRunner : JvmCommandRunner {
    val kinds = ArrayList<JvmCommandKind>()

    override fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult {
        kinds += kind
        return if (kind == JvmCommandKind.THREAD_PRINT) {
            JvmCommandResult(
                kind = kind,
                availability = EvidenceAvailability.NOT_CONFIGURED,
                failureCode = SupervisorFailureCode.COMMAND_TOOL_MISSING,
            )
        } else {
            JvmCommandResult(kind, EvidenceAvailability.AVAILABLE, output = "evidence")
        }
    }
}
