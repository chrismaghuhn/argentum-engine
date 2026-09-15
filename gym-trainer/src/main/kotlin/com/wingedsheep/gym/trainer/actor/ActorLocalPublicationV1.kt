package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Reader
import com.wingedsheep.gym.trainer.trajectory.ValidatedTrajectoryDatasetV1
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

const val LOCAL_PUBLICATION_ENVELOPE_V1_VERSION: Int = 1
const val LOCAL_PUBLICATION_ENVELOPE_V1_SCHEMA_IDENTITY: String =
    "argentum-ml-local-publication-envelope@v1"
const val LOCAL_PUBLICATION_DATASET_DIRECTORY_PREFIX_V1: String = "dataset-"
const val LOCAL_PUBLICATION_ASSIGNMENT_FILE_V1: String = "assignment.json"
const val LOCAL_PUBLICATION_STATUS_FILE_V1: String = "status-final.json"
const val LOCAL_PUBLICATION_RUN_REPORT_FILE_V1: String = "run-report.json"

/** Local, provider-neutral output envelope around an already finalized B2 dataset. */
data class LocalPublishedEnvelopeV1(
    val envelopeDirectory: Path,
    val datasetDirectory: Path,
    val assignment: WorkAssignmentV1,
    val status: ActorStatusV1,
    val report: RunReportV1,
    val manifest: DatasetManifestV1,
)

/** A strict re-import handle; every stream pass remains backed by the existing A7 reader. */
class ReimportedLocalPublicationEnvelopeV1 internal constructor(
    val envelopeDirectory: Path,
    val datasetDirectory: Path,
    val assignment: WorkAssignmentV1,
    val status: ActorStatusV1,
    val report: RunReportV1,
    val manifest: DatasetManifestV1,
    private val validatedDataset: ValidatedTrajectoryDatasetV1,
) {
    fun streamEpisodes(): Sequence<TrajectoryV1> = validatedDataset.streamEpisodes()
}

/** Copies complete local B2 output and reopens it through the strict existing reader. */
object LocalPublicationEnvelopeV1 {
    fun publish(
        sourceDatasetDirectory: Path,
        destinationDirectory: Path,
        assignment: WorkAssignmentV1,
        status: ActorStatusV1,
        report: RunReportV1,
    ): LocalPublishedEnvelopeV1 {
        require(status.assignmentIdentity == assignment.assignmentIdentity) {
            "Local publication status does not belong to the assignment"
        }
        require(report.assignmentIdentity == assignment.assignmentIdentity) {
            "Local publication report does not belong to the assignment"
        }
        require(status.executionAttemptIdentity == report.executionAttemptIdentity) {
            "Local publication status and report attempts disagree"
        }
        require(status.state == ActorStateV1.FINALIZED_LOCAL) {
            "Only finalized-local actor status can enter a publication envelope"
        }
        require(report.finalState == ActorStateV1.FINALIZED_LOCAL) {
            "Only finalized-local actor output can enter a publication envelope"
        }
        requireSourceProvenance(assignment, report)

        val validatedDataset = TrajectoryV1Reader.openPublishedDataset(sourceDatasetDirectory)
        val manifest = validatedDataset.manifest
        require(report.localShardDigests == manifest.shards.map { it.contentDigest }) {
            "Local publication report shard digests disagree with the B2 manifest"
        }

        Files.createDirectories(destinationDirectory)
        val finalDirectory = destinationDirectory.resolve("envelope-${manifest.datasetId}")
        require(!Files.exists(finalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "Local publication envelope destination already exists"
        }
        val stagingRoot = destinationDirectory.resolve(".staging")
        Files.createDirectories(stagingRoot)
        val stagingDirectory = Files.createTempDirectory(stagingRoot, "envelope-")
        var moved = false
        try {
            copyTree(
                sourceDatasetDirectory,
                stagingDirectory.resolve(
                    "$LOCAL_PUBLICATION_DATASET_DIRECTORY_PREFIX_V1${manifest.datasetId}",
                ),
            )
            writeNew(
                stagingDirectory.resolve(LOCAL_PUBLICATION_ASSIGNMENT_FILE_V1),
                ActorWorkloadV1Json.encode(assignment),
            )
            writeNew(
                stagingDirectory.resolve(LOCAL_PUBLICATION_STATUS_FILE_V1),
                ActorOperationalV1Json.encode(status),
            )
            writeNew(
                stagingDirectory.resolve(LOCAL_PUBLICATION_RUN_REPORT_FILE_V1),
                ActorOperationalV1Json.encode(report),
            )
            moveNewDirectory(stagingDirectory, finalDirectory)
            moved = true
        } finally {
            if (!moved) deleteTree(stagingDirectory)
        }
        return LocalPublishedEnvelopeV1(
            envelopeDirectory = finalDirectory,
            datasetDirectory = finalDirectory.resolve(
                "$LOCAL_PUBLICATION_DATASET_DIRECTORY_PREFIX_V1${manifest.datasetId}",
            ),
            assignment = assignment,
            status = status,
            report = report,
            manifest = manifest,
        )
    }

    fun reimport(envelopeDirectory: Path): ReimportedLocalPublicationEnvelopeV1 {
        require(Files.isDirectory(envelopeDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "Local publication envelope must be a directory"
        }
        val assignmentEncoded = readRegularFile(
            envelopeDirectory.resolve(LOCAL_PUBLICATION_ASSIGNMENT_FILE_V1),
        )
        val assignment = when (val validation =
            ActorWorkloadV1Json.decodeAndValidateAssignment(assignmentEncoded)
        ) {
            is ActorContractValidationResult.Valid -> validation.assignment
            is ActorContractValidationResult.Rejected ->
                throw IllegalArgumentException("Local publication assignment is invalid")
        }
        val statusEncoded = readRegularFile(
            envelopeDirectory.resolve(LOCAL_PUBLICATION_STATUS_FILE_V1),
        )
        val status = ActorOperationalV1Json.decodeStatus(statusEncoded).also {
            require(statusEncoded == ActorOperationalV1Json.encode(it)) {
                "Local publication status is not canonical"
            }
        }
        val reportEncoded = readRegularFile(
            envelopeDirectory.resolve(LOCAL_PUBLICATION_RUN_REPORT_FILE_V1),
        )
        val report = ActorOperationalV1Json.decodeRunReport(reportEncoded).also {
            require(reportEncoded == ActorOperationalV1Json.encode(it)) {
                "Local publication report is not canonical"
            }
        }
        require(status.assignmentIdentity == assignment.assignmentIdentity) {
            "Re-imported status does not belong to the assignment"
        }
        require(report.assignmentIdentity == assignment.assignmentIdentity) {
            "Re-imported report does not belong to the assignment"
        }
        require(status.executionAttemptIdentity == report.executionAttemptIdentity) {
            "Re-imported status and report attempts disagree"
        }
        require(report.finalState == ActorStateV1.FINALIZED_LOCAL) {
            "Re-imported envelope is not finalized-local output"
        }
        require(status.state == ActorStateV1.FINALIZED_LOCAL) {
            "Re-imported envelope status is not finalized-local"
        }
        requireSourceProvenance(assignment, report)

        val datasetDirectory = findDatasetDirectory(envelopeDirectory)
        val validatedDataset = TrajectoryV1Reader.openPublishedDataset(datasetDirectory)
        val manifest = validatedDataset.manifest
        require(report.localShardDigests == manifest.shards.map { it.contentDigest }) {
            "Re-imported report shard digests disagree with the B2 manifest"
        }
        return ReimportedLocalPublicationEnvelopeV1(
            envelopeDirectory = envelopeDirectory,
            datasetDirectory = datasetDirectory,
            assignment = assignment,
            status = status,
            report = report,
            manifest = manifest,
            validatedDataset = validatedDataset,
        )
    }

    private fun copyTree(source: Path, destination: Path) {
        require(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            "Local publication source dataset must be a directory"
        }
        val paths = Files.walk(source).use { stream ->
            stream.iterator().asSequence().toList().sortedBy { source.relativize(it).toString() }
        }
        paths.forEach { sourcePath ->
            require(!Files.isSymbolicLink(sourcePath)) {
                "Local publication source must not contain symbolic links"
            }
            val relative = source.relativize(sourcePath)
            val destinationPath = destination.resolve(relative.toString())
            if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(destinationPath)
            } else {
                require(Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    "Local publication source contains a non-regular entry"
                }
                Files.createDirectories(destinationPath.parent)
                Files.copy(sourcePath, destinationPath)
            }
        }
    }

    private fun requireSourceProvenance(assignment: WorkAssignmentV1, report: RunReportV1) {
        require(report.sourceRevisionVerified) {
            "Local publication requires verified source revision evidence"
        }
        require(assignment.items.all {
            it.environmentIdentity.engineCommit == report.expectedSourceCommit
        }) {
            "Local publication source revision does not match the assignment"
        }
    }

    private fun findDatasetDirectory(envelopeDirectory: Path): Path {
        val candidates = Files.list(envelopeDirectory).use { stream ->
            stream.filter { path ->
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                    path.fileName.toString().startsWith(LOCAL_PUBLICATION_DATASET_DIRECTORY_PREFIX_V1)
            }.toList()
        }
        require(candidates.size == 1) {
            "Local publication envelope must contain exactly one dataset directory"
        }
        return candidates.single()
    }

    private fun writeNew(path: Path, content: String) {
        Files.write(
            path,
            content.toByteArray(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
        )
    }

    private fun readRegularFile(path: Path): String {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Local publication envelope file must be a regular non-symlink file"
        }
        val bytes = Files.readAllBytes(path)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Local publication envelope file is not valid UTF-8", failure)
        }
    }

    private fun moveNewDirectory(source: Path, destination: Path) {
        try {
            Files.move(source, destination, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        val paths = Files.walk(root).use { stream ->
            stream.iterator().asSequence().toList().sortedByDescending { it.nameCount }
        }
        paths.forEach(Files::deleteIfExists)
    }
}
