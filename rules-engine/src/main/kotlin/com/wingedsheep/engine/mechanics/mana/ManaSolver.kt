package com.wingedsheep.engine.mechanics.mana
import com.wingedsheep.engine.state.components.battlefield.chosenCreatureType
import com.wingedsheep.engine.state.components.battlefield.chosenColor

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AbilityActivatedEverComponent
import com.wingedsheep.engine.state.components.battlefield.AbilityActivatedThisTurnComponent
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.engine.mechanics.SummoningSicknessRules
import com.wingedsheep.engine.mechanics.cost.CostPaymentService
import com.wingedsheep.engine.mechanics.cost.CostAmountResolver
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.PayCost
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.effects.AddAnyColorManaSpendOnChosenTypeEffect
import com.wingedsheep.sdk.scripting.effects.AddColorlessManaEffect
import com.wingedsheep.sdk.scripting.effects.AddDynamicManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaEffect
import com.wingedsheep.sdk.scripting.effects.AddManaOfChoiceEffect
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.Gate
import com.wingedsheep.sdk.scripting.effects.GatedEffect
import com.wingedsheep.sdk.scripting.values.ManaColorSet
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.effects.ManaSpellRider
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.AdditionalManaOnSourceTap
import com.wingedsheep.sdk.scripting.TappedForManaType
import com.wingedsheep.sdk.scripting.AdditionalManaOnTap
import com.wingedsheep.sdk.scripting.DampLandManaProduction
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.MultiplyManaOnSourceTap
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * Represents the mana production capability of a source.
 */
data class ManaSource(
    val entityId: EntityId,
    val name: String,
    /** Subtype snapshot captured during source discovery for the actual production transition. */
    val sourceSubtypes: Set<Subtype> = emptySet(),
    /** Colors this source can produce (empty means colorless-only) */
    val producesColors: Set<Color>,
    /** Whether this source can produce colorless mana */
    val producesColorless: Boolean = false,
    /** Whether this is a basic land (Plains, Island, Swamp, Mountain, Forest) */
    val isBasicLand: Boolean = false,
    /** Whether this source is a land (any land type) */
    val isLand: Boolean = false,
    /**
     * Colors supplied by an intrinsic basic-land-subtype mana ability before explicit or granted
     * abilities are aggregated. A non-empty value combined with explicit mana abilities means the
     * source currently has two distinct ability identities that ManaSource does not yet expose as
     * a complete public choice set.
     */
    val intrinsicManaColors: Set<Color> = emptySet(),
    /** Whether this source is a creature */
    val isCreature: Boolean = false,
    /** Whether this source has non-mana activated abilities (utility land/creature) */
    val hasNonManaAbilities: Boolean = false,
    /** Whether tapping this source costs life (pain land) */
    val hasPainCost: Boolean = false,
    /** Amount of life paid when tapping (for pain lands) */
    val painAmount: Int = 0,
    /** Whether this creature can attack (no summoning sickness or has haste) */
    val canAttack: Boolean = false,
    /** Amount of mana this source produces per tap (e.g., 3 for Elvish Aberration) */
    val manaAmount: Int = 1,
    /** Extra mana produced per tap from auras like Elvish Guidance */
    val bonusManaPerTap: Int = 0,
    /** Color of the bonus mana */
    val bonusManaColor: Color? = null,
    /**
     * When true, the bonus mana ([bonusManaPerTap]) is "one mana of any color" (Fertile Ground) —
     * the solver may spend it toward any colored or generic cost. [bonusManaColor] then only acts
     * as the fallback color for any bonus left unspent.
     */
    val bonusManaIsAnyColor: Boolean = false,
    /**
     * Extra *colorless* {C} mana produced per tap by an [AdditionalManaOnSourceTap] whose bonus is
     * colorless (Ultima's "tap a land for {C}, add an additional {C}"). Kept separate from
     * [bonusManaPerTap]/[bonusManaColor] because colorless is not a [Color]; floated as a colorless
     * [BonusManaEntry] when the source is tapped, so it can pay {C}/generic pips or land in the pool.
     */
    val bonusManaColorlessPerTap: Int = 0,
    /** Mana spending restriction (e.g., "only for instant/sorcery"). Null = unrestricted. */
    val restriction: ManaRestriction? = null,
    /**
     * Per-color spell riders attached to mana this source produces (e.g. Cavern of
     * Souls' colored mana carries [ManaSpellRider.MakesSpellUncounterable]). Tracked
     * per-color so a fused source with mixed rider/non-rider abilities (e.g. an
     * uncounterable colored ability alongside a plain colorless ability) attributes
     * riders only to the color the rider-bearing ability actually produces.
     */
    val colorRiders: Map<Color, Set<ManaSpellRider>> = emptyMap(),
    /** Per-color mana restrictions. Colors not in this map are unrestricted. */
    val colorRestrictions: Map<Color, ManaRestriction> = emptyMap(),
    /**
     * True when this source has multiple mana abilities with mutually-different
     * restrictions (e.g. Steelswarm Operator's two abilities), so the cached aggregate
     * collapses to "unrestricted" and is only correct without a spell/ability payment
     * context. The solver re-runs [findAvailableManaSources] with the context when any
     * cached source carries this flag.
     */
    val hasContextSensitiveAbilities: Boolean = false,
    /**
     * Additional mana cost required (beyond tapping) to produce each color.
     * Entries reflect the *cheapest* ability producing that color on this permanent.
     * For filter lands like Hidden Grotto ({1}, {T}: Add one mana of any color),
     * every color maps to 1 because the only ability producing colors costs {1}.
     * Colors not present in this map can be produced for free (or not at all).
     */
    val colorActivationManaCost: Map<Color, Int> = emptyMap(),
    /**
     * Additional mana cost required to produce colorless mana. Colorless production has no
     * [Color] key, so it is tracked separately from [colorActivationManaCost].
     */
    val colorlessActivationManaCost: Int = 0,
    /**
     * Life required to produce each color, from the *cheapest* ability producing that color.
     * Covers both pain modeled as a cost atom (Starting Town's "{T}, Pay 1 life: Add one mana
     * of any color") and pain modeled as a self-damage side effect (Battlefield Forge's
     * "{T}: Add {R} or {W}. This land deals 1 damage to you."). Colors not in this map are
     * pain-free. Unlike the source-level [hasPainCost]/[painAmount] pair — which only flags a
     * source whose *every* ability pains — this lets the solver see that a mixed source (free
     * "{T}: Add {C}" alongside a painful colored ability) charges life for its colors but not
     * for colorless, so auto-pay can route generic pips through the free ability.
     */
    val colorPainCost: Map<Color, Int> = emptyMap(),
    /**
     * Life required to produce colorless via the cheapest colorless-producing ability
     * (0 = free, the overwhelmingly common case). Only meaningful when [producesColorless].
     */
    val colorlessPainCost: Int = 0,
    /** Exact mana ability selected by the solver for each colored production. */
    val manaAbilityForColor: Map<Color, ActivatedAbility> = emptyMap(),
    /** Exact mana ability selected by the solver for colorless production. */
    val manaAbilityForColorless: ActivatedAbility? = null,
    /**
     * Every currently usable explicit mana ability for each colored production. The solver's
     * singular maps above remain its deterministic auto-pay choice; payment-domain callers must
     * use these complete lists so a preferred runtime ability is never hidden from a controller.
     */
    val manaAbilityOptionsForColor: Map<Color, List<ActivatedAbility>> = emptyMap(),
    /** Every currently usable explicit mana ability for colorless production. */
    val manaAbilityOptionsForColorless: List<ActivatedAbility> = emptyList(),
    /**
     * Rules-owned PaymentPlanV1 production profile for each stable mana-ability identity. The
     * profile is resolved after the current source discovery pass and is invalidated by any
     * production-transforming runtime modifier.
     */
    val paymentManaProductionProfiles: Map<String, PaymentManaProductionProfile> = emptyMap(),
    /** Exact support certificate for the selected ability's non-mana side effects. */
    val paymentManaSideEffectCertificates: Map<String, PaymentManaSideEffectCertificate> = emptyMap(),
    /**
     * Tapping this source also requires sacrificing it (e.g. Treasure tokens —
     * "{T}, Sacrifice this artifact: Add one mana of any color"). The auto-pay
     * solver (`solve()`) refuses to pick these because silently sacrificing a
     * permanent would surprise the player; manual mana-source selection menus
     * may offer them so the choice is explicit.
     */
    val requiresSacrifice: Boolean = false,
    /**
     * Colors this source can produce *only* by sacrificing itself, when it also has at
     * least one non-sacrifice mana ability (e.g. Irrigation Ditch — `{T}: Add {W}` plus
     * `{T}, Sacrifice this land: Add {G}{U}`). The source-level [requiresSacrifice] flag
     * stays false for such mixed sources (the {W} path is sacrifice-free), so the auto-pay
     * solver must drop these specific colors instead — otherwise it would silently pick the
     * sacrifice ability to produce {G}/{U}. Manual mana-source selection still offers the
     * sacrifice ability for these colors so the choice is explicit.
     */
    val colorsRequiringSacrifice: Set<Color> = emptySet(),
    /**
     * Tapping this source also requires tapping another permanent (e.g. Springleaf
     * Drum — "{T}, Tap an untapped creature you control: Add one mana of any color").
     * Auto-pay refuses to pick these because silently tapping someone else's permanent
     * choice would surprise the player; manual mana-source selection menus offer the
     * source and the resumer prompts for the secondary tap target. Null when no such
     * sub-cost is present.
     */
    val tapPermanentsSubCost: TapPermanentsSubCost? = null
) {
    /**
     * Returns the set of colors this source can produce for a given spell context.
     * Filters out colors whose restriction is not satisfied.
     */
    fun availableColorsFor(spellContext: SpellPaymentContext?): Set<Color> {
        if (colorRestrictions.isEmpty() || spellContext == null) return producesColors
        return producesColors.filter { color ->
            val restriction = colorRestrictions[color]
            restriction == null || restriction.isSatisfiedBy(spellContext)
        }.toSet()
    }

    fun manaAbilityFor(color: Color?): ActivatedAbility? =
        if (color == null) manaAbilityForColorless else manaAbilityForColor[color]

    /** All explicit ability choices for this production, with legacy-source fallback. */
    fun manaAbilityOptionsFor(color: Color?): List<ActivatedAbility> =
        if (color == null) {
            manaAbilityOptionsForColorless.ifEmpty { manaAbilityForColorless?.let(::listOf).orEmpty() }
        } else {
            manaAbilityOptionsForColor[color].orEmpty().ifEmpty {
                manaAbilityForColor[color]?.let(::listOf).orEmpty()
            }
        }
}

/**
 * Secondary tap-permanents sub-cost attached to a tap-based mana ability
 * (e.g. Springleaf Drum's "Tap an untapped creature you control").
 *
 * Mirrors [com.wingedsheep.sdk.scripting.AbilityCost.TapPermanents] but lives on
 * [ManaSource] so consumers don't need to re-resolve the ability's cost shape.
 */
data class TapPermanentsSubCost(
    val count: Int,
    val filter: GameObjectFilter,
    val excludeSelf: Boolean
)

/**
 * Result of solving mana payment.
 *
 * @property sources The mana sources to tap to pay the cost
 * @property manaProduced Map of each source to the mana it will produce for this payment
 */
data class ManaSolution(
    val sources: List<ManaSource>,
    val manaProduced: Map<EntityId, ManaProduction>,
    /**
     * Bonus mana remaining after the solver consumed some to pay the cost. Entries that
     * came from a restricted mana ability retain the restriction so callers can
     * preserve it when adding the leftover to the player's pool — losing the
     * restriction would let an artifact-only or creature-spell-only mana be spent
     * arbitrarily on the next action.
     */
    val remainingBonusMana: List<BonusManaEntry> = emptyList(),
    /**
     * Spell riders consumed by this solution — the union of riders attached to the
     * specific (source, color) slots actually tapped (e.g. Cavern of Souls' colored
     * ability contributes [ManaSpellRider.MakesSpellUncounterable] when tapped for
     * a color, but its colorless `{T}: Add {C}` ability does not).
     */
    val consumedRiders: List<ManaSpellRider> = emptyList(),
    /**
     * For a color-restricted `{X}` cost ("spend only [colors] on X"), the per-color
     * breakdown of mana this solution allocated to the X portion specifically. Empty
     * when the cost has no X restriction. The cast/ability payment path adds this to the
     * mana-spent-on-X accumulator that backs `DynamicAmount.ManaSpentOnX`.
     */
    val xRestrictedManaSpent: Map<Color, Int> = emptyMap(),
    /**
     * Per-color count of genuinely-extra aura bonus mana (Shimmerwilds Growth, Fertile Ground, …)
     * the solver consumed to pay colored/hybrid pips or generic. This mana is NOT in [manaProduced]
     * (which only tracks the printed mana of tapped sources), so the cast/ability payment path must
     * add it to the per-color mana-spent tally that backs `Conditions.ManaSpentToCastIncludes`.
     */
    val bonusManaSpentByColor: Map<Color, Int> = emptyMap(),
    /** Exact mana ability selected for every tapped source, including sources tapped only to pay
     * another mana ability's activation cost (which therefore have no [manaProduced] entry). */
    val manaAbilityUses: Map<EntityId, ManaAbilityUse> = emptyMap(),
    /** Initial floating pool after all pool resources consumed by this solution. */
    val poolAfterPayment: ManaPool? = null,
    /** Initial floating pool after only nested mana-activation costs are consumed. */
    val poolAfterActivation: ManaPool? = null,
    /** Pool mana consumed while paying selected mana abilities' activation costs. */
    val poolManaSpentForActivation: ManaPool = ManaPool(),
    /** Pool mana consumed directly against the requested outer cost, including X. */
    val poolManaSpentForOuter: ManaPool = ManaPool()
)

/**
 * A unit of bonus mana that wasn't consumed by the current solve. Carries the source's
 * mana restriction (when any) so the caller can route it back into the floating pool
 * via [ManaPool.addRestricted] instead of [ManaPool.add].
 */
data class BonusManaEntry(
    val color: Color,
    val amount: Int = 1,
    val restriction: ManaRestriction? = null,
    /**
     * When true this bonus mana may be spent toward a cost of any color (Fertile Ground's
     * "one mana of any color"). [color] then only acts as the fallback if the bonus is left
     * unspent and lands in the pool.
     */
    val anyColor: Boolean = false,
    /**
     * When true this entry is colorless excess from a multi-mana colorless source (e.g. the
     * second {C} of Sol Ring's "{T}: Add {C}{C}"). It can pay generic and {C} costs but never
     * a colored pip; [color] is an unused placeholder and the entry floats back as colorless.
     */
    val colorless: Boolean = false,
    /**
     * True when this entry is genuinely-extra mana from an aura tap-bonus (Shimmerwilds Growth,
     * Fertile Ground, Wild Growth, …) that is NOT already reflected in [ManaSolution.manaProduced].
     * Consuming such an entry to pay a pip therefore contributes its color to the mana-spent tally
     * (so "if {B}{B} was spent" gates like Deceit's see the bonus black mana). It is false for
     * multi-mana excess (e.g. a 3-green source), whose full amount the solver already records in
     * [manaProduced] — counting that again would double the spend.
     */
    val countsTowardSpent: Boolean = false,
)

/** One exact floating-pool unit selected by the pool-only outer-payment witness. */
private data class PoolResourceChoice(
    val color: Color?,
    val restricted: Boolean,
)

/** The pool units assigned to one fixed outer cost symbol by a pool-only witness. */
private data class PoolFixedAllocation(
    val symbol: ManaSymbol,
    val resources: List<PoolResourceChoice>,
)

/** Complete pool-only witness for remaining fixed demand plus color-restricted X. */
private data class PoolOnlyPaymentPlan(
    val fixedAllocations: List<PoolFixedAllocation>,
    val restrictedXAllocations: List<PoolResourceChoice>,
)

/**
 * The mana a single source produces for a payment.
 */
data class ManaProduction(
    val color: Color? = null,
    val amount: Int = 1,
    val colorless: Int = 0,
    /** Snapshot carried from source discovery; null means this legacy result has no snapshot. */
    val sourceSubtypes: Set<Subtype>? = null,
    /** Exact activated mana ability represented by this production, when the source has one. */
    val manaAbility: ActivatedAbility? = null,
)

/** Exact solver provenance for one selected mana ability and the mana color it was used for. */
data class ManaAbilityUse(
    val ability: ActivatedAbility?,
    val producedColor: Color?,
)

/**
 * Select the deterministic ability used when a source is tapped only for a generic activation
 * cost. This mirrors the solver's generic-production preference without relying on map iteration
 * order or the first color on a multi-ability source.
 */
private fun ManaSource.preferredManaAbilityForGenericPayment(): ManaAbilityUse? {
    val colorless = manaAbilityFor(null)?.let { ManaAbilityUse(it, null) }
    val colored = producesColors.mapNotNull { color ->
        manaAbilityFor(color)?.let { ability -> ManaAbilityUse(ability, color) }
    }.minWithOrNull(
        compareBy<ManaAbilityUse>(
            { use ->
                val color = use.producedColor ?: return@compareBy 0
                (colorActivationManaCost[color] ?: 0) + (colorPainCost[color] ?: 0)
            },
            { use -> use.ability?.id?.value ?: Int.MAX_VALUE },
        )
    )
    if (colored == null) return colorless
    if (colorless == null) return colored

    val coloredCost = (colored.producedColor?.let { colorActivationManaCost[it] ?: 0 } ?: 0) +
        (colored.producedColor?.let { colorPainCost[it] ?: 0 } ?: 0)
    val colorlessCost = colorlessActivationManaCost + colorlessPainCost
    return if (coloredCost > colorlessCost) colorless else colored
}

private fun ManaSource.activationManaCostFor(color: Color?): Int =
    color?.let { colorActivationManaCost[it] ?: 0 } ?: colorlessActivationManaCost

private data class ManaAbilitySelection(
    val ability: ActivatedAbility,
    val activationManaCost: Int,
    val painAmount: Int,
    val requiresSacrifice: Boolean,
    val requiresSecondaryTap: Boolean,
)

/** Deterministic selection matching the solver's cheapest per-color metadata. */
private fun isPreferredManaAbility(
    candidate: ManaAbilitySelection,
    existing: ManaAbilitySelection?,
): Boolean {
    if (existing == null) return true
    val candidateTotal = candidate.activationManaCost.toLong() + candidate.painAmount.toLong()
    val existingTotal = existing.activationManaCost.toLong() + existing.painAmount.toLong()
    return when {
        // The selected ability is executed later by the auto-payment side-effect path. Preserve
        // the source-level safety policy first: never choose a sacrifice or secondary-tap variant
        // merely because its life/mana price is lower when a safe ability produces the same color.
        candidate.requiresSacrifice != existing.requiresSacrifice -> !candidate.requiresSacrifice
        candidate.requiresSecondaryTap != existing.requiresSecondaryTap -> !candidate.requiresSecondaryTap
        candidateTotal != existingTotal -> candidateTotal < existingTotal
        candidate.activationManaCost != existing.activationManaCost ->
            candidate.activationManaCost < existing.activationManaCost
        candidate.painAmount != existing.painAmount -> candidate.painAmount < existing.painAmount
        else -> candidate.ability.id.value < existing.ability.id.value
    }
}

/**
 * Solves mana payment by finding which lands/sources to tap for AutoPay.
 *
 * The solver uses a greedy algorithm:
 * 1. Pay colored costs first, using sources that ONLY produce that color when possible
 * 2. Pay colorless costs with sources that only produce colorless
 * 3. Pay generic costs with any remaining sources
 *
 * This heuristic preserves flexibility by saving multi-color lands for later.
 *
 * @param cardRegistry Optional registry to look up card definitions for mana abilities.
 *                     When provided, non-land permanents with mana abilities can be used as sources.
 */
class ManaSolver(
    private val cardRegistry: CardRegistry,
    private val dynamicAmountEvaluator: DynamicAmountEvaluator = DynamicAmountEvaluator()
) {

    private val predicateEvaluator = PredicateEvaluator()
    private val conditionEvaluator = ConditionEvaluator()

    /** The five subtypes that grant a land its intrinsic `{T}: Add …` mana ability (CR 305.6). */
    private val basicLandSubtypeNames = setOf("Plains", "Island", "Swamp", "Mountain", "Forest")

    /**
     * Finds a valid set of mana sources to pay the cost.
     *
     * @param state The current game state
     * @param playerId The player who needs to pay
     * @param cost The mana cost to pay
     * @param xValue The value of X (for X-cost spells)
     * @return A solution describing which sources to tap, or null if the cost cannot be paid
     */
    fun solve(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        xValue: Int = 0,
        excludeSources: Set<EntityId> = emptySet(),
        spellContext: SpellPaymentContext? = null,
        precomputedSources: List<ManaSource>? = null,
        /**
         * Colors that may be spent on the `{X}` portion of [cost] ("spend only [colors] on X").
         * Empty (default) means X behaves like ordinary generic. When non-empty, the X mana is
         * paid by a dedicated pass that only taps sources able to produce one of these colors,
         * and the per-color amount is reported in [ManaSolution.xRestrictedManaSpent].
         */
        xManaRestriction: Set<Color> = emptySet(),
        /**
         * Life already committed by the caller in this same atomic payment. The solver adds it
         * to the selected mana abilities' own PayLife atoms before accepting the solution.
         */
        additionalPayLife: Int = 0,
        /**
         * Floating mana available to the same payment program. Unlike the historical callers
         * that pre-spend the pool against the outer cost, this ledger keeps pool units available
         * for a selected mana ability's own activation cost and for the outer cost together.
         */
        initialManaPool: ManaPool? = null,
    ): ManaSolution? {
        // Get all untapped mana sources controlled by the player.
        //
        // The cached `precomputedSources` is built without a payment context, so for
        // sources that have multiple mana abilities with mismatched restrictions
        // (e.g. Steelswarm Operator) the cached aggregate collapses to "unrestricted"
        // and over-states what the source can produce for a specific spend. When such
        // a source is present and a context is provided, re-run findAvailableManaSources
        // with the context to filter abilities accurately. Otherwise reuse the cache.
        val cachedSources = precomputedSources
        val needsContextRebuild = spellContext != null && (
            cachedSources == null ||
                cachedSources.any { it.hasContextSensitiveAbilities }
            )
        val rawSources = if (needsContextRebuild) {
            findAvailableManaSources(state, playerId, spellContext)
        } else {
            cachedSources ?: findAvailableManaSources(state, playerId)
        }
        val availableSources = rawSources
            .filter { it.entityId !in excludeSources }
            // Auto-pay must not silently sacrifice permanents (e.g. Treasure tokens).
            // The bonus-mana accounting in canPay() still counts these via
            // sacrificeSelfManaBySource(), but the solver itself never picks them.
            .filter { !it.requiresSacrifice }
            // Same rule for composite Tap+TapPermanents sources (Springleaf Drum) — the
            // resumer must prompt the player to pick which creature gets tapped, so the
            // auto-pay solver refuses to silently consume the choice. canPay() accounts
            // for these via calculateCompositeTapPermanentsBonusMana().
            .filter { it.tapPermanentsSubCost == null }
            .filter { source ->
                if (source.restriction == null || spellContext == null) true
                else source.restriction.isSatisfiedBy(spellContext)
            }
            // Drop colors a mixed source can only make by sacrificing itself (e.g. Irrigation
            // Ditch's {G}{U}, kept behind its sacrifice-free {W} ability). Auto-pay must not
            // silently sacrifice; the {W} path remains usable, and manual selection still
            // offers the sacrifice ability for these colors.
            .map { source ->
                if (source.colorsRequiringSacrifice.isEmpty()) source
                else source.copy(producesColors = source.producesColors - source.colorsRequiringSacrifice)
            }

        // Analyze hand to inform smart tapping decisions
        val handRequirements = analyzeHandRequirements(state, playerId)

        // Count available sources per color for hand-awareness (respecting per-color restrictions)
        val availableSourcesByColor = mutableMapOf<Color, Int>()
        for (color in Color.entries) {
            availableSourcesByColor[color] = availableSources.count { it.availableColorsFor(spellContext).contains(color) }
        }

        // Track which sources we've used
        val usedSources = mutableListOf<ManaSource>()
        val manaProduced = mutableMapOf<EntityId, ManaProduction>()
        val manaAbilityUses = mutableMapOf<EntityId, ManaAbilityUse>()
        var remainingSources = availableSources.toMutableList()

        // Track bonus mana from auras and excess mana from multi-mana sources. The list
        // preserves the originating restriction (if any) per entry so unconsumed bonus
        // mana retains its restriction when it lands in the player's pool.
        val bonusManaPool = mutableListOf<BonusManaEntry>()

        // One resource ledger for both the outer payment and nested activation costs. Pool
        // consumption is classified separately so callers can emit the outer ManaSpentEvent
        // without attributing mana spent to an inner mana ability to the spell/ability being paid.
        var availableManaPool = initialManaPool
        // The activation path defers the outer payment to CostHandler. Keep a second view of the
        // same initial pool with only nested activation-cost resources removed so that it can hand
        // CostHandler the exact pool it should start from after the selected source activations.
        var poolAfterActivation = initialManaPool
        var poolManaSpentForActivation = ManaPool()
        var poolManaSpentForOuter = ManaPool()

        // Track the outer payment demand independently from the mutable pool. The reservation
        // look-ahead is called after earlier symbols have already been paid, so asking whether the
        // original cost is affordable would preserve a resource that is no longer needed and can
        // make a paid source appear reachable when it should remain untouched.
        var unrestrictedXExpanded = false
        val remainingOuterSymbols = cost.symbols.flatMap { symbol ->
            when (symbol) {
                is ManaSymbol.Generic -> List(symbol.amount.coerceAtLeast(0)) { ManaSymbol.Generic(1) }
                is ManaSymbol.X -> if (xManaRestriction.isEmpty() && !unrestrictedXExpanded) {
                    unrestrictedXExpanded = true
                    List(xValue.coerceAtLeast(0)) { ManaSymbol.Generic(1) }
                } else {
                    emptyList()
                }
                else -> listOf(symbol)
            }
        }.toMutableList()
        var remainingRestrictedX = if (xManaRestriction.isEmpty()) 0 else xValue.coerceAtLeast(0)
        var outerPoolWitness: PoolOnlyPaymentPlan? = null

        fun consumeOuterSymbol(symbol: ManaSymbol) {
            val index = remainingOuterSymbols.indexOf(symbol)
            check(index >= 0) { "Outer payment symbol was already consumed: $symbol" }
            remainingOuterSymbols.removeAt(index)
        }

        fun consumeOuterRestrictedX() {
            check(remainingRestrictedX > 0) { "Restricted X payment was already consumed" }
            remainingRestrictedX--
        }

        fun convertOuterMonocolorHybridToGeneric(symbol: ManaSymbol.MonocolorHybrid) {
            val index = remainingOuterSymbols.indexOf(symbol)
            check(index >= 0) { "Outer monocolor-hybrid symbol was already consumed: $symbol" }
            remainingOuterSymbols.removeAt(index)
            val genericUnits = symbol.generic.coerceAtLeast(0)
            remainingOuterSymbols.addAll(index, List(genericUnits) { ManaSymbol.Generic(1) })

            // Keep a pool-only witness executable when the monocolored-hybrid branch selected its
            // generic alternative. The actual generic pass consumes ordinary Generic(1) targets,
            // so expand the multi-unit target into the same atomized targets here.
            val witness = outerPoolWitness
            val allocationIndex = witness?.fixedAllocations?.indexOfFirst { it.symbol == symbol } ?: -1
            if (witness != null && allocationIndex >= 0) {
                val allocation = witness.fixedAllocations[allocationIndex]
                if (allocation.resources.size == genericUnits) {
                    val genericAllocations = allocation.resources.map { resource ->
                        PoolFixedAllocation(ManaSymbol.Generic(1), listOf(resource))
                    }
                    outerPoolWitness = witness.copy(
                        fixedAllocations = witness.fixedAllocations.toMutableList().apply {
                            removeAt(allocationIndex)
                            addAll(index.coerceAtMost(size), genericAllocations)
                        }
                    )
                }
            }
        }

        fun addPoolSpend(current: ManaPool, spent: ManaPool): ManaPool = current.copy(
            white = current.white + spent.white,
            blue = current.blue + spent.blue,
            black = current.black + spent.black,
            red = current.red + spent.red,
            green = current.green + spent.green,
            colorless = current.colorless + spent.colorless,
        )

        fun addOuterPoolSpend(color: Color?) {
            poolManaSpentForOuter = when (color) {
                null -> poolManaSpentForOuter.copy(colorless = poolManaSpentForOuter.colorless + 1)
                Color.WHITE -> poolManaSpentForOuter.copy(white = poolManaSpentForOuter.white + 1)
                Color.BLUE -> poolManaSpentForOuter.copy(blue = poolManaSpentForOuter.blue + 1)
                Color.BLACK -> poolManaSpentForOuter.copy(black = poolManaSpentForOuter.black + 1)
                Color.RED -> poolManaSpentForOuter.copy(red = poolManaSpentForOuter.red + 1)
                Color.GREEN -> poolManaSpentForOuter.copy(green = poolManaSpentForOuter.green + 1)
            }
        }

        fun consumePlannedResource(pool: ManaPool, resource: PoolResourceChoice): ManaPool? {
            if (resource.restricted) {
                val context = spellContext ?: return null
                return pool.spendRestricted(resource.color, context)
            }
            return resource.color?.let(pool::spend) ?: pool.spendColorless()
        }

        /**
         * Apply exactly the resources consumed by one [ManaPool.payPartial] call to an independent
         * pool view. The ordinary counters are represented by [before]/[after] differences and
         * restricted entries by multiset subtraction; this avoids replaying every spend as a new
         * generic payment, which could select a different restriction or leave an inner resource
         * in the outer ability's pool.
         */
        fun applyPoolConsumption(
            pool: ManaPool,
            before: ManaPool,
            after: ManaPool,
        ): ManaPool? {
            val spentByColor = listOf(
                Color.WHITE to (before.white - after.white),
                Color.BLUE to (before.blue - after.blue),
                Color.BLACK to (before.black - after.black),
                Color.RED to (before.red - after.red),
                Color.GREEN to (before.green - after.green),
            )
            if (spentByColor.any { (_, amount) -> amount < 0 } || before.colorless - after.colorless < 0) {
                return null
            }

            var result = pool
            for ((color, amount) in spentByColor) {
                repeat(amount) {
                    result = result.spend(color) ?: return null
                }
            }
            repeat(before.colorless - after.colorless) {
                result = result.spendColorless() ?: return null
            }

            val remainingAfter = after.restrictedMana.toMutableList()
            val restrictedSpent = mutableListOf<RestrictedManaEntry>()
            for (entry in before.restrictedMana) {
                val index = remainingAfter.indexOf(entry)
                if (index >= 0) remainingAfter.removeAt(index) else restrictedSpent.add(entry)
            }
            val remainingPoolRestricted = result.restrictedMana.toMutableList()
            for (entry in restrictedSpent) {
                if (!remainingPoolRestricted.remove(entry)) return null
            }
            return result.copy(restrictedMana = remainingPoolRestricted)
        }

        fun spendPoolCost(
            poolCost: ManaCost,
            paymentContext: SpellPaymentContext?,
            activationCost: Boolean,
            outerSymbol: ManaSymbol? = null,
        ): Boolean {
            val pool = availableManaPool ?: return false

            // When the complete outer-demand probe found a pool-only witness, consume the exact
            // resource assigned to this symbol. Falling back to payPartial here would reintroduce
            // proof/execution drift and could activate a paid source unnecessarily.
            if (!activationCost && outerSymbol != null && outerPoolWitness != null) {
                val witness = outerPoolWitness!!
                val allocationIndex = witness.fixedAllocations.indexOfFirst { it.symbol == outerSymbol }
                if (allocationIndex < 0) return false
                val allocation = witness.fixedAllocations[allocationIndex]
                if (allocation.resources.size != 1) return false
                val resource = allocation.resources.single()
                val nextPool = consumePlannedResource(pool, resource) ?: return false
                availableManaPool = nextPool
                outerPoolWitness = witness.copy(
                    fixedAllocations = witness.fixedAllocations.toMutableList().apply {
                        removeAt(allocationIndex)
                    }
                )
                addOuterPoolSpend(resource.color)
                consumeOuterSymbol(outerSymbol)
                return true
            }

            val partial = pool.payPartial(poolCost, paymentContext)
            if (!partial.remainingCost.isEmpty()) return false
            val nextActivationPool = if (activationCost) {
                val activationPool = poolAfterActivation ?: return false
                applyPoolConsumption(activationPool, pool, partial.newPool) ?: return false
            } else {
                null
            }
            availableManaPool = partial.newPool
            if (activationCost) {
                poolAfterActivation = nextActivationPool
                poolManaSpentForActivation = addPoolSpend(poolManaSpentForActivation, partial.manaSpent)
            } else {
                poolManaSpentForOuter = addPoolSpend(poolManaSpentForOuter, partial.manaSpent)
            }
            if (!activationCost && outerSymbol != null) consumeOuterSymbol(outerSymbol)
            return true
        }

        val genericManaUnit = ManaCost(listOf(ManaSymbol.Generic(1)))

        /**
         * Whether the currently available pool can cover the entire outer payment, including the
         * already-resolved X amount. [ManaPool.canPay] intentionally treats X as zero because it
         * has no X-value parameter; using it directly here could spend the pool's only prerequisite
         * unit and strand a paid source that still needs that unit for its activation cost.
         */
        fun poolCanPayOuterCost(): Boolean {
            val pool = availableManaPool
            if (pool == null) {
                outerPoolWitness = null
                return false
            }

            /**
             * Find a complete pool-only witness without inheriting `ManaPool.payPartial`'s greedy
             * resource order. A pool may cover a fixed colored/generic portion and restricted X
             * only when one common allocation exists; trying every legal unit assignment is small
             * here because the fixed cost is atomized and X is processed one unit at a time.
             */
            fun findPoolOnlyPaymentPlan(): PoolOnlyPaymentPlan? {
                val failedStates = mutableSetOf<String>()

                fun poolShape(pool: ManaPool): String = buildString {
                    append(pool.white).append(',')
                    append(pool.blue).append(',')
                    append(pool.black).append(',')
                    append(pool.red).append(',')
                    append(pool.green).append(',')
                    append(pool.colorless).append('|')
                    pool.restrictedMana
                        .groupingBy { entry -> "${entry.color?.ordinal ?: -1}:${entry.restriction}" }
                        .eachCount()
                        .toSortedMap()
                        .forEach { (resource, amount) -> append(resource).append('=').append(amount).append(';') }
                }

                fun consumeResources(
                    pool: ManaPool,
                    accepts: (Color?) -> Boolean,
                ): List<Pair<ManaPool, PoolResourceChoice>> {
                    val options = mutableListOf<Pair<ManaPool, PoolResourceChoice>>()
                    // Preserve the existing payment preference for the first witness while still
                    // exploring every alternative when that choice strands a later demand.
                    if (spellContext != null) {
                        for (entry in pool.restrictedMana) {
                            if (
                                accepts(entry.color) &&
                                entry.restriction.isSatisfiedBy(spellContext)
                            ) {
                                pool.spendRestricted(entry.color, spellContext)?.let {
                                    options.add(it to PoolResourceChoice(entry.color, restricted = true))
                                }
                            }
                        }
                    }
                    for (color in Color.entries) {
                        if (accepts(color)) {
                            pool.spend(color)?.let { options.add(it to PoolResourceChoice(color, restricted = false)) }
                        }
                    }
                    if (accepts(null)) {
                        pool.spendColorless()?.let {
                            options.add(it to PoolResourceChoice(null, restricted = false))
                        }
                    }
                    return options.distinct()
                }

                fun consumeGenericUnits(
                    pool: ManaPool,
                    amount: Int,
                ): List<Pair<ManaPool, List<PoolResourceChoice>>> {
                    var options = listOf(pool to emptyList<PoolResourceChoice>())
                    repeat(amount.coerceAtLeast(0)) {
                        options = options.flatMap { (currentPool, resources) ->
                            consumeResources(currentPool) { true }.map { (nextPool, resource) ->
                                nextPool to (resources + resource)
                            }
                        }.distinct()
                    }
                    return options
                }

                fun consumeFixedSymbol(
                    pool: ManaPool,
                    symbol: ManaSymbol,
                ): List<Pair<ManaPool, List<PoolResourceChoice>>> = when (symbol) {
                    is ManaSymbol.Generic -> consumeGenericUnits(pool, symbol.amount)
                    is ManaSymbol.Colorless -> consumeResources(pool) { it == null }
                        .map { (nextPool, resource) -> nextPool to listOf(resource) }
                    is ManaSymbol.Colored -> consumeResources(pool) { it == symbol.color }
                        .map { (nextPool, resource) -> nextPool to listOf(resource) }
                    is ManaSymbol.Hybrid -> (
                        consumeResources(pool) { it == symbol.color1 }
                            .map { (nextPool, resource) -> nextPool to listOf(resource) } +
                            consumeResources(pool) { it == symbol.color2 }
                                .map { (nextPool, resource) -> nextPool to listOf(resource) }
                        ).distinct()
                    is ManaSymbol.Phyrexian -> consumeResources(pool) { it == symbol.color }
                        .map { (nextPool, resource) -> nextPool to listOf(resource) }
                    is ManaSymbol.MonocolorHybrid -> (
                        consumeResources(pool) { it == symbol.color }
                            .map { (nextPool, resource) -> nextPool to listOf(resource) } +
                            consumeGenericUnits(pool, symbol.generic)
                        ).distinct()
                    is ManaSymbol.X -> emptyList()
                }

                fun search(
                    currentPool: ManaPool,
                    fixedSymbols: List<ManaSymbol>,
                    xRemaining: Int,
                ): PoolOnlyPaymentPlan? {
                    if (fixedSymbols.isEmpty() && xRemaining == 0) {
                        return PoolOnlyPaymentPlan(emptyList(), emptyList())
                    }
                    val stateKey = "${poolShape(currentPool)}|$fixedSymbols|$xRemaining"
                    if (!failedStates.add(stateKey)) return null

                    if (fixedSymbols.isNotEmpty()) {
                        val remainingFixed = fixedSymbols.drop(1)
                        val symbol = fixedSymbols.first()
                        for ((nextPool, resources) in consumeFixedSymbol(currentPool, symbol)) {
                            val result = search(nextPool, remainingFixed, xRemaining)
                            if (result != null) {
                                return result.copy(
                                    fixedAllocations = listOf(PoolFixedAllocation(symbol, resources)) +
                                        result.fixedAllocations
                                )
                            }
                        }
                        return null
                    }

                    for ((nextPool, resource) in consumeResources(currentPool) { color ->
                        color != null && color in xManaRestriction
                    }) {
                        val result = search(nextPool, emptyList(), xRemaining - 1)
                        if (result != null) {
                            return result.copy(
                                restrictedXAllocations = listOf(resource) + result.restrictedXAllocations
                            )
                        }
                    }
                    return null
                }

                return search(pool, remainingOuterSymbols, remainingRestrictedX)
            }

            outerPoolWitness = findPoolOnlyPaymentPlan()
            return outerPoolWitness != null
        }

        /** Consume one eligible restricted pool unit for the color-restricted X pass. */
        fun spendRestrictedManaForX(): Color? {
            val context = spellContext ?: return null
            val pool = availableManaPool ?: return null
            val entry = pool.restrictedMana.firstOrNull { entry ->
                entry.color != null &&
                    entry.color in xManaRestriction &&
                    entry.restriction.isSatisfiedBy(context)
            } ?: return null
            val color = entry.color ?: return null
            availableManaPool = pool.spendRestricted(color, context) ?: return null
            addOuterPoolSpend(color)
            return color
        }

        /** Consume the exact X resource selected by the current pool-only witness. */
        fun spendPlannedRestrictedX(): Color? {
            val witness = outerPoolWitness ?: return null
            val resource = witness.restrictedXAllocations.firstOrNull() ?: return null
            val color = resource.color ?: return null
            val pool = availableManaPool ?: return null
            val nextPool = consumePlannedResource(pool, resource) ?: return null
            availableManaPool = nextPool
            outerPoolWitness = witness.copy(
                restrictedXAllocations = witness.restrictedXAllocations.drop(1),
            )
            addOuterPoolSpend(color)
            return color
        }

        fun poolCanSeedActivation(source: ManaSource, colorUsed: Color?): Boolean {
            val pool = availableManaPool ?: return false
            val ability = source.manaAbilityFor(colorUsed) ?: return false
            val sourceCard = state.getEntity(source.entityId)?.get<CardComponent>() ?: return false
            val activationContext = buildAbilityPaymentContext(
                cardComponent = sourceCard,
                projected = state.projectedState,
                sourceId = source.entityId,
                ability = ability,
            )
            return source.activationManaCostFor(colorUsed) > 0 &&
                pool.payPartial(genericManaUnit, activationContext).remainingCost.isEmpty()
        }

        // Per-color tally of aura bonus mana (entries flagged [BonusManaEntry.countsTowardSpent])
        // actually spent on the cost. Reported via [ManaSolution.bonusManaSpentByColor] so callers
        // fold it into the mana-spent-to-cast tally — see [BonusManaEntry.countsTowardSpent].
        val bonusManaSpentByColor = mutableMapOf<Color, Int>()

        // Helper to update available counts when a source is used
        fun useSource(
            source: ManaSource,
            colorUsed: Color?,
            selectedAbility: ActivatedAbility? = source.manaAbilityFor(colorUsed),
        ) {
            usedSources.add(source)
            remainingSources.removeAll { it.entityId == source.entityId }
            // Keep a marker even for intrinsic sources with no scripted ability. This is
            // important for a source tapped only to pay another mana ability's activation cost:
            // the side-effect executor must distinguish that valid no-cost source from a
            // production-less source whose ability provenance was lost.
            manaAbilityUses[source.entityId] = ManaAbilityUse(selectedAbility, colorUsed)
            for (color in source.producesColors) {
                availableSourcesByColor[color] = (availableSourcesByColor[color] ?: 1) - 1
            }
            // Track excess mana from multi-mana sources (e.g., Elvish Aberration produces 3 green).
            // Inherit the source's restriction for that color so leftover restricted mana
            // remains restricted in the pool.
            if (source.manaAmount > 1) {
                if (colorUsed != null) {
                    val restrictionForExcess = source.colorRestrictions[colorUsed] ?: source.restriction
                    bonusManaPool.add(BonusManaEntry(colorUsed, source.manaAmount - 1, restrictionForExcess))
                } else if (source.producesColorless) {
                    // Colorless excess (e.g. the second {C} of Sol Ring's "{T}: Add {C}{C}").
                    // Float it as colorless so a later generic/{C} pip can consume it, or it
                    // lands in the pool — instead of being silently dropped.
                    bonusManaPool.add(
                        BonusManaEntry(Color.WHITE, source.manaAmount - 1, source.restriction, colorless = true)
                    )
                }
            }
            // Collect bonus mana from auras attached to this source (no restriction —
            // the aura grants extra mana on top of the source's printed ability).
            if (source.bonusManaPerTap > 0 && (source.bonusManaColor != null || source.bonusManaIsAnyColor)) {
                bonusManaPool.add(
                    BonusManaEntry(
                        // Fertile Ground's any-color bonus has no fixed color; default to white as
                        // the fallback for any portion left unspent (anyColor lets it pay any cost).
                        color = source.bonusManaColor ?: Color.WHITE,
                        amount = source.bonusManaPerTap,
                        restriction = null,
                        anyColor = source.bonusManaIsAnyColor,
                        // Genuinely-extra mana, not in `manaProduced` — count it when spent.
                        countsTowardSpent = true,
                    )
                )
            }
            // Colorless tap-bonus (Ultima's "tap a land for {C}, add an additional {C}"). Floated as
            // a colorless entry so it can pay {C}/generic pips or float to the pool.
            if (source.bonusManaColorlessPerTap > 0) {
                bonusManaPool.add(
                    BonusManaEntry(
                        color = Color.WHITE,
                        amount = source.bonusManaColorlessPerTap,
                        restriction = null,
                        colorless = true,
                        countsTowardSpent = true,
                    )
                )
            }
        }

        // Helper to spend one bonus mana of a specific color for a colored cost. The
        // restriction was already checked when the source was admitted into
        // `availableSources`, so any matching-color entry is eligible for this payment.
        // Consumption is FIFO over `bonusManaPool` (insertion order = tap order); for the
        // current solve any order is correct, and the choice affects only which
        // restrictions land back in [ManaSolution.remainingBonusMana] for the caller.
        fun spendBonusMana(
            color: Color,
            paymentContext: SpellPaymentContext? = spellContext,
            outerSymbol: ManaSymbol? = null,
        ): Boolean {
            // An any-color bonus entry (Fertile Ground) can pay a cost of any color; prefer an exact
            // color match first so fixed-color bonuses aren't wasted on flexible demand. Colorless
            // excess is excluded — it can never satisfy a colored pip.
            val eligible: (BonusManaEntry) -> Boolean = { entry ->
                entry.restriction == null || paymentContext?.let(entry.restriction::isSatisfiedBy) == true
            }
            val idx = bonusManaPool.indexOfFirst {
                eligible(it) && it.color == color && !it.colorless && it.amount > 0
            }
                .takeIf { it >= 0 }
                ?: bonusManaPool.indexOfFirst { eligible(it) && it.anyColor && it.amount > 0 }
            if (idx < 0) return false
            val entry = bonusManaPool[idx]
            bonusManaPool[idx] = entry.copy(amount = entry.amount - 1)
            // A colored pip paid from genuinely-extra aura bonus mana contributes its color to the
            // mana-spent tally. The mana produced is exactly `color` (a fixed-color entry matches it;
            // an any-color entry produces the demanded color).
            if (entry.countsTowardSpent) {
                bonusManaSpentByColor[color] = (bonusManaSpentByColor[color] ?: 0) + 1
            }
            if (outerSymbol != null) consumeOuterSymbol(outerSymbol)
            return true
        }

        // Helper to spend one bonus mana of any color for a generic cost. Same FIFO
        // policy as [spendBonusMana].
        fun spendAnyBonusMana(
            countTowardSpent: Boolean = true,
            paymentContext: SpellPaymentContext? = spellContext,
            outerSymbol: ManaSymbol? = null,
        ): Boolean {
            val idx = bonusManaPool.indexOfFirst {
                it.amount > 0 &&
                    (it.restriction == null || paymentContext?.let(it.restriction::isSatisfiedBy) == true)
            }
            if (idx < 0) return false
            val entry = bonusManaPool[idx]
            bonusManaPool[idx] = entry.copy(amount = entry.amount - 1)
            // Generic paid from extra aura bonus mana counts its fixed color (mirroring
            // ManaPool.payPartial, where colored mana spent on generic still tracks as that color).
            // An any-color bonus has no color committed at solve time, so it stays uncounted here.
            if (countTowardSpent && entry.countsTowardSpent && !entry.anyColor && !entry.colorless) {
                bonusManaSpentByColor[entry.color] = (bonusManaSpentByColor[entry.color] ?: 0) + 1
            }
            if (outerSymbol != null) consumeOuterSymbol(outerSymbol)
            return true
        }

        // Helper to spend one colorless bonus mana for a {C} pip — only colorless excess
        // (e.g. Sol Ring's second {C}) qualifies; colored bonus mana can't pay {C}.
        fun spendColorlessBonusMana(
            paymentContext: SpellPaymentContext? = spellContext,
            outerSymbol: ManaSymbol? = null,
        ): Boolean {
            val idx = bonusManaPool.indexOfFirst {
                it.colorless && it.amount > 0 &&
                    (it.restriction == null || paymentContext?.let(it.restriction::isSatisfiedBy) == true)
            }
            if (idx < 0) return false
            val entry = bonusManaPool[idx]
            bonusManaPool[idx] = entry.copy(amount = entry.amount - 1)
            if (outerSymbol != null) consumeOuterSymbol(outerSymbol)
            return true
        }

        /**
         * Makes a source's activation mana cost reachable before the source contributes any output.
         *
         * A source's output cannot be used to pay its own activation cost, and two paid sources
         * cannot bootstrap one another. Existing bonus mana is available because it was produced
         * by an earlier, already-activated source. When that is insufficient, only a source whose
         * selected generic-payment ability is itself free may be tapped as a prerequisite. Its
         * primary mana pays one activation-cost unit and any excess remains in [bonusManaPool].
         *
         * The local solver state is restored when the prerequisite search fails. This matters for
        * callers that can try another production route after an unreachable candidate.
         */
        fun prepareSourceForProduction(source: ManaSource, colorUsed: Color?): Boolean {
            var activationCostRemaining = source.activationManaCostFor(colorUsed)
            if (activationCostRemaining <= 0) return true

            val selectedAbility = source.manaAbilityFor(colorUsed) ?: return false
            val sourceCard = state.getEntity(source.entityId)?.get<CardComponent>() ?: return false
            val activationContext = buildAbilityPaymentContext(
                cardComponent = sourceCard,
                projected = state.projectedState,
                sourceId = source.entityId,
                ability = selectedAbility,
            )

            val usedSourceCount = usedSources.size
            val remainingSnapshot = remainingSources.toList()
            val sourceCountsSnapshot = availableSourcesByColor.toMap()
            val bonusSnapshot = bonusManaPool.toList()
            val abilityUsesSnapshot = manaAbilityUses.toMap()
            val bonusSpentSnapshot = bonusManaSpentByColor.toMap()
            val poolSnapshot = availableManaPool
            val activationPoolSnapshot = poolAfterActivation
            val activationPoolSpentSnapshot = poolManaSpentForActivation
            val outerPoolSpentSnapshot = poolManaSpentForOuter

            fun restoreSolverState() {
                while (usedSources.size > usedSourceCount) usedSources.removeAt(usedSources.lastIndex)
                remainingSources.clear()
                remainingSources.addAll(remainingSnapshot)
                availableSourcesByColor.clear()
                availableSourcesByColor.putAll(sourceCountsSnapshot)
                bonusManaPool.clear()
                bonusManaPool.addAll(bonusSnapshot)
                manaAbilityUses.clear()
                manaAbilityUses.putAll(abilityUsesSnapshot)
                bonusManaSpentByColor.clear()
                bonusManaSpentByColor.putAll(bonusSpentSnapshot)
                availableManaPool = poolSnapshot
                poolAfterActivation = activationPoolSnapshot
                poolManaSpentForActivation = activationPoolSpentSnapshot
                poolManaSpentForOuter = outerPoolSpentSnapshot
            }

            while (activationCostRemaining > 0) {
                if (spendPoolCost(genericManaUnit, activationContext, activationCost = true)) {
                    activationCostRemaining--
                    continue
                }
                if (spendAnyBonusMana(countTowardSpent = false, paymentContext = activationContext)) {
                    activationCostRemaining--
                    continue
                }

                // Discover prerequisites under the payment context of the *inner* ability. The
                // outer spell context is deliberately not reused: spell-only mana may pay the
                // outer spell but never an activated ability, while AbilityActivationOnly mana
                // may be usable here despite being absent from the outer source set.
                val prerequisiteSources = findAvailableManaSources(state, playerId, activationContext)
                    .filter { it.entityId !in excludeSources }
                    .filter { !it.requiresSacrifice && it.tapPermanentsSubCost == null }
                    .map { candidate ->
                        if (candidate.colorsRequiringSacrifice.isEmpty()) candidate
                        else candidate.copy(
                            producesColors = candidate.producesColors - candidate.colorsRequiringSacrifice,
                            manaAbilityForColor = candidate.manaAbilityForColor.filterKeys {
                                it !in candidate.colorsRequiringSacrifice
                            },
                            manaAbilityOptionsForColor = candidate.manaAbilityOptionsForColor.filterKeys {
                                it !in candidate.colorsRequiringSacrifice
                            },
                        )
                    }
                val prerequisite = prerequisiteSources
                    .asSequence()
                    .filter { it.entityId != source.entityId && it.entityId !in usedSources.map(ManaSource::entityId) }
                    .mapNotNull { candidate ->
                        val selectedAbility = candidate.preferredManaAbilityForGenericPayment()
                        val candidateCost = candidate.activationManaCostFor(selectedAbility?.producedColor)
                        if (candidateCost == 0) candidate to selectedAbility else null
                    }
                    .minByOrNull { (candidate, _) ->
                        calculateTapPriority(candidate, handRequirements, availableSourcesByColor)
                    }

                if (prerequisite == null) {
                    restoreSolverState()
                    return false
                }

                val (prerequisiteSource, selectedAbility) = prerequisite
                useSource(
                    source = prerequisiteSource,
                    colorUsed = selectedAbility?.producedColor,
                    selectedAbility = selectedAbility?.ability,
                )
                // The prerequisite source's first mana unit pays this activation-cost unit;
                // excess output was added to bonusManaPool by useSource above.
                activationCostRemaining--
            }
            return true
        }

        /**
         * Select a paid source that can be seeded from the current pool for one of [colors].
         * The caller uses this before assigning the next outer cost unit to the pool; otherwise
         * that assignment could consume the only resource that makes the paid source reachable.
         */
        fun seedableSourceForColors(colors: Iterable<Color>): Pair<ManaSource, Color>? {
            if (poolCanPayOuterCost()) return null
            val candidates = colors.flatMap { color ->
                remainingSources.mapNotNull { candidate ->
                    if (
                        candidate.availableColorsFor(spellContext).contains(color) &&
                        candidate.activationManaCostFor(color) > 0 &&
                        poolCanSeedActivation(candidate, color)
                    ) candidate to color else null
                }
            }
            return candidates.minWithOrNull(
                compareBy<Pair<ManaSource, Color>>(
                    { (candidate, _) -> calculateTapPriority(candidate, handRequirements, availableSourcesByColor) },
                    { (_, color) -> color.ordinal },
                )
            )
        }

        /** Record and consume a source whose output pays one outer mana unit. */
        fun activateSourceForPayment(
            source: ManaSource,
            colorUsed: Color?,
            outerSymbol: ManaSymbol? = null,
        ): Boolean {
            if (!prepareSourceForProduction(source, colorUsed)) return false
            manaProduced[source.entityId] = if (colorUsed != null) {
                ManaProduction(
                    color = colorUsed,
                    amount = source.manaAmount,
                    manaAbility = source.manaAbilityFor(colorUsed),
                )
            } else {
                ManaProduction(
                    colorless = source.manaAmount,
                    manaAbility = source.manaAbilityFor(null),
                )
            }
            useSource(source, colorUsed)
            if (outerSymbol != null) consumeOuterSymbol(outerSymbol)
            return true
        }

        // Helper for a colored pip that no *printed* source can produce, but an as-yet-untapped
        // source carries an aura tap-bonus that can (Fertile Ground's "one mana of any color", or a
        // fixed-color bonus matching the pip). Tapping such a source yields its printed mana PLUS the
        // bonus; both flow into the bonus pool so the printed mana stays available for later passes
        // (or floats), and one bonus is spent on this pip. Returns false when no bonus source fits.
        //
        // Without this, the colored pass — which runs before any source is tapped — sees an empty
        // bonus pool and bails on a pip like {R} when the player's only red comes from a Fertile
        // Ground forest, even though tapping that forest would produce it.
        fun payColoredPipFromAuraBonus(color: Color, outerSymbol: ManaSymbol? = null): Boolean {
            val source = remainingSources.firstOrNull { src ->
                src.bonusManaPerTap > 0 && (src.bonusManaIsAnyColor || src.bonusManaColor == color)
            } ?: return false
            val primaryColor = source.availableColorsFor(spellContext).firstOrNull()
            if (!prepareSourceForProduction(source, primaryColor)) return false
            usedSources.add(source)
            remainingSources.remove(source)
            for (c in source.producesColors) {
                availableSourcesByColor[c] = (availableSourcesByColor[c] ?: 1) - 1
            }
            // Printed mana → recorded as produced (mana-spent tally) and routed into the bonus pool
            // so the generic pass can consume it (or it floats back to the player's pool).
            if (primaryColor != null) {
                manaProduced[source.entityId] = ManaProduction(
                    color = primaryColor,
                    amount = source.manaAmount,
                    manaAbility = source.manaAbilityFor(primaryColor),
                )
                source.manaAbilityFor(primaryColor)?.let { ability ->
                    manaAbilityUses[source.entityId] = ManaAbilityUse(ability, primaryColor)
                }
                bonusManaPool.add(BonusManaEntry(primaryColor, source.manaAmount, source.restriction))
            } else {
                manaProduced[source.entityId] = ManaProduction(
                    colorless = source.manaAmount,
                    manaAbility = source.manaAbilityFor(null),
                )
                source.manaAbilityFor(null)?.let { ability ->
                    manaAbilityUses[source.entityId] = ManaAbilityUse(ability, null)
                }
            }
            // Aura bonus → bonus pool (any-color or fixed), then spend one toward this pip.
            bonusManaPool.add(
                BonusManaEntry(
                    color = source.bonusManaColor ?: Color.WHITE,
                    amount = source.bonusManaPerTap,
                    restriction = null,
                    anyColor = source.bonusManaIsAnyColor,
                    // Genuinely-extra mana, not in `manaProduced` — count it when spent.
                    countsTowardSpent = true,
                )
            )
            return spendBonusMana(color, outerSymbol = outerSymbol)
        }

        // 1. Pay colored costs first (most constrained)
        for (symbol in cost.symbols) {
            when (symbol) {
                is ManaSymbol.Colored -> {
                    val sourceCandidate = findBestSourceForColor(
                        remainingSources,
                        symbol.color,
                        handRequirements,
                        availableSourcesByColor,
                        spellContext,
                    )
                    if (
                        sourceCandidate != null &&
                        !poolCanPayOuterCost() &&
                        poolCanSeedActivation(sourceCandidate, symbol.color)
                    ) {
                        if (!activateSourceForPayment(sourceCandidate, symbol.color, symbol)) return null
                        continue
                    }
                    // A composite source such as Golgari Signet may expose the demanded color
                    // through an additional output rather than its primary color. Preserve a
                    // pool unit that is needed for that source's activation before letting the
                    // ordinary pool spend claim the colored pip.
                    if (!poolCanPayOuterCost() && payColoredPipFromAuraBonus(symbol.color, symbol)) continue
                    if (spendPoolCost(ManaCost(listOf(symbol)), spellContext, activationCost = false, outerSymbol = symbol)) continue
                    // Try bonus mana first
                    if (spendBonusMana(symbol.color, outerSymbol = symbol)) continue

                    val source = findBestSourceForColor(remainingSources, symbol.color, handRequirements, availableSourcesByColor, spellContext)
                    if (source == null) {
                        // No printed source makes this color. Fall back to an aura tap-bonus
                        // (e.g. only a Fertile Ground forest can supply the {R}).
                        if (payColoredPipFromAuraBonus(symbol.color, symbol)) continue
                        return null // Can't pay this colored cost
                    }

                    if (!activateSourceForPayment(source, symbol.color, symbol)) return null

                    // Check if the bonus mana from this source can pay remaining colored costs
                    // (handled naturally on next iteration via spendBonusMana)
                }
                is ManaSymbol.Hybrid -> {
                    // A composite source such as Golgari Signet may need the pool unit for its
                    // activation. Prefer its bonus output (or another seedable paid source) before
                    // assigning the pool unit to this flexible symbol.
                    if (!poolCanPayOuterCost()) {
                        if (payColoredPipFromAuraBonus(symbol.color1, symbol)) continue
                        if (payColoredPipFromAuraBonus(symbol.color2, symbol)) continue
                        val seeded = seedableSourceForColors(listOf(symbol.color1, symbol.color2))
                        if (seeded != null) {
                            val (source, colorUsed) = seeded
                            if (!activateSourceForPayment(source, colorUsed, symbol)) return null
                            continue
                        }
                    }
                    if (spendPoolCost(ManaCost(listOf(symbol)), spellContext, activationCost = false, outerSymbol = symbol)) continue
                    // Try bonus mana first
                    if (spendBonusMana(symbol.color1, outerSymbol = symbol)) continue
                    if (spendBonusMana(symbol.color2, outerSymbol = symbol)) continue

                    // Try first color, then second - use priority to pick the best
                    val source1 = findBestSourceForColor(remainingSources, symbol.color1, handRequirements, availableSourcesByColor, spellContext)
                    val source2 = findBestSourceForColor(remainingSources, symbol.color2, handRequirements, availableSourcesByColor, spellContext)

                    val source = when {
                        source1 == null && source2 == null -> {
                            // Neither color has a printed source; try an aura tap-bonus for either.
                            if (payColoredPipFromAuraBonus(symbol.color1, symbol)) continue
                            if (payColoredPipFromAuraBonus(symbol.color2, symbol)) continue
                            return null
                        }
                        source1 == null -> source2!!
                        source2 == null -> source1
                        else -> {
                            // Pick the source with lower priority (tap it first)
                            val priority1 = calculateTapPriority(source1, handRequirements, availableSourcesByColor) +
                                painPenalty(source1, source1.colorPainCost[symbol.color1] ?: 0)
                            val priority2 = calculateTapPriority(source2, handRequirements, availableSourcesByColor) +
                                painPenalty(source2, source2.colorPainCost[symbol.color2] ?: 0)
                            if (priority1 <= priority2) source1 else source2
                        }
                    }

                    val availableColors = source.availableColorsFor(spellContext)
                    val colorUsed = if (availableColors.contains(symbol.color1))
                        symbol.color1 else symbol.color2
                    if (!activateSourceForPayment(source, colorUsed, symbol)) return null
                }
                is ManaSymbol.Phyrexian -> {
                    val sourceCandidate = seedableSourceForColors(listOf(symbol.color))
                    if (sourceCandidate != null) {
                        if (!activateSourceForPayment(sourceCandidate.first, sourceCandidate.second, symbol)) return null
                        continue
                    }
                    if (spendPoolCost(ManaCost(listOf(symbol)), spellContext, activationCost = false, outerSymbol = symbol)) continue
                    // Try bonus mana first
                    if (spendBonusMana(symbol.color, outerSymbol = symbol)) continue

                    // For now, always pay with mana (not life)
                    val source = findBestSourceForColor(remainingSources, symbol.color, handRequirements, availableSourcesByColor, spellContext)
                    if (source == null) {
                        if (payColoredPipFromAuraBonus(symbol.color, symbol)) continue
                        return null
                    }

                    if (!activateSourceForPayment(source, symbol.color, symbol)) return null
                }
                is ManaSymbol.Colorless -> {
                    val sourceCandidate = remainingSources
                        .filter { it.producesColorless }
                        .minByOrNull {
                            calculateTapPriority(it, handRequirements, availableSourcesByColor) +
                                painPenalty(it, it.colorlessPainCost)
                        }
                    if (
                        sourceCandidate != null &&
                        !poolCanPayOuterCost() &&
                        poolCanSeedActivation(sourceCandidate, null)
                    ) {
                        if (!activateSourceForPayment(sourceCandidate, null, symbol)) return null
                        continue
                    }
                    if (spendPoolCost(ManaCost(listOf(symbol)), spellContext, activationCost = false, outerSymbol = symbol)) continue
                    // A floated colorless bonus (e.g. the second {C} from a Sol Ring already
                    // tapped for an earlier pip) pays this {C} without tapping another source.
                    if (spendColorlessBonusMana(outerSymbol = symbol)) continue

                    // Must pay with actual colorless mana (from Wastes, etc.)
                    // Sort colorless sources by priority
                    val source = remainingSources
                        .filter { it.producesColorless }
                        .minByOrNull {
                            calculateTapPriority(it, handRequirements, availableSourcesByColor) +
                                painPenalty(it, it.colorlessPainCost)
                        }
                        ?: return null

                    if (!activateSourceForPayment(source, null, symbol)) return null
                }
                is ManaSymbol.MonocolorHybrid -> {
                    // Handle in pass 1a below, after all strict colored pips have claimed sources.
                }
                is ManaSymbol.Generic, is ManaSymbol.X -> {
                    // Handle in the generic pass below
                }
            }
        }

        // 1a. Pay monocolored hybrid costs ({2/B}). Prefer one mana of the color (one tap vs the
        //     generic amount); fall back to the generic side, paid in the generic pass below. This
        //     runs after pass 1 so strict pips reserve their colored sources first.
        var monoHybridGeneric = 0
        for (symbol in cost.symbols) {
            if (symbol !is ManaSymbol.MonocolorHybrid) continue
            if (!poolCanPayOuterCost() && payColoredPipFromAuraBonus(symbol.color, symbol)) continue
            val seeded = seedableSourceForColors(listOf(symbol.color))
            if (seeded != null) {
                if (!activateSourceForPayment(seeded.first, seeded.second, symbol)) return null
                continue
            }
            val plannedAllocation = outerPoolWitness?.fixedAllocations?.firstOrNull { it.symbol == symbol }
            if (plannedAllocation != null && plannedAllocation.resources.size != 1) {
                monoHybridGeneric += symbol.generic
                convertOuterMonocolorHybridToGeneric(symbol)
                continue
            }
            if (spendPoolCost(ManaCost(listOf(symbol)), spellContext, activationCost = false, outerSymbol = symbol)) continue
            if (spendBonusMana(symbol.color, outerSymbol = symbol)) continue
            val source = findBestSourceForColor(remainingSources, symbol.color, handRequirements, availableSourcesByColor, spellContext)
            if (source != null) {
                if (!activateSourceForPayment(source, symbol.color, symbol)) return null
            } else {
                monoHybridGeneric += symbol.generic
                convertOuterMonocolorHybridToGeneric(symbol)
            }
        }

        // 1c. Pay the color-restricted X portion ("spend only [colors] on X"), if any.
        //     Runs before the generic pass so unrestricted generic mana isn't consumed by a
        //     source that could have paid the restricted X. Only sources able to produce one
        //     of the allowed colors are eligible; the per-color amount is reported back so the
        //     payment path can expose it via DynamicAmount.ManaSpentOnX.
        val xRestrictedSpent = mutableMapOf<Color, Int>()
        if (xManaRestriction.isNotEmpty() && xValue > 0) {
            // Spend bonus mana of an allowed color (or an any-color bonus) toward X.
            fun spendBonusManaOnX(): Color? {
                val eligible: (BonusManaEntry) -> Boolean = { entry ->
                    entry.restriction == null || spellContext?.let(entry.restriction::isSatisfiedBy) == true
                }
                val idx = bonusManaPool.indexOfFirst {
                    it.amount > 0 && eligible(it) && (it.color in xManaRestriction || it.anyColor)
                }
                if (idx < 0) return null
                val entry = bonusManaPool[idx]
                val color = if (entry.color in xManaRestriction) entry.color else xManaRestriction.first()
                bonusManaPool[idx] = entry.copy(amount = entry.amount - 1)
                return color
            }
            var xRemaining = xValue
            while (xRemaining > 0) {
                val poolCanPay = poolCanPayOuterCost()
                if (!poolCanPay) {
                    val seeded = seedableSourceForColors(xManaRestriction)
                    if (seeded != null) {
                        if (!activateSourceForPayment(seeded.first, seeded.second)) return null
                        consumeOuterRestrictedX()
                        xRestrictedSpent[seeded.second] = (xRestrictedSpent[seeded.second] ?: 0) + 1
                        xRemaining--
                        continue
                    }
                }
                val plannedXColor = spendPlannedRestrictedX()
                if (plannedXColor != null) {
                    consumeOuterRestrictedX()
                    xRestrictedSpent[plannedXColor] = (xRestrictedSpent[plannedXColor] ?: 0) + 1
                    xRemaining--
                    continue
                }
                val restrictedPoolColor = spendRestrictedManaForX()
                if (restrictedPoolColor != null) {
                    consumeOuterRestrictedX()
                    xRestrictedSpent[restrictedPoolColor] = (xRestrictedSpent[restrictedPoolColor] ?: 0) + 1
                    xRemaining--
                    continue
                }
                val poolUnit = availableManaPool?.xCoveragePlan(1, xManaRestriction)?.firstOrNull()
                val poolPaid = when (poolUnit) {
                    null -> xManaRestriction.isEmpty() &&
                        spendPoolCost(ManaCost(listOf(ManaSymbol.Colorless)), spellContext, activationCost = false)
                    else -> spendPoolCost(
                        ManaCost(listOf(ManaSymbol.Colored(poolUnit))),
                        spellContext,
                        activationCost = false,
                    )
                }
                if (poolPaid) {
                    if (poolUnit != null) {
                        consumeOuterRestrictedX()
                        xRestrictedSpent[poolUnit] = (xRestrictedSpent[poolUnit] ?: 0) + 1
                    }
                    xRemaining--
                    continue
                }
                val bonusColor = spendBonusManaOnX()
                if (bonusColor != null) {
                    consumeOuterRestrictedX()
                    xRestrictedSpent[bonusColor] = (xRestrictedSpent[bonusColor] ?: 0) + 1
                    xRemaining--
                    continue
                }
                val source = remainingSources
                    .filter { src -> src.availableColorsFor(spellContext).any { it in xManaRestriction } }
                    .minByOrNull { calculateTapPriority(it, handRequirements, availableSourcesByColor) }
                    ?: return null // Can't pay X with the allowed colors
                val colorToUse = source.availableColorsFor(spellContext).first { it in xManaRestriction }
                if (!activateSourceForPayment(source, colorToUse)) return null
                consumeOuterRestrictedX()
                xRestrictedSpent[colorToUse] = (xRestrictedSpent[colorToUse] ?: 0) + 1
                xRemaining--
            }
        }

        // 2. Pay generic costs (and unrestricted X), using bonus mana first.
        // xValue here is the total extra generic mana needed for X (callers handle XX multiplication).
        // When X is color-restricted it was already paid by pass 1c above, so it's excluded here.
        var genericRemaining = cost.genericAmount + monoHybridGeneric +
            (if (xManaRestriction.isEmpty()) xValue else 0)

        fun cheapestGenericColor(source: ManaSource): Color? {
            fun coloredExtraCost(color: Color): Int =
                (source.colorPainCost[color] ?: 0) + (source.colorActivationManaCost[color] ?: 0)
            val cheapestColor = source.availableColorsFor(spellContext)
                .ifEmpty { source.producesColors }
                .minByOrNull(::coloredExtraCost)
            return when {
                cheapestColor == null -> null
                source.producesColorless &&
                    coloredExtraCost(cheapestColor) >
                        source.colorlessPainCost + source.colorlessActivationManaCost -> null
                else -> cheapestColor
            }
        }

        while (genericRemaining > 0) {
            // Try to spend bonus mana first
            if (spendAnyBonusMana(outerSymbol = genericManaUnit.symbols.single())) {
                genericRemaining--
                continue
            }

            // If this pool cannot cover the complete outer cost, preserve a pool unit that can
            // seed a paid source. This is the generic-cost counterpart of the colored-pip
            // look-ahead above (for example, pool {C} + Signet + outer {2}).
            if (!poolCanPayOuterCost()) {
                val paidSource = remainingSources
                    .asSequence()
                    .mapNotNull { candidate ->
                        val colorToUse = cheapestGenericColor(candidate)
                        if (
                            (colorToUse != null || candidate.producesColorless) &&
                            candidate.activationManaCostFor(colorToUse) > 0 &&
                            poolCanSeedActivation(candidate, colorToUse)
                        ) candidate to colorToUse else null
                    }
                    .minByOrNull { (candidate, _) ->
                        calculateTapPriority(candidate, handRequirements, availableSourcesByColor)
                    }
                if (paidSource != null) {
                    val (source, colorToUse) = paidSource
                    if (!activateSourceForPayment(source, colorToUse, genericManaUnit.symbols.single())) return null
                    genericRemaining--
                    continue
                }
            }

            if (spendPoolCost(
                    genericManaUnit,
                    spellContext,
                    activationCost = false,
                    outerSymbol = genericManaUnit.symbols.single(),
                )
            ) {
                genericRemaining--
                continue
            }

            if (remainingSources.isEmpty()) {
                return null // Not enough mana
            }

            // Check if single-mana sources alone can cover the remaining generic cost.
            // If not, prefer multi-mana sources for efficiency (fewer taps overall).
            val singleManaCount = remainingSources.count { it.manaAmount == 1 }
            val needMultiMana = singleManaCount < genericRemaining

            val source = if (needMultiMana) {
                // Not enough single-mana sources — prefer multi-mana for efficiency
                remainingSources.minByOrNull { source ->
                    val basePriority = calculateTapPriority(source, handRequirements, availableSourcesByColor)
                    val savedTaps = minOf(source.manaAmount, genericRemaining) - 1
                    basePriority - savedTaps * 25
                }
            } else {
                // Enough single-mana sources — use normal priority (preserve multi-mana creatures for attacks)
                remainingSources.minByOrNull { calculateTapPriority(it, handRequirements, availableSourcesByColor) }
            } ?: return null

            // For generic costs any mana works, so pick the cheapest production: prefer
            // unrestricted colors, then colorless — but never pay a color's extra cost
            // (pain or activation mana) when a cheaper colorless ability exists. Without
            // this, a Starting Town tapped for generic would route through "{T}, Pay
            // 1 life: Add one mana of any color" instead of its free "{T}: Add {C}".
            val colorToUse = cheapestGenericColor(source)
            if (!activateSourceForPayment(source, colorToUse, genericManaUnit.symbols.single())) return null
            genericRemaining--
        }

        // A List, not a Set: multiplicity is load-bearing. Two rider-carrying sources spent on
        // one spell fire the rider twice (Pyromancer's Goggles: "That many copies will be
        // created"), so identical riders must not collapse.
        val consumedRiders: List<ManaSpellRider> = usedSources.flatMap { source ->
            val color = manaProduced[source.entityId]?.color ?: return@flatMap emptyList()
            source.colorRiders[color]?.toList() ?: emptyList()
        }
        if (!hasAffordablePayLifeTotal(
                state,
                playerId,
                usedSources,
                manaProduced,
                manaAbilityUses,
                additionalPayLife,
            )
        ) {
            return null
        }
        val productionSnapshots = usedSources.associate { it.entityId to it.sourceSubtypes }
        val authoritativeManaProduced = manaProduced.mapValues { (sourceId, production) ->
            production.copy(sourceSubtypes = productionSnapshots[sourceId])
        }
        return ManaSolution(
            usedSources,
            authoritativeManaProduced,
            bonusManaPool.filter { it.amount > 0 },
            consumedRiders,
            xRestrictedManaSpent = xRestrictedSpent,
            bonusManaSpentByColor = bonusManaSpentByColor,
            manaAbilityUses = manaAbilityUses,
            poolAfterPayment = availableManaPool,
            poolAfterActivation = poolAfterActivation,
            poolManaSpentForActivation = poolManaSpentForActivation,
            poolManaSpentForOuter = poolManaSpentForOuter,
        )
    }

    /**
     * A solver candidate is one activation/payment operation, so every selected mana ability's
     * PayLife atoms must be resolved against the same pre-payment life total before the candidate is
     * advertised. [findAvailableManaSources] intentionally checks individual abilities only; this
     * final check covers a solution that combines several individually payable painful sources.
     */
    private fun hasAffordablePayLifeTotal(
        state: GameState,
        playerId: EntityId,
        sources: List<ManaSource>,
        manaProduced: Map<EntityId, ManaProduction>,
        manaAbilityUses: Map<EntityId, ManaAbilityUse>,
        additionalPayLife: Int,
    ): Boolean {
        if (additionalPayLife < 0) return false
        if (additionalPayLife > state.lifeTotal(playerId)) return false
        var total = additionalPayLife
        for (source in sources) {
            val production = manaProduced[source.entityId]
            // Sources tapped only to pay another mana ability's activation mana cost have no
            // production entry, so only exact solver provenance can identify their selected
            // ability. An absent entry means the source supplied intrinsic mana and has no
            // scripted cost to resolve; guessing from a source color would silently select the
            // wrong ability on multi-ability permanents.
            val ability = manaAbilityUses[source.entityId]?.ability
                ?: production?.manaAbility
                ?: if (production != null) source.manaAbilityFor(production.color) else null
            if (ability == null) continue
            val amount = CostAmountResolver.resolvePayLifeTotal(
                state = state,
                amounts = CostAmountResolver.payLifeAmounts(ability.cost),
                sourceId = source.entityId,
                controllerId = playerId,
                cardRegistry = cardRegistry,
            ) ?: return false
            if (amount > Int.MAX_VALUE - total) return false
            total += amount
        }
        return total <= state.lifeTotal(playerId)
    }

    /**
     * Calculates the tap priority for a mana source (lower = tap first).
     *
     * Priority order (tap first to last):
     * 1. Basic lands (priority ~0-1)
     * 2. Non-basic single-color lands without abilities (~2)
     * 3. Dual/tri-lands without abilities (~3-5)
     * 4. Utility lands with non-mana abilities (~10-14)
     * 5. Pain lands (~16+)
     * 6. Mana creatures that can attack (~20+)
     * 7. Five-color lands (~25+)
     */
    private fun calculateTapPriority(
        source: ManaSource,
        handRequirements: Map<Color, Int>,
        availableSourcesByColor: Map<Color, Int>
    ): Int {
        var priority = 0

        // Base: color flexibility (0-10 based on color count)
        priority += when (source.producesColors.size) {
            0 -> 1      // Colorless-only
            1 -> 0      // Single color - tap first
            2 -> 3      // Dual land
            3 -> 4      // Tri-land
            4 -> 5      // Four-color
            else -> 10  // Five-color - tap last
        }

        // Prefer basics (+2 penalty for non-basics)
        if (!source.isBasicLand && source.producesColors.isNotEmpty()) {
            priority += 2
        }

        // Preserve utility lands (+10 for non-mana abilities)
        if (source.hasNonManaAbilities) {
            priority += 10
        }

        // Avoid pain lands (+15 + pain amount)
        if (source.hasPainCost) {
            priority += 15 + source.painAmount
        }

        // Preserve attackers (+20 for creatures that can attack)
        if (source.isCreature && source.canAttack) {
            priority += 20
        }

        // Hand awareness: penalize tapping sources for colors we need in hand
        // but have limited supply of
        for (color in source.producesColors) {
            val required = handRequirements[color] ?: 0
            val available = availableSourcesByColor[color] ?: 0

            // If tapping this would leave us short for hand cards, add penalty
            if (required > 0 && available <= required) {
                priority += 5  // Medium penalty - still allow if necessary
            }
        }

        return priority
    }

    /**
     * Analyzes cards in hand and returns required color counts.
     * Returns map of Color -> minimum sources needed to cast the most color-demanding card of that color.
     */
    private fun analyzeHandRequirements(state: GameState, playerId: EntityId): Map<Color, Int> {
        val handZone = ZoneKey(playerId, Zone.HAND)
        val handCards = state.getZone(handZone)

        val colorRequirements = mutableMapOf<Color, Int>()

        for (entityId in handCards) {
            val container = state.getEntity(entityId) ?: continue
            val card = container.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue

            // Get mana cost
            val manaCost = cardDef.manaCost

            // Count colored symbols per color in this card's cost
            val cardColorCounts = mutableMapOf<Color, Int>()
            for (symbol in manaCost.symbols) {
                when (symbol) {
                    is ManaSymbol.Colored -> {
                        cardColorCounts[symbol.color] = (cardColorCounts[symbol.color] ?: 0) + 1
                    }
                    // Hybrid symbols don't strictly require either color
                    else -> {}
                }
            }

            // Update max requirements per color
            for ((color, count) in cardColorCounts) {
                val current = colorRequirements[color] ?: 0
                colorRequirements[color] = maxOf(current, count)
            }
        }

        return colorRequirements
    }

    /**
     * Finds all untapped mana sources controlled by a player.
     * Supports:
     * - Basic lands and lands with basic land subtypes
     * - Non-land permanents with explicit tap mana abilities (mana dorks, mana rocks)
     * - Respects summoning sickness for creatures (unless they have haste)
     *
     * Populates smart-tapping metadata for each source:
     * - isBasicLand: true for basic land types
     * - isCreature: true for creatures
     * - hasNonManaAbilities: true if the source has activated abilities that aren't mana abilities
     * - hasPainCost/painAmount: true if the mana ability costs life
     * - canAttack: true for creatures that can attack (no summoning sickness or has haste)
     */
    fun findAvailableManaSources(
        state: GameState,
        playerId: EntityId,
        spellContext: SpellPaymentContext? = null,
    ): List<ManaSource> {
        // Project state once to get all keywords and projected controllers
        val projected = state.projectedState

        // Collect every mana-relevant battlefield static once, rather than re-walking the
        // battlefield inside each of the five per-source helpers below (see ManaStaticsIndex).
        //
        // Lazily, and that matters: this function is called on every affordability check, and a
        // player who is tapped out has no candidate source at all, so the helpers below never run.
        // Building eagerly would charge a battlefield walk to exactly the calls that used to do no
        // scanning whatsoever — measurably the wrong trade in a benchmark full of tapped-out
        // windows. NONE is safe because a solve never leaves the calling thread.
        val manaStatics by lazy(LazyThreadSafetyMode.NONE) { ManaStaticsIndex.build(state, cardRegistry) }

        // Use projected controller to find all permanents controlled by this player
        // (accounts for control-changing effects like Annex)
        val battlefieldCards = projected.getBattlefieldControlledBy(playerId)

        return battlefieldCards.mapNotNull { entityId ->
            val container = state.getEntity(entityId) ?: return@mapNotNull null

            // Must be untapped
            if (container.has<TappedComponent>()) return@mapNotNull null

            val card = container.get<CardComponent>() ?: return@mapNotNull null

            // Check for explicit mana abilities via CardRegistry
            val cardDef = cardRegistry.getCard(card.cardDefinitionId)
            // Suppress the card's own activated abilities when projection has stripped them
            // (e.g., Noggle the Mind / Humility / Deep Freeze). Granted abilities are kept.
            val allAbilities = if (cardDef == null || projected.hasLostAllAbilities(entityId)) emptyList()
                else cardDef.script.activatedAbilities

            // Include mana abilities granted by static effects from other permanents
            // (e.g., Clement, the Worrywort granting {T}: Add {G} or {U} to Frogs)
            val staticGrantedManaAbilities = getStaticGrantedManaAbilities(entityId, state, manaStatics)
            val rawManaAbilities = allAbilities.filter { it.isManaAbility } + staticGrantedManaAbilities

            // When a spell/ability payment context is provided, drop mana abilities whose
            // restriction is incompatible. Otherwise the combiner below would treat a
            // source with two mutually-exclusive restricted abilities (e.g. Steelswarm
            // Operator's "spells only" + "abilities only" variants of
            // CardTypeSpellsOrAbilitiesOnly) as unrestricted and over-produce mana for the
            // actual spend.
            val manaAbilities = if (spellContext != null) {
                rawManaAbilities.filter { ability ->
                    val r = extractManaRestriction(ability.effect, state, entityId, playerId)
                    r == null || r.isSatisfiedBy(spellContext)
                }
            } else {
                rawManaAbilities
            }

            // Detect non-mana activated abilities (utility land/creature)
            val hasNonManaAbilities = allAbilities.any { !it.isManaAbility }

            // Creature and attack capability detection
            val isCreature = projected.isCreature(entityId)
            // Attack legality reads plain haste (CR 302.6 / 702.10b): an "activate as though hasty"
            // grant must NOT make this creature look like an attacker to the auto-tap heuristic.
            val hasSummoningSickness = container.has<SummoningSicknessComponent>()
            val hasHaste = projected.hasKeyword(entityId, Keyword.HASTE)
            val canAttack = isCreature && (!hasSummoningSickness || hasHaste)
            // The {T}/{Q} half of CR 302.6, which "as though those creatures had haste" does lift.
            val tapBlockedBySickness =
                SummoningSicknessRules.blocksTapOrUntapCost(entityId, container, projected)

            // Basic land detection
            val isBasicLand = card.typeLine.isBasicLand

            // For lands: check projected basic land subtypes first (Rule 305.7)
            // Basic land types grant intrinsic mana abilities, and type-changing effects
            // like Sea's Claim change what mana a land produces
            //
            // When the land carries no statically-granted mana abilities, the subtype-derived
            // intrinsic ability is its whole mana story, so we short-circuit here. When a static
            // grant is in play (e.g. Greenhouse: "Lands you control have '{T}: Add one mana of any
            // color.'"), we must NOT short-circuit — we carry the intrinsic subtype colors forward
            // to seed the combined-ability loop below so the granted ability's colors (and any
            // restrictions/riders/activation costs) fold in correctly.
            var landSubtypeSeedColors: Set<Color>? = null
            var landProductionTransformReason: String? = null
            var landHasManaColorReplacement = false
            var landOverrideColor: Color? = null
            if (card.typeLine.isLand) {
                landHasManaColorReplacement = landMatchesManaColorReplacement(state, entityId, manaStatics)
                landOverrideColor = manaStatics.landColorOverrideByTarget[entityId]
                landProductionTransformReason = when {
                    landHasManaColorReplacement ->
                        "A runtime land mana-color replacement changes intrinsic production"
                    landOverrideColor != null ->
                        "A runtime land color override changes intrinsic production"
                    else -> null
                }
            }
            if (card.typeLine.isLand) {
                val projectedSubtypes = projected.getSubtypes(entityId)
                val subtypeColors = mutableSetOf<Color>()
                if (projectedSubtypes.contains("Plains")) subtypeColors.add(Color.WHITE)
                if (projectedSubtypes.contains("Island")) subtypeColors.add(Color.BLUE)
                if (projectedSubtypes.contains("Swamp")) subtypeColors.add(Color.BLACK)
                if (projectedSubtypes.contains("Mountain")) subtypeColors.add(Color.RED)
                if (projectedSubtypes.contains("Forest")) subtypeColors.add(Color.GREEN)

                if (subtypeColors.isNotEmpty()) {
                    // An attached aura may override the produced mana color
                    // (e.g., Shimmerwilds Growth on a Mountain with Blue chosen → produces {U}).
                    // A filter-based replacement (Pulse of Llanowar) makes a matched land produce
                    // one mana of a color of its controller's choice — i.e. any of the five.
                    val overrideColor = landOverrideColor
                    val hasReplacement = landHasManaColorReplacement
                    val effectiveColors = when {
                        hasReplacement -> Color.entries.toSet()
                        overrideColor != null -> setOf(overrideColor)
                        else -> subtypeColors
                    }
                    if (staticGrantedManaAbilities.isEmpty()) {
                        val productionProfiles = effectiveColors.associate { color ->
                            ManaAbilityIdentity.intrinsic(color) to
                                (landProductionTransformReason?.let(PaymentManaProductionProfile::Unsupported)
                                    ?: PaymentManaProductionProfile.SelectableSingleOutput(
                                        setOf(PaymentManaColor.fromEngine(color))
                                    ))
                        }
                        return@mapNotNull ManaSource(
                            entityId = entityId,
                            name = card.name,
                            sourceSubtypes = projected.productionSourceSubtypes(entityId),
                            producesColors = effectiveColors,
                            producesColorless = false,
                            isBasicLand = isBasicLand,
                            isLand = true,
                            intrinsicManaColors = effectiveColors,
                            isCreature = isCreature,
                            hasNonManaAbilities = hasNonManaAbilities,
                            hasPainCost = false,
                            painAmount = 0,
                            canAttack = canAttack,
                            paymentManaProductionProfiles = productionProfiles,
                            paymentManaSideEffectCertificates = productionProfiles.mapValues {
                                PaymentManaSideEffectCertificate.NoSideEffect
                            },
                        )
                    }
                    landSubtypeSeedColors = effectiveColors
                }
            }

            // Collect all tap-based mana abilities to build a combined ManaSource
            val combinedColors = mutableSetOf<Color>()
            var producesColorless = false
            var maxManaAmount = 1
            // Extra mana produced by the SAME tap when one mana ability adds more than one mana of
            // different kinds via a CompositeEffect — Gruul Turf's "{T}: Add {R}{G}" and Mossfire
            // Valley's "{1}, {T}: Add {R}{G}" are `AddMana(RED).then(AddMana(GREEN))`. `producesColors`
            // models a *choice* of one color, so it can't hold "R AND G on one tap"; the additional
            // leaves are folded into the bonus-mana channel that auras already use (the solver knows
            // how to spend it — see useSource / spendBonusMana / payColoredPipFromAuraBonus). Without
            // this the second color is dropped and a spell payable only with it (Grumgully {1}{R}{G})
            // is wrongly reported unaffordable, so the client never highlights it.
            var extraBonusColor: Color? = null
            var extraBonusAmount = 0
            var extraColorlessBonus = 0
            var anyAbilityHasNoPainCost = false
            var minPainAmount = Int.MAX_VALUE
            // Track which accepted abilities required sacrificing the source (e.g. Treasure).
            // The source is marked `requiresSacrifice` only when every accepted mana ability
            // requires sacrifice — if any accepted ability is non-sac, prefer that path.
            var anyAcceptedWithSac = false
            var anyAcceptedWithoutSac = false
            // Mirror of anyAcceptedWith[out]Sac for composite tap+TapPermanents abilities
            // (Springleaf Drum). The source surfaces a non-null `tapPermanentsSubCost` only
            // when every accepted mana ability requires the secondary tap.
            var anyAcceptedWithTapPermanents = false
            var anyAcceptedWithoutTapPermanents = false
            var firstAcceptedTapPermanentsSubCost: TapPermanentsSubCost? = null
            // Track restrictions: if any ability is unrestricted, the source is unrestricted
            var hasUnrestrictedAbility = false
            var commonRestriction: ManaRestriction? = null
            var firstRestrictionSeen = false
            // Set when two abilities on this source have different non-null restrictions —
            // the cached aggregate then mis-represents what the source can produce for a
            // specific context, and the solver re-runs us with a context to disambiguate.
            var hasMixedRestrictions = false
            // Track per-color restrictions (for sources with mixed restricted/unrestricted abilities)
            val perColorRestrictions = mutableMapOf<Color, ManaRestriction?>()
            // Track the minimum mana-cost-to-activate per color (cheapest ability producing it)
            val perColorActivationCost = mutableMapOf<Color, Int>()
            // Track the minimum life cost (pain) per color, and for colorless production,
            // across the abilities producing each — see ManaSource.colorPainCost.
            val perColorPainCost = mutableMapOf<Color, Int>()
            var cheapestColorlessPain = Int.MAX_VALUE
            var cheapestColorlessActivationCost = Int.MAX_VALUE
            val perColorManaAbility = mutableMapOf<Color, ManaAbilitySelection>()
            var colorlessManaAbility: ManaAbilitySelection? = null
            val perColorManaAbilities = mutableMapOf<Color, MutableList<ActivatedAbility>>()
            val colorlessManaAbilities = mutableListOf<ActivatedAbility>()
            // Track which colors are produceable WITHOUT sacrificing the source. A color is
            // sacrifice-free if any accepted ability producing it has no SacrificeSelf cost.
            // Colors in `combinedColors` but not here can only be made by sacrificing — the
            // auto-pay solver must not pick those (see ManaSource.colorsRequiringSacrifice).
            val sacrificeFreeColors = mutableSetOf<Color>()
            // Spell riders contributed per color by abilities on this source (e.g.
            // Cavern of Souls' "Add one mana of any color" carries
            // MakesSpellUncounterable on every color it can produce, while its
            // plain `{T}: Add {C}` ability contributes nothing).
            val perColorRiders = mutableMapOf<Color, MutableSet<ManaSpellRider>>()
            val paymentProductionProfiles = linkedMapOf<String, PaymentManaProductionProfile>()
            val paymentSideEffectCertificates = linkedMapOf<String, PaymentManaSideEffectCertificate>()

            // Seed the accumulators with a basic land's intrinsic subtype mana (Rule 305.7) when a
            // static grant kept us out of the short-circuit above. The intrinsic ability is
            // unrestricted, free, sacrifice-free and rider-free; the granted abilities then add
            // their own colors/restrictions on top in the loop below.
            landSubtypeSeedColors?.let { seed ->
                combinedColors.addAll(seed)
                sacrificeFreeColors.addAll(seed)
                for (color in seed) {
                    perColorRestrictions[color] = null
                    perColorPainCost[color] = 0
                    paymentProductionProfiles[ManaAbilityIdentity.intrinsic(color)] =
                        landProductionTransformReason?.let(PaymentManaProductionProfile::Unsupported)
                            ?: PaymentManaProductionProfile.SelectableSingleOutput(
                                setOf(PaymentManaColor.fromEngine(color))
                            )
                    paymentSideEffectCertificates[ManaAbilityIdentity.intrinsic(color)] =
                        PaymentManaSideEffectCertificate.NoSideEffect
                }
                hasUnrestrictedAbility = true
            }

            for (ability in manaAbilities) {
                // Skip abilities whose activation restrictions aren't satisfied
                // (e.g., Lys Alana Dignitary's "only if there is an Elf card in your graveyard").
                if (!activationRestrictionsSatisfied(state, playerId, entityId, ability)) {
                    continue
                }

                // Detect pain cost and mana activation cost in mana abilities
                var abilityHasPainCost = false
                var abilityPainAmount = 0
                var abilityActivationManaCost = 0
                var abilityRequiresSacrifice = false
                var abilityTapPermanentsSubCost: TapPermanentsSubCost? = null
                val payLifeAmounts = CostAmountResolver.payLifeAmounts(ability.cost)
                val resolvedPayLifeTotal = if (payLifeAmounts.isEmpty()) {
                    0
                } else {
                    CostAmountResolver.resolvePayLifeTotal(
                        state = state,
                        amounts = payLifeAmounts,
                        sourceId = entityId,
                        controllerId = playerId,
                        cardRegistry = cardRegistry,
                    )
                }
                if (payLifeAmounts.isNotEmpty()) {
                    abilityHasPainCost = true
                    if (resolvedPayLifeTotal != null) {
                        abilityPainAmount = resolvedPayLifeTotal
                    }
                }
                val abilityCanBeUsed = when (val cost = ability.cost) {
                    is AbilityCost.Tap -> true
                    is AbilityCost.Atom -> when (val atom = cost.atom) {
                        is CostAtom.PayLife ->
                            resolvedPayLifeTotal != null && state.lifeTotal(playerId) >= resolvedPayLifeTotal
                        else -> false // Non-pain atom-only cost: skip like other non-tap mana abilities.
                    }
                    is AbilityCost.Composite -> {
                        var hasTap = false
                        var hasUnsupportedSubCost = resolvedPayLifeTotal == null
                        for (subCost in cost.costs) {
                            when (subCost) {
                                is AbilityCost.Tap -> hasTap = true
                                is AbilityCost.Atom -> when (val atom = subCost.atom) {
                                    is CostAtom.PayLife -> {}
                                    is CostAtom.Mana -> {
                                        abilityActivationManaCost += atom.cost.cmc
                                    }
                                    // TapPermanents as a sub-cost (Springleaf Drum:
                                    // "{T}, Tap an untapped creature you control: Add …"). Same
                                    // treatment as SacrificeSelf — auto-pay refuses to silently
                                    // consume the secondary tap target; manual menus offer the
                                    // source and the resumer prompts for the creature.
                                    is CostAtom.TapPermanents -> {
                                        abilityTapPermanentsSubCost = TapPermanentsSubCost(
                                            count = atom.count,
                                            filter = atom.filter,
                                            excludeSelf = atom.excludeSelf
                                        )
                                    }
                                    // Other choice atoms (sacrifice-something-else, discard, …) still
                                    // require explicit ActivateAbility entry.
                                    else -> hasUnsupportedSubCost = true
                                }
                                // SacrificeSelf (Treasure: "{T}, Sacrifice this artifact: Add …").
                                // Auto-tap won't pick these (filtered in solve()), but they appear
                                // in `findAvailableManaSources` so manual-selection UIs can offer
                                // them; selecting one triggers an explicit sacrifice in the resumer.
                                is AbilityCost.SacrificeSelf -> abilityRequiresSacrifice = true
                                // Other choice costs (Forage, sacrifice-something-else, etc.) still
                                // require explicit ActivateAbility entry.
                                else -> hasUnsupportedSubCost = true
                            }
                        }
                        if (abilityHasPainCost && state.lifeTotal(playerId) < abilityPainAmount) {
                            hasUnsupportedSubCost = true
                        }
                        val tapPermSubCost = abilityTapPermanentsSubCost
                        if (hasTap && !hasUnsupportedSubCost && tapPermSubCost != null) {
                            // Verify enough untapped non-source permanents are available to satisfy
                            // the secondary tap. If not, this ability is not usable right now.
                            if (!hasEnoughTapTargets(state, playerId, entityId, tapPermSubCost)) {
                                abilityTapPermanentsSubCost = null
                                false
                            } else {
                                true
                            }
                        } else {
                            hasTap && !hasUnsupportedSubCost
                        }
                    }
                    else -> false // Skip non-tap mana abilities
                }

                if (!abilityCanBeUsed) continue

                if (abilityRequiresSacrifice) anyAcceptedWithSac = true else anyAcceptedWithoutSac = true
                if (abilityTapPermanentsSubCost != null) {
                    anyAcceptedWithTapPermanents = true
                    if (firstAcceptedTapPermanentsSubCost == null) {
                        firstAcceptedTapPermanentsSubCost = abilityTapPermanentsSubCost
                    }
                } else {
                    anyAcceptedWithoutTapPermanents = true
                }

                // Check summoning sickness for creatures (non-lands)
                if (!card.typeLine.isLand && isCreature && tapBlockedBySickness) {
                    continue // Can't use this ability due to summoning sickness
                }

                // Pain modeled as a self-damage side effect is derived from the same exact
                // certificate used by PaymentPlanV1 publication. The certificate is not an
                // execution authority; the selected ActivatedAbility remains the only effect input
                // to ManaAbilitySideEffectExecutor.
                val sideEffectCertificate = PaymentManaSideEffectCertificateResolver.resolve(ability.effect)
                val effectPain = (sideEffectCertificate as? PaymentManaSideEffectCertificate.FixedSelfDamage)
                    ?.amount ?: 0
                if (effectPain > 0) {
                    abilityHasPainCost = true
                    abilityPainAmount += effectPain
                }

                if (!abilityHasPainCost) anyAbilityHasNoPainCost = true
                if (abilityHasPainCost) minPainAmount = minOf(minPainAmount, abilityPainAmount)

                // Accumulate production from effect.
                // Note: maxManaAmount tracks the GROSS mana produced per tap (not net of
                // activation cost). The solver accounts for ability activation mana costs
                // separately via colorActivationManaCost / colorlessActivationManaCost,
                // tapping additional sources to cover them.
                val effectColors = mutableSetOf<Color>()
                val manaEffect = manaProducingEffect(ability.effect, state, entityId, playerId)
                // A single tap that adds several mana of different kinds (Gruul Turf: {R}{G}). The
                // primary leaf below feeds producesColors/maxManaAmount as usual; the *additional*
                // fixed-color/colorless leaves have no home in the choice-based producesColors set,
                // so route them through the bonus-mana channel. Only unconditional AddMana/
                // AddColorlessMana leaves are folded — anything gated/choice-based stays with the
                // primary path to avoid over-counting.
                // …but only from an ability auto-pay is actually allowed to activate. The bonus-mana
                // channel carries no provenance, so a sacrifice-gated (Ancient Spring's "{T},
                // Sacrifice this land: Add {W}{B}") or tap-another-permanent ability would donate
                // free floating mana on top of the source's sacrifice-free tap — letting auto-pay
                // spend {W}{B} it never paid for. The primary leaf's color is already fenced off by
                // colorsRequiringSacrifice / tapPermanentsSubCost; the extra leaves are dropped here.
                val abilityIsAutoPayable = !abilityRequiresSacrifice && abilityTapPermanentsSubCost == null
                if (abilityIsAutoPayable && ability.effect is CompositeEffect && manaEffect is AddManaEffect) {
                    var seenPrimary = false
                    for (leaf in (ability.effect as CompositeEffect).effects) {
                        when (leaf) {
                            is AddManaEffect -> {
                                if (!seenPrimary && leaf === manaEffect) {
                                    seenPrimary = true // the primary leaf; handled by the `when` below
                                } else {
                                    extraBonusColor = extraBonusColor ?: leaf.color
                                    extraBonusAmount += (leaf.amount as? DynamicAmount.Fixed)?.amount ?: 1
                                }
                            }
                            is AddColorlessManaEffect ->
                                extraColorlessBonus += (leaf.amount as? DynamicAmount.Fixed)?.amount ?: 1
                            else -> {}
                        }
                    }
                }
                val effectRestriction: ManaRestriction? = when (val effect = manaEffect) {
                    is AddManaEffect -> {
                        combinedColors.add(effect.color)
                        effectColors.add(effect.color)
                        val manaAmount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        maxManaAmount = maxOf(maxManaAmount, manaAmount)
                        effect.restriction
                    }
                    is AddColorlessManaEffect -> {
                        producesColorless = true
                        val manaAmount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        maxManaAmount = maxOf(maxManaAmount, manaAmount)
                        effect.restriction
                    }
                    is AddManaOfChoiceEffect -> {
                        val resolved = ManaColorSetResolver.resolve(
                            colorSet = effect.colorSet,
                            state = state,
                            projected = state.projectedState,
                            sourceId = entityId,
                            controllerId = playerId,
                            cardRegistry = cardRegistry,
                        )
                        combinedColors.addAll(resolved)
                        effectColors.addAll(resolved)
                        if (resolved.isNotEmpty()) {
                            val manaAmount = evaluateManaAmount(effect.amount, state, entityId, playerId)
                            maxManaAmount = maxOf(maxManaAmount, manaAmount)
                        }
                        effect.restriction
                    }
                    is AddAnyColorManaSpendOnChosenTypeEffect -> {
                        val chosenType = state.getEntity(entityId)
                            ?.chosenCreatureType()
                        if (chosenType != null) {
                            combinedColors.addAll(Color.entries)
                            effectColors.addAll(Color.entries)
                            val manaAmount = evaluateManaAmount(effect.amount, state, entityId, playerId)
                            maxManaAmount = maxOf(maxManaAmount, manaAmount)
                            if (effect.riders.isNotEmpty()) {
                                for (color in Color.entries) {
                                    perColorRiders.getOrPut(color) { mutableSetOf() }.addAll(effect.riders)
                                }
                            }
                            ManaRestriction.SubtypeSpellsOrAbilitiesOnly(chosenType, effect.creatureOnly)
                        } else null
                    }
                    is AddDynamicManaEffect -> {
                        combinedColors.addAll(effect.allowedColors)
                        effectColors.addAll(effect.allowedColors)
                        val manaAmount = (effect.amountSource as? DynamicAmount.Fixed)?.amount ?: 1
                        maxManaAmount = maxOf(maxManaAmount, manaAmount)
                        effect.restriction
                    }
                    else -> null
                }

                paymentProductionProfiles[ManaAbilityIdentity.key(ability)] =
                    landProductionTransformReason?.let(PaymentManaProductionProfile::Unsupported)
                        ?: PaymentManaProductionProfileResolver.resolve(ability.effect, effectColors)
                paymentSideEffectCertificates[ManaAbilityIdentity.key(ability)] = sideEffectCertificate

                // Record the cheapest activation mana cost per color this ability produces.
                for (color in effectColors) {
                    perColorManaAbilities.getOrPut(color) { mutableListOf() }.add(ability)
                    val existing = perColorActivationCost[color]
                    perColorActivationCost[color] = if (existing == null) abilityActivationManaCost
                    else minOf(existing, abilityActivationManaCost)
                    val candidate = ManaAbilitySelection(
                        ability = ability,
                        activationManaCost = abilityActivationManaCost,
                        painAmount = abilityPainAmount,
                        requiresSacrifice = abilityRequiresSacrifice,
                        requiresSecondaryTap = abilityTapPermanentsSubCost != null,
                    )
                    if (isPreferredManaAbility(candidate, perColorManaAbility[color])) {
                        perColorManaAbility[color] = candidate
                    }
                }

                // Record the cheapest pain per color / for colorless this ability produces.
                for (color in effectColors) {
                    val existing = perColorPainCost[color]
                    perColorPainCost[color] = if (existing == null) abilityPainAmount
                    else minOf(existing, abilityPainAmount)
                }
                if (manaEffect is AddColorlessManaEffect) {
                    colorlessManaAbilities.add(ability)
                    cheapestColorlessPain = minOf(cheapestColorlessPain, abilityPainAmount)
                    cheapestColorlessActivationCost = minOf(
                        cheapestColorlessActivationCost,
                        abilityActivationManaCost,
                    )
                    val candidate = ManaAbilitySelection(
                        ability = ability,
                        activationManaCost = abilityActivationManaCost,
                        painAmount = abilityPainAmount,
                        requiresSacrifice = abilityRequiresSacrifice,
                        requiresSecondaryTap = abilityTapPermanentsSubCost != null,
                    )
                    if (isPreferredManaAbility(candidate, colorlessManaAbility)) {
                        colorlessManaAbility = candidate
                    }
                }

                // Record which colors this ability can produce without sacrifice.
                if (!abilityRequiresSacrifice) {
                    sacrificeFreeColors.addAll(effectColors)
                }

                // Track per-color restrictions: null means unrestricted
                for (color in effectColors) {
                    val existing = perColorRestrictions[color]
                    if (existing == null && color in perColorRestrictions) {
                        // Already have an unrestricted ability for this color — stays unrestricted
                    } else if (effectRestriction == null) {
                        // This ability is unrestricted for this color
                        perColorRestrictions[color] = null
                    } else if (existing == null && color !in perColorRestrictions) {
                        // First ability for this color — record its restriction
                        perColorRestrictions[color] = effectRestriction
                    } else if (existing != null && existing != effectRestriction) {
                        // Different restriction — collapse for the cached aggregate
                        // (player can choose which ability to activate) and flag this
                        // source as context-sensitive so solve() re-runs us with the
                        // payment context.
                        perColorRestrictions[color] = null
                        hasMixedRestrictions = true
                    }
                }

                // Track restriction for the combined source
                if (effectRestriction == null) {
                    hasUnrestrictedAbility = true
                } else if (!firstRestrictionSeen) {
                    commonRestriction = effectRestriction
                    firstRestrictionSeen = true
                } else if (commonRestriction != effectRestriction) {
                    // Different restrictions across abilities — treat as unrestricted
                    // (player can choose which ability to activate); flag for the
                    // context-aware re-solve.
                    hasUnrestrictedAbility = true
                    hasMixedRestrictions = true
                }
            }

            // If we found any usable mana abilities, return the combined source
            if (combinedColors.isNotEmpty() || producesColorless) {
                // If any ability has no pain cost, the source is not a pain source
                val hasPainCost = !anyAbilityHasNoPainCost && minPainAmount < Int.MAX_VALUE
                val painAmount = if (hasPainCost) minPainAmount else 0

                // Determine combined restriction: unrestricted if any ability is unrestricted
                val sourceRestriction = if (hasUnrestrictedAbility) null else commonRestriction

                // Build the per-color restrictions map (only include restricted colors)
                val restrictedColors = perColorRestrictions
                    .filter { (_, restriction) -> restriction != null }
                    .mapValues { (_, restriction) -> restriction!! }

                // Only record activation costs > 0 (the default is "free to produce").
                val colorActivationCosts = perColorActivationCost
                    .filter { (_, cost) -> cost > 0 }

                // Only record pain > 0 (the default is "pain-free").
                val colorPainCosts = perColorPainCost
                    .filter { (_, pain) -> pain > 0 }

                // Mark the source as sacrifice-required only when every accepted ability
                // demanded sacrifice. If any non-sac ability was accepted, that path is
                // preferred and the source is offered without sacrifice.
                val requiresSacrifice = anyAcceptedWithSac && !anyAcceptedWithoutSac

                // Colors this mixed source can only produce by sacrificing. When the whole
                // source is sacrifice-bound (requiresSacrifice == true) this stays empty — the
                // source is dropped from auto-pay entirely by the existing filter, so there's
                // no need to also tag individual colors.
                val colorsRequiringSacrifice = if (requiresSacrifice) emptySet()
                else combinedColors - sacrificeFreeColors

                // Same rule for the tap-another-permanent sub-cost: surface it only when
                // every accepted ability requires the secondary tap. If any plain mana
                // ability was accepted, that path is preferred.
                val tapPermanentsSubCost = if (anyAcceptedWithTapPermanents && !anyAcceptedWithoutTapPermanents) {
                    firstAcceptedTapPermanentsSubCost
                } else {
                    null
                }

                // A multi-mana composite tap ability contributes its extra colored mana as a
                // bonus alongside a colored source; if the source is otherwise colorless-only
                // (never happens for the two real cards, but keep it sound) the extra colored
                // leaf still needs `producesColors` non-empty so its color is spendable.
                if (extraBonusColor != null && combinedColors.isEmpty()) {
                    combinedColors.add(extraBonusColor)
                    extraBonusColor = null
                    extraBonusAmount = maxOf(0, extraBonusAmount - 1)
                }

                return@mapNotNull ManaSource(
                    entityId = entityId,
                    name = card.name,
                    sourceSubtypes = projected.productionSourceSubtypes(entityId),
                    producesColors = combinedColors,
                    producesColorless = producesColorless,
                    isBasicLand = isBasicLand,
                    isLand = card.typeLine.isLand,
                    intrinsicManaColors = landSubtypeSeedColors.orEmpty(),
                    isCreature = isCreature,
                    hasNonManaAbilities = hasNonManaAbilities,
                    hasPainCost = hasPainCost,
                    painAmount = painAmount,
                    canAttack = canAttack,
                    manaAmount = maxManaAmount,
                    bonusManaPerTap = extraBonusAmount,
                    bonusManaColor = extraBonusColor,
                    bonusManaColorlessPerTap = extraColorlessBonus,
                    restriction = sourceRestriction,
                    colorRiders = perColorRiders.mapValues { (_, v) -> v.toSet() },
                    colorRestrictions = restrictedColors,
                    colorActivationManaCost = colorActivationCosts,
                    colorlessActivationManaCost = if (
                        producesColorless && cheapestColorlessActivationCost != Int.MAX_VALUE
                    ) {
                        cheapestColorlessActivationCost
                    } else 0,
                    colorPainCost = colorPainCosts,
                    colorlessPainCost = if (producesColorless && cheapestColorlessPain != Int.MAX_VALUE) {
                        cheapestColorlessPain
                    } else 0,
                    manaAbilityForColor = perColorManaAbility.mapValues { (_, selection) -> selection.ability },
                    manaAbilityForColorless = colorlessManaAbility?.ability,
                    manaAbilityOptionsForColor = perColorManaAbilities.mapValues { (_, abilities) ->
                        abilities.distinctBy { it.id.value }
                    },
                    manaAbilityOptionsForColorless = colorlessManaAbilities.distinctBy { it.id.value },
                    paymentManaProductionProfiles = paymentProductionProfiles,
                    paymentManaSideEffectCertificates = paymentSideEffectCertificates,
                    requiresSacrifice = requiresSacrifice,
                    colorsRequiringSacrifice = colorsRequiringSacrifice,
                    hasContextSensitiveAbilities = hasMixedRestrictions,
                    tapPermanentsSubCost = tapPermanentsSubCost,
                )
            }

            // Fall back to land subtype logic for lands without explicit abilities
            // (lands without basic land subtypes that also have no explicit mana abilities
            // produce colorless mana, e.g., Wastes)
            // Skip lands that have non-mana activated abilities but no mana abilities
            // (e.g., fetch lands like Windswept Heath)
            if (!card.typeLine.isLand) return@mapNotNull null
            if (allAbilities.isNotEmpty() && manaAbilities.isEmpty()) return@mapNotNull null

            ManaSource(
                entityId = entityId,
                name = card.name,
                sourceSubtypes = projected.productionSourceSubtypes(entityId),
                producesColors = emptySet(),
                producesColorless = true,
                isBasicLand = isBasicLand,
                isLand = true,
                isCreature = false,
                hasNonManaAbilities = hasNonManaAbilities,
                hasPainCost = false,
                painAmount = 0,
                canAttack = false,
                paymentManaProductionProfiles = mapOf(
                    ManaAbilityIdentity.intrinsic(null) to
                        PaymentManaProductionProfile.SelectableSingleOutput(
                            setOf(PaymentManaColor.COLORLESS)
                        )
                ),
                paymentManaSideEffectCertificates = mapOf(
                    ManaAbilityIdentity.intrinsic(null) to PaymentManaSideEffectCertificate.NoSideEffect
                ),
            )
        }.map { source -> augmentWithAuraBonusMana(state, source, playerId, manaStatics) }
            .map { source -> augmentWithSourceTapBonusMana(state, source, playerId, manaStatics) }
            // After the bonus augmentations, and touching only `manaAmount`: a multiplier scales the
            // source's *own* mana ability, never the separate triggered mana abilities that supply
            // `bonusManaPerTap` (Virtue of Strength's rulings say so explicitly).
            .map { source -> augmentWithSourceTapManaMultiplier(state, source, manaStatics) }
            .let { sources ->
                if (hasDampLandManaProduction(state)) applyLandManaDampening(sources) else sources
            }
            .map(ManaSource::authorizePaymentManaProductionProfiles)
    }

    /**
     * Returns true when every [ActivationRestriction] on the given mana ability is currently
     * satisfied for the controller. Mirrors `CastPermissionUtils.checkActivationRestriction` but
     * is inlined here so the auto-tap solver doesn't need to depend on the legalactions module.
     */
    private fun activationRestrictionsSatisfied(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId,
        ability: ActivatedAbility
    ): Boolean {
        if (ability.restrictions.isEmpty()) return true
        return ability.restrictions.all {
            checkActivationRestriction(state, playerId, sourceId, ability, it)
        }
    }

    private fun checkActivationRestriction(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId,
        ability: ActivatedAbility,
        restriction: ActivationRestriction
    ): Boolean = when (restriction) {
        is ActivationRestriction.AnyPlayerMay -> true
        is ActivationRestriction.OnlyDuringYourTurn -> state.isActiveTurnFor(playerId)
        is ActivationRestriction.BeforeStep -> state.step.ordinal < restriction.step.ordinal
        is ActivationRestriction.DuringPhase -> state.phase == restriction.phase
        is ActivationRestriction.DuringStep -> state.step == restriction.step
        is ActivationRestriction.OnlyIfCondition -> {
            val context = EffectContext(
                sourceId = sourceId,
                controllerId = playerId,
                targets = emptyList(),
                xValue = 0
            )
            conditionEvaluator.evaluate(state, restriction.condition, context)
        }
        is ActivationRestriction.OncePerTurn -> {
            val tracker = state.getEntity(sourceId)?.get<AbilityActivatedThisTurnComponent>()
            tracker == null || !tracker.hasActivated(ability.id)
        }
        is ActivationRestriction.MaxPerTurn -> {
            val tracker = state.getEntity(sourceId)?.get<AbilityActivatedThisTurnComponent>()
            (tracker?.activationCount(ability.id) ?: 0) < restriction.count
        }
        is ActivationRestriction.Once -> {
            val tracker = state.getEntity(sourceId)?.get<AbilityActivatedEverComponent>()
            tracker == null || !tracker.hasActivated(ability.id) ||
                // An exhaust mana ability's once-only memory can be waived (Elvish Refueler), and
                // auto-tap has to agree with the enumerator about whether it may be tapped again.
                (
                    ability.isExhaust && com.wingedsheep.engine.mechanics.ExhaustActivationWaiver
                        .isWaivedFor(state, playerId, cardRegistry, conditionEvaluator)
                    )
        }
        is ActivationRestriction.ControlledSinceYourMostRecentTurn ->
            state.getEntity(sourceId)
                ?.has<com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent>() != true
        is ActivationRestriction.All -> restriction.restrictions.all {
            checkActivationRestriction(state, playerId, sourceId, ability, it)
        }
    }

    /**
     * Evaluates a DynamicAmount for a mana ability, returning the actual mana count.
     * Returns 0 when the amount evaluates to zero (e.g., no creatures of the chosen type).
     */
    /**
     * Extract the [ManaRestriction] (if any) attached to the mana-producing effect of a
     * mana ability. Used to filter abilities by spell/ability payment context before
     * combining multiple abilities on the same source.
     */
    /**
     * Unwrap an ability's effect down to the mana-producing leaf the solver should read.
     *
     * Two wrappers can hide the mana effect from the structural inspection below:
     *  - [CompositeEffect] — the mana effect sits alongside bookkeeping effects.
     *  - [GatedEffect] from `Effects`/`ConditionalEffect` — a *conditional* mana ability
     *    ("{T}: Add {G}. If you control a creature with power 4 or greater, add {G}{G} instead."
     *    — Raucous Audience) whose branch is chosen at resolution. The battlefield is stable during
     *    a single payment, so we evaluate the [Gate.WhenCondition] against the current state and read
     *    the branch that will actually run. Without this the [GatedEffect] falls through unread, the
     *    solver never sees the green, and the auto-tapper skips the source entirely.
     */
    private fun manaProducingEffect(
        effect: Effect,
        state: GameState,
        sourceId: EntityId,
        playerId: EntityId,
    ): Effect = when (effect) {
        is CompositeEffect -> effect.effects.firstOrNull {
            it is AddManaEffect ||
                it is AddColorlessManaEffect ||
                it is AddManaOfChoiceEffect ||
                it is AddAnyColorManaSpendOnChosenTypeEffect ||
                it is AddDynamicManaEffect
        } ?: effect
        is GatedEffect -> when (val gate = effect.gate) {
            is Gate.WhenCondition -> {
                val context = EffectContext(
                    sourceId = sourceId,
                    controllerId = playerId,
                    targets = emptyList(),
                    xValue = 0,
                )
                val branch = if (conditionEvaluator.evaluate(state, gate.condition, context)) effect.then
                else effect.otherwise
                branch?.let { manaProducingEffect(it, state, sourceId, playerId) } ?: effect
            }
            else -> effect
        }
        else -> effect
    }

    private fun extractManaRestriction(
        effect: Effect,
        state: GameState,
        sourceId: EntityId,
        playerId: EntityId,
    ): ManaRestriction? {
        return when (val manaEffect = manaProducingEffect(effect, state, sourceId, playerId)) {
            is AddManaEffect -> manaEffect.restriction
            is AddColorlessManaEffect -> manaEffect.restriction
            is AddManaOfChoiceEffect -> manaEffect.restriction
            is AddDynamicManaEffect -> manaEffect.restriction
            // AddAnyColorManaSpendOnChosenTypeEffect derives its restriction at resolution
            // time from the source's CastChoicesComponent, so we don't pre-filter it.
            else -> null
        }
    }

    private fun evaluateManaAmount(
        amount: DynamicAmount,
        state: GameState,
        sourceId: EntityId,
        playerId: EntityId
    ): Int {
        if (amount is DynamicAmount.Fixed) return amount.amount
        val context = EffectContext(
            sourceId = sourceId,
            controllerId = playerId,
            targets = emptyList(),
            xValue = null
        )
        return maxOf(0, dynamicAmountEvaluator.evaluate(state, amount, context))
    }

    /**
     * True if [landId] is subject to a [com.wingedsheep.sdk.scripting.ReplaceLandManaColor] static
     * (Pulse of Llanowar) — its produced mana becomes one mana of a color of its controller's
     * choice, so for solving it is treated as a five-color source. Mirrors
     * `ActivateAbilityHandler.landMatchesManaColorReplacement`.
     *
     * The statics come from [manaStatics] rather than a battlefield walk, so a board with no
     * Pulse of Llanowar answers in zero work instead of one scan per candidate source.
     */
    private fun landMatchesManaColorReplacement(
        state: GameState,
        landId: EntityId,
        manaStatics: ManaStaticsIndex
    ): Boolean {
        for (replacement in manaStatics.landColorReplacements) {
            val filterContext = PredicateContext(
                controllerId = replacement.sourceControllerId,
                sourceId = replacement.sourceId
            )
            if (predicateEvaluator.matches(
                    state, state.projectedState, landId, replacement.static.filter, filterContext
                )
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if a mana source has auras attached with AdditionalManaOnTap
     * and augments the source with bonus mana information.
     */
    private fun augmentWithAuraBonusMana(
        state: GameState,
        source: ManaSource,
        playerId: EntityId,
        manaStatics: ManaStaticsIndex
    ): ManaSource {
        val attachedBonuses = manaStatics.auraBonusManaByTarget[source.entityId] ?: return source

        var totalBonus = 0
        var bonusColor: Color? = null
        var anyColorBonus = false

        for (bonus in attachedBonuses) {
            val additionalMana = bonus.static

            val landController = state.getEntity(source.entityId)
                ?.get<ControllerComponent>()?.playerId ?: playerId

            val context = EffectContext(
                sourceId = bonus.auraId,
                controllerId = landController,
                targets = emptyList(),
                xValue = null
            )

            val amount = dynamicAmountEvaluator.evaluate(state, additionalMana.amount, context)
            if (amount > 0) {
                if (additionalMana.anyColor) {
                    // Fertile Ground: one mana of any color — treat as flexible for the solve.
                    totalBonus += amount
                    anyColorBonus = true
                } else {
                    // Resolve the color: null means "read the aura's chosen color".
                    // If no color is chosen (shouldn't happen in practice), skip.
                    val manaColor = additionalMana.color
                        ?: bonus.chosenColor
                        ?: continue
                    totalBonus += amount
                    bonusColor = manaColor
                }
            }
        }

        return if (totalBonus > 0) {
            source.copy(
                bonusManaPerTap = totalBonus,
                bonusManaColor = bonusColor,
                bonusManaIsAnyColor = anyColorBonus
            ).invalidatePaymentManaProductionProfiles(
                "AdditionalManaOnTap changes the selected source production"
            )
        } else {
            source
        }
    }

    /**
     * Augments a mana source with bonus mana from [AdditionalManaOnSourceTap]
     * statics anywhere on the battlefield. Covers both flavors:
     *  - Lavaleaper: filter = BasicLand, color = null (mirror produced color)
     *  - Badgermole Cub: filter = Creature.youControl(), color = GREEN
     *
     * Filter matching uses projected state so animated creature-lands and typeshifted
     * lands are recognised under their projected types. The static-ability source's
     * controller is also read from projected state and used as the "you" perspective
     * for the filter's controller predicate, so the "you tap" form transfers correctly
     * across control-changing effects.
     *
     * The bonus color, when mirroring, is the source's first produced color — basic
     * lands produce a single color so this is unambiguous for the canonical case;
     * multi-color producers fall back to the first listed color (the auto-tap solver
     * already preferentially picks single-color sources for colored slots).
     */
    private fun augmentWithSourceTapBonusMana(
        state: GameState,
        source: ManaSource,
        tappingPlayerId: EntityId,
        manaStatics: ManaStaticsIndex
    ): ManaSource {
        if (manaStatics.sourceTapBonuses.isEmpty()) return source

        var totalBonus = 0
        var bonusColor: Color? = null
        var totalColorlessBonus = 0

        for (entry in manaStatics.sourceTapBonuses) {
            val onSourceTap = entry.static

            // Gate on the produced-mana type. At solve time the produced type is inferred from
            // what the source can supply: COLORLESS applies only to a source that produces {C},
            // COLORED only to one that produces a color.
            when (onSourceTap.whenProducing) {
                TappedForManaType.ANY -> {}
                TappedForManaType.COLORLESS -> if (!source.producesColorless) continue
                TappedForManaType.COLORED -> if (source.producesColors.isEmpty()) continue
            }

            // Filter from the static-ability controller's perspective — see
            // AdditionalManaOnSourceTap kdoc.
            val filterContext = PredicateContext(
                controllerId = entry.sourceControllerId,
                sourceId = entry.sourceId
            )
            if (!predicateEvaluator.matches(
                    state, state.projectedState, source.entityId, onSourceTap.sourceFilter, filterContext
                )) continue

            val effectContext = EffectContext(
                sourceId = entry.sourceId,
                controllerId = tappingPlayerId,
                targets = emptyList(),
                xValue = null
            )
            val amount = dynamicAmountEvaluator.evaluate(state, onSourceTap.amount, effectContext)
            if (amount <= 0) continue

            // A colorless bonus arises when the ability adds {C}: either it's explicitly gated to
            // colorless taps, or it's a mirror (color = null) over a colorless-only source.
            val isColorlessBonus = onSourceTap.color == null &&
                (onSourceTap.whenProducing == TappedForManaType.COLORLESS || source.producesColors.isEmpty())
            if (isColorlessBonus) {
                totalColorlessBonus += amount
                continue
            }

            // Resolve the bonus color: explicit color wins; null means mirror the source's produced color.
            val resolvedColor = onSourceTap.color ?: source.producesColors.firstOrNull() ?: continue
            totalBonus += amount
            bonusColor = bonusColor ?: resolvedColor
        }

        return if (totalBonus > 0 || totalColorlessBonus > 0) {
            source.copy(
                bonusManaPerTap = source.bonusManaPerTap + totalBonus,
                bonusManaColor = source.bonusManaColor ?: bonusColor,
                bonusManaColorlessPerTap = source.bonusManaColorlessPerTap + totalColorlessBonus
            ).invalidatePaymentManaProductionProfiles(
                "AdditionalManaOnSourceTap changes the selected source production"
            )
        } else {
            source
        }
    }

    /**
     * Scales a mana source's own per-tap output by every applicable [MultiplyManaOnSourceTap]
     * (Virtue of Strength: "If you tap a basic land for mana, it produces three times as much of
     * that mana instead").
     *
     * Three deliberate narrowings, all straight from the card's rulings:
     *
     * - Only [ManaSource.manaAmount] is scaled. `bonusManaPerTap` / `bonusManaColorlessPerTap` come
     *   from *separate* triggered mana abilities (Fertile Ground, Lavaleaper), which the multiplier
     *   does not touch.
     * - The source must actually be tapped for its mana ([hasSelfTapManaAbility]); a mana ability
     *   with no `{T}` in its cost is not "tapping a permanent for mana".
     * - Several instances multiply together (two Virtues → nine times as much), so the multipliers
     *   are folded with `*`, not `+`.
     *
     * The filter is evaluated from the static's own controller, exactly as
     * [augmentWithSourceTapBonusMana] does, so `.youControl()` transfers across control changes.
     */
    private fun augmentWithSourceTapManaMultiplier(
        state: GameState,
        source: ManaSource,
        manaStatics: ManaStaticsIndex
    ): ManaSource {
        if (manaStatics.sourceTapMultipliers.isEmpty()) return source
        if (!hasSelfTapManaAbility(state, source.entityId)) return source

        var multiplier = 1
        for (entry in manaStatics.sourceTapMultipliers) {
            if (entry.static.multiplier <= 1) continue
            val filterContext = PredicateContext(
                controllerId = entry.sourceControllerId,
                sourceId = entry.sourceId
            )
            if (!predicateEvaluator.matches(
                    state, state.projectedState, source.entityId, entry.static.sourceFilter, filterContext
                )
            ) continue
            multiplier *= entry.static.multiplier
        }

        return if (multiplier > 1) {
            source.copy(manaAmount = source.manaAmount * multiplier)
                .invalidatePaymentManaProductionProfiles(
                    "MultiplyManaOnSourceTap changes the selected source production"
                )
        } else source
    }

    /**
     * True when [entityId] produces mana through an ability that taps it — either an explicit
     * activated mana ability with `{T}` in its cost, or the intrinsic `{T}: Add …` a land gets from
     * its basic land subtypes (CR 305.6), which has no printed cost to inspect.
     */
    private fun hasSelfTapManaAbility(state: GameState, entityId: EntityId): Boolean {
        val container = state.getEntity(entityId) ?: return false
        val card = container.get<CardComponent>() ?: return false
        val cardDef = cardRegistry.getCard(card.cardDefinitionId)
        val manaAbilities = cardDef?.script?.activatedAbilities?.filter { it.isManaAbility }.orEmpty()
        if (manaAbilities.any { abilityCostHasTap(it.cost) }) return true
        // No printed mana ability: a land with a basic subtype still has the intrinsic tap ability.
        return manaAbilities.isEmpty() &&
            card.typeLine.isLand &&
            state.projectedState.getSubtypes(entityId).any { it in basicLandSubtypeNames }
    }

    private fun abilityCostHasTap(cost: AbilityCost): Boolean = when (cost) {
        is AbilityCost.Tap -> true
        is AbilityCost.Composite -> cost.costs.any { abilityCostHasTap(it) }
        else -> false
    }

    /**
     * Get mana abilities granted to an entity by static abilities on battlefield permanents.
     * E.g., Clement, the Worrywort grants "{T}: Add {G} or {U}" to Frog creatures.
     *
     * The grants themselves are collected once per solve into [manaStatics] — including the
     * unlocked-Room-face ones (CR 709.5), so Greenhouse's "Lands you control have '{T}: Add one
     * mana of any color'" feeds the auto-payer only while its door is unlocked. All that is left
     * here is matching [entityId] against each grant's filter, and on a board with no such grant
     * (nearly every board) that is no work at all.
     */
    private fun getStaticGrantedManaAbilities(
        entityId: EntityId,
        state: GameState,
        manaStatics: ManaStaticsIndex
    ): List<ActivatedAbility> {
        if (manaStatics.manaAbilityGrantors.isEmpty()) return emptyList()

        val result = mutableListOf<ActivatedAbility>()
        for (grantor in manaStatics.manaAbilityGrantors) {
            val grant = grantor.grant
            if (grant.filter.excludeSelf && grantor.granterId == entityId) continue
            val matches = predicateEvaluator.matches(
                state,
                state.projectedState,
                entityId,
                grant.filter.baseFilter,
                PredicateContext(controllerId = grantor.granterControllerId, sourceId = grantor.granterId)
            )
            if (matches) {
                result.add(grant.ability)
            }
        }

        return result
    }

    /**
     * Check if any permanent on the battlefield has DampLandManaProduction.
     *
     * Deliberately *not* folded into [ManaStaticsIndex]: this runs once per
     * [findAvailableManaSources] call rather than once per candidate source, so it is O(battlefield)
     * already and was never part of the quadratic problem. It also walks a different entity set
     * (`turnOrder` × `getBattlefield(playerId)`, face-down included), and moving it would have meant
     * reconciling that difference for no measurable gain.
     */
    private fun hasDampLandManaProduction(state: GameState): Boolean {
        for (playerId in state.turnOrder) {
            for (entityId in state.getBattlefield(playerId)) {
                val card = state.getEntity(entityId)?.get<CardComponent>() ?: continue
                val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue
                if (cardDef.script.staticAbilities.any { it is DampLandManaProduction }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Apply Damping Sphere mana dampening to land sources.
     * Lands that would produce 2+ total mana are converted to produce 1 colorless instead.
     */
    private fun applyLandManaDampening(sources: List<ManaSource>): List<ManaSource> {
        return sources.map { source ->
            // Only dampen lands; non-land mana sources (mana dorks, mana rocks) are unaffected
            val totalMana = source.manaAmount + source.bonusManaPerTap
            val fixedBundleProfile = source.paymentManaProductionProfiles.values.any {
                it is PaymentManaProductionProfile.FixedOutputBundle
            }
            if (source.isLand && (totalMana >= 2 || fixedBundleProfile)) {
                val dampedSource = if (totalMana >= 2) {
                    source.copy(
                        producesColors = emptySet(),
                        producesColorless = true,
                        manaAmount = 1,
                        bonusManaPerTap = 0,
                        bonusManaColor = null
                    )
                } else {
                    // The legacy aggregate currently keeps colorless composite leaves in a
                    // separate bonus field that this total does not include. Do not refactor that
                    // auto-pay path here; conservatively keep the public plan boundary closed.
                    source
                }
                dampedSource.invalidatePaymentManaProductionProfiles(
                    "DampLandManaProduction changes the selected source production"
                )
            } else {
                source
            }
        }
    }

    /**
     * Finds the best source to produce a specific color.
     * Uses priority-based selection to pick the optimal source.
     * Respects per-color mana restrictions when a spell context is provided.
     */
    private fun findBestSourceForColor(
        sources: List<ManaSource>,
        color: Color,
        handRequirements: Map<Color, Int>,
        availableSourcesByColor: Map<Color, Int>,
        spellContext: SpellPaymentContext? = null
    ): ManaSource? {
        return sources
            .filter { it.availableColorsFor(spellContext).contains(color) }
            .minByOrNull {
                calculateTapPriority(it, handRequirements, availableSourcesByColor) +
                    painPenalty(it, it.colorPainCost[color] ?: 0)
            }
    }

    /**
     * Extra tap-priority penalty for producing mana that costs [pain] life from [source].
     * Mirrors the pain-land rule in [calculateTapPriority], which already charges a source
     * whose *every* ability pains ([ManaSource.hasPainCost]) — such sources are skipped here
     * so the penalty isn't applied twice. This covers mixed sources (a free colorless ability
     * alongside painful colored ones) where the demanded color specifically costs life.
     */
    private fun painPenalty(source: ManaSource, pain: Int): Int =
        if (pain > 0 && !source.hasPainCost) 15 + pain else 0

    /**
     * Checks if a player can pay a mana cost (from floating mana pool + auto-pay).
     * Considers floating mana first, then checks if remaining can be paid by tapping sources.
     */
    fun canPay(
        state: GameState,
        playerId: EntityId,
        cost: ManaCost,
        xValue: Int = 0,
        excludeSources: Set<EntityId> = emptySet(),
        spellContext: SpellPaymentContext? = null,
        precomputedSources: List<ManaSource>? = null,
        /** Colors that may pay the `{X}` portion ("spend only [colors] on X"); empty = any. */
        xManaRestriction: Set<Color> = emptySet(),
        /** Life already owed by another atom in this same atomic payment. */
        additionalPayLife: Int = 0
    ): Boolean {
        if (additionalPayLife < 0) return false
        if (additionalPayLife > state.lifeTotal(playerId)) return false
        // Get the player's floating mana pool
        val poolComponent = state.getEntity(playerId)?.get<ManaPoolComponent>()
        val pool = poolComponent?.toManaPool() ?: ManaPool()

        // Solve the complete payment against one shared ledger. The ledger may reserve a pool
        // unit for a paid mana source's activation before spending any source output on the outer
        // cost; a pool-first partial payment cannot represent that legal ordering.
        val xSymbolCount = cost.xCount.coerceAtLeast(1)
        val totalXMana = xValue * xSymbolCount
        if (solve(
                state = state,
                playerId = playerId,
                cost = cost,
                // solve() receives the total X allocation. The legacy fallback below already
                // multiplies the player-selected X value for costs such as {X}{X}; keep the
                // shared-ledger fast path on that same contract.
                xValue = totalXMana,
                excludeSources = excludeSources,
                spellContext = spellContext,
                precomputedSources = precomputedSources,
                xManaRestriction = xManaRestriction,
                additionalPayLife = additionalPayLife,
                initialManaPool = pool,
            ) != null
        ) return true

        // Fallback: check if "extras" — mana abilities the auto-tap solver doesn't pick — can
        // cover the remaining cost. Two flavors:
        //  1. TapPermanents (e.g. Birchlore Rangers): tap *other* permanents to produce mana.
        //  2. SacrificeSelf (e.g. Treasure tokens): a tap+sacrifice mana ability. The solver
        //     refuses to auto-tap these because paying SacrificeSelf in the auto-pay flow would
        //     mean silently losing the permanent; the player must opt-in by activating the
        //     ability directly. But the spell is still *affordable* — we just need to know it.
        //  3. Cost shapes findAvailableManaSources doesn't model at all (Ashnod's Altar's
        //     "Sacrifice a creature: Add {C}{C}" — no {T} anywhere in the cost).
        val sacrificeManaBySource = sacrificeSelfManaBySource(state, playerId)
        val bonus = calculateTapPermanentsBonusMana(state, playerId)
            .plus(sacrificeManaBySource.values.fold(TapPermanentsBonusMana()) { acc, p -> acc + p })
            .plus(calculateCompositeTapPermanentsBonusMana(state, playerId))
            .plus(calculateExplicitActivationBonusMana(state, playerId))
        if (bonus.totalMana == 0) return false

        // Allocate any-color bonus mana to the pool based on what the cost needs,
        // then re-check. This correctly handles color requirements.
        val augmentedPool = allocateAnyColorManaToPool(pool, bonus.anyColorMana, cost)
            .let { p ->
                // Also add specific-color bonus mana
                bonus.specificMana.entries.fold(p) { acc, (color, amount) -> acc.add(color, amount) }
            }
            .let { p ->
                // Also add colorless bonus mana
                if (bonus.colorlessMana > 0) p.addColorless(bonus.colorlessMana) else p
            }
        // Spending a permanent's tap+sacrifice ability uses up its {T}, so it can't also be
        // auto-tapped for the rest of the cost. Pure sacrifice sources (Treasures) are already
        // dropped by solve(); this only bites *mixed* sources like Ancient Spring, where counting
        // both abilities would claim {U} and {W}{B} from one land.
        val sacrificeConsumedIds = sacrificeManaBySource.keys
        return solve(
            state = state,
            playerId = playerId,
            cost = cost,
            // This final extras path still solves the original cost. Keep the same total-X
            // contract as the shared-ledger path above; otherwise an XX cost can re-enter the
            // fallback with only one copy of the selected X value and report a false positive.
            xValue = totalXMana,
            excludeSources = excludeSources + sacrificeConsumedIds,
            spellContext = spellContext,
            precomputedSources = precomputedSources,
            xManaRestriction = xManaRestriction,
            additionalPayLife = additionalPayLife,
            initialManaPool = augmentedPool,
        ) != null
    }

    /**
     * Gets the total available mana for a player (floating mana + untapped sources).
     *
     * When [spellContext] is provided, floating restricted mana whose restriction the context
     * satisfies is counted too (it is spendable on that payment). Without a context restricted
     * entries are ignored — the conservative choice, since eligibility can't be judged.
     */
    fun getAvailableManaCount(
        state: GameState,
        playerId: EntityId,
        precomputedSources: List<ManaSource>? = null,
        spellContext: SpellPaymentContext? = null
    ): Int {
        // Count floating mana (plus restricted entries eligible for this payment)
        val poolComponent = state.getEntity(playerId)?.get<ManaPoolComponent>()
        val floatingMana = if (poolComponent != null) {
            val eligibleRestricted = if (spellContext != null) {
                poolComponent.restrictedMana.count { it.restriction.isSatisfiedBy(spellContext) }
            } else 0
            poolComponent.white + poolComponent.blue + poolComponent.black +
                poolComponent.red + poolComponent.green + poolComponent.colorless +
                eligibleRestricted
        } else {
            0
        }

        // Add untapped mana sources (including bonus mana from auras and multi-mana sources).
        // Sacrifice-self sources (treasures) and composite tap+TapPermanents sources
        // (Springleaf Drum) are counted below via their dedicated bonus helpers, so skip
        // them here to avoid double-counting.
        val sacrificeManaBySource = sacrificeSelfManaBySource(state, playerId)
        // Enumeration caches are built without a payment context. Rebuild when a mixed source
        // collapsed mutually exclusive restricted abilities into one aggregate; single-restriction
        // sources remain safe in the cache because availableColorsFor() below applies their
        // per-color restriction to this payment.
        val cachedSources = precomputedSources
        val sources = if (spellContext != null && (
                cachedSources == null || cachedSources.any { it.hasContextSensitiveAbilities }
            )) {
            findAvailableManaSources(state, playerId, spellContext)
        } else {
            cachedSources ?: findAvailableManaSources(state, playerId)
        }
        val autoTappableSources = sources
            .filter { !it.requiresSacrifice && it.tapPermanentsSubCost == null }
            .filter { source ->
                (source.restriction == null || spellContext == null || source.restriction.isSatisfiedBy(spellContext)) &&
                    (source.producesColorless || source.availableColorsFor(spellContext).isNotEmpty())
            }
        val sourceMana = autoTappableSources
            // A *mixed* source (Ancient Spring — "{T}: Add {U}" plus "{T}, Sacrifice this land:
            // Add {W}{B}") is auto-tappable and so counted here, but its sacrifice ability is also
            // counted by the extras helper below. Both abilities spend the same {T}, so the
            // permanent yields the better of the two — never their sum.
            .sumOf { source ->
                maxOf(
                    source.manaAmount + source.bonusManaPerTap,
                    sacrificeManaBySource[source.entityId]?.totalMana ?: 0
                )
            }

        // Add extra mana from "extras" abilities the solver doesn't pick:
        //  - TapPermanents (e.g., Birchlore Rangers)
        //  - Tap+SacrificeSelf mana abilities (e.g., Treasure tokens)
        //  - Composite Tap+TapPermanents mana abilities (e.g., Springleaf Drum)
        //  - Costs with no {T} at all, which the solver doesn't model (e.g., Ashnod's Altar)
        val autoTappableIds = autoTappableSources.map { it.entityId }.toSet()
        val sacrificeExtras = sacrificeManaBySource
            .filterKeys { it !in autoTappableIds }
            .values
            .sumOf { it.totalMana }
        val extrasMana = calculateTapPermanentsBonusMana(state, playerId).totalMana +
            sacrificeExtras +
            calculateCompositeTapPermanentsBonusMana(state, playerId).totalMana +
            calculateExplicitActivationBonusMana(state, playerId).totalMana

        return floatingMana + sourceMana + extrasMana
    }

    /**
     * Bonus mana available from TapPermanents mana abilities.
     */
    internal data class TapPermanentsBonusMana(
        val anyColorMana: Int = 0,
        val specificMana: Map<Color, Int> = emptyMap(),
        val colorlessMana: Int = 0
    ) {
        val totalMana: Int get() = anyColorMana + specificMana.values.sum() + colorlessMana

        operator fun plus(other: TapPermanentsBonusMana): TapPermanentsBonusMana {
            val mergedSpecific = buildMap {
                putAll(specificMana)
                for ((color, amount) in other.specificMana) {
                    merge(color, amount, Int::plus)
                }
            }
            return TapPermanentsBonusMana(
                anyColorMana = anyColorMana + other.anyColorMana,
                specificMana = mergedSpecific,
                colorlessMana = colorlessMana + other.colorlessMana
            )
        }
    }

    /**
     * Calculates extra mana available from TapPermanents mana abilities (e.g., Birchlore Rangers).
     *
     * These abilities tap other permanents (not the source itself) to produce mana.
     * Only counts activations using permanents that are NOT already regular mana sources,
     * so this represents genuinely "extra" mana that the solver doesn't know about.
     */
    internal fun calculateTapPermanentsBonusMana(
        state: GameState,
        playerId: EntityId
    ): TapPermanentsBonusMana {
        val projected = state.projectedState
        val battlefieldCards = projected.getBattlefieldControlledBy(playerId)
        val regularSourceIds = findAvailableManaSources(state, playerId).map { it.entityId }.toSet()

        var anyColorTotal = 0
        val specificColorTotal = mutableMapOf<Color, Int>()
        var colorlessTotal = 0

        // Track which non-source permanents have already been "consumed" by a TapPermanents activation
        val consumedIds = mutableSetOf<EntityId>()

        for (entityId in battlefieldCards) {
            val container = state.getEntity(entityId) ?: continue
            val card = container.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue

            // Skip own abilities that have been stripped by a continuous effect (Humility, Noggle the Mind, etc.)
            if (projected.hasLostAllAbilities(entityId)) continue

            for (ability in cardDef.script.activatedAbilities) {
                if (!ability.isManaAbility) continue
                val tapCost = (ability.cost as? AbilityCost.Atom)?.atom as? CostAtom.TapPermanents ?: continue

                // Find untapped permanents matching the filter that are NOT regular mana sources
                // and haven't been consumed by another TapPermanents activation.
                // Note: TapPermanents doesn't use the {T} symbol, so summoning sickness doesn't apply.
                val context = PredicateContext(controllerId = playerId)
                val matchingNonSources = battlefieldCards.filter { targetId ->
                    targetId !in regularSourceIds &&
                    targetId !in consumedIds &&
                    state.getEntity(targetId)?.has<TappedComponent>() == false &&
                    predicateEvaluator.matches(state, projected, targetId, tapCost.filter, context)
                }

                val activationCount = matchingNonSources.size / tapCost.count
                if (activationCount == 0) continue

                // Mark consumed permanents
                val toConsume = matchingNonSources.take(activationCount * tapCost.count)
                consumedIds.addAll(toConsume)

                // Accumulate mana production
                when (val effect = ability.effect) {
                    is AddManaOfChoiceEffect -> if (effect.colorSet is ManaColorSet.AnyColor) {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        anyColorTotal += activationCount * amount
                    }
                    is AddAnyColorManaSpendOnChosenTypeEffect -> {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        anyColorTotal += activationCount * amount
                    }
                    is AddManaEffect -> {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        specificColorTotal[effect.color] =
                            (specificColorTotal[effect.color] ?: 0) + activationCount * amount
                    }
                    is AddColorlessManaEffect -> {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        colorlessTotal += activationCount * amount
                    }
                    else -> {}
                }
            }
        }

        return TapPermanentsBonusMana(anyColorTotal, specificColorTotal, colorlessTotal)
    }

    /**
     * Calculates extra mana available from mana abilities that can only be produced by activating
     * them explicitly, because [findAvailableManaSources] does not model their cost shape at all.
     *
     * That function accepts exactly three shapes — a bare `{T}`, a bare pay-life, and a composite
     * containing `{T}` whose other parts are all pay-life/mana — and drops everything else on the
     * floor (`else -> false // Skip non-tap mana abilities`). So Ashnod's Altar ("Sacrifice a
     * creature: Add {C}{C}"), a `{T}`-plus-discard ability, or anything with a Forage sub-cost is
     * invisible to the solver, and therefore to `canPay`. That mattered because ward, "counter
     * unless you pay", and "you may pay {N}" all gate the *prompt itself* on `canPay`: a player
     * whose only mana source was one of these had their spell countered without ever being asked.
     *
     * Like the other three extras helpers, this only feeds affordability. `solve()` still won't
     * auto-tap these sources — paying a sacrifice or discard sub-cost silently is not something
     * auto-pay may do — so the player activates the ability themselves, either at priority or
     * inside the payment window that
     * [com.wingedsheep.engine.mechanics.mana.ManaPaymentWindow] opens (CR 605.3a).
     *
     * Deliberately conservative: an ability is counted only when every non-mana part of its cost is
     * verifiably payable right now. Anything whose payability can't be established here (a Craft or
     * Blight cost, an `{X}`-shaped one) is skipped rather than assumed affordable.
     */
    internal fun calculateExplicitActivationBonusMana(
        state: GameState,
        playerId: EntityId
    ): TapPermanentsBonusMana {
        val projected = state.projectedState
        val manaStatics = ManaStaticsIndex.build(state, cardRegistry)
        var total = TapPermanentsBonusMana()

        for (entityId in projected.getBattlefieldControlledBy(playerId)) {
            val container = state.getEntity(entityId) ?: continue
            // Face-down permanents have no abilities (CR 708.2).
            if (container.has<FaceDownComponent>()) continue
            val card = container.get<CardComponent>() ?: continue

            val ownAbilities = if (projected.hasLostAllAbilities(entityId)) emptyList()
                else cardRegistry.getCard(card.cardDefinitionId)?.script?.activatedAbilities.orEmpty()
            val abilities = ownAbilities + getStaticGrantedManaAbilities(entityId, state, manaStatics)

            for (ability in abilities) {
                if (!ability.isManaAbility) continue
                val cost = ability.cost
                if (manaAbilityIsAlreadyCounted(cost)) continue
                // A mana sub-cost would recurse straight back into canPay, and its net production
                // is ambiguous anyway — the same call the tap+SacrificeSelf helper makes.
                if (costHasManaSubCost(cost)) continue
                if (!activationRestrictionsSatisfied(state, playerId, entityId, ability)) continue
                if (!nonManaAbilityCostIsPayable(state, playerId, entityId, cost)) continue

                total += manaProducedByEffect(ability.effect)
            }
        }

        return total
    }

    /**
     * Whether a mana ability with this cost is already reflected in the mana the affordability
     * path counts — either modelled by [findAvailableManaSources] or picked up by one of the other
     * extras helpers. Counting it again in [calculateExplicitActivationBonusMana] would inflate
     * `canPay` and `getAvailableManaCount`.
     *
     * Mirrors the accept conditions of `abilityCanBeUsed` inside [findAvailableManaSources] and the
     * cost shapes matched by [calculateTapPermanentsBonusMana], [sacrificeSelfManaBySource]
     * and [calculateCompositeTapPermanentsBonusMana]. `ManaSolverExtrasNoDoubleCountTest` pins the
     * two together: it asserts the available-mana count for a Treasure / Birchlore / Springleaf
     * board is unchanged by this helper.
     */
    private fun manaAbilityIsAlreadyCounted(cost: AbilityCost): Boolean = when (cost) {
        // Modelled by findAvailableManaSources.
        is AbilityCost.Tap -> true
        is AbilityCost.Atom -> when (cost.atom) {
            is CostAtom.PayLife -> true                 // modelled (a pain land)
            is CostAtom.TapPermanents -> true           // calculateTapPermanentsBonusMana
            else -> false
        }
        is AbilityCost.Composite -> {
            if (cost.costs.none { it is AbilityCost.Tap }) false
            else if (cost.costs.any { it is AbilityCost.SacrificeSelf }) true               // sac-self helper
            else if (cost.costs.any { (it as? AbilityCost.Atom)?.atom is CostAtom.TapPermanents }) true // composite helper
            else cost.costs.all { sub ->
                sub is AbilityCost.Tap ||
                    ((sub as? AbilityCost.Atom)?.atom.let { it is CostAtom.PayLife || it is CostAtom.Mana })
            }
        }
        else -> false
    }

    /** Whether any part of [cost] is a mana payment. */
    private fun costHasManaSubCost(cost: AbilityCost): Boolean = when (cost) {
        is AbilityCost.Atom -> cost.atom is CostAtom.Mana
        is AbilityCost.Composite -> cost.costs.any { costHasManaSubCost(it) }
        else -> false
    }

    /**
     * Whether every non-mana part of [cost] can be paid right now by [playerId] activating the
     * ability on [sourceId]. Unrecognised cost shapes report false — see the conservatism note on
     * [calculateExplicitActivationBonusMana].
     */
    private fun nonManaAbilityCostIsPayable(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId,
        cost: AbilityCost
    ): Boolean = when (cost) {
        is AbilityCost.Free -> true
        is AbilityCost.Tap -> canTapForCost(state, sourceId)
        // The source itself pays these, and it is on the battlefield by construction.
        is AbilityCost.SacrificeSelf, is AbilityCost.ExileSelf,
        is AbilityCost.ReturnSelfToHand, is AbilityCost.DiscardSelf -> true
        // Discarding an empty hand is a legal payment of "discard your hand".
        is AbilityCost.DiscardHand -> true
        is AbilityCost.SacrificeChosenCreatureType -> {
            val chosenType = state.getEntity(sourceId)?.chosenCreatureType()
            chosenType != null && controlsMatching(
                state, playerId, GameObjectFilter.Creature.withSubtype(chosenType), null, 1
            )
        }
        is AbilityCost.TapAttachedCreature -> {
            val attachedTo = state.getEntity(sourceId)?.get<AttachedToComponent>()?.targetId
            attachedTo != null && canTapForCost(state, attachedTo)
        }
        // CR 701.58a — forage: exile three cards from your graveyard, or sacrifice a Food.
        is AbilityCost.Forage -> state.getZone(ZoneKey(playerId, Zone.GRAVEYARD)).size >= 3 ||
            state.projectedState.getBattlefieldControlledBy(playerId)
                .any { state.projectedState.hasSubtype(it, Subtype.FOOD.value) }
        is AbilityCost.Atom -> CostPaymentService.canAfford(
            state = state,
            payerId = playerId,
            cost = PayCost.Atom(cost.atom),
            sourceId = sourceId,
            manaSolver = this,
            cardRegistry = cardRegistry,
            // The source pays its own cost unless the atom says otherwise (CR 601.2h) — mirrors
            // ManaAbilityEnumerator, which only excludes self for an excludeSelf sacrifice.
            excludeSource = (cost.atom as? CostAtom.Sacrifice)?.excludeSelf == true
        )
        is AbilityCost.Composite -> cost.costs.all {
            nonManaAbilityCostIsPayable(state, playerId, sourceId, it)
        }
        else -> false
    }

    /** Whether [entityId] can pay a `{T}` cost: untapped, and not a summoning-sick creature. */
    private fun canTapForCost(state: GameState, entityId: EntityId): Boolean {
        val container = state.getEntity(entityId) ?: return false
        if (container.has<TappedComponent>()) return false
        val card = container.get<CardComponent>() ?: return false
        val projected = state.projectedState
        // Summoning sickness only bites non-land creatures (CR 302.6).
        if (!card.typeLine.isLand && projected.isCreature(entityId)) {
            if (SummoningSicknessRules.blocksTapOrUntapCost(entityId, container, projected)) return false
        }
        return true
    }

    /** Whether [playerId] controls at least [count] permanents matching [filter]. */
    private fun controlsMatching(
        state: GameState,
        playerId: EntityId,
        filter: GameObjectFilter,
        excludeId: EntityId?,
        count: Int
    ): Boolean {
        val projected = state.projectedState
        val context = PredicateContext(controllerId = playerId)
        return projected.getBattlefieldControlledBy(playerId).count { id ->
            id != excludeId && predicateEvaluator.matches(state, projected, id, filter, context)
        } >= count
    }

    /**
     * Calculates extra mana available from tap+SacrificeSelf mana abilities (e.g. Treasure tokens,
     * "{T}, Sacrifice this artifact: Add one mana of any color").
     *
     * The auto-tap solver refuses to pick these sources because the SacrificeSelf sub-cost can't
     * be silently paid by the auto-pay flow — the player has to activate the ability directly so
     * the sacrifice is explicit. But the spell is still *affordable* when the player has these
     * permanents available, so `canPay` and `getAvailableManaCount` must count their production.
     *
     * Reported per permanent because both callers also count a permanent's *sacrifice-free* mana
     * ability, and the two share one {T}: a mixed source (Ancient Spring — "{T}: Add {U}" plus
     * "{T}, Sacrifice this land: Add {W}{B}") produces one or the other, never the sum.
     */
    internal fun sacrificeSelfManaBySource(
        state: GameState,
        playerId: EntityId
    ): Map<EntityId, TapPermanentsBonusMana> {
        val projected = state.projectedState
        val battlefieldCards = projected.getBattlefieldControlledBy(playerId)

        val bySource = mutableMapOf<EntityId, TapPermanentsBonusMana>()

        for (entityId in battlefieldCards) {
            val container = state.getEntity(entityId) ?: continue

            // Already tapped → can't pay the {T} sub-cost.
            if (container.has<TappedComponent>()) continue

            val card = container.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue

            // Own abilities stripped by Humility / similar — skip the printed mana ability.
            if (projected.hasLostAllAbilities(entityId)) continue

            // Summoning sickness applies to non-land creatures (CR 302.6) — they can't tap unless
            // they have haste or an "activate as though hasty" grant.
            val isCreature = projected.isCreature(entityId)
            if (!card.typeLine.isLand && isCreature &&
                SummoningSicknessRules.blocksTapOrUntapCost(entityId, container, projected)
            ) continue

            for (ability in cardDef.script.activatedAbilities) {
                if (!ability.isManaAbility) continue
                val composite = ability.cost as? AbilityCost.Composite ?: continue
                val hasTap = composite.costs.any { it is AbilityCost.Tap }
                val hasSacSelf = composite.costs.any { it is AbilityCost.SacrificeSelf }
                if (!hasTap || !hasSacSelf) continue

                // Skip abilities that bundle a mana sub-cost — the player can still afford them
                // when the pool has mana, but counting their net production here would
                // double-count and complicate color resolution. Treasure / Food (mana) / etc.
                // have a flat tap+sac shape; we cover the canonical case.
                if (composite.costs.any { it.manaCostOrNull != null }) continue

                // Honor activation restrictions (e.g. "only during your turn").
                if (!activationRestrictionsSatisfied(state, playerId, entityId, ability)) continue

                // Recurse into the effect so multi-mana sacrifice abilities expressed as a
                // CompositeEffect (e.g. Irrigation Ditch's "{T}, Sacrifice: Add {G}{U}",
                // `Effects.Composite(AddMana(GREEN), AddMana(BLUE))`) are counted in full
                // rather than dropping to the unhandled `else` branch and contributing zero.
                val produced = manaProducedByEffect(ability.effect)
                bySource[entityId] = (bySource[entityId] ?: TapPermanentsBonusMana()) + produced
            }
        }

        return bySource
    }

    /**
     * Mana produced by a single mana-ability effect, recursing into [CompositeEffect].
     *
     * Used by the "bonus mana" affordability helpers (e.g. [sacrificeSelfManaBySource])
     * so an ability that adds several mana via `Effects.Composite(AddMana(...), AddMana(...))`
     * is counted in full. Without the recursion such an effect falls into the `else` branch and
     * contributes nothing, so a spell payable only by that ability is wrongly reported
     * unaffordable (Irrigation Ditch's {G}{U} → casting Nomadic Elf, {1}{G}).
     */
    private fun manaProducedByEffect(effect: Effect): TapPermanentsBonusMana = when (effect) {
        is AddManaOfChoiceEffect ->
            if (effect.colorSet is ManaColorSet.AnyColor) {
                TapPermanentsBonusMana(anyColorMana = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1)
            } else {
                TapPermanentsBonusMana()
            }
        is AddManaEffect ->
            TapPermanentsBonusMana(
                specificMana = mapOf(effect.color to ((effect.amount as? DynamicAmount.Fixed)?.amount ?: 1))
            )
        is AddColorlessManaEffect ->
            TapPermanentsBonusMana(colorlessMana = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1)
        is CompositeEffect ->
            effect.effects.fold(TapPermanentsBonusMana()) { acc, sub -> acc + manaProducedByEffect(sub) }
        else -> TapPermanentsBonusMana()
    }

    /**
     * Whether enough untapped permanents matching [subCost] exist on the battlefield to
     * pay the secondary tap of a composite Tap+TapPermanents mana ability (Springleaf Drum).
     *
     * The source's own entity is always excluded — even when [TapPermanentsSubCost.excludeSelf]
     * is false, the source is tapped by the {T} sub-cost and so can't also satisfy the
     * "tap another permanent" half. The filter is matched via the projected state so
     * type-changing effects (Mistform Elemental, etc.) are honored.
     */
    private fun hasEnoughTapTargets(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId,
        subCost: TapPermanentsSubCost
    ): Boolean {
        val projected = state.projectedState
        val context = PredicateContext(controllerId = playerId)
        val matches = projected.getBattlefieldControlledBy(playerId).count { targetId ->
            targetId != sourceId &&
                state.getEntity(targetId)?.has<TappedComponent>() == false &&
                predicateEvaluator.matches(state, projected, targetId, subCost.filter, context)
        }
        return matches >= subCost.count
    }

    /**
     * Bonus mana available from composite Tap+TapPermanents mana abilities (Springleaf Drum:
     * "{T}, Tap an untapped creature you control: Add one mana of any color").
     *
     * `solve()` refuses to auto-tap these sources because the secondary tap requires player
     * input, but `canPay` and `getAvailableManaCount` must still count their production so
     * a ward (or other "counter unless pays") doesn't short-circuit to countered when the
     * player would actually be able to pay manually.
     *
     * Each Springleaf-like source contributes one activation, sharing a single pool of
     * untapped non-source creatures across all such activations to avoid double-counting.
     */
    internal fun calculateCompositeTapPermanentsBonusMana(
        state: GameState,
        playerId: EntityId
    ): TapPermanentsBonusMana {
        val projected = state.projectedState
        val battlefieldCards = projected.getBattlefieldControlledBy(playerId)

        var anyColorTotal = 0
        val specificColorTotal = mutableMapOf<Color, Int>()
        var colorlessTotal = 0

        val consumedIds = mutableSetOf<EntityId>()

        for (entityId in battlefieldCards) {
            val container = state.getEntity(entityId) ?: continue
            if (container.has<TappedComponent>()) continue
            val card = container.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue
            if (projected.hasLostAllAbilities(entityId)) continue

            val isCreature = projected.isCreature(entityId)
            if (!card.typeLine.isLand && isCreature &&
                SummoningSicknessRules.blocksTapOrUntapCost(entityId, container, projected)
            ) continue

            for (ability in cardDef.script.activatedAbilities) {
                if (!ability.isManaAbility) continue
                val composite = ability.cost as? AbilityCost.Composite ?: continue
                val hasTap = composite.costs.any { it is AbilityCost.Tap }
                val tapPermanentsCost = composite.costs
                    .firstNotNullOfOrNull { (it as? AbilityCost.Atom)?.atom as? CostAtom.TapPermanents }
                if (!hasTap || tapPermanentsCost == null) continue
                // Skip composites that also bundle SacrificeSelf or a mana sub-cost — those are
                // handled by other helpers (sacrificeSelfManaBySource) and would
                // double-count or complicate color resolution here.
                if (composite.costs.any { it is AbilityCost.SacrificeSelf || it.manaCostOrNull != null }) continue

                if (!activationRestrictionsSatisfied(state, playerId, entityId, ability)) continue

                val context = PredicateContext(controllerId = playerId)
                val matchingTapTargets = battlefieldCards.filter { targetId ->
                    targetId != entityId &&
                        targetId !in consumedIds &&
                        state.getEntity(targetId)?.has<TappedComponent>() == false &&
                        predicateEvaluator.matches(state, projected, targetId, tapPermanentsCost.filter, context)
                }
                if (matchingTapTargets.size < tapPermanentsCost.count) continue

                consumedIds.addAll(matchingTapTargets.take(tapPermanentsCost.count))

                when (val effect = ability.effect) {
                    is AddManaOfChoiceEffect -> if (effect.colorSet is ManaColorSet.AnyColor) {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        anyColorTotal += amount
                    }
                    is AddManaEffect -> {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        specificColorTotal[effect.color] =
                            (specificColorTotal[effect.color] ?: 0) + amount
                    }
                    is AddColorlessManaEffect -> {
                        val amount = (effect.amount as? DynamicAmount.Fixed)?.amount ?: 1
                        colorlessTotal += amount
                    }
                    else -> {}
                }
                // Each source contributes one activation per canPay query — multiple
                // mana abilities on the same Springleaf-like permanent are uncommon and
                // would share the {T} cost anyway.
                break
            }
        }

        return TapPermanentsBonusMana(anyColorTotal, specificColorTotal, colorlessTotal)
    }

    /**
     * Allocates any-color bonus mana to the pool based on what the cost needs.
     * Adds mana to colors where there's a deficit relative to the cost's colored requirements,
     * then adds the rest as colorless (usable for generic costs).
     */
    private fun allocateAnyColorManaToPool(pool: ManaPool, anyColorCount: Int, cost: ManaCost): ManaPool {
        if (anyColorCount == 0) return pool

        // Determine colored mana needed from the cost
        val colorNeeds = mutableMapOf<Color, Int>()
        for (symbol in cost.symbols) {
            when (symbol) {
                is ManaSymbol.Colored -> colorNeeds[symbol.color] = (colorNeeds[symbol.color] ?: 0) + 1
                is ManaSymbol.Phyrexian -> colorNeeds[symbol.color] = (colorNeeds[symbol.color] ?: 0) + 1
                is ManaSymbol.Hybrid -> {
                    // For hybrid, add to both colors (overestimates but correct for affordability)
                    colorNeeds[symbol.color1] = (colorNeeds[symbol.color1] ?: 0) + 1
                    colorNeeds[symbol.color2] = (colorNeeds[symbol.color2] ?: 0) + 1
                }
                is ManaSymbol.MonocolorHybrid ->
                    colorNeeds[symbol.color] = (colorNeeds[symbol.color] ?: 0) + 1
                else -> {}
            }
        }

        var result = pool
        var remaining = anyColorCount

        // Allocate to colors where pool is deficient, starting with the biggest deficit
        for ((color, needed) in colorNeeds.entries.sortedByDescending { it.value }) {
            val poolHas = result.get(color)
            val deficit = needed - poolHas
            if (deficit > 0) {
                val toAdd = minOf(remaining, deficit)
                result = result.add(color, toAdd)
                remaining -= toAdd
                if (remaining == 0) break
            }
        }

        // Remaining any-color mana goes to colorless (usable for generic costs)
        if (remaining > 0) {
            result = result.addColorless(remaining)
        }

        return result
    }
}
