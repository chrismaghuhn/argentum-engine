package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.A3SemanticJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class TrajectoryV1ManifestPreflightTest : FunSpec({

    test("shared V1 frame DTOs reproduce A6's exact event bytes") {
        val fixture = validFixture()
        val trajectory = fixture.trajectory
        val expected = TrajectoryV1Admission.admit(trajectory, fixture.binding, episodeOrdinal = 0)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
            .episode
            .storageBytes()
            .toString(StandardCharsets.UTF_8)
            .trimEnd('\n')
            .split('\n')
            .map { line -> "$line\n".toByteArray(StandardCharsets.UTF_8).toList() }

        val actual = listOf(
            TrajectoryV1StorageCodec.encodeFrame(
                EpisodeStartFrameV1(
                    semanticEpisodeId = trajectory.semanticEpisodeId,
                    collectionJobId = trajectory.collectionJobId,
                    episodeOrdinal = 0,
                    episodeMetadata = trajectory.episodeMetadata,
                ),
            ),
            TrajectoryV1StorageCodec.encodeFrame(
                DecisionFrameV1(
                    semanticEpisodeId = trajectory.semanticEpisodeId,
                    collectionJobId = trajectory.collectionJobId,
                    decision = trajectory.decisions.single(),
                ),
            ),
            TrajectoryV1StorageCodec.encodeFrame(
                EpisodeEndFrameV1(
                    semanticEpisodeId = trajectory.semanticEpisodeId,
                    collectionJobId = trajectory.collectionJobId,
                    trajectoryId = trajectory.trajectoryId,
                    decisionCount = trajectory.decisions.size,
                    episodeContentDigest = TrajectoryV1StorageCodec.episodeContentDigest(trajectory),
                    closure = trajectory.closure,
                ),
            ),
        ).map(ByteArray::toList)

        actual shouldBe expected
    }

    test("a finalized A6 dataset has one canonical manifest-owned shard set") {
        val fixture = publishFixture()
        Files.writeString(fixture.datasetRoot.resolve("README.txt"), "unrelated")
        Files.writeString(fixture.datasetRoot.resolve("old-shard.ndjson"), "unrelated")
        Files.writeString(fixture.datasetRoot.resolve("random.json"), "unrelated")

        val preflight = TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)

        preflight.manifest shouldBe fixture.manifest
        preflight.datasetRoot shouldBe fixture.datasetRoot.toAbsolutePath().normalize()
        preflight.shardPaths shouldBe listOf(
            fixture.datasetRoot.resolve(fixture.manifest.shards.single().contentReference)
                .toAbsolutePath()
                .normalize(),
        )
    }

    test("A6 staging bytes are not a published dataset") {
        val source = validFixture()
        val output = Files.createTempDirectory("a7-staging-publication-test-")
        var moveCount = 0
        val failDirectoryMove: (Path, Path) -> Unit = { from, to ->
            moveCount++
            if (moveCount == 3) throw UnsupportedOperationException("atomic directory move unavailable")
            moveAtomically(from, to)
        }
        val writer = TrajectoryV1Writer(
            output,
            DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
            atomicMove = failDirectoryMove,
        )
        writer.appendEpisode(0, source.trajectory, source.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
        shouldThrow<TrajectoryV1StorageException> { writer.finalizeDataset() }

        val stagingDataset = Files.list(output.resolve(".staging")).use { directories ->
            directories.filter(Files::isDirectory).findFirst().orElseThrow()
        }

        moveCount shouldBe 3
        readFailure(TrajectoryV1ReadFailure.DATASET_NOT_PUBLISHED) {
            TrajectoryV1ManifestPreflight.open(stagingDataset)
        }
    }

    test("strict manifest loading rejects missing, malformed, future, and unknown-field input") {
        val cases = listOf(
            TrajectoryV1ReadFailure.MANIFEST_MISSING to { fixture: PublishedFixture ->
                Files.delete(fixture.datasetRoot.resolve("manifest.json"))
            },
            TrajectoryV1ReadFailure.MANIFEST_INVALID to { fixture: PublishedFixture ->
                Files.writeString(fixture.datasetRoot.resolve("manifest.json"), "{\n")
            },
            TrajectoryV1ReadFailure.MANIFEST_INVALID to { fixture: PublishedFixture ->
                rewriteCanonicalManifest(fixture.datasetRoot) { it.withField("version", JsonPrimitive(2)) }
            },
            TrajectoryV1ReadFailure.MANIFEST_INVALID to { fixture: PublishedFixture ->
                rewriteCanonicalManifest(fixture.datasetRoot) {
                    it.withField("schemaIdentity", JsonPrimitive("argentum-trajectory-dataset-manifest@v2"))
                }
            },
            TrajectoryV1ReadFailure.MANIFEST_INVALID to { fixture: PublishedFixture ->
                rewriteCanonicalManifest(fixture.datasetRoot) { root ->
                    root.withField(
                        "metadata",
                        root.getValue("metadata").jsonObject.withField("version", JsonPrimitive(2)),
                    )
                }
            },
            TrajectoryV1ReadFailure.MANIFEST_INVALID to { fixture: PublishedFixture ->
                rewriteCanonicalManifest(fixture.datasetRoot) { it.withField("futureField", JsonPrimitive(true)) }
            },
        )

        cases.forEach { (expected, mutate) ->
            val fixture = publishFixture()
            mutate(fixture)

            readFailure(expected) {
                TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)
            }
        }
    }

    test("manifest byte validation rejects noncanonical formatting, omitted defaults, CR, and incomplete LF") {
        listOf<(PublishedFixture) -> Unit>(
            { fixture ->
                val path = fixture.datasetRoot.resolve("manifest.json")
                val canonical = Files.readString(path, StandardCharsets.UTF_8)
                Files.writeString(path, "{\n${canonical.drop(1)}", StandardCharsets.UTF_8)
            },
            { fixture ->
                rewriteCanonicalManifest(fixture.datasetRoot) { root ->
                    JsonObject(root.filterKeys { it != "version" })
                }
            },
            { fixture ->
                val path = fixture.datasetRoot.resolve("manifest.json")
                Files.writeString(
                    path,
                    Files.readString(path, StandardCharsets.UTF_8).replace("\n", "\r\n"),
                    StandardCharsets.UTF_8,
                )
            },
            { fixture ->
                val path = fixture.datasetRoot.resolve("manifest.json")
                val bytes = Files.readAllBytes(path)
                Files.write(path, bytes.copyOf(bytes.size - 1))
            },
        ).forEach { mutate ->
            val fixture = publishFixture()
            mutate(fixture)

            readFailure(TrajectoryV1ReadFailure.MANIFEST_NONCANONICAL) {
                TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)
            }
        }
    }

    test("manifest decoding rejects malformed UTF-8") {
        val fixture = publishFixture()
        Files.write(
            fixture.datasetRoot.resolve("manifest.json"),
            byteArrayOf('{'.code.toByte(), 0xff.toByte(), '\n'.code.toByte()),
        )

        readFailure(TrajectoryV1ReadFailure.MANIFEST_INVALID) {
            TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)
        }
    }

    test("manifest identity fields are recomputed before a manifest is accepted") {
        val datasetId = publishFixture()
        rewriteCanonicalManifest(datasetId.datasetRoot) { root ->
            root.withField("datasetId", JsonPrimitive("f".repeat(64)))
        }
        readFailure(TrajectoryV1ReadFailure.DATASET_ID_MISMATCH) {
            TrajectoryV1ManifestPreflight.open(datasetId.datasetRoot)
        }

        val digest = publishFixture()
        rewriteCanonicalManifest(digest.datasetRoot) { root ->
            root.withField("manifestContentDigest", JsonPrimitive("f".repeat(64)))
        }
        readFailure(TrajectoryV1ReadFailure.MANIFEST_CONTENT_DIGEST_MISMATCH) {
            TrajectoryV1ManifestPreflight.open(digest.datasetRoot)
        }
    }

    test("the accepted manifest validator rejects self-consistent semantic contract drift") {
        listOf<(DatasetManifestV1) -> DatasetManifestV1>(
            { manifest ->
                manifest.copy(metadata = manifest.metadata.copy(
                    maxShardBytes = manifest.shards.single().byteCount - 1,
                ))
            },
            { manifest ->
                manifest.copy(shards = listOf(manifest.shards.single().copy(shardOrdinal = 1)))
            },
            { manifest ->
                manifest.copy(episodes = listOf(manifest.episodes.single().copy(shardOrdinal = 1)))
            },
            { manifest ->
                manifest.copy(shards = listOf(manifest.shards.single().copy(
                    contentReference = "outside.ndjson",
                )))
            },
        ).forEach { mutate ->
            val fixture = publishFixture()
            writeSelfConsistentManifest(fixture.datasetRoot, mutate)

            readFailure(TrajectoryV1ReadFailure.MANIFEST_INVALID) {
                TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)
            }
        }
    }

    test("manifest-owned shard paths must exist as non-symlink regular files below the dataset root") {
        val missing = publishFixture()
        Files.delete(missing.shardPath)
        readFailure(TrajectoryV1ReadFailure.SHARD_MISSING) {
            TrajectoryV1ManifestPreflight.open(missing.datasetRoot)
        }

        val directory = publishFixture()
        Files.delete(directory.shardPath)
        Files.createDirectory(directory.shardPath)
        readFailure(TrajectoryV1ReadFailure.SHARD_PATH_INVALID) {
            TrajectoryV1ManifestPreflight.open(directory.datasetRoot)
        }
    }

    test("manifest failures do not echo trajectory payloads") {
        val fixture = publishFixture()
        Files.writeString(
            fixture.datasetRoot.resolve("manifest.json"),
            "{\"observationBefore\":\"private-payload\"}\n",
            StandardCharsets.UTF_8,
        )

        val failure = shouldThrow<TrajectoryV1ReadException> {
            TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)
        }

        failure.failure shouldBe TrajectoryV1ReadFailure.MANIFEST_INVALID
        failure.message.orEmpty() shouldNotContain "observationBefore"
        failure.message.orEmpty() shouldNotContain "private-payload"
    }
})

private data class PublishedFixture(
    val datasetRoot: Path,
    val manifest: DatasetManifestV1,
) {
    val shardPath: Path
        get() = datasetRoot.resolve(manifest.shards.single().contentReference)
}

private fun publishFixture(): PublishedFixture {
    val source = validFixture()
    val output = Files.createTempDirectory("a7-manifest-preflight-")
    val writer = TrajectoryV1Writer(
        output,
        DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
    )
    writer.appendEpisode(0, source.trajectory, source.binding)
        .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
    val manifest = writer.finalizeDataset()
    return PublishedFixture(
        datasetRoot = output.resolve("dataset-${manifest.datasetId}"),
        manifest = manifest,
    )
}

private fun readFailure(
    expected: TrajectoryV1ReadFailure,
    block: () -> Unit,
) {
    val failure = shouldThrow<TrajectoryV1ReadException>(block)
    failure.failure shouldBe expected
}

private fun rewriteCanonicalManifest(
    datasetRoot: Path,
    transform: (JsonObject) -> JsonObject,
) {
    val manifestPath = datasetRoot.resolve("manifest.json")
    val root = A3SemanticJson.strictJson.parseToJsonElement(
        Files.readString(manifestPath, StandardCharsets.UTF_8),
    ).jsonObject
    val bytes = (A3SemanticJson.canonicalJson(transform(root)) + "\n").toByteArray(StandardCharsets.UTF_8)
    Files.write(manifestPath, bytes)
}

private fun writeSelfConsistentManifest(
    datasetRoot: Path,
    transform: (DatasetManifestV1) -> DatasetManifestV1,
) {
    val manifestPath = datasetRoot.resolve("manifest.json")
    val original = A3SemanticJson.strictJson.decodeFromString(
        DatasetManifestV1.serializer(),
        Files.readString(manifestPath, StandardCharsets.UTF_8),
    )
    val withoutIdentifiers = transform(original).copy(
        datasetId = "0".repeat(64),
        manifestContentDigest = "0".repeat(64),
    )
    val withDatasetId = withoutIdentifiers.copy(datasetId = withoutIdentifiers.recomputeDatasetId())
    val complete = withDatasetId.copy(
        manifestContentDigest = withDatasetId.recomputeManifestContentDigest(),
    )
    val element = A3SemanticJson.strictJson.encodeToJsonElement(DatasetManifestV1.serializer(), complete)
    Files.write(
        manifestPath,
        (A3SemanticJson.canonicalJson(element) + "\n").toByteArray(StandardCharsets.UTF_8),
    )
}

private fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    JsonObject(this + (name to value))
