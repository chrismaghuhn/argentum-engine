package com.wingedsheep.engine.handlers.effects.stack

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ResolvingSpellCopyPayload
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CopyTargetSpellEffect
import kotlin.reflect.KClass

/**
 * Executor for CopyTargetSpellEffect.
 * Copies a targeted spell on the stack, allowing the controller to choose new targets.
 *
 * Reads the targeted spell's effect and target requirements from its components on the stack,
 * then creates [CopyTargetSpellEffect.copies] copies (one by default). If the original spell has
 * targets, prompts for new target selection once per copy (reusing StormCopyTargetContinuation,
 * whose resumer walks the remaining copies).
 */
class CopyTargetSpellExecutor(
    private val cardRegistry: com.wingedsheep.engine.registry.CardRegistry,
    private val targetFinder: TargetFinder = TargetFinder()
) : EffectExecutor<CopyTargetSpellEffect> {

    override val effectType: KClass<CopyTargetSpellEffect> = CopyTargetSpellEffect::class

    private val dynamicAmountEvaluator = com.wingedsheep.engine.handlers.DynamicAmountEvaluator()

    override fun execute(
        state: GameState,
        effect: CopyTargetSpellEffect,
        context: EffectContext
    ): EffectResult {
        val spellEntityId = context.resolveTarget(effect.target)
            ?: return EffectResult.error(state, "No target spell to copy")

        // "Copy it for each …" clauses resolve their count here (Thousand-Year Storm). Zero
        // matching prior spells means no copies at all — the ability still resolved.
        val copyCount = dynamicAmountEvaluator.evaluate(state, effect.copies, context)
        if (copyCount <= 0) return EffectResult.success(state)

        val resolvingPayload = context.resolvingSpellCopyPayload
            ?.takeIf { it.sourceSpellId == spellEntityId }
        val container = state.getEntity(spellEntityId)
        if (container == null && resolvingPayload == null) {
            return EffectResult.error(state, "Target spell entity not found on stack")
        }

        val cardComponent = resolvingPayload?.card ?: container?.get<CardComponent>()
            ?: return EffectResult.error(state, "Target spell has no CardComponent")

        // Permanent spells (creatures, artifacts, ...) have no spellEffect; their
        // resolution puts a permanent onto the battlefield. Only the
        // TriggeredAbilityOnStackComponent fallback path needs a spellEffect.
        val spellEffect = resolvingPayload?.effectiveSpellEffect ?: cardComponent.spellEffect
        val spellName = cardComponent.name
        val targetsComponent = resolvingPayload?.targets ?: container?.get<TargetsComponent>()
        val targetRequirements = targetsComponent?.targetRequirements ?: emptyList()

        val stackResolver = StackResolver(cardRegistry = cardRegistry)

        // Token-side riders (CR 707.10f): keywords baked onto, and a delayed sacrifice trigger for,
        // the token the copy resolves into when the copied spell is a permanent spell. Stamped on
        // the copy entity and consumed by StackResolver at resolution.
        val tokenRiders = if (effect.addedTokenKeywords.isNotEmpty() || effect.sacrificeTokenAtStep != null) {
            com.wingedsheep.engine.state.components.stack.SpellCopyTokenRidersComponent(
                addedKeywords = effect.addedTokenKeywords,
                sacrificeAtStep = effect.sacrificeTokenAtStep,
                sacrificeOnlyOnControllersTurn = effect.sacrificeTokenOnlyOnControllersTurn
            )
        } else null

        // Propagate modal info from the source spell (700.2g — copies keep the
        // same chosen modes). Targets inherit by default; a future enhancement
        // may let the copy controller re-choose per-mode targets.
        val sourceSpell = resolvingPayload?.spell ?: container?.get<SpellOnStackComponent>()
        val inheritedChosenModes = sourceSpell?.chosenModes ?: emptyList()
        val inheritedModeTargetRequirements = sourceSpell?.modeTargetRequirements ?: emptyMap()

        // Modal source (700.2g): modes are fixed for the copy, but per 707.10c the
        // copy controller may pick new targets per mode. If no mode has target
        // requirements, inherit verbatim; otherwise drive per-mode retargeting via
        // StormCopyEffectExecutor.driveStormModalCopies, once per copy.
        if (inheritedChosenModes.isNotEmpty()) {
            val hasAnyTargetedMode = inheritedChosenModes.any { modeIdx ->
                inheritedModeTargetRequirements[modeIdx]?.isNotEmpty() == true
            }
            if (!hasAnyTargetedMode) {
                return EffectResult.from(
                    putInheritedCopies(
                        state, stackResolver, spellEntityId, context.controllerId, copyCount,
                        effect.keywordsForCopy.toSet(), effect.removeLegendary, tokenRiders,
                        resolvingPayload
                    )
                )
            }
            return EffectResult.from(StormCopyEffectExecutor.driveStormModalCopies(
                state = state,
                stackResolver = stackResolver,
                targetFinder = targetFinder,
                sourceId = spellEntityId,
                controllerId = context.controllerId,
                spellName = spellName,
                chosenModes = inheritedChosenModes,
                modeTargetRequirements = inheritedModeTargetRequirements,
                accumulatedOrdinalTargets = emptyList(),
                currentOrdinal = 0,
                remainingCopies = copyCount,
                totalCopies = copyCount,
                priorEvents = emptyList(),
                keywordsForCopy = effect.keywordsForCopy.toSet(),
                removeLegendary = effect.removeLegendary,
                resolvingSpellCopyPayload = resolvingPayload
            ))
        }

        // If the original spell has no targets, create the copy immediately. Every spell copy
        // uses StackResolver.putSpellCopy so the copy remains a real spell object and inherits
        // the complete immutable stack payload (including cast-time cost snapshots). This is
        // especially important for a cost-linked trigger: its source spell may leave the stack,
        // and each copy must retain the same copyable characteristics and payload under
        // CR 707.10.
        if (targetRequirements.isEmpty()) {
            return EffectResult.from(
                putInheritedCopies(
                    state, stackResolver, spellEntityId, context.controllerId, copyCount,
                    effect.keywordsForCopy.toSet(), effect.removeLegendary, tokenRiders,
                    resolvingPayload
                )
            )
        }

        // Spell has targets — prompt for new target selection. Permanent spells
        // (spellEffect == null) are supported: the continuation resumes via
        // putSpellCopy which clones the source's CardComponent, and the
        // CR 707.10f token tagging happens at resolution in StackResolver.
        return promptForCopyTargets(
            state, context, spellEntityId, spellEffect, targetRequirements, spellName,
            effect.keywordsForCopy.toSet(), effect.removeLegendary, copyCount, stackResolver,
            resolvingPayload
        )
    }

    /**
     * Push [copyCount] copies that inherit the source's targets and modes verbatim — the
     * no-retarget paths (no targets at all, modal with no targeted mode, or no legal replacement
     * target under CR 707.10c). Each copy is a real spell entity via
     * [StackResolver.putSpellCopy] so [StormCopyEffectExecutor.applyCopyMutations] can patch it.
     */
    private fun putInheritedCopies(
        state: GameState,
        stackResolver: StackResolver,
        spellEntityId: EntityId,
        controllerId: EntityId,
        copyCount: Int,
        keywordsForCopy: Set<String>,
        removeLegendary: Boolean,
        tokenRiders: com.wingedsheep.engine.state.components.stack.SpellCopyTokenRidersComponent?,
        resolvingSpellCopyPayload: ResolvingSpellCopyPayload? = null
    ): ExecutionResult {
        var currentState = state
        val allEvents = mutableListOf<GameEvent>()
        for (i in 1..copyCount) {
            val copyResult = stackResolver.putSpellCopy(
                state = currentState,
                sourceSpellId = spellEntityId,
                copyIndex = i,
                copyTotal = copyCount,
                controllerId = controllerId,
                resolvingSpellCopyPayload = resolvingSpellCopyPayload
            )
            if (!copyResult.isSuccess) return copyResult
            currentState = StormCopyEffectExecutor.applyCopyMutations(
                copyResult.newState, copyResult.events,
                keywordsForCopy, removeLegendary, tokenRiders
            )
            allEvents.addAll(copyResult.events)
        }
        return ExecutionResult.success(currentState, allEvents)
    }

    private fun promptForCopyTargets(
        state: GameState,
        context: EffectContext,
        spellEntityId: EntityId,
        spellEffect: com.wingedsheep.sdk.scripting.effects.Effect?,
        targetRequirements: List<com.wingedsheep.sdk.scripting.targets.TargetRequirement>,
        spellName: String,
        keywordsForCopy: Set<String> = emptySet(),
        removeLegendary: Boolean = false,
        copyCount: Int = 1,
        stackResolver: StackResolver = StackResolver(cardRegistry = cardRegistry),
        resolvingSpellCopyPayload: ResolvingSpellCopyPayload? = null
    ): EffectResult {
        val decisionId = targetDecisionId()

        val legalTargetsMap = mutableMapOf<Int, List<EntityId>>()
        for ((index, requirement) in targetRequirements.withIndex()) {
            val legalTargets = targetFinder.findLegalTargets(
                state, requirement, context.controllerId, context.sourceId
            )
            legalTargetsMap[index] = legalTargets
        }

        // CR 707.10c: no legal replacement for some requirement, so nothing can be re-chosen —
        // the copies still go on the stack inheriting the source's (now-illegal) targets and
        // fizzle on resolution per 608.2b / 112.3b, exactly as the Storm path does.
        val hasNoLegalTargets = legalTargetsMap.any { (_, targets) -> targets.isEmpty() }
        if (hasNoLegalTargets) {
            return EffectResult.from(
                putInheritedCopies(
                    state, stackResolver, spellEntityId, context.controllerId, copyCount,
                    keywordsForCopy, removeLegendary, tokenRiders = null,
                    resolvingSpellCopyPayload = resolvingSpellCopyPayload
                )
            )
        }

        // Reuse StormCopyTargetContinuation. The resumer clones the
        // SpellOnStackComponent off this sourceId via putSpellCopy (Phase 1 of
        // spell-copies-as-spells), so it must point at the targeted spell on the
        // stack — not the trigger source (e.g., Mischievous Quanar / Naru Meha are
        // creatures with no SpellOnStackComponent). It also walks any copies beyond
        // the first, prompting once per copy.
        val continuation = StormCopyTargetContinuation(
            decisionId = decisionId,
            remainingCopies = copyCount,
            spellEffect = spellEffect,
            spellTargetRequirements = targetRequirements,
            spellName = spellName,
            controllerId = context.controllerId,
            sourceId = spellEntityId,
            totalCopies = copyCount,
            keywordsForCopy = keywordsForCopy,
            removeLegendary = removeLegendary,
            resolvingSpellCopyPayload = resolvingSpellCopyPayload
        )
        val targetReqInfos = targetRequirements.mapIndexed { index, req ->
            TargetRequirementInfo.fromRequirement(
                index = index,
                requirement = req,
            )
        }

        // Matches the Storm path's labelling so a multi-copy prompt says which copy it is for.
        val copyLabel = if (copyCount > 1) "copy 1 of $copyCount of $spellName" else "copy of $spellName"
        val decision = ChooseTargetsDecision(
            id = decisionId,
            playerId = context.controllerId,
            prompt = "Choose new targets for $copyLabel",
            context = DecisionContext(
                phase = DecisionPhase.CASTING,
                sourceName = spellName,
                effectHint = "Copy of $spellName"
            ),
            targetRequirements = targetReqInfos,
            legalTargets = legalTargetsMap
        )

        val stateWithDecision = state.withPendingDecision(decision)
        val stateWithContinuation = stateWithDecision.pushContinuation(continuation)

        return EffectResult.paused(stateWithContinuation, decision)
    }

    private fun targetDecisionId(): String =
        "copy-spell-target-${java.util.UUID.randomUUID()}"
}
