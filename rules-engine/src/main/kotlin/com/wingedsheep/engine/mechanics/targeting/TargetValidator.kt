package com.wingedsheep.engine.mechanics.targeting

import com.wingedsheep.engine.handlers.SourceTypeTargeting
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.ControllerGrants
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.core.DamageRecipientKindSet
import com.wingedsheep.engine.core.PendingTargetRequirementSnapshot
import com.wingedsheep.engine.core.ResolvedTargetCount
import com.wingedsheep.engine.core.ResolvedTotalManaValueAtMost
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetRequirementInfoResult
import com.wingedsheep.engine.core.TargetRequirementUnsupportedReason
import com.wingedsheep.engine.core.hasUnresolvedDynamicMaxCount
import com.wingedsheep.engine.core.totalManaValueAtMostOrNull
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.CantBeTargetedByOpponentAbilitiesComponent
import com.wingedsheep.engine.state.components.battlefield.CastChoicesComponent
import com.wingedsheep.engine.state.components.battlefield.blightAmountChoice
import com.wingedsheep.engine.state.components.battlefield.numberChoice
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.core.AbilityFlag
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.*
import com.wingedsheep.sdk.scripting.values.ContextPropertyKey
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * The card-type names (CR 205.2a). `ProjectedState.getTypes` folds supertypes (LEGENDARY, BASIC,
 * SNOW, …) in beside them, so a "share a card type" comparison has to sieve through this — else two
 * legendary permanents would qualify by both being legendary.
 */
private val CARD_TYPE_NAMES: Set<String> = CardType.entries.mapTo(mutableSetOf()) { it.name }

/** Negative caps cannot be legal target limits; this value marks an unavailable locked cap. */
private const val UNAVAILABLE_DYNAMIC_AGGREGATE_CAP = -1

/**
 * Validates that chosen targets match their target requirements.
 *
 * This class checks if a target:
 * - Is the correct type (creature, permanent, player, etc.)
 * - Matches any filters (attacking, nonblack, you control, etc.)
 *
 * Uses PredicateEvaluator to match unified filters against game state.
 */
class TargetValidator {
    private val predicateEvaluator = PredicateEvaluator()

    /**
     * Evaluate a target aggregate cap without turning unavailable data into an unlimited cap.
     * A negative fixed value is the serialized fail-closed marker produced when announcement-time
     * locking could not resolve the dynamic expression. Valid locked caps are non-negative.
     */
    private fun evaluateAggregateCapOrNull(
        state: GameState,
        expression: DynamicAmount,
        context: EffectContext
    ): Int? {
        if (expression is DynamicAmount.Fixed && expression.amount < 0) return null
        return runCatching {
            DynamicAmountEvaluator().evaluate(state, expression, context).coerceAtLeast(0)
        }.getOrNull()
    }

    /**
     * Lock the effective target-slot counts at announcement time. Resolution must consume this
     * metadata rather than re-evaluating a dynamic/unlimited declaration against a changed board.
     */
    fun lockRequirementsForSelectedCounts(
        requirements: List<TargetRequirement>,
        selectedCounts: List<Int>
    ): List<TargetRequirement> = requirements.mapIndexed { index, requirement ->
        lockRequirement(requirement, selectedCounts.getOrNull(index) ?: requirement.count)
    }

    /**
     * Lock a flat target payload when a legacy/direct caller has not carried per-requirement
     * counts separately. The deterministic partition prefers the largest legal prefix while
     * reserving every later requirement's mandatory minimum. Normal decision continuations should
     * use [lockRequirementsForSelectedCounts], which preserves the player's exact slot response.
     */
    fun lockRequirementsForTargets(
        state: GameState,
        targets: List<ChosenTarget>,
        requirements: List<TargetRequirement>,
        casterId: EntityId,
        sourceColors: Set<Color> = emptySet(),
        sourceSubtypes: Set<String> = emptySet(),
        sourceId: EntityId? = null,
        xValue: Int? = null,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        triggeringPlayerId: EntityId? = null,
        storedCollections: Map<String, List<EntityId>> = emptyMap()
    ): List<TargetRequirement> {
        if (requirements.isEmpty()) return emptyList()
        val effectiveRequirements = snapshotDynamicCounts(
            state = state,
            requirements = requirements,
            casterId = casterId,
            sourceId = sourceId,
            xValue = xValue,
            triggeringEntityId = triggeringEntityId,
            triggeringPlayerId = triggeringPlayerId,
            storedCollections = storedCollections
        )
        // An empty payload is ambiguous at this legacy/direct boundary. Preserve a mandatory
        // requirement's locked slot count so a malformed empty payload remains a target payload
        // and CR 608.2b can reject it at resolution. Optional/up-to requirements legitimately
        // selected zero targets and therefore keep zero slots.
        val counts = if (targets.isEmpty()) {
            effectiveRequirements.map { requirement ->
                if (requirement.effectiveMinCount > 0) requirement.count else 0
            }
        } else {
            inferSelectedCounts(
                state = state,
                targets = targets,
                requirements = effectiveRequirements,
                casterId = casterId,
                sourceColors = sourceColors,
                sourceSubtypes = sourceSubtypes,
                sourceId = sourceId,
                xValue = xValue,
                targetingSourceType = targetingSourceType
            )
        }
        val locked = lockRequirementsForSelectedCounts(effectiveRequirements, counts)
        return locked.map { requirement ->
            lockDynamicAggregate(
                state = state,
                requirement = requirement,
                casterId = casterId,
                sourceId = sourceId,
                xValue = xValue,
                triggeringEntityId = triggeringEntityId,
                triggeringPlayerId = triggeringPlayerId,
                storedCollections = storedCollections
            )
        }
    }

    /**
     * Resolve board/trigger/X-dependent target-count caps at announcement time and remove the
     * dynamic expression from the stored requirement. Later target rechecks must use this exact
     * slot shape even when the board or trigger context has changed.
     */
    fun snapshotDynamicCounts(
        state: GameState,
        requirements: List<TargetRequirement>,
        casterId: EntityId,
        sourceId: EntityId? = null,
        xValue: Int? = null,
        triggeringEntityId: EntityId? = null,
        triggeringPlayerId: EntityId? = null,
        storedCollections: Map<String, List<EntityId>> = emptyMap()
    ): List<TargetRequirement> = requirements.map { requirement ->
        snapshotDynamicCount(
            state = state,
            requirement = requirement,
            casterId = casterId,
            sourceId = sourceId,
            xValue = xValue,
            triggeringEntityId = triggeringEntityId,
            triggeringPlayerId = triggeringPlayerId,
            storedCollections = storedCollections
        )
    }

    /**
     * Snapshot target counts for a pending decision without allowing unavailable context to become
     * the static SDK count. The legacy [snapshotDynamicCounts] path remains unchanged for direct
     * resolution callers; pending publication needs the typed source witness and fail-closed
     * result returned here.
     */
    internal fun snapshotDynamicCountsForPending(
        state: GameState,
        requirements: List<TargetRequirement>,
        context: EffectContext,
    ): List<PendingTargetRequirementSnapshot> = requirements.map { requirement ->
        snapshotDynamicCountForPending(
            state = state,
            requirement = requirement,
            context = context,
        )
    }

    private fun snapshotDynamicCountForPending(
        state: GameState,
        requirement: TargetRequirement,
        context: EffectContext,
    ): PendingTargetRequirementSnapshot = when (requirement) {
        is TargetObject -> {
            val expression = requirement.dynamicMaxCount
                ?: return PendingTargetRequirementSnapshot.Resolved(
                    requirement = requirement,
                    semanticSource = requirement,
                    resolvedMaxTargets = null,
                )
            val resolved = evaluateDynamicAmountForPending(state, expression, context)
                ?.coerceAtLeast(0)
            if (resolved == null) {
                PendingTargetRequirementSnapshot.Unsupported(
                    requirement = requirement,
                    semanticSource = requirement,
                    reason = TargetRequirementUnsupportedReason.UNRESOLVED_TARGET_COUNT,
                )
            } else {
                PendingTargetRequirementSnapshot.Resolved(
                    requirement = requirement.copy(
                        count = resolved,
                        minCount = requirement.minCount.coerceAtMost(resolved),
                        unlimited = false,
                        dynamicMaxCount = null,
                    ),
                    semanticSource = requirement,
                    resolvedMaxTargets = ResolvedTargetCount(resolved),
                )
            }
        }
        is TargetOther -> when (
            val base = snapshotDynamicCountForPending(
                state = state,
                requirement = requirement.baseRequirement,
                context = context,
            )
        ) {
            is PendingTargetRequirementSnapshot.Resolved ->
                PendingTargetRequirementSnapshot.Resolved(
                    requirement = requirement.copy(baseRequirement = base.requirement),
                    semanticSource = requirement,
                    resolvedMaxTargets = base.resolvedMaxTargets,
                )
            is PendingTargetRequirementSnapshot.Unsupported ->
                PendingTargetRequirementSnapshot.Unsupported(
                    requirement = requirement,
                    semanticSource = requirement,
                    reason = base.reason,
                )
        }
        else -> PendingTargetRequirementSnapshot.Resolved(
            requirement = requirement,
            semanticSource = requirement,
            resolvedMaxTargets = null,
        )
    }

    /**
     * Evaluate a dynamic source only when all pending-boundary context facts are available. The
     * generic evaluator intentionally treats absent X, cast, and pipeline values as zero for
     * legacy resolution; pending metadata cannot use those defaults as an authoritative fact.
     */
    internal fun evaluateDynamicAmountForPending(
        state: GameState,
        amount: DynamicAmount,
        context: EffectContext,
    ): Int? = if (dynamicCountContextUnavailable(state, amount, context)) {
        null
    } else {
        runCatching { DynamicAmountEvaluator().evaluate(state, amount, context) }.getOrNull()
    }

    /**
     * Resolve a source aggregate target cap for pending metadata. The nullable result is
     * intentional: no cap is represented by null, and a dynamic cap with unavailable context also
     * returns null so [TargetRequirementInfo] can reject publication rather than accept a fabricated
     * value. The typed wrapper prevents callers from confusing this witness with a plain override.
     */
    internal fun resolveTotalManaValueAtMostForPending(
        state: GameState,
        requirement: TargetRequirement,
        context: EffectContext,
    ): ResolvedTotalManaValueAtMost? = requirement.totalManaValueAtMostOrNull()
        ?.let { amount -> evaluateDynamicAmountForPending(state, amount, context) }
        ?.let(::ResolvedTotalManaValueAtMost)

    private fun dynamicCountContextUnavailable(
        state: GameState,
        amount: DynamicAmount,
        context: EffectContext,
    ): Boolean = when (amount) {
        is DynamicAmount.XValue -> context.xValue == null
        is DynamicAmount.CastX -> {
            val source = context.sourceId?.let(state::getEntity)
            context.xValue == null &&
                source?.get<CastChoicesComponent>()?.x == null &&
                source?.get<SpellOnStackComponent>()?.xValue == null
        }
        is DynamicAmount.CastChoice -> {
            val source = context.sourceId?.let(state::getEntity)
            when (amount.slot) {
                com.wingedsheep.sdk.scripting.ChoiceSlot.BLIGHT_AMOUNT ->
                    source?.blightAmountChoice() == null
                else -> source?.numberChoice(amount.slot) == null
            }
        }
        is DynamicAmount.ContextProperty -> when (amount.key) {
            ContextPropertyKey.TRIGGER_DAMAGE_AMOUNT,
            ContextPropertyKey.PREVENTED_DAMAGE_AMOUNT,
            ContextPropertyKey.TRIGGER_LIFE_GAINED,
            ContextPropertyKey.TRIGGER_LIFE_LOST -> context.triggerDamageAmount == null
            ContextPropertyKey.LAST_KNOWN_PLUS_ONE_COUNTER_COUNT,
            ContextPropertyKey.TRIGGER_COUNTERS_PLACED_AMOUNT -> context.triggerCounterCount == null
            ContextPropertyKey.LAST_KNOWN_TOTAL_COUNTER_COUNT -> context.triggerTotalCounterCount == null
            // These values are not carried by a pending-target producer's context, so zero would
            // be an invented cap rather than an authoritative snapshot.
            ContextPropertyKey.ADDITIONAL_COST_EXILED_COUNT,
            ContextPropertyKey.TARGET_COUNT -> true
            ContextPropertyKey.MODES_CHOSEN_ON_TRIGGERING_SPELL ->
                context.triggerModesChosenCount == null
            ContextPropertyKey.MANA_SPENT_ON_TRIGGERING_SPELL ->
                context.triggerManaSpentOnTriggeringSpell == null
            ContextPropertyKey.COLORS_SPENT_ON_TRIGGERING_SPELL ->
                context.triggerColorsSpentOnTriggeringSpell == null
            ContextPropertyKey.TRIGGERING_SPELL_MANA_VALUE ->
                context.triggerManaValueOfTriggeringSpell == null
            ContextPropertyKey.X_VALUE_OF_TRIGGERING_SPELL ->
                context.triggerXValueOfTriggeringSpell == null
            ContextPropertyKey.TRIGGER_SCRY_COUNT -> context.triggerScryCount == null
            ContextPropertyKey.TRIGGER_DISCARD_COUNT -> context.triggerDiscardCount == null
            ContextPropertyKey.TRIGGER_DISCOVER_VALUE -> context.triggerDiscoverValue == null
            ContextPropertyKey.TRIGGER_EXCESS_DAMAGE_AMOUNT ->
                context.triggerExcessDamageAmount == null
            ContextPropertyKey.TRIGGER_RECIPIENT_TOUGHNESS -> context.triggerRecipientToughness == null
            ContextPropertyKey.DIED_BATCH_TOTAL_POWER -> context.triggerDiedBatchTotalPower == null
            ContextPropertyKey.LINKED_EXILE_CARD_COUNT,
            ContextPropertyKey.LINKED_EXILE_DISTINCT_CARD_TYPE_COUNT -> {
                val source = context.sourceId?.let(state::getEntity)
                source?.get<com.wingedsheep.engine.state.components.battlefield.LinkedExileComponent>() == null
            }
        }
        is DynamicAmount.VariableReference -> {
            if (amount.variableName.endsWith("_count")) {
                amount.variableName.removeSuffix("_count") !in context.pipeline.storedCollections
            } else {
                amount.variableName !in context.pipeline.storedNumbers
            }
        }
        is DynamicAmount.Add ->
            dynamicCountContextUnavailable(state, amount.left, context) ||
                dynamicCountContextUnavailable(state, amount.right, context)
        is DynamicAmount.Subtract ->
            dynamicCountContextUnavailable(state, amount.left, context) ||
                dynamicCountContextUnavailable(state, amount.right, context)
        is DynamicAmount.Multiply -> dynamicCountContextUnavailable(state, amount.amount, context)
        is DynamicAmount.Power -> dynamicCountContextUnavailable(state, amount.exponent, context)
        is DynamicAmount.IfPositive -> dynamicCountContextUnavailable(state, amount.amount, context)
        is DynamicAmount.Conditional ->
            dynamicCountContextUnavailable(state, amount.ifTrue, context) ||
                dynamicCountContextUnavailable(state, amount.ifFalse, context)
        is DynamicAmount.Max ->
            dynamicCountContextUnavailable(state, amount.left, context) ||
                dynamicCountContextUnavailable(state, amount.right, context)
        is DynamicAmount.Min ->
            dynamicCountContextUnavailable(state, amount.left, context) ||
                dynamicCountContextUnavailable(state, amount.right, context)
        else -> false
    }

    private fun snapshotDynamicCount(
        state: GameState,
        requirement: TargetRequirement,
        casterId: EntityId,
        sourceId: EntityId?,
        xValue: Int?,
        triggeringEntityId: EntityId?,
        triggeringPlayerId: EntityId?,
        storedCollections: Map<String, List<EntityId>>
    ): TargetRequirement = when (requirement) {
        is TargetObject -> {
            val expression = requirement.dynamicMaxCount ?: return requirement
            val context = EffectContext(
                sourceId = sourceId,
                controllerId = casterId,
                triggeringEntityId = triggeringEntityId,
                triggeringPlayerId = triggeringPlayerId,
                xValue = xValue,
                pipeline = PipelineState(storedCollections = storedCollections)
            )
            val resolved = runCatching {
                DynamicAmountEvaluator().evaluate(state, expression, context).coerceAtLeast(0)
            }.getOrElse { requirement.count }
            requirement.copy(
                count = resolved,
                minCount = requirement.minCount.coerceAtMost(resolved),
                unlimited = false,
                dynamicMaxCount = null
            )
        }
        is TargetOther -> requirement.copy(
            baseRequirement = snapshotDynamicCount(
                state,
                requirement.baseRequirement,
                casterId,
                sourceId,
                xValue,
                triggeringEntityId,
                triggeringPlayerId,
                storedCollections
            )
        )
        else -> requirement
    }

    /** Freeze aggregate target caps alongside dynamic target counts at announcement time. */
    private fun lockDynamicAggregate(
        state: GameState,
        requirement: TargetRequirement,
        casterId: EntityId,
        sourceId: EntityId?,
        xValue: Int?,
        triggeringEntityId: EntityId?,
        triggeringPlayerId: EntityId?,
        storedCollections: Map<String, List<EntityId>>
    ): TargetRequirement = when (requirement) {
        is TargetObject -> requirement.totalManaValueAtMost?.let { expression ->
            val context = EffectContext(
                sourceId = sourceId,
                controllerId = casterId,
                triggeringEntityId = triggeringEntityId,
                triggeringPlayerId = triggeringPlayerId,
                xValue = xValue,
                pipeline = EffectContext(sourceId = sourceId, controllerId = casterId).pipeline
                    .copy(storedCollections = storedCollections)
            )
            val resolved = evaluateAggregateCapOrNull(state, expression, context)
                ?: return requirement.copy(
                    totalManaValueAtMost = DynamicAmount.Fixed(UNAVAILABLE_DYNAMIC_AGGREGATE_CAP)
                )
            requirement.copy(totalManaValueAtMost = DynamicAmount.Fixed(resolved))
        } ?: requirement
        is TargetOther -> requirement.copy(
            baseRequirement = lockDynamicAggregate(
                state,
                requirement.baseRequirement,
                casterId,
                sourceId,
                xValue,
                triggeringEntityId,
                triggeringPlayerId,
                storedCollections
            )
        )
        else -> requirement
    }

    /**
     * The payload that remains after CR 608.2b re-checks each originally locked target.
     *
     * [targets] is the compact list consumed by effects. [alignedTargets] keeps the original
     * target slots, replacing an illegal target with null, so positional references do not shift
     * onto a later survivor. The target requirements remain the original requirements: resolution
     * filters locked choices; it does not manufacture a new choice or retarget a slot.
     */
    data class ResolutionTargetPayload(
        val targets: List<ChosenTarget>,
        val alignedTargets: List<ChosenTarget?>
    )

    /**
     * Filter a locked target payload for resolution without applying cast-time cardinality rules.
     *
     * CR 608.2b removes individually-illegal targets when at least one target remains legal. The
     * announcement-time minimum/maximum and distinctness checks are deliberately not repeated here:
     * the choices are already locked, and this function only determines which of those choices can
     * still be affected. [allowedTargetSlots], when supplied by a nested queue, is the
     * position-preserving source-aware result captured by the outer resolution check; it prevents
     * a per-mode queue from re-admitting a target into a different original slot.
     */
    fun filterTargetsAtResolution(
        state: GameState,
        targets: List<ChosenTarget>,
        requirements: List<TargetRequirement>,
        casterId: EntityId,
        sourceColors: Set<Color> = emptySet(),
        sourceSubtypes: Set<String> = emptySet(),
        sourceId: EntityId? = null,
        xValue: Int? = null,
        /**
         * Position-preserving source-aware target gate captured for a nested modal/splice
         * queue. A target is allowed only when it occupies the same original slot; membership
         * anywhere in the list would let a survivor fill an earlier illegal slot.
         */
        allowedTargetSlots: List<ChosenTarget?>? = null,
        targetEntryStamps: Map<EntityId, Long> = emptyMap(),
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        triggeringPlayerId: EntityId? = null,
        defendingPlayerId: EntityId? = null,
        damageSourceId: EntityId? = null,
        damageRecipientId: EntityId? = null,
        damageRecipientKind: DamageRecipientKind = DamageRecipientKind.UNKNOWN,
        damageRecipientKinds: DamageRecipientKindSet = DamageRecipientKindSet.UNKNOWN,
        damageSourceLastKnownSnapshot: EntitySnapshot? = null,
        damageRecipientLastKnownSnapshot: EntitySnapshot? = null,
        storedCollections: Map<String, List<EntityId>> = emptyMap()
    ): ResolutionTargetPayload {
        fun malformedPayload() = ResolutionTargetPayload(
            targets = emptyList(),
            alignedTargets = List(targets.size) { null }
        )

        // A targetless requirement set is valid only when the payload is also targetless. A
        // nonempty payload with no requirements has no predicate that can prove any target legal.
        // Conversely, an empty payload with a nonzero/negative locked slot is malformed rather
        // than a targetless choice. Keep zero-count optional requirements valid.
        if (targets.isEmpty()) {
            return if (requirements.all { it.count == 0 }) {
                ResolutionTargetPayload(emptyList(), emptyList())
            } else {
                malformedPayload()
            }
        }
        if (requirements.isEmpty()) return malformedPayload()

        // The locked requirement counts define the original flat payload shape. Never truncate a
        // requirement to the number of supplied targets: a short or overlong serialized payload
        // is malformed and must not leak a prefix to an executor under CR 608.2b.
        var expectedSlots = 0
        for (requirement in requirements) {
            val slotCount = requirement.count
            if (slotCount < 0 || slotCount > targets.size - expectedSlots) return malformedPayload()
            expectedSlots += slotCount
        }
        if (expectedSlots != targets.size) return malformedPayload()

        val legal = BooleanArray(targets.size)
        var offset = 0
        for (requirement in requirements) {
            if (offset >= targets.size) break
            // Requirements stored on a stack object are already normalized by the cast/trigger
            // decision path. Do not re-evaluate dynamicMaxCount, X, or the current remaining list
            // here: doing so recomputes a locked choice after the board has changed.
            val slotCount = requirement.count
            val end = offset + slotCount
            val requirementTargets = targets.subList(offset, end)
            val relationshipError = validateRequirementRelationships(
                state = state,
                requirement = requirement,
                requirementTargets = requirementTargets,
                priorTargets = targets.subList(0, offset),
                casterId = casterId,
                sourceId = sourceId,
                xValue = xValue
            )
            for (index in offset until end) {
                val target = targets[index]
                val sourceAwareLegal = allowedTargetSlots == null ||
                    (index < allowedTargetSlots.size && allowedTargetSlots[index] == target)
                // Players do not change CR 400.7 object identity between target selection and
                // resolution. Only object/card/spell targets need the captured identity stamp;
                // applying that gate to a Player target would treat its normal absent stamp as a
                // replacement and incorrectly fizzle an otherwise legal spell.
                val sameObject = if (target is ChosenTarget.Player) {
                    true
                } else {
                    target.entityIdOrNull()?.let {
                        !TargetsComponent.isDifferentObject(state, it, targetEntryStamps)
                    } ?: true
                }
                legal[index] = relationshipError == null && sourceAwareLegal && sameObject &&
                    validateSingleTarget(
                        state = state,
                        target = target,
                        requirement = requirement,
                        casterId = casterId,
                        sourceColors = sourceColors,
                        sourceSubtypes = sourceSubtypes,
                        sourceId = sourceId,
                        xValue = xValue,
                        allTargets = targets,
                        targetingSourceType = targetingSourceType,
                        triggeringEntityId = triggeringEntityId,
                        triggeringPlayerId = triggeringPlayerId,
                        defendingPlayerId = defendingPlayerId,
                        damageSourceId = damageSourceId,
                        damageRecipientId = damageRecipientId,
                        damageRecipientKind = damageRecipientKind,
                        damageRecipientKinds = damageRecipientKinds,
                        damageSourceLastKnownSnapshot = damageSourceLastKnownSnapshot,
                        damageRecipientLastKnownSnapshot = damageRecipientLastKnownSnapshot,
                        storedCollections = storedCollections
                    ) == null
            }
            offset = end
        }

        val compact = mutableListOf<ChosenTarget>()
        val aligned = targets.mapIndexed { index, target ->
            if (legal[index]) {
                compact += target
                target
            } else {
                null
            }
        }
        return ResolutionTargetPayload(compact, aligned)
    }

    /** Normalize one requirement to the exact number of slots selected at announcement time. */
    private fun lockRequirement(requirement: TargetRequirement, count: Int): TargetRequirement {
        val lockedCount = count.coerceAtLeast(0)
        return when (requirement) {
            is TargetPlayer -> requirement.copy(
                count = lockedCount,
                optional = false,
                unlimited = false
            )
            is TargetOpponent -> requirement.copy(
                count = lockedCount,
                optional = false,
                unlimited = false
            )
            is AnyTarget -> requirement.copy(
                count = lockedCount,
                minCount = lockedCount,
                optional = false
            )
            is TargetCreatureOrPlayer -> requirement.copy(
                count = lockedCount,
                optional = false
            )
            is TargetPermanentOrPlayer -> requirement.copy(
                count = lockedCount,
                optional = false
            )
            is TargetOpponentOrPlaneswalker -> requirement.copy(
                count = lockedCount,
                optional = false
            )
            is TargetPlayerOrPlaneswalker -> requirement.copy(
                count = lockedCount,
                optional = false
            )
            is TargetCreatureOrPlaneswalker -> requirement.copy(
                count = lockedCount,
                optional = false
            )
            is TargetSpellOrPermanent -> requirement.copy(
                count = lockedCount,
                optional = false
            )
            is TargetObject -> requirement.copy(
                count = lockedCount,
                minCount = lockedCount,
                optional = false,
                unlimited = false,
                dynamicMaxCount = null
            )
            is TargetOther -> requirement.copy(
                baseRequirement = lockRequirement(requirement.baseRequirement, lockedCount)
            )
        }
    }

    /** Infer a deterministic legacy partition when a caller did not carry response slot counts. */
    private fun inferSelectedCounts(
        state: GameState,
        targets: List<ChosenTarget>,
        requirements: List<TargetRequirement>,
        casterId: EntityId,
        sourceColors: Set<Color>,
        sourceSubtypes: Set<String>,
        sourceId: EntityId?,
        xValue: Int?,
        targetingSourceType: TargetingSourceType
    ): List<Int> {
        fun effectiveMax(requirement: TargetRequirement, remaining: Int): Int {
            val objectRequirement = requirement.objectRequirement()
            if (objectRequirement != null) {
                val dynamic = objectRequirement.dynamicMaxCount
                if (dynamic == DynamicAmount.XValue) return (xValue ?: 0).coerceAtLeast(0)
                if (dynamic != null) {
                    return try {
                        DynamicAmountEvaluator().evaluate(
                            state,
                            dynamic,
                            EffectContext(sourceId = sourceId, controllerId = casterId, xValue = xValue)
                        ).coerceAtLeast(0)
                    } catch (_: Exception) {
                        objectRequirement.count
                    }
                }
            }
            return if (requirement.unlimited) remaining else requirement.count
        }

        fun search(requirementIndex: Int, targetOffset: Int): List<Int>? {
            if (requirementIndex == requirements.size) {
                return if (targetOffset == targets.size) emptyList() else null
            }

            val requirement = requirements[requirementIndex]
            val remaining = targets.size - targetOffset
            val max = effectiveMax(requirement, remaining).coerceAtLeast(0).coerceAtMost(remaining)
            val min = requirement.effectiveMinCount.coerceAtLeast(0).coerceAtMost(max)
            val laterMinimum = requirements.drop(requirementIndex + 1).sumOf {
                it.effectiveMinCount.coerceAtLeast(0)
            }
            val upper = (max - laterMinimum).coerceAtLeast(min)

            // Prefer the declared maximum for ambiguous legacy payloads. Exact response counts
            // take the separate path above, so this is only a compatibility fallback.
            for (count in upper downTo min) {
                val end = targetOffset + count
                if (!segmentIsLegalAtChoice(
                        state,
                        targets.subList(targetOffset, end),
                        targets.subList(0, targetOffset),
                        requirement,
                        targets,
                        casterId,
                        sourceColors,
                        sourceSubtypes,
                        sourceId,
                        xValue,
                        targetingSourceType
                    )
                ) continue
                val tail = search(requirementIndex + 1, end) ?: continue
                return listOf(count) + tail
            }
            return null
        }

        return search(0, 0) ?: run {
            var remaining = targets.size
            requirements.mapIndexed { index, requirement ->
                val laterMinimum = requirements.drop(index + 1).sumOf {
                    it.effectiveMinCount.coerceAtLeast(0)
                }
                val count = if (index == requirements.lastIndex) {
                    remaining
                } else {
                    (remaining - laterMinimum).coerceAtLeast(0)
                        .coerceAtMost(requirement.count.coerceAtLeast(0))
                }
                remaining -= count
                count
            }
        }
    }

    private fun segmentIsLegalAtChoice(
        state: GameState,
        requirementTargets: List<ChosenTarget>,
        priorTargets: List<ChosenTarget>,
        requirement: TargetRequirement,
        allTargets: List<ChosenTarget>,
        casterId: EntityId,
        sourceColors: Set<Color>,
        sourceSubtypes: Set<String>,
        sourceId: EntityId?,
        xValue: Int?,
        targetingSourceType: TargetingSourceType
    ): Boolean {
        if (requirementTargets.any { target ->
                validateSingleTarget(
                    state = state,
                    target = target,
                    requirement = requirement,
                    casterId = casterId,
                    sourceColors = sourceColors,
                    sourceSubtypes = sourceSubtypes,
                    sourceId = sourceId,
                    xValue = xValue,
                    allTargets = allTargets,
                    targetingSourceType = targetingSourceType
                ) != null
            }
        ) return false
        return validateRequirementRelationships(
            state = state,
            requirement = requirement,
            requirementTargets = requirementTargets,
            priorTargets = priorTargets,
            casterId = casterId,
            sourceId = sourceId,
            xValue = xValue
        ) == null
    }

    /** Re-check cross-target restrictions against the current projected characteristics. */
    private fun validateRequirementRelationships(
        state: GameState,
        requirement: TargetRequirement,
        requirementTargets: List<ChosenTarget>,
        priorTargets: List<ChosenTarget>,
        casterId: EntityId,
        sourceId: EntityId?,
        xValue: Int?
    ): String? {
        if (requirement is TargetOther && requirementTargets.any { candidate ->
                priorTargets.any { it == candidate }
            }) {
            return "Target must be different from the other chosen targets"
        }

        val objectRequirement = requirement.objectRequirement() ?: return null

        // CR 115.9b says a target that has left its expected zone is ignored for a query about
        // whether something targets it; CR 608.2b separately makes that target illegal. The
        // relationship checks therefore compare only currently-present target objects. This is
        // deliberately not a last-known-information fallback: LKI would reintroduce a departed
        // target into the live legality relation and could make a legal survivor illegal.
        val presentTargets = requirementTargets.filter { isCurrentTargetObject(state, it) }

        // The aggregate cap applies to the complete chosen set, including a single target. Keep
        // this check before the multi-target early return so an unavailable locked cap cannot be
        // mistaken for an unlimited cap when only one slot was chosen.
        objectRequirement.totalManaValueAtMost?.let { capExpression ->
            val cap = evaluateAggregateCapOrNull(
                state = state,
                expression = capExpression,
                context = EffectContext(sourceId = sourceId, controllerId = casterId, xValue = xValue)
            ) ?: return "Target aggregate mana value cap is unavailable"
            val total = presentTargets.sumOf { target ->
                (target as? ChosenTarget.Card)?.let { card ->
                    state.getEntity(card.cardId)?.get<CardComponent>()?.manaValue
                        ?: return "Target characteristics are unavailable"
                } ?: 0
            }
            if (total > cap) return "Targets must have total mana value $cap or less"
        }

        if (requirementTargets.size <= 1) return null

        if (objectRequirement.sameController) {
            val controllers = mutableListOf<EntityId>()
            for (target in presentTargets) {
                val permanent = target as? ChosenTarget.Permanent ?: continue
                val controller = state.projectedState.getController(permanent.entityId)
                    ?: state.getEntity(permanent.entityId)?.get<ControllerComponent>()?.playerId
                    ?: return "Target controller is unavailable"
                controllers += controller
            }
            if (controllers.toSet().size > 1) return "Targets must be controlled by the same player"
        }
        if (objectRequirement.sameOwner) {
            val owners = mutableListOf<EntityId>()
            for (target in presentTargets) {
                val card = target as? ChosenTarget.Card ?: continue
                state.getEntity(card.cardId)?.get<CardComponent>()
                    ?: return "Target characteristics are unavailable"
                owners += card.ownerId
            }
            if (owners.toSet().size > 1) return "Targets must be from a single graveyard"
        }
        if (objectRequirement.sameCreatureType) {
            val subtypeSets = presentTargets.mapNotNull { target ->
                val permanent = target as? ChosenTarget.Permanent ?: return@mapNotNull null
                state.getEntity(permanent.entityId)?.get<CardComponent>()
                    ?: return "Target characteristics are unavailable"
                state.projectedState.getSubtypes(permanent.entityId)
            }
            if (subtypeSets.isNotEmpty() && subtypeSets.reduce { acc, next -> acc intersect next }.isEmpty()) {
                return "Targets must share a creature type"
            }
        }
        if (objectRequirement.sameCardType) {
            val typeSets = presentTargets.mapNotNull { target ->
                val permanent = target as? ChosenTarget.Permanent ?: return@mapNotNull null
                state.getEntity(permanent.entityId)?.get<CardComponent>()
                    ?: return "Target characteristics are unavailable"
                state.projectedState.getTypes(permanent.entityId)
                    .filter { it in CARD_TYPE_NAMES }
                    .toSet()
            }
            if (typeSets.isNotEmpty() && typeSets.reduce { acc, next -> acc intersect next }.isEmpty()) {
                return "Targets must share a card type"
            }
        }
        if (objectRequirement.differentNames) {
            val names = presentTargets.mapNotNull { target ->
                val id = (target as? ChosenTarget.Permanent)?.entityId
                    ?: (target as? ChosenTarget.Card)?.cardId
                id?.let {
                    state.getEntity(it)?.get<CardComponent>()
                        ?: return "Target characteristics are unavailable"
                    state.projectedState.getName(it) ?: state.getEntity(it)?.get<CardComponent>()?.name
                }
            }
            if (names.size != names.toSet().size) return "Targets must have different names"
        }
        return null
    }

    /** Whether a locked target object is still present in the zone in which it was chosen. */
    private fun isCurrentTargetObject(state: GameState, target: ChosenTarget): Boolean = when (target) {
        is ChosenTarget.Player -> state.getEntity(target.playerId) != null
        is ChosenTarget.Permanent ->
            target.entityId in state.getBattlefield() && state.getEntity(target.entityId) != null
        is ChosenTarget.Card ->
            target.cardId in state.getZone(ZoneKey(target.ownerId, target.zone)) &&
                state.getEntity(target.cardId) != null
        is ChosenTarget.Spell -> target.spellEntityId in state.stack &&
            state.getEntity(target.spellEntityId) != null
    }

    private fun TargetRequirement.objectRequirement(): TargetObject? = when (this) {
        is TargetObject -> this
        is TargetOther -> baseRequirement.objectRequirement()
        else -> null
    }

    private fun ChosenTarget.entityIdOrNull(): EntityId? = when (this) {
        is ChosenTarget.Player -> playerId
        is ChosenTarget.Permanent -> entityId
        is ChosenTarget.Card -> cardId
        is ChosenTarget.Spell -> spellEntityId
    }

    /**
     * Validate all targets for a spell/ability against their requirements.
     *
     * @param state The current game state
     * @param targets The chosen targets
     * @param requirements The target requirements from the card definition
     * @param casterId The player casting the spell
     * @param sourceColors Colors of the source spell/ability (for protection checks)
     * @return Error message if any target is invalid, null if all targets are valid
     */
    fun validateTargets(
        state: GameState,
        targets: List<ChosenTarget>,
        requirements: List<TargetRequirement>,
        casterId: EntityId,
        sourceColors: Set<Color> = emptySet(),
        sourceSubtypes: Set<String> = emptySet(),
        sourceId: EntityId? = null,
        xValue: Int? = null
    ): String? {
        // Use the game state for validation
        // StateProjector is used for P/T checks to account for continuous effects

        // Match targets to requirements (assuming targets are in order of requirements).
        // For TargetObject with a dynamicMaxCount, the resolved dynamic value clamps the
        // per-req max count (the static `count` field is just a placeholder). XValue is
        // threaded via the chosen [xValue]; every other DynamicAmount is evaluated against
        // board state here, mirroring TriggerProcessor.snapshotDynamicCount — so a cast-time
        // spell with e.g. `dynamicMaxCount = Count(...)` caps correctly instead of falling
        // back to the static placeholder.
        // An explicit `dynamicMaxCount` outranks the `unlimited` flag. `unlimited` means "no
        // *static* upper bound" — it is the count the author didn't write down — whereas a
        // dynamic cap is a bound the author did write down and is simply not knowable until
        // cast time. Grove's Bounty needs both: "any number of target creatures you control"
        // with X counters to hand out, where CR 601.2d still forbids declaring more targets
        // than there are counters. Checking `unlimited` first would drop that cap on the floor.
        fun effectiveMaxCount(req: TargetRequirement): Int {
            val unboundedFallback = if (req.unlimited) Int.MAX_VALUE else req.count
            if (req is TargetObject) {
                val dyn = req.dynamicMaxCount
                if (dyn == DynamicAmount.XValue) {
                    return xValue ?: unboundedFallback
                }
                if (dyn != null) {
                    return try {
                        val context = EffectContext(
                            sourceId = sourceId,
                            controllerId = casterId,
                            xValue = xValue
                        )
                        DynamicAmountEvaluator().evaluate(state, dyn, context).coerceAtLeast(0)
                    } catch (_: Exception) {
                        unboundedFallback
                    }
                }
            }
            return unboundedFallback
        }
        for ((index, requirement) in requirements.withIndex()) {
            // Get targets for this requirement (handle multi-target requirements)
            val targetCount = effectiveMaxCount(requirement)
            val startIdx = requirements.take(index).sumOf { effectiveMaxCount(it) }
            // Use Long for the end index so an unlimited requirement (targetCount = Int.MAX_VALUE)
            // doesn't overflow to a negative value and make subList throw.
            val endIdx = (startIdx.toLong() + targetCount.toLong())
                .coerceAtMost(targets.size.toLong()).toInt()
            val targetsForReq = targets.subList(
                startIdx.coerceAtMost(targets.size),
                endIdx
            )

            // Reject if too many targets were declared. When a requirement is *effectively*
            // unbounded ("any number of target ...", Drafna's Restoration) there is no upper
            // bound, so skip this check — summing Int.MAX_VALUE would overflow to a negative cap
            // and spuriously reject a legal cast. An unlimited requirement that also carries a
            // resolved `dynamicMaxCount` (Grove's Bounty) is bounded after all, so it is checked.
            if (requirements.none { effectiveMaxCount(it) == Int.MAX_VALUE }) {
                val totalMax = requirements.sumOf { effectiveMaxCount(it) }
                if (targets.size > totalMax) {
                    return "Too many targets for ${requirement.description}"
                }
            }

            // Check minimum targets
            if (targetsForReq.size < requirement.effectiveMinCount) {
                return "Not enough targets for ${requirement.description}"
            }

            // Validate each target against the requirement
            for (target in targetsForReq) {
                val error = validateSingleTarget(state, target, requirement, casterId, sourceColors, sourceSubtypes, sourceId, xValue, targets)
                if (error != null) return error
            }

            // "Two/X target ..." — the same object or player can't be chosen more than once for a
            // single instance of the word "target" (CR 601.2c; applies to abilities via 602.2b).
            // Cross-requirement duplicates are a different "target" instance and stay legal by
            // default — that distinctness is opt-in via TargetOther below.
            if (targetsForReq.size > 1 && targetsForReq.distinct().size != targetsForReq.size) {
                return "The same target can't be chosen more than once for ${requirement.description}"
            }

            // "Another target" — must differ from any earlier target chosen for this same cast
            if (requirement is TargetOther) {
                val priorTargets = targets.subList(0, startIdx.coerceAtMost(targets.size))
                for (target in targetsForReq) {
                    if (priorTargets.any { it == target }) {
                        return "Target must be different from the other chosen targets"
                    }
                }
            }

            // "... controlled by the same player" — every chosen target for this requirement
            // must share a controller (Rule uses current control; projected state respects
            // control-changing effects). No-op for single-target requirements.
            if (requirement is TargetObject && requirement.sameController && targetsForReq.size > 1) {
                val projected = state.projectedState
                val controllers = targetsForReq.mapNotNull { target ->
                    (target as? ChosenTarget.Permanent)?.let { perm ->
                        projected.getController(perm.entityId)
                            ?: state.getEntity(perm.entityId)?.get<ControllerComponent>()?.playerId
                    }
                }
                if (controllers.toSet().size > 1) {
                    return "Targets must be controlled by the same player"
                }
            }

            // "... from a single graveyard" — every chosen card target for this requirement
            // must share an owner (CR uses the graveyard's owner). No-op for single-target
            // requirements and for non-card targets.
            if (requirement is TargetObject && requirement.sameOwner && targetsForReq.size > 1) {
                val owners = targetsForReq.mapNotNull { target ->
                    (target as? ChosenTarget.Card)?.ownerId
                }
                if (owners.toSet().size > 1) {
                    return "Targets must be from a single graveyard"
                }
            }

            // "... that share a creature type" — every chosen permanent target must hold at least
            // one creature type in common with all the others (Secret Tunnel). Uses projected
            // subtypes so granted/changed types count. No-op for single-target requirements; a
            // target with no creature types (or one off the battlefield) can never share, so the
            // set is rejected.
            if (requirement is TargetObject && requirement.sameCreatureType && targetsForReq.size > 1) {
                val projected = state.projectedState
                val subtypeSets = targetsForReq.map { target ->
                    (target as? ChosenTarget.Permanent)
                        ?.takeIf { it.entityId in state.getBattlefield() }
                        ?.let { projected.getSubtypes(it.entityId) }
                        ?: emptySet()
                }
                val shared = subtypeSets.reduce { acc, next -> acc intersect next }
                if (shared.isEmpty()) {
                    return "Targets must share a creature type"
                }
            }

            // "... that share a card type" — every chosen permanent target must hold at least one
            // *card type* (CR 205.2a) in common with all the others (Burglar's Plot). Projected
            // types, so an animated land counts as a creature; supertypes are sieved out, so two
            // legendary permanents don't qualify by both being legendary. No-op for single-target
            // requirements; a target off the battlefield contributes nothing and rejects the set.
            if (requirement is TargetObject && requirement.sameCardType && targetsForReq.size > 1) {
                val projected = state.projectedState
                val typeSets = targetsForReq.map { target ->
                    (target as? ChosenTarget.Permanent)
                        ?.takeIf { it.entityId in state.getBattlefield() }
                        ?.let { perm -> projected.getTypes(perm.entityId).filterTo(mutableSetOf()) { it in CARD_TYPE_NAMES } }
                        ?: emptySet()
                }
                val shared = typeSets.reduce { acc, next -> acc intersect next }
                if (shared.isEmpty()) {
                    return "Targets must share a card type"
                }
            }

            // "... with total mana value N or less" — the summed mana value of the chosen card
            // targets may not exceed the resolved cap (Fire Lord Sozin's "total mana value X or
            // less"; XValue resolves against the paid [xValue]). CR 601.2c. No-op for non-card
            // targets, which contribute 0.
            val totalManaCap = (requirement as? TargetObject)?.totalManaValueAtMost
            if (totalManaCap != null && targetsForReq.isNotEmpty()) {
                val cap = evaluateAggregateCapOrNull(
                    state = state,
                    expression = totalManaCap,
                    context = EffectContext(sourceId = sourceId, controllerId = casterId, xValue = xValue)
                ) ?: return "Target aggregate mana value cap is unavailable"
                val summedManaValue = targetsForReq.sumOf { target ->
                    (target as? ChosenTarget.Card)?.let { card ->
                        state.getEntity(card.cardId)?.get<CardComponent>()?.manaValue ?: 0
                    } ?: 0
                }
                if (summedManaValue > cap) {
                    return "Targets must have total mana value $cap or less"
                }
            }

            // "... with different names" — no two chosen targets for this requirement may share a
            // name (Behold the Sinister Six!: "up to six target creature cards with different
            // names"). CR 601.2c. Grouped by projected name on the battlefield, base card name in
            // other zones (graveyard cards aren't projected).
            if (requirement is TargetObject && requirement.differentNames && targetsForReq.size > 1) {
                val names = targetsForReq.map { target ->
                    val id = (target as? ChosenTarget.Permanent)?.entityId
                        ?: (target as? ChosenTarget.Card)?.cardId
                    id?.let {
                        state.projectedState.getName(it) ?: state.getEntity(it)?.get<CardComponent>()?.name
                    }
                }
                if (names.size != names.toSet().size) {
                    return "Targets must have different names"
                }
            }
        }

        return null
    }

    /**
     * Validate a single target against a requirement.
     */
    private fun validateSingleTarget(
        state: GameState,
        target: ChosenTarget,
        requirement: TargetRequirement,
        casterId: EntityId,
        sourceColors: Set<Color> = emptySet(),
        sourceSubtypes: Set<String> = emptySet(),
        sourceId: EntityId? = null,
        xValue: Int? = null,
        allTargets: List<ChosenTarget> = emptyList(),
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        triggeringPlayerId: EntityId? = null,
        defendingPlayerId: EntityId? = null,
        storedCollections: Map<String, List<EntityId>> = emptyMap(),
        predicateContext: PredicateContext? = null,
        damageSourceId: EntityId? = null,
        damageRecipientId: EntityId? = null,
        damageRecipientKind: DamageRecipientKind = DamageRecipientKind.UNKNOWN,
        damageRecipientKinds: DamageRecipientKindSet = DamageRecipientKindSet.UNKNOWN,
        damageSourceLastKnownSnapshot: EntitySnapshot? = null,
        damageRecipientLastKnownSnapshot: EntitySnapshot? = null
    ): String? {
        // A separately-chosen player target (target index 0 for "target player's graveyard"
        // spells) — lets a later requirement's filter resolve `OwnedByTargetPlayer` /
        // `ControlledByTargetPlayer` against the player already chosen for this same cast
        // (Drafna's Restoration, Hurkyl's-Recall-family relational predicates).
        val chosenPlayerTarget = allTargets.firstNotNullOfOrNull { (it as? ChosenTarget.Player)?.playerId }
        val targetPredicateContext = predicateContext ?: PredicateContext(
            controllerId = casterId,
            targetOpponentId = chosenPlayerTarget,
            targetPlayerId = chosenPlayerTarget,
            sourceId = sourceId,
            triggeringEntityId = triggeringEntityId,
            triggeringPlayerId = triggeringPlayerId,
            defendingPlayerId = defendingPlayerId,
            storedCollections = storedCollections,
            targets = allTargets,
            xValue = xValue,
            damageSourceId = damageSourceId,
            damageRecipientId = damageRecipientId,
            damageRecipientKind = damageRecipientKind,
            damageRecipientKinds = damageRecipientKinds,
            damageSourceLastKnownSnapshot = damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageRecipientLastKnownSnapshot
        )
        val error = when (requirement) {
            is TargetPlayer -> validatePlayerTarget(state, target, requirement, casterId, sourceId)
            is TargetOpponent -> validateOpponentTarget(state, target, requirement, casterId, sourceId)
            is AnyTarget -> validateAnyTarget(state, target, casterId)
            is TargetCreatureOrPlayer -> validateCreatureOrPlayerTarget(state, target, casterId)
            is TargetPermanentOrPlayer ->
                validatePermanentOrPlayerTarget(
                    state, target, requirement, casterId, sourceId, xValue, chosenPlayerTarget, targetPredicateContext
                )
            is TargetOpponentOrPlaneswalker -> validateOpponentOrPlaneswalkerTarget(state, target, casterId)
            is TargetPlayerOrPlaneswalker -> validatePlayerOrPlaneswalkerTarget(state, target, casterId)
            is TargetCreatureOrPlaneswalker -> validateCreatureOrPlaneswalkerTarget(state, target)
            is TargetSpellOrPermanent ->
                validateSpellOrPermanentTarget(state, target, requirement, casterId, sourceId, xValue, targetPredicateContext)
            is TargetObject ->
                validateObjectTarget(
                    state, target, requirement.filter, casterId, sourceId, xValue, chosenPlayerTarget,
                    targetPredicateContext
                )
            is TargetOther -> validateSingleTarget(
                state,
                target,
                requirement.baseRequirement,
                casterId,
                sourceColors,
                sourceSubtypes,
                sourceId,
                xValue,
                allTargets,
                targetingSourceType,
                triggeringEntityId,
                triggeringPlayerId,
                defendingPlayerId,
                storedCollections,
                targetPredicateContext
            )
        }
        if (error != null) return error

        // These source-aware restrictions are part of target legality, not a cast-time-only
        // filter. Keep them in the canonical resolution validator so modal and splice entries do
        // not bypass the same ability/spell distinction as ordinary stack objects.
        if (target is ChosenTarget.Permanent) {
            val projected = state.projectedState
            val entityController = projected.getController(target.entityId)
                ?: state.getEntity(target.entityId)?.get<ControllerComponent>()?.playerId
            if (SourceTypeTargeting.cantBeTargetedBySourceTypeAbility(
                    state, target.entityId, sourceId, targetingSourceType
                )
            ) {
                return "Target is protected from this source type"
            }
            if (targetingSourceType != TargetingSourceType.SPELL &&
                entityController != casterId &&
                ControllerGrants.isActiveOn<CantBeTargetedByOpponentAbilitiesComponent>(
                    state,
                    target.entityId
                )
            ) {
                return "Target can't be targeted by an opponent's ability"
            }
        }

        // Check player-level protection, e.g. The One Ring's "protection from everything" (Rule 702.16).
        // A protected player can't be the target of a source matching one of its protection scopes.
        if (target is ChosenTarget.Player &&
            PlayerProtectionRules.isProtectedFromSource(state, target.playerId, sourceId, casterId)
        ) {
            return "Target player has protection from this source"
        }

        // Check hexproof and shroud on permanent targets (Rule 702.11, 702.18)
        val hexproofShroudError = checkHexproofAndShroud(state, target, casterId)
        if (hexproofShroudError != null) return hexproofShroudError

        // Check hexproof from color (Rule 702.11b)
        val hexproofError = checkHexproofFromColor(state, target, casterId, sourceColors)
        if (hexproofError != null) return hexproofError

        // Check hexproof from card type, e.g. "hexproof from instants" (Rule 702.11b).
        // Elenda, Saint of Dusk.
        val hexproofCardTypeError = checkHexproofFromCardType(state, target, casterId, sourceId)
        if (hexproofCardTypeError != null) return hexproofCardTypeError

        // Check protection from each opponent (Rule 702.16e)
        val protectionFromOpponentError = checkProtectionFromEachOpponent(state, target, casterId)
        if (protectionFromOpponentError != null) return protectionFromOpponentError

        // Check protection from supertype, e.g. "protection from legendary creatures" (Rule 702.16)
        val protectionFromSupertypeError = checkProtectionFromSupertype(state, target, sourceId)
        if (protectionFromSupertypeError != null) return protectionFromSupertypeError

        // Check protection from card type, e.g. "protection from instants and from sorceries"
        // (Rule 702.16). Sword of Wealth and Power.
        val protectionFromCardTypeError = checkProtectionFromCardType(state, target, sourceId)
        if (protectionFromCardTypeError != null) return protectionFromCardTypeError

        // "Can't be enchanted" (CR 303.4): an Aura can't legally target a permanent with the
        // CANT_BE_ENCHANTED restriction (Guardian Beast). Only applies when the source is an Aura.
        val cantBeEnchantedError = checkCantBeEnchanted(state, target, sourceId)
        if (cantBeEnchantedError != null) return cantBeEnchantedError

        // Check protection from color and creature subtype (Rule 702.16)
        return checkProtection(state, target, sourceColors, sourceSubtypes)
    }

    /**
     * Reject an Aura targeting a permanent that can't be enchanted (CR 303.4). No-op for
     * non-Aura sources and for non-permanent targets.
     *
     * Scope note: this only guards Aura *targeting* at cast/activation time, which covers the
     * common case (casting an Aura, or an ability that targets with an Aura on the stack). It does
     * NOT cover effects that move/attach an existing Aura without targeting (e.g. "attach target
     * Aura to ~", reanimate-an-Aura-attached). A card needing full coverage of the non-targeted
     * attachment cases (CR 303.4i for entering attached, CR 303.4j for re-attaching an on-battlefield
     * Aura) must also check [AbilityFlag.CANT_BE_ENCHANTED] at the attachment step.
     */
    private fun checkCantBeEnchanted(
        state: GameState,
        target: ChosenTarget,
        sourceId: EntityId?
    ): String? {
        val sourceIsAura = sourceId
            ?.let { state.getEntity(it)?.get<CardComponent>()?.typeLine?.isAura }
            ?: false
        if (!sourceIsAura) return null
        val targetId = (target as? ChosenTarget.Permanent)?.entityId ?: return null
        return if (state.projectedState.hasKeyword(targetId, AbilityFlag.CANT_BE_ENCHANTED)) {
            "That permanent can't be enchanted"
        } else null
    }

    /**
     * Check if a target has protection from one of the source's supertypes
     * (e.g. "protection from legendary creatures"). Format: PROTECTION_FROM_SUPERTYPE_<SUPERTYPE>.
     */
    private fun checkProtectionFromSupertype(
        state: GameState,
        target: ChosenTarget,
        sourceId: EntityId?
    ): String? {
        if (sourceId == null) return null
        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            else -> return null
        }
        if (entityId !in state.getBattlefield()) return null

        val projected = state.projectedState
        for (supertype in projected.getSupertypes(sourceId)) {
            if (projected.hasKeyword(entityId, "PROTECTION_FROM_SUPERTYPE_${supertype.uppercase()}")) {
                val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                return "$cardName has protection from ${supertype.lowercase()} permanents"
            }
        }
        return null
    }

    /**
     * Check if a target has protection from one of the source's card types
     * (e.g. "protection from instants and from sorceries"). Format:
     * PROTECTION_FROM_CARDTYPE_<CARDTYPE>.
     *
     * The source's card types are read from its [CardComponent.typeLine] directly (not the
     * projected state), because the source is typically an instant/sorcery spell on the stack —
     * which the layer projector does not project. Card types of a spell aren't changed by
     * continuous effects in the cases this guards, so the printed type line is authoritative here.
     */
    private fun checkProtectionFromCardType(
        state: GameState,
        target: ChosenTarget,
        sourceId: EntityId?
    ): String? {
        if (sourceId == null) return null
        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            else -> return null
        }
        if (entityId !in state.getBattlefield()) return null

        val sourceCardTypes = state.getEntity(sourceId)
            ?.get<CardComponent>()?.typeLine?.cardTypes ?: return null
        val projected = state.projectedState
        for (cardType in sourceCardTypes) {
            if (projected.hasKeyword(entityId, "PROTECTION_FROM_CARDTYPE_${cardType.name}")) {
                val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                return "$cardName has protection from ${cardType.displayName.lowercase()}s"
            }
        }
        return null
    }

    /**
     * Check if a permanent target has hexproof or shroud.
     * Hexproof prevents opponents from targeting; shroud prevents all targeting.
     */
    private fun checkHexproofAndShroud(
        state: GameState,
        target: ChosenTarget,
        casterId: EntityId
    ): String? {
        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            else -> return null
        }

        if (entityId !in state.getBattlefield()) return null

        val projected = state.projectedState
        val entityController = projected.getController(entityId)
            ?: state.getEntity(entityId)?.get<ControllerComponent>()?.playerId

        if (projected.hasKeyword(entityId, Keyword.SHROUD)) {
            val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
            return "$cardName has shroud"
        }
        if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != casterId &&
            !HexproofSuppression.isSuppressedForCaster(state, projected, entityId, casterId)
        ) {
            val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
            return "$cardName has hexproof"
        }
        return null
    }

    /**
     * Check if a target has hexproof from any of the source's colors.
     * "Hexproof from [color]" prevents opponents from targeting with spells/abilities of that color.
     * Returns an error message if hexproof blocks this targeting, null otherwise.
     */
    private fun checkHexproofFromColor(
        state: GameState,
        target: ChosenTarget,
        casterId: EntityId,
        sourceColors: Set<Color>
    ): String? {
        if (sourceColors.isEmpty()) return null

        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            else -> return null
        }

        // Only check permanents on the battlefield
        if (entityId !in state.getBattlefield()) return null

        // Hexproof from color only blocks opponents — owner can still target. Use projected
        // control because a control-changing effect can resolve before this target recheck.
        val entityController = state.projectedState.getController(entityId)
            ?: state.getEntity(entityId)?.get<ControllerComponent>()?.playerId
        if (entityController == casterId) return null

        val projected = state.projectedState
        val hexproofSuppressed = HexproofSuppression.isSuppressedForCaster(state, projected, entityId, casterId)
        if (!hexproofSuppressed) {
            for (color in sourceColors) {
                if (projected.hasKeyword(entityId, "HEXPROOF_FROM_${color.name}")) {
                    val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                    return "$cardName has hexproof from ${color.displayName.lowercase()}"
                }
            }
            // Hexproof from monocolored: a source with exactly one color can't target (CR 105.2).
            if (sourceColors.size == 1 && projected.hasKeyword(entityId, "HEXPROOF_FROM_MONOCOLORED")) {
                val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                return "$cardName has hexproof from monocolored"
            }
        }
        return null
    }

    /**
     * Check if a target has hexproof from one of the source's card types — "hexproof from
     * instants" (Rule 702.11b). Format: `HEXPROOF_FROM_CARDTYPE_<CARDTYPE>`.
     *
     * Like hexproof from a color this only blocks *opponents*: the permanent's controller can
     * still target it with their own instants, and hexproof-suppressing effects (Glaring Spotlight
     * and friends) turn it off for the caster. The source's card types are resolved the same way as
     * for protection-from-card-type — projected types for a permanent source, falling back to the
     * printed type line for a spell on the stack, which the layer projector doesn't cover.
     */
    private fun checkHexproofFromCardType(
        state: GameState,
        target: ChosenTarget,
        casterId: EntityId,
        sourceId: EntityId?
    ): String? {
        if (sourceId == null) return null
        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            else -> return null
        }
        if (entityId !in state.getBattlefield()) return null

        val entityController = state.projectedState.getController(entityId)
            ?: state.getEntity(entityId)?.get<ControllerComponent>()?.playerId
        if (entityController == casterId) return null

        val projected = state.projectedState
        if (HexproofSuppression.isSuppressedForCaster(state, projected, entityId, casterId)) return null

        for (cardType in SourceTypeTargeting.sourceCardTypes(state, sourceId)) {
            if (projected.hasKeyword(entityId, "HEXPROOF_FROM_CARDTYPE_${cardType.uppercase()}")) {
                val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                return "$cardName has hexproof from ${cardType.lowercase()}s"
            }
        }
        return null
    }

    /**
     * Check if a target has protection from each of the controller's opponents (Rule 702.16e).
     * Prevents being targeted by any spell or ability controlled by an opponent of the target's controller.
     */
    private fun checkProtectionFromEachOpponent(
        state: GameState,
        target: ChosenTarget,
        casterId: EntityId
    ): String? {
        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            else -> return null
        }
        if (entityId !in state.getBattlefield()) return null

        val projected = state.projectedState
        if (!projected.hasKeyword(entityId, "PROTECTION_FROM_EACH_OPPONENT")) return null

        val entityController = projected.getController(entityId)
            ?: state.getEntity(entityId)?.get<ControllerComponent>()?.playerId
        if (entityController == null || entityController == casterId) return null

        val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
        return "$cardName has protection from each of your opponents"
    }

    /**
     * Check if a target has protection from any of the source's colors or creature subtypes.
     * Returns an error message if the target is protected, null otherwise.
     */
    private fun checkProtection(
        state: GameState,
        target: ChosenTarget,
        sourceColors: Set<Color>,
        sourceSubtypes: Set<String> = emptySet()
    ): String? {
        if (sourceColors.isEmpty() && sourceSubtypes.isEmpty()) return null

        val entityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            is ChosenTarget.Player -> return null  // Protection on players is handled separately
            else -> return null
        }

        // Only check permanents on the battlefield
        if (entityId !in state.getBattlefield()) return null

        val projected = state.projectedState
        for (color in sourceColors) {
            if (projected.hasKeyword(entityId, "PROTECTION_FROM_${color.name}")) {
                val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                return "$cardName has protection from ${color.displayName.lowercase()}"
            }
        }
        for (subtype in sourceSubtypes) {
            if (projected.hasKeyword(entityId, "PROTECTION_FROM_SUBTYPE_${subtype.uppercase()}")) {
                val cardName = state.getEntity(entityId)?.get<CardComponent>()?.name ?: "target"
                return "$cardName has protection from ${subtype.lowercase()}s"
            }
        }
        return null
    }

    private fun validatePermanentTarget(
        state: GameState,
        target: ChosenTarget,
        filter: TargetFilter,
        casterId: EntityId,
        sourceId: EntityId? = null,
        xValue: Int? = null,
        predicateContext: PredicateContext? = null
    ): String? {
        if (target !is ChosenTarget.Permanent) {
            return "Target must be a permanent"
        }

        state.getEntity(target.entityId)
            ?: return "Target not found"

        // Check if target is on the battlefield
        if (target.entityId !in state.getBattlefield()) {
            return "Target must be on the battlefield"
        }

        // "Another target …" — the source itself is not a legal target. Mirrors the same guard
        // in validateGraveyardTarget; enumeration also honors excludeSelf, but direct submission
        // must be rejected here too (Braided Net's "another target nonland permanent").
        if (filter.excludeSelf && sourceId != null && target.entityId == sourceId) {
            return "Target must be another permanent"
        }

        // Use unified filter with projection (face-down creatures have CMC 0 per Rule 708.2)
        val projected = state.projectedState
        val context = (predicateContext ?: PredicateContext(controllerId = casterId))
            .copy(controllerId = casterId, sourceId = sourceId, xValue = xValue)
        val matches = predicateEvaluator.matches(state, projected, target.entityId, filter.baseFilter, context)
        if (!matches) {
            return "Target does not match filter: ${filter.description}"
        }
        return null
    }

    private fun validatePlayerTarget(
        state: GameState,
        target: ChosenTarget,
        requirement: TargetPlayer,
        casterId: EntityId,
        sourceId: EntityId?
    ): String? {
        if (target !is ChosenTarget.Player) {
            return "Target must be a player"
        }
        if (!state.hasEntity(target.playerId)) {
            return "Target player not found"
        }
        if (playerHasShroud(state, target.playerId)) {
            return "Target player has shroud"
        }
        if (playerHasHexproofAgainst(state, target.playerId, casterId)) {
            return "Target player has hexproof"
        }
        // CR 608.2b: a target illegal at resolution is removed. Re-checking the restriction
        // here covers both cast-time validation and the resolution-time re-validation.
        if (!PlayerTargetRestriction.isSatisfied(state, requirement.restriction, target.playerId, casterId, sourceId)) {
            return "Target player does not match: ${requirement.description}"
        }
        return null
    }

    private fun validateOpponentTarget(
        state: GameState,
        target: ChosenTarget,
        requirement: TargetOpponent,
        casterId: EntityId,
        sourceId: EntityId?
    ): String? {
        if (target !is ChosenTarget.Player) {
            return "Target must be a player"
        }
        if (!state.hasEntity(target.playerId)) {
            return "Target player not found"
        }
        if (target.playerId == casterId) {
            return "Target must be an opponent"
        }
        if (playerHasShroud(state, target.playerId)) {
            return "Target player has shroud"
        }
        if (playerHasHexproof(state, target.playerId)) {
            return "Target player has hexproof"
        }
        if (!PlayerTargetRestriction.isSatisfied(state, requirement.restriction, target.playerId, casterId, sourceId)) {
            return "Target player does not match: ${requirement.description}"
        }
        return null
    }

    private fun validateAnyTarget(state: GameState, target: ChosenTarget, casterId: EntityId): String? {
        return when (target) {
            is ChosenTarget.Player -> {
                if (!state.hasEntity(target.playerId)) "Target player not found"
                else if (playerHasShroud(state, target.playerId)) "Target player has shroud"
                else if (playerHasHexproofAgainst(state, target.playerId, casterId)) "Target player has hexproof"
                else null
            }
            is ChosenTarget.Permanent -> {
                if (target.entityId !in state.getBattlefield()) "Target not on battlefield" else null
            }
            else -> "Invalid target type"
        }
    }

    private fun validateCreatureOrPlayerTarget(state: GameState, target: ChosenTarget, casterId: EntityId): String? {
        return when (target) {
            is ChosenTarget.Player -> {
                if (!state.hasEntity(target.playerId)) "Target player not found"
                else if (playerHasShroud(state, target.playerId)) "Target player has shroud"
                else if (playerHasHexproofAgainst(state, target.playerId, casterId)) "Target player has hexproof"
                else null
            }
            is ChosenTarget.Permanent -> {
                val container = state.getEntity(target.entityId)
                    ?: return "Target not found"
                val cardComponent = container.get<CardComponent>()
                    ?: return "Target is not a card"
                // Face-down permanents are always creatures (Rule 708.2)
                if (!state.projectedState.isCreature(target.entityId) && !container.has<FaceDownComponent>()) {
                    return "Target must be a creature or player"
                }
                if (target.entityId !in state.getBattlefield()) {
                    return "Target must be on the battlefield"
                }
                null
            }
            else -> "Target must be a creature or player"
        }
    }

    /**
     * "Target permanent or player": a player target is validated exactly like [TargetPlayer], a
     * permanent target exactly like a [TargetObject] over the requirement's `permanentFilter`, so
     * neither half can drift from its single-kind counterpart.
     */
    private fun validatePermanentOrPlayerTarget(
        state: GameState,
        target: ChosenTarget,
        requirement: TargetPermanentOrPlayer,
        casterId: EntityId,
        sourceId: EntityId?,
        xValue: Int?,
        chosenPlayerTarget: EntityId?,
        predicateContext: PredicateContext? = null
    ): String? {
        return when (target) {
            is ChosenTarget.Player -> {
                if (!state.hasEntity(target.playerId)) "Target player not found"
                else if (playerHasShroud(state, target.playerId)) "Target player has shroud"
                else if (playerHasHexproofAgainst(state, target.playerId, casterId)) "Target player has hexproof"
                else null
            }
            is ChosenTarget.Permanent ->
                validateObjectTarget(
                    state, target, requirement.permanentFilter, casterId, sourceId, xValue, chosenPlayerTarget,
                    predicateContext
                )
            else -> "Target must be a ${requirement.permanentFilter.description} or player"
        }
    }

    private fun validateOpponentOrPlaneswalkerTarget(state: GameState, target: ChosenTarget, casterId: EntityId): String? {
        return when (target) {
            is ChosenTarget.Player -> {
                if (!state.hasEntity(target.playerId)) "Target player not found"
                else if (target.playerId == casterId) "Target must be an opponent"
                else if (playerHasShroud(state, target.playerId)) "Target player has shroud"
                else if (playerHasHexproof(state, target.playerId)) "Target player has hexproof"
                else null
            }
            is ChosenTarget.Permanent -> {
                val container = state.getEntity(target.entityId)
                    ?: return "Target not found"
                val cardComponent = container.get<CardComponent>()
                    ?: return "Target is not a card"
                if (target.entityId !in state.getBattlefield()) {
                    return "Target must be on the battlefield"
                }
                val isPlaneswalker = state.projectedState.isPlaneswalker(target.entityId) ||
                    CardType.PLANESWALKER in cardComponent.typeLine.cardTypes
                if (!isPlaneswalker) {
                    return "Target must be an opponent or planeswalker"
                }
                null
            }
            else -> "Target must be an opponent or planeswalker"
        }
    }

    private fun validatePlayerOrPlaneswalkerTarget(state: GameState, target: ChosenTarget, casterId: EntityId): String? {
        return when (target) {
            is ChosenTarget.Player -> {
                if (!state.hasEntity(target.playerId)) "Target player not found"
                else if (playerHasShroud(state, target.playerId)) "Target player has shroud"
                else if (playerHasHexproofAgainst(state, target.playerId, casterId)) "Target player has hexproof"
                else null
            }
            is ChosenTarget.Permanent -> {
                val container = state.getEntity(target.entityId)
                    ?: return "Target not found"
                val cardComponent = container.get<CardComponent>()
                    ?: return "Target is not a card"
                if (target.entityId !in state.getBattlefield()) {
                    return "Target must be on the battlefield"
                }
                val isPlaneswalker = state.projectedState.isPlaneswalker(target.entityId) ||
                    CardType.PLANESWALKER in cardComponent.typeLine.cardTypes
                if (!isPlaneswalker) {
                    return "Target must be a player or planeswalker"
                }
                null
            }
            else -> "Target must be a player or planeswalker"
        }
    }

    private fun validateCreatureOrPlaneswalkerTarget(state: GameState, target: ChosenTarget): String? {
        if (target !is ChosenTarget.Permanent) {
            return "Target must be a creature or planeswalker"
        }
        val container = state.getEntity(target.entityId)
            ?: return "Target not found"
        val cardComponent = container.get<CardComponent>()
            ?: return "Target is not a card"
        val projected = state.projectedState
        val isPlaneswalker = projected.isPlaneswalker(target.entityId) ||
            CardType.PLANESWALKER in cardComponent.typeLine.cardTypes
        // Face-down permanents are always creatures (Rule 708.2)
        val isFaceDown = container.has<FaceDownComponent>()
        if (!projected.isCreature(target.entityId) && !isPlaneswalker && !isFaceDown) {
            return "Target must be a creature or planeswalker"
        }
        if (target.entityId !in state.getBattlefield()) {
            return "Target must be on the battlefield"
        }
        return null
    }

    private fun validateGraveyardTarget(
        state: GameState,
        target: ChosenTarget,
        filter: TargetFilter,
        casterId: EntityId,
        sourceId: EntityId? = null,
        xValue: Int? = null,
        targetPlayerId: EntityId? = null,
        predicateContext: PredicateContext? = null
    ): String? {
        if (target !is ChosenTarget.Card) {
            return "Target must be a card in a graveyard"
        }
        if (target.zone != Zone.GRAVEYARD) {
            return "Target must be in a graveyard"
        }

        val zoneKey = ZoneKey(target.ownerId, Zone.GRAVEYARD)
        if (target.cardId !in state.getZone(zoneKey)) {
            return "Target not found in graveyard"
        }

        if (filter.excludeSelf && sourceId != null && target.cardId == sourceId) {
            return "Target must be another card"
        }

        // Use unified filter - OwnedByYou predicate handles "your graveyard" restriction; a
        // separately-chosen player target (Drafna's Restoration) flows in via targetPlayerId so
        // OwnedByTargetPlayer matches "cards in target player's graveyard".
        val context = (predicateContext ?: PredicateContext(controllerId = casterId))
            .copy(
                controllerId = casterId,
                ownerId = target.ownerId,
                sourceId = sourceId,
                xValue = xValue,
                targetPlayerId = targetPlayerId ?: predicateContext?.targetPlayerId
            )
        val matches = predicateEvaluator.matches(state, state.projectedState, target.cardId, filter.baseFilter, context)
        if (!matches) {
            return "Target does not match filter: ${filter.description}"
        }
        return null
    }

    private fun validateSpellTarget(
        state: GameState,
        target: ChosenTarget,
        filter: TargetFilter,
        casterId: EntityId,
        xValue: Int? = null,
        sourceId: EntityId? = null,
        predicateContext: PredicateContext? = null
    ): String? {
        if (target !is ChosenTarget.Spell) {
            return "Target must be a spell on the stack"
        }
        if (target.spellEntityId !in state.stack) {
            return "Target spell not on the stack"
        }

        // Use unified filter with projected state (face-down spells need projection to be seen as
        // creatures); sourceId lets source-relative predicates evaluate (Goblin Artisans'
        // NotTargetedByAbilityFromSameNamedSource).
        val context = (predicateContext ?: PredicateContext(controllerId = casterId))
            .copy(controllerId = casterId, sourceId = sourceId, xValue = xValue)
        val matches = predicateEvaluator.matches(state, state.projectedState, target.spellEntityId, filter.baseFilter, context)
        if (!matches) {
            return "Target does not match filter: ${filter.description}"
        }
        return null
    }

    /**
     * Validate a target for TargetObject, dispatching based on the filter's zone.
     */
    private fun validateObjectTarget(
        state: GameState,
        target: ChosenTarget,
        filter: TargetFilter,
        casterId: EntityId,
        sourceId: EntityId? = null,
        xValue: Int? = null,
        targetPlayerId: EntityId? = null,
        predicateContext: PredicateContext? = null
    ): String? {
        // Cross-zone union: the target is legal if it satisfies *any* clause. Validate against each
        // single-zone clause; succeed on the first that accepts, otherwise report that clause's
        // error (the most informative — the target type matched a clause's zone but failed its
        // filter). Each clause has no alternatives, so this recursion terminates.
        if (filter.isUnion) {
            val clauseErrors = filter.clauses().map { clause ->
                validateObjectTarget(
                    state, target, clause, casterId, sourceId, xValue, targetPlayerId, predicateContext
                )
            }
            if (clauseErrors.any { it == null }) return null
            return clauseErrors.firstOrNull { it != null }
                ?: "Target does not match filter: ${filter.description}"
        }
        return when (filter.zone) {
            Zone.GRAVEYARD ->
                validateGraveyardTarget(state, target, filter, casterId, sourceId, xValue, targetPlayerId, predicateContext)
            Zone.BATTLEFIELD ->
                validatePermanentTarget(state, target, filter, casterId, sourceId, xValue, predicateContext)
            Zone.STACK ->
                validateSpellTarget(state, target, filter, casterId, xValue, sourceId, predicateContext)
            else ->
                validateCardInZoneTarget(state, target, filter, casterId, xValue, sourceId, predicateContext)
        }
    }

    /**
     * Validate a target for TargetSpellOrPermanent.
     * Accepts either a spell on the stack or a permanent on the battlefield.
     * If [requirement.permanentFilter] is set, permanent targets must also match it
     * (e.g., "target spell or creature" restricts the permanent side to creatures).
     */
    private fun validateSpellOrPermanentTarget(
        state: GameState,
        target: ChosenTarget,
        requirement: TargetSpellOrPermanent,
        casterId: EntityId,
        sourceId: EntityId?,
        xValue: Int? = null,
        predicateContext: PredicateContext? = null
    ): String? {
        return when (target) {
            is ChosenTarget.Permanent -> {
                state.getEntity(target.entityId)
                    ?: return "Target not found"
                if (target.entityId !in state.getBattlefield()) {
                    return "Target must be on the battlefield or on the stack"
                }
                val filter = requirement.permanentFilter
                if (filter != null) {
                    val projected = state.projectedState
                    val context = (predicateContext ?: PredicateContext(controllerId = casterId))
                        .copy(controllerId = casterId, sourceId = sourceId, xValue = xValue)
                    if (!predicateEvaluator.matches(state, projected, target.entityId, filter, context)) {
                        return "Target does not match ${filter.description}"
                    }
                }
                null
            }
            is ChosenTarget.Spell -> {
                if (target.spellEntityId !in state.stack) {
                    return "Target spell not on the stack"
                }
                null
            }
            else -> "Target must be a spell or permanent"
        }
    }

    /**
     * Validate a card target in a non-battlefield, non-stack zone (hand, library, exile).
     */
    private fun validateCardInZoneTarget(
        state: GameState,
        target: ChosenTarget,
        filter: TargetFilter,
        casterId: EntityId,
        xValue: Int? = null,
        sourceId: EntityId? = null,
        predicateContext: PredicateContext? = null
    ): String? {
        if (target !is ChosenTarget.Card) {
            return "Target must be a card"
        }

        val expectedZone = filter.zone

        if (target.zone != expectedZone) {
            return "Target must be in ${filter.zone.displayName}"
        }

        val zoneKey = ZoneKey(target.ownerId, expectedZone)
        if (target.cardId !in state.getZone(zoneKey)) {
            return "Target not found in ${filter.zone.displayName}"
        }

        // sourceId lets source-relative predicates evaluate (e.g. ExiledWithSource — "target card
        // exiled with ~"). Mirrors the graveyard/spell validation paths.
        val context = (predicateContext ?: PredicateContext(controllerId = casterId))
            .copy(controllerId = casterId, ownerId = target.ownerId, xValue = xValue, sourceId = sourceId)
        val matches = predicateEvaluator.matches(state, state.projectedState, target.cardId, filter.baseFilter, context)
        if (!matches) {
            return "Target does not match filter: ${filter.description}"
        }
        return null
    }

    /**
     * Check if a player has shroud (e.g., from True Believer's "You have shroud"
     * or Gilded Light's "You gain shroud until end of turn").
     */
    private fun playerHasShroud(state: GameState, playerId: EntityId): Boolean =
        ControllerShroud.appliesTo(state, playerId)

    private fun playerHasHexproof(state: GameState, playerId: EntityId): Boolean =
        ControllerHexproof.appliesTo(state, playerId)

    private fun playerHasHexproofAgainst(state: GameState, playerId: EntityId, casterId: EntityId): Boolean {
        return playerId != casterId && playerHasHexproof(state, playerId)
    }
}

/**
 * Build one pending target requirement from the authoritative source context.
 *
 * Pending producers must not duplicate the dynamic-count and aggregate-cap protocol: a plain
 * maximum or a null aggregate result is not evidence that a dynamic source was resolved. This
 * helper keeps the source snapshot, typed witnesses, and unlimited candidate bound together while
 * leaving the legacy direct [TargetValidator] payload path unchanged.
 */
internal fun TargetValidator.pendingTargetRequirementInfo(
    state: GameState,
    index: Int,
    requirement: TargetRequirement,
    context: EffectContext,
    legalTargetCount: Int? = null,
    description: String = requirement.description,
): TargetRequirementInfoResult {
    val snapshot = snapshotDynamicCountsForPending(
        state = state,
        requirements = listOf(requirement),
        context = context,
    ).single()
    return when (snapshot) {
        is PendingTargetRequirementSnapshot.Unsupported ->
            TargetRequirementInfoResult.Unsupported(snapshot.reason)
        is PendingTargetRequirementSnapshot.Resolved ->
            TargetRequirementInfo.fromRequirement(
                index = index,
                requirement = snapshot.requirement,
                semanticSource = snapshot.semanticSource,
                description = description,
                minTargets = snapshot.requirement.effectiveMinCount,
                maxTargets = if (snapshot.resolvedMaxTargets == null &&
                    snapshot.requirement.unlimited &&
                    !snapshot.requirement.hasUnresolvedDynamicMaxCount()
                ) {
                    legalTargetCount
                } else {
                    null
                },
                resolvedMaxTargets = snapshot.resolvedMaxTargets,
                resolvedTotalManaValueAtMost = resolveTotalManaValueAtMostForPending(
                    state = state,
                    requirement = snapshot.semanticSource,
                    context = context,
                ),
            )
    }
}
