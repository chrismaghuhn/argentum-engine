package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.TargetChooser

/**
 * Projects the complete Rules-owned action target seam into the bounded Gym V1 contract.
 *
 * This mapper is deliberately pure and does not know about perspectives or the game state. It reads
 * only the authoritative [LegalAction.targetRequirements], its [LegalAction.targetDomainSupport],
 * the structural [TargetPayloadPartition] certificate, and the engine-owned reference-addressability
 * predicate supplied by [ObservationBuilder]. It never consults CardDefinition, Oracle text,
 * hidden components, or a trainer-side legality algorithm.
 */
object ActionTargetDomainMapper {

    sealed interface Result {
        data class Supported(val domain: ActionTargetDomainV1) : Result

        /** Stable and intentionally detail-free: no card, entity, or internal reason crosses Gym. */
        data object Unsupported : Result {
            val diagnostic: DiagnosticSignal = DiagnosticSignal(
                DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED,
            )
        }
    }

    fun map(
        action: LegalAction,
        isEntityReferenceAddressable: (EntityId) -> Boolean,
    ): Result {
        if (action.targetDomainSupport !is TargetDomainSupport.SUPPORTED) {
            return Result.Unsupported
        }

        if (TargetPayloadPartition.certify(action) !is TargetPayloadPartition.Certification.Supported) {
            return Result.Unsupported
        }

        val requirements = action.targetRequirements
        if (requirements.isEmpty()) {
            return Result.Supported(ActionTargetDomainV1())
        }

        if (requirements.withIndex().any { (position, requirement) ->
                requirement.index != position ||
                    requirement.minTargets < 0 ||
                    requirement.maxTargets < requirement.minTargets ||
                    requirement.validTargets.size != requirement.validTargets.toSet().size ||
                    requirement.validTargets.size < requirement.minTargets ||
                    requirement.targetChooser != TargetChooser.Controller ||
                    requirement.validTargets.any { !isEntityReferenceAddressable(it) }
            }) {
            return Result.Unsupported
        }

        return Result.Supported(
            ActionTargetDomainV1(
                requirements = requirements.map(::mapRequirement),
            ),
        )
    }

    private fun mapRequirement(info: TargetInfo): TargetRequirementDomain =
        TargetRequirementDomain(
            index = info.index,
            description = info.description,
            minTargets = info.minTargets,
            maxTargets = info.maxTargets,
            candidates = info.validTargets.sortedBy { it.value },
            targetZone = info.targetZone,
            mustDifferFromEarlier = info.mustDifferFromEarlier,
            sameController = info.sameController,
            sameOwner = info.sameOwner,
            sameCreatureType = info.sameCreatureType,
            sameCardType = info.sameCardType,
            totalManaValueAtMost = info.totalManaValueAtMost,
            differentNames = info.differentNames,
            xConstrainsManaValue = info.xConstrainsManaValue,
            xConstrainsManaValueExactly = info.xConstrainsManaValueExactly,
            xConstrainsPower = info.xConstrainsPower,
            xConstrainsCount = info.xConstrainsCount,
        )
}
