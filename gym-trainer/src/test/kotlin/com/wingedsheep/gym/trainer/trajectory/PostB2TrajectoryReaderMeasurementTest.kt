package com.wingedsheep.gym.trainer.trajectory

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

private const val ACCEPTED_DATASET_ID =
    "69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03"
private const val ACCEPTED_MANIFEST_CONTENT_DIGEST =
    "de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2"

/**
 * Opt-in, read-only timing probe for the accepted A7 reader path. The test is disabled unless an
 * external dataset path is supplied, so ordinary CI never depends on a local multi-gigabyte
 * artifact. The reader handle is intentionally obtained and consumed only through its public API.
 */
class PostB2TrajectoryReaderMeasurementTest : FunSpec({
    test("strict A7 reader characterizes the accepted finalized dataset")
        .config(enabled = datasetPath() != null, timeout = 2.hours) {
            val root = Path.of(requireNotNull(datasetPath()))
            val gcBefore = gcSnapshot()
            val heapSamples = mutableListOf<Long>()

            val preflightStarted = System.nanoTime()
            val dataset = TrajectoryV1Reader.openPublishedDataset(root)
            heapSamples += heapUsed()
            val preflightNanos = System.nanoTime() - preflightStarted

            dataset.manifest.datasetId shouldBe ACCEPTED_DATASET_ID
            dataset.manifest.manifestContentDigest shouldBe ACCEPTED_MANIFEST_CONTENT_DIGEST

            var episodeCount = 0
            var decisionCount = 0
            val streamStarted = System.nanoTime()
            dataset.streamEpisodes().forEach { trajectory ->
                episodeCount += 1
                decisionCount += trajectory.decisions.size
                heapSamples += heapUsed()
            }
            val streamNanos = System.nanoTime() - streamStarted
            val gcAfter = gcSnapshot()

            episodeCount shouldBe dataset.manifest.counts.episodeCount
            decisionCount shouldBe dataset.manifest.counts.decisionCount

            println("POST_B2_A7_READER_MEASUREMENT")
            println("DATASET_ROOT=${root.toAbsolutePath().normalize()}")
            println("DATASET_ID=${dataset.manifest.datasetId}")
            println("MANIFEST_CONTENT_DIGEST=${dataset.manifest.manifestContentDigest}")
            println("EPISODES=$episodeCount")
            println("DECISIONS=$decisionCount")
            println("A7_PREFLIGHT_TIME_MILLIS=${preflightNanos / 1_000_000.0}")
            println("A7_STREAM_TIME_MILLIS=${streamNanos / 1_000_000.0}")
            println(
                "A7_TOTAL_STRICT_READER_TIME_MILLIS=" +
                    ((preflightNanos + streamNanos) / 1_000_000.0),
            )
            println("A7_STREAM_DECISIONS_PER_SEC=${ratePerSecond(decisionCount, streamNanos)}")
            println("A7_STREAM_EPISODES_PER_SEC=${ratePerSecond(episodeCount, streamNanos)}")
            println("JVM_HEAP_USED_AFTER_PREFLIGHT_BYTES=${heapSamples.first()}")
            println("JVM_HEAP_USED_MAX_SAMPLED_BYTES=${heapSamples.maxOrNull()}")
            println("JVM_HEAP_USED_AFTER_STREAM_BYTES=${heapSamples.last()}")
            println("GC_COLLECTION_COUNT_DELTA=${gcAfter.count - gcBefore.count}")
            println("GC_COLLECTION_TIME_MILLIS_DELTA=${gcAfter.timeMillis - gcBefore.timeMillis}")
            println("PHASE_SPLIT_FRAME_DECODE=UNMEASURED")
            println("PHASE_SPLIT_A5_RECONSTRUCTION=UNMEASURED")
            println("PEAK_RSS=UNMEASURED")
    }
})

private fun datasetPath(): String? =
    System.getProperty("postB2.dataset") ?: System.getenv("POST_B2_DATASET")

private fun heapUsed(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

private data class GcSnapshot(
    val count: Long,
    val timeMillis: Long,
)

private fun gcSnapshot(): GcSnapshot = ManagementFactory.getGarbageCollectorMXBeans()
    .filter { it.collectionCount >= 0 && it.collectionTime >= 0 }
    .fold(GcSnapshot(0, 0)) { total, bean ->
        GcSnapshot(
            count = total.count + bean.collectionCount,
            timeMillis = total.timeMillis + bean.collectionTime,
        )
    }

private fun ratePerSecond(count: Int, nanos: Long): Double =
    if (nanos == 0L) 0.0 else count.toDouble() * 1_000_000_000.0 / nanos
