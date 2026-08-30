package com.wingedsheep.engine.handlers.actions.spell

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.handlers.CostHandler
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.ExplicitPaymentPlanExecutor
import com.wingedsheep.engine.mechanics.mana.OrderedPaymentProgramExecutor
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidator
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCost
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.fromManaPool
import com.wingedsheep.engine.mechanics.mana.toManaPool
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.SpentManaProvenance
import com.wingedsheep.engine.mechanics.mana.isSatisfiedBy
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.sdk.scripting.effects.ManaSpellRider

/**
 * Result of a mana payment attempt.
 *
 * @property consumedRiders Every [ManaSpellRider] carried by the mana actually spent on this
 *   payment (from both restricted floating mana and freshly-tapped sources). The caller applies
 *   each rider to the spell as it goes on the stack — e.g.
 *   [ManaSpellRider.MakesSpellUncounterable] stamps `CantBeCounteredComponent`. A **list**, not a
 *   set: two rider-carrying mana spent on one spell must fire the rider twice (Pyromancer's
 *   Goggles copies the spell once per {R} spent), so identical riders must not be deduplicated.
 */
data class PaymentResult(
    val state: GameState,
    val events: List<GameEvent>,
    val error: String?,
    val consumedRiders: List<ManaSpellRider> = emptyList(),
    /**
     * Provenance of the mana actually spent on this payment — which producing-source subtypes and
     * which producing sources contributed (see [SpentManaProvenance]). Combines mana pulled from the
     * floating pool (tags snapshotted at production) with mana freshly tapped by the solver during
     * this payment. The cast handler propagates it onto the engine
     * [com.wingedsheep.engine.core.SpellCastEvent] and the resolving permanent's
     * [com.wingedsheep.engine.state.components.battlefield.CastRecordComponent], driving
     * `SpellCastPredicate.PaidWithManaFromSubtype` / `PaidWithManaFromSource` triggers and the
     * `DynamicAmount.ManaSpentFromSubtype` count (Bat Colony). Treasure is just
     * `spentManaProvenance.bySubtype[Subtype.TREASURE]` (Alchemist's Talent level 3).
     */
    val spentManaProvenance: SpentManaProvenance = SpentManaProvenance(),
    /**
     * For a color-restricted `{X}` cost ("spend only [colors] on X"), the per-color
     * breakdown of mana spent on the X portion. The cast handler stores this on the spell's
     * stack object so it can be read at resolution via `DynamicAmount.ManaSpentOnX`
     * (e.g. Soul Burn's "gain life equal to the {B} spent on X"). Empty when X is unrestricted.
     */
    val xManaSpentByColor: Map<Color, Int> = emptyMap()
)

/**
 * Processes mana payment for spell casting using one of three strategies:
 * AutoPay (solver taps lands), FromPool (use floating mana), or Explicit (specific sources).
 */
class CastPaymentProcessor(
    private val manaSolver: ManaSolver,
    private val costHandler: CostHandler,
    private val manaAbilitySideEffectExecutor: ManaAbilitySideEffectExecutor
) {
    private val paymentPlanValidator = PaymentPlanValidator(manaSolver)
    private val explicitPaymentPlanExecutor = ExplicitPaymentPlanExecutor(
        manaSolver = manaSolver,
        manaAbilitySideEffectExecutor = manaAbilitySideEffectExecutor,
    )
    private val orderedPaymentProgramExecutor = OrderedPaymentProgramExecutor(
        manaSolver = manaSolver,
        manaAbilitySideEffectExecutor = manaAbilitySideEffectExecutor,
    )

    /**
     * Provenance of mana freshly tapped by the solver during a payment (AutoPay / Explicit). The
     * snapshot is carried from the actual production transition through [ManaProduction]; this
     * method never rereads a source's current card state after a tap or sacrifice.
     */
    private fun tappedSourceProvenance(state: GameState, manaProduced: Map<EntityId, com.wingedsheep.engine.mechanics.mana.ManaProduction>): SpentManaProvenance {
        if (manaProduced.isEmpty()) return SpentManaProvenance()
        val bySubtype = mutableMapOf<com.wingedsheep.sdk.core.Subtype, Int>()
        val sourceIds = mutableSetOf<EntityId>()
        for ((sourceId, production) in manaProduced) {
            val amount = production.amount + production.colorless
            if (amount <= 0) continue
            sourceIds.add(sourceId)
            val subtypes = production.sourceSubtypes.orEmpty()
            for (subtype in subtypes) bySubtype[subtype] = (bySubtype[subtype] ?: 0) + amount
        }
        return SpentManaProvenance(bySubtype, sourceIds)
    }

    /** Merge two provenance snapshots (summing subtype counts, unioning source ids). */
    private fun mergeProvenance(a: SpentManaProvenance, b: SpentManaProvenance): SpentManaProvenance {
        if (a.isEmpty) return b
        if (b.isEmpty) return a
        val bySubtype = a.bySubtype.toMutableMap()
        for ((subtype, count) in b.bySubtype) bySubtype[subtype] = (bySubtype[subtype] ?: 0) + count
        return SpentManaProvenance(bySubtype, a.sourceIds + b.sourceIds)
    }

    fun processPayment(
        state: GameState,
        action: com.wingedsheep.engine.core.CastSpell,
        effectiveCost: ManaCost,
        cardName: String,
        xValue: Int,
        spellContext: SpellPaymentContext? = null,
        xManaRestriction: Set<Color> = emptySet()
    ): PaymentResult {
        return when (action.paymentStrategy) {
            is PaymentStrategy.FromPool -> payFromPool(state, action.playerId, effectiveCost, cardName, xValue, spellContext, xManaRestriction)
            is PaymentStrategy.AutoPay -> autoPay(state, action.playerId, effectiveCost, cardName, xValue, spellContext, xManaRestriction = xManaRestriction)
            is PaymentStrategy.Explicit -> action.paymentStrategy.paymentPlan?.let { plan ->
                explicitPlanPay(
                    state = state,
                    playerId = action.playerId,
                    plan = plan,
                    cost = effectiveCost,
                    cardName = cardName,
                    spellContext = spellContext,
                )
            } ?: explicitPay(
                state,
                action.playerId,
                action.paymentStrategy,
                effectiveCost,
                cardName,
                xValue,
                spellContext,
                xManaRestriction
            )
            is PaymentStrategy.ExplicitV2 -> action.paymentStrategy.paymentPlan?.let { plan ->
                spellContext?.let { context ->
                    explicitPlanV2Pay(
                        state = state,
                        playerId = action.playerId,
                        plan = plan,
                        cost = effectiveCost,
                        cardName = cardName,
                        spellContext = context,
                    )
                } ?: PaymentResult(
                    state = state,
                    events = emptyList(),
                    error = "PaymentStrategy.ExplicitV2 requires SpellPaymentContext",
                )
            } ?: PaymentResult(
                state = state,
                events = emptyList(),
                error = "PaymentStrategy.ExplicitV2 requires PaymentPlanV2",
            )
            is PaymentStrategy.ExplicitV3 -> action.paymentStrategy.paymentPlan?.let { plan ->
                spellContext?.let { context ->
                    explicitPlanV3Pay(
                        state = state,
                        playerId = action.playerId,
                        plan = plan,
                        cost = effectiveCost,
                        cardName = cardName,
                        paymentContext = context,
                    )
                } ?: PaymentResult(
                    state = state,
                    events = emptyList(),
                    error = "PaymentStrategy.ExplicitV3 requires SpellPaymentContext",
                )
            } ?: PaymentResult(
                state = state,
                events = emptyList(),
                error = "PaymentStrategy.ExplicitV3 requires PaymentPlanV3",
            )
        }
    }

    /**
     * Materialize a complete external PaymentPlanV1 without choosing a different payment.
     *
     * This path deliberately has no fallback to [autoPay], [payFromPool], or the legacy
     * source-ID-only explicit path. The validator has already checked every source production and
     * cost allocation, so the only remaining engine work is to consume the submitted pool units,
     * run the selected mana-ability side effects, and emit the normal cast payment event.
     */
    private fun explicitPlanPay(
        state: GameState,
        playerId: EntityId,
        plan: PaymentPlanV1,
        cost: ManaCost,
        cardName: String,
        spellContext: SpellPaymentContext?,
    ): PaymentResult {
        val validation = paymentPlanValidator.validate(
            state = state,
            playerId = playerId,
            cost = cost.canonicalPaymentManaCost(),
            plan = plan,
            spellContext = spellContext,
        )
        val accepted = validation as? PaymentPlanValidation.Accepted
            ?: return PaymentResult(
                state = state,
                events = emptyList(),
                error = (validation as PaymentPlanValidation.Rejected).reason,
            )

        return finishExplicitPlanPayment(
            state = state,
            playerId = playerId,
            accepted = accepted,
            cardName = cardName,
            errorLabel = "PaymentPlanV1 source activation failed",
        )
    }

    private fun explicitPlanV2Pay(
        state: GameState,
        playerId: EntityId,
        plan: PaymentPlanV2,
        cost: ManaCost,
        cardName: String,
        spellContext: SpellPaymentContext,
    ): PaymentResult {
        val execution = explicitPaymentPlanExecutor.executeV2(
            state = state,
            playerId = playerId,
            cost = cost.canonicalPaymentManaCost(),
            plan = plan,
            paymentContext = spellContext,
            reason = "Cast $cardName",
        )
        return PaymentResult(
            state = execution.state,
            events = execution.events,
            error = execution.error,
            spentManaProvenance = execution.spentManaProvenance,
        )
    }

    private fun explicitPlanV3Pay(
        state: GameState,
        playerId: EntityId,
        plan: com.wingedsheep.engine.core.PaymentPlanV3,
        cost: ManaCost,
        cardName: String,
        paymentContext: SpellPaymentContext,
    ): PaymentResult {
        val execution = orderedPaymentProgramExecutor.executeV3(
            state = state,
            playerId = playerId,
            cost = cost.canonicalPaymentManaCost(),
            plan = plan,
            paymentContext = paymentContext,
            reason = "Cast $cardName",
        )
        return PaymentResult(
            state = execution.state,
            events = execution.events,
            error = execution.error,
            spentManaProvenance = execution.spentManaProvenance,
        )
    }

    private fun finishExplicitPlanPayment(
        state: GameState,
        playerId: EntityId,
        accepted: PaymentPlanValidation.Accepted,
        cardName: String,
        errorLabel: String,
    ): PaymentResult {
        var currentState = state.updateEntity(playerId) { container ->
            container.with(fromManaPool(accepted.materialization.poolAfterFloatingSpend))
        }
        val events = mutableListOf<GameEvent>()

        var spentProvenance = accepted.materialization.spentManaProvenance
        if (accepted.materialization.sourcePayments.isNotEmpty()) {
            val sideEffectResult = manaAbilitySideEffectExecutor.tapSourcesWithSideEffects(
                state = currentState,
                solution = accepted.solution,
                controllerId = playerId,
            )
            if (!sideEffectResult.success) {
                return PaymentResult(state, events, errorLabel)
            }
            currentState = sideEffectResult.state
            events.addAll(sideEffectResult.events)

            // The selected source abilities have now actually produced mana. Only at this seam do
            // the solver-carried subtype snapshots become authoritative: consumed outputs
            // contribute to SpentManaProvenance, while unspent fixed outputs enter the pool as
            // exact Rules-owned joint buckets. No current CardComponent is consulted here.
            currentState = currentState.updateEntity(playerId) { container ->
                container.with(
                    fromManaPool(
                        accepted.materialization.poolAfterSuccessfulSourceProduction(playerId)
                    )
                )
            }
        }

        val spent = accepted.materialization.manaSpent

        events.add(
            ManaSpentEvent(
                playerId = playerId,
                reason = "Cast $cardName",
                white = spent.white,
                blue = spent.blue,
                black = spent.black,
                red = spent.red,
                green = spent.green,
                colorless = spent.colorless,
            )
        )
        return PaymentResult(
            state = currentState,
            events = events,
            error = null,
            spentManaProvenance = spentProvenance,
        )
    }

    private fun payFromPool(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        cardName: String,
        xValue: Int,
        spellContext: SpellPaymentContext? = null,
        xManaRestriction: Set<Color> = emptySet()
    ): PaymentResult {
        val poolComponent = state.getEntity(playerId)?.get<ManaPoolComponent>()
            ?: ManaPoolComponent()
        val pool = poolComponent.toManaPool()

        // Pay base cost first
        var poolAfterPayment = costHandler.payManaCost(pool, cost, spellContext)
            ?: return PaymentResult(state, emptyList(), "Insufficient mana in pool")

        // Track mana spent for the event (unrestricted only — restricted changes tracked by count difference)
        val unrestrictedBefore = ManaPool(poolComponent.white, poolComponent.blue, poolComponent.black, poolComponent.red, poolComponent.green, poolComponent.colorless)
        val unrestrictedAfter = ManaPool(poolAfterPayment.white, poolAfterPayment.blue, poolAfterPayment.black, poolAfterPayment.red, poolAfterPayment.green, poolAfterPayment.colorless)
        val restrictedSpent = poolComponent.restrictedMana.size - poolAfterPayment.restrictedMana.size

        var whiteSpent = poolComponent.white - poolAfterPayment.white
        var blueSpent = poolComponent.blue - poolAfterPayment.blue
        var blackSpent = poolComponent.black - poolAfterPayment.black
        var redSpent = poolComponent.red - poolAfterPayment.red
        var greenSpent = poolComponent.green - poolAfterPayment.green
        var colorlessSpent = poolComponent.colorless - poolAfterPayment.colorless

        // Count restricted mana spent by color for tracking
        val restrictedSpentByColor = countRestrictedSpentByColor(poolComponent.restrictedMana, poolAfterPayment.restrictedMana)
        whiteSpent += restrictedSpentByColor.getOrDefault(Color.WHITE, 0)
        blueSpent += restrictedSpentByColor.getOrDefault(Color.BLUE, 0)
        blackSpent += restrictedSpentByColor.getOrDefault(Color.BLACK, 0)
        redSpent += restrictedSpentByColor.getOrDefault(Color.RED, 0)
        greenSpent += restrictedSpentByColor.getOrDefault(Color.GREEN, 0)
        colorlessSpent += restrictedSpentByColor.getOrDefault(null, 0)

        // Pay for X from remaining pool (multiply by X symbol count for XX costs)
        val xSymbolCount = cost.xCount.coerceAtLeast(1)
        var xRemainingToPay = xValue * xSymbolCount
        // Per-color mana spent on the X portion (for DynamicAmount.ManaSpentOnX).
        val xSpentByColor = mutableMapOf<Color, Int>()
        // When X is color-restricted, only these colors may pay it (and colorless can't).
        val xColorsAllowed: Set<Color> =
            if (xManaRestriction.isEmpty()) Color.entries.toSet() else xManaRestriction

        // Spend eligible restricted mana for X first
        if (spellContext != null) {
            for (entry in poolAfterPayment.restrictedMana.toList()) {
                if (xRemainingToPay <= 0) break
                // A color-restricted X can't be paid with off-color or colorless restricted mana.
                if (entry.color != null && entry.color !in xColorsAllowed) continue
                if (entry.color == null && xManaRestriction.isNotEmpty()) continue
                if (entry.restriction.isSatisfiedBy(spellContext)) {
                    val spent = poolAfterPayment.spendRestricted(entry.color, spellContext)
                    if (spent != null) {
                        poolAfterPayment = spent
                        if (entry.color != null) {
                            when (entry.color) {
                                Color.WHITE -> whiteSpent++
                                Color.BLUE -> blueSpent++
                                Color.BLACK -> blackSpent++
                                Color.RED -> redSpent++
                                Color.GREEN -> greenSpent++
                            }
                            xSpentByColor[entry.color] = (xSpentByColor[entry.color] ?: 0) + 1
                        } else colorlessSpent++
                        xRemainingToPay--
                    }
                }
            }
        }

        // Spend unrestricted floating mana for the remaining X: colorless first (unless X is
        // color-restricted), then allowed colors. Same coverage rule as autoTapForManaCost.
        for (unit in poolAfterPayment.xCoveragePlan(xRemainingToPay, xManaRestriction)) {
            poolAfterPayment = if (unit == null) {
                colorlessSpent++
                poolAfterPayment.spendColorless()!!
            } else {
                when (unit) {
                    Color.WHITE -> whiteSpent++
                    Color.BLUE -> blueSpent++
                    Color.BLACK -> blackSpent++
                    Color.RED -> redSpent++
                    Color.GREEN -> greenSpent++
                }
                xSpentByColor[unit] = (xSpentByColor[unit] ?: 0) + 1
                poolAfterPayment.spend(unit)!!
            }
            xRemainingToPay--
        }

        // Check if we could pay for all of X
        if (xRemainingToPay > 0) {
            return PaymentResult(state, emptyList(), "Insufficient mana in pool for X cost")
        }

        // Consume provenance tags proportional to unrestricted mana pulled from the pool.
        // Restricted mana doesn't participate (tagged mana is always unrestricted). Everything is
        // paid from the pool here, so there is no freshly-tapped-source provenance to add.
        val unrestrictedSpent = (whiteSpent + blueSpent + blackSpent + redSpent + greenSpent + colorlessSpent) - restrictedSpent
        val (provenancePool, spentProvenance) = pool.consumeProvenance(maxOf(0, unrestrictedSpent))
        val poolWithProvenanceUpdated = poolAfterPayment.withProvenanceFrom(provenancePool)

        val newState = state.updateEntity(playerId) { container ->
            container.with(fromManaPool(poolWithProvenanceUpdated))
        }

        val event = ManaSpentEvent(
            playerId = playerId,
            reason = "Cast $cardName",
            white = whiteSpent,
            blue = blueSpent,
            black = blackSpent,
            red = redSpent,
            green = greenSpent,
            colorless = colorlessSpent
        )

        val consumedRiders = ridersConsumedDuringPayment(poolComponent.restrictedMana, poolAfterPayment.restrictedMana)
        return PaymentResult(
            newState,
            listOf(event),
            null,
            consumedRiders,
            spentManaProvenance = spentProvenance,
            xManaSpentByColor = xSpentByColor
        )
    }

    private fun autoPay(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        cardName: String,
        xValue: Int,
        spellContext: SpellPaymentContext? = null,
        excludeSources: Set<EntityId> = emptySet(),
        xManaRestriction: Set<Color> = emptySet()
    ): PaymentResult {
        val poolComponent = state.getEntity(playerId)?.get<ManaPoolComponent>()
            ?: ManaPoolComponent()
        val pool = poolComponent.toManaPool()
        val xSymbolCount = cost.xCount.coerceAtLeast(1)
        val solution = manaSolver.solve(
            state = state,
            playerId = playerId,
            cost = cost,
            xValue = xValue * xSymbolCount,
            excludeSources = excludeSources,
            spellContext = spellContext,
            xManaRestriction = xManaRestriction,
            initialManaPool = pool,
        ) ?: return PaymentResult(state, emptyList(), "Not enough mana to auto-pay")

        // The solver has already reserved both inner activation costs and the outer cost on one
        // shared ledger. Execute all selected abilities only after that complete preflight succeeds;
        // a failed side effect returns the untouched input state.
        val tapResult = manaAbilitySideEffectExecutor
            .tapSourcesWithSideEffects(state, solution, playerId)
        if (!tapResult.success) {
            return PaymentResult(state, emptyList(), "Unable to pay mana ability side effect")
        }
        var currentState = tapResult.state
        val events = tapResult.events.toMutableList()

        // `poolAfterPayment` contains the initial pool after both activation-cost and outer-cost
        // consumption. Source output is consumed logically by the solver; only excess bonus mana
        // remains to be floated back to the player.
        var poolAfterPayment = solution.poolAfterPayment ?:
            return PaymentResult(state, emptyList(), "Mana solver did not return pool accounting")

        // The solver's aggregate outer-spend counters include restricted mana for the
        // ManaSpentEvent, but restricted units must never consume the producing-source provenance
        // of ordinary floating mana. Normalize the initial pool in two ledger stages so inner
        // activation-cost spends are removed before the outer-spend provenance is materialized.
        val poolAfterActivation = solution.poolAfterActivation ?: pool
        val innerUnrestrictedSpent = (
            pool.unrestrictedTotal - poolAfterActivation.unrestrictedTotal
            ).coerceAtLeast(0)
        val (poolProvenanceAfterActivation, _) = pool.consumeProvenance(innerUnrestrictedSpent)
        val outerUnrestrictedSpent = (
            poolAfterActivation.unrestrictedTotal - poolAfterPayment.unrestrictedTotal
            ).coerceAtLeast(0)
        val (poolProvenanceAfterPayment, poolProvenance) =
            poolProvenanceAfterActivation.consumeProvenance(outerUnrestrictedSpent)
        poolAfterPayment = poolAfterPayment.withProvenanceFrom(poolProvenanceAfterPayment)

        for (entry in solution.remainingBonusMana) {
            poolAfterPayment = when {
                entry.colorless && entry.restriction != null ->
                    poolAfterPayment.addRestricted(null, entry.amount, entry.restriction)
                entry.colorless -> poolAfterPayment.addColorless(entry.amount)
                entry.restriction != null ->
                    poolAfterPayment.addRestricted(entry.color, entry.amount, entry.restriction)
                else -> poolAfterPayment.add(entry.color, entry.amount)
            }
        }
        currentState = currentState.updateEntity(playerId) { container ->
            container.with(fromManaPool(poolAfterPayment))
        }

        var whiteSpent = solution.poolManaSpentForOuter.white
        var blueSpent = solution.poolManaSpentForOuter.blue
        var blackSpent = solution.poolManaSpentForOuter.black
        var redSpent = solution.poolManaSpentForOuter.red
        var greenSpent = solution.poolManaSpentForOuter.green
        var colorlessSpent = solution.poolManaSpentForOuter.colorless
        for (production in solution.manaProduced.values) {
            when (production.color) {
                Color.WHITE -> whiteSpent += production.amount
                Color.BLUE -> blueSpent += production.amount
                Color.BLACK -> blackSpent += production.amount
                Color.RED -> redSpent += production.amount
                Color.GREEN -> greenSpent += production.amount
                null -> colorlessSpent += production.colorless
            }
        }
        for ((color, amount) in solution.bonusManaSpentByColor) {
            when (color) {
                Color.WHITE -> whiteSpent += amount
                Color.BLUE -> blueSpent += amount
                Color.BLACK -> blackSpent += amount
                Color.RED -> redSpent += amount
                Color.GREEN -> greenSpent += amount
            }
        }

        val spentProvenance = mergeProvenance(
            poolProvenance,
            tappedSourceProvenance(state, solution.manaProduced),
        )
        val xSpentByColor = solution.xRestrictedManaSpent

        events.add(
            ManaSpentEvent(
                playerId = playerId,
                reason = "Cast $cardName",
                white = whiteSpent,
                blue = blueSpent,
                black = blackSpent,
                red = redSpent,
                green = greenSpent,
                colorless = colorlessSpent,
            )
        )

        val poolBeforeOuterPayment = solution.poolAfterActivation ?: pool
        val consumedRiders = ridersConsumedDuringPayment(
            poolBeforeOuterPayment.restrictedMana,
            solution.poolAfterPayment.restrictedMana,
        ) + solution.consumedRiders
        return PaymentResult(
            state = currentState,
            events = events,
            error = null,
            consumedRiders = consumedRiders,
            spentManaProvenance = spentProvenance,
            xManaSpentByColor = xSpentByColor,
        )
    }

    /**
     * Pay a spell's mana cost using only the player-chosen sources as candidates.
     *
     * The client's mana selection UI can over-specify sources — for example, when a
     * spell with convoke reduces its cost after creatures are tapped, the pre-cast
     * auto-tap preview (computed against the full cost) over-selects lands. Rather
     * than tapping every chosen source unconditionally, we delegate to the mana
     * solver with the non-chosen sources excluded, so only the minimum subset
     * actually needed to cover the (already cost-reduced) payment gets tapped.
     *
     * Validation (`CastSpellHandler.validatePayment`) already uses the same solver
     * call with the same exclusion — execution matching validation ensures we never
     * tap lands that weren't required.
     */
    private fun explicitPay(
        state: GameState,
        playerId: EntityId,
        strategy: PaymentStrategy.Explicit,
        cost: ManaCost,
        cardName: String,
        xValue: Int,
        spellContext: SpellPaymentContext? = null,
        xManaRestriction: Set<Color> = emptySet()
    ): PaymentResult {
        val chosenSet = strategy.manaAbilitiesToActivate.toSet()
        val excluded = manaSolver.findAvailableManaSources(state, playerId)
            .map { it.entityId }
            .filter { it !in chosenSet }
            .toSet()
        return autoPay(state, playerId, cost, cardName, xValue, spellContext, excluded, xManaRestriction)
    }

    /**
     * Count restricted mana spent by color by comparing before/after restricted mana lists.
     */
    private fun countRestrictedSpentByColor(
        before: List<RestrictedManaEntry>,
        after: List<RestrictedManaEntry>
    ): Map<Color?, Int> {
        val beforeCounts = before.groupingBy { it.color }.eachCount()
        val afterCounts = after.groupingBy { it.color }.eachCount()
        return beforeCounts.mapValues { (color, count) ->
            count - (afterCounts[color] ?: 0)
        }.filter { it.value > 0 }
    }

    /**
     * Every [ManaSpellRider] carried by restricted mana entries that disappeared during payment
     * (present in [before], gone from [after] after multiset subtraction). Used to detect that
     * e.g. Cavern of Souls' floating restricted mana was spent on the cast. Multiplicity is
     * preserved — two spent entries carrying the same rider yield it twice (Pyromancer's
     * Goggles), so this returns a list rather than a set.
     */
    private fun ridersConsumedDuringPayment(
        before: List<RestrictedManaEntry>,
        after: List<RestrictedManaEntry>
    ): List<ManaSpellRider> {
        val remaining = after.toMutableList()
        val consumed = mutableListOf<ManaSpellRider>()
        for (entry in before) {
            val idx = remaining.indexOfFirst { it == entry }
            if (idx >= 0) {
                remaining.removeAt(idx)
            } else if (entry.riders.isNotEmpty()) {
                consumed.addAll(entry.riders)
            }
        }
        return consumed
    }
}
