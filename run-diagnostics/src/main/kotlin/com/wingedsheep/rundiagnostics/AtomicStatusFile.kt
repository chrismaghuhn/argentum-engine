package com.wingedsheep.rundiagnostics

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * File-system seam used by the D1 atomic status primitive. Implementations must not replace the
 * target through a non-atomic fallback. The interface is public so D3 can inject deterministic
 * provider failures without touching a workload.
 */
public interface AtomicStatusFileOps {
    public fun createTempFile(directory: Path, prefix: String, suffix: String): Path

    public fun writeAndForce(path: Path, bytes: ByteArray)

    public fun atomicReplace(source: Path, target: Path)

    public fun deleteIfExists(path: Path)

    public companion object {
        public fun system(): AtomicStatusFileOps = JdkAtomicStatusFileOps
    }
}

/**
 * Publishes an already-serialized status by writing a same-directory temporary file, forcing and
 * closing it, then requesting an atomic replacement. If the provider cannot perform the requested
 * atomic operation, publication fails and there is deliberately no overwrite fallback.
 */
public class AtomicStatusFile(
    target: Path,
    private val maxBytes: Int = DiagnosticsSchema.DEFAULT_MAX_SERIALIZED_STATUS_BYTES,
    private val ops: AtomicStatusFileOps = AtomicStatusFileOps.system(),
) {
    private val target = target.toAbsolutePath().normalize()

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(this.target.fileName != null) { "target must name a file" }
    }

    /**
     * Performs one serialized publication. All expected diagnostics failures become stable result
     * codes; they never escape into the workload as an exception.
     */
    @Synchronized
    public fun publish(bytes: ByteArray): StatusPublicationResult {
        if (bytes.size > maxBytes) {
            return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_SERIALIZATION_TOO_LARGE)
        }

        val directory = target.parent
            ?: return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_DIRECTORY_UNAVAILABLE)
        if (!Files.isDirectory(directory)) {
            return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_DIRECTORY_UNAVAILABLE)
        }

        var temporary: Path? = null
        var moved = false
        val result = try {
            val temp = try {
                ops.createTempFile(directory, ".${target.fileName}.", ".tmp")
            } catch (_: Exception) {
                null
            }

            if (temp == null) {
                StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_TEMP_FILE_CREATE_FAILED)
            } else {
                temporary = temp
                val writeFailed = try {
                    ops.writeAndForce(temp, bytes)
                    false
                } catch (_: Exception) {
                    true
                }

                if (writeFailed) {
                    StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_WRITE_FAILED)
                } else {
                    try {
                        ops.atomicReplace(temp, target)
                        moved = true
                        StatusPublicationResult.Published(bytesWritten = bytes.size)
                    } catch (_: AtomicMoveNotSupportedException) {
                        StatusPublicationResult.Failed(
                            code = StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE,
                            implementation = AtomicReplacementImplementation.PROVIDER_UNSUPPORTED,
                        )
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        StatusPublicationResult.Failed(
                            code = StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE,
                            implementation = AtomicReplacementImplementation.PROVIDER_UNSUPPORTED,
                        )
                    } catch (_: Exception) {
                        StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_FAILED)
                    }
                }
            }
        } catch (_: Exception) {
            StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_WRITE_FAILED)
        }

        if (!moved && temporary != null) {
            try {
                ops.deleteIfExists(temporary)
            } catch (_: Exception) {
                if (result is StatusPublicationResult.Failed) {
                    return result.copy(
                        code = result.code,
                        tempCleanupFailed = true,
                    )
                }
                return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_TEMP_CLEANUP_FAILED)
            }
        }
        return result
    }
}

private object JdkAtomicStatusFileOps : AtomicStatusFileOps {
    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
        Files.createTempFile(directory, prefix, suffix)

    override fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(path, WRITE, TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    override fun atomicReplace(source: Path, target: Path) {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}
