package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.DiagnosticsSchema
import com.wingedsheep.rundiagnostics.RunStatusCodec
import com.wingedsheep.rundiagnostics.RunStatusV1
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

public sealed interface StatusReadResult {
    public data class Available(
        public val status: RunStatusV1,
    ) : StatusReadResult

    public data class Unavailable(
        public val availability: EvidenceAvailability,
        public val code: SupervisorFailureCode,
    ) : StatusReadResult
}

/** Reads only the declared bounded sidecar path; temporary files are never considered status. */
public class StatusSidecarReader(
    path: Path,
    private val maxBytes: Int = DiagnosticsSchema.DEFAULT_MAX_SERIALIZED_STATUS_BYTES,
) {
    private val path = path.toAbsolutePath().normalize()

    init {
        require(maxBytes in 1..4 * 1024 * 1024) { "maxBytes is outside the bounded range" }
    }

    public fun read(): StatusReadResult {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return StatusReadResult.Unavailable(EvidenceAvailability.MISSING, SupervisorFailureCode.STATUS_MISSING)
        }
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            return StatusReadResult.Unavailable(
                EvidenceAvailability.FAILED,
                SupervisorFailureCode.STATUS_NOT_REGULAR_FILE,
            )
        }
        val bytes = try {
            Files.newInputStream(path).use { input -> input.readNBytes(maxBytes + 1) }
        } catch (_: Exception) {
            return StatusReadResult.Unavailable(EvidenceAvailability.FAILED, SupervisorFailureCode.STATUS_READ_FAILED)
        }
        if (bytes.size > maxBytes) {
            return StatusReadResult.Unavailable(EvidenceAvailability.FAILED, SupervisorFailureCode.STATUS_TOO_LARGE)
        }
        return try {
            StatusReadResult.Available(RunStatusCodec.decode(bytes, maxBytes))
        } catch (_: Exception) {
            StatusReadResult.Unavailable(EvidenceAvailability.FAILED, SupervisorFailureCode.STATUS_SCHEMA_INVALID)
        }
    }
}

public data class ProcessHandleObservation(
    public val pid: Long,
    public val alive: Boolean,
    public val startInstant: Instant?,
    public val commandLine: String? = null,
)

public interface ProcessHandleSource {
    public fun observe(pid: Long): ProcessHandleObservation?
}

public data class ProcessIdentityResult(
    public val liveness: ProcessLiveness,
    public val observation: ProcessHandleObservation?,
)

public class ProcessIdentityChecker(
    private val source: ProcessHandleSource,
    private val startToleranceMillis: Long = 2_000,
) {
    init {
        require(startToleranceMillis in 0..SupervisorSchema.MAX_DURATION_MILLIS) {
            "startToleranceMillis is outside the bounded range"
        }
    }

    public fun observe(pid: Long, expectedStart: Instant?): ProcessIdentityResult {
        if (pid <= 0) return ProcessIdentityResult(ProcessLiveness.UNKNOWN, null)
        val observation = try {
            source.observe(pid)
        } catch (_: Exception) {
            return ProcessIdentityResult(ProcessLiveness.UNKNOWN, null)
        }
            ?: return ProcessIdentityResult(ProcessLiveness.PROCESS_EXITED, null)
        if (observation.pid != pid) {
            return ProcessIdentityResult(ProcessLiveness.IDENTITY_MISMATCH, observation)
        }
        if (!observation.alive) {
            return ProcessIdentityResult(ProcessLiveness.PROCESS_EXITED, observation)
        }
        if (expectedStart != null) {
            val actualStart = observation.startInstant
                ?: return ProcessIdentityResult(ProcessLiveness.UNKNOWN, observation)
            val difference = kotlin.math.abs(Duration.between(expectedStart, actualStart).toMillis())
            if (difference > startToleranceMillis) {
                return ProcessIdentityResult(ProcessLiveness.IDENTITY_MISMATCH, observation)
            }
        }
        return ProcessIdentityResult(ProcessLiveness.ALIVE, observation)
    }
}

public class JdkProcessHandleSource : ProcessHandleSource {
    override fun observe(pid: Long): ProcessHandleObservation? {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        val info = handle.info()
        return ProcessHandleObservation(
            pid = pid,
            alive = handle.isAlive,
            startInstant = info.startInstant().orElse(null),
            commandLine = info.commandLine().orElse(null),
        )
    }
}
