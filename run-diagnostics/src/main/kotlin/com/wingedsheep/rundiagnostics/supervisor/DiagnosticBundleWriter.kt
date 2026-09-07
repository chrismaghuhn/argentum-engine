package com.wingedsheep.rundiagnostics.supervisor

import com.wingedsheep.rundiagnostics.RunStatusCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Writes a bounded evidence bundle. Safe scalar files are kept at the bundle root; arbitrary JVM
 * command text is written only below privileged/ and is explicitly marked not dataset-safe.
 */
public class DiagnosticBundleWriter(
    root: Path,
    private val maxDiagnosticBundles: Int = SupervisorSchema.DEFAULT_MAX_DIAGNOSTIC_BUNDLES,
    private val maxBundleBytes: Int = SupervisorSchema.DEFAULT_MAX_BUNDLE_BYTES,
    private val retentionEnforcer: (Path, Int) -> DiagnosticRetentionResult = { stallsDirectory, maxBundles ->
        DiagnosticRetention(stallsDirectory, maxBundles).enforce()
    },
) : DiagnosticBundleSink {
    private val root = root.toAbsolutePath().normalize()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }
    private val retentionFailureRunIds = HashSet<String>()

    init {
        require(maxDiagnosticBundles > 0) { "maxDiagnosticBundles must be positive" }
        require(maxBundleBytes > 0) { "maxBundleBytes must be positive" }
    }

    override fun captureEnabled(diagnosticRunId: String): Boolean = synchronized(this) {
        if (!isSafeBundleToken(diagnosticRunId)) return@synchronized false
        val marker = retentionMarker(root.resolve(diagnosticRunId).normalize())
        if (diagnosticRunId in retentionFailureRunIds) return@synchronized false
        try {
            when {
                Files.isSymbolicLink(marker) -> false
                Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> false
                Files.notExists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS) -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun write(input: DiagnosticBundleInput): DiagnosticBundleResult = synchronized(this) {
        if (!isSafeBundleToken(input.diagnosticRunId) || !isSafeBundleToken(input.stallId)) {
            return@synchronized DiagnosticBundleResult(
                availability = EvidenceAvailability.FAILED,
                failures = listOf(SupervisorFailureCode.BUNDLE_DIRECTORY_FAILED),
            )
        }
        val runDirectory = root.resolve(input.diagnosticRunId).normalize()
        if (!runDirectory.startsWith(root)) {
            return@synchronized DiagnosticBundleResult(
                availability = EvidenceAvailability.FAILED,
                failures = listOf(SupervisorFailureCode.BUNDLE_DIRECTORY_FAILED),
            )
        }
        val stallsDirectory = runDirectory.resolve("stalls").normalize()
        if (!captureEnabled(input.diagnosticRunId)) {
            return@synchronized DiagnosticBundleResult(
                availability = EvidenceAvailability.FAILED,
                failures = listOf(SupervisorFailureCode.RETENTION_FAILED),
            )
        }
        try {
            Files.createDirectories(stallsDirectory)
        } catch (_: Exception) {
            return@synchronized DiagnosticBundleResult(
                availability = EvidenceAvailability.FAILED,
                failures = listOf(SupervisorFailureCode.BUNDLE_DIRECTORY_FAILED),
            )
        }

        val bundleDirectory = stallsDirectory.resolve(input.stallId).normalize()
        if (!bundleDirectory.startsWith(stallsDirectory)) {
            return@synchronized DiagnosticBundleResult(
                EvidenceAvailability.FAILED,
                failures = listOf(SupervisorFailureCode.BUNDLE_DIRECTORY_FAILED),
            )
        }
        try {
            Files.createDirectory(bundleDirectory)
        } catch (_: Exception) {
            return@synchronized DiagnosticBundleResult(
                EvidenceAvailability.FAILED,
                bundleDirectory = bundleDirectory,
                failures = listOf(SupervisorFailureCode.BUNDLE_DIRECTORY_FAILED),
            )
        }

        var bytesUsed = 0L
        val files = ArrayList<BundleFileRecordV1>()
        val failures = ArrayList<SupervisorFailureCode>()

        fun recordFile(
            name: String,
            required: Boolean,
            availability: EvidenceAvailability,
            datasetSafe: Boolean,
            bytesWritten: Long? = null,
            failureCode: SupervisorFailureCode? = null,
        ) {
            files += BundleFileRecordV1(
                name = name,
                required = required,
                availability = availability,
                datasetSafe = datasetSafe,
                bytesWritten = bytesWritten,
                failureCode = failureCode,
            )
            failureCode?.let { failures += it }
        }

        fun writeBoundedFile(
            name: String,
            required: Boolean,
            datasetSafe: Boolean,
            bytes: ByteArray,
        ) {
            if (bytesUsed + bytes.size > maxBundleBytes) {
                recordFile(name, required, EvidenceAvailability.FAILED, datasetSafe, failureCode = SupervisorFailureCode.BUNDLE_TOO_LARGE)
                return
            }
            val destination = bundleDirectory.resolve(name).normalize()
            if (!destination.startsWith(bundleDirectory)) {
                recordFile(name, required, EvidenceAvailability.FAILED, datasetSafe, failureCode = SupervisorFailureCode.BUNDLE_FILE_FAILED)
                return
            }
            try {
                Files.createDirectories(destination.parent)
                val temporary = Files.createTempFile(destination.parent, ".diagnostic-", ".tmp")
                try {
                    FileChannel.open(temporary, WRITE).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                    bytesUsed += bytes.size
                    recordFile(name, required, EvidenceAvailability.AVAILABLE, datasetSafe, bytes.size.toLong())
                } finally {
                    Files.deleteIfExists(temporary)
                }
            } catch (_: Exception) {
                recordFile(name, required, EvidenceAvailability.FAILED, datasetSafe, failureCode = SupervisorFailureCode.BUNDLE_FILE_FAILED)
            }
        }

        fun writeUnavailableArtifact(
            name: String,
            artifactKind: String,
            availability: EvidenceAvailability,
            failureCode: SupervisorFailureCode? = null,
        ) {
            writeBoundedFile(
                name = name,
                required = true,
                datasetSafe = true,
                bytes = encode(
                    BundleArtifactV1.serializer(),
                    BundleArtifactV1(
                        artifactKind = artifactKind,
                        availability = availability,
                        failureCode = failureCode,
                    ),
                ),
            )
        }

        val status = input.status
        if (status == null) {
            writeUnavailableArtifact(
                name = "status.json",
                artifactKind = "STATUS",
                availability = EvidenceAvailability.MISSING,
                failureCode = SupervisorFailureCode.STATUS_MISSING,
            )
        } else {
            try {
                writeBoundedFile(
                    name = "status.json",
                    required = true,
                    datasetSafe = true,
                    bytes = RunStatusCodec.encode(status),
                )
            } catch (_: Exception) {
                writeUnavailableArtifact(
                    name = "status.json",
                    artifactKind = "STATUS",
                    availability = EvidenceAvailability.FAILED,
                    failureCode = SupervisorFailureCode.STATUS_SCHEMA_INVALID,
                )
            }
        }

        writeBoundedFile(
            name = "process-metrics.json",
            required = true,
            datasetSafe = true,
            bytes = encode(ProcessMetricsV1.serializer(), input.metrics),
        )

        if (input.safeArtifactSizes.isEmpty()) {
            writeUnavailableArtifact(
                name = "artifact-sizes.json",
                artifactKind = "ARTIFACT_SIZES",
                availability = EvidenceAvailability.NOT_CONFIGURED,
            )
        } else {
            writeBoundedFile(
                name = "artifact-sizes.json",
                required = true,
                datasetSafe = true,
                bytes = encode(ListSerializer(SafeArtifactSizeV1.serializer()), input.safeArtifactSizes),
            )
        }

        if (input.recentHistory.isEmpty()) {
            writeUnavailableArtifact(
                name = "recent-stages.json",
                artifactKind = "RECENT_STAGES",
                availability = EvidenceAvailability.NOT_CONFIGURED,
            )
        } else {
            writeBoundedFile(
                name = "recent-stages.json",
                required = true,
                datasetSafe = true,
                bytes = encode(ListSerializer(SupervisorHistoryEntryV1.serializer()), input.recentHistory),
            )
        }

        var threadDumpIndex = 0
        input.jvmResults.forEach { result ->
            val name = when (result.kind) {
                JvmCommandKind.THREAD_PRINT, JvmCommandKind.JSTACK -> "privileged/thread-dump-${threadDumpIndex++}.txt"
                JvmCommandKind.GC_HEAP_INFO -> "privileged/heap-info.txt"
                JvmCommandKind.VM_FLAGS -> "privileged/vm-flags.txt"
            }
            if (result.availability == EvidenceAvailability.AVAILABLE && result.output != null) {
                writeBoundedFile(
                    name,
                    required = false,
                    datasetSafe = false,
                    result.output.toByteArray(Charsets.UTF_8),
                )
            } else {
                recordFile(name, required = false, result.availability, datasetSafe = false, failureCode = result.failureCode)
            }
        }

        val manifestBytes = encode(
            DiagnosticBundleManifestV1.serializer(),
            DiagnosticBundleManifestV1(
                diagnosticRunId = input.diagnosticRunId,
                stallId = input.stallId,
            ),
        )
        writeBoundedFile(
            name = "bundle.json",
            required = true,
            datasetSafe = true,
            bytes = manifestBytes,
        )
        recordFile("summary.json", required = true, EvidenceAvailability.AVAILABLE, datasetSafe = true)

        val summary = try {
            DiagnosticBundleSummaryV1(
                diagnosticRunId = input.diagnosticRunId,
                stallId = input.stallId,
                createdWallClock = input.createdWallClock.toString(),
                trigger = input.trigger,
                classification = input.classification,
                action = input.action,
                processLiveness = input.process.liveness,
                files = files,
            )
        } catch (_: Exception) {
            failures += SupervisorFailureCode.BUNDLE_FILE_FAILED
            null
        }
        if (summary == null) {
            return@synchronized DiagnosticBundleResult(
                EvidenceAvailability.FAILED,
                bundleDirectory,
                failures = failures.distinct(),
            )
        }

        val summaryBytes = encode(DiagnosticBundleSummaryV1.serializer(), summary)
        if (bytesUsed + summaryBytes.size > maxBundleBytes) {
            failures += SupervisorFailureCode.BUNDLE_TOO_LARGE
            return@synchronized DiagnosticBundleResult(
                EvidenceAvailability.FAILED,
                bundleDirectory,
                failures = failures.distinct(),
            )
        }
        try {
            writeAtomicWithoutRecord(bundleDirectory.resolve("summary.json"), summaryBytes)
            bytesUsed += summaryBytes.size
        } catch (_: Exception) {
            failures += SupervisorFailureCode.BUNDLE_FILE_FAILED
            return@synchronized DiagnosticBundleResult(
                EvidenceAvailability.FAILED,
                bundleDirectory,
                failures = failures.distinct(),
            )
        }

        val retention = try {
            retentionEnforcer(stallsDirectory, maxDiagnosticBundles)
        } catch (_: Exception) {
            DiagnosticRetentionResult(
                availability = EvidenceAvailability.FAILED,
                deletedBundleCount = 0,
                failureCode = SupervisorFailureCode.RETENTION_FAILED,
            )
        }
        val retentionFailure = retention.failureCode
            ?: if (retention.availability == EvidenceAvailability.FAILED) {
                SupervisorFailureCode.RETENTION_FAILED
            } else {
                null
            }
        retentionFailure?.let {
            failures += it
            if (it == SupervisorFailureCode.RETENTION_FAILED) {
                latchRetentionFailure(input.diagnosticRunId, stallsDirectory)
            }
        }
        val requiredFileFailure = files.any {
            it.required && it.availability != EvidenceAvailability.AVAILABLE
        }
        return@synchronized DiagnosticBundleResult(
            availability = if (retentionFailure == null && !requiredFileFailure) {
                EvidenceAvailability.AVAILABLE
            } else {
                EvidenceAvailability.FAILED
            },
            bundleDirectory = bundleDirectory,
            summary = summary,
            failures = failures.distinct(),
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    private fun writeAtomicWithoutRecord(destination: Path, bytes: ByteArray) {
        val normalized = destination.toAbsolutePath().normalize()
        Files.createDirectories(normalized.parent)
        val temporary = Files.createTempFile(normalized.parent, ".diagnostic-summary-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, normalized, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun latchRetentionFailure(diagnosticRunId: String, stallsDirectory: Path) {
        retentionFailureRunIds += diagnosticRunId
        try {
            Files.createDirectories(stallsDirectory)
            val marker = retentionMarker(stallsDirectory.parent)
            if (!Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.write(marker, RETENTION_FAILURE_MARKER, CREATE_NEW)
            }
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Another writer or an earlier call already established the run latch.
        } catch (_: Exception) {
            // The in-memory latch still protects this writer instance if the durable marker fails.
        }
    }

    private fun retentionMarker(runDirectory: Path): Path = runDirectory.resolve("stalls/.retention-failed")

    private fun isSafeBundleToken(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))

    private companion object {
        val RETENTION_FAILURE_MARKER = "RETENTION_FAILED\n".toByteArray(Charsets.UTF_8)
    }
}
