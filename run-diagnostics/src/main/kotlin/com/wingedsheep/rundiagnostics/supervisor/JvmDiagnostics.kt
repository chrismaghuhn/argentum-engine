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
        val parsedDumps = availableThreads.map { parseThreadDump(it.output!!) }
        val stableHotPatterns = stablePatterns(parsedDumps, ThreadState.RUNNABLE)
        val stableWaitPatterns = stablePatterns(parsedDumps, ThreadState.WAITING)
        val ambiguousThreadStateEvidence = stableHotPatterns.size > 1 ||
            stableWaitPatterns.size > 1 ||
            (stableHotPatterns.isNotEmpty() && stableWaitPatterns.isNotEmpty())
        val deadlock = availableThreads.any { hasExplicitDeadlock(it.output!!) }
        val availability = when {
            results.isEmpty() -> EvidenceAvailability.NOT_CONFIGURED
            results.any { it.availability == EvidenceAvailability.AVAILABLE } -> EvidenceAvailability.AVAILABLE
            results.any { it.availability == EvidenceAvailability.TIMED_OUT } -> EvidenceAvailability.TIMED_OUT
            else -> EvidenceAvailability.FAILED
        }
        return JvmEvidenceV1(
            availability = availability,
            stableHotStack = stableHotPatterns.isNotEmpty(),
            stableWaitStack = stableWaitPatterns.isNotEmpty(),
            ambiguousThreadStateEvidence = ambiguousThreadStateEvidence,
            deadlockDetected = deadlock,
            gcPressure = null,
            threadDumpCount = availableThreads.size,
            results = results,
        )
    }

    private fun stablePatterns(
        dumps: List<List<ThreadSnapshot>>,
        state: ThreadState,
    ): Set<ThreadSnapshot> {
        if (dumps.size < 2) return emptySet()
        val firstDumpCandidates = dumps.first().filter { it.state == state }.toSet()
        return firstDumpCandidates.filter { candidate ->
            dumps.drop(1).all { dump -> dump.contains(candidate) }
        }.toSet()
    }

    /** Parses only a bounded prefix of a standard jcmd/jstack thread dump. */
    private fun parseThreadDump(output: String): List<ThreadSnapshot> {
        val snapshots = ArrayList<ThreadSnapshot>()
        var identity: String? = null
        var state: ThreadState? = null
        var frames = ArrayList<String>()
        var unheadedOrdinal = 0

        fun flush() {
            val currentIdentity = identity
            val currentState = state
            if (currentIdentity != null && currentState != null && frames.isNotEmpty()) {
                snapshots += ThreadSnapshot(currentIdentity, currentState, frames.toList())
            }
            identity = null
            state = null
            frames = ArrayList()
        }

        output.lineSequence().take(MAX_DUMP_LINES).forEach { rawLine ->
            val line = rawLine.trim()
            val headerIdentity = parseThreadHeader(line)
            if (headerIdentity != null) {
                flush()
                identity = headerIdentity
                return@forEach
            }
            if (line.regionMatches(0, THREAD_STATE_PREFIX, 0, THREAD_STATE_PREFIX.length, ignoreCase = true)) {
                if (identity == null) identity = "unheaded-${unheadedOrdinal++}"
                state = parseThreadState(line.substringAfter(':').trim())
                return@forEach
            }
            if (state != null && line.startsWith("at ")) {
                if (frames.size < MAX_STACK_FRAMES) frames += line.take(MAX_FRAME_LENGTH)
                return@forEach
            }
            // Keep compatibility with short synthetic dumps that omit the quoted header.
            if (identity == null && line.isNotEmpty()) identity = line.take(MAX_IDENTITY_LENGTH)
        }
        flush()
        return snapshots
    }

    private fun parseThreadHeader(line: String): String? {
        if (!line.startsWith('"')) return null
        val closingQuote = line.indexOf('"', startIndex = 1)
        if (closingQuote <= 1) return null
        val name = line.substring(1, closingQuote).take(MAX_IDENTITY_LENGTH)
        val suffix = line.substring(closingQuote + 1)
        val threadNumber = THREAD_NUMBER_REGEX.find(suffix)?.groupValues?.get(1)
        return if (threadNumber == null) name else "$name#$threadNumber"
    }

    private fun parseThreadState(value: String): ThreadState {
        val upper = value.uppercase()
        return when {
            upper.startsWith("RUNNABLE") -> ThreadState.RUNNABLE
            upper.startsWith("WAITING") || upper.startsWith("TIMED_WAITING") ||
                upper.startsWith("BLOCKED") -> ThreadState.WAITING
            else -> ThreadState.OTHER
        }
    }

    private fun hasExplicitDeadlock(output: String): Boolean {
        val lower = output.lowercase()
        return lower.contains("found one java-level deadlock") ||
            (lower.contains("deadlock") && lower.contains("waiting to lock"))
    }

    private data class ThreadSnapshot(
        val identity: String,
        val state: ThreadState,
        val frames: List<String>,
    )

    private enum class ThreadState {
        RUNNABLE,
        WAITING,
        OTHER,
    }

    private const val MAX_DUMP_LINES = 4_096
    private const val MAX_STACK_FRAMES = 64
    private const val MAX_FRAME_LENGTH = 1_024
    private const val MAX_IDENTITY_LENGTH = 256
    private const val THREAD_STATE_PREFIX = "java.lang.Thread.State:"
    private val THREAD_NUMBER_REGEX = Regex("""^\s+#(\d+)\b""")
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
