package com.wingedsheep.engine.handlers.effects.token

import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.CreateTokenCopyAuraHostContinuation
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.orReturnUnsupported
import com.wingedsheep.engine.core.toEffectError
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.handlers.effects.library.AuraHostLegality
import com.wingedsheep.engine.mechanics.targeting.PlayerProtectionRules
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.mechanics.targeting.pendingTargetRequirementInfo
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CreateTokenCopyOfTargetEffect

/**
 * Raises the "what does this Aura token enchant?" choice (CR 303.4f).
 *
 * A token copy of an Aura is put onto the battlefield without being cast, so it never targets.
 * Instead its controller chooses what it enchants as it enters, restricted to objects the copied
 * Aura could legally enchant (CR 303.4f — its printed `enchant` restriction, with targeting
 * restrictions such as hexproof and shroud ignored, since nothing is being targeted).
 *
 * The choice is raised *before* the token is created so it enters already attached: its
 * enters-the-battlefield triggers and the first state-based check both see a properly attached
 * Aura. If no legal object exists the token isn't created at all (CR 303.4g).
 *
 * Each token gets its own choice, so an effect creating several Aura copies asks once per token —
 * the continuation carries how many are still owed.
 */
internal object AuraTokenHostChooser {

    /**
     * Pause for the controller to pick a host for the next Aura token, or return an unchanged
     * state when there is nothing the Aura could legally enchant (no token is created).
     *
     * [effectiveSource] is the copy's effective copiable characteristics (after the effect's copy
     * exceptions), used for protection-based host legality; null derives them from the printed
     * Aura definition.
     */
    fun pause(
        state: GameState,
        effect: CreateTokenCopyOfTargetEffect,
        context: EffectContext,
        auraDefinitionId: String,
        auraName: String,
        controllerId: EntityId,
        remaining: Int,
        cardRegistry: CardRegistry?,
        targetFinder: TargetFinder,
        targetValidator: TargetValidator,
        effectiveSource: PlayerProtectionRules.SourceCharacteristics? = null,
    ): EffectResult {
        if (remaining <= 0) return EffectResult.success(state)

        val auraTarget = cardRegistry?.getCard(auraDefinitionId)?.script?.auraTarget
            ?: return EffectResult.error(
                state,
                "Target requirement semantics are unavailable for structured publication",
                diagnostics = listOf(DiagnosticSignal(DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING))
            )
        val hosts = legalHosts(state, auraDefinitionId, controllerId, cardRegistry, targetFinder, effectiveSource)
        val requirementInfo = targetValidator.pendingTargetRequirementInfo(
            state = state,
            index = 0,
            requirement = auraTarget,
            context = context.copy(controllerId = controllerId),
            legalTargetCount = hosts.size,
            description = "permanent for the $auraName token to enchant",
        ).orReturnUnsupported { return it.toEffectError(state) }
        if (hosts.isEmpty()) {
            // Nothing legal to enchant — the Aura token can't enter (CR 303.4g), and neither can
            // any of the ones still owed, since they would all copy the same Aura.
            return EffectResult.success(state)
        }

        val decision = { decisionId: String -> ChooseTargetsDecision(
            id = decisionId,
            playerId = controllerId,
            prompt = "Choose what the $auraName token enchants",
            context = DecisionContext(
                sourceId = context.sourceId,
                sourceName = context.sourceId?.let { state.getEntity(it)?.get<CardComponent>()?.name },
                phase = DecisionPhase.RESOLUTION,
            ),
            targetRequirements = listOf(requirementInfo),
            legalTargets = mapOf(0 to hosts),
        ) }

        val continuation = CreateTokenCopyAuraHostContinuation(
            effect = effect,
            context = context,
            controllerId = controllerId,
            auraDefinitionId = auraDefinitionId,
            auraName = auraName,
            remaining = remaining,
            effectiveSource = effectiveSource,
        )

        return EffectResult.from(state.suspendForDecision(decision, continuation, emptyList()))
    }

    /**
     * Objects the copied Aura could legally enchant. Derived from the Aura card definition's
     * `auraTarget` — the token copies the printed enchant restriction along with everything else —
     * filtered by the attachment restrictions (protection, can't-be-enchanted) that still apply when
     * nothing is targeted. An Aura whose definition declares no enchant restriction has no legal host.
     */
    private fun legalHosts(
        state: GameState,
        auraDefinitionId: String,
        controllerId: EntityId,
        cardRegistry: CardRegistry?,
        targetFinder: TargetFinder,
        effectiveSource: PlayerProtectionRules.SourceCharacteristics?,
    ): List<EntityId> {
        val registry = cardRegistry ?: return emptyList()
        return AuraHostLegality(registry, targetFinder).findLegalHostsForDefinition(
            state = state,
            auraDefinitionId = auraDefinitionId,
            hostControllerId = controllerId,
            effectiveSource = effectiveSource,
        )
    }
}
