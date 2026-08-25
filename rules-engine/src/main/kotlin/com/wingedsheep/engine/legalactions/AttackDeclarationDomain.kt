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
}

sealed interface AttackDeclarationDomainSupport {
    data object SUPPORTED : AttackDeclarationDomainSupport

    data class UNSUPPORTED(
        val reason: AttackDeclarationDomainUnsupportedReason,
    ) : AttackDeclarationDomainSupport
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
        if (!relation.keys.isCanonical()) return false
        if (relation.any { (_, defenders) ->
                defenders.isEmpty() || !defenders.isCanonical()
            }
        ) {
            return false
        }

        if (!domain.mandatoryAttackers.isCanonical() ||
            domain.mandatoryAttackers.any { it !in relation }
        ) {
            return false
        }

        if (domain.maxAttackers != null && domain.maxAttackers < 0) return false

        if (!domain.coAttackerRequirements.keys.isCanonical()) return false
        for ((attacker, requirements) in domain.coAttackerRequirements) {
            if (attacker !in relation || !requirements.isCanonicalRequirements()) return false
            for (requirement in requirements) {
                if (!requirement.anyOf.isCanonical() ||
                    requirement.anyOf.any { it == attacker || it !in relation }
                ) {
                    return false
                }
            }
        }

        val banding = domain.bandConstraints.bandingAttackersByDefender
        val nonBanding = domain.bandConstraints.nonBandingAttackersByDefender
        if (!banding.keys.isCanonical() || !nonBanding.keys.isCanonical()) return false

        val partitionEntries = mutableMapOf<Pair<EntityId, EntityId>, Int>()
        for ((defender, attackers) in banding) {
            if (!isCanonicalPartition(defender, attackers, relation)) return false
            for (attacker in attackers) {
                val edge = attacker to defender
                if (partitionEntries[edge] == 1) return false
                partitionEntries[edge] = 1
            }
        }
        for ((defender, attackers) in nonBanding) {
            if (!isCanonicalPartition(defender, attackers, relation)) return false
            for (attacker in attackers) {
                val edge = attacker to defender
                if (partitionEntries[edge] != null) return false
                partitionEntries[edge] = 1
            }
        }

        return relation.all { (attacker, defenders) ->
            defenders.all { defender -> partitionEntries[attacker to defender] == 1 }
        }
    }

    private fun isCanonicalPartition(
        defender: EntityId,
        attackers: List<EntityId>,
        relation: Map<EntityId, List<EntityId>>,
    ): Boolean {
        if (attackers.isEmpty() || !attackers.isCanonical()) return false
        return attackers.all { attacker ->
            relation[attacker]?.contains(defender) == true
        }
    }

    private fun List<EntityId>.isCanonical(): Boolean =
        size == distinct().size && zipWithNext().all { (left, right) ->
            left.value < right.value
        }

    private fun Collection<EntityId>.isCanonical(): Boolean =
        toList().isCanonical()

    private fun List<RulesCoAttackerRequirement>.isCanonicalRequirements(): Boolean {
        if (size != distinct().size) return false
        val keys = map { requirement ->
            requirement.anyOf.joinToString(separator = "\u0000") { it.value }
        }
        return keys.zipWithNext().all { (left, right) -> left < right }
    }
}
