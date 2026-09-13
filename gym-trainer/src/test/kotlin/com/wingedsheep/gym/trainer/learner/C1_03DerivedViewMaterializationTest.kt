package com.wingedsheep.gym.trainer.learner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

private const val ACCEPTED_SOURCE_DATASET_ID =
    "69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03"
private const val ACCEPTED_SOURCE_MANIFEST_DIGEST =
    "de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2"
private const val ACCEPTED_SOURCE_EPISODES = 64
private const val ACCEPTED_SOURCE_DECISIONS = 125471
private const val ACCEPTED_MATERIALIZER_SOURCE_COMMIT =
    "4eb71de7893395c4abc965d3ce705d623bca8a92"
private const val MATERIALIZER_IMPLEMENTATION_IDENTITY = "c1-materializer@v1"
private const val MATERIALIZER_CONFIG_DIGEST =
    "7d5fbfd0bfe71844fefbd25d3fcce7beac3de8de281d9a2f02cf225aef27364c"

/**
 * Manual-only C1_03 runner for the accepted source dataset.
 *
 * The test is disabled unless both explicit paths are supplied. Ordinary CI therefore compiles
 * this guard but never opens or materializes the multi-gigabyte source dataset.
 */
class C1_03DerivedViewMaterializationTest : FunSpec({
    test("materializes the exact accepted source through C1_00")
        .config(
            enabled = sourcePath() != null && outputPath() != null,
            timeout = 2.hours,
        ) {
            val source = Path.of(requireNotNull(sourcePath())).toAbsolutePath().normalize()
            val output = Path.of(requireNotNull(outputPath())).toAbsolutePath().normalize()
            require(source != output) { "C1_03 output must differ from the source dataset" }
            require(!output.startsWith(source)) { "C1_03 output must not be below the source" }
            require(!source.startsWith(output)) { "C1_03 source must not be below the output" }
            require(Files.isDirectory(source)) { "C1_03 source dataset must be a directory" }
            require(!Files.exists(output.resolve("manifest.json"))) {
                "C1_03 derived manifest already exists"
            }
            require(!Files.exists(output.resolve("samples.ndjson"))) {
                "C1_03 derived samples already exist"
            }

            val manifest = C1LearnerArtifactMaterializer.materialize(
                publishedDatasetDirectory = source,
                outputDirectory = output,
                sourceCommit = ACCEPTED_MATERIALIZER_SOURCE_COMMIT,
                config = C1MaterializerConfig(
                    implementationIdentity = MATERIALIZER_IMPLEMENTATION_IDENTITY,
                    configDigest = MATERIALIZER_CONFIG_DIGEST,
                ),
            )

            manifest.sourceDatasetId shouldBe ACCEPTED_SOURCE_DATASET_ID
            manifest.sourceManifestContentDigest shouldBe ACCEPTED_SOURCE_MANIFEST_DIGEST
            manifest.derivedViewSchemaIdentity shouldBe C1_DERIVED_VIEW_SCHEMA_IDENTITY
            manifest.materializerImplementationIdentity.implementation shouldBe
                MATERIALIZER_IMPLEMENTATION_IDENTITY
            manifest.materializerImplementationIdentity.sourceCommit shouldBe
                ACCEPTED_MATERIALIZER_SOURCE_COMMIT
            manifest.materializerConfigDigest shouldBe MATERIALIZER_CONFIG_DIGEST
            manifest.episodeCount shouldBe ACCEPTED_SOURCE_EPISODES
            manifest.sampleCount shouldBe ACCEPTED_SOURCE_DECISIONS
            manifest.recomputeDerivedArtifactId() shouldBe manifest.derivedArtifactId
            manifest.recomputeManifestContentDigest() shouldBe manifest.manifestContentDigest

            println("C1_03_DERIVED_VIEW_REGENERATED=YES")
            println("C1_03_DERIVED_VIEW_PURPOSE=C1_03_CHARACTERIZATION_ONLY")
            println("C1_03_SOURCE_DATASET_ID=${manifest.sourceDatasetId}")
            println("C1_03_SOURCE_MANIFEST_CONTENT_DIGEST=${manifest.sourceManifestContentDigest}")
            println("C1_03_DERIVED_ARTIFACT_ID=${manifest.derivedArtifactId}")
            println("C1_03_SAMPLES_CONTENT_DIGEST=${manifest.samplesContentDigest}")
            println("C1_03_DERIVED_EPISODE_COUNT=${manifest.episodeCount}")
            println("C1_03_DERIVED_SAMPLE_COUNT=${manifest.sampleCount}")
        }
})

private fun sourcePath(): String? =
    System.getProperty("c1_03.dataset") ?: System.getenv("C1_03_DATASET")

private fun outputPath(): String? =
    System.getProperty("c1_03.output") ?: System.getenv("C1_03_OUTPUT")
