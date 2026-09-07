package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.rundiagnostics.DiagnosticsRecorder
import com.wingedsheep.rundiagnostics.MonotonicClock
import com.wingedsheep.rundiagnostics.ProgressHistoryKind
import com.wingedsheep.rundiagnostics.RunStatusV1
import com.wingedsheep.rundiagnostics.StageRefV1
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class RunDiagnosticsReaderIntegrationTest : FunSpec({

    test("preflight and successful yields expose real bounded reader progress") {
        val fixture = validFixture()
        val output = Files.createTempDirectory("d4-reader-")
        val writer = TrajectoryV1Writer(
            outputDirectory = output,
            metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
        )
        val manifest = try {
            writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            writer.finalizeDataset()
        } finally {
            writer.close()
        }

        val recorder = recorder()
        try {
            val dataset = TrajectoryV1Reader.openPublishedDataset(
                output.resolve("dataset-${manifest.datasetId}"),
                diagnosticsRecorder = recorder,
            )
            checkNotNull(recorder.snapshot()).currentStage shouldBe ReaderDiagnosticsStageV1.OPEN

            dataset.streamEpisodes().toList() shouldBe listOf(fixture.trajectory)
            val status = checkNotNull(recorder.snapshot())
            status.currentStage shouldBe ReaderDiagnosticsStageV1.COMPLETE
            status.progress.trajectoryDecisionCount shouldBe fixture.trajectory.decisions.size.toLong()
            recorder.recentHistory().count { it.kind == ProgressHistoryKind.STAGE_CHANGED } shouldBe 5
        } finally {
            recorder.close()
        }
    }

    test("reader diagnostics failure does not change the trusted stream") {
        val fixture = validFixture()
        val output = Files.createTempDirectory("d4-reader-failure-")
        val writer = TrajectoryV1Writer(
            outputDirectory = output,
            metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
        )
        val manifest = try {
            writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            writer.finalizeDataset()
        } finally {
            writer.close()
        }

        val dataset = TrajectoryV1Reader.openPublishedDataset(
            output.resolve("dataset-${manifest.datasetId}"),
            diagnosticsRecorder = ReaderThrowingRecorder(),
        )
        dataset.streamEpisodes().toList() shouldBe listOf(fixture.trajectory)
    }

    test("reader output is equivalent with diagnostics disabled and enabled") {
        val fixture = validFixture()
        val output = Files.createTempDirectory("d4-reader-equivalence-")
        val writer = TrajectoryV1Writer(
            outputDirectory = output,
            metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
        )
        val manifest = try {
            writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            writer.finalizeDataset()
        } finally {
            writer.close()
        }
        val root = output.resolve("dataset-${manifest.datasetId}")

        val disabled = TrajectoryV1Reader.openPublishedDataset(root).streamEpisodes().toList()
        val recorder = recorder()
        val enabled = try {
            TrajectoryV1Reader.openPublishedDataset(root, diagnosticsRecorder = recorder)
                .streamEpisodes().toList()
        } finally {
            recorder.close()
        }

        enabled shouldBe disabled
    }
})

private fun recorder(): DiagnosticsRecorder = DiagnosticsRecorder.enabled(
    diagnosticRunId = "d4-reader-run",
    sourceCommit = "c".repeat(40),
    workloadType = "trajectory-reader",
    initialStage = ReaderDiagnosticsStageV1.INITIALIZING,
    processId = ProcessHandle.current().pid(),
    wallClock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
    monotonicClock = ReaderTestMonotonicClock(),
)

private class ReaderTestMonotonicClock : MonotonicClock {
    private val now = AtomicLong(0)

    override fun nowNanos(): Long = now.getAndAdd(1_000_000L)
}

private class ReaderThrowingRecorder : DiagnosticsRecorder {
    override val enabled: Boolean = true

    override fun heartbeatTick(): Long = error("diagnostics failure")

    override fun advanceStage(stage: StageRefV1): Unit = error("diagnostics failure")

    override fun recordUsefulProgress(
        episodeOrdinal: Long?,
        engineProgressDelta: Long?,
        authoritativeTransitionDelta: Long?,
        semanticDecisionDelta: Long?,
        trajectoryDecisionDelta: Long?,
        replayFramesVerifiedDelta: Long?,
        episodesAdmittedDelta: Long?,
        bytesSerializedDelta: Long?,
        shardsFinalizedDelta: Long?,
    ): Unit = error("diagnostics failure")

    override fun recordArtifactCounters(counters: List<com.wingedsheep.rundiagnostics.ArtifactCounterV1>) =
        error("diagnostics failure")

    override fun snapshot(): RunStatusV1? = null

    override fun recentHistory(): List<com.wingedsheep.rundiagnostics.ProgressHistoryEntryV1> = emptyList()

    override fun close() = Unit
}
