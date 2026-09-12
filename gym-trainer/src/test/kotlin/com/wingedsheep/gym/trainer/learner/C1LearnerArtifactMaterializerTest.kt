package com.wingedsheep.gym.trainer.learner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

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
})

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun hasUtf8Bom(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
