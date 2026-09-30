package com.wingedsheep.gym

import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer
import io.kotest.core.spec.style.FunSpec
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours

private val ka07BenchmarkEnabled = System.getProperty("ka07.benchmark") == "true"

/** Fixed engine commit so identities are comparable across source revisions of this benchmark. */
private const val KA07_BENCHMARK_ENGINE_COMMIT = "0aa9444c6872db6a6527ea479061eb2efafea705"

/**
 * Opt-in shard-generation throughput benchmark on the accepted A9 actor schedule.
 *
 * Generates the first `ka07.episodes` actor episodes exactly as the Kaggle actor does and records,
 * per episode, wall time plus every output identity that must stay byte-identical across a pure
 * performance change: trajectory id, replay content identity, all semantic decision ids, and the
 * live checkpoint fingerprints (which replay content identity deliberately excludes).
 */
class KaggleActor07ShardGenerationBenchmarkTest : FunSpec({
    test("measures actor episode generation throughput and output identities")
        .config(enabled = ka07BenchmarkEnabled, timeout = 8.hours) {
            val episodes = System.getProperty("ka07.episodes")?.toInt() ?: 1
            require(episodes in 1..64) { "ka07.episodes must be in 1..64" }
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val outputFile = Path.of(
                System.getProperty("ka07.outputFile")
                    ?: repositoryRoot.resolve("gym/build/ka07-benchmark.tsv").toString(),
            )
            val registry = A9TrustedGenerationHarness.actorRegistry()
            val resolver = DeckResolver(registry)
            val policySourceIdentity = A9TrustedGenerationHarness.actorPolicySourceIdentity(repositoryRoot)
            val memory = ManagementFactory.getMemoryMXBean()

            val lines = mutableListOf(
                listOf(
                    "ordinal", "decisions", "closure", "seconds", "decisionsPerSecond", "writeSeconds", "heapUsedMb",
                    "trajectoryId", "replayContentIdentity", "semanticDecisionIdsDigest",
                    "checkpointFingerprintsDigest",
                ).joinToString("\t"),
            )
            var totalDecisions = 0L
            var totalNanos = 0L
            var totalWriteNanos = 0L
            // Same shard policy as the Kaggle actor: one episode per shard.
            val writerRoot = Files.createTempDirectory("ka07-benchmark-writer-")
            val writer = TrajectoryV1Writer(
                writerRoot,
                DatasetMetadataV1(maxShardBytes = 256L * 1024L * 1024L, maxEpisodesPerShard = 1),
            )
            for ((localOrdinal, schedule) in A9TrustedGenerationHarness.actorSchedule(episodes).withIndex()) {
                val started = System.nanoTime()
                val episode = A9TrustedGenerationHarness.actorGenerateEpisode(
                    schedule = schedule,
                    registry = registry,
                    resolver = resolver,
                    repositoryRoot = repositoryRoot,
                    policySourceIdentity = policySourceIdentity,
                    engineCommit = KA07_BENCHMARK_ENGINE_COMMIT,
                )
                val elapsed = System.nanoTime() - started
                val trajectory = episode.trajectory
                val writeStarted = System.nanoTime()
                val admission = writer.appendEpisode(localOrdinal, trajectory, episode.replayTrajectoryBinding)
                check(admission is TrajectoryAdmissionResult.Admitted) { "Benchmark episode was quarantined: $admission" }
                val writeElapsed = System.nanoTime() - writeStarted
                totalWriteNanos += writeElapsed
                val decisions = trajectory.decisions.size
                totalDecisions += decisions
                totalNanos += elapsed
                val seconds = elapsed / 1e9
                lines += listOf(
                    schedule.jobOrdinal.toString(),
                    decisions.toString(),
                    trajectory.closure.kind.name,
                    "%.2f".format(seconds),
                    "%.2f".format(decisions / seconds),
                    "%.2f".format(writeElapsed / 1e9),
                    (memory.heapMemoryUsage.used / (1024 * 1024)).toString(),
                    trajectory.trajectoryId,
                    trajectory.compactReplayLink.replayContentIdentity,
                    sha256Lines(trajectory.decisions.map { it.semanticDecisionId.value }),
                    sha256Lines(episode.replayCheckpoints.map { "${it.afterActionCount}:${it.fingerprint}" }),
                ).joinToString("\t")
                println(lines.last())
            }
            val finalizeStarted = System.nanoTime()
            writer.finalizeDataset()
            writer.close()
            totalWriteNanos += System.nanoTime() - finalizeStarted
            writerRoot.toFile().deleteRecursively()
            val totalSeconds = totalNanos / 1e9
            val endToEndSeconds = (totalNanos + totalWriteNanos) / 1e9
            lines += "# total decisions=$totalDecisions generateSeconds=${"%.2f".format(totalSeconds)} " +
                "writeSeconds=${"%.2f".format(totalWriteNanos / 1e9)} " +
                "generateDecisionsPerSecond=${"%.2f".format(totalDecisions / totalSeconds)} " +
                "endToEndDecisionsPerSecond=${"%.2f".format(totalDecisions / endToEndSeconds)}"
            println(lines.last())
            Files.createDirectories(outputFile.parent)
            Files.writeString(outputFile, lines.joinToString("\n", postfix = "\n"))
        }
})

private fun sha256Lines(values: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("\n").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
