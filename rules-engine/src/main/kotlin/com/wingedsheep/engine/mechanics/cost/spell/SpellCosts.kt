package com.wingedsheep.engine.mechanics.cost.spell

import com.wingedsheep.engine.handlers.CostHandler
import com.wingedsheep.engine.legalactions.AdditionalCostData
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.costs.CostAtom
import kotlin.reflect.KClass

/**
 * The single dispatch point from a spell [AdditionalCost] to its [SpellCostKind], one function per
 * stage of the casting procedure. Callers never `when` over the cost vocabulary themselves.
 *
 * The registry is keyed by the dispatch leaf's class (the cost itself, or the atom an
 * [AdditionalCost.Atom] wraps) and `SpellCostKindCoverageTest` walks both sealed hierarchies, so a
 * new subtype without a kind fails the build instead of silently doing nothing.
 */
object SpellCosts {

    private val kinds: Map<KClass<*>, SpellCostKind<*>> = buildMap {
        // Shared atoms carried by AdditionalCost.Atom.
        put(CostAtom.Sacrifice::class, SacrificeCostKind)
        put(CostAtom.Discard::class, DiscardCostKind)
        put(CostAtom.DiscardHand::class, DiscardHandCostKind)
        put(CostAtom.SacrificeAll::class, SacrificeAllCostKind)
        put(CostAtom.ExileFrom::class, ExileFromCostKind)
        put(CostAtom.CollectEvidence::class, CollectEvidenceCostKind)
        put(CostAtom.ExileFromGraveyardForTotal::class, ExileFromGraveyardForTotalCostKind)
        put(CostAtom.TapPermanents::class, TapPermanentsCostKind)
        put(CostAtom.ReturnToHand::class, ReturnToHandCostKind)
        put(CostAtom.VariablePermanents::class, VariablePermanentsCostKind)
        put(CostAtom.RevealFromHand::class, RevealFromHandCostKind)
        put(CostAtom.PayPlayerCounters::class, PlayerCountersCostKind)
        put(CostAtom.RemoveCounters::class, RemoveCountersCostKind)
        put(CostAtom.PayLife::class, PayLifeCostKind)
        put(CostAtom.Mana::class, AbilityOnlyAtomCostKind)
        put(CostAtom.Mill::class, AbilityOnlyAtomCostKind)
        put(CostAtom.ExileTopOfLibrary::class, AbilityOnlyAtomCostKind)
        put(CostAtom.PutCountersOnSelf::class, AbilityOnlyAtomCostKind)
        put(CostAtom.PutCountersOnPermanent::class, AbilityOnlyAtomCostKind)
        put(CostAtom.RevealNotedCreatureType::class, AbilityOnlyAtomCostKind)
        put(CostAtom.Unattach::class, AbilityOnlyAtomCostKind)
        put(CostAtom.PutFromHandOnTopOfLibrary::class, AbilityOnlyAtomCostKind)

        // Casting-context-specific costs.
        put(AdditionalCost.PayLifePerTarget::class, PayLifePerTargetCostKind)
        put(AdditionalCost.PayXLife::class, PayXLifeCostKind)
        put(AdditionalCost.PayLifeEqualToManaValueOfSpell::class, PayLifeEqualToManaValueCostKind)
        put(AdditionalCost.ExileVariableCards::class, ExileVariableCardsCostKind)
        put(AdditionalCost.SacrificeCreaturesForCostReduction::class, SacrificeForCostReductionCostKind)
        put(AdditionalCost.Forage::class, ForageCostKind)
        put(AdditionalCost.BlightOrPay::class, BlightOrPayCostKind)
        put(AdditionalCost.BlightVariable::class, BlightVariableCostKind)
        put(AdditionalCost.Behold::class, BeholdCostKind)
        put(AdditionalCost.ExileFromStorage::class, ExileFromStorageCostKind)
        put(AdditionalCost.ChooseEntity::class, ChooseEntityCostKind)
        put(AdditionalCost.Choice::class, ChoiceCostKind)
        put(AdditionalCost.OrPay::class, OrPayCostKind)
        put(AdditionalCost.Composite::class, CompositeCostKind)
    }

    /** Every leaf class with a registered kind — read by the coverage test. */
    fun registeredLeafTypes(): Set<KClass<*>> = kinds.keys

    private fun leafOf(cost: AdditionalCost): Any = if (cost is AdditionalCost.Atom) cost.atom else cost

    @Suppress("UNCHECKED_CAST")
    private fun kindOf(leaf: Any): SpellCostKind<Any> =
        kinds[leaf::class] as SpellCostKind<Any>?
            ?: error("No SpellCostKind registered for ${leaf::class.qualifiedName} — register one in SpellCosts")

    private inline fun <R> dispatch(cost: AdditionalCost, block: (SpellCostKind<Any>, Any) -> R): R {
        val leaf = leafOf(cost)
        return block(kindOf(leaf), leaf)
    }

    // ---------------------------------------------------------------------------------------------
    // Stages
    // ---------------------------------------------------------------------------------------------

    fun canPay(state: GameState, cost: AdditionalCost, payerId: EntityId, costHandler: CostHandler): Boolean =
        dispatch(cost) { kind, leaf -> kind.canPay(state, payerId, leaf, costHandler) }

    fun enumerate(env: SpellCostEnumeration, cost: AdditionalCost, offer: SpellCostOffer): Boolean =
        dispatch(cost) { kind, leaf -> kind.enumerate(env, leaf, offer) }

    /**
     * Enumerates every cost in [costs] into one [SpellCostOffer] — composites flattened one level,
     * as a spell's printed list is — and reports whether all of them are payable. Every cost is
     * visited even after one fails, so the offer is complete either way.
     */
    fun enumerateAll(env: SpellCostEnumeration, costs: List<AdditionalCost>, offer: SpellCostOffer): Boolean {
        var payable = com.wingedsheep.engine.mechanics.cost.PlayerCounterPayment.canAffordSpell(env.state, env.playerId, costs)
        if (!canAffordLifeCosts(env, costs)) payable = false
        for (cost in costs.flatMap { if (it is AdditionalCost.Composite) it.steps else listOf(it) }) {
            if (!enumerate(env, cost, offer)) payable = false
        }
        return payable
    }

    /**
     * Every mandatory "pay N life" in [costs] (composites included) as one payment: each amount
     * resolved against the same pre-payment state, the cast card as their source, and the sum
     * affordable at once (CR 119.4). Two "pay 1 life" costs each fit a life total of 1, but together
     * they don't. A dynamic amount that can't be resolved fails closed. The leg of a cost-vs-cost
     * choice is an alternative, not a payment, so it isn't summed here.
     */
    private fun canAffordLifeCosts(env: SpellCostEnumeration, costs: List<AdditionalCost>): Boolean {
        val amounts = costs.flatMap { com.wingedsheep.engine.mechanics.cost.CostAmountResolver.payLifeAmounts(it) }
        if (amounts.isEmpty()) return true
        var total = 0
        for (amount in amounts) {
            val resolved = (amount as? com.wingedsheep.sdk.scripting.values.DynamicAmount.Fixed)?.amount
                ?: env.costUtils.resolvePayLifeCostTotal(
                    env.state, env.playerId, env.castCardId, AdditionalCost.Atom(CostAtom.PayLife(amount))
                )
                ?: return false
            if (resolved < 0 || resolved > Int.MAX_VALUE - total) return false
            total += resolved
        }
        return env.state.canPayLife(env.playerId, total)
    }

    fun candidates(env: SpellCostEnumeration, cost: AdditionalCost): List<EntityId> =
        dispatch(cost) { kind, leaf -> kind.candidates(env, leaf) }

    fun selectionCount(cost: AdditionalCost): Int =
        dispatch(cost) { kind, leaf -> kind.selectionCount(leaf) }

    fun canPayFrom(env: SpellCostEnumeration, cost: AdditionalCost, candidates: List<EntityId>): Boolean =
        dispatch(cost) { kind, leaf -> kind.canPayFrom(env, leaf, candidates) }

    fun present(env: SpellCostEnumeration, cost: AdditionalCost, candidates: List<EntityId>): Pair<String, AdditionalCostData>? =
        dispatch(cost) { kind, leaf -> kind.present(env, leaf, candidates) }

    fun selectionSupplied(cost: AdditionalCost, payment: AdditionalCostPayment?): Boolean =
        payment != null && dispatch(cost) { kind, leaf -> kind.selectionSupplied(leaf, payment) }

    fun validate(check: SpellCostCheck, cost: AdditionalCost): String? =
        dispatch(cost) { kind, leaf -> kind.validate(check, leaf) }

    fun lifeToPay(check: SpellCostCheck, cost: AdditionalCost): Int? =
        dispatch(cost) { kind, leaf -> kind.lifeToPay(check, leaf) }

    /**
     * The life every cost in [costs] takes, as one payment (CR 601.2h pays the total cost at once):
     * each amount read against the same pre-payment [SpellCostCheck.state]. Composites are flattened
     * the way [reduceAlternatives] flattens them; pass the reduced list, so a cost-vs-cost choice is
     * already the leg the caster took. Null — fail closed — when any amount can't be resolved, is
     * negative, or the sum overflows.
     */
    fun totalLifeToPay(check: SpellCostCheck, costs: List<AdditionalCost>): Int? {
        var total = 0
        for (cost in flattenComposites(costs)) {
            val amount = lifeToPay(check, cost) ?: return null
            if (amount < 0 || amount > Int.MAX_VALUE - total) return null
            total += amount
        }
        return total
    }

    /** Whether [cost] is a life payment — one of the costs [totalLifeToPay] sums. */
    fun takesLife(cost: AdditionalCost): Boolean =
        dispatch(cost) { kind, leaf -> kind.takesLife(leaf) }

    fun pay(ledger: SpellCostLedger, cost: AdditionalCost): String? =
        dispatch(cost) { kind, leaf -> kind.pay(ledger, leaf) }

    fun paysUnprompted(cost: AdditionalCost): Boolean =
        dispatch(cost) { kind, leaf -> kind.paysUnprompted(leaf) }

    // ---------------------------------------------------------------------------------------------
    // The cost tree: composites and the costs that offer the caster a choice of legs
    // ---------------------------------------------------------------------------------------------

    /** Reject forged branch identities before reduction can fall back to another cost. */
    fun validateChoiceDeclarations(
        costs: List<AdditionalCost>,
        choices: Map<com.wingedsheep.sdk.scripting.ChoiceSlot, Int>,
    ): String? {
        if (choices.isEmpty()) return null
        val declared = flattenComposites(costs).filterIsInstance<AdditionalCost.Choice>()
        for ((slot, index) in choices) {
            val choice = declared.singleOrNull { it.choiceSlot == slot }
                ?: return "Unknown or ambiguous additional-cost choice: $slot"
            if (index !in choice.options.indices) return "Invalid additional-cost branch: $index"
        }
        return null
    }

    /** Expand [AdditionalCost.Composite] wrappers so every cost in the list stands on its own. */
    fun flattenComposites(costs: List<AdditionalCost>): List<AdditionalCost> =
        costs.flatMap { if (it is AdditionalCost.Composite) flattenComposites(it.steps) else listOf(it) }

    /**
     * Reduce each cost that offers the caster a *choice of legs* to the leg they actually took, so
     * every downstream stage handles a plain cost with no alternatives awareness.
     *
     * Cost-vs-cost ([AdditionalCost.Choice]) reduces to the single option being paid:
     *  1. the explicitly declared branch for a named choice (validated before reduction); else
     *  2. the option whose [AdditionalCostPayment] field the client populated — a normal cast, where
     *     each option surfaced as its own legal action so exactly one field is filled; else
     *  3. the first option payable from the current board — server-initiated free/AI casts arrive with
     *     no payment (mirrors `ForageCostResolver`'s engine-direct fallback); else
     *  4. the first option (nothing payable — downstream validation/selection then rejects the cast).
     *
     * Cost-vs-mana ([AdditionalCost.OrPay]) reduces to its leg cost when the caster populated that
     * cost's payment field, and to *nothing* otherwise: declining the leg means they took the pay
     * path, whose only consequence — the extra mana — is already folded into the spell's cost by
     * [applyManaSurcharges]. Paying the leg then runs through that cost's ordinary kind, so the
     * or-pay shape needs no validation or payment code of its own.
     *
     * Composite steps are flattened on the way in and out, so an alternative nested inside a
     * composite is reduced (and priced) like a top-level one.
     */
    fun reduceAlternatives(
        costs: List<AdditionalCost>,
        state: GameState,
        playerId: EntityId,
        payment: AdditionalCostPayment?,
        costHandler: CostHandler,
        choices: Map<com.wingedsheep.sdk.scripting.ChoiceSlot, Int> = emptyMap(),
    ): List<AdditionalCost> = flattenComposites(costs).flatMap { cost ->
        when (cost) {
            is AdditionalCost.Choice -> listOf(
                cost.choiceSlot?.let { choices[it] }?.let { cost.options.getOrNull(it) }
                    ?: cost.options.firstOrNull { selectionSupplied(it, payment) }
                    ?: cost.options.firstOrNull { canPay(state, it, playerId, costHandler) }
                    ?: cost.options.first()
            )
            is AdditionalCost.OrPay ->
                if (selectionSupplied(cost.cost, payment)) listOf(cost.cost) else emptyList()
            else -> listOf(cost)
        }
    }.let(::flattenComposites)

    /**
     * Folds every surcharge the caster's payment incurs across [costs] into [cost] (CR 601.2f — the
     * total cost is locked in as the spell is cast). Composites are flattened the way
     * [reduceAlternatives] flattens them, so the two always agree on which costs they see.
     */
    fun applyManaSurcharges(cost: ManaCost, costs: List<AdditionalCost>, payment: AdditionalCostPayment?): ManaCost =
        flattenComposites(costs).fold(cost) { acc, additionalCost ->
            val surcharge = dispatch(additionalCost) { kind, leaf -> kind.manaSurcharge(leaf, payment) }
            if (surcharge == null) acc else acc + ManaCost.parse(surcharge)
        }
}
