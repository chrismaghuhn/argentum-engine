package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.CompositeStaticAbility
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.IncreaseActivatedAbilityCost
import com.wingedsheep.sdk.scripting.ReduceActivatedAbilityCost
import com.wingedsheep.sdk.scripting.StaticAbility
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull

/**
 * The Rules-owned facts needed to qualify one paid mana ability for the V5 ordered program.
 *
 * V5 currently serializes prerequisite mana as an earlier activation node. Comprehensive Rules
 * 601.2g also permits mana abilities to be activated after another ability has been announced and
 * its total cost locked. A caller must therefore provide an explicit certification that those two
 * timing shapes are interchangeable for this candidate; publication must never infer that from a
 * syntactic "mana plus tap" shape alone.
 */
data class PaidManaSourceTimingCandidate(
    val state: GameState,
    val controllerId: EntityId,
    val sourceId: EntityId,
    val manaAbilityKey: String,
    val ability: ActivatedAbility,
    val effectiveCost: AbilityCost,
    val productionProfile: PaymentManaProductionProfile,
    val spellContext: SpellPaymentContext,
)

/**
 * Rules-owned qualification seam for paid mana sources.
 *
 * Returning false means that the complete V5 action domain is unsupported. It does not authorize
 * the caller to omit just this source or to fall back to automatic payment.
 */
fun interface PaidManaSourceTimingCertifier {
    fun certify(candidate: PaidManaSourceTimingCandidate): Boolean

    companion object {
        /**
         * The conservative first V5 slice used by the public payment-domain builder.
         *
         * This is deliberately a structural/state class, not a card-name exception. It certifies
         * only a fixed output bundle with one ordinary fixed mana component and one TapSelf, on a
         * source whose effective cost cannot be changed by the currently modelled state-dependent
         * cost paths. Any additional timing-sensitive shape remains fail-closed until it gets its
         * own Rules proof.
         */
        fun fixedFirstSlice(cardRegistry: CardRegistry): PaidManaSourceTimingCertifier =
            FixedFirstSlicePaidManaSourceTimingCertifier(cardRegistry)
    }
}

private class FixedFirstSlicePaidManaSourceTimingCertifier(
    private val cardRegistry: CardRegistry,
) : PaidManaSourceTimingCertifier {
    override fun certify(candidate: PaidManaSourceTimingCandidate): Boolean {
        if (!candidate.ability.isManaAbility ||
            candidate.ability.targetRequirements.isNotEmpty() ||
            candidate.ability.restrictions.isNotEmpty() ||
            candidate.ability.hasConvoke ||
            candidate.ability.hasWaterbend
        ) {
            return false
        }
        if (!candidate.effectiveCost.isFixedOrdinaryManaAndOneTap()) return false
        if (candidate.productionProfile !is PaymentManaProductionProfile.FixedOutputBundle) {
            return false
        }

        // These source-local rules can make the locked activation cost depend on the current
        // object/state in ways that the pre-generation V5 program does not model.
        if (candidate.ability.isPowerUp || candidate.ability.genericCostReduction != null) {
            return false
        }
        if (candidate.state.getEntity(candidate.sourceId)?.has<TextReplacementComponent>() == true) {
            return false
        }

        // ActivatedAbilityCostCalculator is Rules-owned and currently walks these statics while
        // computing the effective cost. Reject the whole certification whenever one is present:
        // prerequisite mana generation could change the state used by that calculation, and V5
        // has no nested cost-lock witness to preserve the distinction.
        val stabilityStaticAbilities = resolvePaymentStabilityStaticAbilities(
            state = candidate.state,
            cardRegistry = cardRegistry,
        ) ?: return false
        if (stabilityStaticAbilities.any(::containsActivatedAbilityCostModifier)) return false

        return true
    }

    private fun AbilityCost.isFixedOrdinaryManaAndOneTap(): Boolean {
        val components = when (this) {
            AbilityCost.Tap -> listOf(this)
            is AbilityCost.Composite -> costs
            else -> return false
        }
        var manaComponents = 0
        var tapComponents = 0
        for (component in components) {
            when (component) {
                AbilityCost.Tap -> tapComponents++
                else -> {
                    val manaCost = component.manaCostOrNull ?: return false
                    if (!manaCost.isFixedOrdinaryManaCost()) return false
                    manaComponents++
                }
            }
        }
        return manaComponents == 1 && tapComponents == 1
    }

    private fun containsActivatedAbilityCostModifier(ability: StaticAbility): Boolean = when (ability) {
        is ReduceActivatedAbilityCost,
        is IncreaseActivatedAbilityCost,
        -> true
        is ConditionalStaticAbility -> containsActivatedAbilityCostModifier(ability.ability)
        is CompositeStaticAbility -> ability.abilities.any(::containsActivatedAbilityCostModifier)
        else -> false
    }
}
