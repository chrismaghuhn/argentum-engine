package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Admission
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1StorageCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

const val ACCEPTED_MEMBERSHIP_LEDGER_V1_VERSION: Int = 1
const val ACCEPTED_MEMBERSHIP_LEDGER_V1_SCHEMA_IDENTITY: String =
    "argentum-ml-accepted-membership-ledger@v1"

@Serializable
enum class MembershipStateV1 {
    MISSING,
    ACCEPTED,
    EXCLUDED,
    CONFLICTED,
}

@Serializable
enum class OfflineReplayReverificationStatusV1 {
    VERIFIED,
    NO_INDEPENDENT_PROOF,
    BLOCKED,
}

@Serializable
enum class OfflineReplayFailureCodeV1 {
    NO_PROOF,
    REPLAY_NOT_EXACT,
    REPLAY_INCOMPLETE,
    REPLAY_DIVERGED,
    REPLAY_CONTENT_INVALID,
}

@Serializable
data class AcceptedMembershipContentIdentityV1(
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val episodeContentDigest: String,
    val closureKind: EpisodeClosureV1.Kind,
    val trajectorySchemaIdentity: String,
    val observationSchemaIdentity: String,
    val actionDomainSchemaIdentity: String,
    val replaySchemaIdentity: String,
) {
    init {
        listOf(
            semanticEpisodeId,
            collectionJobId,
            trajectoryId,
            episodeContentDigest,
        ).forEach { identity ->
            A3SemanticJson.requireSha256(identity, "Membership content identity")
        }
        require(trajectorySchemaIdentity.isNotBlank()) {
            "Membership trajectory schema identity is required"
        }
        require(observationSchemaIdentity.isNotBlank()) {
            "Membership observation schema identity is required"
        }
        require(actionDomainSchemaIdentity.isNotBlank()) {
            "Membership action-domain schema identity is required"
        }
        require(replaySchemaIdentity.isNotBlank()) {
            "Membership replay schema identity is required"
        }
    }
}

@Serializable
data class MembershipClaimRefV1(
    val assignmentIdentity: String,
    val sourceDatasetId: String,
    val sourceShardContentDigest: String,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val episodeContentDigest: String,
    val closureKind: EpisodeClosureV1.Kind,
    val trajectorySchemaIdentity: String,
    val observationSchemaIdentity: String,
    val actionDomainSchemaIdentity: String,
    val replaySchemaIdentity: String,
) {
    init {
        listOf(
            assignmentIdentity,
            sourceDatasetId,
            sourceShardContentDigest,
            semanticEpisodeId,
            collectionJobId,
            trajectoryId,
            episodeContentDigest,
        ).forEach { identity ->
            A3SemanticJson.requireSha256(identity, "Membership claim identity")
        }
        require(trajectorySchemaIdentity.isNotBlank()) {
            "Membership claim trajectory schema identity is required"
        }
        require(observationSchemaIdentity.isNotBlank()) {
            "Membership claim observation schema identity is required"
        }
        require(actionDomainSchemaIdentity.isNotBlank()) {
            "Membership claim action-domain schema identity is required"
        }
        require(replaySchemaIdentity.isNotBlank()) {
            "Membership claim replay schema identity is required"
        }
    }

    fun contentIdentity(): AcceptedMembershipContentIdentityV1 =
        AcceptedMembershipContentIdentityV1(
            semanticEpisodeId = semanticEpisodeId,
            collectionJobId = collectionJobId,
            trajectoryId = trajectoryId,
            episodeContentDigest = episodeContentDigest,
            closureKind = closureKind,
            trajectorySchemaIdentity = trajectorySchemaIdentity,
            observationSchemaIdentity = observationSchemaIdentity,
            actionDomainSchemaIdentity = actionDomainSchemaIdentity,
            replaySchemaIdentity = replaySchemaIdentity,
        )

    companion object {
        fun from(
            assignmentIdentity: String,
            sourceDatasetId: String,
            sourceShardContentDigest: String,
            trajectory: TrajectoryV1,
        ): MembershipClaimRefV1 = MembershipClaimRefV1(
            assignmentIdentity = assignmentIdentity,
            sourceDatasetId = sourceDatasetId,
            sourceShardContentDigest = sourceShardContentDigest,
            semanticEpisodeId = trajectory.semanticEpisodeId,
            collectionJobId = trajectory.collectionJobId,
            trajectoryId = trajectory.trajectoryId,
            episodeContentDigest = TrajectoryV1StorageCodec.episodeContentDigest(trajectory),
            closureKind = trajectory.closure.kind,
            trajectorySchemaIdentity = trajectory.schemaIdentity,
            observationSchemaIdentity = trajectory.episodeMetadata.environmentIdentity.observationSchemaIdentity,
            actionDomainSchemaIdentity = trajectory.episodeMetadata.environmentIdentity.actionDomainSchemaIdentity,
            replaySchemaIdentity = trajectory.episodeMetadata.environmentIdentity.replaySchemaIdentity,
        )
    }
}

@Serializable
data class MembershipLedgerEntryV1(
    val workloadPlanIdentity: String,
    val jobOrdinal: Int,
    val semanticJobIdentity: String,
    val expectedSemanticEpisodeId: String,
    val expectedCollectionJobId: String,
    val membershipState: MembershipStateV1,
    val acceptedContentIdentity: AcceptedMembershipContentIdentityV1? = null,
    val claims: List<MembershipClaimRefV1> = emptyList(),
) {
    init {
        listOf(
            workloadPlanIdentity,
            semanticJobIdentity,
            expectedSemanticEpisodeId,
            expectedCollectionJobId,
        ).forEach { identity ->
            A3SemanticJson.requireSha256(identity, "Membership ledger identity")
        }
        require(jobOrdinal >= 0) { "Membership ledger job ordinal must not be negative" }
        require(claims == claims.sortedWith(compareBy(::membershipClaimSortKey))) {
            "Membership ledger claims must use canonical order"
        }
        when (membershipState) {
            MembershipStateV1.MISSING -> {
                require(acceptedContentIdentity == null && claims.isEmpty()) {
                    "Missing membership must not contain content or claims"
                }
            }

            MembershipStateV1.ACCEPTED,
            MembershipStateV1.EXCLUDED,
            -> {
                val content = requireNotNull(acceptedContentIdentity) {
                    "Accepted or excluded membership requires content identity"
                }
                require(claims.isNotEmpty()) {
                    "Accepted or excluded membership requires claims"
                }
                require(claims.all { it.contentIdentity() == content }) {
                    "Membership claims disagree with accepted content identity"
                }
            }

            MembershipStateV1.CONFLICTED -> {
                require(acceptedContentIdentity == null) {
                    "Conflicted membership cannot select accepted content"
                }
                require(claims.size >= 2) {
                    "Conflicted membership requires multiple claims"
                }
                require(claims.map(MembershipClaimRefV1::contentIdentity).distinct().size >= 2) {
                    "Conflicted membership requires differing content claims"
                }
            }
        }
    }
}

@Serializable
data class AcceptedMembershipLedgerV1(
    val version: Int = ACCEPTED_MEMBERSHIP_LEDGER_V1_VERSION,
    val schemaIdentity: String = ACCEPTED_MEMBERSHIP_LEDGER_V1_SCHEMA_IDENTITY,
    val workloadPlanIdentity: String,
    val entries: List<MembershipLedgerEntryV1>,
) {
    val contentDigest: String
        get() = A3SemanticJson.sha256(
            A3SemanticJson.canonicalJson(canonicalElement())
                .toByteArray(StandardCharsets.UTF_8),
        )

    init {
        require(version == ACCEPTED_MEMBERSHIP_LEDGER_V1_VERSION) {
            "Unsupported accepted-membership-ledger version: $version"
        }
        require(schemaIdentity == ACCEPTED_MEMBERSHIP_LEDGER_V1_SCHEMA_IDENTITY) {
            "Unsupported accepted-membership-ledger schema identity: $schemaIdentity"
        }
        A3SemanticJson.requireSha256(workloadPlanIdentity, "Membership ledger workload-plan identity")
        require(entries.isNotEmpty()) { "Membership ledger must contain plan entries" }
        require(entries.map(MembershipLedgerEntryV1::jobOrdinal) ==
            entries.map(MembershipLedgerEntryV1::jobOrdinal).sorted()) {
            "Membership ledger entries must be ordered by global job ordinal"
        }
        require(entries.map(MembershipLedgerEntryV1::jobOrdinal).distinct().size == entries.size) {
            "Membership ledger entries must not duplicate global job ordinals"
        }
        require(entries.map(MembershipLedgerEntryV1::semanticJobIdentity).distinct().size == entries.size) {
            "Membership ledger semantic jobs must be unique"
        }
        require(entries.all { it.workloadPlanIdentity == workloadPlanIdentity }) {
            "Membership ledger entry plan identities must agree"
        }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put("workloadPlanIdentity", workloadPlanIdentity)
        put(
            "entries",
            A3SemanticJson.strictJson.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(MembershipLedgerEntryV1.serializer()),
                entries,
            ),
        )
    }

    companion object {
        fun from(
            plan: WorkloadPlanV1,
            entries: List<MembershipLedgerEntryV1>,
        ): AcceptedMembershipLedgerV1 {
            require(entries.map(MembershipLedgerEntryV1::jobOrdinal) == plan.jobs.indices.toList()) {
                "Membership ledger must contain every global workload job exactly once"
            }
            require(entries.all { it.workloadPlanIdentity == plan.workloadPlanIdentity }) {
                "Membership ledger entries do not belong to the workload plan"
            }
            return AcceptedMembershipLedgerV1(
                workloadPlanIdentity = plan.workloadPlanIdentity,
                entries = entries.map { entry ->
                    entry.copy(claims = entry.claims.sortedWith(compareBy(::membershipClaimSortKey)))
                },
            )
        }
    }
}

object AcceptedMembershipLedgerV1Json {
    fun encode(ledger: AcceptedMembershipLedgerV1): String =
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                AcceptedMembershipLedgerV1.serializer(),
                ledger,
            ),
        )

    fun decode(encoded: String): AcceptedMembershipLedgerV1 {
        val ledger = A3SemanticJson.strictJson.decodeFromString(
            AcceptedMembershipLedgerV1.serializer(),
            encoded,
        )
        require(encoded == encode(ledger)) { "Membership ledger JSON is not canonical" }
        return ledger
    }
}

sealed interface OfflineReplayVerificationResultV1 {
    data class Verified(val replayTrajectoryBinding: ReplayTrajectoryBindingV1) :
        OfflineReplayVerificationResultV1

    data class Unavailable(val code: OfflineReplayFailureCodeV1) : OfflineReplayVerificationResultV1
}

fun interface OfflineReplayVerifierV1 {
    fun verify(trajectory: TrajectoryV1): OfflineReplayVerificationResultV1
}

data class OfflineAdmissionRequestV1(
    val assignment: WorkAssignmentV1,
    val sources: List<ReimportedLocalPublicationEnvelopeV1>,
    val replayVerifier: OfflineReplayVerifierV1?,
    val targetEligibleClosureKinds: Set<EpisodeClosureV1.Kind> = setOf(
        EpisodeClosureV1.Kind.GAME_TERMINAL,
        EpisodeClosureV1.Kind.INTERRUPTED,
    ),
)

data class OfflineAcceptedSourceV1(
    val jobOrdinal: Int,
    val trajectory: TrajectoryV1,
    val replayTrajectoryBinding: ReplayTrajectoryBindingV1,
    val claim: MembershipClaimRefV1,
)

data class OfflineAdmissionResultV1(
    val ledger: AcceptedMembershipLedgerV1,
    val offlineReplayReverification: OfflineReplayReverificationStatusV1,
    val datasetEligible: Boolean,
    val acceptedSources: List<OfflineAcceptedSourceV1>,
)

/** Strict offline join and deterministic source repack composition root. */
object OfflineAdmissionV1 {
    fun admit(request: OfflineAdmissionRequestV1): OfflineAdmissionResultV1 {
        val itemsByJoinKey = request.assignment.items.associateBy {
            SemanticJoinKey(it.expectedSemanticEpisodeId, it.expectedCollectionJobId)
        }
        require(itemsByJoinKey.size == request.assignment.items.size) {
            "Work assignment contains duplicate semantic join keys"
        }

        val candidatesByOrdinal = linkedMapOf<Int, MutableList<OfflineCandidateV1>>()
        var replayStatus = if (request.replayVerifier == null) {
            OfflineReplayReverificationStatusV1.NO_INDEPENDENT_PROOF
        } else {
            OfflineReplayReverificationStatusV1.VERIFIED
        }

        request.sources.forEach { source ->
            val sourceItemsByJoinKey = source.assignment.items.associateBy {
                SemanticJoinKey(it.expectedSemanticEpisodeId, it.expectedCollectionJobId)
            }
            val shardDigestByTrajectory = source.manifest.episodes.associate { episode ->
                episode.trajectoryId to source.manifest.shards[episode.shardOrdinal].contentDigest
            }
            source.streamEpisodes().forEach { trajectory ->
                val item = itemsByJoinKey[SemanticJoinKey(
                    trajectory.semanticEpisodeId,
                    trajectory.collectionJobId,
                )] ?: throw IllegalArgumentException(
                    "Source trajectory is not assigned by the workload plan",
                )
                require(sourceItemsByJoinKey[SemanticJoinKey(
                    trajectory.semanticEpisodeId,
                    trajectory.collectionJobId,
                )] == item) {
                    "Source assignment claim does not match the requested workload item"
                }
                require(trajectory.episodeMetadata.environmentIdentity == item.environmentIdentity) {
                    "Source trajectory environment does not match the assigned work item"
                }
                require(trajectory.episodeMetadata.policyProvenance == item.policyProvenance) {
                    "Source trajectory policy does not match the assigned work item"
                }
                val sourceShardContentDigest = requireNotNull(
                    shardDigestByTrajectory[trajectory.trajectoryId],
                ) {
                    "Source trajectory is absent from its manifest episode index"
                }
                val verifier = request.replayVerifier ?: return@forEach
                val replayResult = runCatching { verifier.verify(trajectory) }.getOrNull()
                when (replayResult) {
                    is OfflineReplayVerificationResultV1.Verified -> {
                        val admission = runCatching {
                            TrajectoryV1Admission.admit(
                                trajectory = trajectory,
                                binding = replayResult.replayTrajectoryBinding,
                                episodeOrdinal = 0,
                            )
                        }.getOrNull()
                        if (admission !is TrajectoryAdmissionResult.Admitted) {
                            replayStatus = OfflineReplayReverificationStatusV1.BLOCKED
                            return@forEach
                        }
                        val claim = MembershipClaimRefV1.from(
                            assignmentIdentity = source.assignment.assignmentIdentity,
                            sourceDatasetId = source.manifest.datasetId,
                            sourceShardContentDigest = sourceShardContentDigest,
                            trajectory = trajectory,
                        )
                        candidatesByOrdinal.getOrPut(item.jobOrdinal) { mutableListOf() } +=
                            OfflineCandidateV1(
                                jobOrdinal = item.jobOrdinal,
                                trajectory = trajectory,
                                replayTrajectoryBinding = replayResult.replayTrajectoryBinding,
                                claim = claim,
                            )
                    }

                    is OfflineReplayVerificationResultV1.Unavailable,
                    null,
                    -> replayStatus = OfflineReplayReverificationStatusV1.BLOCKED
                }
            }
        }

        val planItems = request.assignment.workloadPlan.jobs.map { job ->
            request.assignment.workloadPlan.resolve(job.jobOrdinal)
        }
        val entries = planItems.map { item ->
            val candidates = candidatesByOrdinal[item.jobOrdinal]
                .orEmpty()
                .sortedWith(compareBy { membershipClaimSortKey(it.claim) })
            val contentGroups = candidates.groupBy { it.claim.contentIdentity() }
            val claims = candidates.map(OfflineCandidateV1::claim)
            val (state, acceptedContent) = when {
                candidates.isEmpty() -> MembershipStateV1.MISSING to null
                contentGroups.size > 1 -> MembershipStateV1.CONFLICTED to null
                else -> {
                    val content = contentGroups.keys.single()
                    if (content.closureKind in request.targetEligibleClosureKinds) {
                        MembershipStateV1.ACCEPTED to content
                    } else {
                        MembershipStateV1.EXCLUDED to content
                    }
                }
            }
            MembershipLedgerEntryV1(
                workloadPlanIdentity = request.assignment.workloadPlanIdentity,
                jobOrdinal = item.jobOrdinal,
                semanticJobIdentity = item.semanticJobIdentity.value,
                expectedSemanticEpisodeId = item.expectedSemanticEpisodeId,
                expectedCollectionJobId = item.expectedCollectionJobId,
                membershipState = state,
                acceptedContentIdentity = acceptedContent,
                claims = claims,
            )
        }
        val ledger = AcceptedMembershipLedgerV1.from(request.assignment.workloadPlan, entries)
        val acceptedSources = entries.asSequence()
            .filter { it.membershipState == MembershipStateV1.ACCEPTED }
            .map { entry ->
                val candidate = candidatesByOrdinal.getValue(entry.jobOrdinal)
                    .filter { it.claim.contentIdentity() == entry.acceptedContentIdentity }
                    .minWithOrNull(compareBy { membershipClaimSortKey(it.claim) })
                    ?: error("Accepted membership has no source candidate")
                OfflineAcceptedSourceV1(
                    jobOrdinal = candidate.jobOrdinal,
                    trajectory = candidate.trajectory,
                    replayTrajectoryBinding = candidate.replayTrajectoryBinding,
                    claim = candidate.claim,
                )
            }
            .sortedBy(OfflineAcceptedSourceV1::jobOrdinal)
            .toList()
        val datasetEligible = replayStatus == OfflineReplayReverificationStatusV1.VERIFIED &&
            entries.all {
                it.membershipState == MembershipStateV1.ACCEPTED ||
                    it.membershipState == MembershipStateV1.EXCLUDED
            }
        return OfflineAdmissionResultV1(
            ledger = ledger,
            offlineReplayReverification = replayStatus,
            datasetEligible = datasetEligible,
            acceptedSources = acceptedSources,
        )
    }

    fun repackAcceptedSources(
        admission: OfflineAdmissionResultV1,
        outputDirectory: Path,
        metadata: DatasetMetadataV1,
    ): DatasetManifestV1 {
        require(admission.offlineReplayReverification ==
            OfflineReplayReverificationStatusV1.VERIFIED
        ) {
            "Deterministic B2 repack requires independent replay reverification"
        }
        require(admission.datasetEligible) {
            "Deterministic B2 repack requires an eligible admission result"
        }
        require(admission.acceptedSources.isNotEmpty()) {
            "Deterministic B2 repack requires at least one accepted source"
        }
        val writer = com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer(
            outputDirectory = outputDirectory,
            metadata = metadata,
        )
        return try {
            admission.acceptedSources.sortedBy(OfflineAcceptedSourceV1::jobOrdinal)
                .forEachIndexed { episodeOrdinal, source ->
                    val result = writer.appendEpisode(
                        episodeOrdinal = episodeOrdinal,
                        trajectory = source.trajectory,
                        replayTrajectoryBinding = source.replayTrajectoryBinding,
                    )
                    require(result is TrajectoryAdmissionResult.Admitted) {
                        "Accepted source failed deterministic B2 repack admission"
                    }
                }
            writer.finalizeDataset()
        } finally {
            writer.close()
        }
    }

    private data class SemanticJoinKey(
        val semanticEpisodeId: String,
        val collectionJobId: String,
    )

    private data class OfflineCandidateV1(
        val jobOrdinal: Int,
        val trajectory: TrajectoryV1,
        val replayTrajectoryBinding: ReplayTrajectoryBindingV1,
        val claim: MembershipClaimRefV1,
    )
}

private fun membershipClaimSortKey(claim: MembershipClaimRefV1): String = listOf(
    claim.sourceDatasetId,
    claim.sourceShardContentDigest,
    claim.assignmentIdentity,
    claim.semanticEpisodeId,
    claim.collectionJobId,
    claim.trajectoryId,
    claim.episodeContentDigest,
).joinToString("|")
