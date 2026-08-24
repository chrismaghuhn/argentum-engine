package com.wingedsheep.engine.handlers.effects.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.mechanics.mana.productionSourceSubtypes
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.core.Subtype

/**
 * Tags mana added to a player's pool with its provenance — which source produced it and what
 * subtypes that source had — so payoffs can later ask "which kind of source produced the mana spent
 * to cast this?".
 *
 * The [ManaPoolComponent.manaBySubtype] / [ManaPoolComponent.manaBySource] counters record, per
 * subtype and per producing source, how many mana units in the pool came from there. When mana is
 * spent for a spell, [com.wingedsheep.engine.handlers.actions.spell.CastPaymentProcessor] consumes
 * from those counters proportional to the unrestricted mana taken from the pool and records what was
 * consumed on the spell (`SpentManaProvenance`).
 *
 * Generalizes the old Treasure-only counter (Treasure is now just `manaBySubtype[Subtype.TREASURE]`)
 * and powers Alchemist's Talent level 3 ("if mana from a Treasure was spent"), Bat Colony ("a Bat
 * for each mana from a Cave spent to cast it"), and the LCI mana-source lands (Tecutlan / Barracks /
 * Myriad Pools — "cast … using mana produced by this land"). The set of mana-producing executors
 * that call into here is: [AddManaExecutor], [AddColorlessManaExecutor], [AddManaOfChoiceExecutor]
 * (both the immediate and the post-color-choice resumer paths).
 */
object ManaProvenanceTracker {

    /**
     * Increment the producing player's provenance counters when [sourceId] produced [amount] mana.
     * [sourceSubtypes] is the production-time snapshot when the caller already captured it before
     * a tap/sacrifice. The fallback reads effective projected characteristics at this actual
     * production seam only; payment code never calls this method to reconstruct a historical
     * bucket.
     */
    fun addUnrestrictedMana(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId?,
        color: PaymentManaColor,
        amount: Int,
        sourceSubtypes: Set<Subtype>? = null,
    ): GameState {
        if (amount <= 0) return state
        val subtypes = sourceSubtypes ?: sourceId?.let {
            state.projectedState.productionSourceSubtypes(it)
        } ?: emptySet()
        return state.updateEntity(playerId) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            val updated = if (sourceId == null) {
                if (color == PaymentManaColor.COLORLESS) pool.addColorless(amount)
                else pool.add(color.asEngineColor()!!, amount)
            } else {
                pool.addTracked(
                    color = color,
                    sourceId = sourceId,
                    subtypes = subtypes,
                    amount = amount,
                    // This is stamped at the actual production transition, while the producing
                    // player is authoritative for the snapshot. Publication later must use this
                    // stored known-information fact, never current CardComponent visibility.
                    knownToPlayers = setOf(playerId),
                )
            }
            container.with(updated)
        }
    }

    /** Compatibility path for old callers; it cannot preserve source/color detail. */
    @Deprecated("Pass the concrete produced color to preserve source/color provenance")
    fun tagAddedMana(state: GameState, playerId: EntityId, sourceId: EntityId?, amount: Int): GameState {
        if (amount <= 0 || sourceId == null) return state
        return state.updateEntity(playerId) { container ->
            val pool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            // This compatibility API is called after the concrete production seam has already
            // lost its snapshot. Do not reconstruct subtype provenance from the current source;
            // preserve only the legacy source aggregate and remain fail-closed for joint payment.
            container.with(pool.withProvenance(sourceId, emptySet(), amount))
        }
    }
}
