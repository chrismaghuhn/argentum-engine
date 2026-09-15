package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.contract.A3SemanticJson
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

const val ACTOR_RUNTIME_PROBE_V1_VERSION: Int = 1
const val ACTOR_RUNTIME_PROBE_V1_SCHEMA_IDENTITY: String =
    "argentum-ml-actor-runtime-probe@v1"

/** Bounded, provider-neutral runtime facts for operational smoke evidence. */
@Serializable
data class ActorRuntimeSnapshotV1(
    val version: Int = ACTOR_RUNTIME_PROBE_V1_VERSION,
    val schemaIdentity: String = ACTOR_RUNTIME_PROBE_V1_SCHEMA_IDENTITY,
    val osName: String,
    val osVersion: String,
    val architecture: String,
    val javaVersion: String,
    val gradleWrapperAvailable: Boolean,
    val pythonVersion: String? = null,
    val gitVersion: String? = null,
    val availableProcessors: Int,
    val availableRamBytes: Long? = null,
    val maxJvmMemoryBytes: Long,
    val working: StorageCapacitySnapshotV1,
    val scratch: StorageCapacitySnapshotV1,
) {
    init {
        require(version == ACTOR_RUNTIME_PROBE_V1_VERSION) {
            "Unsupported actor-runtime-probe version: $version"
        }
        require(schemaIdentity == ACTOR_RUNTIME_PROBE_V1_SCHEMA_IDENTITY) {
            "Unsupported actor-runtime-probe schema identity: $schemaIdentity"
        }
        listOf(osName, osVersion, architecture, javaVersion).forEach { value ->
            require(value.isNotBlank()) { "Actor runtime identity field must not be blank" }
        }
        require(availableProcessors > 0) { "Actor runtime processor count must be positive" }
        require(availableRamBytes == null || availableRamBytes >= 0) {
            "Available RAM must not be negative"
        }
        require(maxJvmMemoryBytes >= 0) { "Maximum JVM memory must not be negative" }
    }

    /** Canonical operational rendering; no checkout roots, hostnames, or command output paths. */
    fun renderSafeText(): String = A3SemanticJson.canonicalJson(
        A3SemanticJson.strictJson.encodeToJsonElement(serializer(), this),
    )
}

/** Collects only bounded, explicitly selected runtime facts; it is not semantic identity input. */
class ActorRuntimeProbeV1(
    private val sourceRepositoryRoot: Path,
    private val workingRoot: Path,
    private val scratchRoot: Path,
    private val storageProbe: StorageCapacityProbeV1 = FileStoreStorageCapacityProbeV1(),
) {
    fun probe(): ActorRuntimeSnapshotV1 {
        val runtime = Runtime.getRuntime()
        return ActorRuntimeSnapshotV1(
            osName = safeProperty("os.name", "unknown"),
            osVersion = safeProperty("os.version", "unknown"),
            architecture = safeProperty("os.arch", "unknown"),
            javaVersion = safeProperty("java.version", "unknown"),
            gradleWrapperAvailable = Files.isRegularFile(sourceRepositoryRoot.resolve("gradlew")) ||
                Files.isRegularFile(sourceRepositoryRoot.resolve("gradlew.bat")),
            pythonVersion = commandVersion(listOf("python", "--version"), PYTHON_VERSION),
            gitVersion = commandVersion(listOf("git", "--version"), GIT_VERSION),
            availableProcessors = runtime.availableProcessors(),
            availableRamBytes = availableRamBytes(),
            maxJvmMemoryBytes = runtime.maxMemory().coerceAtLeast(0L),
            working = storageProbe.probe(StorageAreaV1.WORKING, workingRoot),
            scratch = storageProbe.probe(StorageAreaV1.SCRATCH, scratchRoot),
        )
    }

    private fun safeProperty(name: String, fallback: String): String =
        System.getProperty(name)
            ?.take(MAX_FIELD_LENGTH)
            ?.takeIf { it.isNotBlank() && it.all(SAFE_FIELD_CHARACTERS::contains) }
            ?: fallback

    private fun commandVersion(command: List<String>, pattern: Regex): String? {
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return null
        }
        return try {
            val output = process.inputStream.readNBytes(MAX_COMMAND_OUTPUT_BYTES)
                .toString(Charsets.UTF_8)
                .lineSequence()
                .firstOrNull()
                ?.trim()
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                pattern.matchEntire(output ?: "")?.value
            } else {
                null
            }
        } catch (_: Exception) {
            process.destroyForcibly()
            null
        }
    }

    private fun availableRamBytes(): Long? {
        val operatingSystem = ManagementFactory.getOperatingSystemMXBean()
        val comSun = operatingSystem as? com.sun.management.OperatingSystemMXBean
        val nativeAvailable = comSun?.freeMemorySize
        if (nativeAvailable != null && nativeAvailable >= 0L) return nativeAvailable

        val memInfo = Path.of("/proc/meminfo")
        if (!Files.isRegularFile(memInfo)) return null
        return runCatching {
            Files.newBufferedReader(memInfo).useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    MEM_AVAILABLE.matchEntire(line.trim())?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { kib -> Math.multiplyExact(kib, 1024L) }
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 64
        const val MAX_COMMAND_OUTPUT_BYTES = 256
        const val COMMAND_TIMEOUT_SECONDS = 2L
        val SAFE_FIELD_CHARACTERS: Set<Char> =
            (('a'..'z') + ('A'..'Z') + ('0'..'9') + ".-_+() /".toList()).toSet()
        val GIT_VERSION = Regex("git version [0-9A-Za-z._-]+")
        val PYTHON_VERSION = Regex("Python [0-9A-Za-z._-]+")
        val MEM_AVAILABLE = Regex("MemAvailable:\\s+(\\d+)\\s+kB")
    }
}
