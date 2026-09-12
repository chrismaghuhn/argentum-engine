package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.trainer.trajectory.TRAJECTORY_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Reader
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists

data class C1MaterializerConfig(
    val implementationIdentity: String,
    val configDigest: String,
)

object C1LearnerArtifactMaterializer {
    fun materialize(
        publishedDatasetDirectory: Path,
        outputDirectory: Path,
        sourceCommit: String,
        config: C1MaterializerConfig,
    ): C1DerivedManifestV1 {
        val implementationIdentity = C1MaterializerImplementationIdentity(
            implementation = config.implementationIdentity,
            sourceCommit = sourceCommit,
        )
        require(!Files.isSymbolicLink(outputDirectory)) {
            "Derived artifact output directory must not be a symlink"
        }
        Files.createDirectories(outputDirectory)
        require(Files.isDirectory(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "Derived artifact output path must be a directory"
        }
        val samplesPath = outputDirectory.resolve("samples.ndjson")
        val manifestPath = outputDirectory.resolve("manifest.json")
        require(!Files.exists(samplesPath, LinkOption.NOFOLLOW_LINKS)) {
            "Derived artifact samples.ndjson already exists"
        }
        require(!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            "Derived artifact manifest.json already exists"
        }

        val stagingDirectory = Files.createTempDirectory(outputDirectory, ".c1-staging-")
        var samplesPublished = false
        var manifestPublished = false
        try {
            val stagingSamplesPath = stagingDirectory.resolve("samples.ndjson")
            val source = TrajectoryV1Reader.openPublishedDataset(publishedDatasetDirectory)
            val episodeCounts = PartitionCounter()
            val sampleCounts = PartitionCounter()
            var episodeCount = 0
            var sampleCount = 0
            var sampleByteCount = 0L
            val sampleDigest = MessageDigest.getInstance("SHA-256")

            BufferedOutputStream(
                Files.newOutputStream(
                    stagingSamplesPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ),
            ).use { samples ->
                source.streamEpisodes().forEach { trajectory ->
                    val partition = C1DatasetSplitV1.assign(trajectory.semanticEpisodeId)
                    episodeCount = checkedIncrement(episodeCount, "episode count")
                    episodeCounts.increment(partition)
                    trajectory.decisions.forEach { record ->
                        val sample = C1ModelFacingProjectionV1.project(
                            trajectory = trajectory,
                            record = record,
                            context = C1ProjectionContext(
                                datasetId = source.manifest.datasetId,
                                sourceManifestContentDigest = source.manifest.manifestContentDigest,
                            ),
                            partition = partition,
                        )
                        val line = canonicalSampleBytes(sample)
                        samples.write(line)
                        samples.write(LF.toInt())
                        sampleDigest.update(line)
                        sampleDigest.update(LF)
                        sampleByteCount = Math.addExact(sampleByteCount, line.size.toLong() + 1L)
                        sampleCount = checkedIncrement(sampleCount, "sample count")
                        sampleCounts.increment(partition)
                    }
                }
            }

            val sampleContentDigest = sampleDigest.digest().toHex()
            val placeholder = C1DerivedManifestV1(
                derivedArtifactId = ZERO_SHA256,
                sourceDatasetId = source.manifest.datasetId,
                sourceManifestContentDigest = source.manifest.manifestContentDigest,
                trajectorySchemaIdentity = TRAJECTORY_V1_SCHEMA_IDENTITY,
                modelFacingContractIdentity = C1_MODEL_FACING_CONTRACT_IDENTITY,
                splitContractIdentity = C1_SPLIT_CONTRACT_IDENTITY,
                materializerImplementationIdentity = implementationIdentity,
                materializerConfigDigest = config.configDigest,
                samplesContentDigest = sampleContentDigest,
                samplesByteCount = sampleByteCount,
                sampleCount = sampleCount,
                episodeCount = episodeCount,
                episodeCountsByPartition = episodeCounts.snapshot(),
                sampleCountsByPartition = sampleCounts.snapshot(),
                manifestContentDigest = ZERO_SHA256,
            )
            val withArtifactId = placeholder.copy(
                derivedArtifactId = placeholder.recomputeDerivedArtifactId(),
            )
            val manifest = withArtifactId.copy(
                manifestContentDigest = withArtifactId.recomputeManifestContentDigest(),
            )
            require(manifest.recomputeDerivedArtifactId() == manifest.derivedArtifactId)
            require(manifest.recomputeManifestContentDigest() == manifest.manifestContentDigest)

            val manifestBytes = canonicalManifestBytes(manifest)
            val stagingManifestPath = stagingDirectory.resolve("manifest.json")
            Files.write(
                stagingManifestPath,
                manifestBytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            require(Files.readAllBytes(stagingManifestPath).contentEquals(manifestBytes)) {
                "Derived manifest bytes changed while staged"
            }

            atomicMove(stagingSamplesPath, samplesPath)
            samplesPublished = true
            atomicMove(stagingManifestPath, manifestPath)
            manifestPublished = true
            return manifest
        } catch (failure: Exception) {
            if (manifestPublished) manifestPath.deleteIfExists()
            if (samplesPublished) samplesPath.deleteIfExists()
            throw failure
        } finally {
            deleteTree(stagingDirectory)
        }
    }

    private fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun canonicalSampleBytes(sample: C1DerivedSampleV1): ByteArray =
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1DerivedSampleV1.serializer(),
                sample,
            ),
        ).toByteArray(StandardCharsets.UTF_8)

    private fun canonicalManifestBytes(manifest: C1DerivedManifestV1): ByteArray =
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1DerivedManifestV1.serializer(),
                manifest,
            ),
        ).toByteArray(StandardCharsets.UTF_8)

    private fun checkedIncrement(value: Int, label: String): Int =
        Math.addExact(value, 1).also { require(it >= 0) { "$label overflowed" } }

    private class PartitionCounter {
        private var train = 0
        private var validation = 0
        private var test = 0

        fun increment(partition: C1DatasetPartition) {
            when (partition) {
                C1DatasetPartition.TRAIN -> train = checkedIncrement(train, "TRAIN partition count")
                C1DatasetPartition.VALIDATION -> {
                    validation = checkedIncrement(validation, "VALIDATION partition count")
                }

                C1DatasetPartition.TEST -> test = checkedIncrement(test, "TEST partition count")
            }
        }

        fun snapshot(): C1PartitionCounts = C1PartitionCounts(
            train = train,
            validation = validation,
            test = test,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }

    private const val LF: Byte = 0x0A
    private val ZERO_SHA256: String = "0".repeat(64)
}
