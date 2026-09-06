package com.wingedsheep.rundiagnostics.supervisor

import java.nio.file.Files
import java.nio.file.Path

/** Fixed-command adapter for the JDK tools; callers cannot supply an arbitrary subcommand. */
public class JdkJvmCommandRunner(
    toolDirectory: Path = Path.of(System.getProperty("java.home"), "bin"),
    private val commandRunner: BoundedCommandRunner = JdkBoundedCommandRunner(),
) : JvmCommandRunner {
    private val toolDirectory = toolDirectory.toAbsolutePath().normalize()

    override fun run(pid: Long, kind: JvmCommandKind, timeoutMillis: Long, maxBytes: Int): JvmCommandResult {
        if (pid <= 0 || timeoutMillis <= 0 || maxBytes <= 0) {
            return JvmCommandResult(kind, EvidenceAvailability.FAILED, failureCode = SupervisorFailureCode.COMMAND_FAILED)
        }
        val tool = resolveTool(toolName(kind))
            ?: return JvmCommandResult(
                kind,
                EvidenceAvailability.NOT_CONFIGURED,
                failureCode = SupervisorFailureCode.COMMAND_TOOL_MISSING,
            )
        val command = when (kind) {
            JvmCommandKind.THREAD_PRINT -> listOf(tool.toString(), pid.toString(), "Thread.print", "-l")
            JvmCommandKind.GC_HEAP_INFO -> listOf(tool.toString(), pid.toString(), "GC.heap_info")
            JvmCommandKind.VM_FLAGS -> listOf(tool.toString(), pid.toString(), "VM.flags")
            JvmCommandKind.JSTACK -> listOf(tool.toString(), "-l", pid.toString())
        }
        return try {
            val result = commandRunner.run(command, timeoutMillis, maxBytes)
            JvmCommandResult(
                kind = kind,
                availability = result.availability,
                exitCode = result.exitCode,
                timedOut = result.availability == EvidenceAvailability.TIMED_OUT,
                output = result.output,
                capturedBytes = result.capturedBytes,
                failureCode = result.failureCode,
            )
        } catch (_: Exception) {
            JvmCommandResult(kind, EvidenceAvailability.FAILED, failureCode = SupervisorFailureCode.COMMAND_FAILED)
        }
    }

    private fun resolveTool(name: String): Path? {
        val candidates = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf(toolDirectory.resolve("$name.exe"), toolDirectory.resolve(name))
        } else {
            listOf(toolDirectory.resolve(name), toolDirectory.resolve("$name.exe"))
        }
        return candidates.firstOrNull(Files::isRegularFile)
    }

    private fun toolName(kind: JvmCommandKind): String = when (kind) {
        JvmCommandKind.JSTACK -> "jstack"
        else -> "jcmd"
    }
}

public object JvmEvidenceAnalyzer {
    public fun analyze(results: List<JvmCommandResult>): JvmEvidenceV1 {
        val threadResults = results.filter {
            it.kind == JvmCommandKind.THREAD_PRINT || it.kind == JvmCommandKind.JSTACK
        }
        val availableThreads = threadResults.filter {
            it.availability == EvidenceAvailability.AVAILABLE && it.output != null
        }
        val signatures = availableThreads.map { stackSignature(it.output!!) }
        val stable = signatures.size >= 2 && signatures.distinct().size == 1
        val waiting = availableThreads.isNotEmpty() && availableThreads.all { hasWaitState(it.output!!) }
        val hot = stable && availableThreads.isNotEmpty() && availableThreads.all { hasRunnableState(it.output!!) }
        val deadlock = availableThreads.any { hasExplicitDeadlock(it.output!!) }
        val gcPressure = results.firstOrNull { it.kind == JvmCommandKind.GC_HEAP_INFO }
            ?.output
            ?.contains("GC_PRESSURE=true", ignoreCase = true)
        val availability = when {
            results.isEmpty() -> EvidenceAvailability.NOT_CONFIGURED
            results.any { it.availability == EvidenceAvailability.AVAILABLE } -> EvidenceAvailability.AVAILABLE
            results.any { it.availability == EvidenceAvailability.TIMED_OUT } -> EvidenceAvailability.TIMED_OUT
            else -> EvidenceAvailability.FAILED
        }
        return JvmEvidenceV1(
            availability = availability,
            stableHotStack = hot,
            stableWaitStack = stable && waiting,
            deadlockDetected = deadlock,
            gcPressure = gcPressure,
            threadDumpCount = availableThreads.size,
            results = results,
        )
    }

    private fun stackSignature(output: String): String = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("at ") || it.contains("Thread.State:") }
        .take(8)
        .joinToString("\n")

    private fun hasWaitState(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("thread.state: waiting") ||
            lower.contains("thread.state: blocked") ||
            lower.contains("parking to wait")
    }

    private fun hasRunnableState(output: String): Boolean =
        output.contains("Thread.State: RUNNABLE", ignoreCase = true)

    private fun hasExplicitDeadlock(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("found one java-level deadlock") ||
            (lower.contains("deadlock") && lower.contains("waiting to lock"))
    }
}

public class JvmEvidenceCollector(
    private val config: SupervisorConfigV1,
    private val runner: JvmCommandRunner,
    private val sleeper: SupervisorSleeper = SupervisorSleeper { millis -> Thread.sleep(millis) },
) {
    public fun capture(pid: Long): JvmEvidenceV1 {
        val results = ArrayList<JvmCommandResult>(config.threadDumpCount + 2)
        repeat(config.threadDumpCount) { index ->
            val threadDump = safeRun(pid, JvmCommandKind.THREAD_PRINT)
            results += if (
                threadDump.availability == EvidenceAvailability.NOT_CONFIGURED &&
                threadDump.failureCode == SupervisorFailureCode.COMMAND_TOOL_MISSING
            ) {
                safeRun(pid, JvmCommandKind.JSTACK)
            } else {
                threadDump
            }
            if (index + 1 < config.threadDumpCount) {
                try {
                    sleeper.sleepMillis(config.threadDumpIntervalMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return JvmEvidenceAnalyzer.analyze(results)
                }
            }
        }
        results += safeRun(pid, JvmCommandKind.GC_HEAP_INFO)
        results += safeRun(pid, JvmCommandKind.VM_FLAGS)
        return JvmEvidenceAnalyzer.analyze(results)
    }

    private fun safeRun(pid: Long, kind: JvmCommandKind): JvmCommandResult = try {
        runner.run(pid, kind, config.captureTimeoutMillis, config.maxCommandOutputBytes)
    } catch (_: Exception) {
        JvmCommandResult(kind, EvidenceAvailability.FAILED, failureCode = SupervisorFailureCode.COMMAND_FAILED)
    }
}
