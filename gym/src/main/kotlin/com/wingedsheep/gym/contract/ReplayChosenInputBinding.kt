package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/** Version of the replay-coordinate-to-chosen-input binding contract. */
const val REPLAY_CHOSEN_INPUT_BINDING_V1_VERSION: Int = 1

/** Stable identity of one complete replay chosen-input binding. */
const val REPLAY_CHOSEN_INPUT_BINDING_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-replay-chosen-input-binding@v1"

/** Version of the additive A4-plus-chosen-input composition contract. */
const val REPLAY_TRAJECTORY_BINDING_V1_VERSION: Int = 1

/** Stable identity of one A4 verification plus chosen-input evidence composition. */
const val REPLAY_TRAJECTORY_BINDING_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-replay-trajectory-binding@v1"

/**
 * One externally controlled replay action at its authoritative pre-action public boundary.
 *
 * The two chosen fields deliberately reuse the A3 vocabulary. This carrier contains no raw
 * [com.wingedsheep.engine.core.GameAction], reconstructed state, or live routing identity.
 */
@Serializable
data class ReplayChosenInputV1(
    val replayActionIndex: Int,
    val perspectivePlayerId: EntityId,
    val chosenSemanticAction: ChosenSemanticActionV1? = null,
    val chosenSemanticResponse: ChosenSemanticResponseV1? = null,
) {
    init {
        require(replayActionIndex >= 0) {
            "Replay chosen-input action index must not be negative"
        }
        require(perspectivePlayerId.value.isNotBlank()) {
            "Replay chosen-input perspective identity is required"
        }
        require((chosenSemanticAction == null) != (chosenSemanticResponse == null)) {
            "Replay chosen input must contain exactly one semantic action or response"
        }
    }
}

/**
 * Complete ordered chosen-input evidence for one replay content identity.
 *
 * CompactReplay v5 currently records every submitted [com.wingedsheep.engine.core.GameAction] in
 * its action stream; continuation resumes performed inside the replay fold are not separate
 * replay actions. The binding therefore reuses A5's one-record-per-action coordinate contract.
 *
 * This is linkage evidence, not an A4 trust verdict: the separate
 * [ReplayVerificationBindingV1] retains fidelity, checkpoint, closure, and frame proof state.
 */
@Serializable
data class ReplayChosenInputBindingV1(
    val version: Int = REPLAY_CHOSEN_INPUT_BINDING_V1_VERSION,
    val schemaIdentity: String = REPLAY_CHOSEN_INPUT_BINDING_V1_SCHEMA_IDENTITY,
    val replayContentIdentity: ReplayContentIdentityV1,
    val replayActionCount: Int,
    val chosenInputs: List<ReplayChosenInputV1>,
) {
    init {
        require(version == REPLAY_CHOSEN_INPUT_BINDING_V1_VERSION) {
            "Unsupported replay chosen-input binding version: $version"
        }
        require(schemaIdentity == REPLAY_CHOSEN_INPUT_BINDING_V1_SCHEMA_IDENTITY) {
            "Unsupported replay chosen-input binding schema: $schemaIdentity"
        }
        require(replayActionCount >= 0) {
            "Replay chosen-input action count must not be negative"
        }
        require(chosenInputs.size == replayActionCount) {
            "Replay chosen-input binding must cover the complete action range"
        }
        require(chosenInputs.map(ReplayChosenInputV1::replayActionIndex) ==
            (0 until replayActionCount).toList()) {
            "Replay chosen-input coordinates must be contiguous and producer ordered"
        }
    }
}

/**
 * Additive composition of the unchanged A4 verification binding and the per-action chosen-input
 * binding. It is the one-pass API result for a future A6 composition root; neither nested V1
 * contract is redefined here.
 */
@Serializable
data class ReplayTrajectoryBindingV1(
    val version: Int = REPLAY_TRAJECTORY_BINDING_V1_VERSION,
    val schemaIdentity: String = REPLAY_TRAJECTORY_BINDING_V1_SCHEMA_IDENTITY,
    val verificationBinding: ReplayVerificationBindingV1,
    val chosenInputBinding: ReplayChosenInputBindingV1,
) {
    init {
        require(version == REPLAY_TRAJECTORY_BINDING_V1_VERSION) {
            "Unsupported replay trajectory binding version: $version"
        }
        require(schemaIdentity == REPLAY_TRAJECTORY_BINDING_V1_SCHEMA_IDENTITY) {
            "Unsupported replay trajectory binding schema: $schemaIdentity"
        }
        require(
            verificationBinding.replayContentIdentity == chosenInputBinding.replayContentIdentity,
        ) {
            "Replay verification and chosen-input bindings must identify the same replay content"
        }
        require(
            verificationBinding.verification.replayActionCount == chosenInputBinding.replayActionCount,
        ) {
            "Replay verification and chosen-input bindings must cover the same action range"
        }
    }
}

/** Neutral source of chosen-input evidence produced alongside one replay verification fold. */
interface ReplayChosenInputBindingSource {
    /** Return complete chosen-input evidence for the source replay, or fail closed. */
    fun verifyChosenInputBinding(): ReplayChosenInputBindingV1
}

/** Neutral source of one-pass A4 verification and chosen-input evidence. */
interface ReplayTrajectoryBindingSource {
    /** Return both evidence bindings produced by one replay fold. */
    fun verifyTrajectoryBinding(): ReplayTrajectoryBindingV1
}
