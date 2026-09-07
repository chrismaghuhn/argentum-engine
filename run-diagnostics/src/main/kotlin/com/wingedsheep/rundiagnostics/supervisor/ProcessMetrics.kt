package com.wingedsheep.rundiagnostics.supervisor

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets.UTF_8

@Serializable
public data class ProcessMetricsV1(
    public val schemaVersion: Int = SupervisorSchema.PROCESS_METRICS_SCHEMA_VERSION,
    public val schemaIdentity: String = SupervisorSchema.PROCESS_METRICS_SCHEMA_IDENTITY,
    public val availability: EvidenceAvailability,
    public val cpuTimeNanos: Long? = null,
    public val rssBytes: Long? = null,
    public val threadCount: Long? = null,
    public val sampledAtElapsedNanos: Long? = null,
    public val failureCode: SupervisorFailureCode? = null,
) {
    init {
        require(schemaVersion == SupervisorSchema.PROCESS_METRICS_SCHEMA_VERSION) {
            "unsupported process metrics schemaVersion"
        }
        require(schemaIdentity == SupervisorSchema.PROCESS_METRICS_SCHEMA_IDENTITY) {
            "unsupported process metrics schemaIdentity"
        }
        requireNonNegative(cpuTimeNanos, "cpuTimeNanos")
        requireNonNegative(rssBytes, "rssBytes")
        requireNonNegative(threadCount, "threadCount")
        requireNonNegative(sampledAtElapsedNanos, "sampledAtElapsedNanos")
    }
}

public fun interface ProcessMetricsSampler {
    public fun sample(pid: Long): ProcessMetricsV1
}

public class LinuxProcessMetricsSampler(
    private val procRoot: Path = Path.of("/proc"),
    private val clockTicksPerSecond: Long = 100,
) : ProcessMetricsSampler {
    init {
        require(clockTicksPerSecond > 0) { "clockTicksPerSecond must be positive" }
    }

    override fun sample(pid: Long): ProcessMetricsV1 {
        if (pid <= 0) return unavailable(SupervisorFailureCode.METRICS_UNAVAILABLE)
        val directory = procRoot.resolve(pid.toString())
        if (!Files.isDirectory(directory)) return missing()
        return try {
            val stat = readBounded(directory.resolve("stat"))
            val status = readBounded(directory.resolve("status"))
            val fields = stat.substringAfterLast(") ").trim().split(Regex("\\s+"))
            require(fields.size > 21) { "incomplete proc stat" }
            val userTicks = fields[11].toLong()
            val systemTicks = fields[12].toLong()
            val cpuTicks = Math.addExact(userTicks, systemTicks)
            val cpuNanos = ticksToNanos(cpuTicks)
            val values = status.lines().associate { line ->
                val separator = line.indexOf(':')
                if (separator < 0) "" to "" else line.substring(0, separator) to line.substring(separator + 1).trim()
            }
            val threads = values["Threads"]?.toLong()
            val rssKilobytes = values["VmRSS"]?.removeSuffix(" kB")?.trim()?.toLong()
            ProcessMetricsV1(
                availability = EvidenceAvailability.AVAILABLE,
                cpuTimeNanos = cpuNanos,
                rssBytes = rssKilobytes?.let { Math.multiplyExact(it, 1_024) },
                threadCount = threads,
            )
        } catch (_: Exception) {
            ProcessMetricsV1(
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.METRICS_PARSE_FAILED,
            )
        }
    }

    private fun ticksToNanos(ticks: Long): Long {
        require(ticks >= 0) { "CPU ticks must be non-negative" }
        val wholeSeconds = ticks / clockTicksPerSecond
        val remainder = ticks % clockTicksPerSecond
        return Math.addExact(
            Math.multiplyExact(wholeSeconds, 1_000_000_000L),
            Math.multiplyExact(remainder, 1_000_000_000L) / clockTicksPerSecond,
        )
    }

    private fun readBounded(path: Path): String {
        val bytes = Files.newInputStream(path, StandardOpenOption.READ).use { input -> input.readNBytes(64 * 1024 + 1) }
        require(bytes.size <= 64 * 1024) { "proc file exceeds bound" }
        return bytes.toString(UTF_8)
    }

    private fun missing() = ProcessMetricsV1(
        availability = EvidenceAvailability.MISSING,
        failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
    )

    private fun unavailable(code: SupervisorFailureCode) = ProcessMetricsV1(
        availability = EvidenceAvailability.FAILED,
        failureCode = code,
    )
}

public class WindowsProcessMetricsSampler(
    private val commandRunner: BoundedCommandRunner,
    private val powershellExecutable: String = "powershell.exe",
) : ProcessMetricsSampler {
    override fun sample(pid: Long): ProcessMetricsV1 {
        if (pid <= 0) return ProcessMetricsV1(
            availability = EvidenceAvailability.FAILED,
            failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
        )
        val dollar = '$'
        val script = "${dollar}p = Get-Process -Id $pid -ErrorAction Stop; " +
            "[Console]::WriteLine(('{0}|{1}|{2}|{3}|{4}' -f ${dollar}p.Id, " +
            "([double]${dollar}p.CPU).ToString('R', [System.Globalization.CultureInfo]::InvariantCulture), " +
            "${dollar}p.WorkingSet64, ${dollar}p.Threads.Count, " +
            "${dollar}p.StartTime.ToUniversalTime().ToString('o')))"
        val result = commandRunner.run(
            command = listOf(powershellExecutable, "-NoProfile", "-NonInteractive", "-Command", script),
            timeoutMillis = 2_000,
            maxBytes = 4_096,
        )
        if (result.availability != EvidenceAvailability.AVAILABLE || result.output == null) {
            return ProcessMetricsV1(
                availability = result.availability,
                failureCode = result.failureCode ?: SupervisorFailureCode.METRICS_UNAVAILABLE,
            )
        }
        return try {
            val fields = result.output.trim().split('|', limit = 5)
            require(fields.size == 5 && fields[0].toLong() == pid)
            val cpuSeconds = fields[1].toDouble()
            val rssBytes = fields[2].toLong()
            val threadCount = fields[3].toLong()
            require(cpuSeconds.isFinite() && cpuSeconds >= 0 && rssBytes >= 0 && threadCount >= 0)
            ProcessMetricsV1(
                availability = EvidenceAvailability.AVAILABLE,
                cpuTimeNanos = (cpuSeconds * 1_000_000_000L).toLong(),
                rssBytes = rssBytes,
                threadCount = threadCount,
            )
        } catch (_: Exception) {
            ProcessMetricsV1(
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.METRICS_PARSE_FAILED,
            )
        }
    }
}

public class UnsupportedProcessMetricsSampler : ProcessMetricsSampler {
    override fun sample(pid: Long): ProcessMetricsV1 = ProcessMetricsV1(
        availability = EvidenceAvailability.NOT_CONFIGURED,
        failureCode = SupervisorFailureCode.METRICS_UNAVAILABLE,
    )
}

public object SystemProcessMetricsSampler {
    public fun create(commandRunner: BoundedCommandRunner = JdkBoundedCommandRunner()): ProcessMetricsSampler {
        return when (System.getProperty("os.name").lowercase()) {
            in listOf("linux", "unix", "freebsd") -> LinuxProcessMetricsSampler()
            in listOf("windows 10", "windows 11", "windows") -> WindowsProcessMetricsSampler(commandRunner)
            else -> UnsupportedProcessMetricsSampler()
        }
    }
}
