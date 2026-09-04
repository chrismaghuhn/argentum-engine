package com.wingedsheep.gym.contract

import kotlinx.serialization.Serializable

/** Version of the neutral logical replay-content identity contract. */
const val REPLAY_CONTENT_IDENTITY_V1_VERSION: Int = 1

/** Stable identity of one logical CompactReplay content preimage. */
const val REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY: String =
    "argentum-compact-replay-content@v1"

/** Version of the neutral replay-content-to-verification binding contract. */
const val REPLAY_VERIFICATION_BINDING_V1_VERSION: Int = 1

/** Stable identity of one replay-content-to-verification binding. */
const val REPLAY_VERIFICATION_BINDING_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-replay-verification-binding@v1"

private val sha256Pattern = Regex("[0-9a-f]{64}")

/**
 * Neutral metadata identifying the logical replay input whose reconstruction was attempted.
 *
 * This contract intentionally has no dependency on the replay authority or reconstructed engine
 * state. The replay authority computes [value]; Gym only keeps its versioned wire shape and
 * fail-closed validation. It is not a model feature or a proof of exact reconstruction by itself.
 */
@Serializable
data class ReplayContentIdentityV1(
    val version: Int = REPLAY_CONTENT_IDENTITY_V1_VERSION,
    val schemaIdentity: String = REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY,
    val replayVersion: Int,
    val value: String,
) {
    init {
        require(version == REPLAY_CONTENT_IDENTITY_V1_VERSION) {
            "Unsupported replay-content identity version: $version"
        }
        require(schemaIdentity == REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY) {
            "Unsupported replay-content identity schema: $schemaIdentity"
        }
        require(replayVersion > 0) {
            "Replay-content identity replay version must be positive"
        }
        require(value.matches(sha256Pattern)) {
            "Replay-content identity value must be lowercase SHA-256 hex"
        }
    }
}

/**
 * Neutral association between one logical replay-content identity and one existing A4 verification.
 *
 * The wrapper does not upgrade [verification] to EXACT or otherwise assert training trust. A4
 * fidelity, complete-range, checkpoint, frame, closure, and failure fields remain the authoritative
 * proof evidence inside the unchanged [VerifiedReplayVerification] V1 contract.
 */
@Serializable
data class ReplayVerificationBindingV1(
    val version: Int = REPLAY_VERIFICATION_BINDING_V1_VERSION,
    val schemaIdentity: String = REPLAY_VERIFICATION_BINDING_V1_SCHEMA_IDENTITY,
    val replayContentIdentity: ReplayContentIdentityV1,
    val verification: VerifiedReplayVerification,
) {
    init {
        require(version == REPLAY_VERIFICATION_BINDING_V1_VERSION) {
            "Unsupported replay-verification binding version: $version"
        }
        require(schemaIdentity == REPLAY_VERIFICATION_BINDING_V1_SCHEMA_IDENTITY) {
            "Unsupported replay-verification binding schema: $schemaIdentity"
        }
        require(replayContentIdentity.replayVersion == verification.replayVersion) {
            "Replay-content identity and verification replay versions must agree"
        }
    }
}

/** Neutral source of a replay-content identity bound to the existing A4 verification result. */
interface ReplayVerificationBindingSource {
    /** Return the identity and the A4 evidence produced for the same bound replay. */
    fun verifyBinding(): ReplayVerificationBindingV1
}
