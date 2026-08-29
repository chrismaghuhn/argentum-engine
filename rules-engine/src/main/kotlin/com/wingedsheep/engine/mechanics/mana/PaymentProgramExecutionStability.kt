package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.ClassLevelComponent
import com.wingedsheep.engine.state.components.battlefield.GrantsControllerProtectionComponent
import com.wingedsheep.engine.state.components.battlefield.ReplacementEffectSourceComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.engine.state.components.player.DamageBonusComponent
import com.wingedsheep.engine.state.components.player.PlayerProtectionComponent
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.CompositeStaticAbility
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.IncreaseActivatedAbilityCost
import com.wingedsheep.sdk.scripting.NoncombatDamageBonus
import com.wingedsheep.sdk.scripting.PlayersCantActivateAbilities
import com.wingedsheep.sdk.scripting.PreventActivatedAbilities
import com.wingedsheep.sdk.scripting.ReplacementEffect
import com.wingedsheep.sdk.scripting.ReduceActivatedAbilityCost
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull

/**
 * The Rules-owned facts needed to prove that one V5 activation remains legal throughout an
 * ordered program whose earlier nodes can tap other sources.
 *
 * The first slice deliberately proves a stronger property than a particular plan needs: the
 * selected ability has no activation restriction or dynamic cost input, and the battlefield has
 * no supported activated-ability cost modifier. A fixed self-damage side effect is accepted only
 * when the Rules-owned life-mutation certificate has proved that every V5 payment fact remains
 * stable over the possible positive life losses, including intermediate life totals at or below
 * zero. This certificate does not impose a life floor or process state-based actions. External
 * activation-permission statics are also outside this first slice: their truth is read at
 * activation time and may depend on the state changed by an earlier TapSelf node, including
 * printed or durationally granted locks.
 */
data class PaymentProgramExecutionStabilityCandidate(
    val state: GameState,
    val controllerId: EntityId,
    val sourceId: EntityId,
    val manaAbilityKey: String,
    val ability: ActivatedAbility,
    val effectiveCost: AbilityCost,
    val productionProfile: PaymentManaProductionProfile,
    val sideEffectCertificate: PaymentManaSideEffectCertificate,
    /** Rules-owned proof that this candidate's fixed life mutation is stable for ordered execution. */
    val lifeMutationStabilityCertified: Boolean,
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
        when (val sideEffect = candidate.sideEffectCertificate) {
            PaymentManaSideEffectCertificate.NoSideEffect -> Unit
            is PaymentManaSideEffectCertificate.FixedSelfDamage -> {
                if (sideEffect.amount <= 0 || !candidate.lifeMutationStabilityCertified ||
                    !fixedSelfDamageEnvironmentIsSupported(candidate)
                ) {
                    return false
                }
            }

            is PaymentManaSideEffectCertificate.Unsupported -> return false
        }
        if (candidate.state.getEntity(candidate.sourceId)?.has<TextReplacementComponent>() == true) {
            return false
        }

        // An earlier TapSelf node can change which state-dependent cost modifiers apply. V5 has no
        // nested cost-lock witness, so any such modifier closes the complete public domain.
        if (hasActivatedAbilityCostModifier(candidate.state)) return false

        // Authoritative activation legality also includes permission statics that are not carried
        // by the selected ability itself. Their filters/conditions are read against live state by
        // CastPermissionUtils, so an earlier TapSelf node can make a later node illegal even when
        // the later ability has no own restriction. The first V5 slice has no permission-closure
        // certificate; reject every recognized printed or granted permission shape instead of
        // allowing the executor to bypass that authoritative check.
        if (hasExternalActivationPermission(candidate.state)) return false

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

    /**
     * Qualification closure for the one supported non-mana mutation in V5.
     *
     * [PaymentManaSideEffectCertificate.FixedSelfDamage] is exact only when the authoritative
     * damage path cannot prevent, redirect, replace, amplify, floor, or otherwise change the
     * damage/life result, and cannot grant lifelink or a protection outcome. The stability probe
     * intentionally does not invoke [DamageUtils]: it is a side-effect-free discovery pass, while
     * [ManaAbilitySideEffectExecutor] remains the execution authority. This conservative closure
     * therefore rejects every currently modelled state channel that [DamageUtils] could observe
     * for a noncombat damage event. It is deliberately broader than the candidate-specific
     * matching rules; false negatives are preferable to publishing an inexact public domain.
     */
    private fun fixedSelfDamageEnvironmentIsSupported(
        candidate: PaymentProgramExecutionStabilityCandidate,
    ): Boolean {
        val state = candidate.state

        // A player-level protection marker or a controller-protection grant can prevent the
        // source's damage before it reaches the life-total branch.
        if (state.getEntity(candidate.controllerId)?.get<PlayerProtectionComponent>() != null) {
            return false
        }
        if (state.getBattlefield().any { entityId ->
                state.getEntity(entityId)?.get<GrantsControllerProtectionComponent>() != null
            }
        ) {
            return false
        }

        // DamageUtils reads this player-scoped additive damage channel before the life-loss
        // replacement step. Any instance would make the fixed certificate inexact.
        if (state.turnOrder.any { playerId ->
                state.getEntity(playerId)?.get<DamageBonusComponent>() != null
            }
        ) {
            return false
        }

        // This flag and every currently attached replacement effect can change whether/how much
        // self-damage is dealt or how much life is lost. Match by event pattern rather than by a
        // closed list of replacement subclasses so newly added damage/life-loss replacements fail
        // closed without silently widening V5.
        if (state.damageCantBePreventedThisTurn || state.getBattlefield().any { entityId ->
                val container = state.getEntity(entityId) ?: return@any false
                val replacements = container.get<ReplacementEffectSourceComponent>()
                    ?.replacementEffects
                    .orEmpty()
                replacements.any { it.targetsDamageOrLifeLoss() }
            }
        ) {
            return false
        }
        if (state.grantedReplacementEffects.any { grant ->
                grant.replacement.targetsDamageOrLifeLoss()
            }
        ) {
            return false
        }

        // Floating shields are stored as a separate serializable channel and are not necessarily
        // present in ReplacementEffectSourceComponent. Reject all current damage/protection
        // modifications, including combat-only variants, because the V5 certificate has no
        // target/source matching proof for them and a later state change must not expose one.
        if (state.floatingEffects.any {
                it.effect.modification.isDamageOrProtectionInterference()
            }
        ) {
            return false
        }

        // DamageUtils grants life from damage when the source has lifelink. A fixed self-damage
        // probe has no life-gain mutation, so any projected lifelink closes this slice.
        if (state.projectedState.hasKeyword(candidate.sourceId, Keyword.LIFELINK.name)) {
            return false
        }

        // The direct DamageUtils path also reads these static amplification abilities. Treat an
        // unknown battlefield definition as unsupported rather than assuming it has no damage
        // modifier.
        if (state.getBattlefield().any { entityId ->
                val container = state.getEntity(entityId) ?: return@any false
                val card = container.get<CardComponent>() ?: return@any false
                val definition = cardRegistry.getCard(card.cardDefinitionId) ?: return@any true
                val classLevel = container.get<ClassLevelComponent>()?.currentLevel
                definition.script.effectiveStaticAbilities(classLevel)
                    .any(::containsNoncombatDamageBonus)
            }
        ) {
            return false
        }

        return true
    }

    private fun ReplacementEffect.targetsDamageOrLifeLoss(): Boolean =
        appliesTo is EventPattern.DamageEvent || appliesTo is EventPattern.LifeLossEvent

    private fun SerializableModification.isDamageOrProtectionInterference(): Boolean = when (this) {
        SerializableModification.PreventDamageFromAttackingCreatures,
        SerializableModification.PreventAllCombatDamage,
        SerializableModification.ReflectCombatDamage,
        SerializableModification.GrantProtectionFromColor,
        SerializableModification.GrantProtectionFromCardType,
        SerializableModification.PreventNextDamage,
        SerializableModification.PreventAllDamageTo,
        SerializableModification.PreventAllDamageDealtBy,
        SerializableModification.RedirectNextDamage,
        SerializableModification.PreventNextDamageFromCreatureType,
        SerializableModification.PreventCombatDamageFromGroup,
        SerializableModification.PreventAllDamageToGroup,
        SerializableModification.PreventCombatDamageToAndBy,
        SerializableModification.RedirectCombatDamageToController,
        SerializableModification.PreventNextDamageFromChosenSourceShield,
        SerializableModification.PreventAllDamageFromSource,
        SerializableModification.PreventNextDamageInstanceFromSource,
        SerializableModification.AmplifyNoncombatDamage,
        SerializableModification.DoubleDamageToPlayer,
        -> true
        else -> false
    }

    private fun containsNoncombatDamageBonus(
        ability: com.wingedsheep.sdk.scripting.StaticAbility,
    ): Boolean = when (ability) {
        is NoncombatDamageBonus -> true
        is ConditionalStaticAbility -> containsNoncombatDamageBonus(ability.ability)
        is CompositeStaticAbility -> ability.abilities.any(::containsNoncombatDamageBonus)
        else -> false
    }

    private fun hasExternalActivationPermission(state: GameState): Boolean {
        for (entityId in state.getBattlefield()) {
            val card = state.getEntity(entityId)?.get<CardComponent>() ?: continue
            val cardDefinition = cardRegistry.getCard(card.cardDefinitionId)
                ?: return true
            if (cardDefinition.script.staticAbilities.any(::containsExternalActivationPermission)) {
                return true
            }
        }
        return state.grantedStaticAbilities.any { grant ->
            containsExternalActivationPermission(grant.ability)
        }
    }

    private fun containsExternalActivationPermission(
        ability: com.wingedsheep.sdk.scripting.StaticAbility,
    ): Boolean = when (ability) {
        is PlayersCantActivateAbilities,
        is PreventActivatedAbilities,
        -> true
        is ConditionalStaticAbility -> containsExternalActivationPermission(ability.ability)
        is CompositeStaticAbility -> ability.abilities.any(::containsExternalActivationPermission)
        else -> false
    }
}
