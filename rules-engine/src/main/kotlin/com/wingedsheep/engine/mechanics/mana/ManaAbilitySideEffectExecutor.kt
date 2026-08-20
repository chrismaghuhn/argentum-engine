package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.core.TappedEvent
import com.wingedsheep.engine.core.tap
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.DamageUtils
import com.wingedsheep.engine.handlers.effects.life.LifePaymentService
import com.wingedsheep.engine.mechanics.cost.CostAmountResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.effects.AddAnyColorManaSpendOnChosenTypeEffect
import com.wingedsheep.sdk.scripting.effects.AddColorlessManaEffect
import com.wingedsheep.sdk.scripting.effects.AddDynamicManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaOfChoiceEffect
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.Effect

/**
 * Runs the non-mana side effects of an activated mana ability when a source is
 * auto-tapped to pay a cost.
 *
 * Auto-tap fast paths (e.g. spell casting, cycling, combat tax) bypass the normal
 * activated-ability flow: they tap the source and credit its produced mana directly
 * to the payment, skipping the [com.wingedsheep.engine.handlers.actions.ability.ActivateAbilityHandler].
 * For most lands that's correct — the ability is just "{T}: Add {X}" — but pain
 * lands like Adarkar Wastes carry damage as part of the ability's effect
 * (`{T}: Add {W} or {U}. This land deals 1 damage to you.`). Without this helper
 * that damage is silently lost.
 *
 * The helper finds the activated mana ability that matches the produced color and
 * executes everything in its effect chain *except* the mana-producing pieces
 * (which the auto-tap path has already accounted for).
 */
class ManaAbilitySideEffectExecutor(
    private val cardRegistry: CardRegistry,
    private val effectExecutor: (GameState, Effect, EffectContext) -> EffectResult
) {

    /**
     * Run side effects for a single auto-tapped source.
     *
     * @param state Current game state (already mutated by the caller to reflect tap).
     * @param sourceId Permanent that was tapped.
     * @param producedColor Color the source produced for the payment, or null for colorless.
     * @param controllerId Player who controls the source / paid the cost.
     */
    /**
     * Tap every source in [solution] (emitting [TappedEvent]) and run any
     * non-mana side effects of the matching mana ability. This is the
     * one-shot form for callers that already have a [ManaSolution] from
     * [ManaSolver]; the produced mana itself is still consumed separately
     * via [ManaSolution.manaProduced].
     */
    fun tapSourcesWithSideEffects(
        state: GameState,
        solution: ManaSolution,
        controllerId: EntityId
    ): ManaSideEffectExecution {
        var currentState = state
        val events = mutableListOf<GameEvent>()
        for (source in solution.sources) {
            val (tappedState, event) = tap(currentState, source.entityId)
            currentState = tappedState
            event?.let(events::add)

            val production = solution.manaProduced[source.entityId]
            val selectedUse = solution.manaAbilityUses[source.entityId]
            // A source tapped only to pay another mana ability's activation cost has no production
            // entry, but the solver still records its exact selected ability so its costs and
            // non-mana effects are not silently skipped. A production-less source without that
            // provenance is unsafe to execute: fail closed and roll back the entire payment.
            if (production == null && selectedUse == null) {
                return ManaSideEffectExecution(state, emptyList(), success = false)
            }

            val sideEffectResult = runSideEffects(
                state = currentState,
                sourceId = source.entityId,
                producedColor = selectedUse?.producedColor ?: production?.color,
                controllerId = controllerId,
                selectedAbility = selectedUse?.ability
                    ?: production?.manaAbility
                    ?: production?.color?.let(source::manaAbilityFor)
                    ?: if (production != null) source.manaAbilityFor(null) else null,
            )
            if (!sideEffectResult.success) {
                // Auto-tap is one payment operation. Roll back the tap and every earlier side
                // effect when the selected dynamic life payment or effect execution fails.
                return ManaSideEffectExecution(state, emptyList(), success = false)
            }
            currentState = sideEffectResult.state
            events.addAll(sideEffectResult.events)
        }
        return ManaSideEffectExecution(currentState, events, success = true)
    }

    fun runSideEffects(
        state: GameState,
        sourceId: EntityId,
        producedColor: Color?,
        controllerId: EntityId,
        selectedAbility: ActivatedAbility? = null,
        resolvedPayLifeCost: Int? = null,
    ): ManaSideEffectExecution {
        val card = state.getEntity(sourceId)?.get<CardComponent>()
            ?: return ManaSideEffectExecution(state, emptyList(), success = true)
        val cardDef = cardRegistry.getCard(card.cardDefinitionId)

        val matchingAbility = selectedAbility ?: run {
            val candidates = cardDef?.script?.activatedAbilities
                ?.filter { it.isManaAbility && abilityProducesColor(it, producedColor) }
                .orEmpty()
            // No printed mana ability means there is no side effect to run (basic/intrinsic
            // sources use this path). Multiple candidates are unsafe without solver provenance.
            when {
                candidates.isEmpty() -> return ManaSideEffectExecution(state, emptyList(), success = true)
                candidates.size == 1 -> candidates.single()
                else -> return ManaSideEffectExecution(state, emptyList(), success = false)
            }
        }
        if (!matchingAbility.isManaAbility || !abilityProducesColor(matchingAbility, producedColor)) {
            return ManaSideEffectExecution(state, emptyList(), success = false)
        }

        var currentState = state
        val events = mutableListOf<GameEvent>()

        // Pain modeled as part of the ability's *cost* (e.g. Starting Town's
        // "{T}, Pay 1 life: Add one mana of any color") — the auto-tap fast path only
        // pays the tap, so any life-payment cost atom would otherwise be silently skipped.
        // (Pain modeled as an *effect*, like Adarkar Wastes, is handled by the sub-effect
        // loop below.) The solver already tracks these via ManaSource.hasPainCost for tap
        // priority, but never deducts the life.
        val lifeCost = resolvedPayLifeCost ?: payLifeCost(
            state = currentState,
            cost = matchingAbility.cost,
            sourceId = sourceId,
            controllerId = controllerId,
        )
        if (lifeCost == null || lifeCost < 0 || currentState.lifeTotal(controllerId) < lifeCost) {
            return ManaSideEffectExecution(state, emptyList(), success = false)
        }
        if (lifeCost > 0) {
            val payment = LifePaymentService.pay(currentState, controllerId, lifeCost)
                ?: return ManaSideEffectExecution(state, emptyList(), success = false)
            currentState = payment.first
            events.addAll(payment.second)
        }

        val sideEffects = nonManaSubEffects(matchingAbility.effect)
        if (sideEffects.isEmpty()) return ManaSideEffectExecution(currentState, events, success = true)

        val context = EffectContext(
            sourceId = sourceId,
            controllerId = controllerId,
        )

        for (sub in sideEffects) {
            val result = effectExecutor(currentState, sub, context)
            if (!result.isSuccess) {
                return ManaSideEffectExecution(state, emptyList(), success = false)
            }
            currentState = result.state
            events.addAll(result.events)
            // Side effects from auto-tap should never pause for player decisions
            // (mana abilities don't use the stack); a pause is therefore a failed
            // auto-payment and was handled transactionally above.
        }
        return ManaSideEffectExecution(currentState, events, success = true)
    }

    /**
     * Sum of life-payment ([CostAtom.PayLife]) amounts in a mana ability's cost, recursing
     * through composite costs (e.g. `{T}, Pay 1 life`). Returns 0 when the cost has no
     * life component and null when a dynamic life amount cannot be resolved.
     */
    private fun payLifeCost(
        state: GameState,
        cost: AbilityCost,
        sourceId: EntityId,
        controllerId: EntityId,
    ): Int? = CostAmountResolver.resolvePayLifeTotal(
        state = state,
        amounts = CostAmountResolver.payLifeAmounts(cost),
        sourceId = sourceId,
        controllerId = controllerId,
        cardRegistry = cardRegistry,
    )

    private fun abilityProducesColor(ability: ActivatedAbility, color: Color?): Boolean =
        manaSubEffects(ability.effect).any { effect -> effectProduces(effect, color) }

    private fun effectProduces(effect: Effect, color: Color?): Boolean = when (effect) {
        is AddManaEffect -> effect.color == color
        is AddColorlessManaEffect -> color == null
        is AddManaOfChoiceEffect,
        is AddAnyColorManaSpendOnChosenTypeEffect -> color != null  // any non-null color
        is AddDynamicManaEffect -> color != null && color in effect.allowedColors
        else -> false
    }

    private fun manaSubEffects(effect: Effect): List<Effect> = when (effect) {
        is CompositeEffect -> effect.effects.filter { isManaEffect(it) }
        else -> if (isManaEffect(effect)) listOf(effect) else emptyList()
    }

    private fun nonManaSubEffects(effect: Effect): List<Effect> = when (effect) {
        is CompositeEffect -> effect.effects.filterNot { isManaEffect(it) }
        else -> emptyList()  // single-effect mana abilities have nothing extra to run
    }

    private fun isManaEffect(effect: Effect): Boolean = effect is AddManaEffect ||
        effect is AddColorlessManaEffect ||
        effect is AddManaOfChoiceEffect ||
        effect is AddAnyColorManaSpendOnChosenTypeEffect ||
        effect is AddDynamicManaEffect

    companion object {
        /**
         * Stand-in instance for default-constructed contexts (e.g. a [CombatManager]
         * built without an [EngineServices] wiring). Side effects are dropped on the
         * floor — production code must use the executor wired by [EngineServices].
         */
        fun noOp(cardRegistry: CardRegistry): ManaAbilitySideEffectExecutor =
            ManaAbilitySideEffectExecutor(cardRegistry) { state, _, _ ->
                EffectResult.success(state)
            }
    }
}

/** Transactional result of an auto-tap side-effect payment. */
data class ManaSideEffectExecution(
    val state: GameState,
    val events: List<GameEvent>,
    val success: Boolean,
)
