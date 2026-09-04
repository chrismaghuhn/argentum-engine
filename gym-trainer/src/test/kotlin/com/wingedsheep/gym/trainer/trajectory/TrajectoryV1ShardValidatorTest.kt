package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.A3SemanticJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class TrajectoryV1ShardValidatorTest : FunSpec({

    test("a real A6 shard validates and reconstructs its original trajectory") {
        val source = validFixture()
        val fixture = publishFixture(source)

        val preflight = TrajectoryV1ManifestPreflight.open(fixture.datasetRoot)
        val plannedShard = TrajectoryV1ShardValidationPlan.from(preflight).single()
        val validated = TrajectoryV1ShardValidator.validate(plannedShard)

        validated.metadata shouldBe fixture.manifest.shards.single()
        validated.episodes.map(ValidatedEpisodeV1::trajectory) shouldBe listOf(source.trajectory)
    }

    test("A6 interrupted trajectories remain valid reconstructed interrupted trajectories") {
        val source = validFixture().withClosure(
            EpisodeClosureV1.Interrupted(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
            ),
        )
        val fixture = publishFixture(source)

        val validated = TrajectoryV1ShardValidator.validate(
            TrajectoryV1ShardValidationPlan.from(
                TrajectoryV1ManifestPreflight.open(fixture.datasetRoot),
            ).single(),
        )

        validated.episodes.single().trajectory shouldBe source.trajectory
        validated.episodes.single().trajectory.closure shouldBe source.trajectory.closure
    }

    test("a valid A6 zero-decision episode reconstructs from start directly to end") {
        val source = zeroDecisionFixture()
        val fixture = publishFixture(source)

        val validated = validateSingleShard(fixture)

        validated.episodes.map(ValidatedEpisodeV1::trajectory) shouldBe listOf(source.trajectory)
        validated.episodes.single().trajectory.decisions shouldBe emptyList()
    }

    test("manifest-bound shard validation preserves physical multi-shard trajectory order") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val fixture = publishFixture(first, second, maxEpisodesPerShard = 1)

        val plannedShards = TrajectoryV1ShardValidationPlan.from(
            TrajectoryV1ManifestPreflight.open(fixture.datasetRoot),
        )
        val reconstructed = plannedShards.flatMap { shard ->
            TrajectoryV1ShardValidator.validate(shard).episodes.map(ValidatedEpisodeV1::trajectory)
        }

        reconstructed shouldBe listOf(first.trajectory, second.trajectory)
    }

    test("manifest shard membership must be contiguous in manifest episode order") {
        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val third = first.withPolicySeed(4259907L)
        val fixture = publishFixture(first, second, third, maxEpisodesPerShard = 1)
        val interleaved = completeManifest(fixture.manifest.copy(
            metadata = fixture.manifest.metadata.copy(maxEpisodesPerShard = 2),
            shards = listOf(
                fixture.manifest.shards[0].copy(episodeCount = 2),
                fixture.manifest.shards[1],
            ),
            episodes = listOf(
                fixture.manifest.episodes[0],
                fixture.manifest.episodes[1],
                fixture.manifest.episodes[2].copy(shardOrdinal = 0),
            ),
        ))
        TrajectoryV1Manifest.validate(interleaved)
        val dataset = PublishedTrajectoryDatasetManifestV1(
            datasetRoot = fixture.datasetRoot,
            manifest = interleaved,
            shardPaths = interleaved.shards.map { shard ->
                fixture.datasetRoot.resolve(shard.contentReference)
            },
        )

        readFailure(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH) {
            TrajectoryV1ShardValidationPlan.from(dataset)
        }
    }

    test("physical shard byte counts, digests, and actual byte bounds fail closed") {
        val digestMismatch = publishFixture(validFixture())
        val changed = Files.readAllBytes(digestMismatch.singleShardPath)
        changed[10] = (changed[10].toInt() xor 1).toByte()
        Files.write(digestMismatch.singleShardPath, changed)
        readFailure(TrajectoryV1ReadFailure.SHARD_DIGEST_MISMATCH) {
            validateSingleShard(digestMismatch)
        }

        val truncated = publishFixture(validFixture())
        val truncatedBytes = Files.readAllBytes(truncated.singleShardPath)
        Files.write(truncated.singleShardPath, truncatedBytes.copyOf(truncatedBytes.size - 1))
        readFailure(TrajectoryV1ReadFailure.SHARD_BYTE_COUNT_MISMATCH) {
            validateSingleShard(truncated)
        }

        val appended = publishFixture(validFixture())
        Files.write(appended.singleShardPath, Files.readAllBytes(appended.singleShardPath) + 0x20)
        readFailure(TrajectoryV1ReadFailure.SHARD_BYTE_COUNT_MISMATCH) {
            validateSingleShard(appended)
        }

        val physicalBound = publishFixture(validFixture())
        val byteCount = Files.size(physicalBound.singleShardPath)
        val boundManifest = physicalBound.manifest.copy(
            metadata = physicalBound.manifest.metadata.copy(maxShardBytes = byteCount - 1),
            shards = listOf(physicalBound.manifest.shards.single().copy(byteCount = byteCount - 1)),
        )
        val rebound = rebindManifest(physicalBound, boundManifest)
        readFailure(TrajectoryV1ReadFailure.SHARD_BOUND_VIOLATION) {
            validateSingleShard(rebound)
        }

        val actualEpisodeBound = publishFixture(validFixture(), maxEpisodesPerShard = 1)
        val duplicatedEpisodes = validFrameLines(actualEpisodeBound).let { lines -> lines + lines }
        val duplicatedRebound = rebindSingleShardLines(actualEpisodeBound, duplicatedEpisodes)
        readFailure(TrajectoryV1ReadFailure.SHARD_BOUND_VIOLATION) {
            validateSingleShard(duplicatedRebound)
        }
    }

    test("frame headers are explicitly checked after strict shared-DTO decoding") {
        val mutations = listOf(
            TrajectoryV1ReadFailure.STORAGE_SCHEMA_MISMATCH to { root: JsonObject ->
                root.withField("storageSchemaVersion", JsonPrimitive(2))
            },
            TrajectoryV1ReadFailure.STORAGE_SCHEMA_MISMATCH to { root: JsonObject ->
                root.withField("storageSchemaIdentity", JsonPrimitive("argentum-trajectory-events@v2"))
            },
            TrajectoryV1ReadFailure.TRAJECTORY_SCHEMA_MISMATCH to { root: JsonObject ->
                root.withField("trajectorySchemaVersion", JsonPrimitive(2))
            },
            TrajectoryV1ReadFailure.UNKNOWN_RECORD_TYPE to { root: JsonObject ->
                root.withField("recordType", JsonPrimitive("future-frame"))
            },
            TrajectoryV1ReadFailure.NONCANONICAL_FRAME to { root: JsonObject ->
                root.withField("futureField", JsonPrimitive(true))
            },
        )

        mutations.forEach { (expected, mutate) ->
            val fixture = publishFixture(validFixture())
            val lines = validFrameLines(fixture).toMutableList()
            lines[0] = rewriteFrame(lines[0], mutate)
            val rebound = rebindSingleShardLines(fixture, lines)

            readFailure(expected) { validateSingleShard(rebound) }
        }
    }

    test("frame canonicality rejects whitespace and omitted defaulted headers") {
        val whitespace = publishFixture(validFixture())
        val whitespaceLines = validFrameLines(whitespace).toMutableList()
        whitespaceLines[0] = whitespaceLines[0].replaceFirst("{", "{ ")
        val whitespaceRebound = rebindSingleShardLines(whitespace, whitespaceLines)
        readFailure(TrajectoryV1ReadFailure.NONCANONICAL_FRAME) {
            validateSingleShard(whitespaceRebound)
        }

        val omittedDefault = publishFixture(validFixture())
        val omittedLines = validFrameLines(omittedDefault).toMutableList()
        omittedLines[0] = rewriteFrame(omittedLines[0]) { root ->
            JsonObject(root.filterKeys { it != "storageSchemaVersion" })
        }
        val omittedRebound = rebindSingleShardLines(omittedDefault, omittedLines)
        readFailure(TrajectoryV1ReadFailure.NONCANONICAL_FRAME) {
            validateSingleShard(omittedRebound)
        }
    }

    test("physical shard framing rejects CR, missing final LF, malformed UTF-8, and malformed JSON") {
        val withCarriageReturn = publishFixture(validFixture())
        val original = Files.readAllBytes(withCarriageReturn.singleShardPath)
        val firstLf = original.indexOf('\n'.code.toByte())
        val crBytes = ByteArray(original.size + 1)
        original.copyInto(crBytes, endIndex = firstLf)
        crBytes[firstLf] = '\r'.code.toByte()
        original.copyInto(crBytes, destinationOffset = firstLf + 1, startIndex = firstLf)
        readFailure(TrajectoryV1ReadFailure.NONCANONICAL_FRAME) {
            validateSingleShard(rebindSingleShardBytes(withCarriageReturn, crBytes))
        }

        val missingFinalLf = publishFixture(validFixture())
        val withoutLf = Files.readAllBytes(missingFinalLf.singleShardPath).let { bytes ->
            bytes.copyOf(bytes.size - 1)
        }
        readFailure(TrajectoryV1ReadFailure.NONCANONICAL_FRAME) {
            validateSingleShard(rebindSingleShardBytes(missingFinalLf, withoutLf))
        }

        val malformedUtf8 = publishFixture(validFixture())
        val malformedBytes = Files.readAllBytes(malformedUtf8.singleShardPath)
        malformedBytes[10] = 0xff.toByte()
        readFailure(TrajectoryV1ReadFailure.NONCANONICAL_FRAME) {
            validateSingleShard(rebindSingleShardBytes(malformedUtf8, malformedBytes))
        }

        val malformedJson = publishFixture(validFixture())
        val malformedLines = validFrameLines(malformedJson).toMutableList()
        malformedLines[0] = "{"
        readFailure(TrajectoryV1ReadFailure.NONCANONICAL_FRAME) {
            validateSingleShard(rebindSingleShardLines(malformedJson, malformedLines))
        }
    }

    test("the event-frame state machine rejects every malformed physical order") {
        val fixture = publishFixture(validFixture())
        val valid = validFrameLines(fixture)
        val start = valid[0]
        val decision = valid[1]
        val end = valid[2]
        val future = rewriteFrame(end) { it.withField("recordType", JsonPrimitive("future-frame")) }
        val cases = listOf(
            TrajectoryV1ReadFailure.FRAME_ORDER_INVALID to listOf(decision, start, end),
            TrajectoryV1ReadFailure.FRAME_ORDER_INVALID to listOf(end),
            TrajectoryV1ReadFailure.FRAME_ORDER_INVALID to listOf(start, start, end),
            TrajectoryV1ReadFailure.TRUNCATED_EPISODE to listOf(start),
            TrajectoryV1ReadFailure.TRUNCATED_EPISODE to listOf(start, decision),
            TrajectoryV1ReadFailure.FRAME_ORDER_INVALID to listOf(start, decision, end, end),
            TrajectoryV1ReadFailure.UNKNOWN_RECORD_TYPE to listOf(start, decision, future),
            TrajectoryV1ReadFailure.NONCANONICAL_FRAME to listOf(start, "", decision, end),
        )

        cases.forEach { (expected, lines) ->
            val rebound = rebindSingleShardLines(publishFixture(validFixture()), lines)
            readFailure(expected) { validateSingleShard(rebound) }
        }
    }

    test("cross-frame identities and start-metadata redundancy must agree exactly") {
        val cases = listOf(
            1 to { root: JsonObject -> root.withField("semanticEpisodeId", JsonPrimitive("e".repeat(64))) },
            1 to { root: JsonObject -> root.withField("collectionJobId", JsonPrimitive("e".repeat(64))) },
            2 to { root: JsonObject -> root.withField("semanticEpisodeId", JsonPrimitive("e".repeat(64))) },
            2 to { root: JsonObject -> root.withField("collectionJobId", JsonPrimitive("e".repeat(64))) },
            0 to { root: JsonObject -> root.withField("semanticEpisodeId", JsonPrimitive("e".repeat(64))) },
            0 to { root: JsonObject -> root.withField("collectionJobId", JsonPrimitive("e".repeat(64))) },
        )

        cases.forEach { (lineIndex, mutate) ->
            val fixture = publishFixture(validFixture())
            val lines = validFrameLines(fixture).toMutableList()
            lines[lineIndex] = rewriteFrame(lines[lineIndex], mutate)
            val rebound = rebindSingleShardLines(fixture, lines)
            readFailure(TrajectoryV1ReadFailure.EPISODE_IDENTITY_MISMATCH) {
                validateSingleShard(rebound)
            }
        }
    }

    test("episode-end count, closure, content digest, and trajectory identity are independently checked") {
        val count = publishFixture(validFixture())
        val countLines = validFrameLines(count).toMutableList()
        countLines[2] = rewriteFrame(countLines[2]) { it.withField("decisionCount", JsonPrimitive(2)) }
        readFailure(TrajectoryV1ReadFailure.EPISODE_DECISION_COUNT_MISMATCH) {
            validateSingleShard(rebindSingleShardLines(count, countLines))
        }

        val closure = publishFixture(validFixture())
        val closureLines = validFrameLines(closure).toMutableList()
        closureLines[2] = rewriteFrame(closureLines[2]) { root ->
            root.withField(
                "closure",
                A3SemanticJson.strictJson.encodeToJsonElement(
                    EpisodeClosureV1.serializer(),
                    EpisodeClosureV1.Interrupted(
                        stepCount = 1,
                        reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
                    ),
                ),
            )
        }
        readFailure(TrajectoryV1ReadFailure.EPISODE_CLOSURE_MISMATCH) {
            validateSingleShard(rebindSingleShardLines(closure, closureLines))
        }

        val contentDigest = publishFixture(validFixture())
        val contentDigestLines = validFrameLines(contentDigest).toMutableList()
        contentDigestLines[2] = rewriteFrame(contentDigestLines[2]) {
            it.withField("episodeContentDigest", JsonPrimitive("f".repeat(64)))
        }
        readFailure(TrajectoryV1ReadFailure.EPISODE_CONTENT_DIGEST_MISMATCH) {
            validateSingleShard(rebindSingleShardLines(contentDigest, contentDigestLines))
        }

        val trajectoryId = publishFixture(validFixture())
        val trajectoryIdLines = validFrameLines(trajectoryId).toMutableList()
        trajectoryIdLines[2] = rewriteFrame(trajectoryIdLines[2]) {
            it.withField("trajectoryId", JsonPrimitive("f".repeat(64)))
        }
        readFailure(TrajectoryV1ReadFailure.TRAJECTORY_ID_MISMATCH) {
            validateSingleShard(rebindSingleShardLines(trajectoryId, trajectoryIdLines))
        }
    }

    test("physically canonical self-consistent frames still require A5 validation") {
        val source = validFixture()
        val fixture = publishFixture(source)
        val invalidDecision = source.trajectory.decisions.single().copy(decisionIndex = 1)
        val provisional = source.trajectory.copy(
            trajectoryId = "0".repeat(64),
            decisions = listOf(invalidDecision),
        )
        val invalidTrajectory = provisional.copy(trajectoryId = provisional.recomputeTrajectoryId())
        val rebound = rebindSingleShardBytes(
            fixture,
            TrajectoryV1StorageCodec.encodeLine(invalidTrajectory, episodeOrdinal = 0),
        ) { manifest, shard ->
            manifest.copy(
                shards = listOf(shard),
                episodes = listOf(manifest.episodes.single().copy(
                    trajectoryId = invalidTrajectory.trajectoryId,
                )),
            )
        }

        readFailure(TrajectoryV1ReadFailure.A5_CONTRACT_INVALID) {
            validateSingleShard(rebound)
        }
    }

    test("physical episodes bind to every manifest episode-index field") {
        val transforms = listOf<(DatasetManifestV1) -> DatasetManifestV1>(
            { manifest -> manifest.copy(episodes = listOf(manifest.episodes.single().copy(episodeOrdinal = 1))) },
            { manifest -> manifest.copy(episodes = listOf(manifest.episodes.single().copy(
                semanticEpisodeId = "f".repeat(64),
            ))) },
            { manifest -> manifest.copy(episodes = listOf(manifest.episodes.single().copy(
                collectionJobId = "f".repeat(64),
            ))) },
            { manifest -> manifest.copy(episodes = listOf(manifest.episodes.single().copy(
                trajectoryId = "f".repeat(64),
            ))) },
            { manifest -> manifest.copy(
                episodes = listOf(manifest.episodes.single().copy(decisionCount = 2)),
                counts = manifest.counts.copy(decisionCount = 2),
            ) },
            { manifest -> manifest.copy(
                episodes = listOf(manifest.episodes.single().copy(
                    closureKind = EpisodeClosureV1.Kind.INTERRUPTED,
                )),
                counts = manifest.counts.copy(gameTerminalCount = 0, interruptedCount = 1),
            ) },
        )

        transforms.forEach { transform ->
            val fixture = publishFixture(validFixture())
            val rebound = rebindManifest(fixture, transform(fixture.manifest))
            readFailure(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH) {
                validateSingleShard(rebound)
            }
        }

        val first = validFixture()
        val second = first.withPolicySeed(4259906L)
        val multi = publishFixture(first, second, maxEpisodesPerShard = 1)
        val swapped = multi.manifest.copy(
            episodes = multi.manifest.episodes.map { entry ->
                entry.copy(shardOrdinal = if (entry.shardOrdinal == 0) 1 else 0)
            },
        )
        val multiRebound = rebindManifest(multi, swapped)
        readFailure(TrajectoryV1ReadFailure.MANIFEST_EPISODE_INDEX_MISMATCH) {
            TrajectoryV1ShardValidator.validate(
                TrajectoryV1ShardValidationPlan.from(
                    TrajectoryV1ManifestPreflight.open(multiRebound.datasetRoot),
                ).first(),
            )
        }
    }
})

private data class PublishedShardFixture(
    val datasetRoot: Path,
    val manifest: DatasetManifestV1,
) {
    val singleShardPath: Path
        get() = datasetRoot.resolve(manifest.shards.single().contentReference)
}

private fun publishFixture(
    vararg sources: AdmissionFixture,
    maxEpisodesPerShard: Int = 2,
): PublishedShardFixture {
    val output = Files.createTempDirectory("a7-shard-validator-")
    val writer = TrajectoryV1Writer(
        output,
        DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = maxEpisodesPerShard),
    )
    sources.forEachIndexed { ordinal, source ->
        writer.appendEpisode(ordinal, source.trajectory, source.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
    }
    val manifest = writer.finalizeDataset()
    return PublishedShardFixture(
        datasetRoot = output.resolve("dataset-${manifest.datasetId}"),
        manifest = manifest,
    )
}

private fun zeroDecisionFixture(): AdmissionFixture {
    val source = validFixture()
    val closure = EpisodeClosureV1.Interrupted(
        stepCount = 0,
        reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
    )
    val metadataBase = source.trajectory.episodeMetadata.copy(
        compactReplayLink = source.trajectory.compactReplayLink.copy(
            replayActionCount = 0,
            replayActionEndExclusive = 0,
        ),
        closure = closure,
    )
    val metadata = metadataBase.copy(
        semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
    ).let { identified ->
        identified.copy(collectionJobId = identified.recomputeCollectionJobId())
    }
    val provisional = TrajectoryV1(
        trajectoryId = "0".repeat(64),
        episodeMetadata = metadata,
        decisions = emptyList(),
    )
    val trajectory = provisional.copy(trajectoryId = provisional.recomputeTrajectoryId())
    val originalVerification = source.binding.verificationBinding.verification
    val verification = originalVerification.copy(
        replayActionCount = 0,
        verifiedActionCount = 0,
        frames = listOf(originalVerification.frames.first().copy(replayActionIndex = 0)),
        closure = closure,
    )
    val binding = source.binding.copy(
        verificationBinding = source.binding.verificationBinding.copy(verification = verification),
        chosenInputBinding = source.binding.chosenInputBinding.copy(
            replayActionCount = 0,
            chosenInputs = emptyList(),
        ),
    )
    return AdmissionFixture(trajectory, binding)
}

private fun validateSingleShard(fixture: PublishedShardFixture): ValidatedTrajectoryShardV1 =
    TrajectoryV1ShardValidator.validate(
        TrajectoryV1ShardValidationPlan.from(
            TrajectoryV1ManifestPreflight.open(fixture.datasetRoot),
        ).single(),
    )

private fun validFrameLines(fixture: PublishedShardFixture): List<String> =
    Files.readString(fixture.singleShardPath, StandardCharsets.UTF_8)
        .trimEnd('\n')
        .split('\n')

private fun rebindSingleShardLines(
    fixture: PublishedShardFixture,
    lines: List<String>,
): PublishedShardFixture = rebindSingleShardBytes(
    fixture,
    (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8),
)

private fun rebindSingleShardBytes(
    fixture: PublishedShardFixture,
    bytes: ByteArray,
    transform: (DatasetManifestV1, DatasetShardMetadataV1) -> DatasetManifestV1 = { manifest, shard ->
        manifest.copy(shards = listOf(shard))
    },
): PublishedShardFixture {
    val previousShard = fixture.manifest.shards.single()
    val digest = A3SemanticJson.sha256(bytes)
    val reference = "shards/shard-${String.format(Locale.ROOT, "%06d", previousShard.shardOrdinal)}-" +
        "$digest$TRAJECTORY_V1_SHARD_EXTENSION"
    val replacementShard = previousShard.copy(
        contentReference = reference,
        contentDigest = digest,
        byteCount = bytes.size.toLong(),
    )
    val replacementPath = fixture.datasetRoot.resolve(reference)
    Files.write(replacementPath, bytes)
    if (replacementPath != fixture.singleShardPath) Files.delete(fixture.singleShardPath)
    return rebindManifest(fixture, transform(fixture.manifest, replacementShard))
}

private fun rebindManifest(
    fixture: PublishedShardFixture,
    proposed: DatasetManifestV1,
): PublishedShardFixture {
    val complete = completeManifest(proposed)
    Files.write(fixture.datasetRoot.resolve("manifest.json"), TrajectoryV1Manifest.encode(complete))
    val publishedRoot = fixture.datasetRoot.parent.resolve("dataset-${complete.datasetId}")
    if (publishedRoot != fixture.datasetRoot) Files.move(fixture.datasetRoot, publishedRoot)
    return PublishedShardFixture(publishedRoot, complete)
}

private fun completeManifest(proposed: DatasetManifestV1): DatasetManifestV1 {
    val unidentified = proposed.copy(
        datasetId = "0".repeat(64),
        manifestContentDigest = "0".repeat(64),
    )
    val withDatasetId = unidentified.copy(datasetId = unidentified.recomputeDatasetId())
    return withDatasetId.copy(manifestContentDigest = withDatasetId.recomputeManifestContentDigest())
}

private fun rewriteFrame(
    line: String,
    transform: (JsonObject) -> JsonObject,
): String = A3SemanticJson.canonicalJson(
    transform(A3SemanticJson.strictJson.parseToJsonElement(line).jsonObject),
)

private fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    JsonObject(this + (name to value))

private fun readFailure(
    expected: TrajectoryV1ReadFailure,
    block: () -> Unit,
) {
    shouldThrow<TrajectoryV1ReadException>(block).failure shouldBe expected
}
