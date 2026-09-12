package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.EpisodeInterruptionReason
import com.wingedsheep.gym.trainer.trajectory.withClosure
import com.wingedsheep.gym.trainer.trajectory.withPolicySeed
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class C1LearnerArtifactMaterializerTest : FunSpec({
    test("materializes identical exact bytes independent of output path") {
        val root = Files.createTempDirectory("c1-materializer-")
        val published = publishC1Fixture(root)
        val config = C1MaterializerConfig(
            implementationIdentity = "c1-materializer@v1",
            configDigest = "d".repeat(64),
        )
        val firstOutput = root.resolve("first-output")
        val secondOutput = root.resolve("second-output")

        val first = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = firstOutput,
            sourceCommit = "e".repeat(40),
            config = config,
        )
        val second = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = secondOutput,
            sourceCommit = "e".repeat(40),
            config = config,
        )

        val firstSamples = Files.readAllBytes(firstOutput.resolve("samples.ndjson"))
        val secondSamples = Files.readAllBytes(secondOutput.resolve("samples.ndjson"))
        val firstManifest = Files.readAllBytes(firstOutput.resolve("manifest.json"))
        val secondManifest = Files.readAllBytes(secondOutput.resolve("manifest.json"))

        firstSamples.contentEquals(secondSamples) shouldBe true
        firstManifest.contentEquals(secondManifest) shouldBe true
        first.derivedArtifactId shouldBe second.derivedArtifactId
        first.recomputeDerivedArtifactId() shouldBe first.derivedArtifactId
        first.recomputeManifestContentDigest() shouldBe first.manifestContentDigest
        independentDerivedArtifactId(first) shouldBe first.derivedArtifactId
        first.samplesContentDigest shouldBe sha256(firstSamples)
        first.samplesByteCount shouldBe firstSamples.size.toLong()
        first.sampleCount shouldBe 1
        first.episodeCount shouldBe 1
        hasUtf8Bom(firstSamples) shouldBe false
        firstSamples.lastOrNull()?.toInt() shouldBe '\n'.code
        firstSamples.count { it == '\n'.code.toByte() } shouldBe first.sampleCount
        firstSamples.none { it == '\r'.code.toByte() } shouldBe true
        hasUtf8Bom(firstManifest) shouldBe false
        firstManifest.lastOrNull()?.toInt() shouldNotBe '\n'.code
        firstManifest.lastOrNull()?.toInt() shouldNotBe '\r'.code
    }

    test("does not leave an accepted artifact when the A7 source open fails") {
        val root = Files.createTempDirectory("c1-materializer-failure-")
        val output = root.resolve("output")

        shouldThrow<Exception> {
            C1LearnerArtifactMaterializer.materialize(
                publishedDatasetDirectory = root.resolve("missing-source"),
                outputDirectory = output,
                sourceCommit = "e".repeat(40),
                config = C1MaterializerConfig(
                    implementationIdentity = "c1-materializer@v1",
                    configDigest = "d".repeat(64),
                ),
            )
        }

        Files.exists(output.resolve("manifest.json")) shouldBe false
        Files.exists(output.resolve("samples.ndjson")) shouldBe false
    }

    test("ignores unreferenced source files") {
        val root = Files.createTempDirectory("c1-materializer-extra-")
        val published = publishC1Fixture(root)
        Files.writeString(published.root.resolve("unreferenced.extra"), "not a shard")

        val manifest = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = root.resolve("output"),
            sourceCommit = "e".repeat(40),
            config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
        )

        manifest.sampleCount shouldBe 1
    }

    test("preserves source episode order and keeps a shared semantic episode split") {
        val root = Files.createTempDirectory("c1-materializer-order-")
        val first = com.wingedsheep.gym.trainer.trajectory.validFixture()
        val second = first.withPolicySeed(987654L)
        first.trajectory.semanticEpisodeId shouldBe second.trajectory.semanticEpisodeId
        first.trajectory.collectionJobId shouldNotBe second.trajectory.collectionJobId
        val published = publishC1Fixtures(root, listOf(first, second))
        val output = root.resolve("output")

        val manifest = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = output,
            sourceCommit = "e".repeat(40),
            config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
        )
        val samples = Files.readString(output.resolve("samples.ndjson"))
        val firstOffset = samples.indexOf(first.trajectory.collectionJobId)
        val secondOffset = samples.indexOf(second.trajectory.collectionJobId)
        require(firstOffset >= 0 && secondOffset >= 0)
        (firstOffset < secondOffset) shouldBe true
        val partition = C1DatasetSplitV1.assign(first.trajectory.semanticEpisodeId)
        when (partition) {
            C1DatasetPartition.TRAIN -> manifest.episodeCountsByPartition.train
            C1DatasetPartition.VALIDATION -> manifest.episodeCountsByPartition.validation
            C1DatasetPartition.TEST -> manifest.episodeCountsByPartition.test
        } shouldBe 2
    }

    test("preserves decision source order and sample partition grouping") {
        val root = Files.createTempDirectory("c1-materializer-decision-order-")
        val source = com.wingedsheep.gym.trainer.trajectory.validFixture().twoDecisionEpisode()
        val published = publishC1Fixture(root, source)
        val output = root.resolve("output")
        val manifest = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = output,
            sourceCommit = "e".repeat(40),
            config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
        )
        val samples = Files.readString(output.resolve("samples.ndjson"))
            .lineSequence()
            .filter(String::isNotEmpty)
            .map { line ->
                A3SemanticJson.decodeStrict(
                    C1DerivedSampleV1.serializer(),
                    A3SemanticJson.strictJson.parseToJsonElement(line),
                    "derived sample",
                )
            }
            .toList()

        samples.map { it.sourceReference.decisionIndex } shouldBe listOf(0, 1)
        samples.map { it.sourceReference.replayActionIndex } shouldBe listOf(0, 1)
        samples.map { it.sourceReference.replayFrameIndex } shouldBe listOf(0, 1)
        val partition = C1DatasetSplitV1.assign(source.trajectory.semanticEpisodeId)
        samples.all { it.partition == partition } shouldBe true
        when (partition) {
            C1DatasetPartition.TRAIN -> manifest.sampleCountsByPartition.train
            C1DatasetPartition.VALIDATION -> manifest.sampleCountsByPartition.validation
            C1DatasetPartition.TEST -> manifest.sampleCountsByPartition.test
        } shouldBe 2
    }

    test("excludes quarantined episodes and preserves interrupted samples without synthetic values") {
        val root = Files.createTempDirectory("c1-materializer-quarantine-")
        val source = com.wingedsheep.gym.trainer.trajectory.validFixture().withClosure(
            EpisodeClosureV1.Interrupted(
                stepCount = 1,
                reason = EpisodeInterruptionReason.HORIZON_REACHED,
            ),
        )
        val published = publishC1FixtureWithQuarantine(root, source)
        val output = root.resolve("output")
        val manifest = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = output,
            sourceCommit = "e".repeat(40),
            config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
        )
        val samples = Files.readString(output.resolve("samples.ndjson"))

        manifest.episodeCount shouldBe 1
        manifest.sampleCount shouldBe 1
        samples shouldNotContain "\"reward\""
        samples shouldNotContain "\"label\""
    }

    test("preserves zero-sample episode counts") {
        val root = Files.createTempDirectory("c1-materializer-zero-sample-")
        val published = publishC1Fixture(root, com.wingedsheep.gym.trainer.trajectory.validFixture()
            .zeroDecisionEpisode())
        val output = root.resolve("output")
        val manifest = C1LearnerArtifactMaterializer.materialize(
            publishedDatasetDirectory = published.root,
            outputDirectory = output,
            sourceCommit = "e".repeat(40),
            config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
        )

        manifest.episodeCount shouldBe 1
        manifest.sampleCount shouldBe 0
        manifest.episodeCountsByPartition.total() shouldBe 1L
        manifest.sampleCountsByPartition.total() shouldBe 0L
        Files.readAllBytes(output.resolve("samples.ndjson")).isEmpty() shouldBe true
    }

    test("rejects a corrupt A7 shard before publishing derived files") {
        val root = Files.createTempDirectory("c1-materializer-corrupt-shard-")
        val published = publishC1Fixture(root)
        val shard = published.manifest.shards.single()
        val shardPath = published.root.resolve(shard.contentReference)
        val bytes = Files.readAllBytes(shardPath)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        Files.write(shardPath, bytes)
        val output = root.resolve("output")

        shouldThrow<Exception> {
            C1LearnerArtifactMaterializer.materialize(
                publishedDatasetDirectory = published.root,
                outputDirectory = output,
                sourceCommit = "e".repeat(40),
                config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
            )
        }
        Files.exists(output.resolve("manifest.json")) shouldBe false
        Files.exists(output.resolve("samples.ndjson")) shouldBe false
    }

    test("rejects a corrupt A7 manifest before publishing derived files") {
        val root = Files.createTempDirectory("c1-materializer-corrupt-")
        val published = publishC1Fixture(root)
        val manifestPath = published.root.resolve("manifest.json")
        val original = Files.readString(manifestPath)
        val corrupted = original.replace(
            published.manifest.manifestContentDigest,
            "f".repeat(64),
        )
        Files.writeString(manifestPath, corrupted, StandardCharsets.UTF_8)
        val output = root.resolve("output")

        shouldThrow<Exception> {
            C1LearnerArtifactMaterializer.materialize(
                publishedDatasetDirectory = published.root,
                outputDirectory = output,
                sourceCommit = "e".repeat(40),
                config = C1MaterializerConfig("c1-materializer@v1", "d".repeat(64)),
            )
        }

        Files.exists(output.resolve("manifest.json")) shouldBe false
        Files.exists(output.resolve("samples.ndjson")) shouldBe false
    }

    test("rejects an invalid config digest before opening the source") {
        val root = Files.createTempDirectory("c1-materializer-config-")
        val output = root.resolve("output")

        shouldThrow<IllegalArgumentException> {
            C1LearnerArtifactMaterializer.materialize(
                publishedDatasetDirectory = root.resolve("missing-source"),
                outputDirectory = output,
                sourceCommit = "e".repeat(40),
                config = C1MaterializerConfig("c1-materializer@v1", "bad"),
            )
        }
        Files.exists(output) shouldBe false
    }
})

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun hasUtf8Bom(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()

private fun independentDerivedArtifactId(manifest: C1DerivedManifestV1): String {
    val payload = buildJsonObject {
        put("schema", C1_DERIVED_ARTIFACT_IDENTITY_SCHEMA)
        put("derivedViewSchemaIdentity", manifest.derivedViewSchemaIdentity)
        put("sourceDatasetId", manifest.sourceDatasetId)
        put("sourceManifestContentDigest", manifest.sourceManifestContentDigest)
        put("trajectorySchemaIdentity", manifest.trajectorySchemaIdentity)
        put("modelFacingContractIdentity", manifest.modelFacingContractIdentity)
        put("splitContractIdentity", manifest.splitContractIdentity)
        put(
            "materializerImplementationIdentity",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1MaterializerImplementationIdentity.serializer(),
                manifest.materializerImplementationIdentity,
            ),
        )
        put("materializerConfigDigest", manifest.materializerConfigDigest)
        put("samplesContentDigest", manifest.samplesContentDigest)
        put(
            "episodeCountsByPartition",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1PartitionCounts.serializer(),
                manifest.episodeCountsByPartition,
            ),
        )
        put(
            "sampleCountsByPartition",
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1PartitionCounts.serializer(),
                manifest.sampleCountsByPartition,
            ),
        )
    }
    return A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(payload).toByteArray(StandardCharsets.UTF_8),
    )
}
