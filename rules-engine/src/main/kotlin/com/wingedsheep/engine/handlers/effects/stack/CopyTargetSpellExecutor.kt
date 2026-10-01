package com.wingedsheep.engine.handlers.effects.stack

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.mechanics.stack.StackPlacement
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.mechanics.targeting.pendingTargetRequirementInfo
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
 *
 * When the effect context carries a [ResolvingSpellCopyPayload] for the targeted spell (the spell
 * is resolving, or a cost-linked trigger outlived it on the stack), the copies are built from that
 * captured payload — card characteristics, effective spell effect, cast-time choices and targets —
 * rather than from components that may already have been removed.
 */
class CopyTargetSpellExecutor(
    private val dynamicAmountEvaluator: com.wingedsheep.engine.handlers.DynamicAmountEvaluator,
    private val targetFinder: TargetFinder,
    private val targetValidator: TargetValidator
) : EffectExecutor<CopyTargetSpellEffect> {

    override val effectType: KClass<CopyTargetSpellEffect> = CopyTargetSpellEffect::class

    override fun execute(
        state: GameState,
        effect: CopyTargetSpellEffect,
        context: EffectContext
    ): EffectResult {
        val spellEntityId = context.resolveTarget(effect.target)
            ?: frozenSourceSpell(effect, context, state)
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
        // resolution puts a permanent onto the battlefield.
        val spellEffect = resolvingPayload?.effectiveSpellEffect ?: cardComponent.spellEffect
        val spellName = cardComponent.name
        val targetsComponent = resolvingPayload?.targets ?: container?.get<TargetsComponent>()
        val targetRequirements = targetsComponent?.targetRequirements ?: emptyList()

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
                        state, spellEntityId, context.controllerId, copyCount,
                        effect.keywordsForCopy.toSet(), effect.removeLegendary, tokenRiders,
                        resolvingPayload
                    )
                )
            }
            return EffectResult.from(StormCopyEffectExecutor.driveStormModalCopies(
                state = state,
                targetFinder = targetFinder,
                targetValidator = targetValidator,
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
        // uses StackPlacement.putSpellCopy so the copy remains a real spell object and inherits
        // the complete immutable stack payload (including cast-time cost snapshots). This is
        // especially important for a cost-linked trigger: its source spell may leave the stack,
        // and each copy must retain the same copyable characteristics and payload under
        // CR 707.10.
        if (targetRequirements.isEmpty()) {
            return EffectResult.from(
                putInheritedCopies(
                    state, spellEntityId, context.controllerId, copyCount,
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
            effect.keywordsForCopy.toSet(), effect.removeLegendary, copyCount, tokenRiders,
            resolvingPayload
        )
    }

    /**
     * The spell a cost-linked trigger froze when it was put on the stack, when the copy names that
     * very spell but it has since been countered or has otherwise left the stack. The identity gate
     * in [EffectContext.resolveTarget] (CR 400.7) then names nothing, yet the trigger still copies
     * the spell from its frozen payload — the captured card, effect, cast choices and targets — as
     * last-known information. Null for every other reference, so no other lost object is revived.
     */
    private fun frozenSourceSpell(
        effect: CopyTargetSpellEffect,
        context: EffectContext,
        state: GameState
    ): EntityId? {
        val payload = context.resolvingSpellCopyPayload ?: return null
        val reference = effect.target as? com.wingedsheep.sdk.scripting.targets.EffectTarget.SingleEntity
            ?: return null
        val named = com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
            .resolveEntity(reference, context, state)
        return payload.sourceSpellId.takeIf { it == named }
    }

    /**
     * Push [copyCount] copies that inherit the source's targets and modes verbatim — the
     * no-retarget paths (no targets at all, modal with no targeted mode, or no legal replacement
     * target under CR 707.10c). Each copy is a real spell entity via
     * [StackPlacement.putSpellCopy] so [StormCopyEffectExecutor.applyCopyMutations] can patch it.
     */
    private fun putInheritedCopies(
        state: GameState,
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
            val copyResult = StackPlacement.putSpellCopy(
                state = currentState,
                sourceSpellId = spellEntityId,
                copyIndex = i,
                copyTotal = copyCount,
                controllerId = controllerId,
                resolvingSpellCopyPayload = resolvingSpellCopyPayload,
                targetValidator = targetValidator
            )
            if (copyResult.outcome !is Outcome.Done) return copyResult
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
        tokenRiders: com.wingedsheep.engine.state.components.stack.SpellCopyTokenRidersComponent? = null,
        resolvingSpellCopyPayload: ResolvingSpellCopyPayload? = null
    ): EffectResult {
        val copiedSpellXValue = resolvingSpellCopyPayload?.spell?.xValue
            ?: state.getEntity(spellEntityId)?.get<SpellOnStackComponent>()?.xValue
        val copiedSpellPredicateContext =
            com.wingedsheep.engine.handlers.PredicateContext.fromEffectContext(context).copy(
                // X belongs to the copied spell, not to the effect that is creating the copy.
                // If the source snapshot is unavailable, retain null so X-relative candidates
                // fail closed in TargetFinder rather than inheriting the copier's X.
                xValue = copiedSpellXValue,
            )

        val legalTargetsMap = mutableMapOf<Int, List<EntityId>>()
        for ((index, requirement) in targetRequirements.withIndex()) {
            val legalTargets = targetFinder.findLegalTargets(
                state = state,
                requirement = requirement,
                controllerId = context.controllerId,
                sourceId = context.sourceId,
                pipelineContext = copiedSpellPredicateContext,
                requireAuthoritativeContext = true,
            )
            legalTargetsMap[index] = legalTargets
        }

        val targetReqInfos = targetRequirements.mapIndexed { index, requirement ->
            targetValidator.pendingTargetRequirementInfo(
                state = state,
                index = index,
                requirement = requirement,
                context = context.copy(xValue = copiedSpellXValue),
                legalTargetCount = legalTargetsMap[index].orEmpty().size,
            ).orReturnUnsupported { return it.toEffectError(state) }
        }

        // CR 707.10c: no legal replacement for some requirement, so nothing can be re-chosen —
        // the copies still go on the stack inheriting the source's (now-illegal) targets and
        // fizzle on resolution per 608.2b / 112.3b, exactly as the Storm path does.
        val hasNoLegalTargets = legalTargetsMap.any { (_, targets) -> targets.isEmpty() }
        if (hasNoLegalTargets) {
            return EffectResult.from(
                putInheritedCopies(
                    state, spellEntityId, context.controllerId, copyCount,
                    keywordsForCopy, removeLegendary, tokenRiders,
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
            remainingCopies = copyCount,
            spellEffect = spellEffect,
            spellTargetRequirements = targetRequirements,
            spellName = spellName,
            controllerId = context.controllerId,
            sourceId = spellEntityId,
            totalCopies = copyCount,
            keywordsForCopy = keywordsForCopy,
            removeLegendary = removeLegendary,
            tokenRiders = tokenRiders,
            resolvingSpellCopyPayload = resolvingSpellCopyPayload
        )

        // Matches the Storm path's labelling so a multi-copy prompt says which copy it is for.
        val copyLabel = if (copyCount > 1) "copy 1 of $copyCount of $spellName" else "copy of $spellName"
        val decision = { decisionId: String -> ChooseTargetsDecision(
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
        ) }

        return EffectResult.from(state.suspendForDecision(decision, continuation, emptyList()))
    }
}
