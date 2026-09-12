package com.wingedsheep.gym.trainer.learner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class C1DerivedManifestV1Test : FunSpec({
    fun manifest(
        episodeCount: Int = 3,
        sampleCount: Int = 5,
        episodeCounts: C1PartitionCounts = C1PartitionCounts(
            train = 1,
            validation = 1,
            test = 1,
        ),
        sampleCounts: C1PartitionCounts = C1PartitionCounts(
            train = 2,
            validation = 2,
            test = 1,
        ),
    ): C1DerivedManifestV1 = C1DerivedManifestV1(
        derivedArtifactId = "a".repeat(64),
        sourceDatasetId = "b".repeat(64),
        sourceManifestContentDigest = "c".repeat(64),
        trajectorySchemaIdentity = "argentum-trajectory@v1",
        modelFacingContractIdentity = "argentum-ml-model-facing-decision-sample@v1",
        splitContractIdentity = "argentum-ml-dataset-split@v1",
        materializerImplementationIdentity = C1MaterializerImplementationIdentity(
            implementation = "c1-test-materializer@v1",
            sourceCommit = "d".repeat(40),
        ),
        materializerConfigDigest = "e".repeat(64),
        samplesContentDigest = "f".repeat(64),
        samplesByteCount = 128,
        sampleCount = sampleCount,
        episodeCount = episodeCount,
        episodeCountsByPartition = episodeCounts,
        sampleCountsByPartition = sampleCounts,
        manifestContentDigest = "1".repeat(64),
    )

    test("accepts matching episode and sample partition totals") {
        val value = manifest()

        value.episodeCountsByPartition.total() shouldBe value.episodeCount
        value.sampleCountsByPartition.total() shouldBe value.sampleCount
        value.samplesContentReference shouldBe "samples.ndjson"
        value.recomputeDerivedArtifactId().length shouldBe 64
        value.recomputeManifestContentDigest().length shouldBe 64
    }

    test("rejects partition totals that disagree with declared totals") {
        shouldThrow<IllegalArgumentException> {
            manifest(episodeCount = 2)
        }
        shouldThrow<IllegalArgumentException> {
            manifest(sampleCount = 4)
        }
    }

    test("rejects unsupported versions and physical references") {
        shouldThrow<IllegalArgumentException> {
            manifest().copy(version = 2)
        }
        shouldThrow<IllegalArgumentException> {
            manifest().copy(samplesContentReference = "other.ndjson")
        }
    }

    test("derived identity changes with source or sample content identity") {
        val original = manifest().recomputeDerivedArtifactId()
        val changedSource = manifest().copy(sourceDatasetId = "2".repeat(64)).recomputeDerivedArtifactId()
        val changedSamples = manifest().copy(samplesContentDigest = "2".repeat(64)).recomputeDerivedArtifactId()

        original shouldBe manifest().recomputeDerivedArtifactId()
        changedSource shouldNotBe original
        changedSamples shouldNotBe original
    }
})
