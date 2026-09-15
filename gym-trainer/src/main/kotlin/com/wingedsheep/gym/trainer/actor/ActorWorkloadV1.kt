package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Identity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

const val WORKLOAD_JOB_V1_VERSION: Int = 1
const val WORKLOAD_JOB_V1_SCHEMA_IDENTITY: String = "argentum-ml-workload-job@v1"
const val WORKLOAD_PLAN_V1_VERSION: Int = 1
const val WORKLOAD_PLAN_V1_SCHEMA_IDENTITY: String = "argentum-ml-workload-plan@v1"
const val WORKLOAD_PLAN_IDENTITY_V1_SCHEMA_IDENTITY: String = "argentum-ml-workload-plan-id@v1"
const val WORK_ITEM_V1_VERSION: Int = 1
const val WORK_ITEM_V1_SCHEMA_IDENTITY: String = "argentum-ml-work-item@v1"
const val WORK_ASSIGNMENT_V1_VERSION: Int = 1
const val WORK_ASSIGNMENT_V1_SCHEMA_IDENTITY: String = "argentum-ml-work-assignment@v1"
const val WORK_ASSIGNMENT_IDENTITY_V1_SCHEMA_IDENTITY: String = "argentum-ml-work-assignment-id@v1"
const val SEMANTIC_JOB_IDENTITY_V1_VERSION: Int = 1
const val SEMANTIC_JOB_IDENTITY_V1_SCHEMA_IDENTITY: String = "argentum-ml-semantic-job@v1"
const val EXECUTION_ATTEMPT_IDENTITY_V1_VERSION: Int = 1
const val EXECUTION_ATTEMPT_IDENTITY_V1_SCHEMA_IDENTITY: String =
    "argentum-ml-execution-attempt@v1"

private fun sha256Canonical(element: JsonElement): String =
    A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(element).toByteArray(StandardCharsets.UTF_8),
    )

private fun requireNonBlank(value: String, label: String) {
    require(value.isNotBlank()) { "$label is required" }
}

private fun requireSha256(value: String, label: String) {
    A3SemanticJson.requireSha256(value, label)
}

/** One globally ordered semantic job definition inside a workload plan. */
@Serializable
data class WorkloadJobV1(
    val version: Int = WORKLOAD_JOB_V1_VERSION,
    val schemaIdentity: String = WORKLOAD_JOB_V1_SCHEMA_IDENTITY,
    val jobOrdinal: Int,
    val environmentIdentity: EnvironmentIdentityV1,
    val policyProvenance: PolicyProvenanceV1,
) {
    init {
        require(version == WORKLOAD_JOB_V1_VERSION) {
            "Unsupported workload-job version: $version"
        }
        require(schemaIdentity == WORKLOAD_JOB_V1_SCHEMA_IDENTITY) {
            "Unsupported workload-job schema identity: $schemaIdentity"
        }
        require(jobOrdinal >= 0) { "Workload job ordinal must not be negative" }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("jobOrdinal", jobOrdinal)
        put("environmentIdentity", environmentIdentity.semanticElement())
        put("policyProvenance", policyProvenance.canonicalElement())
    }
}

/**
 * The semantic campaign definition. Its complete ordered job list is the authority from which
 * materialized WorkItems are resolved.
 */
@Serializable
data class WorkloadPlanV1(
    val version: Int = WORKLOAD_PLAN_V1_VERSION,
    val schemaIdentity: String = WORKLOAD_PLAN_V1_SCHEMA_IDENTITY,
    val workloadNamespace: String,
    val rolloutGeneration: String,
    val behaviorPolicyCampaignIdentity: String,
    val opponentPolicyCampaignIdentity: String,
    val jobs: List<WorkloadJobV1>,
) {
    init {
        require(version == WORKLOAD_PLAN_V1_VERSION) {
            "Unsupported workload-plan version: $version"
        }
        require(schemaIdentity == WORKLOAD_PLAN_V1_SCHEMA_IDENTITY) {
            "Unsupported workload-plan schema identity: $schemaIdentity"
        }
        requireNonBlank(workloadNamespace, "Workload namespace")
        requireNonBlank(rolloutGeneration, "Rollout generation")
        requireNonBlank(behaviorPolicyCampaignIdentity, "Behavior policy campaign identity")
        requireNonBlank(opponentPolicyCampaignIdentity, "Opponent policy campaign identity")
        require(jobs.isNotEmpty()) { "Workload plan must contain at least one job" }
        require(jobs.map(WorkloadJobV1::jobOrdinal) == jobs.indices.toList()) {
            "Workload plan jobs must be contiguous and globally ordinalized"
        }
        require(jobs.map { it.environmentIdentity.engineCommit }.distinct().size == 1) {
            "Workload plan jobs must use one source engine commit"
        }
    }

    /** Content identity of the complete semantic campaign definition. */
    val workloadPlanIdentity: String
        get() = recomputeWorkloadPlanIdentity()

    fun recomputeWorkloadPlanIdentity(): String = sha256Canonical(identityElement())

    fun resolve(jobOrdinal: Int): WorkItemV1 {
        val job = jobs.getOrNull(jobOrdinal)
            ?: throw IllegalArgumentException("Workload plan does not contain job ordinal $jobOrdinal")
        return WorkItemV1.from(this, job)
    }

    internal fun identityElement(): JsonObject = buildJsonObject {
        put("schema", WORKLOAD_PLAN_IDENTITY_V1_SCHEMA_IDENTITY)
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("workloadNamespace", workloadNamespace)
        put("rolloutGeneration", rolloutGeneration)
        put("behaviorPolicyCampaignIdentity", behaviorPolicyCampaignIdentity)
        put("opponentPolicyCampaignIdentity", opponentPolicyCampaignIdentity)
        put("jobs", JsonArray(jobs.map(WorkloadJobV1::canonicalElement)))
    }
}

/** Content-addressed semantic identity of one global workload-plan job. */
@Serializable
data class SemanticJobIdentityV1(
    val version: Int = SEMANTIC_JOB_IDENTITY_V1_VERSION,
    val schemaIdentity: String = SEMANTIC_JOB_IDENTITY_V1_SCHEMA_IDENTITY,
    val workloadNamespace: String,
    val rolloutGeneration: String,
    val workloadPlanIdentity: String,
    val jobOrdinal: Int,
    val environmentIdentityDigest: String,
    val value: String,
) {
    init {
        require(version == SEMANTIC_JOB_IDENTITY_V1_VERSION) {
            "Unsupported semantic-job version: $version"
        }
        require(schemaIdentity == SEMANTIC_JOB_IDENTITY_V1_SCHEMA_IDENTITY) {
            "Unsupported semantic-job schema identity: $schemaIdentity"
        }
        requireNonBlank(workloadNamespace, "Semantic-job workload namespace")
        requireNonBlank(rolloutGeneration, "Semantic-job rollout generation")
        requireSha256(workloadPlanIdentity, "Semantic-job workload-plan identity")
        require(jobOrdinal >= 0) { "Semantic-job ordinal must not be negative" }
        requireSha256(environmentIdentityDigest, "Semantic-job environment identity digest")
        requireSha256(value, "Semantic-job identity")
        require(value == recomputeValue()) {
            "Semantic-job identity does not match its canonical preimage"
        }
    }

    fun recomputeValue(): String = sha256Canonical(canonicalElement())

    fun canonicalJson(): String = A3SemanticJson.canonicalJson(canonicalElement())

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("schema", SEMANTIC_JOB_IDENTITY_V1_SCHEMA_IDENTITY)
        put("workloadNamespace", workloadNamespace)
        put("rolloutGeneration", rolloutGeneration)
        put("workloadPlanIdentity", workloadPlanIdentity)
        put("jobOrdinal", jobOrdinal)
        put("environmentIdentityDigest", environmentIdentityDigest)
    }

    companion object {
        fun from(plan: WorkloadPlanV1, jobOrdinal: Int): SemanticJobIdentityV1 {
            val job = plan.jobs.singleOrNull { it.jobOrdinal == jobOrdinal }
                ?: throw IllegalArgumentException(
                    "Workload plan does not contain job ordinal $jobOrdinal",
                )
            return from(plan, job)
        }

        private fun from(plan: WorkloadPlanV1, job: WorkloadJobV1): SemanticJobIdentityV1 =
            from(
                plan = plan,
                jobOrdinal = job.jobOrdinal,
                environmentIdentity = job.environmentIdentity,
            )

        private fun from(
            plan: WorkloadPlanV1,
            jobOrdinal: Int,
            environmentIdentity: EnvironmentIdentityV1,
        ): SemanticJobIdentityV1 {
            val planIdentity = plan.workloadPlanIdentity
            val environmentDigest = environmentIdentity.identityDigest()
            val preimage = buildJsonObject {
                put("schema", SEMANTIC_JOB_IDENTITY_V1_SCHEMA_IDENTITY)
                put("workloadNamespace", plan.workloadNamespace)
                put("rolloutGeneration", plan.rolloutGeneration)
                put("workloadPlanIdentity", planIdentity)
                put("jobOrdinal", jobOrdinal)
                put("environmentIdentityDigest", environmentDigest)
            }
            return SemanticJobIdentityV1(
                workloadNamespace = plan.workloadNamespace,
                rolloutGeneration = plan.rolloutGeneration,
                workloadPlanIdentity = planIdentity,
                jobOrdinal = jobOrdinal,
                environmentIdentityDigest = environmentDigest,
                value = sha256Canonical(preimage),
            )
        }
    }
}

/** Materialized WorkItem claim. The plan remains authoritative and is cross-checked by assignment validation. */
@Serializable
data class WorkItemV1(
    val version: Int = WORK_ITEM_V1_VERSION,
    val schemaIdentity: String = WORK_ITEM_V1_SCHEMA_IDENTITY,
    val jobOrdinal: Int,
    val environmentIdentity: EnvironmentIdentityV1,
    val policyProvenance: PolicyProvenanceV1,
    val semanticJobIdentity: SemanticJobIdentityV1,
    val expectedSemanticEpisodeId: String,
    val expectedCollectionJobId: String,
) {
    init {
        require(version == WORK_ITEM_V1_VERSION) {
            "Unsupported work-item version: $version"
        }
        require(schemaIdentity == WORK_ITEM_V1_SCHEMA_IDENTITY) {
            "Unsupported work-item schema identity: $schemaIdentity"
        }
        require(jobOrdinal >= 0) { "Work-item ordinal must not be negative" }
        requireSha256(expectedSemanticEpisodeId, "Expected semantic episode identity")
        requireSha256(expectedCollectionJobId, "Expected collection job identity")
        require(semanticJobIdentity.jobOrdinal == jobOrdinal) {
            "Work-item and semantic-job ordinals must agree"
        }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("jobOrdinal", jobOrdinal)
        put("environmentIdentity", environmentIdentity.semanticElement())
        put("policyProvenance", policyProvenance.canonicalElement())
        put(
            "semanticJobIdentity",
            A3SemanticJson.strictJson.encodeToJsonElement(
                SemanticJobIdentityV1.serializer(),
                semanticJobIdentity,
            ),
        )
        put("expectedSemanticEpisodeId", expectedSemanticEpisodeId)
        put("expectedCollectionJobId", expectedCollectionJobId)
    }

    companion object {
        internal fun from(plan: WorkloadPlanV1, job: WorkloadJobV1): WorkItemV1 {
            val semanticEpisodeId = TrajectoryV1Identity.semanticEpisodeId(job.environmentIdentity)
            return WorkItemV1(
                jobOrdinal = job.jobOrdinal,
                environmentIdentity = job.environmentIdentity,
                policyProvenance = job.policyProvenance,
                semanticJobIdentity = SemanticJobIdentityV1.from(plan, job.jobOrdinal),
                expectedSemanticEpisodeId = semanticEpisodeId,
                expectedCollectionJobId = TrajectoryV1Identity.collectionJobId(
                    semanticEpisodeId,
                    job.policyProvenance,
                ),
            )
        }
    }
}

/** Immutable assignment envelope containing only a subset of the globally ordinalized plan. */
@Serializable
data class WorkAssignmentV1(
    val version: Int = WORK_ASSIGNMENT_V1_VERSION,
    val schemaIdentity: String = WORK_ASSIGNMENT_V1_SCHEMA_IDENTITY,
    val workloadPlan: WorkloadPlanV1,
    val items: List<WorkItemV1>,
) {
    init {
        require(version == WORK_ASSIGNMENT_V1_VERSION) {
            "Unsupported work-assignment version: $version"
        }
        require(schemaIdentity == WORK_ASSIGNMENT_V1_SCHEMA_IDENTITY) {
            "Unsupported work-assignment schema identity: $schemaIdentity"
        }
        require(items.isNotEmpty()) { "Work assignment must contain at least one item" }
        require(items.map(WorkItemV1::jobOrdinal) == items.map(WorkItemV1::jobOrdinal).sorted()) {
            "Work-assignment items must be sorted by global job ordinal"
        }
        require(items.map(WorkItemV1::jobOrdinal).distinct().size == items.size) {
            "Work-assignment items must not duplicate global job ordinals"
        }
        items.forEach { item ->
            require(item == workloadPlan.resolve(item.jobOrdinal)) {
                "Work-item is not the plan-resolved claim for ordinal \${item.jobOrdinal}"
            }
        }
    }

    val workloadPlanIdentity: String
        get() = workloadPlan.workloadPlanIdentity

    /** Content identity of exactly this item subset; execution attempts are not included. */
    val assignmentIdentity: String
        get() = recomputeAssignmentIdentity()

    fun recomputeAssignmentIdentity(): String = sha256Canonical(identityElement())

    internal fun identityElement(): JsonObject = buildJsonObject {
        put("schema", WORK_ASSIGNMENT_IDENTITY_V1_SCHEMA_IDENTITY)
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("workloadPlan", workloadPlan.identityElement())
        put("items", JsonArray(items.map(WorkItemV1::canonicalElement)))
    }

    companion object {
        fun from(plan: WorkloadPlanV1, jobOrdinals: List<Int>): WorkAssignmentV1 {
            require(jobOrdinals == jobOrdinals.sorted()) {
                "Assignment job ordinals must be supplied in global order"
            }
            require(jobOrdinals.distinct().size == jobOrdinals.size) {
                "Assignment job ordinals must be unique"
            }
            return WorkAssignmentV1(
                workloadPlan = plan,
                items = jobOrdinals.map(plan::resolve),
            )
        }
    }
}

/** Operational identity of one physical execution attempt; it is never semantic job identity. */
@Serializable
data class ExecutionAttemptIdentityV1(
    val version: Int = EXECUTION_ATTEMPT_IDENTITY_V1_VERSION,
    val schemaIdentity: String = EXECUTION_ATTEMPT_IDENTITY_V1_SCHEMA_IDENTITY,
    val value: String,
) {
    constructor(value: String) : this(
        version = EXECUTION_ATTEMPT_IDENTITY_V1_VERSION,
        schemaIdentity = EXECUTION_ATTEMPT_IDENTITY_V1_SCHEMA_IDENTITY,
        value = value,
    )

    init {
        require(version == EXECUTION_ATTEMPT_IDENTITY_V1_VERSION) {
            "Unsupported execution-attempt version: $version"
        }
        require(schemaIdentity == EXECUTION_ATTEMPT_IDENTITY_V1_SCHEMA_IDENTITY) {
            "Unsupported execution-attempt schema identity: $schemaIdentity"
        }
        requireNonBlank(value, "Execution-attempt identity")
        require(value.length <= 256) { "Execution-attempt identity is too long" }
    }
}

sealed interface ActorContractValidationResult {
    data class Valid(val assignment: WorkAssignmentV1) : ActorContractValidationResult
    data class Rejected(
        val reason: ActorContractValidationReason,
        val detail: String? = null,
    ) : ActorContractValidationResult
}

enum class ActorContractValidationReason {
    UNKNOWN_VERSION,
    SCHEMA_MISMATCH,
    WORKLOAD_PLAN_MISMATCH,
    WORK_ITEM_MISMATCH,
    ASSIGNMENT_IDENTITY_MISMATCH,
}

/** Strict codec for the provider-neutral workload and assignment control plane. */
object ActorWorkloadV1Json {
    fun encode(plan: WorkloadPlanV1): String =
        encodeCanonical(WorkloadPlanV1.serializer(), plan)

    fun encode(assignment: WorkAssignmentV1): String =
        encodeCanonical(WorkAssignmentV1.serializer(), assignment)

    fun decodePlan(encoded: String): WorkloadPlanV1 =
        A3SemanticJson.strictJson.decodeFromString(WorkloadPlanV1.serializer(), encoded)

    fun decodeAssignment(encoded: String): WorkAssignmentV1 =
        A3SemanticJson.strictJson.decodeFromString(WorkAssignmentV1.serializer(), encoded)

    fun decodeAndValidateAssignment(encoded: String): ActorContractValidationResult = try {
        val assignment = decodeAssignment(encoded)
        require(encoded == encode(assignment)) {
            "Work-assignment JSON is not canonical"
        }
        ActorContractValidationResult.Valid(assignment)
    } catch (failure: Exception) {
        ActorContractValidationResult.Rejected(
            reason = classifyFailure(encoded),
            detail = failure.message?.take(256),
        )
    }

    private fun classifyFailure(encoded: String): ActorContractValidationReason {
        val root = runCatching {
            A3SemanticJson.strictJson.parseToJsonElement(encoded).jsonObject
        }.getOrNull()
        val version = root?.get("version")?.let { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        }
        return if (version != null && version != WORK_ASSIGNMENT_V1_VERSION) {
            ActorContractValidationReason.UNKNOWN_VERSION
        } else {
            ActorContractValidationReason.SCHEMA_MISMATCH
        }
    }

    private fun <T> encodeCanonical(serializer: KSerializer<T>, value: T): String =
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(serializer, value),
        )
}
