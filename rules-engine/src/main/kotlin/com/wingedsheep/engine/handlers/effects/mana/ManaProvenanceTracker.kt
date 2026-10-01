package com.wingedsheep.engine.handlers.effects.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.mechanics.mana.productionSourceSubtypes
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaSourceTag
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.core.Subtype

/**
 * Tags mana added to a player's pool with its provenance — which source produced it and what
 * subtypes and card types that source had — so payoffs can later ask "which kind of source produced the mana spent
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
     * Snapshot what [sourceId] is right now. [sourceSubtypes] is the production-time subtype
     * snapshot when the caller already captured it before a tap/sacrifice; otherwise subtypes read
     * the effective projected characteristics while the source is on the battlefield and fall back
     * to the base [CardComponent.typeLine] once it has left (a Treasure's `{T}, Sacrifice this`
     * pays the cost before the mana effect resolves, but the entity persists with its base type
     * line intact). Card types read the *projected* type line while the source is on the
     * battlefield, so an animated land's mana is mana from a creature, and fall back to the base
     * type line once it has left (a creature sacrificed for its own mana still made creature mana).
     */
    fun sourceTag(state: GameState, sourceId: EntityId, sourceSubtypes: Set<Subtype>? = null): ManaSourceTag {
        val typeLine = state.getEntity(sourceId)?.get<CardComponent>()?.typeLine
        val projectedTypes = state.projectedState.getTypes(sourceId)
        val onBattlefield = projectedTypes.isNotEmpty()
        val cardTypes = if (onBattlefield) {
            CardType.entries.filterTo(mutableSetOf()) { it.name in projectedTypes }
        } else {
            typeLine?.cardTypes ?: emptySet()
        }
        val subtypes = sourceSubtypes ?: if (onBattlefield) {
            state.projectedState.productionSourceSubtypes(sourceId)
        } else {
            typeLine?.subtypes?.toSet() ?: emptySet()
        }
        return ManaSourceTag(sourceId, subtypes, cardTypes)
    }

    /**
     * Increment the producing player's provenance counters when [sourceId] produced [amount] mana.
     * [sourceSubtypes] is the production-time snapshot when the caller already captured it before
     * a tap/sacrifice. The fallback reads effective projected characteristics at this actual
     * production seam only; payment code never calls this method to reconstruct a historical
     * bucket. The source's card types ride along on the aggregate card-type counter (see
     * [sourceTag] for the battlefield / left-the-battlefield rule).
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
        val cardTypes = sourceId?.let { sourceTag(state, it, subtypes).cardTypes } ?: emptySet()
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
                    cardTypes = cardTypes,
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
            container.with(pool.withProvenance(ManaSourceTag(sourceId), amount))
        }
    }

    /**
     * Tag the [amount] restricted entries [sourceId] just appended to the player's pool, so
     * restricted mana ("spend this mana only to cast a creature spell") carries its provenance
     * into the payment like unrestricted mana does. [sourceSubtypes] is the production-time
     * subtype snapshot when the caller captured one (see [sourceTag]).
     */
    fun tagAddedRestrictedMana(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId?,
        amount: Int,
        sourceSubtypes: Set<Subtype>? = null,
    ): GameState {
        if (amount <= 0 || sourceId == null) return state
        val tag = sourceTag(state, sourceId, sourceSubtypes)
        return state.updateEntity(playerId) { container ->
            val pool = container.get<ManaPoolComponent>() ?: return@updateEntity container
            container.with(pool.withRestrictedProvenance(tag, amount))
        }
    }
}
