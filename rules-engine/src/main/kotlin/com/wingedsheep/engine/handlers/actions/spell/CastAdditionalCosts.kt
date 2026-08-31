package com.wingedsheep.engine.handlers.actions.spell

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.EscalateCosts
import com.wingedsheep.engine.mechanics.FlashbackGrants
import com.wingedsheep.engine.mechanics.WarpGrants
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.PlayWithAdditionalCostComponent
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.ModalEffect

/**
 * The optional-additional-cost keywords selected by a cast, or empty when no slot was declared.
 * A card can carry multiple entries for one slot (for example, mana and sacrifice kicker), so the
 * complete list is retained for the caller that resolves the total cast cost.
 */
internal fun declaredOptionalCosts(
    action: CastSpell,
    cardDef: CardDefinition?,
): List<KeywordAbility.OptionalAdditionalCost> {
    val slot = action.declaredCostSlot ?: return emptyList()
    return cardDef?.keywordAbilities
        ?.filterIsInstance<KeywordAbility.OptionalAdditionalCost>()
        ?.filter { it.declaredSlot == slot }
        ?: emptyList()
}

/**
 * Resolve the mode-selected additional costs for one cast. Per-mode costs replace card-level
 * costs when modes explicitly provide them; Escalate contributes its non-mana cost for extra
 * selected modes.
 */
internal fun resolveAdditionalCostsForMode(
    cardDef: CardDefinition,
    action: CastSpell,
): List<AdditionalCost> {
    if (action.chosenModes.isEmpty()) return cardDef.script.additionalCosts
    val modalEffect = cardDef.script.spellEffect as? ModalEffect
        ?: return cardDef.script.additionalCosts

    val perModeOverrides = action.chosenModes.mapNotNull { modeIndex ->
        modalEffect.modes.getOrNull(modeIndex)?.additionalCosts
    }
    val base = if (perModeOverrides.isEmpty()) cardDef.script.additionalCosts else perModeOverrides.flatten()
    val escalate = EscalateCosts.additionalCostFor(modalEffect, action.chosenModes.size)
    return if (escalate == null) base else base + escalate
}

private fun CastSpell.allowsAlternative(type: com.wingedsheep.engine.core.AlternativeCostType): Boolean =
    alternativeCostType == null || alternativeCostType == type

/**
 * Resolve every additional cost applicable to the selected cast from the same Rules-owned sources
 * used by [CastSpellHandler]. This is intentionally read-only: payment qualification uses only
 * whether the complete selected-cast list is empty, while execution resolves and pays the same
 * list through the existing handler machinery.
 *
 * The function is public because the Gym module must use this exact Rules authority when deciding
 * whether the narrow V5 alternative-cast payment slice is representable.
 */
fun resolveApplicableAdditionalCostsForCast(
    state: GameState,
    action: CastSpell,
    cardDef: CardDefinition?,
    cardRegistry: CardRegistry,
    predicateEvaluator: PredicateEvaluator,
    zoneResolver: CastZoneResolver,
): List<AdditionalCost> = buildList {
    if (cardDef != null) addAll(resolveAdditionalCostsForMode(cardDef, action))

    declaredOptionalCosts(action, cardDef)
        .firstOrNull { it.additionalCost != null }
        ?.additionalCost
        ?.let(::add)

    if (action.useAlternativeCost && cardDef != null) {
        val selfAlternativeCost = cardDef.script.selfAlternativeCost
        if (selfAlternativeCost != null && action.allowsAlternative(
                com.wingedsheep.engine.core.AlternativeCostType.SELF_ALTERNATIVE,
            )
        ) {
            addAll(selfAlternativeCost.additionalCosts)
        }

        if (action.allowsAlternative(com.wingedsheep.engine.core.AlternativeCostType.FLASHBACK) &&
            zoneResolver.hasFlashbackPermission(state, action.playerId, action.cardId)
        ) {
            FlashbackGrants.effectiveFlashback(
                state = state,
                cardId = action.cardId,
                cardDef = cardDef,
                controllerId = action.playerId,
                cardRegistry = cardRegistry,
                predicateEvaluator = predicateEvaluator,
            )?.additionalCost?.let(::add)
        }

        if (action.allowsAlternative(com.wingedsheep.engine.core.AlternativeCostType.WARP) &&
            zoneResolver.hasWarpPermission(state, action.playerId, action.cardId)
        ) {
            WarpGrants.effectiveWarp(
                state, action.cardId, cardDef, action.playerId, cardRegistry, predicateEvaluator
            )?.additionalCost?.let(::add)
        }
    }

    state.getEntity(action.cardId)
        ?.get<PlayWithAdditionalCostComponent>()
        ?.takeIf { it.controllerId == action.playerId }
        ?.additionalCosts
        ?.let(::addAll)

    zoneResolver.findLinkedExileGranter(state, action.playerId, action.cardId)
        ?.additionalCost
        ?.let(::add)
    zoneResolver.findMayCastSelfFromZoneAbility(state, action.playerId, action.cardId)
        ?.additionalCost
        ?.let(::add)
    zoneResolver.topOfLibraryAlternativeGrant(state, action.playerId, action.cardId)
        ?.additionalCost
        ?.let(::add)
}
