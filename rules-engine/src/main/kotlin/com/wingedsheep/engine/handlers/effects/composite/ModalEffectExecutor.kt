package com.wingedsheep.engine.handlers.effects.composite

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
import java.util.UUID
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
    private val effectExecutor: (GameState, Effect, EffectContext) -> EffectResult
) : EffectExecutor<ModalEffect> {

    override val effectType: KClass<ModalEffect> = ModalEffect::class

    private val targetValidator = TargetValidator()

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

        // Resolve "choose up to <DynamicAmount>" at runtime. minChooseCount is treated
        // as 0 (player may always decline picks once the dynamic-evaluated cap is
        // exhausted); chooseCount becomes min(evaluated, modes.size).
        val (effectiveChooseCount, effectiveMinChooseCount) = if (effect.dynamicChooseCount != null) {
            val evaluator = com.wingedsheep.engine.handlers.DynamicAmountEvaluator()
            val raw = evaluator.evaluate(state, effect.dynamicChooseCount!!, context)
            val capped = raw.coerceIn(0, effect.modes.size)
            capped to 0
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

        val decisionId = UUID.randomUUID().toString()
        val decision = ChooseOptionDecision(
            id = decisionId,
            playerId = playerId,
            prompt = prompt,
            context = DecisionContext(
                sourceId = context.sourceId,
                sourceName = sourceName,
                phase = DecisionPhase.RESOLUTION
            ),
            options = modeDescriptions
        )

        // Preserve outer-scope targets so no-target modes can resolve ContextTarget
        // references to targets chosen by the enclosing spell/ability (e.g.,
        // Manifold Mouse's BeginCombat trigger targets a Mouse, then picks a
        // keyword mode that grants the keyword to that outer target).
        val continuation = ModalContinuation(
            decisionId = decisionId,
            controllerId = context.controllerId,
            sourceId = context.sourceId,
            sourceName = sourceName,
            modes = effect.modes,
            xValue = context.xValue,
            triggeringEntityId = context.triggeringEntityId,
            chooseCount = effectiveChooseCount,
            minChooseCount = effectiveMinChooseCount,
            selectedModeIndices = emptyList(),
            availableIndices = availableIndices,
            outerTargets = context.targets,
            outerAlignedTargets = context.alignedTargets,
            outerNamedTargets = context.pipeline.namedTargets,
            recordChosenModesOnSource = effect.excludePreviouslyChosenModes,
            recordChosenModesThisTurn = effect.excludeModesChosenThisTurn
        )

        val stateWithDecision = state.withPendingDecision(decision)
        val stateWithContinuation = stateWithDecision.pushContinuation(continuation)

        return EffectResult.paused(
            stateWithContinuation,
            decision,
            listOf(
                DecisionRequestedEvent(
                    decisionId = decisionId,
                    playerId = playerId,
                    decisionType = "CHOOSE_OPTION",
                    prompt = decision.prompt
                )
            )
        )
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
            alignedTargets = context.alignedTargets
        ).map { entry -> entry.copy(targetEntryStamps = context.targetEntryStamps) }
        val sourceName = context.sourceId?.let { id -> state.getEntity(id)?.get<CardComponent>()?.name }
        val baseCtx = PreTargetedEffectContext(
            controllerId = context.controllerId,
            sourceId = context.sourceId,
            sourceName = sourceName,
            xValue = context.xValue,
            triggeringEntityId = context.triggeringEntityId,
            triggeringPlayerId = context.triggeringPlayerId,
            storedCollections = context.pipeline.storedCollections,
            targetingSourceType = context.targetingSourceType
        )
        return processPreTargetedEffectQueue(state, entries, baseCtx, effectExecutor, targetValidator, emptyList())
    }

    companion object {
        /**
         * Label for the synthetic "no mode" option appended when a modal effect
         * allows declining (minChooseCount < chooseCount, e.g., "choose up to one").
         */
        const val DECLINE_MODE_LABEL: String = "Don't choose a mode"

        /** Build the drain queue from pre-chosen modes / targets. */
        fun buildModeEntries(
            effect: ModalEffect,
            chosenModes: List<Int>,
            modeTargetsOrdered: List<List<com.wingedsheep.engine.state.components.stack.ChosenTarget>>,
            modeTargetRequirements: Map<Int, List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>>,
            modeTargetRequirementsOrdered: List<List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>> = emptyList(),
            alignedTargets: List<com.wingedsheep.engine.state.components.stack.ChosenTarget?> = emptyList()
        ): List<PreTargetedEffectEntry> {
            var flatSlotStart = 0
            var prefixMetadataLocked = true
            return chosenModes.mapIndexed { ordinal, modeIndex ->
                val mode = effect.modes.getOrNull(modeIndex)
                val rawTargets = modeTargetsOrdered.getOrNull(ordinal) ?: emptyList()
                val hasLockedRequirements = modeTargetRequirementsOrdered.size == chosenModes.size ||
                    modeTargetRequirements.containsKey(modeIndex)
                val reqs = modeTargetRequirementsOrdered.getOrNull(ordinal)
                    ?: modeTargetRequirements[modeIndex]
                    ?: emptyList()
                val slotCount = reqs.sumOf { it.count.coerceAtLeast(0) }
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
                    targetShapeLocked && alignmentAvailable
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
                flatSlotStart += slotCount
                entry
            }
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
    val storedCollections: Map<String, List<com.wingedsheep.sdk.model.EntityId>> = emptyMap(),
    val targetingSourceType: com.wingedsheep.engine.handlers.TargetingSourceType =
        com.wingedsheep.engine.handlers.TargetingSourceType.ANY
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
    accumulatedEvents: List<GameEvent>
): EffectResult {
    if (entries.isEmpty()) return EffectResult.success(state, accumulatedEvents)

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
            xValue = ctx.xValue,
            allowedTargetSlots = head.alignedTargets,
            targetEntryStamps = head.targetEntryStamps,
            targetingSourceType = ctx.targetingSourceType,
            triggeringEntityId = ctx.triggeringEntityId,
            triggeringPlayerId = ctx.triggeringPlayerId,
            storedCollections = ctx.storedCollections
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

    val effectContext = EffectContext(
        sourceId = ctx.sourceId,
        controllerId = ctx.controllerId,
        xValue = ctx.xValue,
        targets = resolutionTargets.targets,
        alignedTargets = resolutionTargets.alignedTargets,
        targetEntryStamps = head.targetEntryStamps,
        targetingSourceType = ctx.targetingSourceType,
        pipeline = PipelineState(
            namedTargets = EffectContext.buildNamedTargets(
                head.targetRequirements,
                resolutionTargets.alignedTargets
            ),
            storedCollections = ctx.storedCollections
        ),
        triggeringEntityId = ctx.triggeringEntityId,
        triggeringPlayerId = ctx.triggeringPlayerId
    )

    // Pre-push the tail continuation so that if the effect pauses, our frame sits
    // beneath the inner decision's frames and auto-resumes when they finish.
    val stateForExecution = if (tail.isNotEmpty()) {
        state.pushContinuation(
            ModalPreChosenContinuation(
                decisionId = "modal-pre-chosen-${UUID.randomUUID()}",
                controllerId = ctx.controllerId,
                sourceId = ctx.sourceId,
                sourceName = ctx.sourceName,
                xValue = ctx.xValue,
                triggeringEntityId = ctx.triggeringEntityId,
                triggeringPlayerId = ctx.triggeringPlayerId,
                storedCollections = ctx.storedCollections,
                targetingSourceType = ctx.targetingSourceType,
                remainingEntries = tail
            )
        )
    } else state

    val result = effectExecutor(stateForExecution, head.effect, effectContext)
    val nextEvents = accumulatedEvents + result.events

    if (result.isPaused) {
        return EffectResult.paused(result.state, result.pendingDecision!!, nextEvents)
    }
    if (result.error != null) {
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
                ctx,
                effectExecutor,
                targetValidator,
                nextEvents
            )
        }
        return EffectResult(state = result.state, events = nextEvents, error = result.error)
    }

    // Success — pop the pre-pushed tail continuation and drain the rest synchronously.
    val nextState = if (tail.isNotEmpty()) {
        val (_, afterPop) = result.state.popContinuation()
        afterPop
    } else result.state

    return processPreTargetedEffectQueue(nextState, tail, ctx, effectExecutor, targetValidator, nextEvents)
}
