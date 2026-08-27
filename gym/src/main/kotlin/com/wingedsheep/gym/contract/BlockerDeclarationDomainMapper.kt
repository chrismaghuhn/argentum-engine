package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.RulesBlockRequirement
import com.wingedsheep.engine.legalactions.RulesBlockerDeclarationDomain
import com.wingedsheep.engine.legalactions.RulesCoBlockerRequirement
import com.wingedsheep.sdk.model.EntityId

/**
 * Pure perspective-safe projection of the Rules blocker certificate into the versioned wire DTO.
 * A missing or unaddressable reference rejects the whole action; this mapper never filters a
 * hidden choice into a smaller, apparently legal domain.
 */
object BlockerDeclarationDomainMapper {

    sealed interface Result {
        data class Supported(val domain: BlockerDeclarationDomainV1?) : Result

        data object Unsupported : Result {
            val diagnostic: DiagnosticSignal = DiagnosticSignal(
                DiagnosticCode.BLOCKER_DECLARATION_DOMAIN_UNSUPPORTED,
            )
        }
    }

    fun map(
        action: LegalAction,
        isEntityReferenceAddressable: (EntityId) -> Boolean,
    ): Result {
        if (action.action !is DeclareBlockers) return Result.Supported(null)
        if (action.blockerDeclarationDomainSupport !is BlockerDeclarationDomainSupport.SUPPORTED) {
            return Result.Unsupported
        }
        val domain = action.blockerDeclarationDomain ?: return Result.Unsupported
        if (!isStructurallyRepresentable(domain)) return Result.Unsupported

        val references = buildSet {
            addAll(domain.blockerOrder)
            addAll(domain.attackerOrder)
            domain.blockerToAttackers.values.forEach(::addAll)
            domain.minBlockersByAttacker.keys.forEach(::add)
            domain.maxBlockersByAttacker.keys.forEach(::add)
            domain.coBlockerRequirements.forEach { (blockerId, requirements) ->
                add(blockerId)
                requirements.forEach { addAll(it.eligibleCoBlockers) }
            }
            domain.requirements.forEach { requirement ->
                when (requirement) {
                    is RulesBlockRequirement.BlockSpecific -> {
                        add(requirement.blockerId)
                        add(requirement.attackerId)
                    }
                    is RulesBlockRequirement.BlockOneOf -> {
                        add(requirement.blockerId)
                        addAll(requirement.attackerIds)
                    }
                    is RulesBlockRequirement.AttackerMustBeBlockedIfAble -> add(requirement.attackerId)
                    is RulesBlockRequirement.AttackerMustBeBlockedByAll -> add(requirement.attackerId)
                    is RulesBlockRequirement.BlockerMustBlockIfAble -> add(requirement.blockerId)
                }
            }
        }
        if (references.any { !isEntityReferenceAddressable(it) }) return Result.Unsupported

        return Result.Supported(domain.toWireDomain())
    }

    private fun isStructurallyRepresentable(domain: RulesBlockerDeclarationDomain): Boolean {
        val blockers = domain.blockerOrder
        if (!BlockerDeclarationDomainValidator.isCertificateStructurallyValid(domain)) return false
        if (domain.blockerToAttackers.keys.toList() != blockers) return false
        if (domain.maxAttackersByBlocker.keys.toList() != blockers) return false
        if (domain.coBlockerRequirements.keys.toList() != blockers.filter { it in domain.coBlockerRequirements }) {
            return false
        }
        // Rules owns all bounds, references, requirement multiplicity, co-blocker shape, and the
        // empty-declaration invariant. The mapper adds only wire-specific map-order checks here;
        // it must not grow a second certificate validator.
        return true
    }

    private fun RulesBlockerDeclarationDomain.toWireDomain(): BlockerDeclarationDomainV1 =
        BlockerDeclarationDomainV1(
            blockerOrder = blockerOrder,
            attackerOrder = attackerOrder,
            blockerToAttackers = blockerOrder.associateWith { blockerToAttackers.getValue(it) },
            maxAttackersByBlocker = blockerOrder.associateWith { maxAttackersByBlocker.getValue(it) },
            minBlockersByAttacker = attackerOrder
                .filter { it in minBlockersByAttacker }
                .associateWith { minBlockersByAttacker.getValue(it) },
            maxBlockersByAttacker = attackerOrder
                .filter { it in maxBlockersByAttacker }
                .associateWith { maxBlockersByAttacker.getValue(it) },
            globalMaxBlockers = globalMaxBlockers,
            coBlockerRequirements = blockerOrder
                .filter { it in coBlockerRequirements }
                .associateWith { blockerId ->
                    coBlockerRequirements.getValue(blockerId)
                        .map { requirement -> requirement.toWireCoBlockerRequirement() }
                },
            requirements = requirements.map { requirement -> requirement.toWireRequirement() },
            minimumSatisfiedRequirementCount = minimumSatisfiedRequirementCount,
            canDeclareZeroBlockers = canDeclareZeroBlockers,
        )

    private fun RulesCoBlockerRequirement.toWireCoBlockerRequirement(): BlockCoBlockerRequirementV1 =
        BlockCoBlockerRequirementV1(eligibleCoBlockers)

    private fun RulesBlockRequirement.toWireRequirement(): BlockRequirementV1 = when (this) {
        is RulesBlockRequirement.BlockSpecific ->
            BlockRequirementV1.BlockSpecific(blockerId, attackerId)
        is RulesBlockRequirement.BlockOneOf ->
            BlockRequirementV1.BlockOneOf(blockerId, attackerIds)
        is RulesBlockRequirement.AttackerMustBeBlockedIfAble ->
            BlockRequirementV1.AttackerMustBeBlockedIfAble(attackerId)
        is RulesBlockRequirement.AttackerMustBeBlockedByAll ->
            BlockRequirementV1.AttackerMustBeBlockedByAll(attackerId)
        is RulesBlockRequirement.BlockerMustBlockIfAble ->
            BlockRequirementV1.BlockerMustBlockIfAble(blockerId)
    }
}
