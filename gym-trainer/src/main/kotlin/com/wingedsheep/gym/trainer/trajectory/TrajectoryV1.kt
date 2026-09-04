package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.StateDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Version of the durable Trajectory V1 episode envelope. */
const val TRAJECTORY_V1_VERSION: Int = 1

/** Stable identity of the durable Trajectory V1 episode envelope. */
const val TRAJECTORY_V1_SCHEMA_IDENTITY: String = "argentum-trajectory@v1"

/** Version and identity of the episode metadata contract. */
const val EPISODE_METADATA_V1_VERSION: Int = 1
const val EPISODE_METADATA_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-episode-metadata@v1"

/** Version and identity of the environment reproducibility contract. */
const val ENVIRONMENT_IDENTITY_V1_VERSION: Int = 1
const val ENVIRONMENT_IDENTITY_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-environment@v1"

/** Version and identity of one ordered roster-seat binding. */
const val ROSTER_SEAT_V1_VERSION: Int = 1
const val ROSTER_SEAT_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-roster-seat@v1"

/** Version and identity of policy collection provenance. */
const val POLICY_PROVENANCE_V1_VERSION: Int = 1
const val POLICY_PROVENANCE_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-policy-provenance@v1"

/** Version and identity of the storage-neutral CompactReplay linkage. */
const val COMPACT_REPLAY_LINK_V1_VERSION: Int = 1
const val COMPACT_REPLAY_LINK_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-compact-replay-link@v1"

/** The accepted CompactReplay semantic version for the B2 replay linkage. */
const val COMPACT_REPLAY_V5_VERSION: Int = 5
const val COMPACT_REPLAY_V5_SCHEMA_IDENTITY: String = "argentum-compact-replay@v5"

/** Version and identity of one durable decision record. */
const val DECISION_RECORD_V1_VERSION: Int = 1
const val DECISION_RECORD_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-decision-record@v1"

/** Versioned identity preimages used by A5. Their stored IDs are never included in themselves. */
const val SEMANTIC_EPISODE_IDENTITY_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-semantic-episode@v1"
const val COLLECTION_JOB_IDENTITY_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-collection-job@v1"
const val TRAJECTORY_IDENTITY_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-id@v1"

/** Version and identity of storage-neutral dataset metadata. */
const val DATASET_METADATA_V1_VERSION: Int = 1
const val DATASET_METADATA_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-dataset-metadata@v1"

/** Version and identity of storage-neutral dataset manifest contracts. */
const val DATASET_MANIFEST_V1_VERSION: Int = 1
const val DATASET_MANIFEST_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-dataset-manifest@v1"
const val DATASET_SHARD_METADATA_V1_VERSION: Int = 1
const val DATASET_SHARD_METADATA_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-dataset-shard@v1"
const val DATASET_EPISODE_INDEX_V1_VERSION: Int = 1
const val DATASET_EPISODE_INDEX_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-dataset-episode-index@v1"
const val DATASET_COUNTS_V1_VERSION: Int = 1
const val DATASET_COUNTS_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-dataset-counts@v1"
const val DATASET_IDENTITY_V1_SCHEMA_IDENTITY: String = "argentum-trajectory-dataset-id@v1"
const val MANIFEST_CONTENT_DIGEST_V1_SCHEMA_IDENTITY: String =
    "argentum-trajectory-manifest-content-digest@v1"

private fun requireSha256(value: String, label: String) {
    require(value.matches(Regex("[0-9a-f]{64}"))) {
        "$label must be lowercase SHA-256 hex"
    }
}

private fun requireNonBlank(value: String, label: String) {
    require(value.isNotBlank()) { "$label is required" }
}

private fun sha256Canonical(element: JsonElement): String =
    A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(element).toByteArray(StandardCharsets.UTF_8),
    )

/** One ordered, public seat-to-role/deck binding in the reproducible environment identity. */
@Serializable
data class RosterSeatV1(
    val version: Int = ROSTER_SEAT_V1_VERSION,
    val schemaIdentity: String = ROSTER_SEAT_V1_SCHEMA_IDENTITY,
    val seatIndex: Int,
    val playerId: com.wingedsheep.sdk.model.EntityId,
    val role: String,
    val deckIdentity: String,
    val commanderDefinitionIdentity: String? = null,
) {
    init {
        require(version == ROSTER_SEAT_V1_VERSION) { "Unsupported roster-seat version: $version" }
        require(schemaIdentity == ROSTER_SEAT_V1_SCHEMA_IDENTITY) {
            "Unsupported roster-seat identity: $schemaIdentity"
        }
        require(seatIndex >= 0) { "Roster seat index must not be negative" }
        requireNonBlank(playerId.value, "Roster player identity")
        requireNonBlank(role, "Roster seat role")
        requireNonBlank(deckIdentity, "Roster deck identity")
        commanderDefinitionIdentity?.let { requireNonBlank(it, "Commander definition identity") }
    }

    internal fun semanticElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("seatIndex", seatIndex)
        put("playerId", playerId.value)
        put("role", role)
        put("deckIdentity", deckIdentity)
        commanderDefinitionIdentity?.let { put("commanderDefinitionIdentity", it) }
    }
}

/**
 * Reproducibility inputs for one semantic episode. This deliberately excludes policy and A4 proof
 * schema identities: those identify collection/proof artifacts, not the game environment itself.
 */
@Serializable
data class EnvironmentIdentityV1(
    val version: Int = ENVIRONMENT_IDENTITY_V1_VERSION,
    val schemaIdentity: String = ENVIRONMENT_IDENTITY_V1_SCHEMA_IDENTITY,
    val engineCommit: String,
    val cardDefinitionIdentity: String,
    val akiriDeckIdentity: String,
    val chevillDeckIdentity: String,
    val format: String,
    val attackMode: String,
    val startingHandSize: Int,
    val skipMulligans: Boolean,
    val useHandSmoother: Boolean,
    val teams: List<List<Int>> = emptyList(),
    val roster: List<RosterSeatV1>,
    val startingPlayer: com.wingedsheep.sdk.model.EntityId,
    val actualEngineSeed: Long,
    val observationSchemaIdentity: String = PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY,
    val actionDomainSchemaIdentity: String = COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY,
    val candidateDomainDigestSchemaIdentity: String = CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY,
    val replaySchemaIdentity: String = COMPACT_REPLAY_V5_SCHEMA_IDENTITY,
) {
    init {
        require(version == ENVIRONMENT_IDENTITY_V1_VERSION) {
            "Unsupported environment-identity version: $version"
        }
        require(schemaIdentity == ENVIRONMENT_IDENTITY_V1_SCHEMA_IDENTITY) {
            "Unsupported environment-identity identity: $schemaIdentity"
        }
        requireNonBlank(engineCommit, "Engine commit identity")
        requireNonBlank(cardDefinitionIdentity, "Card-definition identity")
        requireNonBlank(akiriDeckIdentity, "Akiri deck identity")
        requireNonBlank(chevillDeckIdentity, "Chevill deck identity")
        requireNonBlank(format, "Environment format")
        requireNonBlank(attackMode, "Environment attack mode")
        require(startingHandSize >= 0) { "Starting hand size must not be negative" }
        require(roster.isNotEmpty()) { "Environment roster must not be empty" }
        require(roster.map(RosterSeatV1::seatIndex) == roster.indices.toList()) {
            "Environment roster must be in contiguous seat order"
        }
        require(roster.map(RosterSeatV1::playerId).distinct().size == roster.size) {
            "Environment roster contains duplicate player identities"
        }
        require(startingPlayer in roster.map(RosterSeatV1::playerId)) {
            "Starting player is absent from the environment roster"
        }
        require(teams.flatten().distinct().size == teams.flatten().size) {
            "Environment teams contain duplicate seat indexes"
        }
        require(teams.flatten().all { it in roster.indices }) {
            "Environment teams reference an unknown seat"
        }
        require(observationSchemaIdentity == PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY) {
            "Unsupported player-observation schema identity"
        }
        require(actionDomainSchemaIdentity == COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY) {
            "Unsupported complete-domain schema identity"
        }
        require(candidateDomainDigestSchemaIdentity == CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY) {
            "Unsupported candidate-domain digest schema identity"
        }
        require(replaySchemaIdentity == COMPACT_REPLAY_V5_SCHEMA_IDENTITY) {
            "Unsupported replay semantic schema identity"
        }
    }

    /** Canonical content identity of the reproducible environment/setup fields. */
    fun identityDigest(): String = sha256Canonical(
        buildJsonObject {
            put("schema", ENVIRONMENT_IDENTITY_V1_SCHEMA_IDENTITY)
            put("environment", semanticElement())
        },
    )

    internal fun semanticElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("engineCommit", engineCommit)
        put("cardDefinitionIdentity", cardDefinitionIdentity)
        put("akiriDeckIdentity", akiriDeckIdentity)
        put("chevillDeckIdentity", chevillDeckIdentity)
        put("format", format)
        put("attackMode", attackMode)
        put("startingHandSize", startingHandSize)
        put("skipMulligans", skipMulligans)
        put("useHandSmoother", useHandSmoother)
        put("teams", JsonArray(teams.map { team -> JsonArray(team.map(::JsonPrimitive)) }))
        put("roster", JsonArray(roster.map(RosterSeatV1::semanticElement)))
        put("startingPlayer", startingPlayer.value)
        put("actualEngineSeed", actualEngineSeed)
        put("observationSchemaIdentity", observationSchemaIdentity)
        put("actionDomainSchemaIdentity", actionDomainSchemaIdentity)
        put("candidateDomainDigestSchemaIdentity", candidateDomainDigestSchemaIdentity)
        put("replaySchemaIdentity", replaySchemaIdentity)
    }
}

/** Collection-policy provenance; it is intentionally absent from semantic episode/decision IDs. */
@Serializable
data class PolicyProvenanceV1(
    val version: Int = POLICY_PROVENANCE_V1_VERSION,
    val schemaIdentity: String = POLICY_PROVENANCE_V1_SCHEMA_IDENTITY,
    val behaviorPolicyIdentity: String,
    val opponentPolicyIdentity: String,
    val behaviorPolicyRole: String,
    val opponentPolicyRole: String,
    val policyRngIdentity: String,
    val policySeed: Long,
    val policySourceIdentity: String,
) {
    init {
        require(version == POLICY_PROVENANCE_V1_VERSION) {
            "Unsupported policy-provenance version: $version"
        }
        require(schemaIdentity == POLICY_PROVENANCE_V1_SCHEMA_IDENTITY) {
            "Unsupported policy-provenance identity: $schemaIdentity"
        }
        requireNonBlank(behaviorPolicyIdentity, "Behavior policy identity")
        requireNonBlank(opponentPolicyIdentity, "Opponent policy identity")
        requireNonBlank(behaviorPolicyRole, "Behavior policy role")
        requireNonBlank(opponentPolicyRole, "Opponent policy role")
        requireNonBlank(policyRngIdentity, "Policy RNG identity")
        requireNonBlank(policySourceIdentity, "Policy source identity")
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("behaviorPolicyIdentity", behaviorPolicyIdentity)
        put("opponentPolicyIdentity", opponentPolicyIdentity)
        put("behaviorPolicyRole", behaviorPolicyRole)
        put("opponentPolicyRole", opponentPolicyRole)
        put("policyRngIdentity", policyRngIdentity)
        put("policySeed", policySeed)
        put("policySourceIdentity", policySourceIdentity)
    }
}

/** Semantic/provenance linkage to one accepted CompactReplay, without importing CompactReplay. */
@Serializable
data class CompactReplayLinkV1(
    val version: Int = COMPACT_REPLAY_LINK_V1_VERSION,
    val schemaIdentity: String = COMPACT_REPLAY_LINK_V1_SCHEMA_IDENTITY,
    val replayVersion: Int = COMPACT_REPLAY_V5_VERSION,
    val replaySchemaIdentity: String = COMPACT_REPLAY_V5_SCHEMA_IDENTITY,
    val replayContentIdentity: String,
    val replayActionStart: Int = 0,
    val replayActionCount: Int,
    val replayActionEndExclusive: Int = replayActionStart + replayActionCount,
    val verifiedReplayFrameSchemaIdentity: String =
        com.wingedsheep.gym.contract.VERIFIED_REPLAY_FRAME_V1_SCHEMA_IDENTITY,
    val verifiedReplayVerificationSchemaIdentity: String =
        com.wingedsheep.gym.contract.VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY,
) {
    init {
        require(version == COMPACT_REPLAY_LINK_V1_VERSION) {
            "Unsupported CompactReplay-link version: $version"
        }
        require(schemaIdentity == COMPACT_REPLAY_LINK_V1_SCHEMA_IDENTITY) {
            "Unsupported CompactReplay-link identity: $schemaIdentity"
        }
        require(replayVersion == COMPACT_REPLAY_V5_VERSION) {
            "Unsupported linked replay version: $replayVersion"
        }
        require(replaySchemaIdentity == COMPACT_REPLAY_V5_SCHEMA_IDENTITY) {
            "Unsupported linked replay schema identity: $replaySchemaIdentity"
        }
        requireSha256(replayContentIdentity, "Replay content identity")
        require(replayActionStart >= 0) { "Replay action start must not be negative" }
        require(replayActionCount >= 0) { "Replay action count must not be negative" }
        require(replayActionEndExclusive >= replayActionStart) {
            "Replay action range overflows"
        }
        require(replayActionEndExclusive - replayActionStart == replayActionCount) {
            "Replay action range does not match its declared count"
        }
        require(
            verifiedReplayFrameSchemaIdentity ==
                com.wingedsheep.gym.contract.VERIFIED_REPLAY_FRAME_V1_SCHEMA_IDENTITY,
        ) { "Unsupported verified replay-frame schema identity" }
        require(
            verifiedReplayVerificationSchemaIdentity ==
                com.wingedsheep.gym.contract.VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY,
        ) { "Unsupported verified replay-verification schema identity" }
    }

    val replayActionRange: IntRange
        get() = replayActionStart until replayActionEndExclusive

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("replayVersion", replayVersion)
        put("replaySchemaIdentity", replaySchemaIdentity)
        put("replayContentIdentity", replayContentIdentity)
        put("replayActionStart", replayActionStart)
        put("replayActionCount", replayActionCount)
        put("replayActionEndExclusive", replayActionEndExclusive)
        put("verifiedReplayFrameSchemaIdentity", verifiedReplayFrameSchemaIdentity)
        put("verifiedReplayVerificationSchemaIdentity", verifiedReplayVerificationSchemaIdentity)
    }
}

/** Episode-level semantic/provenance metadata. */
@Serializable
data class EpisodeMetadataV1(
    val version: Int = EPISODE_METADATA_V1_VERSION,
    val schemaIdentity: String = EPISODE_METADATA_V1_SCHEMA_IDENTITY,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val environmentIdentity: EnvironmentIdentityV1,
    val policyProvenance: PolicyProvenanceV1,
    val compactReplayLink: CompactReplayLinkV1,
    val closure: EpisodeClosureV1,
) {
    init {
        require(version == EPISODE_METADATA_V1_VERSION) {
            "Unsupported episode-metadata version: $version"
        }
        require(schemaIdentity == EPISODE_METADATA_V1_SCHEMA_IDENTITY) {
            "Unsupported episode-metadata identity: $schemaIdentity"
        }
        requireSha256(semanticEpisodeId, "Semantic episode identity")
        requireSha256(collectionJobId, "Collection job identity")
    }

    fun recomputeSemanticEpisodeId(): String = sha256Canonical(
        buildJsonObject {
            put("schema", SEMANTIC_EPISODE_IDENTITY_V1_SCHEMA_IDENTITY)
            put("environmentIdentityDigest", environmentIdentity.identityDigest())
        },
    )

    fun recomputeCollectionJobId(): String = sha256Canonical(
        buildJsonObject {
            put("schema", COLLECTION_JOB_IDENTITY_V1_SCHEMA_IDENTITY)
            put("semanticEpisodeId", semanticEpisodeId)
            put("policyProvenance", policyProvenance.canonicalElement())
        },
    )

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("semanticEpisodeId", semanticEpisodeId)
        put("collectionJobId", collectionJobId)
        put("environmentIdentity", environmentIdentity.semanticElement())
        put("policyProvenance", policyProvenance.canonicalElement())
        put("compactReplayLink", compactReplayLink.canonicalElement())
        put(
            "closure",
            A3SemanticJson.strictJson.encodeToJsonElement(EpisodeClosureV1.serializer(), closure),
        )
    }
}

/** One durable public decision sample; no replay prefix, raw state, or live routing handle. */
@Serializable
data class DecisionRecordV1(
    val version: Int = DECISION_RECORD_V1_VERSION,
    val schemaIdentity: String = DECISION_RECORD_V1_SCHEMA_IDENTITY,
    val decisionIndex: Int,
    val replayActionIndex: Int,
    val replayFrameIndex: Int = replayActionIndex,
    val perspectivePlayerId: com.wingedsheep.sdk.model.EntityId,
    val decisionKind: SemanticDecisionKindV1,
    val semanticDecisionId: SemanticDecisionIdV1,
    val observationBefore: PlayerObservationV1,
    val completeLegalDomain: CompleteLegalDomainV1,
    val candidateDomainDigest: CandidateDomainDigestV1,
    val chosenSemanticAction: ChosenSemanticActionV1? = null,
    val chosenSemanticResponse: ChosenSemanticResponseV1? = null,
) {
    init {
        require(version == DECISION_RECORD_V1_VERSION) {
            "Unsupported decision-record version: $version"
        }
        require(schemaIdentity == DECISION_RECORD_V1_SCHEMA_IDENTITY) {
            "Unsupported decision-record identity: $schemaIdentity"
        }
        require(decisionIndex >= 0) { "Decision index must not be negative" }
        require(replayActionIndex >= 0) { "Replay action index must not be negative" }
        require(replayFrameIndex >= 0) { "Replay frame index must not be negative" }
        require(replayFrameIndex == replayActionIndex) {
            "Replay frame and action coordinates must agree"
        }
        require(perspectivePlayerId == observationBefore.perspectivePlayerId) {
            "Decision perspective does not match the stored observation"
        }
        require((chosenSemanticAction == null) != (chosenSemanticResponse == null)) {
            "Decision record must contain exactly one chosen semantic value"
        }
    }

    val observation: PlayerObservationV1
        get() = observationBefore

    val domain: CompleteLegalDomainV1
        get() = completeLegalDomain

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("decisionIndex", decisionIndex)
        put("replayActionIndex", replayActionIndex)
        put("replayFrameIndex", replayFrameIndex)
        put("perspectivePlayerId", perspectivePlayerId.value)
        put("decisionKind", decisionKind.name)
        put(
            "semanticDecisionId",
            A3SemanticJson.strictJson.encodeToJsonElement(SemanticDecisionIdV1.serializer(), semanticDecisionId),
        )
        put(
            "observationBefore",
            A3SemanticJson.strictJson.encodeToJsonElement(PlayerObservationV1.serializer(), observationBefore),
        )
        put(
            "completeLegalDomain",
            A3SemanticJson.strictJson.encodeToJsonElement(CompleteLegalDomainV1.serializer(), completeLegalDomain),
        )
        put(
            "candidateDomainDigest",
            A3SemanticJson.strictJson.encodeToJsonElement(
                CandidateDomainDigestV1.serializer(),
                candidateDomainDigest,
            ),
        )
        chosenSemanticAction?.let {
            put(
                "chosenSemanticAction",
                A3SemanticJson.strictJson.encodeToJsonElement(ChosenSemanticActionV1.serializer(), it),
            )
        }
        chosenSemanticResponse?.let {
            put(
                "chosenSemanticResponse",
                A3SemanticJson.strictJson.encodeToJsonElement(ChosenSemanticResponseV1.serializer(), it),
            )
        }
    }
}

/** The serializable A5 episode envelope. Validation is a separate pure operation. */
@Serializable
data class TrajectoryV1(
    val version: Int = TRAJECTORY_V1_VERSION,
    val schemaIdentity: String = TRAJECTORY_V1_SCHEMA_IDENTITY,
    val trajectoryId: String,
    val episodeMetadata: EpisodeMetadataV1,
    val decisions: List<DecisionRecordV1>,
) {
    init {
        require(version == TRAJECTORY_V1_VERSION) {
            "Unsupported trajectory version: $version"
        }
        require(schemaIdentity == TRAJECTORY_V1_SCHEMA_IDENTITY) {
            "Unsupported trajectory identity: $schemaIdentity"
        }
        requireSha256(trajectoryId, "Trajectory identity")
    }

    val semanticEpisodeId: String
        get() = episodeMetadata.semanticEpisodeId

    val collectionJobId: String
        get() = episodeMetadata.collectionJobId

    val closure: EpisodeClosureV1
        get() = episodeMetadata.closure

    val compactReplayLink: CompactReplayLinkV1
        get() = episodeMetadata.compactReplayLink

    fun recomputeTrajectoryId(): String = sha256Canonical(
        buildJsonObject {
            put("schema", TRAJECTORY_IDENTITY_V1_SCHEMA_IDENTITY)
            put("semanticEpisodeId", semanticEpisodeId)
            put("collectionJobId", collectionJobId)
            put("decisions", JsonArray(decisions.map(DecisionRecordV1::canonicalElement)))
            put(
                "closure",
                A3SemanticJson.strictJson.encodeToJsonElement(EpisodeClosureV1.serializer(), closure),
            )
        },
    )
}

/** Storage-neutral batch metadata consumed by a later publisher. */
@Serializable
data class DatasetMetadataV1(
    val version: Int = DATASET_METADATA_V1_VERSION,
    val schemaIdentity: String = DATASET_METADATA_V1_SCHEMA_IDENTITY,
    val trajectorySchemaIdentity: String = TRAJECTORY_V1_SCHEMA_IDENTITY,
    val episodeMetadataSchemaIdentity: String = EPISODE_METADATA_V1_SCHEMA_IDENTITY,
    val decisionRecordSchemaIdentity: String = DECISION_RECORD_V1_SCHEMA_IDENTITY,
    val playerObservationSchemaIdentity: String = PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY,
    val completeLegalDomainSchemaIdentity: String = COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY,
    val candidateDomainDigestSchemaIdentity: String = CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY,
    val semanticDecisionIdentitySchemaIdentity: String = SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY,
    val compactReplayLinkSchemaIdentity: String = COMPACT_REPLAY_LINK_V1_SCHEMA_IDENTITY,
    val closureSchemaVersion: Int = EpisodeClosureV1.SCHEMA_VERSION,
    val deterministicEnumeration: String = "episode-ordinal-ascending",
    val maxShardBytes: Long? = null,
    val maxEpisodesPerShard: Int? = null,
) {
    init {
        require(version == DATASET_METADATA_V1_VERSION) {
            "Unsupported dataset-metadata version: $version"
        }
        require(schemaIdentity == DATASET_METADATA_V1_SCHEMA_IDENTITY) {
            "Unsupported dataset-metadata identity: $schemaIdentity"
        }
        require(trajectorySchemaIdentity == TRAJECTORY_V1_SCHEMA_IDENTITY) {
            "Unsupported trajectory schema identity in dataset metadata"
        }
        require(episodeMetadataSchemaIdentity == EPISODE_METADATA_V1_SCHEMA_IDENTITY) {
            "Unsupported episode-metadata schema identity in dataset metadata"
        }
        require(decisionRecordSchemaIdentity == DECISION_RECORD_V1_SCHEMA_IDENTITY) {
            "Unsupported decision-record schema identity in dataset metadata"
        }
        require(playerObservationSchemaIdentity == PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY) {
            "Unsupported player-observation schema identity in dataset metadata"
        }
        require(completeLegalDomainSchemaIdentity == COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY) {
            "Unsupported complete-domain schema identity in dataset metadata"
        }
        require(candidateDomainDigestSchemaIdentity == CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY) {
            "Unsupported candidate-domain digest schema identity in dataset metadata"
        }
        require(semanticDecisionIdentitySchemaIdentity == SEMANTIC_DECISION_IDENTITY_SCHEMA_IDENTITY) {
            "Unsupported semantic-decision schema identity in dataset metadata"
        }
        require(compactReplayLinkSchemaIdentity == COMPACT_REPLAY_LINK_V1_SCHEMA_IDENTITY) {
            "Unsupported replay-link schema identity in dataset metadata"
        }
        require(closureSchemaVersion == EpisodeClosureV1.SCHEMA_VERSION) {
            "Unsupported episode-closure schema version in dataset metadata"
        }
        requireNonBlank(deterministicEnumeration, "Dataset enumeration policy")
        maxShardBytes?.let { require(it > 0) { "Dataset shard byte bound must be positive" } }
        maxEpisodesPerShard?.let {
            require(it > 0) { "Dataset shard episode bound must be positive" }
        }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("trajectorySchemaIdentity", trajectorySchemaIdentity)
        put("episodeMetadataSchemaIdentity", episodeMetadataSchemaIdentity)
        put("decisionRecordSchemaIdentity", decisionRecordSchemaIdentity)
        put("playerObservationSchemaIdentity", playerObservationSchemaIdentity)
        put("completeLegalDomainSchemaIdentity", completeLegalDomainSchemaIdentity)
        put("candidateDomainDigestSchemaIdentity", candidateDomainDigestSchemaIdentity)
        put("semanticDecisionIdentitySchemaIdentity", semanticDecisionIdentitySchemaIdentity)
        put("compactReplayLinkSchemaIdentity", compactReplayLinkSchemaIdentity)
        put("closureSchemaVersion", closureSchemaVersion)
        put("deterministicEnumeration", deterministicEnumeration)
        maxShardBytes?.let { put("maxShardBytes", it) }
        maxEpisodesPerShard?.let { put("maxEpisodesPerShard", it) }
    }
}

/** Immutable content reference for one later-published shard. */
@Serializable
data class DatasetShardMetadataV1(
    val version: Int = DATASET_SHARD_METADATA_V1_VERSION,
    val schemaIdentity: String = DATASET_SHARD_METADATA_V1_SCHEMA_IDENTITY,
    val shardOrdinal: Int,
    val contentReference: String,
    val contentDigest: String,
    val byteCount: Long,
    val episodeCount: Int,
) {
    init {
        require(version == DATASET_SHARD_METADATA_V1_VERSION) {
            "Unsupported dataset-shard version: $version"
        }
        require(schemaIdentity == DATASET_SHARD_METADATA_V1_SCHEMA_IDENTITY) {
            "Unsupported dataset-shard identity: $schemaIdentity"
        }
        require(shardOrdinal >= 0) { "Dataset shard ordinal must not be negative" }
        requireNonBlank(contentReference, "Dataset shard content reference")
        requireSha256(contentDigest, "Dataset shard content digest")
        require(byteCount >= 0) { "Dataset shard byte count must not be negative" }
        require(episodeCount >= 0) { "Dataset shard episode count must not be negative" }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("shardOrdinal", shardOrdinal)
        put("contentReference", contentReference)
        put("contentDigest", contentDigest)
        put("byteCount", byteCount)
        put("episodeCount", episodeCount)
    }
}

/** Storage-neutral index entry for one finalized trajectory episode. */
@Serializable
data class DatasetEpisodeIndexV1(
    val version: Int = DATASET_EPISODE_INDEX_V1_VERSION,
    val schemaIdentity: String = DATASET_EPISODE_INDEX_V1_SCHEMA_IDENTITY,
    val episodeOrdinal: Int,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val shardOrdinal: Int,
    val decisionCount: Int,
    val closureKind: EpisodeClosureV1.Kind,
) {
    init {
        require(version == DATASET_EPISODE_INDEX_V1_VERSION) {
            "Unsupported dataset-episode-index version: $version"
        }
        require(schemaIdentity == DATASET_EPISODE_INDEX_V1_SCHEMA_IDENTITY) {
            "Unsupported dataset-episode-index identity: $schemaIdentity"
        }
        require(episodeOrdinal >= 0) { "Dataset episode ordinal must not be negative" }
        requireSha256(semanticEpisodeId, "Dataset semantic episode identity")
        requireSha256(collectionJobId, "Dataset collection job identity")
        requireSha256(trajectoryId, "Dataset trajectory identity")
        require(shardOrdinal >= 0) { "Dataset episode shard ordinal must not be negative" }
        require(decisionCount >= 0) { "Dataset episode decision count must not be negative" }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("episodeOrdinal", episodeOrdinal)
        put("semanticEpisodeId", semanticEpisodeId)
        put("collectionJobId", collectionJobId)
        put("trajectoryId", trajectoryId)
        put("shardOrdinal", shardOrdinal)
        put("decisionCount", decisionCount)
        put("closureKind", closureKind.name)
    }
}

/** Aggregate counts carried by a later dataset manifest. */
@Serializable
data class DatasetCountsV1(
    val version: Int = DATASET_COUNTS_V1_VERSION,
    val schemaIdentity: String = DATASET_COUNTS_V1_SCHEMA_IDENTITY,
    val episodeCount: Int,
    val decisionCount: Int,
    val gameTerminalCount: Int,
    val interruptedCount: Int,
    val failedCount: Int,
) {
    init {
        require(version == DATASET_COUNTS_V1_VERSION) {
            "Unsupported dataset-counts version: $version"
        }
        require(schemaIdentity == DATASET_COUNTS_V1_SCHEMA_IDENTITY) {
            "Unsupported dataset-counts identity: $schemaIdentity"
        }
        listOf(episodeCount, decisionCount, gameTerminalCount, interruptedCount, failedCount)
            .forEach { require(it >= 0) { "Dataset counts must not be negative" } }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("episodeCount", episodeCount)
        put("decisionCount", decisionCount)
        put("gameTerminalCount", gameTerminalCount)
        put("interruptedCount", interruptedCount)
        put("failedCount", failedCount)
    }
}

/**
 * Storage-neutral manifest shape. It carries references and deterministic metadata only; it does
 * not read, write, checksum, rotate, or publish filesystem artifacts.
 */
@Serializable
data class DatasetManifestV1(
    val version: Int = DATASET_MANIFEST_V1_VERSION,
    val schemaIdentity: String = DATASET_MANIFEST_V1_SCHEMA_IDENTITY,
    val datasetId: String,
    val metadata: DatasetMetadataV1,
    val shards: List<DatasetShardMetadataV1>,
    val episodes: List<DatasetEpisodeIndexV1>,
    val counts: DatasetCountsV1,
    val manifestContentDigest: String,
) {
    init {
        require(version == DATASET_MANIFEST_V1_VERSION) {
            "Unsupported dataset-manifest version: $version"
        }
        require(schemaIdentity == DATASET_MANIFEST_V1_SCHEMA_IDENTITY) {
            "Unsupported dataset-manifest identity: $schemaIdentity"
        }
        requireSha256(datasetId, "Dataset identity")
        requireSha256(manifestContentDigest, "Manifest content digest")
    }

    fun recomputeDatasetId(): String = sha256Canonical(datasetIdentityElement())

    fun recomputeManifestContentDigest(): String = sha256Canonical(manifestContentElement())
}

private fun DatasetManifestV1.datasetIdentityElement(): JsonObject = buildJsonObject {
    put("schema", DATASET_IDENTITY_V1_SCHEMA_IDENTITY)
    put("metadata", metadata.canonicalElement())
    put("shards", JsonArray(shards.map(DatasetShardMetadataV1::canonicalElement)))
    put("episodes", JsonArray(episodes.map(DatasetEpisodeIndexV1::canonicalElement)))
    put("counts", counts.canonicalElement())
}

private fun DatasetManifestV1.manifestContentElement(): JsonObject = buildJsonObject {
    put("schema", MANIFEST_CONTENT_DIGEST_V1_SCHEMA_IDENTITY)
    put("version", version)
    put("schemaIdentity", schemaIdentity)
    put("datasetId", datasetId)
    put("metadata", metadata.canonicalElement())
    put("shards", JsonArray(shards.map(DatasetShardMetadataV1::canonicalElement)))
    put("episodes", JsonArray(episodes.map(DatasetEpisodeIndexV1::canonicalElement)))
    put("counts", counts.canonicalElement())
}

private val VALIDATED_EPISODE_GATE = Any()

/** Marker for an A5 contract-valid episode; this is not replay verification or training trust. */
class ValidatedEpisodeV1 private constructor(
    val trajectory: TrajectoryV1,
) {
    companion object {
        internal fun fromValidated(trajectory: TrajectoryV1, gate: Any): ValidatedEpisodeV1 {
            require(gate === VALIDATED_EPISODE_GATE) {
                "ValidatedEpisodeV1 can only be created by the validation gate"
            }
            return ValidatedEpisodeV1(trajectory)
        }
    }
}

/** Closed reason vocabulary for pure A5 validation outcomes. */
enum class TrajectoryValidationReason {
    UNKNOWN_VERSION,
    SCHEMA_MISMATCH,
    PRIVACY_INTERNAL_FIELD_REJECTION,
    MISSING_PLAYER_OBSERVATION,
    MISSING_COMPLETE_DOMAIN,
    DIGEST_ONLY_DOMAIN,
    CANDIDATE_DOMAIN_DIGEST_MISMATCH,
    MISSING_CHOSEN_PAYLOAD,
    BOTH_CHOSEN_PAYLOADS,
    CHOSEN_NOT_IN_DOMAIN,
    DUPLICATE_DECISION,
    NON_CONTIGUOUS_DECISION_INDEX,
    BAD_REPLAY_COORDINATE,
    DECISION_COUNT_MISMATCH,
    PERSPECTIVE_MISMATCH,
    NONTERMINAL_DECISION_OBSERVATION,
    OBSERVATION_DIGEST_MISMATCH,
    DECISION_KIND_MISMATCH,
    SEMANTIC_DECISION_IDENTITY_MISMATCH,
    SEMANTIC_EPISODE_IDENTITY_MISMATCH,
    COLLECTION_JOB_IDENTITY_MISMATCH,
    TRAJECTORY_IDENTITY_MISMATCH,
    CLOSURE_MISMATCH,
    POLICY_PROVENANCE_INVALID,
    REPLAY_LINK_INVALID,
    FAILED_EPISODE,
}

/** Typed result of pure contract validation. */
sealed interface TrajectoryValidationResult {
    enum class Status { VALID, REJECTED, QUARANTINE_ELIGIBLE }

    val status: Status

    data class Valid(val episode: ValidatedEpisodeV1) : TrajectoryValidationResult {
        override val status: Status = Status.VALID
    }

    data class Rejected(val reason: TrajectoryValidationReason) : TrajectoryValidationResult {
        override val status: Status = Status.REJECTED
    }

    data class QuarantineEligible(val reason: TrajectoryValidationReason) : TrajectoryValidationResult {
        override val status: Status = Status.QUARANTINE_ELIGIBLE
    }
}

private class ContractViolation(val reason: TrajectoryValidationReason) : RuntimeException()

private fun contractRequire(condition: Boolean, reason: TrajectoryValidationReason) {
    if (!condition) throw ContractViolation(reason)
}

/** Pure structural/semantic validator for one Trajectory V1 envelope. */
object TrajectoryV1Validator {
    fun validate(trajectory: TrajectoryV1): TrajectoryValidationResult = try {
        validateRoot(trajectory)
        validateMetadata(trajectory.episodeMetadata)
        contractRequire(
            trajectory.decisions.size == trajectory.compactReplayLink.replayActionCount,
            TrajectoryValidationReason.DECISION_COUNT_MISMATCH,
        )
        val prefixAccumulator = SemanticReplayPrefixAccumulatorV1()
        val seenDecisionIds = HashSet<SemanticDecisionIdV1>()
        trajectory.decisions.forEachIndexed { expectedIndex, record ->
            validateRecord(
                trajectory = trajectory,
                record = record,
                expectedIndex = expectedIndex,
                prefixAccumulator = prefixAccumulator,
                seenDecisionIds = seenDecisionIds,
            )
        }
        validateClosure(trajectory)
        contractRequire(
            trajectory.trajectoryId == trajectory.recomputeTrajectoryId(),
            TrajectoryValidationReason.TRAJECTORY_IDENTITY_MISMATCH,
        )
        if (trajectory.closure is EpisodeClosureV1.Failed) {
            TrajectoryValidationResult.QuarantineEligible(TrajectoryValidationReason.FAILED_EPISODE)
        } else {
            TrajectoryValidationResult.Valid(
                ValidatedEpisodeV1.fromValidated(trajectory, VALIDATED_EPISODE_GATE),
            )
        }
    } catch (violation: ContractViolation) {
        when (violation.reason) {
            TrajectoryValidationReason.FAILED_EPISODE ->
                TrajectoryValidationResult.QuarantineEligible(violation.reason)

            else -> TrajectoryValidationResult.Rejected(violation.reason)
        }
    } catch (_: IllegalArgumentException) {
        TrajectoryValidationResult.Rejected(TrajectoryValidationReason.SCHEMA_MISMATCH)
    }

    private fun validateRoot(trajectory: TrajectoryV1) {
        contractRequire(trajectory.version == TRAJECTORY_V1_VERSION, TrajectoryValidationReason.UNKNOWN_VERSION)
        contractRequire(
            trajectory.schemaIdentity == TRAJECTORY_V1_SCHEMA_IDENTITY,
            TrajectoryValidationReason.UNKNOWN_VERSION,
        )
        try {
            requireSha256(trajectory.trajectoryId, "Trajectory identity")
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.SCHEMA_MISMATCH)
        }
    }

    private fun validateMetadata(metadata: EpisodeMetadataV1) {
        contractRequire(
            metadata.semanticEpisodeId == metadata.recomputeSemanticEpisodeId(),
            TrajectoryValidationReason.SEMANTIC_EPISODE_IDENTITY_MISMATCH,
        )
        contractRequire(
            metadata.collectionJobId == metadata.recomputeCollectionJobId(),
            TrajectoryValidationReason.COLLECTION_JOB_IDENTITY_MISMATCH,
        )
        val link = metadata.compactReplayLink
        contractRequire(link.replayActionStart == 0, TrajectoryValidationReason.BAD_REPLAY_COORDINATE)
        contractRequire(
            metadata.closure.stepCount == link.replayActionCount,
            TrajectoryValidationReason.CLOSURE_MISMATCH,
        )
    }

    private fun validateRecord(
        trajectory: TrajectoryV1,
        record: DecisionRecordV1,
        expectedIndex: Int,
        prefixAccumulator: SemanticReplayPrefixAccumulatorV1,
        seenDecisionIds: MutableSet<SemanticDecisionIdV1>,
    ) {
        contractRequire(
            record.decisionIndex == expectedIndex,
            TrajectoryValidationReason.NON_CONTIGUOUS_DECISION_INDEX,
        )
        contractRequire(
            record.replayActionIndex == expectedIndex && record.replayFrameIndex == expectedIndex,
            TrajectoryValidationReason.BAD_REPLAY_COORDINATE,
        )
        validateCompleteDomain(record.completeLegalDomain)
        validateObservation(
            observation = record.observationBefore,
            perspectivePlayerId = record.perspectivePlayerId,
            domain = record.completeLegalDomain,
        )

        val recomputedDomainDigest = try {
            CandidateDomainDigestV1.from(record.completeLegalDomain)
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.MISSING_COMPLETE_DOMAIN)
        }
        contractRequire(
            record.candidateDomainDigest == recomputedDomainDigest,
            TrajectoryValidationReason.CANDIDATE_DOMAIN_DIGEST_MISMATCH,
        )

        val replayInput = validateChosen(record)
        val identity = try {
            prefixAccumulator.semanticDecisionIdentity(
                semanticEpisodeId = trajectory.semanticEpisodeId,
                replayActionIndex = record.replayActionIndex,
                observation = record.observationBefore,
                domain = record.completeLegalDomain,
                perspectivePlayerId = record.perspectivePlayerId.value,
                decisionKind = record.decisionKind,
            )
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.SEMANTIC_DECISION_IDENTITY_MISMATCH)
        }
        contractRequire(
            record.semanticDecisionId == identity.semanticDecisionId(),
            TrajectoryValidationReason.SEMANTIC_DECISION_IDENTITY_MISMATCH,
        )
        contractRequire(seenDecisionIds.add(record.semanticDecisionId), TrajectoryValidationReason.DUPLICATE_DECISION)
        prefixAccumulator.append(replayInput)
    }

    private fun validateCompleteDomain(domain: CompleteLegalDomainV1) {
        when (domain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> contractRequire(domain.candidates.isNotEmpty(), TrajectoryValidationReason.DIGEST_ONLY_DOMAIN)

            CompleteLegalDomainKind.STRUCTURED_DECISION -> contractRequire(
                domain.structuredDomain != null,
                TrajectoryValidationReason.MISSING_COMPLETE_DOMAIN,
            )
        }
    }

    private fun validateObservation(
        observation: PlayerObservationV1,
        perspectivePlayerId: com.wingedsheep.sdk.model.EntityId,
        domain: CompleteLegalDomainV1,
    ) {
        contractRequire(
            observation.perspectivePlayerId == perspectivePlayerId,
            TrajectoryValidationReason.PERSPECTIVE_MISMATCH,
        )
        contractRequire(
            observation.agentToAct == perspectivePlayerId,
            TrajectoryValidationReason.PERSPECTIVE_MISMATCH,
        )
        contractRequire(
            !observation.terminated && !observation.truncated && observation.winnerId == null,
            TrajectoryValidationReason.NONTERMINAL_DECISION_OBSERVATION,
        )
        contractRequire(observation.wireSchemaHash.isNotBlank(), TrajectoryValidationReason.SCHEMA_MISMATCH)
        try {
            requireSha256(observation.observationDigest, "Observation digest")
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.SCHEMA_MISMATCH)
        }
        val recomputedObservationDigest = try {
            StateDigest.compute(observation, domain)
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.SCHEMA_MISMATCH)
        }
        contractRequire(
            observation.observationDigest == recomputedObservationDigest,
            TrajectoryValidationReason.OBSERVATION_DIGEST_MISMATCH,
        )
        contractRequire(
            observation.winnerId == null || observation.terminated,
            TrajectoryValidationReason.CLOSURE_MISMATCH,
        )
    }

    private fun validateChosen(record: DecisionRecordV1): SemanticReplayInputV1 = when {
        record.chosenSemanticAction != null -> try {
            SemanticReplayInputV1.action(
                ChosenSemanticActionV1.from(
                    domain = record.completeLegalDomain,
                    candidate = record.chosenSemanticAction.candidate,
                    choicePayload = record.chosenSemanticAction.choicePayload,
                ),
            )
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.CHOSEN_NOT_IN_DOMAIN)
        }

        record.chosenSemanticResponse != null -> try {
            SemanticReplayInputV1.response(
                ChosenSemanticResponseV1.from(
                    domain = record.completeLegalDomain,
                    response = record.chosenSemanticResponse.response,
                ),
            )
        } catch (_: IllegalArgumentException) {
            throw ContractViolation(TrajectoryValidationReason.CHOSEN_NOT_IN_DOMAIN)
        }

        else -> throw ContractViolation(TrajectoryValidationReason.MISSING_CHOSEN_PAYLOAD)
    }

    private fun validateClosure(trajectory: TrajectoryV1) {
        when (val closure = trajectory.closure) {
            is EpisodeClosureV1.GameTerminal -> {
                if (closure.reason == com.wingedsheep.engine.core.GameEndReason.DRAW) {
                    contractRequire(closure.winnerId == null, TrajectoryValidationReason.CLOSURE_MISMATCH)
                }
                closure.winnerId?.let { winner ->
                    contractRequire(
                        winner in trajectory.episodeMetadata.environmentIdentity.roster.map { it.playerId },
                        TrajectoryValidationReason.CLOSURE_MISMATCH,
                    )
                }
                trajectory.decisions.forEach { record ->
                    val observation = record.observationBefore
                    contractRequire(!observation.truncated, TrajectoryValidationReason.CLOSURE_MISMATCH)
                    if (observation.terminated || observation.winnerId != null) {
                        contractRequire(
                            observation.terminated && observation.winnerId == closure.winnerId,
                            TrajectoryValidationReason.CLOSURE_MISMATCH,
                        )
                    }
                }
            }

            is EpisodeClosureV1.Interrupted -> trajectory.decisions.forEach { record ->
                contractRequire(!record.observationBefore.terminated, TrajectoryValidationReason.CLOSURE_MISMATCH)
                contractRequire(record.observationBefore.winnerId == null, TrajectoryValidationReason.CLOSURE_MISMATCH)
            }

            is EpisodeClosureV1.Failed -> Unit
        }
    }
}

/** Strict JSON codec for the typed A5 envelope; no writer or filesystem behavior is included. */
object TrajectoryV1Json {
    private val strictJson: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        classDiscriminator = "type"
        allowStructuredMapKeys = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(trajectory: TrajectoryV1): String =
        strictJson.encodeToString(TrajectoryV1.serializer(), trajectory)

    fun decode(encoded: String): TrajectoryV1 =
        strictJson.decodeFromString(TrajectoryV1.serializer(), encoded)

    fun decodeAndValidate(encoded: String): TrajectoryValidationResult = try {
        TrajectoryV1Validator.validate(decode(encoded))
    } catch (_: Exception) {
        TrajectoryValidationResult.Rejected(classifyDecodeFailure(encoded))
    }

    private fun classifyDecodeFailure(encoded: String): TrajectoryValidationReason {
        val root = runCatching { strictJson.parseToJsonElement(encoded).jsonObject }.getOrNull()
        val version = root?.get("version")?.jsonPrimitive?.intOrNull
        if (version != null && version != TRAJECTORY_V1_VERSION) {
            return TrajectoryValidationReason.UNKNOWN_VERSION
        }
        val schema = root?.get("schemaIdentity")?.jsonPrimitive?.content
        if (schema != null && schema != TRAJECTORY_V1_SCHEMA_IDENTITY) {
            return TrajectoryValidationReason.UNKNOWN_VERSION
        }
        val forbidden = setOf(
            "gameState",
            "actionId",
            "decisionId",
            "abilityId",
            "projectionGeneration",
            "envId",
            "rawAction",
            "pendingDecisionInternal",
        )
        if (root != null && containsKey(root, forbidden)) {
            return TrajectoryValidationReason.PRIVACY_INTERNAL_FIELD_REJECTION
        }
        return TrajectoryValidationReason.SCHEMA_MISMATCH
    }

    private fun containsKey(element: JsonElement, keys: Set<String>): Boolean = when (element) {
        is JsonObject -> element.entries.any { (key, value) -> key in keys || containsKey(value, keys) }
        is JsonArray -> element.any { containsKey(it, keys) }
        else -> false
    }
}
