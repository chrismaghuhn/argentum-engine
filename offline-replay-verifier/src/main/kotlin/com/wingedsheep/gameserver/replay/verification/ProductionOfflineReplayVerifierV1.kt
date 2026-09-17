package com.wingedsheep.gameserver.replay.verification

import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.trainer.actor.OfflineReplayFailureCodeV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerificationResultV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerifierV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Production [OfflineReplayVerifierV1]: a process-isolated, same-revision-only verifier that
 * reconstructs a transported trajectory inside a bounded worker JVM and returns fresh A4
 * evidence bound to the exact requested trajectory identity.
 *
 * Hard properties:
 * - the worker entrypoint, Java invocation shape, and classpath are owned here (never
 *   caller-supplied, never shell-interpreted, never PATH-resolved);
 * - bounded timeout: a timeout is a typed fail-closed result, never NO_INDEPENDENT_PROOF and
 *   never VERIFIED;
 * - request→result cross-binding: the worker's verified identity (trajectoryId, semantic
 *   episode id, replay content identity, replay action count) must equal the requested
 *   trajectory's durable identity before any VERIFIED result is surfaced;
 * - no caching, no memoization, no binding reuse across trajectories.
 */
class ProductionOfflineReplayVerifierV1 internal constructor(
    /** Repository root supplying curriculum + card authority; passed to the worker explicitly. */
    private val repositoryRoot: Path,
    /** Bounded wall-clock budget for one worker verification. */
    private val timeout: VerifierTimeoutV1 = VerifierTimeoutV1(),
    /** Test-only launch seam (classpath/executable/heap); null in production construction. */
    private val launchOverrides: WorkerLaunchOverridesV1? = null,
) : OfflineReplayVerifierV1 {

    /** Production construction: only the repository root and bounded limits are configurable. */
    constructor(repositoryRoot: Path) : this(repositoryRoot, VerifierTimeoutV1(), null)

    /** Focused-test seam for the request/result binding contract (not a verification path). */
    internal fun invokeBoundResultForTest(
        trajectory: TrajectoryV1,
        result: VerifierWorkerResultV1,
    ): OfflineReplayVerificationResultV1 = boundResult(trajectory, result)

    override fun verify(trajectory: TrajectoryV1): OfflineReplayVerificationResultV1 {
        val workDirectory = Files.createTempDirectory("offline-replay-verifier-")
        try {
            return verifyInWorkDirectory(workDirectory, trajectory)
        } finally {
            // The workspace carries the full canonical claimant trajectory; it must never leak
            // across verifications or accumulate on disk (timeout/crash included).
            runCatching {
                Files.walk(workDirectory).sorted(java.util.Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun verifyInWorkDirectory(
        workDirectory: Path,
        trajectory: TrajectoryV1,
    ): OfflineReplayVerificationResultV1 {
        val responseFile = workDirectory.resolve("response.json")
        val request = VerifierWorkerRequestV1.of(trajectory)
        val requestBytes = VerifierWorkerProtocolV1Json.encodeRequest(request).toByteArray(StandardCharsets.UTF_8)
        if (requestBytes.size > WORKER_PROTOCOL_MAX_REQUEST_BYTES) {
            // Bounded protocol input: a trajectory that cannot travel within the protocol budget
            // cannot be verified by this verifier version. Fail closed, never truncate.
            return failClosed(
                OfflineReplayFailureCodeV1.UNSUPPORTED_RECONSTRUCTION,
                "canonical claimant trajectory exceeds the worker protocol request budget " +
                    "(${requestBytes.size} > $WORKER_PROTOCOL_MAX_REQUEST_BYTES bytes)",
            )
        }
        // Java @argfile launch: all arguments (including the module classpath) travel inside a
        // file, keeping the OS command line short and the launch shape fixed. The request is a
        // plain file reference, not an inline blob.
        val argFile = workDirectory.resolve("worker.args")
        val requestFile = workDirectory.resolve("request.json")
        Files.write(requestFile, requestBytes)
        val workerMaxHeap = launchOverrides?.workerMaxHeap ?: PRODUCTION_WORKER_MAX_HEAP
        val workerClasspath = launchOverrides?.workerClasspath
            ?: System.getProperty("java.class.path")
            ?: error("ProductionOfflineReplayVerifierV1 requires the JVM classpath")
        val javaExecutable: Path = launchOverrides?.javaExecutable
            ?: Path.of(System.getProperty("java.home")).resolve("bin").let { bin ->
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    bin.resolve("java.exe")
                } else {
                    bin.resolve("java")
                }
            }
        Files.writeString(
            argFile,
            listOf(
                workerMaxHeap,
                "-cp",
                quoteArgFile(workerClasspath),
                WORKER_MAIN_CLASS,
                "--request",
                quoteArgFile(requestFile.toAbsolutePath().toString()),
                "--repository-root",
                quoteArgFile(repositoryRoot.toAbsolutePath().normalize().toString()),
                "--response",
                quoteArgFile(responseFile.toAbsolutePath().toString()),
            ).joinToString("\n"),
            StandardCharsets.UTF_8,
        )

        val process = ProcessBuilder(
            listOf(
                javaExecutable.toString(),
                "@" + argFile.toAbsolutePath().toString(),
            ),
        )
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeout.duration, timeout.unit)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
            return failClosed(
                OfflineReplayFailureCodeV1.VERIFIER_TIMEOUT,
                "offline replay verifier exceeded its bounded timeout " +
                    "(${timeout.duration} ${timeout.unit})",
            )
        }
        val workerOutput = readBoundedWorkerOutput(process)
        if (process.exitValue() != 0) {
            return failClosed(
                OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                "offline replay verifier worker crashed with exit code ${process.exitValue()}" +
                    workerOutput.takeLast(512).let { ": $it" },
            )
        }
        if (!Files.exists(responseFile)) {
            return failClosed(
                OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                "offline replay verifier worker produced no parseable result file" +
                    workerOutput.takeLast(512).let { ": $it" },
            )
        }
        val responseBytes = runCatching { Files.readAllBytes(responseFile) }.getOrElse { readFailure ->
            return failClosed(
                OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                "offline replay verifier result file was unreadable: ${readFailure.message}",
            )
        }
        if (responseBytes.size > WORKER_PROTOCOL_MAX_RESPONSE_BYTES) {
            return failClosed(
                OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                "offline replay verifier result exceeds the protocol response budget " +
                    "(${responseBytes.size} > $WORKER_PROTOCOL_MAX_RESPONSE_BYTES bytes)",
            )
        }
        val result = runCatching {
            VerifierWorkerProtocolV1Json.decodeResult(String(responseBytes, StandardCharsets.UTF_8))
        }.getOrElse { decodeFailure ->
            return failClosed(
                OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                "offline replay verifier worker result was malformed: ${decodeFailure.message}",
            )
        }

        return when (result.status) {
            VerifierWorkerStatusV1.UNAVAILABLE -> {
                // Typed worker failure: fail closed with the worker's exact cause. Diagnostics
                // are observability only; the typed failure code is the trust state.
                val code = result.failureCode ?: OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE
                runCatching {
                    System.err.println(
                        "[offline-replay-verifier] worker unavailable code=$code " +
                            "diagnostics=${result.diagnostics}",
                    )
                }
                OfflineReplayVerificationResultV1.Unavailable(code)
            }

            VerifierWorkerStatusV1.VERIFIED ->
                boundResult(trajectory, result)
        }
    }

    /**
     * Request→result cross-binding: the fresh verification must belong to the exact requested
     * trajectory (trajectoryId, semantic episode id, replay content identity, replay action
     * count) before any VERIFIED result is surfaced.
     */
    private fun boundResult(
        trajectory: TrajectoryV1,
        result: VerifierWorkerResultV1,
    ): OfflineReplayVerificationResultV1 {
        val verified = checkNotNull(result.verifiedResult)
        val link = trajectory.episodeMetadata.compactReplayLink
        val binding = verified.replayTrajectoryBinding
        val verification = binding.verificationBinding.verification
        val mismatches = buildList {
            if (verified.verifiedTrajectoryId != trajectory.trajectoryId) {
                add("trajectoryId(${verified.verifiedTrajectoryId} != ${trajectory.trajectoryId})")
            }
            if (verified.verifiedSemanticEpisodeId != trajectory.semanticEpisodeId) {
                add("semanticEpisodeId(${verified.verifiedSemanticEpisodeId} != ${trajectory.semanticEpisodeId})")
            }
            if (verified.verifiedReplayContentIdentity != link.replayContentIdentity) {
                add("replayContentIdentity(${verified.verifiedReplayContentIdentity} != ${link.replayContentIdentity})")
            }
            if (verified.verifiedReplayActionCount != link.replayActionCount) {
                add("replayActionCount(${verified.verifiedReplayActionCount} != ${link.replayActionCount})")
            }
            // P2: the binding itself must be an EXACT, complete-range claim consistent with the
            // verified outer identity — a UNVERIFIED/DIVERGED or internally inconsistent binding
            // never surfaces as VERIFIED.
            if (verification.fidelity != ReplayFidelity.EXACT) {
                add("bindingFidelity(${verification.fidelity} != EXACT)")
            }
            if (!verification.completeRangeVerified) {
                add("completeRangeVerified(false)")
            }
            if (binding.verificationBinding.replayContentIdentity.value != verified.verifiedReplayContentIdentity) {
                add(
                    "bindingReplayContentIdentity(${binding.verificationBinding.replayContentIdentity.value} " +
                        "!= ${verified.verifiedReplayContentIdentity})",
                )
            }
            if (binding.chosenInputBinding.replayContentIdentity.value != verified.verifiedReplayContentIdentity) {
                add(
                    "chosenInputReplayContentIdentity(${binding.chosenInputBinding.replayContentIdentity.value} " +
                        "!= ${verified.verifiedReplayContentIdentity})",
                )
            }
            if (verification.replayActionCount != verified.verifiedReplayActionCount) {
                add(
                    "bindingReplayActionCount(${verification.replayActionCount} " +
                        "!= ${verified.verifiedReplayActionCount})",
                )
            }
            if (binding.chosenInputBinding.replayActionCount != verified.verifiedReplayActionCount) {
                add(
                    "chosenInputReplayActionCount(${binding.chosenInputBinding.replayActionCount} " +
                        "!= ${verified.verifiedReplayActionCount})",
                )
            }
        }
        if (mismatches.isNotEmpty()) {
            return failClosed(
                OfflineReplayFailureCodeV1.TRAJECTORY_IDENTITY_MISMATCH,
                "offline replay verifier result is not bound to the requested trajectory: " +
                    mismatches.joinToString(", "),
            )
        }
        return OfflineReplayVerificationResultV1.Verified(verified.replayTrajectoryBinding)
    }

    /** Java @argfile quoting: double-quote, backslash-escape embedded quotes/backslashes. */
    private fun quoteArgFile(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Bounded worker-output read: stops at [WORKER_PROTOCOL_MAX_WORKER_OUTPUT_BYTES] so a
     * misbehaving worker cannot balloon the verifier's memory. Excess is simply not read; the
     * truncated tail is diagnostics only.
     */
    private fun readBoundedWorkerOutput(process: Process): String {
        val bytes = ByteArray(WORKER_PROTOCOL_MAX_WORKER_OUTPUT_BYTES)
        var read = 0
        while (read < bytes.size) {
            val chunk = process.inputStream.read(bytes, read, bytes.size - read)
            if (chunk < 0) break
            read += chunk
        }
        return String(bytes, 0, read, StandardCharsets.UTF_8)
    }

    private fun failClosed(
        code: OfflineReplayFailureCodeV1,
        diagnostic: String,
    ): OfflineReplayVerificationResultV1 =
        OfflineReplayVerificationResultV1.Unavailable(code).also {
            // Diagnostics are observability only; the typed failure code is the trust state.
            runCatching { System.err.println("[offline-replay-verifier] $diagnostic") }
        }

    companion object {
        const val WORKER_MAIN_CLASS: String =
            "com.wingedsheep.gameserver.replay.verification.TransportedReplayVerifierWorkerMain"

        /** Bounded protocol I/O budgets (KA06_02_REMEDIATION_01): oversized messages fail closed. */
        const val WORKER_PROTOCOL_MAX_REQUEST_BYTES: Int = 32 * 1024 * 1024
        const val WORKER_PROTOCOL_MAX_RESPONSE_BYTES: Int = 128 * 1024 * 1024
        const val WORKER_PROTOCOL_MAX_WORKER_OUTPUT_BYTES: Int = 64 * 1024

        /** Hard-owned worker heap; not caller-configurable in production construction. */
        const val PRODUCTION_WORKER_MAX_HEAP: String = "-Xmx6g"
    }
}

/**
 * Test-only worker launch seam. Production construction never supplies this; it exists so the
 * focused suite can pin timeout/crash behaviour without spawning real episodes.
 */
data class WorkerLaunchOverridesV1(
    val workerClasspath: String,
    val javaExecutable: Path,
    val workerMaxHeap: String,
)

/** Bounded wall-clock budget for one worker verification. */
data class VerifierTimeoutV1(
    val duration: Long = 30,
    val unit: TimeUnit = TimeUnit.MINUTES,
) {
    init {
        require(duration > 0) { "Verifier timeout must be positive" }
    }
}
