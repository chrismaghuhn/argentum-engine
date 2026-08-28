package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.CompositeStaticAbility
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.IncreaseActivatedAbilityCost
import com.wingedsheep.sdk.scripting.ReduceActivatedAbilityCost
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull

/**
 * The Rules-owned facts needed to prove that one V5 activation remains legal throughout an
 * ordered program whose earlier nodes can tap other sources.
 *
 * The first slice deliberately proves a stronger property than a particular plan needs: the
 * selected ability has no activation restriction or dynamic cost input, and the battlefield has
 * no supported activated-ability cost modifier. Together with V5's NoSideEffect/TapSelf source
 * contract, an earlier node can then change only its own tapped state and cannot invalidate a
 * later node after the single preflight boundary.
 */
data class PaymentProgramExecutionStabilityCandidate(
    val state: GameState,
    val controllerId: EntityId,
    val sourceId: EntityId,
    val manaAbilityKey: String,
    val ability: ActivatedAbility,
    val effectiveCost: AbilityCost,
    val productionProfile: PaymentManaProductionProfile,
)

/**
 * Rules-owned qualification seam for the V5 ordered payment program.
 *
 * Returning false makes the complete V5 action domain unsupported. It never authorizes omission
 * of one source or an automatic fallback. Publisher and validator callers use the same
 * certificate through [ManaSolver].
 */
fun interface PaymentProgramExecutionStabilityCertifier {
    fun certify(candidate: PaymentProgramExecutionStabilityCandidate): Boolean

    companion object {
        /** The conservative, fixed first-slice certificate used by V5 publication and validation. */
        fun fixedFirstSlice(cardRegistry: CardRegistry): PaymentProgramExecutionStabilityCertifier =
            FixedFirstSlicePaymentProgramExecutionStabilityCertifier(cardRegistry)
    }
}

private class FixedFirstSlicePaymentProgramExecutionStabilityCertifier(
    private val cardRegistry: CardRegistry,
) : PaymentProgramExecutionStabilityCertifier {
    override fun certify(candidate: PaymentProgramExecutionStabilityCandidate): Boolean {
        val ability = candidate.ability
        if (!ability.isManaAbility ||
            ability.targetRequirements.isNotEmpty() ||
            ability.restrictions.isNotEmpty() ||
            ability.hasConvoke ||
            ability.hasWaterbend
        ) {
            return false
        }
        if (ability.isPowerUp || ability.genericCostReduction != null) return false
        if (!candidate.effectiveCost.isFixedTapOrOrdinaryManaAndTap()) return false
        if (candidate.productionProfile is PaymentManaProductionProfile.Unsupported) return false
        if (candidate.state.getEntity(candidate.sourceId)?.has<TextReplacementComponent>() == true) {
            return false
        }

        // An earlier TapSelf node can change which state-dependent cost modifiers apply. V5 has no
        // nested cost-lock witness, so any such modifier closes the complete public domain.
        if (hasActivatedAbilityCostModifier(candidate.state)) return false

        return true
    }

    private fun AbilityCost.isFixedTapOrOrdinaryManaAndTap(): Boolean {
        val components = when (this) {
            AbilityCost.Tap -> listOf(this)
            is AbilityCost.Composite -> costs
            else -> return false
        }
        var tapCount = 0
        var manaComponentSeen = false
        for (component in components) {
            when (component) {
                AbilityCost.Tap -> tapCount++
                is AbilityCost.Atom -> {
                    val manaCost = component.manaCostOrNull ?: return false
                    if (manaComponentSeen || !manaCost.canonicalPaymentManaCost().isFixedOrdinaryManaCost()) {
                        return false
                    }
                    manaComponentSeen = true
                }
                else -> return false
            }
        }
        return tapCount == 1
    }

    private fun hasActivatedAbilityCostModifier(state: GameState): Boolean {
        for (entityId in state.getBattlefield()) {
            val card = state.getEntity(entityId)?.get<CardComponent>() ?: continue
            val cardDefinition = cardRegistry.getCard(card.cardDefinitionId)
                ?: return true
            if (cardDefinition.script.staticAbilities.any(::containsActivatedAbilityCostModifier)) {
                return true
            }
        }
        return false
    }

    private fun containsActivatedAbilityCostModifier(
        ability: com.wingedsheep.sdk.scripting.StaticAbility,
    ): Boolean = when (ability) {
        is ReduceActivatedAbilityCost,
        is IncreaseActivatedAbilityCost,
        -> true
        is ConditionalStaticAbility -> containsActivatedAbilityCostModifier(ability.ability)
        is CompositeStaticAbility -> ability.abilities.any(::containsActivatedAbilityCostModifier)
        else -> false
    }
}
