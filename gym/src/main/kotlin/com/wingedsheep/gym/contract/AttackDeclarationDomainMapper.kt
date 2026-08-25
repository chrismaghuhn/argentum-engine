package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomain
import com.wingedsheep.sdk.model.EntityId

/**
 * Pure perspective-safe projection of the Rules attack certificate into its versioned wire DTO.
 * A single unaddressable or structurally invalid reference rejects the whole action; this mapper
 * never filters a hidden choice into a smaller, apparently legal domain.
 */
object AttackDeclarationDomainMapper {

    sealed interface Result {
        data class Supported(val domain: AttackDeclarationDomainV1?) : Result

        data object Unsupported : Result {
            val diagnostic: DiagnosticSignal = DiagnosticSignal(
                DiagnosticCode.ATTACK_DECLARATION_DOMAIN_UNSUPPORTED,
            )
        }
    }

    fun map(
        action: LegalAction,
        isEntityReferenceAddressable: (EntityId) -> Boolean,
    ): Result {
        if (action.action !is DeclareAttackers) {
            return Result.Supported(null)
        }

        if (action.attackDeclarationDomainSupport !is AttackDeclarationDomainSupport.SUPPORTED) {
            return Result.Unsupported
        }
        val domain = action.attackDeclarationDomain ?: return Result.Unsupported
        if (!isStructurallyRepresentable(domain)) return Result.Unsupported

        val references = buildSet {
            domain.attackerToDefenders.forEach { (attacker, defenders) ->
                add(attacker)
                addAll(defenders)
            }
            addAll(domain.mandatoryAttackers)
            domain.coAttackerRequirements.forEach { (attacker, requirements) ->
                add(attacker)
                requirements.forEach { addAll(it.anyOf) }
            }
            domain.bandConstraints.bandingAttackersByDefender.forEach { (defender, attackers) ->
                add(defender)
                addAll(attackers)
            }
            domain.bandConstraints.nonBandingAttackersByDefender.forEach { (defender, attackers) ->
                add(defender)
                addAll(attackers)
            }
        }
        if (references.any { !isEntityReferenceAddressable(it) }) return Result.Unsupported

        return Result.Supported(domain.toWireDomain())
    }

    private fun isStructurallyRepresentable(domain: RulesAttackDeclarationDomain): Boolean {
        val relation = domain.attackerToDefenders
        if (relation.any { (_, defenders) ->
                defenders.isEmpty() || defenders.size != defenders.toSet().size
            }
        ) return false
        val attackers = relation.keys

        if (domain.mandatoryAttackers.size != domain.mandatoryAttackers.toSet().size ||
            domain.mandatoryAttackers.any { it !in attackers }
        ) return false
        if (domain.maxAttackers?.let { it < 0 } == true) return false

        for ((attacker, requirements) in domain.coAttackerRequirements) {
            if (attacker !in attackers) return false
            val requirementKeys = requirements.map { it.anyOf.toSet() }
            if (requirementKeys.size != requirementKeys.toSet().size) return false
            if (requirements.any { requirement ->
                    requirement.anyOf.isEmpty() ||
                        requirement.anyOf.size != requirement.anyOf.toSet().size ||
                        requirement.anyOf.any { it == attacker || it !in attackers }
                }
            ) return false
        }

        val banding = domain.bandConstraints.bandingAttackersByDefender
        val nonBanding = domain.bandConstraints.nonBandingAttackersByDefender
        val partitionEntries = mutableSetOf<Pair<EntityId, EntityId>>()
        for ((defender, members) in banding) {
            if (!isPartitionEntryValid(defender, members, relation)) return false
            for (attacker in members) {
                if (!partitionEntries.add(attacker to defender)) return false
            }
        }
        for ((defender, members) in nonBanding) {
            if (!isPartitionEntryValid(defender, members, relation)) return false
            for (attacker in members) {
                if (!partitionEntries.add(attacker to defender)) return false
            }
        }

        return relation.all { (attacker, defenders) ->
            defenders.all { defender -> attacker to defender in partitionEntries }
        }
    }

    private fun isPartitionEntryValid(
        defender: EntityId,
        members: List<EntityId>,
        relation: Map<EntityId, List<EntityId>>,
    ): Boolean = members.isNotEmpty() &&
        members.size == members.toSet().size &&
        members.all { attacker -> relation[attacker]?.contains(defender) == true }

    private fun RulesAttackDeclarationDomain.toWireDomain(): AttackDeclarationDomainV1 =
        AttackDeclarationDomainV1(
            attackerToDefenders = attackerToDefenders.canonicalEntityMap(),
            mandatoryAttackers = mandatoryAttackers.sortedBy(EntityId::value),
            canDeclareZeroAttackers = canDeclareZeroAttackers,
            maxAttackers = maxAttackers,
            coAttackerRequirements = coAttackerRequirements
                .entries
                .sortedBy { (attacker, _) -> attacker.value }
                .associate { (attacker, requirements) ->
                    attacker to requirements
                        .map { requirement ->
                            AttackCoAttackerRequirementV1(
                                anyOf = requirement.anyOf.sortedBy(EntityId::value),
                            )
                        }
                        .sortedBy { requirement ->
                            requirement.anyOf.joinToString(separator = "\u0000") { it.value }
                        }
                },
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = bandConstraints.bandingAttackersByDefender
                    .canonicalEntityMap(),
                nonBandingAttackersByDefender = bandConstraints.nonBandingAttackersByDefender
                    .canonicalEntityMap(),
            ),
        )

    private fun Map<EntityId, List<EntityId>>.canonicalEntityMap(): Map<EntityId, List<EntityId>> =
        entries
            .sortedBy { (entityId, _) -> entityId.value }
            .associate { (entityId, ids) ->
                entityId to ids.sortedBy(EntityId::value)
            }
}
