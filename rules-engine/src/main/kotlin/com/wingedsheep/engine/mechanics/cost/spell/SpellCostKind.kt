package com.wingedsheep.engine.mechanics.cost.spell

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.handlers.CostHandler
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.legalactions.AdditionalCostData
import com.wingedsheep.engine.legalactions.EnumerationContext
import com.wingedsheep.engine.legalactions.utils.CostEnumerationUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.captureEntitySnapshots
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.costs.CostAtom

/**
 * One kind of spell additional cost (CR 601.2b, 601.2f–h) — everything the engine knows about paying
 * it, in one place.
 *
 * Before this existed, a new [AdditionalCost] subtype was dispatched by hand in some thirty places
 * across `CastSpellHandler`, `CastSpellEnumerator`, `CostHandler` and `SelectionCostPresentation`,
 * each a `when` that had to be found and extended, several ending in `else -> {}` so a missed one
 * compiled and silently did nothing. Now each kind is one object and [SpellCosts] is the single
 * dispatch point per stage; `SpellCostKindCoverageTest` fails the build when a subtype has no kind.
 *
 * The type parameter is the dispatch *leaf*: the [AdditionalCost] subtype itself, or — for
 * [AdditionalCost.Atom] — the wrapped [CostAtom], since the shared atoms are what actually differ.
 *
 * The hooks follow the casting procedure. Every one has a default, because most kinds take part in
 * only a few stages; a default is always the "this kind has nothing to say here" answer.
 */
interface SpellCostKind<in C : Any> {

    /**
     * Whether [payerId] could pay [cost] at all, with no cast in hand (CR 118.3). The gate for the
     * cost-vs-cost fallback, escalate, and the alternative-cost rails.
     */
    fun canPay(state: GameState, payerId: EntityId, cost: C, costHandler: CostHandler): Boolean

    /**
     * Legal-action enumeration for a spell's own additional costs: record the candidates this cost
     * contributes to the cast's picker in [offer], and report whether it is payable. False removes
     * the cast from the legal actions.
     */
    fun enumerate(env: SpellCostEnumeration, cost: C, offer: SpellCostOffer): Boolean = true

    /**
     * The objects the caster could pick to pay [cost] when it is presented on its own (the non-mana
     * leg of an or-pay cost, an alternative cost's non-mana half). Empty for a cost with no
     * selection; callers treat that as "not payable this way".
     */
    fun candidates(env: SpellCostEnumeration, cost: C): List<EntityId> = emptyList()

    /** How many objects [candidates] must supply, 0 for a cost that takes no counted selection. */
    fun selectionCount(cost: C): Int = 0

    /** Whether [cost], presented on its own, could be paid from [candidates]. */
    fun canPayFrom(env: SpellCostEnumeration, cost: C, candidates: List<EntityId>): Boolean {
        val required = selectionCount(cost)
        return required == 0 || candidates.size >= required
    }

    /**
     * The action label and client picker payload for paying [cost] on its own from [candidates], or
     * null when no picker covers it.
     */
    fun present(env: SpellCostEnumeration, cost: C, candidates: List<EntityId>): Pair<String, AdditionalCostData>? = null

    /**
     * True when [payment] carries a selection in the field this cost consumes — the signal that the
     * caster paid this cost rather than an alternative offered alongside it (cost-vs-cost choices
     * and or-pay legs are reduced by it).
     */
    fun selectionSupplied(cost: C, payment: AdditionalCostPayment): Boolean = false

    /**
     * Mana this cost folds into the spell's total cost (CR 601.2f) for the payment the caster made,
     * as a mana-cost string — the declined leg of an "… or pay {N}" cost. Null for nothing.
     */
    fun manaSurcharge(cost: C, payment: AdditionalCostPayment?): String? = null

    /** Rejects an illegal submitted payment for [cost] (CR 601.2h); null when it is legal. */
    fun validate(check: SpellCostCheck, cost: C): String? = null

    /**
     * Life this cost takes, paid up front before any selection cost, whether or not the client
     * submitted a payment object — the amount is fixed by the cast, so there is nothing to choose.
     * Null when the amount can't be resolved (a dynamic amount with no answer, a negative amount, or
     * an overflow) — the cast then fails closed rather than paying a guessed amount. Every life cost
     * of one cast is resolved against the same pre-payment state and paid as one total
     * ([SpellCosts.totalLifeToPay]).
     */
    fun lifeToPay(check: SpellCostCheck, cost: C): Int? = 0

    /**
     * True for a cost that *is* a life payment ("pay 2 life", "pay X life"), as opposed to one that
     * merely takes none. Lets validation check several life costs as one total (CR 119.4) while a
     * lone dedicated life shape keeps its own, more specific rejection.
     */
    fun takesLife(cost: C): Boolean = false

    /**
     * True for a cost that selects nothing — every object it names goes ("discard your hand",
     * "sacrifice all creatures you control") — so [pay] runs whether or not the caster submitted a
     * payment object. A cast with no other selection cost arrives with none at all.
     */
    fun paysUnprompted(cost: C): Boolean = false

    /**
     * Pays [cost] from the caster's submitted selection. Only called when a payment was submitted
     * ([CastSpell.additionalCostPayment] is non-null), or always when [paysUnprompted]. Returns an
     * error to abort the cast.
     */
    fun pay(ledger: SpellCostLedger, cost: C): String? = null
}

/** The cast being validated: the submitted action against the state it was submitted in. */
class SpellCostCheck(
    val state: GameState,
    val action: CastSpell,
    val costHandler: CostHandler,
    val predicateEvaluator: PredicateEvaluator,
    /**
     * The engine's card definitions, for cost amounts that read them — a commander-dependent life
     * cost ("pay life equal to the number of colors in your commanders' color identity") fails
     * closed without one. Defaults to the registry [costHandler] plays with.
     */
    val cardRegistry: CardRegistry? = costHandler.cardRegistry,
) {
    val playerId: EntityId get() = action.playerId
    val payment: AdditionalCostPayment? get() = action.additionalCostPayment
}

/** Where a spell's additional costs are enumerated: the payer, the card being cast, and the finders. */
class SpellCostEnumeration(
    val state: GameState,
    val playerId: EntityId,
    /** The spell being cast — on its way to the stack, so never part of its own cost's pool. */
    val castCardId: EntityId,
    val costUtils: CostEnumerationUtils,
    val predicateEvaluator: PredicateEvaluator,
) {
    constructor(context: EnumerationContext, castCardId: EntityId) :
        this(context.state, context.playerId, castCardId, context.costUtils, context.predicateEvaluator)
}

/**
 * The combined picker a spell's own additional costs build during enumeration. Each kind writes the
 * candidate pool it contributes; the enumerator turns the result into one `AdditionalCostData`.
 */
class SpellCostOffer {
    val sacrificeTargets = mutableListOf<EntityId>()
    var variableSacrificeTargets = emptyList<EntityId>()
    var variableSacrificeReduction = 0
    var variablePermanentsCost: CostAtom.VariablePermanents? = null
    var variablePermanentsTargets = emptyList<EntityId>()
    var exileTargets = emptyList<EntityId>()
    var exileMinCount = 0
    var collectEvidenceCost: CostAtom.CollectEvidence? = null
    var discardTargets = emptyList<EntityId>()
    var discardCount = 0
    var bounceTargets = emptyList<EntityId>()
    var bounceCount = 0
    var tapTargets = emptyList<EntityId>()
    var tapCount = 0
    var beholdTargets = emptyList<EntityId>()
    var beholdCount = 0
    var revealTargets = emptyList<EntityId>()
    var revealCount = 0
    var blightOrPayCost: AdditionalCost.BlightOrPay? = null
    var blightCreatures = emptyList<EntityId>()
    var blightVariableCost: AdditionalCost.BlightVariable? = null
    var blightVariableCreatures = emptyList<EntityId>()
    var blightVariableMaxX = 0
    var payXLifeCost: AdditionalCost.PayXLife? = null
    var payXLifeMaxX = 0
    var orPayCost: AdditionalCost.OrPay? = null
    var orPayTargets = emptyList<EntityId>()
}

/**
 * The running account of a cast's additional-cost payment (CR 601.2h): the state as each cost is
 * paid, the events it emitted, and what the paid costs leave behind for the spell on the stack —
 * LKI snapshots, the cards discarded / exiled / chosen, and the pipeline collections a later cost
 * or the resolving effect reads by name.
 *
 * A local accumulator for one `execute` call, never part of game state.
 */
class SpellCostLedger(
    var state: GameState,
    val action: CastSpell,
    /** The cast card's name — the source of reveals made while paying. */
    val castCardName: String,
    /** The cast card's definition name, when it has one — the source named by collect evidence. */
    val cardDefinitionName: String?,
    val cardRegistry: CardRegistry,
    /** Moves the cards the costs discard, sacrifice, exile or bounce. */
    val zones: ZoneTransitionService,
    /** Finds the permanents a cost names without a selection (sacrifice all …). */
    val costHandler: CostHandler,
    /**
     * The costs the caster's declared optional ability (kicker, teamwork, …) contributed, reduced
     * the same way as the full list — what names a tap's cause for "tapped to pay a teamwork cost".
     */
    val declaredSlotCosts: List<AdditionalCost>,
    val events: MutableList<GameEvent> = mutableListOf(),
) {
    val playerId: EntityId get() = action.playerId

    /** The submitted payment; [SpellCostKind.pay] is only called when there is one. */
    val payment: AdditionalCostPayment get() = action.additionalCostPayment ?: AdditionalCostPayment.NONE

    val sacrificedSnapshots = mutableListOf<EntitySnapshot>()
    var exiledCardCount = 0
    val beheldCards = mutableListOf<EntityId>()

    /** Creatures tapped for convoke, with their entry stamps (CR 702.51c) — `SpellOnStackComponent.convokedCreatures`. */
    val convokedCreatures = linkedMapOf<EntityId, Long>()

    /** Discarded to pay — read at resolution as `EffectTarget.DiscardedAsCost`. */
    val discardedAsCostCards = mutableListOf<EntityId>()

    /**
     * Exiled to pay — named at resolution by `CardSource.ExiledAsCost`. Snapshots are only taken for
     * battlefield exiles (see `SpellOnStackComponent.exiledAsCostSnapshots`).
     */
    val exiledAsCostCards = mutableListOf<EntityId>()
    val exiledAsCostSnapshots = mutableListOf<EntitySnapshot>()

    /**
     * LKI snapshots for entities chosen by [AdditionalCost.ChooseEntity] with `captureSnapshot`, so
     * "power as it last existed on the battlefield" still reads if the entity leaves before
     * resolution (CR 113.7a).
     */
    val chosenEntitySnapshots = mutableListOf<EntitySnapshot>()

    /** Pipeline storage populated by Behold / ChooseEntity, consumed by ExileFromStorage. */
    val costPipelineCollections = mutableMapOf<String, List<EntityId>>()

    /**
     * Sacrifices [permId] as a cost: the sacrifice record (for "whenever you sacrifice"), the
     * sacrifice event, and the move to the graveyard with its leave-the-battlefield events. The
     * event is built from the pre-sacrifice state so it carries the permanent's object-incarnation
     * stamp (the History-C authority a sacrifice event must not reconstruct from a neighbouring
     * zone change).
     */
    fun sacrifice(permId: EntityId) {
        val permName = state.getEntity(permId)?.get<CardComponent>()?.name
        val tracked = ZoneTransitionService.trackPermanentSacrifice(state, listOf(permId), playerId)
        events.add(
            ZoneTransitionService.permanentsSacrificedEvent(
                state = state,
                playerId = playerId,
                permanentIds = listOf(permId),
                permanentNames = listOfNotNull(permName),
            ),
        )
        val transition = zones.moveToZone(tracked, permId, Zone.GRAVEYARD)
        events.addAll(transition.events)
        state = transition.state
    }

    /**
     * A sacrifice cost held back until the mana is paid: which permanents it sacrifices, read when
     * it is finally paid, and the events that mark the cost as paid once it is.
     */
    private class DeferredSacrifice(
        val select: (GameState) -> List<EntityId>,
        val afterPaid: List<GameEvent>,
    )

    private val deferredSacrifices = mutableListOf<DeferredSacrifice>()

    /**
     * Holds [permIds] back to be sacrificed by [sacrificeDeferred] once the mana is paid. Mana
     * abilities are activated before the total cost is paid (CR 601.2g), so a permanent chosen to
     * be sacrificed may still produce mana for this very cast — tapping a mana creature and then
     * sacrificing it, or a Food that taps for mana and is then foraged. [afterPaid] is emitted once
     * the sacrifice actually happens (a forage's "you foraged" event).
     */
    fun deferSacrifice(permIds: List<EntityId>, afterPaid: List<GameEvent> = emptyList()) {
        deferredSacrifices += DeferredSacrifice({ permIds }, afterPaid)
    }

    /**
     * [deferSacrifice] for a cost that names its permanents only as it is paid ("sacrifice all
     * creatures you control"): [select] reads them from the state at that moment.
     */
    fun deferSacrifice(select: (GameState) -> List<EntityId>) {
        deferredSacrifices += DeferredSacrifice(select, emptyList())
    }

    /**
     * Pays every held-back sacrifice, in the order the costs were paid: freezes each permanent's
     * last-known snapshot (CR 608.2h / 113.7a) and sacrifices it. A permanent that already left the
     * battlefield while the mana was paid (a token that ceased to exist, a source that sacrificed
     * itself for mana) has nothing left to sacrifice and is skipped.
     */
    fun sacrificeDeferred() {
        for (deferred in deferredSacrifices) {
            val chosen = deferred.select(state).filter { it in state.getBattlefield() }
            sacrificedSnapshots.addAll(captureEntitySnapshots(chosen, state))
            for (permId in chosen) sacrifice(permId)
            if (chosen.isNotEmpty()) events.addAll(deferred.afterPaid)
        }
        deferredSacrifices.clear()
    }
}
