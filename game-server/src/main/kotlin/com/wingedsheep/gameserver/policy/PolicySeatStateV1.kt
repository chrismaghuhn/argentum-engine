package com.wingedsheep.gameserver.policy

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.LivePolicyRngStateV1
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val POLICY_SEAT_STATE_V1_VERSION: Int = 1
const val POLICY_SEAT_STATE_V1_SCHEMA_IDENTITY: String =
    "argentum-gameserver-policy-seat-state@v1"
private const val POLICY_TIE_STREAM_SCHEMA_IDENTITY: String = "argentum-ml-policy-tie-stream@v1"

/** Mutable persisted state for one ML policy instance; the worker itself is never persisted. */
@Serializable
data class PolicySeatStateV1(
    val version: Int = POLICY_SEAT_STATE_V1_VERSION,
    val schemaIdentity: String = POLICY_SEAT_STATE_V1_SCHEMA_IDENTITY,
    val streamKeyHex: String,
    val cursor: ULong = 0uL,
) {
    init {
        require(version == POLICY_SEAT_STATE_V1_VERSION) {
            "Unsupported policy-seat state version: $version"
        }
        require(schemaIdentity == POLICY_SEAT_STATE_V1_SCHEMA_IDENTITY) {
            "Unsupported policy-seat state identity: $schemaIdentity"
        }
        require(streamKeyHex.matches(Regex("[0-9a-f]{64}"))) {
            "Policy-seat stream key must be lowercase 32-byte hex"
        }
    }

    fun toLiveState(): LivePolicyRngStateV1 = LivePolicyRngStateV1(
        streamKeyHex = streamKeyHex,
        cursor = cursor,
    )

    fun requireMatches(authority: ControllerAuthorityV1) {
        require(authority.isMlPolicy) { "Policy-seat state requires an ML controller authority" }
        require(streamKeyHex == deriveStreamKeyHex(authority.policySeed!!, authority.seatIndex)) {
            "Policy-seat stream key does not match its controller authority"
        }
    }

    companion object {
        fun fromAuthority(authority: ControllerAuthorityV1): PolicySeatStateV1 {
            require(authority.isMlPolicy) { "Policy-seat state requires an ML controller authority" }
            return PolicySeatStateV1(
                streamKeyHex = deriveStreamKeyHex(authority.policySeed!!, authority.seatIndex),
            )
        }

        fun fromLiveState(state: LivePolicyRngStateV1): PolicySeatStateV1 = PolicySeatStateV1(
            streamKeyHex = state.streamKeyHex,
            cursor = state.cursor,
        )
    }
}

/** Exact C0-04B stream-key derivation; no RNG words are generated on the JVM. */
fun deriveStreamKeyHex(policySeed: Long, seatIndex: Int): String {
    require(seatIndex >= 0) { "PolicyTieRng seat index must be non-negative" }
    val seedBitsHex = java.lang.Long.toUnsignedString(policySeed, 16).padStart(16, '0')
    val payload = buildJsonObject {
        put("policySeedBitsHex", seedBitsHex)
        put("schema", POLICY_TIE_STREAM_SCHEMA_IDENTITY)
        put("seatIndex", seatIndex)
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(
        A3SemanticJson.canonicalJson(payload).toByteArray(StandardCharsets.UTF_8),
    )
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
