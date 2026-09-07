package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets

/** Version of the additive Commander-public Gym projection. */
const val COMMANDER_PUBLIC_STATE_V1_VERSION: Int = 1

/** Stable identity of the additive Commander-public Gym projection. */
const val COMMANDER_PUBLIC_STATE_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-commander-public-state@v1"

/** Public knowledge of a designated commander's current zone, without a physical object handle. */
@Serializable
enum class CommanderPublicZoneKind {
    COMMAND,
    BATTLEFIELD,
    STACK,
    GRAVEYARD,
    EXILE,
    HAND,
    LIBRARY,
    UNKNOWN,
}

/** One public cumulative Commander-damage tally for one defending player. */
@Serializable
data class CommanderDamageByDefendingPlayerV1(
    val defendingPlayerId: EntityId,
    val cumulativeDamage: Int,
) {
    init {
        require(defendingPlayerId.value.isNotBlank()) {
            "Commander damage defending-player identity is required"
        }
        require(cumulativeDamage >= 0) {
            "Commander damage must not be negative"
        }
    }
}

/** Public semantic facts for one designated commander. No runtime EntityId is exposed. */
@Serializable
data class CommanderPublicEntryV1(
    val ownerPlayerId: EntityId,
    /** Card-definition identity of the public designation, not the physical object identity. */
    val publicCommanderIdentity: String,
    val publicCurrentZone: CommanderPublicZoneKind,
    val castsFromCommandZone: Int,
    val commanderDamageThreshold: Int?,
    val damageByDefendingPlayer: List<CommanderDamageByDefendingPlayerV1>,
) {
    init {
        require(ownerPlayerId.value.isNotBlank()) {
            "Commander owner identity is required"
        }
        require(publicCommanderIdentity.isNotBlank()) {
            "Public commander identity is required"
        }
        require(castsFromCommandZone >= 0) {
            "Commander command-zone cast count must not be negative"
        }
        commanderDamageThreshold?.let { threshold ->
            require(threshold > 0) {
                "Commander damage threshold must be positive"
            }
        }
        require(
            damageByDefendingPlayer.map { it.defendingPlayerId }.distinct().size ==
                damageByDefendingPlayer.size,
        ) {
            "Commander damage entries cannot duplicate a defending player"
        }
    }
}

/**
 * Additive, perspective-safe Commander state from the Gym projection seam.
 *
 * This is deliberately not a field on TrainingObservation or PlayerObservationV1. It is carried
 * by ObservationResult for the in-process trusted Gym/model seam; HTTP and Trajectory V1 remain
 * unchanged until a later separately authorized binding slice.
 */
@Serializable
data class CommanderPublicStateV1(
    val version: Int = COMMANDER_PUBLIC_STATE_V1_VERSION,
    val schemaIdentity: String = COMMANDER_PUBLIC_STATE_V1_SCHEMA_IDENTITY,
    val perspectivePlayerId: EntityId,
    val commanders: List<CommanderPublicEntryV1>,
) {
    init {
        require(version == COMMANDER_PUBLIC_STATE_V1_VERSION) {
            "Unsupported Commander-public state version: $version"
        }
        require(schemaIdentity == COMMANDER_PUBLIC_STATE_V1_SCHEMA_IDENTITY) {
            "Unsupported Commander-public state identity: $schemaIdentity"
        }
        require(perspectivePlayerId.value.isNotBlank()) {
            "Commander-public state perspective identity is required"
        }
        require(commanders.map { it.ownerPlayerId }.distinct().size == commanders.size) {
            "Current Commander-public state supports one designated commander per owner"
        }
    }

    /** Deterministic canonical semantic JSON, including producer-owned list order. */
    fun canonicalJson(): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(serializer(), this),
    )

    /** Content digest of this additive public contract. */
    fun semanticDigest(): String = A3SemanticJson.sha256(
        canonicalJson().toByteArray(StandardCharsets.UTF_8),
    )
}
