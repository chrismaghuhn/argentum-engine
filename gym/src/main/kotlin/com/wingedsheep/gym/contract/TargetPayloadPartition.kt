package com.wingedsheep.gym.contract

import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetInfo

/**
 * The structural partition contract for the fixed V1 `GameAction.targets` payload.
 *
 * V1 sends one flat list whose entries are ordered by semantic target requirement. This helper
 * answers only whether a payload length identifies exactly one vector of per-requirement counts;
 * it never looks at candidate IDs, game state, card definitions, or target legality.
 */
object TargetPayloadPartition {

    enum class UnsupportedReason {
        /** A target-bearing LegalAction did not carry its canonical requirement list. */
        INCOMPLETE_ACTION_DOMAIN,

        /** A requirement did not carry a valid finite resolved cardinality range. */
        INVALID_CARDINALITY,

        /** Cardinality or target legality still depends on an unbound X value. */
        UNRESOLVED_X,

        /** More than one count vector can produce at least one identical payload length. */
        AMBIGUOUS_FLAT_PARTITION,

        /** The payload length is outside the certified fixed domain. */
        PAYLOAD_LENGTH_OUT_OF_RANGE,
    }

    sealed interface Certification {
        /**
         * A fixed domain whose accepted payload lengths can be partitioned without guessing.
         *
         * At most one requirement may have a variable cardinality range. With two variable
         * ranges, incrementing one count and decrementing the other produces two vectors with
         * the same total length, so the flat payload is not self-describing.
         */
        class Supported internal constructor(
            val payloadLengthRange: IntRange,
            private val minimumCounts: List<Int>,
            private val variableRequirementIndex: Int?,
        ) : Certification {
            val acceptsNonEmptyPayload: Boolean
                get() = payloadLengthRange.last > 0

            /** Return the unique requirement-count vector for [payloadLength], if one exists. */
            fun countVectorForPayloadLength(payloadLength: Int): List<Int>? {
                if (payloadLength !in payloadLengthRange) return null
                val variableIndex = variableRequirementIndex ?: return minimumCounts
                val fixedMinimum = minimumCounts
                    .filterIndexed { index, _ -> index != variableIndex }
                    .sum()
                val variableCount = payloadLength - fixedMinimum
                return minimumCounts.toMutableList().also { counts ->
                    counts[variableIndex] = variableCount
                }
            }

            /** Partition the existing flat target payload by its list length. */
            fun partition(payloadLength: Int): PayloadPartition =
                countVectorForPayloadLength(payloadLength)?.let(PayloadPartition::Accepted)
                    ?: PayloadPartition.Rejected(UnsupportedReason.PAYLOAD_LENGTH_OUT_OF_RANGE)
        }

        data class Unsupported(val reason: UnsupportedReason) : Certification
    }

    sealed interface PayloadPartition {
        data class Accepted(val counts: List<Int>) : PayloadPartition
        data class Rejected(val reason: UnsupportedReason) : PayloadPartition
    }

    /** Certify the ordered canonical action requirements without inspecting their candidates. */
    fun certify(requirements: List<TargetInfo>): Certification {
        if (requirements.isEmpty()) {
            return Certification.Supported(
                payloadLengthRange = 0..0,
                minimumCounts = emptyList(),
                variableRequirementIndex = null,
            )
        }

        if (requirements.any { it.hasUnresolvedXConstraint() }) {
            return Certification.Unsupported(UnsupportedReason.UNRESOLVED_X)
        }

        if (requirements.any { it.minTargets < 0 || it.maxTargets < it.minTargets }) {
            return Certification.Unsupported(UnsupportedReason.INVALID_CARDINALITY)
        }

        val variableIndices = requirements.indices.filter { index ->
            requirements[index].minTargets != requirements[index].maxTargets
        }
        if (variableIndices.size > 1) {
            return Certification.Unsupported(UnsupportedReason.AMBIGUOUS_FLAT_PARTITION)
        }

        val minimumTotal = requirements.sumOf { it.minTargets.toLong() }
        val maximumTotal = requirements.sumOf { it.maxTargets.toLong() }
        if (minimumTotal > Int.MAX_VALUE || maximumTotal > Int.MAX_VALUE) {
            return Certification.Unsupported(UnsupportedReason.INVALID_CARDINALITY)
        }

        return Certification.Supported(
            payloadLengthRange = minimumTotal.toInt()..maximumTotal.toInt(),
            minimumCounts = requirements.map(TargetInfo::minTargets),
            variableRequirementIndex = variableIndices.singleOrNull(),
        )
    }

    /**
     * Certify the canonical target requirement list carried by a registered engine action.
     *
     * A target-bearing action with no requirement entries is incomplete; this intentionally does
     * not fall back to `validTargets`, `minTargets`, or `targetCount`.
     */
    fun certify(action: LegalAction): Certification = when {
        action.requiresTargets && action.targetRequirements.isEmpty() ->
            Certification.Unsupported(UnsupportedReason.INCOMPLETE_ACTION_DOMAIN)
        else -> certify(action.targetRequirements)
    }

    /** Partition a payload by size, returning a stable rejection for unsupported shapes. */
    fun partition(requirements: List<TargetInfo>, payloadLength: Int): PayloadPartition =
        when (val certification = certify(requirements)) {
            is Certification.Supported -> certification.partition(payloadLength)
            is Certification.Unsupported -> PayloadPartition.Rejected(certification.reason)
        }

    private fun TargetInfo.hasUnresolvedXConstraint(): Boolean =
        xConstrainsManaValue ||
            xConstrainsManaValueExactly ||
            xConstrainsPower ||
            xConstrainsCount
}
