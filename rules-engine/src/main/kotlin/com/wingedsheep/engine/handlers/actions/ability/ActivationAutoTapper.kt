package com.wingedsheep.engine.handlers.actions.ability

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.fromManaPool
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId

/**
 * Activates mana abilities for an activated ability's mana cost (CR 601.2g via CR 602.2b) — the
 * auto-tap fast path taken when the player didn't pick sources explicitly.
 *
 * **Why this isn't the shared cast/cost payer.** The other auto-tap paths —
 * `CastPaymentProcessor.autoPay` (spells) and `CostPaymentService.payMana` (non-activation costs)
 * — tap *and pay* in one step. The activation flow is split differently: this stage only *fills the
 * pool*, and `CostHandler.payAbilityCost` then deducts the whole ability cost (mana and non-mana
 * atoms together) from it. That split is load-bearing:
 *  - mana a source produces under a restriction (Steelswarm Operator's artifact-source-only
 *    `{U}{U}`) enters the pool *tagged*, so payment spends the eligible restricted mana first and an
 *    unspent remainder stays restricted instead of laundering into unrestricted mana;
 *  - the *total* per-tap bonus mana (Lavaleaper) lands in the pool, rather than whatever of it the
 *    solver's internal accounting left over;
 *  - floating mana is only *counted* toward the cost here; the outer pool spend (the X portion
 *    included) happens in payment.
 *
 * The sources themselves are tapped through `ManaAbilitySideEffectExecutor.tapSourcesWithSideEffects`,
 * like the other auto-tap paths: tapping a source activates its mana ability, so the matched
 * ability's activation event, its own costs (a paid mana source's mana or life, which the solver
 * reserved from the same ledger) and its non-mana side effects all happen, and a failure rolls the
 * whole payment back.
 */
internal class ActivationAutoTapper(
    private val manaSolver: ManaSolver,
    private val manaAbilitySideEffectExecutor: ManaAbilitySideEffectExecutor,
) {

    data class Result(
        val newState: GameState,
        val newPool: ManaPool,
        val events: List<GameEvent>
    )

    /**
     * Auto-tap mana sources to cover a mana cost that can't be fully paid from the floating pool.
     * Taps sources for the shortfall and adds their mana to the pool so costHandler can consume it.
     * [additionalPayLife] is the life the outer cost pays in the same atomic payment. Returns null
     * if the cost cannot be paid.
     */
    fun autoTapForManaCost(
        state: GameState,
        playerId: EntityId,
        pool: ManaPool,
        cost: ManaCost,
        xValue: Int = 0,
        excludeSources: Set<EntityId> = emptySet(),
        abilityContext: SpellPaymentContext? = null,
        xManaRestriction: Set<Color> = emptySet(),
        additionalPayLife: Int = 0,
    ): Result? {
        val xSymbolCount = cost.xCount.coerceAtLeast(1)
        // Solve the complete payment against one shared ledger. The solver may reserve the supplied
        // floating mana for a selected mana source's own activation cost before that source produces
        // the mana needed by this ability, and it counts the floating pool toward the {X} portion
        // before any source is tapped (Aladdin's Lamp activated with X=4 while 4 mana float). The
        // returned solution still describes a deferred outer payment: this helper only taps sources
        // and leaves the outer pool spend to payAbilityCost.
        val solution = manaSolver.solve(
            state = state,
            playerId = playerId,
            cost = cost,
            xValue = xValue * xSymbolCount,
            excludeSources = excludeSources,
            spellContext = abilityContext,
            xManaRestriction = xManaRestriction,
            additionalPayLife = additionalPayLife,
            initialManaPool = pool,
        )
            ?: return null

        // The solver has already separated the shared pool ledger into nested activation-cost
        // spends and outer-cost spends. Use the exact post-activation view here; replaying generic
        // units would ignore restricted entries and could leave inner resources available for the
        // outer ability payment (or select a different eligible restriction).
        var currentPool = (solution.poolAfterActivation ?: pool)
            .withNormalizedProvenanceAfterSpend(pool)

        val sideEffectResult = manaAbilitySideEffectExecutor.tapSourcesWithSideEffects(
            state = state,
            solution = solution,
            controllerId = playerId,
        )
        if (!sideEffectResult.success) return null

        var currentState = sideEffectResult.state
        val events = sideEffectResult.events.toMutableList()

        // Add produced mana to floating pool so costHandler.payAbilityCost can consume it.
        // When the source's ability is restricted (e.g. Steelswarm Operator's
        // {T}: Add {U}{U} restricted to artifact-source ability activations), tag the
        // produced mana with that restriction. payAbilityCost will preferentially spend
        // the eligible restricted mana for the cost — and any unconsumed remainder stays
        // restricted in the pool instead of laundering into unrestricted mana.
        for (source in solution.sources) {
            // A tapped source may legitimately have no manaProduced entry: ManaSolver taps
            // extra sources to pay the *internal* activation cost of a mana ability (e.g. the
            // {1} in Hidden Grotto's "{1}, {T}: Add one mana of any color"). That mana is
            // consumed by the ability's own cost rather than flowing into the spell/ability
            // payment pool, so the solver intentionally omits it from manaProduced. Such a
            // source is still tapped above; it just contributes nothing to the pool here.
            val production = solution.manaProduced[source.entityId] ?: continue
            val color = production.color
            val restriction = if (color != null) {
                source.colorRestrictions[color] ?: source.restriction
            } else source.restriction
            currentPool = when {
                color != null && restriction != null ->
                    currentPool.addRestricted(color, production.amount, restriction)
                else -> currentPool.addUnrestrictedProduction(
                    sourceId = source.entityId,
                    production = production,
                    knownToPlayer = playerId,
                )
            }
        }

        // Add per-source bonus mana from AdditionalManaOnSourceTap auras/statics (e.g.,
        // Lavaleaper: tapping a basic land adds an extra mana of its produced color).
        // Unlike the cast flow — which uses solve's internal accounting as the payment —
        // the activate flow funnels all produced mana through the pool and then deducts
        // the cost via payAbilityCost, so the *total* bonus from tapping must land in the
        // pool. solution.remainingBonusMana would drop any bonus consumed during solve.
        // (Multi-mana excess is already included via manaProduced.amount above.)
        // Aura bonus mana is unrestricted — the source's restriction belongs to the
        // printed ability, not to the aura-granted extras.
        for (source in solution.sources) {
            if (source.bonusManaPerTap > 0 && source.bonusManaColor != null) {
                currentPool = currentPool.addTracked(
                    color = PaymentManaColor.fromEngine(source.bonusManaColor),
                    sourceId = source.entityId,
                    subtypes = source.sourceSubtypes,
                    amount = source.bonusManaPerTap,
                    knownToPlayers = setOf(playerId),
                )
            }
        }

        // Update state with enriched pool — carry restrictedMana and mana-source provenance through
        // so the ability-payment context can spend (and the leftover can stay) restricted, and so the
        // caller's final writeback still sees tags for mana floated before this auto-tap. This write
        // is transient (the caller overwrites the post-payment pool), but keeps intermediate state
        // consistent for anything that reads the pool between auto-tap and payment.
        currentState = currentState.updateEntity(playerId) { c ->
            c.with(fromManaPool(currentPool))
        }

        return Result(currentState, currentPool, events)
    }
}
