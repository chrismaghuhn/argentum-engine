package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.sdk.model.EntityId

/**
 * The Rules-owned, state-resolved declaration contract registered with one legal action.
 *
 * This is deliberately an internal, unversioned certificate. The Gym layer projects it into
 * the versioned wire DTO; this type remains a Rules concern and contains no presentation data.
 */
data class RulesAttackDeclarationDomain(
    val attackerOrder: List<EntityId>,
    val attackerToDefenders: Map<EntityId, List<EntityId>>,
    val mandatoryAttackers: List<EntityId>,
    val canDeclareZeroAttackers: Boolean,
    val maxAttackers: Int?,
    val coAttackerRequirements: Map<EntityId, List<RulesCoAttackerRequirement>>,
    val bandConstraints: RulesAttackBandConstraints,
)

data class RulesCoAttackerRequirement(val anyOf: List<EntityId>)

data class RulesAttackBandConstraints(
    val bandingAttackersByDefender: Map<EntityId, List<EntityId>>,
    val nonBandingAttackersByDefender: Map<EntityId, List<EntityId>>,
)

enum class AttackDeclarationDomainUnsupportedReason {
    CERTIFICATE_MISSING,
    INCOMPLETE_DECLARATION_CONSTRAINTS,
    UNRESOLVED_CO_ATTACKER_REQUIREMENTS,
    INCOMPLETE_BAND_CONSTRAINTS,
    CANONICAL_ORDER_UNAVAILABLE,
}

sealed interface AttackDeclarationDomainSupport {
    data object SUPPORTED : AttackDeclarationDomainSupport

    data class UNSUPPORTED(
        val reason: AttackDeclarationDomainUnsupportedReason,
    ) : AttackDeclarationDomainSupport
}

sealed interface RulesAttackDeclarationDomainResult {
    data class Supported(
        val domain: RulesAttackDeclarationDomain,
    ) : RulesAttackDeclarationDomainResult

    data class Unsupported(
        val reason: AttackDeclarationDomainUnsupportedReason,
    ) : RulesAttackDeclarationDomainResult
}

enum class AttackDeclarationRejection {
    MALFORMED_CERTIFICATE,
    UNKNOWN_ATTACKER,
    INVALID_DEFENDER,
    ZERO_ATTACKERS_FORBIDDEN,
    MANDATORY_ATTACKER_MISSING,
    ATTACKER_CAP_EXCEEDED,
    CO_ATTACKER_REQUIREMENT_UNSATISFIED,
    MALFORMED_BAND,
    DUPLICATE_BAND_MEMBER,
}

sealed interface AttackDeclarationValidationResult {
    data object Accepted : AttackDeclarationValidationResult

    data class Rejected(
        val reason: AttackDeclarationRejection,
    ) : AttackDeclarationValidationResult
}

/**
 * Validates a declaration against the exact certificate snapshot registered on its action.
 *
 * The validator intentionally accepts no GameState. It is a pure membership/constraint check;
 * Rules performs the stateful execution validation separately after this trusted boundary.
 */
object AttackDeclarationDomainValidator {
    /** Structural certificate check shared by pure projections before addressability validation. */
    fun isStructurallyValid(domain: RulesAttackDeclarationDomain): Boolean =
        isCanonicalCertificate(domain)

    fun validate(
        domain: RulesAttackDeclarationDomain,
        action: DeclareAttackers,
    ): AttackDeclarationValidationResult {
        if (!isCanonicalCertificate(domain)) {
            return AttackDeclarationValidationResult.Rejected(
                AttackDeclarationRejection.MALFORMED_CERTIFICATE,
            )
        }

        val legalDefendersByAttacker = domain.attackerToDefenders
        val submittedAttackers = action.attackers.keys

        if (submittedAttackers.any { it !in legalDefendersByAttacker }) {
            return AttackDeclarationValidationResult.Rejected(
                AttackDeclarationRejection.UNKNOWN_ATTACKER,
            )
        }

        if (action.attackers.any { (attacker, defender) ->
                defender !in legalDefendersByAttacker.getValue(attacker)
            }
        ) {
            return AttackDeclarationValidationResult.Rejected(
                AttackDeclarationRejection.INVALID_DEFENDER,
            )
        }

        if (submittedAttackers.isEmpty() && !domain.canDeclareZeroAttackers) {
            return AttackDeclarationValidationResult.Rejected(
                AttackDeclarationRejection.ZERO_ATTACKERS_FORBIDDEN,
            )
        }

        if (domain.mandatoryAttackers.any { it !in submittedAttackers }) {
            return AttackDeclarationValidationResult.Rejected(
                AttackDeclarationRejection.MANDATORY_ATTACKER_MISSING,
            )
        }

        if (domain.maxAttackers != null && submittedAttackers.size > domain.maxAttackers) {
            return AttackDeclarationValidationResult.Rejected(
                AttackDeclarationRejection.ATTACKER_CAP_EXCEEDED,
            )
        }

        for ((attacker, requirements) in domain.coAttackerRequirements) {
            if (attacker !in submittedAttackers) continue
            if (requirements.any { requirement -> requirement.anyOf.none { it in submittedAttackers } }) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.CO_ATTACKER_REQUIREMENT_UNSATISFIED,
                )
            }
        }

        val bandMemberOwners = mutableSetOf<EntityId>()
        for (band in action.bands) {
            if (band.size < 2) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.MALFORMED_BAND,
                )
            }

            if (band.any { it !in action.attackers }) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.MALFORMED_BAND,
                )
            }

            val defenders = band.map { action.attackers.getValue(it) }.distinct()
            if (defenders.size != 1) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.MALFORMED_BAND,
                )
            }

            val defender = defenders.single()
            val banding = domain.bandConstraints.bandingAttackersByDefender[defender].orEmpty()
            val nonBanding = domain.bandConstraints.nonBandingAttackersByDefender[defender].orEmpty()
            if (band.any { it !in banding && it !in nonBanding }) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.MALFORMED_BAND,
                )
            }

            if (band.count { it in nonBanding } > 1) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.MALFORMED_BAND,
                )
            }

            if (band.any { !bandMemberOwners.add(it) }) {
                return AttackDeclarationValidationResult.Rejected(
                    AttackDeclarationRejection.DUPLICATE_BAND_MEMBER,
                )
            }
        }

        return AttackDeclarationValidationResult.Accepted
    }

    private fun isCanonicalCertificate(domain: RulesAttackDeclarationDomain): Boolean {
        val relation = domain.attackerToDefenders
        val attackerOrder = domain.attackerOrder
        if (attackerOrder.size != attackerOrder.distinct().size ||
            relation.keys != attackerOrder.toSet()
        ) return false

        val defenderOrder = buildDefenderOrder(attackerOrder, relation) ?: return false
        if (attackerOrder.any { attackerId ->
                val defenders = relation[attackerId] ?: return@any true
                defenders.isEmpty() || !defenders.isOrderedSubsequenceOf(defenderOrder)
            }
        ) return false

        if (!domain.mandatoryAttackers.isOrderedSubsequenceOf(attackerOrder) ||
            domain.mandatoryAttackers.any { it !in relation }
        ) {
            return false
        }

        if (domain.maxAttackers != null && domain.maxAttackers < 0) return false

        for ((attacker, requirements) in domain.coAttackerRequirements) {
            if (attacker !in relation || !requirements.isCanonicalRequirements(attackerOrder)) return false
            for (requirement in requirements) {
                if (!requirement.anyOf.isOrderedSubsequenceOf(attackerOrder) ||
                    requirement.anyOf.any { it == attacker || it !in relation }
                ) {
                    return false
                }
            }
        }

        val banding = domain.bandConstraints.bandingAttackersByDefender
        val nonBanding = domain.bandConstraints.nonBandingAttackersByDefender
        val partitionEntries = mutableMapOf<Pair<EntityId, EntityId>, Int>()
        for ((defender, attackers) in banding) {
            if (!isCanonicalPartition(defender, attackers, relation, attackerOrder)) return false
            for (attacker in attackers) {
                val edge = attacker to defender
                if (partitionEntries[edge] == 1) return false
                partitionEntries[edge] = 1
            }
        }
        for ((defender, attackers) in nonBanding) {
            if (!isCanonicalPartition(defender, attackers, relation, attackerOrder)) return false
            for (attacker in attackers) {
                val edge = attacker to defender
                if (partitionEntries[edge] != null) return false
                partitionEntries[edge] = 1
            }
        }

        return attackerOrder.all { attacker ->
            relation.getValue(attacker).all { defender -> partitionEntries[attacker to defender] == 1 }
        } && partitionEntries.keys.all { (attacker, defender) ->
            relation[attacker]?.contains(defender) == true
        }
    }

    private fun isCanonicalPartition(
        defender: EntityId,
        attackers: List<EntityId>,
        relation: Map<EntityId, List<EntityId>>,
        attackerOrder: List<EntityId>,
    ): Boolean {
        if (attackers.isEmpty() || !attackers.isOrderedSubsequenceOf(attackerOrder)) return false
        return attackers.all { attacker ->
            relation[attacker]?.contains(defender) == true
        }
    }

    private fun buildDefenderOrder(
        attackerOrder: List<EntityId>,
        relation: Map<EntityId, List<EntityId>>,
    ): List<EntityId>? {
        val defenderOrder = mutableListOf<EntityId>()
        for (attacker in attackerOrder) {
            val defenders = relation[attacker] ?: return null
            for (defender in defenders) {
                if (defender !in defenderOrder) defenderOrder += defender
            }
        }
        return defenderOrder
    }

    private fun List<EntityId>.isOrderedSubsequenceOf(order: List<EntityId>): Boolean {
        if (size != distinct().size) return false
        var previousIndex = -1
        for (entityId in this) {
            val index = order.indexOf(entityId)
            if (index <= previousIndex) return false
            previousIndex = index
        }
        return true
    }

    private fun List<RulesCoAttackerRequirement>.isCanonicalRequirements(
        attackerOrder: List<EntityId>,
    ): Boolean {
        val rankSequences = map { requirement ->
            requirement.anyOf.map(attackerOrder::indexOf)
        }
        return rankSequences.zipWithNext().all { (left, right) ->
            compareRankSequences(left, right) <= 0
        }
    }

    private fun compareRankSequences(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}
