package com.wingedsheep.gameserver.replay.verification

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
open class ProductionOfflineReplayVerifierV1(
    /** Repository root supplying curriculum + card authority; passed to the worker explicitly. */
    private val repositoryRoot: Path,
    /** Classpath owning the fixed worker entrypoint; never caller-supplied at verification time. */
    protected val workerClasspath: String = System.getProperty("java.class.path")
        ?: error("ProductionOfflineReplayVerifierV1 requires the JVM classpath"),
    /** Java executable ownership stays with the running JVM, never with the caller. */
    protected val javaExecutable: Path = Path.of(System.getProperty("java.home"))
        .resolve("bin")
        .let { bin -> if (System.getProperty("os.name").lowercase().contains("windows")) bin.resolve("java.exe") else bin.resolve("java") },
    /** Bounded wall-clock budget for one worker verification. */
    private val timeout: VerifierTimeoutV1 = VerifierTimeoutV1(),
    /** Worker JVM heap; bounded and owned here. */
    protected val workerMaxHeap: String = "-Xmx6g",
) : OfflineReplayVerifierV1 {

    override fun verify(trajectory: TrajectoryV1): OfflineReplayVerificationResultV1 {
        val workDirectory = Files.createTempDirectory("offline-replay-verifier-")
        val requestFile = workDirectory.resolve("request.json")
        val responseFile = workDirectory.resolve("response.json")
        val request = VerifierWorkerRequestV1.of(trajectory)
        Files.writeString(requestFile, VerifierWorkerProtocolV1Json.encodeRequest(request), StandardCharsets.UTF_8)

        val process = ProcessBuilder(
            listOf(
                javaExecutable.toString(),
                workerMaxHeap,
                "-cp",
                workerClasspath,
                WORKER_MAIN_CLASS,
                "--request",
                Base64.getEncoder().encodeToString(
                    Files.readAllBytes(requestFile),
                ),
                "--repository-root",
                repositoryRoot.toAbsolutePath().normalize().toString(),
                "--response",
                responseFile.toAbsolutePath().toString(),
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
        val workerOutput = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
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
        val result = runCatching {
            VerifierWorkerProtocolV1Json.decodeResult(Files.readString(responseFile, StandardCharsets.UTF_8))
        }.getOrElse { decodeFailure ->
            return failClosed(
                OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                "offline replay verifier worker result was malformed: ${decodeFailure.message}",
            )
        }

        return when (result.status) {
            VerifierWorkerStatusV1.UNAVAILABLE ->
                // Typed worker failure: fail closed with the worker's exact cause.
                OfflineReplayVerificationResultV1.Unavailable(
                    result.failureCode ?: OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                )

            VerifierWorkerStatusV1.VERIFIED ->
                boundResult(trajectory, result)
        }
    }

    /**
     * Request→result cross-binding: the fresh verification must belong to the exact requested
     * trajectory (trajectoryId, semantic episode id, replay content identity, replay action
     * count) before any VERIFIED result is surfaced.
     */
    protected open fun boundResult(
        trajectory: TrajectoryV1,
        result: VerifierWorkerResultV1,
    ): OfflineReplayVerificationResultV1 {
        val verified = checkNotNull(result.verifiedResult)
        val link = trajectory.episodeMetadata.compactReplayLink
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
    }
}

/** Bounded wall-clock budget for one worker verification. */
data class VerifierTimeoutV1(
    val duration: Long = 30,
    val unit: TimeUnit = TimeUnit.MINUTES,
) {
    init {
        require(duration > 0) { "Verifier timeout must be positive" }
    }
}
