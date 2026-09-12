package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

const val C1_DERIVED_VIEW_SCHEMA_IDENTITY: String =
    "argentum-ml-derived-learner-view@v1"
const val C1_DERIVED_ARTIFACT_IDENTITY_SCHEMA: String =
    "argentum-ml-derived-artifact-id@v1"
const val C1_SPLIT_CONTRACT_IDENTITY: String =
    "argentum-ml-dataset-split@v1"
const val C1_MODEL_FACING_CONTRACT_IDENTITY: String =
    "argentum-ml-model-facing-decision-sample@v1"
const val C1_DERIVED_MANIFEST_VERSION: Int = 1
const val C1_DERIVED_SAMPLE_VERSION: Int = 1

private val sha256Pattern = Regex("[0-9a-f]{64}")
private val commitPattern = Regex("[0-9a-f]{40}")

private fun requireSha256(value: String, label: String) {
    require(sha256Pattern.matches(value)) {
        "$label must be lowercase SHA-256 hex"
    }
}

@Serializable
data class C1PartitionCounts(
    @SerialName("TRAIN") val train: Int,
    @SerialName("VALIDATION") val validation: Int,
    @SerialName("TEST") val test: Int,
) {
    init {
        require(train >= 0 && validation >= 0 && test >= 0) {
            "Partition counts must not be negative"
        }
    }

    fun total(): Long = train.toLong() + validation.toLong() + test.toLong()
}

@Serializable
data class C1MaterializerImplementationIdentity(
    val implementation: String,
    val sourceCommit: String,
) {
    init {
        require(implementation.isNotBlank()) {
            "Materializer implementation identity must not be blank"
        }
        require(commitPattern.matches(sourceCommit)) {
            "Materializer source commit must be lowercase Git SHA-1"
        }
    }
}

@Serializable
data class C1DerivedSourceReference(
    val datasetId: String,
    val sourceManifestContentDigest: String,
    val trajectoryId: String,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val decisionIndex: Int,
    val replayActionIndex: Int,
    val replayFrameIndex: Int,
    val semanticDecisionId: JsonObject,
    val perspectivePlayerId: String,
)

@Serializable
data class C1DerivedTargetChannel(
    val chosenSemanticAction: JsonObject? = null,
    val chosenSemanticResponse: JsonObject? = null,
) {
    init {
        require((chosenSemanticAction == null) != (chosenSemanticResponse == null)) {
            "Derived target must contain exactly one chosen semantic value"
        }
    }
}

@Serializable
data class C1EntityAliasBindingV1(
    val alias: String,
    val sourceEntityId: String,
)

@Serializable
data class C1DerivedBindingChannel(
    val completeLegalDomain: JsonObject,
    val selectedExactSourceBinding: JsonObject,
    val sourceBindingOrdinals: List<Int> = emptyList(),
    val semanticTieDiscriminators: Map<String, JsonElement> = emptyMap(),
    val entityAliasBindings: List<C1EntityAliasBindingV1> = emptyList(),
) {
    init {
        val expectedAliases = entityAliasBindings.indices.map { "entity-$it" }
        require(entityAliasBindings.map { it.alias } == expectedAliases) {
            "Entity aliases must be contiguous and producer-ordered"
        }
        require(entityAliasBindings.map { it.sourceEntityId }.distinct().size ==
            entityAliasBindings.size
        ) {
            "Entity alias bindings must be injective"
        }
        require(entityAliasBindings.all { it.sourceEntityId.isNotBlank() }) {
            "Entity alias bindings must contain non-blank source IDs"
        }
    }
}

@Serializable
data class C1DerivedSampleV1(
    val version: Int = C1_DERIVED_SAMPLE_VERSION,
    val partition: C1DatasetPartition,
    val sourceReference: C1DerivedSourceReference,
    val input: JsonObject,
    val target: C1DerivedTargetChannel,
    val binding: C1DerivedBindingChannel,
    val provenance: JsonObject,
) {
    init {
        require(version == C1_DERIVED_SAMPLE_VERSION) {
            "Unsupported derived sample version: $version"
        }
    }
}

@Serializable
data class C1DerivedManifestV1(
    val version: Int = C1_DERIVED_MANIFEST_VERSION,
    val derivedViewSchemaIdentity: String = C1_DERIVED_VIEW_SCHEMA_IDENTITY,
    val derivedArtifactId: String,
    val sourceDatasetId: String,
    val sourceManifestContentDigest: String,
    val trajectorySchemaIdentity: String,
    val modelFacingContractIdentity: String,
    val splitContractIdentity: String,
    val materializerImplementationIdentity: C1MaterializerImplementationIdentity,
    val materializerConfigDigest: String,
    val samplesContentReference: String = "samples.ndjson",
    val samplesContentDigest: String,
    val samplesByteCount: Long,
    val sampleCount: Int,
    val episodeCount: Int,
    val episodeCountsByPartition: C1PartitionCounts,
    val sampleCountsByPartition: C1PartitionCounts,
    val manifestContentDigest: String,
) {
    init {
        require(version == C1_DERIVED_MANIFEST_VERSION) {
            "Unsupported derived manifest version: $version"
        }
        require(derivedViewSchemaIdentity == C1_DERIVED_VIEW_SCHEMA_IDENTITY) {
            "Unsupported derived view schema identity: $derivedViewSchemaIdentity"
        }
        requireSha256(derivedArtifactId, "Derived artifact identity")
        requireSha256(sourceDatasetId, "Source dataset identity")
        requireSha256(sourceManifestContentDigest, "Source manifest content digest")
        requireSha256(materializerConfigDigest, "Materializer config digest")
        requireSha256(samplesContentDigest, "Samples content digest")
        requireSha256(manifestContentDigest, "Manifest content digest")
        require(trajectorySchemaIdentity == "argentum-trajectory@v1") {
            "Unsupported source trajectory schema identity: $trajectorySchemaIdentity"
        }
        require(modelFacingContractIdentity == C1_MODEL_FACING_CONTRACT_IDENTITY) {
            "Unsupported model-facing contract identity: $modelFacingContractIdentity"
        }
        require(splitContractIdentity == C1_SPLIT_CONTRACT_IDENTITY) {
            "Unsupported split contract identity: $splitContractIdentity"
        }
        require(samplesContentReference == "samples.ndjson") {
            "Unsupported samples content reference: $samplesContentReference"
        }
        require(samplesByteCount >= 0) { "Samples byte count must not be negative" }
        require(sampleCount >= 0 && episodeCount >= 0) {
            "Derived counts must not be negative"
        }
        require(episodeCountsByPartition.total() == episodeCount.toLong()) {
            "Episode partition counts do not match episodeCount"
        }
        require(sampleCountsByPartition.total() == sampleCount.toLong()) {
            "Sample partition counts do not match sampleCount"
        }
    }

    fun recomputeDerivedArtifactId(): String = A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(derivedArtifactIdentityElement())
            .toByteArray(StandardCharsets.UTF_8),
    )

    fun recomputeManifestContentDigest(): String = A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(manifestContentElement())
            .toByteArray(StandardCharsets.UTF_8),
    )

    private fun derivedArtifactIdentityElement(): JsonObject = buildJsonObject {
        put("schema", C1_DERIVED_ARTIFACT_IDENTITY_SCHEMA)
        put("derivedViewSchemaIdentity", derivedViewSchemaIdentity)
        put("sourceDatasetId", sourceDatasetId)
        put("sourceManifestContentDigest", sourceManifestContentDigest)
        put("trajectorySchemaIdentity", trajectorySchemaIdentity)
        put("modelFacingContractIdentity", modelFacingContractIdentity)
        put("splitContractIdentity", splitContractIdentity)
        put(
            "materializerImplementationIdentity",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1MaterializerImplementationIdentity.serializer(),
                materializerImplementationIdentity,
            ),
        )
        put("materializerConfigDigest", materializerConfigDigest)
        put("samplesContentDigest", samplesContentDigest)
        put(
            "episodeCountsByPartition",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1PartitionCounts.serializer(),
                episodeCountsByPartition,
            ),
        )
        put(
            "sampleCountsByPartition",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1PartitionCounts.serializer(),
                sampleCountsByPartition,
            ),
        )
    }

    private fun manifestContentElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("derivedViewSchemaIdentity", derivedViewSchemaIdentity)
        put("derivedArtifactId", derivedArtifactId)
        put("sourceDatasetId", sourceDatasetId)
        put("sourceManifestContentDigest", sourceManifestContentDigest)
        put("trajectorySchemaIdentity", trajectorySchemaIdentity)
        put("modelFacingContractIdentity", modelFacingContractIdentity)
        put("splitContractIdentity", splitContractIdentity)
        put(
            "materializerImplementationIdentity",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1MaterializerImplementationIdentity.serializer(),
                materializerImplementationIdentity,
            ),
        )
        put("materializerConfigDigest", materializerConfigDigest)
        put("samplesContentReference", samplesContentReference)
        put("samplesContentDigest", samplesContentDigest)
        put("samplesByteCount", samplesByteCount)
        put("sampleCount", sampleCount)
        put("episodeCount", episodeCount)
        put(
            "episodeCountsByPartition",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1PartitionCounts.serializer(),
                episodeCountsByPartition,
            ),
        )
        put(
            "sampleCountsByPartition",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1PartitionCounts.serializer(),
                sampleCountsByPartition,
            ),
        )
    }
}
