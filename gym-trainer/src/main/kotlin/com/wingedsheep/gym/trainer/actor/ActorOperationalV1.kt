package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.Locale
import kotlin.math.min

const val ACTOR_OPERATIONAL_V1_VERSION: Int = 1
const val ACTOR_STATUS_V1_SCHEMA_IDENTITY: String = "argentum-ml-actor-status@v1"
const val RUN_REPORT_V1_SCHEMA_IDENTITY: String = "argentum-ml-run-report@v1"
const val ACTOR_DIAGNOSTIC_V1_SCHEMA_IDENTITY: String = "argentum-ml-actor-diagnostic@v1"
const val STORAGE_PREFLIGHT_V1_SCHEMA_IDENTITY: String = "argentum-ml-storage-preflight@v1"

@Serializable
enum class ActorStateV1 {
    ASSIGNED,
    PREFLIGHT_VERIFIED,
    RUNNING,
    EPISODE_CLOSED,
    REPLAY_VERIFIED,
    EPISODE_ADMITTED_LOCAL,
    SHARD_STAGED_TMP,
    SHARD_VALIDATED_TMP,
    FINALIZED_LOCAL,
    COPYING_TO_WORKING,
    WORKING_COPY_VERIFIED,
    PUBLICATION_READY,
    CAPACITY_EXHAUSTED,
    PARTIAL_LOST,
    FAILED,
}

@Serializable
enum class ActorDiagnosticSeverityV1 {
    INFO,
    WARNING,
    ERROR,
    FATAL,
}

@Serializable
enum class ActorDiagnosticCodeV1 {
    ASSIGNMENT_SCHEMA_MISMATCH,
    WORKLOAD_PLAN_RESOLUTION_MISMATCH,
    EXPECTED_EPISODE_ID_MISMATCH,
    EXPECTED_COLLECTION_ID_MISMATCH,
    POLICY_CAMPAIGN_MISMATCH,
    UNSUPPORTED_PATH,
    PUBLIC_CHOICE_REJECTION,
    REPLAY_NOT_EXACT,
    REPLAY_INCOMPLETE,
    REPLAY_DIVERGED,
    PRIVACY_REJECTION,
    LOCAL_ADMISSION_FAILURE,
    B2_PUBLISHER_FAILURE,
    PROVIDER_PROCESS_LOST,
    ACTOR_EXECUTOR_FAILURE,
    SHARD_VALIDATION_FAILURE,
    SHARD_FINALIZATION_FAILURE,
    WORKING_COPY_DIGEST_MISMATCH,
    WORKING_COPY_INCOMPLETE,
    EPISODE_PUBLICATION_CAPACITY_EXCEEDED,
    INSUFFICIENT_WORKING_BUDGET,
    INSUFFICIENT_SCRATCH_BUDGET,
    PROVIDER_BUDGET_UNAVAILABLE,
    RUNTIME_STORAGE_RESERVE_BREACH,
    IDENTICAL_DUPLICATE,
    CONFLICTING_DUPLICATE,
    SOURCE_REVISION_UNVERIFIED,
}

@Serializable
data class ActorDiagnosticV1(
    val version: Int = ACTOR_OPERATIONAL_V1_VERSION,
    val schemaIdentity: String = ACTOR_DIAGNOSTIC_V1_SCHEMA_IDENTITY,
    val code: ActorDiagnosticCodeV1,
    val severity: ActorDiagnosticSeverityV1,
    val jobOrdinal: Int? = null,
    val semanticJobIdentity: String? = null,
) {
    init {
        require(version == ACTOR_OPERATIONAL_V1_VERSION) {
            "Unsupported actor-diagnostic version: $version"
        }
        require(schemaIdentity == ACTOR_DIAGNOSTIC_V1_SCHEMA_IDENTITY) {
            "Unsupported actor-diagnostic schema identity: $schemaIdentity"
        }
        require(jobOrdinal == null || jobOrdinal >= 0) {
            "Actor diagnostic job ordinal must not be negative"
        }
        semanticJobIdentity?.let {
            A3SemanticJson.requireSha256(it, "Actor diagnostic semantic job identity")
        }
    }
}

@Serializable
data class ActorStatusV1(
    val version: Int = ACTOR_OPERATIONAL_V1_VERSION,
    val schemaIdentity: String = ACTOR_STATUS_V1_SCHEMA_IDENTITY,
    val assignmentIdentity: String,
    val executionAttemptIdentity: ExecutionAttemptIdentityV1,
    val state: ActorStateV1,
    val actorStartTime: String,
    val lastHeartbeatTime: String,
    val lastProgressTime: String,
    val heartbeatSequence: Long,
    val jobsAssigned: Int,
    val jobsStarted: Int,
    val jobsCompleted: Int,
    val jobsFailed: Int,
    val episodesClosed: Int,
    val episodesAdmittedLocal: Int,
    val decisionsCompleted: Long,
    val currentSemanticJobIdentity: String? = null,
    val currentJobOrdinal: Int? = null,
    val currentEpisodeElapsedSeconds: Long? = null,
    val shardsStaged: Int = 0,
    val shardsFinalized: Int = 0,
    val workingCopiesVerified: Int = 0,
    val lastFinalizedShardIdentity: String? = null,
    val lastFinalizedShardDigest: String? = null,
    val diagnostics: List<ActorDiagnosticV1> = emptyList(),
    val fatalErrorCode: ActorDiagnosticCodeV1? = null,
) {
    init {
        require(version == ACTOR_OPERATIONAL_V1_VERSION) {
            "Unsupported actor-status version: $version"
        }
        require(schemaIdentity == ACTOR_STATUS_V1_SCHEMA_IDENTITY) {
            "Unsupported actor-status schema identity: $schemaIdentity"
        }
        A3SemanticJson.requireSha256(assignmentIdentity, "Actor status assignment identity")
        require(actorStartTime.isNotBlank()) { "Actor status start time is required" }
        require(lastHeartbeatTime.isNotBlank()) { "Actor status heartbeat time is required" }
        require(lastProgressTime.isNotBlank()) { "Actor status progress time is required" }
        require(heartbeatSequence >= 0) { "Heartbeat sequence must not be negative" }
        listOf(
            jobsAssigned,
            jobsStarted,
            jobsCompleted,
            jobsFailed,
            episodesClosed,
            episodesAdmittedLocal,
            shardsStaged,
            shardsFinalized,
            workingCopiesVerified,
        ).forEach { require(it >= 0) { "Actor status counters must not be negative" } }
        require(decisionsCompleted >= 0) { "Completed decisions must not be negative" }
        require(jobsStarted <= jobsAssigned) { "Started jobs exceed assigned jobs" }
        require(jobsCompleted + jobsFailed <= jobsStarted) {
            "Completed and failed jobs exceed started jobs"
        }
        currentSemanticJobIdentity?.let {
            A3SemanticJson.requireSha256(it, "Actor status semantic job identity")
        }
        require(currentJobOrdinal == null || currentJobOrdinal >= 0) {
            "Current actor job ordinal must not be negative"
        }
        require(currentEpisodeElapsedSeconds == null || currentEpisodeElapsedSeconds >= 0) {
            "Current episode elapsed time must not be negative"
        }
        lastFinalizedShardIdentity?.let {
            A3SemanticJson.requireSha256(it, "Last finalized shard identity")
        }
        lastFinalizedShardDigest?.let {
            A3SemanticJson.requireSha256(it, "Last finalized shard digest")
        }
        require(diagnostics.size <= 1024) { "Actor status contains too many diagnostics" }
    }
}

@Serializable
data class RunReportV1(
    val version: Int = ACTOR_OPERATIONAL_V1_VERSION,
    val schemaIdentity: String = RUN_REPORT_V1_SCHEMA_IDENTITY,
    val assignmentIdentity: String,
    val executionAttemptIdentity: ExecutionAttemptIdentityV1,
    val finalState: ActorStateV1,
    val expectedSourceCommit: String,
    val actualRuntimeSourceCommit: String?,
    val sourceRevisionVerified: Boolean,
    val actorStartTime: String,
    val actorEndTime: String,
    val plannedJobOrdinals: List<Int>,
    val startedJobOrdinals: List<Int>,
    val completedJobOrdinals: List<Int>,
    val failedJobOrdinals: List<Int>,
    val partialLostJobOrdinals: List<Int>,
    val localShardDigests: List<String>,
    val diagnostics: List<ActorDiagnosticV1> = emptyList(),
    val storagePreflight: StoragePreflightResultV1? = null,
    val requestedConcurrency: Int = 1,
    val actualConcurrency: Int = 1,
) {
    init {
        require(version == ACTOR_OPERATIONAL_V1_VERSION) {
            "Unsupported run-report version: $version"
        }
        require(schemaIdentity == RUN_REPORT_V1_SCHEMA_IDENTITY) {
            "Unsupported run-report schema identity: $schemaIdentity"
        }
        A3SemanticJson.requireSha256(assignmentIdentity, "Run report assignment identity")
        require(expectedSourceCommit.isNotBlank()) {
            "Run report expected source commit is required"
        }
        require(actualRuntimeSourceCommit == null || actualRuntimeSourceCommit.isNotBlank()) {
            "Run report actual runtime source commit must not be blank"
        }
        require(
            sourceRevisionVerified ==
                (actualRuntimeSourceCommit != null && actualRuntimeSourceCommit == expectedSourceCommit),
        ) {
            "Run report source revision verification disagrees with revisions"
        }
        require(actorStartTime.isNotBlank()) { "Run report start time is required" }
        require(actorEndTime.isNotBlank()) { "Run report end time is required" }
        listOf(
            plannedJobOrdinals,
            startedJobOrdinals,
            completedJobOrdinals,
            failedJobOrdinals,
            partialLostJobOrdinals,
        ).forEach { ordinals ->
            require(ordinals.all { it >= 0 }) { "Run report job ordinals must not be negative" }
            require(ordinals == ordinals.sorted()) { "Run report ordinals must be sorted" }
            require(ordinals.distinct().size == ordinals.size) {
                "Run report ordinals must be unique"
            }
        }
        require(startedJobOrdinals.all { it in plannedJobOrdinals }) {
            "Run report started job is outside the assignment"
        }
        require(completedJobOrdinals.all { it in startedJobOrdinals }) {
            "Run report completed job was not started"
        }
        require(failedJobOrdinals.all { it in startedJobOrdinals }) {
            "Run report failed job was not started"
        }
        require(partialLostJobOrdinals.all { it in startedJobOrdinals }) {
            "Run report partial-lost job was not started"
        }
        localShardDigests.forEach {
            A3SemanticJson.requireSha256(it, "Run report shard digest")
        }
        require(diagnostics.size <= 4096) { "Run report contains too many diagnostics" }
        require(requestedConcurrency > 0) { "Requested concurrency must be positive" }
        require(actualConcurrency > 0) { "Actual concurrency must be positive" }
    }
}

@Serializable
enum class StorageAreaV1 {
    WORKING,
    SCRATCH,
}

@Serializable
data class StorageCapacitySnapshotV1(
    val version: Int = ACTOR_OPERATIONAL_V1_VERSION,
    val schemaIdentity: String = STORAGE_PREFLIGHT_V1_SCHEMA_IDENTITY,
    val area: StorageAreaV1,
    val rootLabel: String,
    val freeBytes: Long,
    val totalBytes: Long,
) {
    init {
        require(version == ACTOR_OPERATIONAL_V1_VERSION) {
            "Unsupported storage-capacity version: $version"
        }
        require(schemaIdentity == STORAGE_PREFLIGHT_V1_SCHEMA_IDENTITY) {
            "Unsupported storage-capacity schema identity: $schemaIdentity"
        }
        require(rootLabel.isNotBlank()) { "Storage root label is required" }
        require(freeBytes >= 0 && totalBytes >= 0) {
            "Storage capacity values must not be negative"
        }
        require(freeBytes <= totalBytes) { "Free storage exceeds total storage" }
    }
}

interface StorageCapacityProbeV1 {
    fun probe(area: StorageAreaV1, root: Path): StorageCapacitySnapshotV1
}

class FileStoreStorageCapacityProbeV1 : StorageCapacityProbeV1 {
    override fun probe(area: StorageAreaV1, root: Path): StorageCapacitySnapshotV1 {
        val store = Files.getFileStore(root)
        return StorageCapacitySnapshotV1(
            area = area,
            rootLabel = area.name.lowercase(Locale.ROOT),
            freeBytes = store.usableSpace,
            totalBytes = store.totalSpace,
        )
    }
}

@Serializable
data class StoragePreflightRequestV1(
    val measuredWorkingFreeBytes: Long,
    val measuredScratchFreeBytes: Long,
    val estimatedFinalizedOutputBytes: Long,
    val requiredAdditionalScratchBytes: Long,
    val publicationOverheadBytes: Long,
    val workingSafetyReserveBytes: Long,
    val providerSafetyReserveBytes: Long,
    val providerOutputBudgetRemainingBytes: Long? = null,
    val configuredProviderOutputCapBytes: Long? = null,
    val providerExistingOrPlannedOutputBytes: Long = 0,
) {
    init {
        listOf(
            measuredWorkingFreeBytes,
            measuredScratchFreeBytes,
            estimatedFinalizedOutputBytes,
            requiredAdditionalScratchBytes,
            publicationOverheadBytes,
            workingSafetyReserveBytes,
            providerSafetyReserveBytes,
            providerExistingOrPlannedOutputBytes,
        ).forEach { require(it >= 0) { "Storage preflight values must not be negative" } }
        require(providerOutputBudgetRemainingBytes == null ||
            providerOutputBudgetRemainingBytes >= 0
        ) {
            "Provider output budget must not be negative"
        }
        require(configuredProviderOutputCapBytes == null ||
            configuredProviderOutputCapBytes >= 0
        ) {
            "Configured provider output cap must not be negative"
        }
    }
}

@Serializable
enum class StoragePreflightStatus {
    PASS,
    REJECTED,
}

@Serializable
enum class StoragePreflightFailureCode {
    PROVIDER_BUDGET_UNAVAILABLE,
    INSUFFICIENT_SCRATCH_BUDGET,
    INSUFFICIENT_PUBLISH_BUDGET,
}

@Serializable
data class StoragePreflightResultV1(
    val version: Int = ACTOR_OPERATIONAL_V1_VERSION,
    val schemaIdentity: String = STORAGE_PREFLIGHT_V1_SCHEMA_IDENTITY,
    val status: StoragePreflightStatus,
    val failureCode: StoragePreflightFailureCode? = null,
    val measuredWorkingFreeBytes: Long,
    val measuredScratchFreeBytes: Long,
    val workingFilesystemBudgetBytes: Long,
    val providerBudgetBytes: Long?,
    val publishBudgetBytes: Long?,
    val estimatedFinalizedOutputBytes: Long,
    val requiredAdditionalScratchBytes: Long,
) {
    init {
        require(version == ACTOR_OPERATIONAL_V1_VERSION) {
            "Unsupported storage-preflight result version: $version"
        }
        require(schemaIdentity == STORAGE_PREFLIGHT_V1_SCHEMA_IDENTITY) {
            "Unsupported storage-preflight result schema identity: $schemaIdentity"
        }
        listOf(
            measuredWorkingFreeBytes,
            measuredScratchFreeBytes,
            workingFilesystemBudgetBytes,
            estimatedFinalizedOutputBytes,
            requiredAdditionalScratchBytes,
        ).forEach { require(it >= 0) { "Storage preflight result values must not be negative" } }
        require(providerBudgetBytes == null || providerBudgetBytes >= 0) {
            "Provider budget must not be negative"
        }
        require(publishBudgetBytes == null || publishBudgetBytes >= 0) {
            "Publish budget must not be negative"
        }
        require((status == StoragePreflightStatus.PASS) == (failureCode == null)) {
            "Storage preflight status and failure code disagree"
        }
    }

    companion object {
        fun pass(
            publishBudgetBytes: Long,
            requiredAdditionalScratchBytes: Long,
        ): StoragePreflightResultV1 = StoragePreflightResultV1(
            status = StoragePreflightStatus.PASS,
            measuredWorkingFreeBytes = publishBudgetBytes,
            measuredScratchFreeBytes = requiredAdditionalScratchBytes,
            workingFilesystemBudgetBytes = publishBudgetBytes,
            providerBudgetBytes = publishBudgetBytes,
            publishBudgetBytes = publishBudgetBytes,
            estimatedFinalizedOutputBytes = 0,
            requiredAdditionalScratchBytes = requiredAdditionalScratchBytes,
        )
    }
}

object StoragePreflight {
    fun evaluate(request: StoragePreflightRequestV1): StoragePreflightResultV1 {
        val workingBudget = saturatingSubtract(
            saturatingSubtract(
                request.measuredWorkingFreeBytes,
                request.workingSafetyReserveBytes,
            ),
            request.publicationOverheadBytes,
        )
        val configuredProviderCapExceeded = request.configuredProviderOutputCapBytes?.let {
            request.providerExistingOrPlannedOutputBytes > it
        } == true
        val providerBudgetBeforeReserve = request.providerOutputBudgetRemainingBytes
            ?: request.configuredProviderOutputCapBytes?.let {
                saturatingSubtract(it, request.providerExistingOrPlannedOutputBytes)
            }

        if (providerBudgetBeforeReserve == null) {
            return rejected(
                request = request,
                workingBudget = workingBudget,
                providerBudget = null,
                publishBudget = null,
                failureCode = StoragePreflightFailureCode.PROVIDER_BUDGET_UNAVAILABLE,
            )
        }

        val providerBudget = saturatingSubtract(
            providerBudgetBeforeReserve,
            request.providerSafetyReserveBytes,
        )
        val publishBudget = min(workingBudget, providerBudget)
        if (configuredProviderCapExceeded) {
            return rejected(
                request = request,
                workingBudget = workingBudget,
                providerBudget = providerBudget,
                publishBudget = publishBudget,
                failureCode = StoragePreflightFailureCode.INSUFFICIENT_PUBLISH_BUDGET,
            )
        }
        if (request.measuredScratchFreeBytes < request.requiredAdditionalScratchBytes) {
            return rejected(
                request = request,
                workingBudget = workingBudget,
                providerBudget = providerBudget,
                publishBudget = publishBudget,
                failureCode = StoragePreflightFailureCode.INSUFFICIENT_SCRATCH_BUDGET,
            )
        }
        if (workingBudget < request.estimatedFinalizedOutputBytes ||
            providerBudget < request.estimatedFinalizedOutputBytes ||
            publishBudget < request.estimatedFinalizedOutputBytes
        ) {
            return rejected(
                request = request,
                workingBudget = workingBudget,
                providerBudget = providerBudget,
                publishBudget = publishBudget,
                failureCode = StoragePreflightFailureCode.INSUFFICIENT_PUBLISH_BUDGET,
            )
        }
        return StoragePreflightResultV1(
            status = StoragePreflightStatus.PASS,
            measuredWorkingFreeBytes = request.measuredWorkingFreeBytes,
            measuredScratchFreeBytes = request.measuredScratchFreeBytes,
            workingFilesystemBudgetBytes = workingBudget,
            providerBudgetBytes = providerBudget,
            publishBudgetBytes = publishBudget,
            estimatedFinalizedOutputBytes = request.estimatedFinalizedOutputBytes,
            requiredAdditionalScratchBytes = request.requiredAdditionalScratchBytes,
        )
    }

    private fun saturatingSubtract(value: Long, amount: Long): Long =
        if (amount >= value) 0 else value - amount

    private fun rejected(
        request: StoragePreflightRequestV1,
        workingBudget: Long,
        providerBudget: Long?,
        publishBudget: Long?,
        failureCode: StoragePreflightFailureCode,
    ): StoragePreflightResultV1 = StoragePreflightResultV1(
        status = StoragePreflightStatus.REJECTED,
        failureCode = failureCode,
        measuredWorkingFreeBytes = request.measuredWorkingFreeBytes,
        measuredScratchFreeBytes = request.measuredScratchFreeBytes,
        workingFilesystemBudgetBytes = workingBudget.coerceAtLeast(0),
        providerBudgetBytes = providerBudget?.coerceAtLeast(0),
        publishBudgetBytes = publishBudget?.coerceAtLeast(0),
        estimatedFinalizedOutputBytes = request.estimatedFinalizedOutputBytes,
        requiredAdditionalScratchBytes = request.requiredAdditionalScratchBytes,
    )
}

class MeasuredStoragePreflightV1(
    private val probe: StorageCapacityProbeV1,
    private val workingRoot: Path,
    private val scratchRoot: Path,
    private val configuration: StoragePreflightConfigurationV1,
) {
    fun evaluate(): StoragePreflightResultV1 {
        val working = probe.probe(StorageAreaV1.WORKING, workingRoot)
        val scratch = probe.probe(StorageAreaV1.SCRATCH, scratchRoot)
        return StoragePreflight.evaluate(
            StoragePreflightRequestV1(
                measuredWorkingFreeBytes = working.freeBytes,
                measuredScratchFreeBytes = scratch.freeBytes,
                estimatedFinalizedOutputBytes = configuration.estimatedFinalizedOutputBytes,
                requiredAdditionalScratchBytes = configuration.requiredAdditionalScratchBytes,
                publicationOverheadBytes = configuration.publicationOverheadBytes,
                workingSafetyReserveBytes = configuration.workingSafetyReserveBytes,
                providerSafetyReserveBytes = configuration.providerSafetyReserveBytes,
                providerOutputBudgetRemainingBytes = configuration.providerOutputBudgetRemainingBytes,
                configuredProviderOutputCapBytes = configuration.configuredProviderOutputCapBytes,
                providerExistingOrPlannedOutputBytes = configuration.providerExistingOrPlannedOutputBytes,
            ),
        )
    }
}

@Serializable
data class StoragePreflightConfigurationV1(
    val estimatedFinalizedOutputBytes: Long,
    val requiredAdditionalScratchBytes: Long,
    val publicationOverheadBytes: Long,
    val workingSafetyReserveBytes: Long,
    val providerSafetyReserveBytes: Long,
    val providerOutputBudgetRemainingBytes: Long? = null,
    val configuredProviderOutputCapBytes: Long? = null,
    val providerExistingOrPlannedOutputBytes: Long = 0,
) {
    init {
        listOf(
            estimatedFinalizedOutputBytes,
            requiredAdditionalScratchBytes,
            publicationOverheadBytes,
            workingSafetyReserveBytes,
            providerSafetyReserveBytes,
            providerExistingOrPlannedOutputBytes,
        ).forEach { require(it >= 0) { "Storage configuration values must not be negative" } }
        require(
            providerOutputBudgetRemainingBytes == null ||
                providerOutputBudgetRemainingBytes >= 0,
        ) {
            "Configured provider output budget must not be negative"
        }
        require(
            configuredProviderOutputCapBytes == null ||
                configuredProviderOutputCapBytes >= 0,
        ) {
            "Configured provider output cap must not be negative"
        }
    }
}

sealed interface ActorOperationalValidationResult {
    data class StatusValid(val status: ActorStatusV1) : ActorOperationalValidationResult
    data class RunReportValid(val report: RunReportV1) : ActorOperationalValidationResult
    data class Rejected(val detail: String? = null) : ActorOperationalValidationResult
}

object ActorOperationalV1Json {
    fun encode(status: ActorStatusV1): String =
        A3SemanticJson.strictJson.encodeToString(ActorStatusV1.serializer(), status)

    fun encode(report: RunReportV1): String =
        A3SemanticJson.strictJson.encodeToString(RunReportV1.serializer(), report)

    fun decodeStatus(encoded: String): ActorStatusV1 =
        A3SemanticJson.strictJson.decodeFromString(ActorStatusV1.serializer(), encoded)

    fun decodeRunReport(encoded: String): RunReportV1 =
        A3SemanticJson.strictJson.decodeFromString(RunReportV1.serializer(), encoded)

    fun decodeAndValidateStatus(encoded: String): ActorOperationalValidationResult = try {
        ActorOperationalValidationResult.StatusValid(decodeStatus(encoded))
    } catch (failure: Exception) {
        ActorOperationalValidationResult.Rejected(failure.message?.take(256))
    }

    fun decodeAndValidateRunReport(encoded: String): ActorOperationalValidationResult = try {
        ActorOperationalValidationResult.RunReportValid(decodeRunReport(encoded))
    } catch (failure: Exception) {
        ActorOperationalValidationResult.Rejected(failure.message?.take(256))
    }
}

interface ActorStatusSink {
    fun persist(status: ActorStatusV1)
}

class AtomicActorStatusFileSink(
    private val statusPath: Path,
) : ActorStatusSink {
    override fun persist(status: ActorStatusV1) {
        val parent = requireNotNull(statusPath.parent) {
            "Actor status path must have a parent directory"
        }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, statusPath.fileName.toString(), ".tmp")
        try {
            val bytes = ActorOperationalV1Json.encode(status).toByteArray(Charsets.UTF_8)
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }
            try {
                Files.move(temporary, statusPath, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, statusPath, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

interface ActorClock {
    fun utcNow(): String
    fun monotonicNanos(): Long
}

object SystemActorClock : ActorClock {
    override fun utcNow(): String = Instant.now().toString()
    override fun monotonicNanos(): Long = System.nanoTime()
}

/**
 * In-memory progress owner. Persistence is best-effort operational output and never changes the
 * actor's semantic result.
 */
class ActorStatusTracker(
    private val assignmentIdentity: String,
    private val executionAttemptIdentity: ExecutionAttemptIdentityV1,
    private val clock: ActorClock = SystemActorClock,
    private val sink: ActorStatusSink? = null,
    private val heartbeatIntervalNanos: Long = 30_000_000_000L,
) {
    private val actorStartMonotonicNanos = clock.monotonicNanos()
    private var currentEpisodeStartMonotonicNanos: Long? = null
    private var lastPersistedMonotonicNanos = actorStartMonotonicNanos
    private var state = ActorStateV1.ASSIGNED
    private var actorStartTime = clock.utcNow()
    private var lastHeartbeatTime = actorStartTime
    private var lastProgressTime = actorStartTime
    private var heartbeatSequence = 0L
    private var jobsAssigned = 0
    private var jobsStarted = 0
    private var jobsCompleted = 0
    private var jobsFailed = 0
    private var episodesClosed = 0
    private var episodesAdmittedLocal = 0
    private var decisionsCompleted = 0L
    private var currentSemanticJobIdentity: String? = null
    private var currentJobOrdinal: Int? = null
    private var shardsStaged = 0
    private var shardsFinalized = 0
    private var workingCopiesVerified = 0
    private var lastFinalizedShardIdentity: String? = null
    private var lastFinalizedShardDigest: String? = null
    private val diagnostics = mutableListOf<ActorDiagnosticV1>()
    private var fatalErrorCode: ActorDiagnosticCodeV1? = null

    init {
        A3SemanticJson.requireSha256(assignmentIdentity, "Actor tracker assignment identity")
        require(heartbeatIntervalNanos >= 0) {
            "Heartbeat interval must not be negative"
        }
    }

    fun assign(jobCount: Int) {
        require(jobCount > 0) { "Actor assignment must contain jobs" }
        jobsAssigned = jobCount
        state = ActorStateV1.ASSIGNED
    }

    fun markProgress(item: WorkItemV1) {
        currentSemanticJobIdentity = item.semanticJobIdentity.value
        currentJobOrdinal = item.jobOrdinal
        lastProgressTime = clock.utcNow()
    }

    fun jobStarted(item: WorkItemV1) {
        jobsStarted += 1
        currentEpisodeStartMonotonicNanos = clock.monotonicNanos()
        markProgress(item)
        state = ActorStateV1.RUNNING
    }

    fun episodeClosed(decisionCount: Int) {
        require(decisionCount >= 0) { "Episode decision count must not be negative" }
        episodesClosed += 1
        decisionsCompleted += decisionCount
        milestone(ActorStateV1.EPISODE_CLOSED)
    }

    fun replayVerified() {
        milestone(ActorStateV1.REPLAY_VERIFIED)
    }

    fun episodeAdmittedLocal() {
        episodesAdmittedLocal += 1
        jobsCompleted += 1
        milestone(ActorStateV1.EPISODE_ADMITTED_LOCAL)
    }

    fun shardStaged() {
        shardsStaged += 1
        milestone(ActorStateV1.SHARD_STAGED_TMP)
    }

    fun shardValidated() {
        milestone(ActorStateV1.SHARD_VALIDATED_TMP)
    }

    fun finalizedLocal(manifest: DatasetManifestV1) {
        shardsFinalized = manifest.shards.size
        lastFinalizedShardIdentity = manifest.datasetId
        lastFinalizedShardDigest = manifest.manifestContentDigest
        milestone(ActorStateV1.FINALIZED_LOCAL)
    }

    fun workingCopyVerified() {
        workingCopiesVerified += 1
        milestone(ActorStateV1.WORKING_COPY_VERIFIED)
    }

    fun fail(
        terminalState: ActorStateV1,
        diagnostic: ActorDiagnosticV1,
    ) {
        require(
            terminalState == ActorStateV1.FAILED ||
                terminalState == ActorStateV1.PARTIAL_LOST ||
                terminalState == ActorStateV1.CAPACITY_EXHAUSTED,
        ) {
            "Actor failure must use a terminal failure state"
        }
        if (diagnostic.jobOrdinal != null) {
            jobsFailed += 1
        }
        diagnostics += diagnostic
        fatalErrorCode = diagnostic.code
        state = terminalState
        persistHeartbeat(force = true)
    }

    fun heartbeat() {
        persistHeartbeat(force = false)
    }

    fun milestone(nextState: ActorStateV1) {
        state = nextState
        persistHeartbeat(force = true)
    }

    fun snapshot(): ActorStatusV1 {
        val elapsed = currentEpisodeStartMonotonicNanos?.let { start ->
            ((clock.monotonicNanos() - start).coerceAtLeast(0L)) / 1_000_000_000L
        }
        return ActorStatusV1(
            assignmentIdentity = assignmentIdentity,
            executionAttemptIdentity = executionAttemptIdentity,
            state = state,
            actorStartTime = actorStartTime,
            lastHeartbeatTime = lastHeartbeatTime,
            lastProgressTime = lastProgressTime,
            heartbeatSequence = heartbeatSequence,
            jobsAssigned = jobsAssigned,
            jobsStarted = jobsStarted,
            jobsCompleted = jobsCompleted,
            jobsFailed = jobsFailed,
            episodesClosed = episodesClosed,
            episodesAdmittedLocal = episodesAdmittedLocal,
            decisionsCompleted = decisionsCompleted,
            currentSemanticJobIdentity = currentSemanticJobIdentity,
            currentJobOrdinal = currentJobOrdinal,
            currentEpisodeElapsedSeconds = elapsed,
            shardsStaged = shardsStaged,
            shardsFinalized = shardsFinalized,
            workingCopiesVerified = workingCopiesVerified,
            lastFinalizedShardIdentity = lastFinalizedShardIdentity,
            lastFinalizedShardDigest = lastFinalizedShardDigest,
            diagnostics = diagnostics.toList(),
            fatalErrorCode = fatalErrorCode,
        )
    }

    private fun persistHeartbeat(force: Boolean) {
        val now = clock.monotonicNanos()
        if (!force && now - lastPersistedMonotonicNanos < heartbeatIntervalNanos) return
        lastHeartbeatTime = clock.utcNow()
        heartbeatSequence += 1
        lastPersistedMonotonicNanos = now
        try {
            sink?.persist(snapshot())
        } catch (_: Exception) {
            // Status persistence is operational evidence and must not change semantic results.
        }
    }
}
