package com.wingedsheep.gameserver.replay.verification

import com.wingedsheep.gym.trainer.actor.OfflineReplayFailureCodeV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerificationResultV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Versioned canonical-JSON protocol between the production offline replay verifier and its
 * bounded worker process (KA06 _02).
 *
 * Transport rules:
 * - bounded inputs: one canonical-JSON request carrying the claimant trajectory;
 * - bounded outputs: one canonical-JSON result carrying a typed status, the verified request
 *   identity, and optional bounded diagnostics;
 * - no Java serialization, no arbitrary class loading, no raw game state across the boundary;
 * - the worker entrypoint and Java invocation shape are owned by [ProcessWorkerLauncherV1],
 *   never by the caller.
 */
const val OFFLINE_REPLAY_VERIFIER_WORKER_PROTOCOL_V1: Int = 1
const val OFFLINE_REPLAY_VERIFIER_WORKER_SCHEMA_IDENTITY: String =
    "argentum-ml-offline-replay-verifier-worker@v1"

@Serializable
data class VerifierWorkerRequestV1(
    val version: Int = OFFLINE_REPLAY_VERIFIER_WORKER_PROTOCOL_V1,
    val schemaIdentity: String = OFFLINE_REPLAY_VERIFIER_WORKER_SCHEMA_IDENTITY,
    /** Base64 of the canonical JSON encoding of the claimant trajectory. */
    val claimantTrajectoryBase64: String,
) {
    fun claimantTrajectory(): TrajectoryV1 {
        val canonical = String(
            Base64.getDecoder().decode(claimantTrajectoryBase64),
            StandardCharsets.UTF_8,
        )
        return A3SemanticJson.strictJson.decodeFromString(TrajectoryV1.serializer(), canonical)
    }

    companion object {
        fun of(trajectory: TrajectoryV1): VerifierWorkerRequestV1 {
            val canonical = A3SemanticJson.canonicalJson(
                A3SemanticJson.strictJson.encodeToJsonElement(TrajectoryV1.serializer(), trajectory),
            )
            return VerifierWorkerRequestV1(
                claimantTrajectoryBase64 = Base64.getEncoder()
                    .encodeToString(canonical.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }

    init {
        require(version == OFFLINE_REPLAY_VERIFIER_WORKER_PROTOCOL_V1) {
            "Unsupported verifier worker protocol version: $version"
        }
        require(schemaIdentity == OFFLINE_REPLAY_VERIFIER_WORKER_SCHEMA_IDENTITY) {
            "Unsupported verifier worker schema identity: $schemaIdentity"
        }
    }
}

@Serializable
data class VerifierWorkerResultV1(
    val version: Int = OFFLINE_REPLAY_VERIFIER_WORKER_PROTOCOL_V1,
    val schemaIdentity: String = OFFLINE_REPLAY_VERIFIER_WORKER_SCHEMA_IDENTITY,
    val status: VerifierWorkerStatusV1,
    /** Present when and only when [status] is [VerifierWorkerStatusV1.VERIFIED]. */
    val verifiedResult: VerifiedWorkerVerificationV1? = null,
    /** Typed fail-closed code; present when and only when [status] is UNAVAILABLE. */
    val failureCode: OfflineReplayFailureCodeV1? = null,
    /** Bounded diagnostics; never carries hidden game state. */
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(version == OFFLINE_REPLAY_VERIFIER_WORKER_PROTOCOL_V1) {
            "Unsupported verifier worker protocol version: $version"
        }
        require(schemaIdentity == OFFLINE_REPLAY_VERIFIER_WORKER_SCHEMA_IDENTITY) {
            "Unsupported verifier worker schema identity: $schemaIdentity"
        }
        require((status == VerifierWorkerStatusV1.VERIFIED) == (verifiedResult != null)) {
            "Worker result status and verified payload disagree"
        }
        require((status == VerifierWorkerStatusV1.UNAVAILABLE) == (failureCode != null)) {
            "Worker result status and failure code disagree"
        }
    }

    fun toVerificationResult(): OfflineReplayVerificationResultV1 = when (status) {
        VerifierWorkerStatusV1.VERIFIED ->
            OfflineReplayVerificationResultV1.Verified(checkNotNull(verifiedResult).replayTrajectoryBinding)

        VerifierWorkerStatusV1.UNAVAILABLE ->
            OfflineReplayVerificationResultV1.Unavailable(checkNotNull(failureCode))
    }
}

@Serializable
enum class VerifierWorkerStatusV1 {
    VERIFIED,
    UNAVAILABLE,
}

/** The verified request identity and the fresh binding, cross-bound by the worker itself. */
@Serializable
data class VerifiedWorkerVerificationV1(
    val verifiedTrajectoryId: String,
    val verifiedSemanticEpisodeId: String,
    val verifiedReplayContentIdentity: String,
    val verifiedReplayActionCount: Int,
    val replayTrajectoryBinding: com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1,
)

object VerifierWorkerProtocolV1Json {
    fun encodeRequest(request: VerifierWorkerRequestV1): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(VerifierWorkerRequestV1.serializer(), request),
    )

    fun decodeRequest(encoded: String): VerifierWorkerRequestV1 =
        A3SemanticJson.strictJson.decodeFromString(VerifierWorkerRequestV1.serializer(), encoded)

    fun encodeResult(result: VerifierWorkerResultV1): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(VerifierWorkerResultV1.serializer(), result),
    )

    fun decodeResult(encoded: String): VerifierWorkerResultV1 =
        A3SemanticJson.strictJson.decodeFromString(VerifierWorkerResultV1.serializer(), encoded)
}

/**
 * Fixed worker entrypoint for the production verifier process boundary. The worker owns the
 * same-revision reconstruction contract and emits exactly one bounded canonical-JSON result
 * file. Exit code 0 always accompanies a parseable result file; any other exit is a failure.
 */
object TransportedReplayVerifierWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val responseFile: Path = Path.of(requireArg(args, "--response"))
        val result: VerifierWorkerResultV1 = runCatching {
            val repositoryRoot = Path.of(requireArg(args, "--repository-root"))
            val request = VerifierWorkerProtocolV1Json.decodeRequest(
                String(Base64.getDecoder().decode(requireArg(args, "--request")), StandardCharsets.UTF_8),
            )
            performVerification(repositoryRoot, request)
        }.getOrElse { failure ->
            VerifierWorkerResultV1(
                status = VerifierWorkerStatusV1.UNAVAILABLE,
                failureCode = (failure as? TransportedReplayReconstructionException)?.let { exception ->
                    mapReconstructionFailure(exception)
                } ?: OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE,
                diagnostics = listOf((failure.message ?: failure::class.simpleName ?: "unknown").take(512)),
            )
        }
        Files.writeString(
            responseFile,
            VerifierWorkerProtocolV1Json.encodeResult(result),
            StandardCharsets.UTF_8,
        )
    }

    private fun requireArg(args: Array<String>, name: String): String {
        val index = args.indexOf(name)
        require(index >= 0 && index + 1 < args.size) { "Worker argument $name is required" }
        return args[index + 1]
    }

    internal fun performVerification(
        repositoryRoot: Path,
        request: VerifierWorkerRequestV1,
    ): VerifierWorkerResultV1 = try {
        val reconstructor = TransportedReplayReconstructorV1(repositoryRoot = repositoryRoot)
        val claimant = request.claimantTrajectory()
        val authenticated = reconstructor.authenticateSource(
            claimant.episodeMetadata.environmentIdentity.engineCommit,
        )
        val reconstruction = reconstructor.reconstruct(claimant)
        VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.VERIFIED,
            verifiedResult = VerifiedWorkerVerificationV1(
                verifiedTrajectoryId = reconstruction.freshTrajectory.trajectoryId,
                verifiedSemanticEpisodeId = reconstruction.freshTrajectory.semanticEpisodeId,
                verifiedReplayContentIdentity = reconstruction.freshReplayContentIdentity.value,
                verifiedReplayActionCount = reconstruction.freshReplayActionCount,
                replayTrajectoryBinding = reconstruction.freshBinding,
            ),
            diagnostics = listOf(
                "authenticatedSourceCommit=${authenticated.actualSourceCommit}",
            ),
        )
    } catch (reconstructionFailure: TransportedReplayReconstructionException) {
        VerifierWorkerResultV1(
            status = VerifierWorkerStatusV1.UNAVAILABLE,
            failureCode = mapReconstructionFailure(reconstructionFailure),
            diagnostics = listOf((reconstructionFailure.message ?: "unknown").take(512)),
        )
    }

    /** Map the typed reconstruction failure taxonomy onto the admission failure codes. */
    internal fun mapReconstructionFailure(
        failure: TransportedReplayReconstructionException,
    ): OfflineReplayFailureCodeV1 = when (failure.code) {
        TransportedReplayReconstructionFailure.SOURCE_AUTHENTICATION_FAILED ->
            // HEAD mismatch is a source-revision boundary; every other bootstrap failure (dirty
            // tracked tree, missing pins, unavailable HEAD) is a doctrine failure on the verifier
            // side. Both are fail-closed and neither verifies, but they are distinct trust causes.
            if (failure.sourceBootstrapFailure ==
                com.wingedsheep.gym.trainer.actor.SourceBootstrapFailureCodeV1.HEAD_MISMATCH
            ) {
                OfflineReplayFailureCodeV1.SOURCE_REVISION_MISMATCH
            } else {
                OfflineReplayFailureCodeV1.SOURCE_REVISION_UNVERIFIED
            }

        TransportedReplayReconstructionFailure.ENVIRONMENT_IDENTITY_MISMATCH ->
            OfflineReplayFailureCodeV1.ENVIRONMENT_IDENTITY_MISMATCH

        TransportedReplayReconstructionFailure.CLAIMANT_VALIDATION_FAILED,
        TransportedReplayReconstructionFailure.UNSUPPORTED_ENVIRONMENT,
        TransportedReplayReconstructionFailure.UNSUPPORTED_RECONSTRUCTION,
        -> OfflineReplayFailureCodeV1.UNSUPPORTED_RECONSTRUCTION

        TransportedReplayReconstructionFailure.SEMANTIC_REBIND_FAILED ->
            OfflineReplayFailureCodeV1.SEMANTIC_REBIND_FAILED

        TransportedReplayReconstructionFailure.REPLAY_NOT_EXACT ->
            OfflineReplayFailureCodeV1.REPLAY_NOT_EXACT

        TransportedReplayReconstructionFailure.REPLAY_INCOMPLETE ->
            OfflineReplayFailureCodeV1.REPLAY_INCOMPLETE

        TransportedReplayReconstructionFailure.REPLAY_DIVERGED ->
            OfflineReplayFailureCodeV1.REPLAY_DIVERGED

        TransportedReplayReconstructionFailure.REPLAY_CONTENT_IDENTITY_MISMATCH ->
            OfflineReplayFailureCodeV1.REPLAY_CONTENT_IDENTITY_MISMATCH

        TransportedReplayReconstructionFailure.TRAJECTORY_IDENTITY_MISMATCH ->
            OfflineReplayFailureCodeV1.TRAJECTORY_IDENTITY_MISMATCH

        TransportedReplayReconstructionFailure.INTERNAL_RECONSTRUCTION_FAILURE ->
            OfflineReplayFailureCodeV1.INTERNAL_VERIFIER_FAILURE
    }
}
