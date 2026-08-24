package com.wingedsheep.engine.handlers.effects.composite

import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.ManaSource
import com.wingedsheep.engine.mechanics.mana.fromManaPool
import com.wingedsheep.engine.mechanics.mana.toManaPool
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId

/**
 * Non-optional payment of an arbitrary [ManaCost] (colored pips included) shared by
 * [PayManaCostExecutor] (fixed cost) and [PayDynamicManaCostExecutor] (resolution-computed
 * generic amount + cross-player payer): spend whatever [player] already has floating, auto-tap
 * their mana sources for the remainder, and deduct [cost] from their pool. Auto-tapped sources
 * emit [TappedEvent]s. Insufficient mana is a recoverable [EffectResult.error] — callers that
 * pre-gate affordability (e.g. `Gate.MayPay`) only hit it on genuinely degenerate input.
 */
fun payManaCostFromPool(
    state: GameState,
    player: EntityId,
    cost: ManaCost,
    cardRegistry: CardRegistry
): EffectResult {
    val playerEntity = state.getEntity(player)
        ?: return EffectResult.error(state, "Paying player not found")

    val manaPoolComponent = playerEntity.get<ManaPoolComponent>()
        ?: return EffectResult.error(state, "Player has no mana pool")

    val manaPool = manaPoolComponent.toManaPool()

    val partialResult = manaPool.payPartial(cost)
    val remainingCost = partialResult.remainingCost
    var currentPool = manaPool
    var currentState = state
    val events = mutableListOf<GameEvent>()

    if (!remainingCost.isEmpty()) {
        val manaSolver = ManaSolver(cardRegistry)
        val solution = manaSolver.solve(currentState, player, remainingCost)
            ?: return EffectResult.error(state, "Cannot pay mana cost")

        val tapResult = ManaAbilitySideEffectExecutor.noOp(cardRegistry)
            .tapSourcesWithSideEffects(currentState, solution, player)
        if (!tapResult.success) return EffectResult.error(state, "Cannot pay mana ability side effect")
        currentState = tapResult.state
        events.addAll(tapResult.events)

        for ((sourceId, production) in solution.manaProduced) {
            val subtypes = state.getEntity(sourceId)
                ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                ?.typeLine?.subtypes.orEmpty()
            currentPool = if (production.color != null) {
                currentPool.addTracked(
                    color = PaymentManaColor.fromEngine(production.color),
                    sourceId = sourceId,
                    subtypes = subtypes,
                    amount = production.amount,
                )
            } else {
                currentPool.addTracked(
                    color = PaymentManaColor.COLORLESS,
                    sourceId = sourceId,
                    subtypes = subtypes,
                    amount = production.colorless,
                )
            }
        }
    }

    val newPool = currentPool.pay(cost)
        ?: return EffectResult.error(state, "Cannot pay mana cost after auto-tap")

    currentState = currentState.updateEntity(player) { container ->
        container.with(
            fromManaPool(newPool)
        )
    }

    return EffectResult.success(currentState, events)
}

/**
 * Whether [player] can pay [cost] *through the path [payManaCostFromPool] actually takes*: floating
 * mana first, then auto-tap for the remainder.
 *
 * Deliberately **not** [ManaSolver.canPay]. `canPay` answers the broader question "is this cost
 * payable at all", counting mana the auto-tap solver refuses to spend on the player's behalf —
 * sacrificing a Treasure, Springleaf Drum's tap-a-creature sub-cost, an explicitly-activated
 * ability (see the extras fallback in `ManaSolver.canPay`, and the matching `requiresSacrifice` /
 * `tapPermanentsSubCost` filters in `ManaSolver.solve`). A caller that *gates* on `canPay` and then
 * *pays* with [payManaCostFromPool] can therefore offer a payment that errors mid-resolution. Gate
 * on this instead whenever the payment is the auto-tapped one.
 *
 * [precomputedSources] is threaded straight to [ManaSolver.solve]; hoist it once with
 * [ManaSolver.findAvailableManaSources] when probing the same state repeatedly.
 */
fun canAutoPayManaCost(
    state: GameState,
    player: EntityId,
    cost: ManaCost,
    cardRegistry: CardRegistry,
    precomputedSources: List<ManaSource>? = null
): Boolean {
    val manaPoolComponent = state.getEntity(player)?.get<ManaPoolComponent>() ?: return false

    val manaPool = manaPoolComponent.toManaPool()

    val remainingCost = manaPool.payPartial(cost).remainingCost
    if (remainingCost.isEmpty()) return true

    return ManaSolver(cardRegistry)
        .solve(state, player, remainingCost, precomputedSources = precomputedSources) != null
}
