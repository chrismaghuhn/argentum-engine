package com.wingedsheep.gym

import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Reader
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Audits the serialized decision surfaces of a finalized A9 dataset against the accepted A8
 * closure matrix. The only input is the strict A7 reader; no live GameState, registry, or Rules
 * object is available on this path.
 */
internal object A9DecisionFamilyClosureAudit {
    private enum class Surface { ACTION, PENDING }

    private enum class A8Status {
        REACHABLE_AND_VERIFIED,
        PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1,
    }

    private data class A8Family(
        val name: String,
        val surface: Surface,
        val status: A8Status,
        val acceptedEvidence: String?,
        val policyBoundary: Boolean = true,
    )

    /**
     * This is the source-derived A8 matrix, kept closed in the test harness so a newly observed
     * serialized family cannot silently become accepted merely because a DTO exists.
     */
    private val acceptedA8Matrix = listOf(
        A8Family("PRIORITY", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("GENERIC", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, null, false),
        A8Family("CHOOSE_TARGETS", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 structured-domain witness"),
        A8Family("SELECT_CARDS", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 exact-pair corpus and card-selection witnesses"),
        A8Family("YES_NO", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 exact-pair corpus"),
        A8Family("CHOOSE_MODE", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Outpost Siege source trace normalizes to CHOOSE_OPTION"),
        A8Family("CHOOSE_COLOR", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 color-domain witness"),
        A8Family("CHOOSE_NUMBER", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Pinned-definition scan has no number-choice producer"),
        A8Family("DISTRIBUTE", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Pinned-definition scan has no distribute producer"),
        A8Family("ORDER_OBJECTS", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 targeted trigger-order witness"),
        A8Family("SPLIT_PILES", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Pinned-definition scan has no pile-split producer"),
        A8Family("CHOOSE_OPTION", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 targeted Outpost Siege option witness"),
        A8Family("CHOOSE_REPLACEMENT", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Pinned-definition scan has no text-changing replacement producer"),
        A8Family("SEARCH_LIBRARY", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Exact searches normalize to SELECT_CARDS"),
        A8Family("REORDER_LIBRARY", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 targeted Read the Bones ordering witness"),
        A8Family("ASSIGN_DAMAGE", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Modern combat emits COMBAT_RESOLUTION"),
        A8Family("COMBAT_RESOLUTION", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 targeted trample/combat-resolution witness"),
        A8Family("SELECT_MANA_SOURCES", Surface.PENDING, A8Status.REACHABLE_AND_VERIFIED, "A8 targeted Mentor/Battlefield Forge payment witness"),
        A8Family("BUDGET_MODAL", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Pinned-definition scan has no budget-modal producer"),
        A8Family("BatchYesNoDecision", Surface.PENDING, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "A8 trigger-shape precondition proof"),
        A8Family("DECISION", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, "A8 folded-decision contract"),
        A8Family("CastSpell", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("ActivateAbility", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("CastSpellMode", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("CastWithFlashback", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, "A8 static locked-definition witness"),
        A8Family("CastWithKicker", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("CycleCard", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("DeclareAttackers", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("DeclareBlockers", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, "A8 targeted blocker-domain witness"),
        A8Family("PassPriority", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("PlayLand", Surface.ACTION, A8Status.REACHABLE_AND_VERIFIED, null),
        A8Family("TypecycleCard", Surface.ACTION, A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1, "Exact locked deck inventory"),
    )

    private val acceptedBySurfaceAndName = acceptedA8Matrix.associateBy { it.surface to it.name }

    data class Result(
        val datasetId: String,
        val episodeCount: Int,
        val decisionCount: Int,
        val candidateActionKinds: Map<String, Int>,
        val chosenActionKinds: Map<String, Int>,
        val pendingDecisionFamilies: Map<String, Int>,
        val requiredPayloadFields: Map<String, Int>,
        val absentReachableFamilies: List<String>,
        val absentReachableCoveredByAcceptedA8Evidence: List<String>,
        val unclassified: List<String>,
        val realBlockers: List<String>,
    ) {
        val pass: Boolean
            get() = unclassified.isEmpty() && realBlockers.isEmpty()

        fun failureSummary(): String = buildString {
            append("A8 serialized closure audit failed")
            if (unclassified.isNotEmpty()) append("; unclassified=$unclassified")
            if (realBlockers.isNotEmpty()) append("; blockers=$realBlockers")
        }

        fun render(): String = buildString {
            appendLine("A8_CLOSURE_AUDIT=${if (pass) "PASS" else "FAIL"}")
            appendLine("A8_CLOSURE_DATASET_ID=$datasetId")
            appendLine("A8_CLOSURE_EPISODE_COUNT=$episodeCount")
            appendLine("A8_CLOSURE_DECISION_COUNT=$decisionCount")
            appendLine("SERIALIZED_CANDIDATE_ACTION_KINDS=${formatCounts(candidateActionKinds)}")
            appendLine("SERIALIZED_CHOSEN_ACTION_KINDS=${formatCounts(chosenActionKinds)}")
            appendLine("SERIALIZED_PENDING_DECISION_FAMILIES=${formatCounts(pendingDecisionFamilies)}")
            appendLine("SERIALIZED_REQUIRED_PAYLOAD_FIELDS=${formatCounts(requiredPayloadFields)}")
            appendLine("A8_ABSENT_REACHABLE_FAMILIES=$absentReachableFamilies")
            appendLine(
                "A8_ABSENT_REACHABLE_COVERED_BY_ACCEPTED_A8_EVIDENCE=" +
                    absentReachableCoveredByAcceptedA8Evidence,
            )
            appendLine("A8_UNCLASSIFIED=$unclassified")
            appendLine("A8_REAL_BLOCKERS=$realBlockers")
        }
    }

    /** One-pass accumulator so the A7 reader does not retain a dataset-sized trajectory cache. */
    internal class Accumulator {
        private var episodeCount = 0
        private var decisionCount = 0
        private val candidateActionKinds = linkedMapOf<String, Int>()
        private val chosenActionKinds = linkedMapOf<String, Int>()
        private val pendingDecisionFamilies = linkedMapOf<String, Int>()
        private val requiredPayloadFields = linkedMapOf<String, Int>()

        fun accept(trajectory: TrajectoryV1) {
            episodeCount++
            decisionCount += trajectory.decisions.size
            trajectory.decisions.forEach { record ->
                record.observationBefore.pendingDecision?.let { pending ->
                    increment(pendingDecisionFamilies, pending.kind.name)
                }
                record.completeLegalDomain.candidates.forEach { candidate ->
                    val kind = candidate["kind"]?.jsonPrimitive?.content
                        ?: error("Serialized complete-domain candidate has no kind")
                    increment(candidateActionKinds, kind)
                }
                record.chosenSemanticAction?.let { chosen ->
                    val kind = chosen.candidate["kind"]?.jsonPrimitive?.content
                        ?: error("Serialized chosen action candidate has no kind")
                    increment(chosenActionKinds, kind)
                    chosen.candidate["requiredPayloadFields"]?.jsonArray?.forEach { field ->
                        increment(requiredPayloadFields, field.jsonPrimitive.content)
                    }
                }
            }
        }

        fun finish(manifest: DatasetManifestV1): Result {
            val observedActions = chosenActionKinds.keys.toMutableSet().apply {
                if (contains("PassPriority")) add("PRIORITY")
            }
            val observedPending = pendingDecisionFamilies.keys
            val unknownActions = chosenActionKinds.keys
                .filter { (Surface.ACTION to it) !in acceptedBySurfaceAndName }
                .sorted()
            val unknownPending = pendingDecisionFamilies.keys
                .filter { (Surface.PENDING to it) !in acceptedBySurfaceAndName }
                .sorted()
            val observedUnreachable = acceptedA8Matrix
                .filter { family ->
                    family.status == A8Status.PROVEN_UNREACHABLE_FOR_ENVIRONMENT_V1 &&
                        family.policyBoundary &&
                        when (family.surface) {
                            Surface.ACTION -> family.name in observedActions
                            Surface.PENDING -> family.name in observedPending
                        }
                }
                .map(A8Family::name)
            val actionableGeneric = pendingDecisionFamilies["GENERIC"] ?: 0
            val absentReachable = acceptedA8Matrix
                .filter { family ->
                    family.status == A8Status.REACHABLE_AND_VERIFIED &&
                        when (family.surface) {
                            Surface.ACTION -> family.name !in observedActions
                            Surface.PENDING -> family.name !in observedPending
                        }
                }
                .map(A8Family::name)
            val absentCovered = acceptedA8Matrix
                .filter { family ->
                    family.name in absentReachable && family.acceptedEvidence != null
                }
                .map { family -> "${family.name}: ${family.acceptedEvidence}" }
            val missingEvidence = acceptedA8Matrix
                .filter { family ->
                    family.name in absentReachable && family.acceptedEvidence == null
                }
                .map(A8Family::name)
            val unclassified = (unknownActions + unknownPending + missingEvidence).distinct().sorted()
            val blockers = buildList {
                if (episodeCount != manifest.counts.episodeCount) {
                    add("A7 episode count ${episodeCount} != manifest ${manifest.counts.episodeCount}")
                }
                if (decisionCount != manifest.counts.decisionCount) {
                    add("A7 decision count ${decisionCount} != manifest ${manifest.counts.decisionCount}")
                }
                if (actionableGeneric > 0) add("actionable GENERIC pending family count=$actionableGeneric")
                observedUnreachable.forEach { add("serialized family classified unreachable by A8: $it") }
            }
            return Result(
                datasetId = manifest.datasetId,
                episodeCount = episodeCount,
                decisionCount = decisionCount,
                candidateActionKinds = candidateActionKinds.toMap(),
                chosenActionKinds = chosenActionKinds.toMap(),
                pendingDecisionFamilies = pendingDecisionFamilies.toMap(),
                requiredPayloadFields = requiredPayloadFields.toMap(),
                absentReachableFamilies = absentReachable,
                absentReachableCoveredByAcceptedA8Evidence = absentCovered,
                unclassified = unclassified,
                realBlockers = blockers,
            )
        }
    }

    fun auditPublishedDataset(datasetRoot: Path): Result {
        require(datasetRoot.toFile().isDirectory) { "Missing finalized A9 dataset: $datasetRoot" }
        val reader = TrajectoryV1Reader.openPublishedDataset(datasetRoot)
        val accumulator = Accumulator()
        reader.streamEpisodes().forEach(accumulator::accept)
        return accumulator.finish(reader.manifest)
    }

    private fun increment(counts: MutableMap<String, Int>, key: String) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    private fun formatCounts(counts: Map<String, Int>): String = counts
        .toSortedMap()
        .entries
        .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }
}
