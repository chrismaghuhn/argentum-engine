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
) : DiagnosticBundleSink {
    private val root = root.toAbsolutePath().normalize()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    init {
        require(maxDiagnosticBundles > 0) { "maxDiagnosticBundles must be positive" }
        require(maxBundleBytes > 0) { "maxBundleBytes must be positive" }
    }

    override fun write(input: DiagnosticBundleInput): DiagnosticBundleResult = synchronized(this) {
        val runDirectory = root.resolve(input.diagnosticRunId).normalize()
        if (!runDirectory.startsWith(root)) {
            return@synchronized DiagnosticBundleResult(
                availability = EvidenceAvailability.FAILED,
                failures = listOf(SupervisorFailureCode.BUNDLE_DIRECTORY_FAILED),
            )
        }
        val stallsDirectory = runDirectory.resolve("stalls").normalize()
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

        val status = input.status
        if (status == null) {
            recordFile("status.json", required = true, EvidenceAvailability.MISSING, datasetSafe = true)
        } else {
            try {
                writeBoundedFile("status.json", required = true, datasetSafe = true, RunStatusCodec.encode(status))
            } catch (_: Exception) {
                recordFile("status.json", required = true, EvidenceAvailability.FAILED, datasetSafe = true, failureCode = SupervisorFailureCode.STATUS_SCHEMA_INVALID)
            }
        }

        writeBoundedFile(
            name = "process-metrics.json",
            required = true,
            datasetSafe = true,
            bytes = encode(ProcessMetricsV1.serializer(), input.metrics),
        )

        if (input.safeArtifactSizes.isEmpty()) {
            recordFile("artifact-sizes.json", required = false, EvidenceAvailability.NOT_CONFIGURED, datasetSafe = true)
        } else {
            writeBoundedFile(
                name = "artifact-sizes.json",
                required = false,
                datasetSafe = true,
                bytes = encode(ListSerializer(SafeArtifactSizeV1.serializer()), input.safeArtifactSizes),
            )
        }

        if (input.recentHistory.isEmpty()) {
            recordFile("recent-stages.json", required = false, EvidenceAvailability.NOT_CONFIGURED, datasetSafe = true)
        } else {
            writeBoundedFile(
                name = "recent-stages.json",
                required = false,
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

        val retention = DiagnosticRetention(stallsDirectory, maxDiagnosticBundles).enforce()
        retention.failureCode?.let { failures += it }
        return@synchronized DiagnosticBundleResult(
            availability = if (retention.failureCode == null) EvidenceAvailability.AVAILABLE else EvidenceAvailability.FAILED,
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
}
