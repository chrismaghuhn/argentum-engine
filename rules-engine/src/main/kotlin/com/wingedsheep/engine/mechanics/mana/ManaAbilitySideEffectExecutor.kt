package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.TappedEvent
import com.wingedsheep.engine.core.tapForMana
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.DamageUtils
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
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
    private val zones: ZoneTransitionService,
    private val cardRegistry: CardRegistry,
    private val effectExecutor: (GameState, Effect, EffectContext) -> EffectResult
) {

    /**
     * Tap every source in [solution] (emitting [TappedEvent]) and run any
     * non-mana side effects of the matching mana ability. This is the
     * one-shot form for callers that already have a [ManaSolution] from
     * [ManaSolver]; the produced mana itself is still consumed separately
     * via [ManaSolution.manaProduced].
     *
     * The whole tap-and-side-effect sequence is one payment operation: when any selected
     * ability's dynamic life payment or effect execution fails, the result is unsuccessful and
     * carries the untouched input state.
     */
    fun tapSourcesWithSideEffects(
        state: GameState,
        solution: ManaSolution,
        controllerId: EntityId
    ): ManaSideEffectExecution {
        var currentState = state
        val events = mutableListOf<GameEvent>()
        for (source in solution.sources) {
            val (tappedState, tapEvents) = tapForMana(currentState, source.entityId, controllerId)
            currentState = tappedState
            events.addAll(tapEvents)

            val production = solution.manaProduced[source.entityId]
            val selectedUse = solution.manaAbilityUses[source.entityId]
            // A source tapped only to pay another mana ability's activation cost has no production
            // entry, but the solver still records its exact selected ability so its costs and
            // non-mana effects are not silently skipped. A production-less source without that
            // provenance is unsafe to execute: fail closed and roll back the entire payment.
            if (production == null && selectedUse == null) {
                return ManaSideEffectExecution(state, emptyList(), success = false)
            }

            val producedColor = selectedUse?.producedColor ?: production?.color
            val selectedAbility = selectedUse?.ability
                ?: production?.manaAbility
                ?: production?.color?.let(source::manaAbilityFor)
                ?: if (production != null) source.manaAbilityFor(null) else null

            // Auto-tapping a source *is* the player activating its mana ability — the fast path is
            // a UI shortcut, not a different game action (CR 605.3). Emit the activation event the
            // manual path emits so "whenever you activate an ability" triggers see it (Elrond,
            // Moon-Reader off an auto-tapped Llanowar Elves). Emitted after the TappedEvent: the
            // tap is the cost, and the ability is activated once its costs are paid.
            activationEvent(
                currentState,
                source.entityId,
                controllerId,
                selectedAbility ?: matchingManaAbility(currentState, source.entityId, producedColor),
            )?.let(events::add)

            val sideEffectResult = runSideEffects(
                state = currentState,
                sourceId = source.entityId,
                producedColor = producedColor,
                controllerId = controllerId,
                selectedAbility = selectedAbility,
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

    /**
     * The [AbilityActivatedEvent] for an auto-tapped mana source, or null if [sourceId] isn't a
     * card (nothing to name in the event).
     *
     * `costsTap` is true by construction — this path only ever reaches sources it taps — so the
     * Antiquities "without {T} in its activation cost" template correctly ignores these. `isExhaust`
     * is read off the matching printed ability where one is found; an intrinsic land mana ability
     * has no [ActivatedAbility] entry to consult and is never exhaust anyway.
     */
    fun activationEvent(
        state: GameState,
        sourceId: EntityId,
        producedColor: Color?,
        controllerId: EntityId,
    ): AbilityActivatedEvent? = activationEvent(
        state, sourceId, controllerId, matchingManaAbility(state, sourceId, producedColor)
    )

    private fun activationEvent(
        state: GameState,
        sourceId: EntityId,
        controllerId: EntityId,
        matchingAbility: ActivatedAbility?,
    ): AbilityActivatedEvent? {
        val card = state.getEntity(sourceId)?.get<CardComponent>() ?: return null
        return AbilityActivatedEvent(
            sourceId = sourceId,
            sourceName = card.name,
            controllerId = controllerId,
            abilityEntityId = null,
            costsTap = true,
            isManaAbility = true,
            isExhaust = matchingAbility?.isExhaust == true
        )
    }

    /**
     * The mana ability of [sourceId] that produced [producedColor], if there is one: a printed one
     * first, then one a resolved effect granted it (`GameState.grantedActivatedAbilities` — the
     * auto-payer taps those too, e.g. Emrakul, the Exigent Doom's "{T}: Add {C}{C}").
     */
    private fun matchingManaAbility(
        state: GameState,
        sourceId: EntityId,
        producedColor: Color?,
    ): ActivatedAbility? = manaAbilityCandidates(state, sourceId, producedColor).firstOrNull()

    /** Every printed, then runtime-granted, mana ability of [sourceId] that produces [producedColor]. */
    private fun manaAbilityCandidates(
        state: GameState,
        sourceId: EntityId,
        producedColor: Color?,
    ): List<ActivatedAbility> {
        val card = state.getEntity(sourceId)?.get<CardComponent>() ?: return emptyList()
        val printed = cardRegistry.getCard(card.cardDefinitionId)?.script?.activatedAbilities.orEmpty()
        val granted = state.grantedActivatedAbilities.asSequence()
            .filter { it.entityId == sourceId }
            .map { it.ability }
        return (printed.asSequence() + granted)
            .filter { it.isManaAbility && abilityProducesColor(it, producedColor) }
            .toList()
    }

    /**
     * Run side effects for a single auto-tapped source.
     *
     * @param state Current game state (already mutated by the caller to reflect tap).
     * @param sourceId Permanent that was tapped.
     * @param producedColor Color the source produced for the payment, or null for colorless.
     * @param controllerId Player who controls the source / paid the cost.
     * @param selectedAbility The exact mana ability the payment selected; when null the single
     *   matching ability is used, and more than one candidate fails closed.
     * @param resolvedPayLifeCost The life cost already resolved by the caller, if any.
     */
    fun runSideEffects(
        state: GameState,
        sourceId: EntityId,
        producedColor: Color?,
        controllerId: EntityId,
        selectedAbility: ActivatedAbility? = null,
        resolvedPayLifeCost: Int? = null,
    ): ManaSideEffectExecution {
        state.getEntity(sourceId)?.get<CardComponent>()
            ?: return ManaSideEffectExecution(state, emptyList(), success = true)

        val matchingAbility = selectedAbility ?: run {
            val candidates = manaAbilityCandidates(state, sourceId, producedColor)
            // No mana ability means there is no side effect to run (basic/intrinsic
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
        if (lifeCost == null || lifeCost < 0 || !currentState.canPayLife(controllerId, lifeCost)) {
            return ManaSideEffectExecution(state, emptyList(), success = false)
        }
        if (lifeCost > 0) {
            val payment = LifePaymentService.pay(zones, currentState, controllerId, lifeCost)
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
            // Side effects from auto-tap should never pause for player decisions
            // (mana abilities don't use the stack); a pause or a rejection is therefore a
            // failed auto-payment and is rolled back transactionally.
            if (result.outcome !is Outcome.Done) {
                return ManaSideEffectExecution(state, emptyList(), success = false)
            }
            currentState = result.state
            events.addAll(result.events)
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
        fun noOp(zones: ZoneTransitionService): ManaAbilitySideEffectExecutor =
            ManaAbilitySideEffectExecutor(zones, zones.cardRegistry) { state, _, _ ->
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
