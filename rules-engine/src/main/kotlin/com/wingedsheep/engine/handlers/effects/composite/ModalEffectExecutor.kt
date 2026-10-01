package com.wingedsheep.engine.handlers.effects.composite

import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.modal.ChosenModeMemory
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import kotlin.reflect.KClass

/**
 * Executor for ModalEffect.
 * Handles "Choose one —" / "Choose two —" modal spells and modal triggered / activated
 * abilities.
 *
 * Two paths, dispatched on whether the mode was picked before resolution:
 *
 * - **Pre-chosen modes** (modal spells, rules 700.2 / 601.2b–c): every modal *spell*
 *   reaches this executor with [SpellOnStackComponent.chosenModes] populated —
 *   [com.wingedsheep.engine.handlers.actions.spell.CastSpellHandler] runs the
 *   cast-time mode + per-mode target picker (`pauseForCastTimeModeSelection` →
 *   `presentCastModalTargetDecision`) before the spell ever lands on the stack.
 *   This branch then drains each chosen mode in order with its captured targets,
 *   pausing if a sub-effect needs another decision; remaining modes ride along on a
 *   [ModalPreChosenContinuation] that auto-resumes once the inner decision resolves.
 *   Per-mode Rule 608.2b re-validation is applied against
 *   [SpellOnStackComponent.modeTargetRequirements].
 *
 * - **Resolution-time mode picking**: whatever arrives here with `chosenModes` empty. The executor
 *   presents a [ChooseOptionDecision] inline, pushes [ModalContinuation], and the modal-and-clone
 *   resumer drives target selection via `processChosenModeQueue`. Two populations still take this
 *   path:
 *   - modal **activated** abilities, which don't go through the cast pipeline; and
 *   - a [ModalEffect] **nested inside another effect** — inside a gated effect, a reflexive
 *     trigger, a pipeline step — where the mode question isn't the spell's or ability's own.
 *
 *   Modal *triggered* abilities do **not**: their top-level modes and per-mode targets are picked
 *   as the ability is put onto the stack (CR 603.3c / 700.2b, and 603.3d for the targets) by
 *   [com.wingedsheep.engine.event.TriggerProcessor], so they reach this executor pre-chosen just
 *   like a cast spell.
 *
 * @param effectExecutor Function to execute a sub-effect (provided by registry)
 */
class ModalEffectExecutor(
    private val effectExecutor: (GameState, Effect, EffectContext) -> EffectResult,
    private val amountEvaluator: DynamicAmountEvaluator,
    private val targetValidator: TargetValidator
) : EffectExecutor<ModalEffect> {

    override val effectType: KClass<ModalEffect> = ModalEffect::class

    override fun execute(
        state: GameState,
        effect: ModalEffect,
        context: EffectContext
    ): EffectResult {
        // Pre-chosen modes flow: used for both direct spell casts and copies
        // (storm / CopyTargetSpell / chain). Both resolvers populate the modal
        // fields from the appropriate stack component (SpellOnStackComponent for
        // spells, TriggeredAbilityOnStackComponent for copies — 700.2g).
        if (context.chosenModes.isNotEmpty()) {
            return executePreChosenModes(state, effect, context)
        }

        // Mode not pre-chosen — present mode selection decision (triggered/activated
        // modal abilities, rule 603.3c; modal spells always arrive pre-chosen via the
        // cast-time picker in CastSpellHandler).
        val playerId = context.controllerId

        val sourceName = context.sourceId?.let { sourceId ->
            state.getEntity(sourceId)?.get<CardComponent>()?.name
        }

        // Resolve "choose up to <DynamicAmount>" at runtime.
        //
        // The floor comes from [ModalEffect.dynamicMinChooseCount] when the card set one, and only
        // falls back to 0 — "you may decline every pick" — when it didn't. Forcing 0 here made the
        // synthetic "Don't choose a mode" option appear on a *mandatory* dynamic modal
        // (Frankenstein's Monster: exactly X counters, one per card exiled), letting the player
        // walk away from picks the card doesn't let them skip. `ModalChooseCounts.forCast` has
        // always read the floor for modal *spells*; this is the resolution-time path that a modal
        // nested inside another effect takes, and it had drifted from it.
        //
        // The ceiling is capped by the mode list only when each mode can be picked once. With
        // [ModalEffect.allowRepeat] the same mode stays on the menu every pick (CR 700.2d), so
        // three modes can absorb any number of picks and capping at `modes.size` would silently
        // shrink X. `forCast` makes the same distinction, so the two paths now agree on both bounds.
        val (effectiveChooseCount, effectiveMinChooseCount) = if (effect.dynamicChooseCount != null) {
            val evaluator = amountEvaluator
            val raw = evaluator.evaluate(state, effect.dynamicChooseCount!!, context)
            val capped = if (effect.allowRepeat) {
                raw.coerceAtLeast(0)
            } else {
                raw.coerceIn(0, effect.modes.size)
            }
            val floor = effect.dynamicMinChooseCount
                ?.let { evaluator.evaluate(state, it, context).coerceIn(0, capped) }
                ?: 0
            capped to floor
        } else {
            effect.chooseCount to effect.minChooseCount
        }

        // Evaluated cap = 0 → no modes will be chosen; resolve as a no-op success.
        if (effectiveChooseCount == 0) {
            return EffectResult.success(state, emptyList())
        }

        // "Choose one that hasn't been chosen" (Gandalf the Grey — game-scoped) / "…this turn"
        // (Breeches, Eager Pillager — turn-scoped): exclude any mode this source has already
        // chosen, recorded in a per-source memory component. If every mode has been chosen, the
        // ability has no legal mode and does nothing.
        val alreadyChosen = ChosenModeMemory.excludedFor(state, context.sourceId, effect)

        val availableIndices = effect.modes.indices.filter { it !in alreadyChosen }
        if (availableIndices.isEmpty()) {
            return EffectResult.success(state, emptyList())
        }
        val baseOptions = availableIndices.map { effect.modes[it].description }
        // "Choose up to N" — allow declining a mode pick when minChooseCount has
        // already been satisfied (here, before any picks, when minChooseCount = 0).
        val canDecline = effectiveMinChooseCount < effectiveChooseCount
        val modeDescriptions = if (canDecline) baseOptions + DECLINE_MODE_LABEL else baseOptions

        val basePrompt = "Choose a mode for ${sourceName ?: "modal spell"}"
        val prompt = if (effectiveChooseCount > 1) "$basePrompt (1 of $effectiveChooseCount)" else basePrompt

        val decision = { decisionId: String -> ChooseOptionDecision(
            id = decisionId,
            playerId = playerId,
            prompt = prompt,
            context = DecisionContext(
                sourceId = context.sourceId,
                sourceName = sourceName,
                phase = DecisionPhase.RESOLUTION
            ),
            options = modeDescriptions
        ) }

        // Preserve outer-scope targets so no-target modes can resolve ContextTarget
        // references to targets chosen by the enclosing spell/ability (e.g.,
        // Manifold Mouse's BeginCombat trigger targets a Mouse, then picks a
        // keyword mode that grants the keyword to that outer target).
        val continuation = ModalContinuation(
            controllerId = context.controllerId,
            sourceId = context.sourceId,
            objectReferences = context.objectReferences,
            sourceName = sourceName,
            modes = effect.modes,
            xValue = context.xValue,
            triggeringEntityId = context.triggeringEntityId,
            chooseCount = effectiveChooseCount,
            minChooseCount = effectiveMinChooseCount,
            selectedModeIndices = emptyList(),
            availableIndices = availableIndices,
            allowRepeat = effect.allowRepeat,
            outerTargets = context.targets,
            outerAlignedTargets = context.alignedTargets,
            pipeline = context.pipeline,
            outerNamedTargets = context.pipeline.namedTargets,
            recordChosenModesOnSource = effect.excludePreviouslyChosenModes,
            recordChosenModesThisTurn = effect.excludeModesChosenThisTurn
        )

        return EffectResult.from(state.suspendForDecision(decision, continuation))
    }

    /**
     * Iterate each pre-chosen mode in order, executing its effect with per-mode targets.
     * Called synchronously on the first invocation and by the auto-resumer for each
     * remaining mode after a mode's effect pauses.
     */
    private fun executePreChosenModes(
        state: GameState,
        effect: ModalEffect,
        context: EffectContext
    ): EffectResult {
        val entries = buildModeEntries(
            effect,
            chosenModes = context.chosenModes,
            modeTargetsOrdered = context.modeTargetsOrdered,
            modeTargetRequirements = context.modeTargetRequirements,
            modeTargetRequirementsOrdered = context.modeTargetRequirementsOrdered,
            alignedTargets = context.alignedTargets,
            modeTargetSlotStarts = context.modeTargetSlotStarts
        ).map { entry -> entry.copy(targetEntryStamps = context.targetEntryStamps) }
        val sourceName = context.sourceId?.let { id -> state.getEntity(id)?.get<CardComponent>()?.name }
        val outerSlotCount = context.modeTargetSlotStarts.firstOrNull()?.coerceAtLeast(0) ?: 0
        val outerAlignedTargets = context.alignedTargets.take(outerSlotCount)
        val baseCtx = PreTargetedEffectContext(
            controllerId = context.controllerId,
            sourceId = context.sourceId,
            sourceName = sourceName,
            xValue = context.xValue,
            triggeringEntityId = context.triggeringEntityId,
            triggeringPlayerId = context.triggeringPlayerId,
            triggerContext = context.triggerContext,
            // The resolution so far, carried into each mode. A mode's effect can read what an
            // earlier step of the same resolution stored — Cemetery Desecrator's two modes both
            // spell X as `StoredCardManaValue("exiledCard")`, the collection its reflexive
            // trigger's action half filled. Dropping it made every such amount read 0.
            pipeline = context.pipeline,
            targetingSourceType = context.targetingSourceType,
            outerTargets = outerAlignedTargets.filterNotNull(),
            outerAlignedTargets = outerAlignedTargets,
            outerNamedTargets = context.pipeline.namedTargets,
            objectReferences = context.objectReferences
        )
        return processPreTargetedEffectQueue(state, entries, baseCtx, effectExecutor, targetValidator, emptyList())
    }

    companion object {
        /**
         * Label for the synthetic "no mode" option appended when a modal effect
         * allows declining (minChooseCount < chooseCount, e.g., "choose up to one").
         */
        const val DECLINE_MODE_LABEL: String = "Don't choose a mode"

        /**
         * Build the drain queue from pre-chosen modes / targets.
         *
         * Each entry's slot metadata (its slice of the original flat target payload, the
         * position-preserving aligned targets and the CR 608.2b legality mask) is derived in pick
         * order, because that is the order the flat payload was laid out in. The returned queue is
         * then ordered for execution in printed mode order, each pick keeping its own target slice
         * and metadata.
         */
        fun buildModeEntries(
            effect: ModalEffect,
            chosenModes: List<Int>,
            modeTargetsOrdered: List<List<com.wingedsheep.engine.state.components.stack.ChosenTarget>>,
            modeTargetRequirements: Map<Int, List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>>,
            modeTargetRequirementsOrdered: List<List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>> = emptyList(),
            alignedTargets: List<com.wingedsheep.engine.state.components.stack.ChosenTarget?> = emptyList(),
            modeTargetSlotStarts: List<Int> = emptyList()
        ): List<PreTargetedEffectEntry> {
            val explicitSlotStarts = modeTargetSlotStarts.isNotEmpty()
            var derivedFlatSlotStart = 0
            var previousExplicitEnd = 0
            var prefixMetadataLocked = true
            val entriesInPickOrder = chosenModes.mapIndexed { ordinal, modeIndex ->
                val mode = effect.modes.getOrNull(modeIndex)
                val rawTargets = modeTargetsOrdered.getOrNull(ordinal) ?: emptyList()
                val hasLockedRequirements = modeTargetRequirementsOrdered.size == chosenModes.size ||
                    modeTargetRequirements.containsKey(modeIndex)
                val reqs = modeTargetRequirementsOrdered.getOrNull(ordinal)
                    ?: modeTargetRequirements[modeIndex]
                    ?: emptyList()
                val slotCount = reqs.sumOf { it.count.coerceAtLeast(0) }
                val flatSlotStart = if (explicitSlotStarts) {
                    modeTargetSlotStarts.getOrNull(ordinal) ?: -1
                } else {
                    derivedFlatSlotStart
                }
                val offsetMetadataValid = !explicitSlotStarts || (
                    modeTargetSlotStarts.size == chosenModes.size &&
                        flatSlotStart >= 0 &&
                        (ordinal == 0 || flatSlotStart == previousExplicitEnd)
                    )
                val alignmentAvailable = flatSlotStart >= 0 &&
                    flatSlotStart + slotCount <= alignedTargets.size
                val alignedSlice = if (alignmentAvailable) {
                    alignedTargets.subList(flatSlotStart, flatSlotStart + slotCount)
                } else {
                    emptyList()
                }
                val targetShapeLocked = rawTargets.size == slotCount
                val slotMetadataLocked = prefixMetadataLocked &&
                    (hasLockedRequirements || (slotCount == 0 && rawTargets.isEmpty())) &&
                    targetShapeLocked && alignmentAvailable && offsetMetadataValid
                val targets = if (slotMetadataLocked) {
                    alignedSlice.mapIndexed { index, aligned -> aligned ?: rawTargets[index] }
                } else {
                    rawTargets
                }
                val entry = PreTargetedEffectEntry(
                    effect = mode?.effect ?: error("Invalid pre-chosen mode index: $modeIndex"),
                    targets = targets,
                    targetRequirements = reqs,
                    flatSlotStart = flatSlotStart,
                    flatSlotCount = slotCount,
                    alignedTargets = alignedSlice,
                    targetSlotLegality = alignedSlice.map { it != null },
                    slotMetadataLocked = slotMetadataLocked
                )
                prefixMetadataLocked = slotMetadataLocked
                if (explicitSlotStarts) {
                    previousExplicitEnd = flatSlotStart + slotCount
                } else {
                    derivedFlatSlotStart += slotCount
                }
                entry
            }
            // Execute in printed mode order while retaining each pick's own target slice.
            return chosenModes.indices
                .sortedBy { ordinal -> chosenModes[ordinal] }
                .map { ordinal -> entriesInPickOrder[ordinal] }
        }

        /** Convenience overload reading from a [SpellOnStackComponent]. */
        fun buildModeEntries(effect: ModalEffect, spellOnStack: SpellOnStackComponent): List<PreTargetedEffectEntry> =
            buildModeEntries(
                effect,
                spellOnStack.chosenModes,
                spellOnStack.modeTargetsOrdered,
                spellOnStack.modeTargetRequirements,
                spellOnStack.modeTargetRequirementsOrdered
            )
    }
}

/** Base fields needed to build per-mode [EffectContext]s during pre-chosen mode drainage. */
internal data class PreTargetedEffectContext(
    val controllerId: com.wingedsheep.sdk.model.EntityId,
    val sourceId: com.wingedsheep.sdk.model.EntityId?,
    val sourceName: String?,
    val xValue: Int?,
    val triggeringEntityId: com.wingedsheep.sdk.model.EntityId?,
    val triggeringPlayerId: com.wingedsheep.sdk.model.EntityId? = null,
    val triggerContext: com.wingedsheep.engine.event.TriggerContext? = null,
    /**
     * Pipeline state the enclosing resolution had already built — stored collections, numbers,
     * chosen values — which each mode's own [EffectContext] inherits. Only the per-mode
     * `namedTargets` are rebuilt on top of it, because those *are* per mode.
     *
     * Defaults to empty for the callers that genuinely have no enclosing pipeline (splice, CR
     * 702.47b: each spliced card's text is its own resolution).
     */
    val pipeline: PipelineState = PipelineState.EMPTY,
    val targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
    val outerTargets: List<com.wingedsheep.engine.state.components.stack.ChosenTarget> = emptyList(),
    val outerAlignedTargets: List<com.wingedsheep.engine.state.components.stack.ChosenTarget?> = emptyList(),
    val outerNamedTargets: Map<String, com.wingedsheep.engine.state.components.stack.ChosenTarget> = emptyMap(),
    val objectReferences: com.wingedsheep.engine.handlers.ObjectReferenceEnvironment = com.wingedsheep.engine.handlers.ObjectReferenceEnvironment()
)

/**
 * Process the remaining pre-chosen modes of a choose-N modal spell.
 *
 * Synchronously executes each entry's effect in order, applying per-mode 608.2b
 * re-validation against the original target requirements. When a mode's
 * execution pauses, pushes a [ModalPreChosenContinuation] holding the tail and
 * surfaces the pause; the continuation is auto-resumed once the inner decision
 * resolves.
 *
 * Shared between [ModalEffectExecutor] (initial entry) and the auto-resumer for
 * [ModalPreChosenContinuation].
 */
internal fun processPreTargetedEffectQueue(
    state: GameState,
    entries: List<PreTargetedEffectEntry>,
    ctx: PreTargetedEffectContext,
    effectExecutor: (GameState, Effect, EffectContext) -> EffectResult,
    targetValidator: TargetValidator,
    accumulatedEvents: List<GameEvent>,
    accumulatedDiagnostics: List<DiagnosticSignal> = emptyList(),
): EffectResult {
    if (entries.isEmpty()) {
        return EffectResult.success(
            state,
            accumulatedEvents,
            diagnostics = accumulatedDiagnostics,
        )
    }

    val head = entries.first()
    val tail = entries.drop(1)

    // CR 608.2b re-checks each locked target independently. The flat top-level resolution pass
    // already decides whether the whole stack object fizzles; this per-entry pass only filters
    // the mode/splice slice that this generic executor is about to consume.
    val cardComponent = ctx.sourceId?.let { state.getEntity(it)?.get<CardComponent>() }
    val sourceColors = cardComponent?.colors ?: emptySet()
    val sourceSubtypes = cardComponent?.typeLine?.subtypes?.map { it.value }?.toSet() ?: emptySet()

    val targetedEntry = head.targetRequirements.isNotEmpty() || head.targets.isNotEmpty()
    val slotMetadataValid = !targetedEntry || (
        head.slotMetadataLocked &&
            head.flatSlotStart >= 0 &&
            head.flatSlotCount == head.targets.size &&
            head.alignedTargets.size == head.targets.size &&
            head.targetSlotLegality.size == head.targets.size &&
            head.targetSlotLegality == head.alignedTargets.map { it != null }
        )
    val resolutionTargets = if (targetedEntry && slotMetadataValid) {
        targetValidator.filterTargetsAtResolution(
            state = state,
            targets = head.targets,
            requirements = head.targetRequirements,
            casterId = ctx.controllerId,
            sourceColors = sourceColors,
            sourceSubtypes = sourceSubtypes,
            sourceId = ctx.sourceId,
            // The chosen X, threaded exactly as the cast and activation paths do. Without it an
            // X-clamped mode ("up to X target creatures", Profane Command) re-validates against
            // the *static* placeholder count of 1, so every legal cast declaring two or more
            // targets fails this re-check.
            xValue = ctx.xValue,
            allowedTargetSlots = head.alignedTargets,
            targetEntryStamps = head.targetEntryStamps,
            // The resolving object's own kind, recorded on the context by the resolver (spell,
            // activated or triggered ability); ANY when the caller could not say.
            targetingSourceType = ctx.targetingSourceType,
            triggeringEntityId = ctx.triggeringEntityId,
            triggeringPlayerId = ctx.triggeringPlayerId,
            storedCollections = ctx.pipeline.storedCollections
        )
    } else TargetValidator.ResolutionTargetPayload(emptyList(), List(head.targets.size) { null })

    // A mode whose entire locked target slice is illegal has no target payload, but the mode's
    // other instructions still belong to the resolving parent object if another mode has a legal
    // target (CR 608.2b). Execute with the empty payload so a CompositeEffect can skip only its
    // target-consuming child and continue to a non-targeted sibling. A direct target-consuming
    // executor may report its missing target as an error; that error is treated as the no-op for
    // this illegal slice below, rather than aborting the remaining mode queue.
    val hasIllegalTargetPortion = targetedEntry && (
        !slotMetadataValid || head.targets.isEmpty() || resolutionTargets.alignedTargets.any { it == null }
    )

    val modeTargets = if (targetedEntry) resolutionTargets.targets else ctx.outerTargets
    val modeAlignedTargets = if (targetedEntry) {
        resolutionTargets.alignedTargets
    } else {
        ctx.outerAlignedTargets
    }
    // The enclosing resolution's named targets, with this mode's own named targets laid over them
    // for a targeted mode. Slots dropped by CR 608.2b stay unmapped.
    val enclosingNamedTargets = ctx.pipeline.namedTargets + ctx.outerNamedTargets
    val modeNamedTargets = if (targetedEntry) {
        enclosingNamedTargets +
            EffectContext.buildNamedTargets(head.targetRequirements, resolutionTargets.alignedTargets)
    } else {
        enclosingNamedTargets
    }
    val effectContext = EffectContext(
        sourceId = ctx.sourceId,
        objectReferences = ctx.objectReferences,
        controllerId = ctx.controllerId,
        xValue = ctx.xValue,
        targets = modeTargets,
        alignedTargets = modeAlignedTargets,
        targetEntryStamps = head.targetEntryStamps,
        targetingSourceType = ctx.targetingSourceType,
        // The enclosing resolution's pipeline, with this mode's own named targets laid over it.
        // A bare `PipelineState(namedTargets = …)` here dropped every stored collection, number
        // and chosen value the resolution had accumulated before the modal.
        pipeline = ctx.pipeline.copy(namedTargets = modeNamedTargets),
        triggeringEntityId = ctx.triggeringEntityId,
        triggeringPlayerId = ctx.triggeringPlayerId ?: ctx.triggerContext?.triggeringPlayerId,
        triggerContext = ctx.triggerContext
    )

    // Pre-push the tail continuation so that if the effect pauses, our frame sits
    // beneath the inner decision's frames and auto-resumes when they finish.
    val stateForExecution = if (tail.isNotEmpty()) {
        state.pushContinuation(
            ModalPreChosenContinuation(
                controllerId = ctx.controllerId,
                sourceId = ctx.sourceId,
                objectReferences = ctx.objectReferences,
                sourceName = ctx.sourceName,
                xValue = ctx.xValue,
                triggeringEntityId = ctx.triggeringEntityId,
                triggeringPlayerId = ctx.triggeringPlayerId,
                triggerContext = ctx.triggerContext,
                pipeline = ctx.pipeline,
                targetingSourceType = ctx.targetingSourceType,
                outerTargets = ctx.outerTargets,
                outerAlignedTargets = ctx.outerAlignedTargets,
                outerNamedTargets = ctx.outerNamedTargets,
                remainingEntries = tail
            )
        )
    } else state

    val result = effectExecutor(stateForExecution, head.effect, effectContext)
    val nextEvents = accumulatedEvents + result.events
    val nextDiagnostics = accumulatedDiagnostics + result.diagnostics
    val nextCtx = ctx.copy(objectReferences = ctx.objectReferences.authorize(result.events))

    if (result.diagnostics.isNotEmpty()) {
        val cleanState = if (tail.isNotEmpty()) {
            val (_, afterPop) = result.state.popContinuation()
            afterPop
        } else {
            result.state
        }
        return EffectResult(
            state = cleanState,
            events = nextEvents,
            outcome = result.outcome as? Outcome.Rejected
                ?: Outcome.Rejected(Rejection.ExecutionFailed("Unsupported path during modal resolution")),
            diagnostics = nextDiagnostics,
        )
    }

    if (result.outcome is Outcome.Paused) {
        return EffectResult.propagatePause(result.state, nextEvents, diagnostics = nextDiagnostics)
    }
    if (result.outcome is Outcome.Rejected) {
        if (hasIllegalTargetPortion) {
            val nextState = if (tail.isNotEmpty()) {
                val (_, afterPop) = result.state.popContinuation()
                afterPop
            } else {
                result.state
            }
            return processPreTargetedEffectQueue(
                nextState,
                tail,
                nextCtx,
                effectExecutor,
                targetValidator,
                nextEvents,
                nextDiagnostics,
            )
        }
        return EffectResult(
            state = result.state,
            events = nextEvents,
            outcome = result.outcome,
            diagnostics = nextDiagnostics,
        )
    }

    // Success — pop the pre-pushed tail continuation and drain the rest synchronously.
    val nextState = if (tail.isNotEmpty()) {
        val (_, afterPop) = result.state.popContinuation()
        afterPop
    } else result.state

    return processPreTargetedEffectQueue(
        nextState,
        tail,
        nextCtx,
        effectExecutor,
        targetValidator,
        nextEvents,
        nextDiagnostics,
    )
}
