package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.sdk.model.EntityId

/**
 * Rules-owned, state-resolved certificate for one DeclareBlockers action.
 *
 * The certificate contains only public entity references and resolved constraints. It deliberately
 * does not contain GameState, CardDefinition, evaluator objects, or payment information. The
 * versioned Gym DTO is a projection of this type; it is not a second source of combat legality.
 *
 * [blockerOrder] and [attackerOrder] are producer-owned canonical orders. They are derived from
 * the current battlefield-object identity stamps (CR 400.7), never from EntityId text or map/set
 * iteration. The order is part of the certificate so serialization and replay do not have to invent
 * a tie-breaker at the public boundary.
 */
data class RulesBlockerDeclarationDomain(
    val blockerOrder: List<EntityId>,
    val attackerOrder: List<EntityId>,
    val blockerToAttackers: Map<EntityId, List<EntityId>>,
    val maxAttackersByBlocker: Map<EntityId, Int>,
    val minBlockersByAttacker: Map<EntityId, Int>,
    val maxBlockersByAttacker: Map<EntityId, Int>,
    val globalMaxBlockers: Int?,
    val coBlockerRequirements: Map<EntityId, List<RulesCoBlockerRequirement>>,
    /**
     * A multiset, not a set. Equal entries are intentionally retained because CR 509.1c counts
     * requirement instances when determining the maximum number that can be obeyed.
     */
    val requirements: List<RulesBlockRequirement>,
    /** Exact Rules-owned maximum number of requirement instances simultaneously satisfiable. */
    val minimumSatisfiedRequirementCount: Int,
    /** Direct result of evaluating the empty declaration against this certificate. */
    val canDeclareZeroBlockers: Boolean,
    /**
     * Rules-only relation used while evaluating CR 509.1c requirements. A relation that would
     * require a blocking cost remains in [blockerToAttackers] as a voluntary public choice, but
     * is omitted here because the defender is not required to pay that cost merely to satisfy
     * more requirements. This certificate detail is deliberately not projected to Gym.
     */
    internal val requirementBlockerToAttackers: Map<EntityId, List<EntityId>> = blockerToAttackers,
)

data class RulesCoBlockerRequirement(
    /** Public blocker candidates that satisfy this restriction when declared alongside the owner. */
    val eligibleCoBlockers: List<EntityId>,
)

/** Resolved CR 509.1c requirement instances. Duplicate instances are semantically meaningful. */
sealed interface RulesBlockRequirement {
    data class BlockSpecific(
        val blockerId: EntityId,
        val attackerId: EntityId,
    ) : RulesBlockRequirement

    data class BlockOneOf(
        val blockerId: EntityId,
        val attackerIds: List<EntityId>,
    ) : RulesBlockRequirement

    data class AttackerMustBeBlockedIfAble(
        val attackerId: EntityId,
    ) : RulesBlockRequirement

    data class AttackerMustBeBlockedByAll(
        val attackerId: EntityId,
    ) : RulesBlockRequirement

    data class BlockerMustBlockIfAble(
        val blockerId: EntityId,
    ) : RulesBlockRequirement
}

enum class BlockerDeclarationDomainUnsupportedReason {
    CERTIFICATE_MISSING,
    INCOMPLETE_DECLARATION_CONSTRAINTS,
    UNSUPPORTED_RULE_OR_MECHANIC,
    CANONICAL_ORDER_UNAVAILABLE,
    EXACT_REQUIREMENT_THRESHOLD_UNAVAILABLE,
}

sealed interface BlockerDeclarationDomainSupport {
    data object SUPPORTED : BlockerDeclarationDomainSupport

    data class UNSUPPORTED(
        val reason: BlockerDeclarationDomainUnsupportedReason,
    ) : BlockerDeclarationDomainSupport
}

sealed interface RulesBlockerDeclarationDomainResult {
    data class Supported(
        val domain: RulesBlockerDeclarationDomain,
    ) : RulesBlockerDeclarationDomainResult

    data class Unsupported(
        val reason: BlockerDeclarationDomainUnsupportedReason,
    ) : RulesBlockerDeclarationDomainResult
}

enum class BlockerDeclarationRejection {
    MALFORMED_CERTIFICATE,
    UNKNOWN_BLOCKER,
    EMPTY_BLOCKER_ASSIGNMENT,
    UNKNOWN_ATTACKER,
    DUPLICATE_ATTACKER_ASSIGNMENT,
    INVALID_ATTACKER_FOR_BLOCKER,
    BLOCKER_MAX_EXCEEDED,
    MIN_BLOCKERS_UNSATISFIED,
    MAX_BLOCKERS_EXCEEDED,
    GLOBAL_BLOCKER_CAP_EXCEEDED,
    CO_BLOCKER_REQUIREMENT_UNSATISFIED,
    ZERO_BLOCKERS_FORBIDDEN,
    REQUIREMENT_THRESHOLD_UNSATISFIED,
}

sealed interface BlockerDeclarationValidationResult {
    data object Accepted : BlockerDeclarationValidationResult

    data class Rejected(
        val reason: BlockerDeclarationRejection,
    ) : BlockerDeclarationValidationResult
}

/**
 * Pure evaluator for a resolved blocker certificate.
 *
 * It intentionally has no GameState parameter. Rules resolves stateful pairwise legality and
 * declaration constraints into [RulesBlockerDeclarationDomain]; this evaluator checks membership,
 * shape, and the producer-owned 509.1c threshold. BlockPhaseManager uses the same evaluator before
 * its stateful commitment path, and Gym calls it through the public submission seam.
 */
object BlockerDeclarationDomainValidator {

    /**
     * Shared certificate-shape gate for projections. It checks the same Rules-owned structural
     * invariants used before submission without recomputing the exact 509.1c threshold.
     */
    fun isCertificateStructurallyValid(domain: RulesBlockerDeclarationDomain): Boolean =
        isCanonicalCertificate(domain)

    fun validate(
        domain: RulesBlockerDeclarationDomain,
        action: DeclareBlockers,
    ): BlockerDeclarationValidationResult {
        if (!isCanonicalCertificate(domain)) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.MALFORMED_CERTIFICATE,
            )
        }

        val submitted = action.blockers
        if (submitted.keys.any { it !in domain.blockerToAttackers }) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.UNKNOWN_BLOCKER,
            )
        }
        if (submitted.any { (_, attackers) -> attackers.isEmpty() }) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.EMPTY_BLOCKER_ASSIGNMENT,
            )
        }
        if (submitted.any { (_, attackers) -> attackers.size != attackers.toSet().size }) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.DUPLICATE_ATTACKER_ASSIGNMENT,
            )
        }
        if (submitted.values.flatten().any { it !in domain.attackerOrder }) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.UNKNOWN_ATTACKER,
            )
        }
        if (submitted.any { (blockerId, attackers) ->
                attackers.any { it !in domain.blockerToAttackers.getValue(blockerId) }
            }
        ) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.INVALID_ATTACKER_FOR_BLOCKER,
            )
        }

        if (submitted.any { (blockerId, attackers) ->
                attackers.size > domain.maxAttackersByBlocker.getValue(blockerId)
            }
        ) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.BLOCKER_MAX_EXCEEDED,
            )
        }

        val attackerBlockerCounts = attackerBlockerCounts(submitted)
        if (domain.minBlockersByAttacker.any { (attackerId, minimum) ->
                attackerBlockerCounts.getOrDefault(attackerId, 0).let { count ->
                    count > 0 && count < minimum
                }
            }
        ) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.MIN_BLOCKERS_UNSATISFIED,
            )
        }
        if (attackerBlockerCounts.any { (attackerId, count) ->
                count > domain.maxBlockersByAttacker.getOrDefault(attackerId, Int.MAX_VALUE)
            }
        ) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.MAX_BLOCKERS_EXCEEDED,
            )
        }
        if (domain.globalMaxBlockers != null && submitted.keys.size > domain.globalMaxBlockers) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.GLOBAL_BLOCKER_CAP_EXCEEDED,
            )
        }

        val selectedBlockers = submitted.keys
        for ((blockerId, requirements) in domain.coBlockerRequirements) {
            if (blockerId !in selectedBlockers) continue
            for (requirement in requirements) {
                if (requirement.eligibleCoBlockers.none { it in selectedBlockers }) {
                    return BlockerDeclarationValidationResult.Rejected(
                        BlockerDeclarationRejection.CO_BLOCKER_REQUIREMENT_UNSATISFIED,
                    )
                }
            }
        }

        if (submitted.isEmpty() && !domain.canDeclareZeroBlockers) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.ZERO_BLOCKERS_FORBIDDEN,
            )
        }

        if (satisfiedRequirementCount(domain, submitted) < domain.minimumSatisfiedRequirementCount) {
            return BlockerDeclarationValidationResult.Rejected(
                BlockerDeclarationRejection.REQUIREMENT_THRESHOLD_UNSATISFIED,
            )
        }

        return BlockerDeclarationValidationResult.Accepted
    }

    /**
     * Return the exact maximum number of requirement instances satisfiable under 509.1a-c.
     * Relations that require a blocking cost are excluded from this maximum because CR 509.1c
     * does not require the defender to pay that cost merely to obey more requirements.
     */
    fun maximumSatisfiedRequirementCount(domain: RulesBlockerDeclarationDomain): Int? {
        if (!isCanonicalCertificate(domain, allowThresholdMismatch = true)) return null

        val blockers = domain.blockerOrder
        // Keep the exact search bounded without materializing the power set of each blocker. A
        // large CanBlockAnyNumber board must fail closed once the exact certificate search is
        // beyond the supported budget; it must not allocate millions of intermediate subsets.
        if (blockers.any {
                domain.requirementBlockerToAttackers.getValue(it).size >
                    MAX_EXACT_CANDIDATES_PER_BLOCKER
            }
        ) {
            return null
        }

        var exploredNodes = 0L
        var best = 0
        val selected = LinkedHashMap<EntityId, List<EntityId>>()

        fun visit(index: Int, selectedBlockerCount: Int) {
            if (exploredNodes++ >= MAX_EXACT_SEARCH_NODES) {
                throw SearchLimitExceeded
            }
            if (index == blockers.size) {
                if (!satisfiesDeclarationConstraints(domain, selected)) return
                best = maxOf(best, satisfiedRequirementCountWithoutBlockingCosts(domain, selected))
                return
            }

            val blockerId = blockers[index]
            val candidates = domain.requirementBlockerToAttackers.getValue(blockerId)
            val cap = minOf(domain.maxAttackersByBlocker.getValue(blockerId), candidates.size)
            val assignment = ArrayList<EntityId>(cap)

            fun enumerateAssignments(candidateIndex: Int) {
                if (exploredNodes++ >= MAX_EXACT_SEARCH_NODES) throw SearchLimitExceeded
                if (candidateIndex == candidates.size) {
                    val nextCount = selectedBlockerCount + if (assignment.isEmpty()) 0 else 1
                    if (domain.globalMaxBlockers == null || nextCount <= domain.globalMaxBlockers) {
                        if (assignment.isEmpty()) {
                            visit(index + 1, nextCount)
                        } else {
                            selected[blockerId] = assignment.toList()
                            visit(index + 1, nextCount)
                            selected.remove(blockerId)
                        }
                    }
                    return
                }

                // Excluding the next attacker first preserves the old producer order while the
                // search remains lazy. Every subset up to the resolved per-blocker cap appears
                // exactly once, including the explicit empty assignment.
                enumerateAssignments(candidateIndex + 1)
                if (assignment.size < cap) {
                    assignment += candidates[candidateIndex]
                    enumerateAssignments(candidateIndex + 1)
                    assignment.removeAt(assignment.lastIndex)
                }
            }

            enumerateAssignments(0)
        }

        return try {
            visit(0, 0)
            best
        } catch (_: SearchLimitExceeded) {
            null
        }
    }

    internal fun satisfiedRequirementCount(
        domain: RulesBlockerDeclarationDomain,
        blockers: Map<EntityId, List<EntityId>>,
    ): Int = satisfiedRequirementCount(domain, blockers, domain.blockerToAttackers)

    /**
     * Count requirements for the exact 509.1c maximum. A blocking-cost relation is not an able
     * relation for this calculation, but remains available to [satisfiedRequirementCount] when a
     * submitted declaration voluntarily chooses and later pays for that block.
     */
    internal fun satisfiedRequirementCountWithoutBlockingCosts(
        domain: RulesBlockerDeclarationDomain,
        blockers: Map<EntityId, List<EntityId>>,
    ): Int = satisfiedRequirementCount(domain, blockers, domain.requirementBlockerToAttackers)

    private fun satisfiedRequirementCount(
        domain: RulesBlockerDeclarationDomain,
        blockers: Map<EntityId, List<EntityId>>,
        abilityRelation: Map<EntityId, List<EntityId>>,
    ): Int = domain.requirements.count { requirement ->
        when (requirement) {
            is RulesBlockRequirement.BlockSpecific ->
                requirement.attackerId in blockers[requirement.blockerId].orEmpty()

            is RulesBlockRequirement.BlockOneOf ->
                blockers[requirement.blockerId].orEmpty().any {
                    it in requirement.attackerIds
                }

            is RulesBlockRequirement.AttackerMustBeBlockedIfAble ->
                domain.blockerOrder
                    .filter { blockerId ->
                        requirement.attackerId in abilityRelation.getValue(blockerId)
                    }
                    .let { ableBlockers ->
                        ableBlockers.isNotEmpty() && ableBlockers.any { blockerId ->
                            requirement.attackerId in blockers[blockerId].orEmpty()
                        }
                    }

            is RulesBlockRequirement.AttackerMustBeBlockedByAll ->
                domain.blockerOrder
                    .filter { requirement.attackerId in abilityRelation.getValue(it) }
                    .let { ableBlockers ->
                        ableBlockers.isNotEmpty() &&
                            ableBlockers.all { requirement.attackerId in blockers[it].orEmpty() }
                    }

            is RulesBlockRequirement.BlockerMustBlockIfAble ->
                abilityRelation.getValue(requirement.blockerId).isNotEmpty() &&
                    !blockers[requirement.blockerId].isNullOrEmpty()
        }
    }

    internal fun satisfiesDeclarationConstraints(
        domain: RulesBlockerDeclarationDomain,
        blockers: Map<EntityId, List<EntityId>>,
    ): Boolean {
        if (blockers.keys.any { it !in domain.blockerToAttackers }) return false
        if (blockers.any { (_, attackers) -> attackers.isEmpty() }) return false
        if (blockers.any { (blockerId, attackers) ->
                attackers.size != attackers.toSet().size ||
                    attackers.any { it !in domain.blockerToAttackers.getValue(blockerId) } ||
                    attackers.size > domain.maxAttackersByBlocker.getValue(blockerId)
            }
        ) return false

        val counts = attackerBlockerCounts(blockers)
        if (domain.minBlockersByAttacker.any { (attackerId, minimum) ->
                counts.getOrDefault(attackerId, 0).let { count ->
                    count > 0 && count < minimum
                }
            }
        ) return false
        if (counts.any { (attackerId, count) ->
                count > domain.maxBlockersByAttacker.getOrDefault(attackerId, Int.MAX_VALUE)
            }
        ) return false
        if (domain.globalMaxBlockers != null && blockers.keys.size > domain.globalMaxBlockers) return false

        for ((blockerId, requirements) in domain.coBlockerRequirements) {
            if (blockerId !in blockers) continue
            if (requirements.any { requirement ->
                    requirement.eligibleCoBlockers.none { it in blockers }
                }
            ) return false
        }
        return true
    }

    private fun isCanonicalCertificate(
        domain: RulesBlockerDeclarationDomain,
        allowThresholdMismatch: Boolean = false,
    ): Boolean {
        val blockers = domain.blockerOrder
        val attackers = domain.attackerOrder
        if (blockers.size != blockers.toSet().size || attackers.size != attackers.toSet().size) return false
        if (domain.blockerToAttackers.keys != blockers.toSet()) return false
        if (domain.requirementBlockerToAttackers.keys != blockers.toSet()) return false
        if (domain.maxAttackersByBlocker.keys != blockers.toSet()) return false
        if (domain.maxAttackersByBlocker.values.any { it < 0 }) return false
        if (domain.minBlockersByAttacker.keys.any { it !in attackers } ||
            domain.minBlockersByAttacker.values.any { it < 0 }
        ) return false
        if (domain.maxBlockersByAttacker.keys.any { it !in attackers } ||
            domain.maxBlockersByAttacker.values.any { it < 0 }
        ) return false
        if (domain.minBlockersByAttacker.any { (attackerId, min) ->
                min > domain.maxBlockersByAttacker.getOrDefault(attackerId, Int.MAX_VALUE)
            }
        ) return false
        if (domain.globalMaxBlockers != null && domain.globalMaxBlockers < 0) return false

        val blockerRanks = blockers.withIndex().associate { it.value to it.index }
        val attackerRanks = attackers.withIndex().associate { it.value to it.index }
        if (domain.blockerToAttackers.any { (blockerId, candidateAttackers) ->
                candidateAttackers.isEmpty() ||
                    candidateAttackers.size != candidateAttackers.toSet().size ||
                    candidateAttackers.any { it !in attackerRanks } ||
                    candidateAttackers.zipWithNext().any { (left, right) ->
                        attackerRanks.getValue(left) >= attackerRanks.getValue(right)
                    } ||
                    blockerRanks[blockerId] == null
            }
        ) return false
        if (domain.requirementBlockerToAttackers.any { (blockerId, candidateAttackers) ->
                candidateAttackers.size != candidateAttackers.toSet().size ||
                    candidateAttackers.any { it !in domain.blockerToAttackers.getValue(blockerId) } ||
                    candidateAttackers.any { it !in attackerRanks } ||
                    candidateAttackers.zipWithNext().any { (left, right) ->
                        attackerRanks.getValue(left) >= attackerRanks.getValue(right)
                    } ||
                    blockerRanks[blockerId] == null
            }
        ) return false

        if (domain.coBlockerRequirements.keys.any { it !in blockerRanks }) return false
        if (domain.coBlockerRequirements.any { (blockerId, requirements) ->
                requirements.any { requirement ->
                    requirement.eligibleCoBlockers.isEmpty() ||
                        requirement.eligibleCoBlockers.size != requirement.eligibleCoBlockers.toSet().size ||
                        requirement.eligibleCoBlockers.any { it !in blockerRanks || it == blockerId } ||
                        requirement.eligibleCoBlockers.zipWithNext().any { (left, right) ->
                            blockerRanks.getValue(left) >= blockerRanks.getValue(right)
                        }
                }
            }
        ) return false

        if (domain.requirements.any { requirement ->
                when (requirement) {
                    is RulesBlockRequirement.BlockSpecific ->
                        requirement.blockerId !in blockerRanks || requirement.attackerId !in attackerRanks ||
                            requirement.attackerId !in domain.blockerToAttackers.getValue(requirement.blockerId)

                    is RulesBlockRequirement.BlockOneOf ->
                        requirement.blockerId !in blockerRanks ||
                            requirement.attackerIds.isEmpty() ||
                            requirement.attackerIds.size != requirement.attackerIds.toSet().size ||
                            requirement.attackerIds.any { it !in attackerRanks } ||
                            requirement.attackerIds.any { it !in domain.blockerToAttackers.getValue(requirement.blockerId) } ||
                            requirement.attackerIds.zipWithNext().any { (left, right) ->
                                attackerRanks.getValue(left) >= attackerRanks.getValue(right)
                            }

                    is RulesBlockRequirement.AttackerMustBeBlockedIfAble ->
                        requirement.attackerId !in attackerRanks

                    is RulesBlockRequirement.AttackerMustBeBlockedByAll ->
                        requirement.attackerId !in attackerRanks

                    is RulesBlockRequirement.BlockerMustBlockIfAble ->
                        requirement.blockerId !in blockerRanks
                }
            }
        ) return false

        if (domain.minimumSatisfiedRequirementCount < 0 ||
            domain.minimumSatisfiedRequirementCount > domain.requirements.size
        ) return false

        if (!allowThresholdMismatch) {
            // The producer sets this field from the exact solver. A pure certificate cannot prove
            // the value without repeating the producer's search, but it can reject the only
            // impossible boundary value: an empty declaration that claims to be legal while the
            // declared result says otherwise. The producer and differential tests own exactness.
            val empty = emptyMap<EntityId, List<EntityId>>()
            if (domain.canDeclareZeroBlockers !=
                (satisfiesDeclarationConstraints(domain, empty) &&
                    satisfiedRequirementCountWithoutBlockingCosts(domain, empty) >=
                        domain.minimumSatisfiedRequirementCount)
            ) return false
        }

        return true
    }

    private fun attackerBlockerCounts(
        blockers: Map<EntityId, List<EntityId>>,
    ): Map<EntityId, Int> {
        val counts = mutableMapOf<EntityId, Int>()
        blockers.values.flatten().forEach { attackerId ->
            counts[attackerId] = counts.getOrDefault(attackerId, 0) + 1
        }
        return counts
    }

    private object SearchLimitExceeded : RuntimeException()

    // The candidate bound prevents recursive subset generation from exhausting the stack, while
    // the node bound makes the exact threshold producer deterministic and fail-closed on
    // pathological combinatorics.
    private const val MAX_EXACT_CANDIDATES_PER_BLOCKER = 128
    private const val MAX_EXACT_SEARCH_NODES = 4_000_000L
}
