package com.wingedsheep.rundiagnostics.supervisor

public enum class SupervisorCliFailureCode {
    MISSING_ARGUMENT,
    UNKNOWN_ARGUMENT,
    INVALID_ARGUMENT,
    HELP_REQUESTED,
}

public class SupervisorCliException(
    public val code: SupervisorCliFailureCode,
) : IllegalArgumentException("supervisor CLI argument error: $code")

public object SupervisorCli {
    public fun parse(args: Array<String>): SupervisorConfigV1 {
        var pid: Long? = null
        var statusPath: String? = null
        var diagnosticsDirectory: String? = null
        var heartbeatTimeout = SupervisorSchema.DEFAULT_HEARTBEAT_TIMEOUT_MILLIS
        var usefulTimeout = SupervisorSchema.DEFAULT_USEFUL_PROGRESS_TIMEOUT_MILLIS
        var sampleInterval = SupervisorSchema.DEFAULT_SAMPLE_INTERVAL_MILLIS
        var captureCooldown = SupervisorSchema.DEFAULT_CAPTURE_COOLDOWN_MILLIS
        var maxBundles = SupervisorSchema.DEFAULT_MAX_DIAGNOSTIC_BUNDLES
        var maxHistory = SupervisorSchema.DEFAULT_MAX_HISTORY_SAMPLES
        var dumpCount = SupervisorSchema.DEFAULT_THREAD_DUMP_COUNT
        var dumpInterval = SupervisorSchema.DEFAULT_THREAD_DUMP_INTERVAL_MILLIS
        var captureTimeout = SupervisorSchema.DEFAULT_CAPTURE_TIMEOUT_MILLIS
        var maxCommandBytes = SupervisorSchema.DEFAULT_MAX_COMMAND_OUTPUT_BYTES
        var maxBundleBytes = SupervisorSchema.DEFAULT_MAX_BUNDLE_BYTES
        var expectedStart: String? = null
        var jvmToolDirectory: String? = null
        var cpuActiveFraction = 0.5
        var cpuLowFraction = 0.1
        var once = false
        val artifacts = ArrayList<String>()

        var index = 0
        while (index < args.size) {
            when (val argument = args[index]) {
                "--help", "-h" -> throw SupervisorCliException(SupervisorCliFailureCode.HELP_REQUESTED)
                "--once" -> once = true
                "--pid" -> pid = nextLong(args, ++index, argument)
                "--status" -> statusPath = nextString(args, ++index, argument)
                "--diagnostics-dir" -> diagnosticsDirectory = nextString(args, ++index, argument)
                "--heartbeat-timeout-ms" -> heartbeatTimeout = nextLong(args, ++index, argument)
                "--useful-progress-timeout-ms" -> usefulTimeout = nextLong(args, ++index, argument)
                "--sample-interval-ms" -> sampleInterval = nextLong(args, ++index, argument)
                "--capture-cooldown-ms" -> captureCooldown = nextLong(args, ++index, argument)
                "--max-bundles" -> maxBundles = nextInt(args, ++index, argument)
                "--max-history-samples" -> maxHistory = nextInt(args, ++index, argument)
                "--thread-dumps" -> dumpCount = nextInt(args, ++index, argument)
                "--thread-dump-interval-ms" -> dumpInterval = nextLong(args, ++index, argument)
                "--capture-timeout-ms" -> captureTimeout = nextLong(args, ++index, argument)
                "--max-command-bytes" -> maxCommandBytes = nextInt(args, ++index, argument)
                "--max-bundle-bytes" -> maxBundleBytes = nextInt(args, ++index, argument)
                "--expected-start" -> expectedStart = nextString(args, ++index, argument)
                "--jvm-tool-dir" -> jvmToolDirectory = nextString(args, ++index, argument)
                "--cpu-active-fraction" -> cpuActiveFraction = nextDouble(args, ++index, argument)
                "--cpu-low-fraction" -> cpuLowFraction = nextDouble(args, ++index, argument)
                "--artifact" -> artifacts += nextString(args, ++index, argument)
                else -> throw SupervisorCliException(SupervisorCliFailureCode.UNKNOWN_ARGUMENT)
            }
            index++
        }

        val requiredPid = pid ?: throw SupervisorCliException(SupervisorCliFailureCode.MISSING_ARGUMENT)
        val requiredStatusPath = statusPath ?: throw SupervisorCliException(SupervisorCliFailureCode.MISSING_ARGUMENT)
        val requiredDiagnosticsDirectory = diagnosticsDirectory
            ?: throw SupervisorCliException(SupervisorCliFailureCode.MISSING_ARGUMENT)
        return try {
            SupervisorConfigV1(
                targetPid = requiredPid,
                statusPath = requiredStatusPath,
                diagnosticsDirectory = requiredDiagnosticsDirectory,
                heartbeatTimeoutMillis = heartbeatTimeout,
                usefulProgressTimeoutMillis = usefulTimeout,
                sampleIntervalMillis = sampleInterval,
                diagnosticCaptureCooldownMillis = captureCooldown,
                maxDiagnosticBundles = maxBundles,
                maxHistorySamples = maxHistory,
                threadDumpCount = dumpCount,
                threadDumpIntervalMillis = dumpInterval,
                captureTimeoutMillis = captureTimeout,
                maxCommandOutputBytes = maxCommandBytes,
                maxBundleBytes = maxBundleBytes,
                safeArtifactPaths = artifacts,
                jvmToolDirectory = jvmToolDirectory,
                expectedProcessStartWallClock = expectedStart,
                cpuActiveFraction = cpuActiveFraction,
                cpuLowFraction = cpuLowFraction,
                once = once,
            )
        } catch (_: Exception) {
            throw SupervisorCliException(SupervisorCliFailureCode.INVALID_ARGUMENT)
        }
    }

    public fun usage(): String = """
        Usage: run-diagnostics --pid PID --status PATH --diagnostics-dir PATH [options]
          --once                         poll once and exit
          --heartbeat-timeout-ms N       heartbeat stale threshold
          --useful-progress-timeout-ms N useful progress stale threshold
          --sample-interval-ms N         observation interval
          --capture-cooldown-ms N        diagnostic capture cooldown
          --max-bundles N                retained bundle count
          --thread-dumps N               bounded thread dump count
          --thread-dump-interval-ms N   interval between dumps
          --capture-timeout-ms N         per-command timeout
          --max-command-bytes N          per-command output bound
          --max-bundle-bytes N           bundle byte bound
          --expected-start ISO_INSTANT   expected target process start
          --jvm-tool-dir PATH            JDK tool directory
          --cpu-active-fraction N        CPU fraction classified as active
          --cpu-low-fraction N           CPU fraction classified as low
          --artifact PATH                explicitly safe artifact path (repeatable)
          --help                         show this message
    """.trimIndent()

    private fun nextString(args: Array<String>, index: Int, option: String): String {
        if (index >= args.size || args[index].startsWith("--")) {
            throw SupervisorCliException(SupervisorCliFailureCode.MISSING_ARGUMENT)
        }
        return args[index]
    }

    private fun nextLong(args: Array<String>, index: Int, option: String): Long = try {
        nextString(args, index, option).toLong()
    } catch (_: Exception) {
        throw SupervisorCliException(SupervisorCliFailureCode.INVALID_ARGUMENT)
    }

    private fun nextInt(args: Array<String>, index: Int, option: String): Int = try {
        nextString(args, index, option).toInt()
    } catch (_: Exception) {
        throw SupervisorCliException(SupervisorCliFailureCode.INVALID_ARGUMENT)
    }

    private fun nextDouble(args: Array<String>, index: Int, option: String): Double = try {
        nextString(args, index, option).toDouble()
    } catch (_: Exception) {
        throw SupervisorCliException(SupervisorCliFailureCode.INVALID_ARGUMENT)
    }
}
