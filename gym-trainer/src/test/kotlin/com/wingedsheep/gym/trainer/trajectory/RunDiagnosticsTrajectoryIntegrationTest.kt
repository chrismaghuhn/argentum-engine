package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.rundiagnostics.DiagnosticsRecorder
import com.wingedsheep.rundiagnostics.MonotonicClock
import com.wingedsheep.rundiagnostics.RunStatusV1
import com.wingedsheep.rundiagnostics.StageRefV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class RunDiagnosticsTrajectoryIntegrationTest : FunSpec({

    test("only admitted trajectory units advance trajectory progress") {
        val fixture = validFixture()
        val accepted = fixture.withPolicySeed(4259906L)
        val recorder = recorder(TrajectoryDiagnosticsStageV1.ADMITTING)
        val writer = TrajectoryV1Writer(
            outputDirectory = Files.createTempDirectory("d4-trajectory-admission-"),
            metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
            diagnosticsRecorder = recorder,
        )

        try {
            writer.appendEpisode(
                episodeOrdinal = 0,
                trajectory = fixture.trajectory.copy(trajectoryId = "0".repeat(64)),
                replayTrajectoryBinding = fixture.binding,
            ).shouldBeInstanceOf<TrajectoryAdmissionResult.Quarantined>()
            checkNotNull(recorder.snapshot()).progress.trajectoryDecisionCount shouldBe null
            checkNotNull(recorder.snapshot()).progress.episodesAdmitted shouldBe null

            writer.appendEpisode(1, accepted.trajectory, accepted.binding)
                .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
            val afterAdmission = checkNotNull(recorder.snapshot())
            afterAdmission.progress.trajectoryDecisionCount shouldBe accepted.trajectory.decisions.size.toLong()
            afterAdmission.progress.episodesAdmitted shouldBe 1L

            val manifest = writer.finalizeDataset()
            val afterPublication = checkNotNull(recorder.snapshot())
            afterPublication.progress.shardsFinalized shouldBe manifest.shards.size.toLong()
            afterPublication.currentStage shouldBe TrajectoryDiagnosticsStageV1.PUBLISHED
        } finally {
            writer.close()
            recorder.close()
        }
    }

    test("failed final publication does not advance durable publication progress") {
        val fixture = validFixture()
        val recorder = recorder(TrajectoryDiagnosticsStageV1.ADMITTING)
        val output = Files.createTempDirectory("d4-trajectory-publication-failure-")
        val writer = TrajectoryV1Writer(
            outputDirectory = output,
            metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 1),
            atomicMove = { source, target ->
                if (target.fileName.toString().startsWith("dataset-")) {
                    throw IOException("synthetic final publication failure")
                }
                Files.move(source, target)
            },
            diagnosticsRecorder = recorder,
        )

        try {
            writer.appendEpisode(0, fixture.trajectory, fixture.binding)
                .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
            shouldThrow<TrajectoryV1StorageException> { writer.finalizeDataset() }

            val status = checkNotNull(recorder.snapshot())
            status.progress.trajectoryDecisionCount shouldBe fixture.trajectory.decisions.size.toLong()
            status.progress.shardsFinalized shouldBe null
        } finally {
            writer.close()
            recorder.close()
        }
    }

    test("diagnostics failure is non-fatal to A5/A6 and publication") {
        val fixture = validFixture()
        val writer = TrajectoryV1Writer(
            outputDirectory = Files.createTempDirectory("d4-trajectory-diagnostics-failure-"),
            metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
            diagnosticsRecorder = TrajectoryThrowingRecorder(),
        )

        try {
            writer.appendEpisode(0, fixture.trajectory, fixture.binding)
                .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
            writer.finalizeDataset().counts.episodeCount shouldBe 1
        } finally {
            writer.close()
        }
    }

    test("diagnostics enabled and disabled produce identical trusted trajectory output") {
        val fixture = validFixture()
        val disabled = publish(fixture, diagnosticsRecorder = null)
        val recorder = recorder(TrajectoryDiagnosticsStageV1.ADMITTING)
        val enabled = publish(fixture, diagnosticsRecorder = recorder)

        enabled.manifest shouldBe disabled.manifest
        enabled.episodes shouldBe disabled.episodes
        checkNotNull(recorder.snapshot()).progress.episodesAdmitted shouldBe 1L
        recorder.close()
    }
})

private data class PublishedTrajectory(
    val manifest: DatasetManifestV1,
    val episodes: List<TrajectoryV1>,
)

private fun publish(
    fixture: AdmissionFixture,
    diagnosticsRecorder: DiagnosticsRecorder?,
): PublishedTrajectory {
    val output = Files.createTempDirectory("d4-trajectory-equivalence-")
    val writer = TrajectoryV1Writer(
        outputDirectory = output,
        metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2),
        diagnosticsRecorder = diagnosticsRecorder,
    )
    return try {
        writer.appendEpisode(0, fixture.trajectory, fixture.binding)
            .shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>()
        val manifest = writer.finalizeDataset()
        val root = output.resolve("dataset-${manifest.datasetId}")
        PublishedTrajectory(
            manifest = manifest,
            episodes = TrajectoryV1Reader.openPublishedDataset(root).streamEpisodes().toList(),
        )
    } finally {
        writer.close()
    }
}

private fun recorder(initialStage: StageRefV1): DiagnosticsRecorder = DiagnosticsRecorder.enabled(
    diagnosticRunId = "d4-trajectory-run",
    sourceCommit = "b".repeat(40),
    workloadType = "trajectory",
    initialStage = initialStage,
    processId = ProcessHandle.current().pid(),
    wallClock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC),
    monotonicClock = TrajectoryTestMonotonicClock(),
)

private class TrajectoryTestMonotonicClock : MonotonicClock {
    private val now = AtomicLong(0)

    override fun nowNanos(): Long = now.getAndAdd(1_000_000L)
}

private class TrajectoryThrowingRecorder : DiagnosticsRecorder {
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
