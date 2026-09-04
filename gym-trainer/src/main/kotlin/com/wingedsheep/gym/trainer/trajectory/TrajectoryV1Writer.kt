package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.REPLAY_CHOSEN_INPUT_BINDING_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.REPLAY_CHOSEN_INPUT_BINDING_V1_VERSION
import com.wingedsheep.gym.contract.REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.REPLAY_CONTENT_IDENTITY_V1_VERSION
import com.wingedsheep.gym.contract.REPLAY_TRAJECTORY_BINDING_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.REPLAY_TRAJECTORY_BINDING_V1_VERSION
import com.wingedsheep.gym.contract.REPLAY_VERIFICATION_BINDING_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.REPLAY_VERIFICATION_BINDING_V1_VERSION
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.EpisodeClosureV1

/** Result of the A6 replay-backed admission boundary. */
sealed interface TrajectoryAdmissionResult {
    @ConsistentCopyVisibility
    data class Admitted internal constructor(
        val episode: ReplayAdmittedEpisodeV1,
    ) : TrajectoryAdmissionResult

    data class Quarantined(
        val metadata: QuarantineMetadataV1,
    ) : TrajectoryAdmissionResult
}
/**
 * A trajectory that passed A5 and the complete replay-backed A6 comparison. The constructor is
 * private so callers cannot manufacture trusted input for the publisher from an arbitrary DTO.
 */
class ReplayAdmittedEpisodeV1 private constructor(
    val episodeOrdinal: Int,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val replayContentIdentity: String,
    val decisionCount: Int,
    val closureKind: EpisodeClosureV1.Kind,
    private val storageBytes: ByteArray,
) {
    init {
        require(episodeOrdinal >= 0) {
            "Admitted trajectory episode ordinal must not be negative"
        }
        require(decisionCount >= 0) {
            "Admitted trajectory decision count must not be negative"
        }
        listOf(
            semanticEpisodeId,
            collectionJobId,
            trajectoryId,
            replayContentIdentity,
        ).forEach { identity ->
            A3SemanticJson.requireSha256(identity, "Admitted trajectory identity")
        }
        require(storageBytes.isNotEmpty() && storageBytes.last() == '\n'.code.toByte()) {
            "Admitted trajectory storage bytes must have LF framing"
        }
    }

    internal fun storageBytes(): ByteArray = storageBytes.copyOf()

    companion object {
        internal fun fromAdmission(
            trajectory: TrajectoryV1,
            lineBytes: ByteArray,
            episodeOrdinal: Int,
        ): ReplayAdmittedEpisodeV1 = ReplayAdmittedEpisodeV1(
            episodeOrdinal = episodeOrdinal,
            semanticEpisodeId = trajectory.semanticEpisodeId,
            collectionJobId = trajectory.collectionJobId,
            trajectoryId = trajectory.trajectoryId,
            replayContentIdentity = trajectory.compactReplayLink.replayContentIdentity,
            decisionCount = trajectory.decisions.size,
            closureKind = trajectory.closure.kind,
            storageBytes = lineBytes.copyOf(),
        )
    }
}

/**
 * Pure A6 gate. It consumes the combined neutral replay binding and never reconstructs a replay or
 * accesses GameState. All failures return bounded quarantine metadata rather than a trusted value.
 */
object TrajectoryV1Admission {
    fun admit(
        trajectory: TrajectoryV1,
        binding: ReplayTrajectoryBindingV1,
        episodeOrdinal: Int = 0,
    ): TrajectoryAdmissionResult = admitSnapshot(
        trajectory = trajectory.copy(decisions = trajectory.decisions.toList()),
        binding = binding,
        episodeOrdinal = episodeOrdinal,
    )

    private fun admitSnapshot(
        trajectory: TrajectoryV1,
        binding: ReplayTrajectoryBindingV1,
        episodeOrdinal: Int,
    ): TrajectoryAdmissionResult {
        when (val validation = TrajectoryV1Validator.validate(trajectory)) {
            is TrajectoryValidationResult.Valid -> Unit
            is TrajectoryValidationResult.QuarantineEligible -> return quarantined(
                trajectory = trajectory,
                reason = if (validation.reason == TrajectoryValidationReason.FAILED_EPISODE) {
                    TrajectoryQuarantineReason.FAILED_EPISODE
                } else if (validation.reason == TrajectoryValidationReason.PRIVACY_INTERNAL_FIELD_REJECTION) {
                    TrajectoryQuarantineReason.PRIVACY_REJECTION
                } else {
                    TrajectoryQuarantineReason.A5_CONTRACT_INVALID
                },
                episodeOrdinal = episodeOrdinal,
                a5Reason = validation.reason,
            )

            is TrajectoryValidationResult.Rejected -> return quarantined(
                trajectory = trajectory,
                reason = if (validation.reason == TrajectoryValidationReason.PRIVACY_INTERNAL_FIELD_REJECTION) {
                    TrajectoryQuarantineReason.PRIVACY_REJECTION
                } else {
                    TrajectoryQuarantineReason.A5_CONTRACT_INVALID
                },
                episodeOrdinal = episodeOrdinal,
                a5Reason = validation.reason,
            )
        }

        if (episodeOrdinal < 0) {
            return quarantined(
                trajectory,
                TrajectoryQuarantineReason.EPISODE_ORDER_MISMATCH,
                episodeOrdinal,
            )
        }

        val verificationBinding = binding.verificationBinding
        val chosenInputBinding = binding.chosenInputBinding
        val verification = verificationBinding.verification
        val contentIdentity = verificationBinding.replayContentIdentity

        if (
            binding.version != REPLAY_TRAJECTORY_BINDING_V1_VERSION ||
            binding.schemaIdentity != REPLAY_TRAJECTORY_BINDING_V1_SCHEMA_IDENTITY ||
            verificationBinding.version != REPLAY_VERIFICATION_BINDING_V1_VERSION ||
            verificationBinding.schemaIdentity != REPLAY_VERIFICATION_BINDING_V1_SCHEMA_IDENTITY ||
            chosenInputBinding.version != REPLAY_CHOSEN_INPUT_BINDING_V1_VERSION ||
            chosenInputBinding.schemaIdentity != REPLAY_CHOSEN_INPUT_BINDING_V1_SCHEMA_IDENTITY ||
            contentIdentity.version != REPLAY_CONTENT_IDENTITY_V1_VERSION ||
            contentIdentity.schemaIdentity != REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY
        ) {
            return quarantined(trajectory, TrajectoryQuarantineReason.REPLAY_VERSION_MISMATCH, episodeOrdinal)
        }

        if (
            trajectory.compactReplayLink.replayContentIdentity != contentIdentity.value ||
            trajectory.compactReplayLink.replayContentIdentity != chosenInputBinding.replayContentIdentity.value
        ) {
            return quarantined(
                trajectory,
                TrajectoryQuarantineReason.REPLAY_CONTENT_IDENTITY_MISMATCH,
                episodeOrdinal,
            )
        }

        if (
            trajectory.compactReplayLink.replayVersion != contentIdentity.replayVersion ||
            trajectory.compactReplayLink.replayVersion != verification.replayVersion
        ) {
            return quarantined(trajectory, TrajectoryQuarantineReason.REPLAY_VERSION_MISMATCH, episodeOrdinal)
        }

        if (verification.fidelity != ReplayFidelity.EXACT) {
            return quarantined(trajectory, TrajectoryQuarantineReason.REPLAY_NOT_EXACT, episodeOrdinal)
        }
        if (!verification.completeRangeVerified) {
            return quarantined(trajectory, TrajectoryQuarantineReason.REPLAY_RANGE_INCOMPLETE, episodeOrdinal)
        }

        val expectedActionCount = trajectory.compactReplayLink.replayActionCount
        if (
            trajectory.decisions.size != expectedActionCount ||
            verification.replayActionCount != expectedActionCount ||
            chosenInputBinding.replayActionCount != expectedActionCount ||
            chosenInputBinding.chosenInputs.size != expectedActionCount ||
            verification.verifiedActionCount != expectedActionCount
        ) {
            return quarantined(
                trajectory,
                TrajectoryQuarantineReason.REPLAY_ACTION_COUNT_MISMATCH,
                episodeOrdinal,
            )
        }

        if (trajectory.closure != verification.closure) {
            return quarantined(trajectory, TrajectoryQuarantineReason.REPLAY_CLOSURE_MISMATCH, episodeOrdinal)
        }

        trajectory.decisions.forEachIndexed { index, record ->
            val frame = verification.frames.getOrNull(index)
                ?: return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.REPLAY_RANGE_INCOMPLETE,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            val choice = chosenInputBinding.chosenInputs.getOrNull(index)
                ?: return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.REPLAY_ACTION_COUNT_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )

            if (
                record.decisionIndex != index ||
                record.replayActionIndex != index ||
                record.replayFrameIndex != index ||
                frame.replayActionIndex != index ||
                choice.replayActionIndex != index
            ) {
                return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.FRAME_COORDINATE_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            }
            if (
                record.perspectivePlayerId != frame.perspectivePlayerId ||
                record.perspectivePlayerId != choice.perspectivePlayerId
            ) {
                return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.PERSPECTIVE_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            }
            if (record.observationBefore != frame.observation) {
                return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.OBSERVATION_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            }
            if (record.completeLegalDomain != frame.domain) {
                return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.LEGAL_DOMAIN_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            }
            if (record.candidateDomainDigest != frame.candidateDomainDigest) {
                return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.CANDIDATE_DOMAIN_DIGEST_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            }
            if (
                record.chosenSemanticAction != choice.chosenSemanticAction ||
                record.chosenSemanticResponse != choice.chosenSemanticResponse
            ) {
                return quarantined(
                    trajectory,
                    TrajectoryQuarantineReason.CHOSEN_INPUT_MISMATCH,
                    episodeOrdinal,
                    failureReplayActionIndex = index,
                )
            }
        }

        val lineBytes = try {
            TrajectoryV1StorageCodec.encodeLine(trajectory, episodeOrdinal)
        } catch (_: TrajectoryPrivacyViolation) {
            return quarantined(trajectory, TrajectoryQuarantineReason.PRIVACY_REJECTION, episodeOrdinal)
        } catch (_: Exception) {
            return quarantined(trajectory, TrajectoryQuarantineReason.SERIALIZATION_FAILURE, episodeOrdinal)
        }
        return TrajectoryAdmissionResult.Admitted(
            ReplayAdmittedEpisodeV1.fromAdmission(trajectory, lineBytes, episodeOrdinal),
        )
    }

    private fun quarantined(
        trajectory: TrajectoryV1,
        reason: TrajectoryQuarantineReason,
        episodeOrdinal: Int,
        failureReplayActionIndex: Int? = null,
        a5Reason: TrajectoryValidationReason? = null,
    ): TrajectoryAdmissionResult.Quarantined = TrajectoryAdmissionResult.Quarantined(
        QuarantineMetadataV1.from(
            trajectory = trajectory,
            reason = reason,
            episodeOrdinal = episodeOrdinal,
            failureReplayActionIndex = failureReplayActionIndex,
            a5Reason = a5Reason,
        ),
    )
}
