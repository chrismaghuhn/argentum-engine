package com.wingedsheep.rundiagnostics.supervisor

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

public data class DiagnosticRetentionResult(
    public val availability: EvidenceAvailability,
    public val deletedBundleCount: Int,
    public val failureCode: SupervisorFailureCode? = null,
)

/** Deletes only oldest bundle directories below the configured diagnostics/stalls root. */
public class DiagnosticRetention(
    stallsDirectory: Path,
    private val maxDiagnosticBundles: Int,
) {
    private val stallsDirectory = stallsDirectory.toAbsolutePath().normalize()

    init {
        require(maxDiagnosticBundles > 0) { "maxDiagnosticBundles must be positive" }
    }

    public fun enforce(): DiagnosticRetentionResult {
        if (!Files.isDirectory(stallsDirectory, NOFOLLOW_LINKS) || Files.isSymbolicLink(stallsDirectory)) {
            return DiagnosticRetentionResult(EvidenceAvailability.NOT_CONFIGURED, 0)
        }
        val bundles = try {
            Files.list(stallsDirectory).use { stream ->
                stream.filter { Files.isDirectory(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .filter { it.fileName.toString().startsWith("stall-") }
                    .sorted(compareBy(Path::toString))
                    .toList()
            }
        } catch (_: Exception) {
            return DiagnosticRetentionResult(
                EvidenceAvailability.FAILED,
                0,
                SupervisorFailureCode.RETENTION_FAILED,
            )
        }
        var deleted = 0
        for (bundle in bundles.take((bundles.size - maxDiagnosticBundles).coerceAtLeast(0))) {
            if (!bundle.startsWith(stallsDirectory) || Files.isSymbolicLink(bundle)) {
                return DiagnosticRetentionResult(
                    EvidenceAvailability.FAILED,
                    deleted,
                    SupervisorFailureCode.RETENTION_FAILED,
                )
            }
            try {
                if (!deleteTree(bundle)) {
                    return DiagnosticRetentionResult(
                        EvidenceAvailability.FAILED,
                        deleted,
                        SupervisorFailureCode.RETENTION_FAILED,
                    )
                }
                deleted++
            } catch (_: Exception) {
                return DiagnosticRetentionResult(
                    EvidenceAvailability.FAILED,
                    deleted,
                    SupervisorFailureCode.RETENTION_FAILED,
                )
            }
        }
        return DiagnosticRetentionResult(EvidenceAvailability.AVAILABLE, deleted)
    }

    private fun deleteTree(path: Path): Boolean {
        if (Files.isSymbolicLink(path)) return Files.deleteIfExists(path)
        val children = Files.list(path).use { stream -> stream.toList() }
        for (child in children) {
            if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                if (!deleteTree(child)) return false
            } else if (!Files.deleteIfExists(child)) {
                return false
            }
        }
        return Files.deleteIfExists(path)
    }
}
