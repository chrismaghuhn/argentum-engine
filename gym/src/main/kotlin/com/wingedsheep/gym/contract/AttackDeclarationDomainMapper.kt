package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainValidator
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
        data class Supported(val domain: AttackDeclarationDomainV2?) : Result

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
        if (!AttackDeclarationDomainValidator.isStructurallyValid(domain)) return Result.Unsupported

        val references = buildSet {
            addAll(domain.attackerOrder)
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

    private fun RulesAttackDeclarationDomain.toWireDomain(): AttackDeclarationDomainV2 {
        val defenderOrder = this.defenderOrder
        val relation = linkedMapOf<EntityId, List<EntityId>>()
        attackerOrder.forEach { attackerId ->
            relation[attackerId] = attackerToDefenders.getValue(attackerId).toList()
        }

        val coAttackers = linkedMapOf<EntityId, List<AttackCoAttackerRequirementV1>>()
        attackerOrder.forEach { attackerId ->
            coAttackerRequirements[attackerId]?.let { requirements ->
                coAttackers[attackerId] = requirements.map { requirement ->
                    AttackCoAttackerRequirementV1(anyOf = requirement.anyOf.toList())
                }
            }
        }

        return AttackDeclarationDomainV2(
            attackerOrder = attackerOrder.toList(),
            attackerToDefenders = relation,
            mandatoryAttackers = mandatoryAttackers.toList(),
            canDeclareZeroAttackers = canDeclareZeroAttackers,
            maxAttackers = maxAttackers,
            coAttackerRequirements = coAttackers,
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = bandConstraints.bandingAttackersByDefender
                    .orderedBy(defenderOrder),
                nonBandingAttackersByDefender = bandConstraints.nonBandingAttackersByDefender
                    .orderedBy(defenderOrder),
            ),
        )
    }

    private fun Map<EntityId, List<EntityId>>.orderedBy(
        order: List<EntityId>,
    ): Map<EntityId, List<EntityId>> = linkedMapOf<EntityId, List<EntityId>>().also { ordered ->
        order.forEach { entityId ->
            this[entityId]?.let { ids -> ordered[entityId] = ids.toList() }
        }
    }
}
