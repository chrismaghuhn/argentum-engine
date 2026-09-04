package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.charset.StandardCharsets

/** The physical V1 shard representation selected for the trusted writer. */
const val TRAJECTORY_V1_STORAGE_FORMAT: String = "canonical-trajectory-events-v1-ndjson"
const val TRAJECTORY_V1_STORAGE_ENCODING: String = "UTF-8"
const val TRAJECTORY_V1_STORAGE_LINE_ENDING: String = "LF"
const val TRAJECTORY_V1_SHARD_EXTENSION: String = ".ndjson"

/** Builds and validates the accepted storage-neutral DatasetManifestV1 without filesystem scans. */
object TrajectoryV1Manifest {
    private val shardReferencePattern = Regex(
        "shards/shard-([0-9]{6})-([0-9a-f]{64})\\.ndjson",
    )

    fun build(
        metadata: DatasetMetadataV1,
        shards: List<DatasetShardMetadataV1>,
        episodes: List<DatasetEpisodeIndexV1>,
        counts: DatasetCountsV1,
    ): DatasetManifestV1 {
        val placeholder = DatasetManifestV1(
            datasetId = "0".repeat(64),
            metadata = metadata,
            shards = shards.toList(),
            episodes = episodes.toList(),
            counts = counts,
            manifestContentDigest = "0".repeat(64),
        )
        val identified = placeholder.copy(datasetId = placeholder.recomputeDatasetId())
        val complete = identified.copy(
            manifestContentDigest = identified.recomputeManifestContentDigest(),
        )
        validate(complete)
        return complete
    }

    fun encode(manifest: DatasetManifestV1): ByteArray {
        validate(manifest)
        val element = A3SemanticJson.strictJson.encodeToJsonElement(
            DatasetManifestV1.serializer(),
            manifest,
        )
        return (A3SemanticJson.canonicalJson(element) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    fun validate(manifest: DatasetManifestV1) {
        require(manifest.metadata.deterministicEnumeration == "episode-ordinal-ascending") {
            "A6 requires episode-ordinal-ascending dataset enumeration"
        }
        require(manifest.shards.map(DatasetShardMetadataV1::shardOrdinal) ==
            manifest.shards.indices.toList()) {
            "Dataset shards must remain in producer ordinal order"
        }
        require(manifest.episodes.map(DatasetEpisodeIndexV1::episodeOrdinal) ==
            manifest.episodes.map(DatasetEpisodeIndexV1::episodeOrdinal).sorted()) {
            "Dataset episodes must remain in producer ordinal order"
        }
        require(manifest.episodes.map(DatasetEpisodeIndexV1::episodeOrdinal).distinct().size ==
            manifest.episodes.size) {
            "Dataset episode ordinals must be unique"
        }
        require(manifest.episodes.all { it.shardOrdinal in manifest.shards.indices }) {
            "Dataset episode references an unknown shard"
        }
        require(manifest.shards.all { it.byteCount > 0 && it.episodeCount > 0 }) {
            "Dataset manifests cannot contain empty shards"
        }
        require(manifest.shards.map { shard ->
            manifest.episodes.count { it.shardOrdinal == shard.shardOrdinal }
        } == manifest.shards.map(DatasetShardMetadataV1::episodeCount)) {
            "Dataset shard membership does not match shard episode counts"
        }
        require(manifest.shards.sumOf(DatasetShardMetadataV1::episodeCount) ==
            manifest.counts.episodeCount) {
            "Dataset shard episode counts do not match the manifest count"
        }
        require(manifest.episodes.size == manifest.counts.episodeCount) {
            "Dataset episode index does not match the manifest count"
        }
        require(manifest.counts.decisionCount == manifest.episodes.sumOf(DatasetEpisodeIndexV1::decisionCount)) {
            "Dataset decision count does not match the episode index"
        }
        require(manifest.counts.gameTerminalCount == manifest.episodes.count {
            it.closureKind == com.wingedsheep.gym.EpisodeClosureV1.Kind.GAME_TERMINAL
        }) {
            "Dataset game-terminal count does not match the episode index"
        }
        require(manifest.counts.interruptedCount == manifest.episodes.count {
            it.closureKind == com.wingedsheep.gym.EpisodeClosureV1.Kind.INTERRUPTED
        }) {
            "Dataset interrupted count does not match the episode index"
        }
        require(manifest.counts.gameTerminalCount + manifest.counts.interruptedCount ==
            manifest.counts.episodeCount) {
            "Trusted dataset closure counts do not cover every episode"
        }
        require(manifest.counts.failedCount == 0) {
            "Failed episodes are quarantine-only and cannot enter a trusted manifest"
        }
        require(manifest.shards.all { shard ->
            val match = shardReferencePattern.matchEntire(shard.contentReference)
            match != null &&
                match.groupValues[1].toInt() == shard.shardOrdinal &&
                match.groupValues[2] == shard.contentDigest
        }) {
            "Dataset shard references must be relative trusted-storage paths"
        }
        require(manifest.datasetId == manifest.recomputeDatasetId()) {
            "Dataset identity does not match its accepted V1 preimage"
        }
        require(manifest.manifestContentDigest == manifest.recomputeManifestContentDigest()) {
            "Manifest content digest does not match its accepted V1 preimage"
        }
    }
}
