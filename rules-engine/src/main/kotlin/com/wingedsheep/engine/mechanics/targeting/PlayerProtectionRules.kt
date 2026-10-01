package com.wingedsheep.engine.mechanics.targeting

import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.ControllerGrants
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.GrantsControllerProtectionComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.player.PlayerProtectionComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ProtectionScope
import kotlinx.serialization.Serializable

/**
 * Player-level protection (CR 702.16) — consulted by the targeting and damage systems
 * for a player carrying a [PlayerProtectionComponent] (The One Ring's "protection from
 * everything until your next turn").
 *
 * A protected player cannot be enchanted, targeted, or dealt damage by a matching source.
 * Attachment choice, state-based checks, targeting, and damage share this reading.
 */
object PlayerProtectionRules {

    /** Characteristics used when a source has no entity yet, such as an Aura token copy. */
    @Serializable
    data class SourceCharacteristics(
        val colors: Set<String> = emptySet(),
        val subtypes: Set<String> = emptySet(),
        val supertypes: Set<String> = emptySet(),
        val cardTypes: Set<String> = emptySet(),
    )

    /**
     * True if [playerId] has protection from the source [sourceId] (a spell or ability
     * source). [casterId] is the controller of that source, used for the
     * [ProtectionScope.EachOpponent] scope. A null [sourceId] is treated as an unknown
     * source — only [ProtectionScope.Everything] still protects against it.
     */
    fun isProtectedFromSource(
        state: GameState,
        playerId: EntityId,
        sourceId: EntityId?,
        casterId: EntityId?,
        predicateEvaluator: PredicateEvaluator
    ): Boolean {
        // Player-level protection comes from two sources, unioned:
        //  1. A one-shot [PlayerProtectionComponent] on the player (e.g. The One Ring).
        //  2. Continuous statics ([GrantProtectionToController]) on permanents the player
        //     controls, stamped as [GrantsControllerProtectionComponent] (Absolute Virtue).
        val ownScopes = state.getEntity(playerId)?.get<PlayerProtectionComponent>()?.scopes.orEmpty()
        if (ownScopes.any { scopeMatchesSource(state, playerId, it, sourceId, casterId) }) return true

        return state.getBattlefield().any { entityId ->
            val container = state.getEntity(entityId) ?: return@any false
            // Projected controller: a stolen Absolute Virtue protects its thief, not the player it
            // was taken from — see [ControllerGrants.granterController].
            if (ControllerGrants.granterController(state, entityId) != playerId) return@any false
            container.get<GrantsControllerProtectionComponent>()?.grants
                // Each scope carries its own "as long as …" gate, re-evaluated here on every read
                // because the marker was stamped once, on entry — see [ControllerGrantMarker].
                ?.any {
                    ControllerGrants.isActive(state, entityId, it.condition, predicateEvaluator = predicateEvaluator) &&
                        scopeMatchesSource(state, playerId, it.scope, sourceId, casterId)
                } == true
        }
    }

    /**
     * The same protection check for a source represented by its characteristics. A
     * definition-only Aura token has no source entity or stable identity yet, but its printed
     * colors and types still matter when the non-targeting attachment host is chosen; a spell
     * being cast is judged by the face actually cast rather than its entity's printed front.
     *
     * [sourceId] is the source object when there is one: the scopes that name a *kind* of source
     * rather than a characteristic (spells, permanents cast this turn) are read off it, and match
     * nothing without it.
     */
    fun isProtectedFromSourceCharacteristics(
        state: GameState,
        playerId: EntityId,
        source: SourceCharacteristics,
        casterId: EntityId?,
        predicateEvaluator: PredicateEvaluator,
        sourceId: EntityId? = null
    ): Boolean {
        val ownScopes = state.getEntity(playerId)?.get<PlayerProtectionComponent>()?.scopes.orEmpty()
        if (ownScopes.any { scopeMatchesCharacteristics(state, it, source, playerId, casterId, sourceId) }) return true

        return state.getBattlefield().any { entityId ->
            val container = state.getEntity(entityId) ?: return@any false
            if (ControllerGrants.granterController(state, entityId) != playerId) return@any false
            container.get<GrantsControllerProtectionComponent>()?.grants
                ?.any {
                    ControllerGrants.isActive(state, entityId, it.condition, predicateEvaluator = predicateEvaluator) &&
                        scopeMatchesCharacteristics(state, it.scope, source, playerId, casterId, sourceId)
                } == true
        }
    }

    private fun scopeMatchesSource(
        state: GameState,
        protectedPlayerId: EntityId,
        scope: ProtectionScope,
        sourceId: EntityId?,
        casterId: EntityId?
    ): Boolean {
        if (scope is ProtectionScope.Everything) return true
        if (sourceId == null) return false

        val projected = state.projectedState
        // Attachment choices inspect an Aura before entry; spells likewise need their current
        // off-battlefield characteristics rather than an absent battlefield projection — see
        // [sourceColors] and its siblings.
        return when (scope) {
            is ProtectionScope.Color -> scope.color.name in sourceColors(state, projected, sourceId)
            is ProtectionScope.Colors -> scope.colors.any { it.name in sourceColors(state, projected, sourceId) }
            is ProtectionScope.NonColor -> scope.color.name !in sourceColors(state, projected, sourceId)
            ProtectionScope.Multicolored -> sourceColors(state, projected, sourceId).size >= 2
            is ProtectionScope.Subtype ->
                sourceSubtypes(state, projected, sourceId).any { it.equals(scope.subtype, ignoreCase = true) }
            is ProtectionScope.Supertype ->
                sourceSupertypes(state, projected, sourceId).any { it.equals(scope.supertype, ignoreCase = true) }
            is ProtectionScope.CardType -> scope.cardType.uppercase() in sourceCardTypes(state, projected, sourceId)
            is ProtectionScope.EachOpponent -> {
                val sourceController = casterId
                    ?: projected.getController(sourceId)
                    ?: state.getEntity(sourceId)?.get<ControllerComponent>()?.playerId
                sourceController != null && sourceController != protectedPlayerId
            }
            ProtectionScope.Everything -> true
            ProtectionScope.Spells -> SourceKindProtection.isSpell(state, sourceId)
            ProtectionScope.PermanentsCastThisTurn -> SourceKindProtection.isPermanentCastThisTurn(state, sourceId)
            // An ability kind is a property of the targeting spell-or-ability, not of the source
            // object this reading is given; no player-protection grant names one.
            ProtectionScope.ActivatedAbilities, ProtectionScope.TriggeredAbilities -> false
        }
    }

    private fun scopeMatchesCharacteristics(
        state: GameState,
        scope: ProtectionScope,
        source: SourceCharacteristics,
        protectedPlayerId: EntityId,
        casterId: EntityId?,
        sourceId: EntityId?
    ): Boolean = when (scope) {
        is ProtectionScope.Color -> scope.color.name in source.colors
        is ProtectionScope.Colors -> scope.colors.any { it.name in source.colors }
        is ProtectionScope.NonColor -> scope.color.name !in source.colors
        ProtectionScope.Multicolored -> source.colors.size >= 2
        is ProtectionScope.Subtype -> source.subtypes.any { it.equals(scope.subtype, ignoreCase = true) }
        is ProtectionScope.Supertype -> source.supertypes.any { it.equals(scope.supertype, ignoreCase = true) }
        is ProtectionScope.CardType -> source.cardTypes.any { it.equals(scope.cardType, ignoreCase = true) }
        ProtectionScope.Everything -> true
        ProtectionScope.EachOpponent -> casterId != null && casterId != protectedPlayerId
        // A kind of source is a fact about the source object, not its characteristics: read it
        // off [sourceId] when there is one (a definition-only Aura token has none and was never
        // cast). An ability kind belongs to the targeting spell-or-ability, not to the source
        // object; no player-protection grant names one.
        ProtectionScope.Spells -> sourceId != null && SourceKindProtection.isSpell(state, sourceId)
        ProtectionScope.PermanentsCastThisTurn ->
            sourceId != null && SourceKindProtection.isPermanentCastThisTurn(state, sourceId)
        ProtectionScope.ActivatedAbilities, ProtectionScope.TriggeredAbilities -> false
    }

    /**
     * A source Aura selected from a library is not in the battlefield projection yet. Its base
     * characteristics still define the quality that player protection is from; battlefield
     * sources continue to use projected characteristics so continuous effects remain visible.
     */
    private fun sourceColors(
        state: GameState,
        projected: com.wingedsheep.engine.mechanics.layers.ProjectedState,
        sourceId: EntityId
    ): Set<String> = if (sourceId in state.getBattlefield()) {
        projected.getColors(sourceId)
    } else {
        state.getEntity(sourceId)?.get<CardComponent>()?.colors?.map { it.name }?.toSet().orEmpty()
    }

    private fun sourceSubtypes(
        state: GameState,
        projected: com.wingedsheep.engine.mechanics.layers.ProjectedState,
        sourceId: EntityId
    ): Set<String> = if (sourceId in state.getBattlefield()) {
        projected.getSubtypes(sourceId)
    } else {
        state.getEntity(sourceId)?.get<CardComponent>()?.typeLine?.subtypes?.map { it.value }?.toSet().orEmpty()
    }

    private fun sourceSupertypes(
        state: GameState,
        projected: com.wingedsheep.engine.mechanics.layers.ProjectedState,
        sourceId: EntityId
    ): Set<String> = if (sourceId in state.getBattlefield()) {
        projected.getSupertypes(sourceId)
    } else {
        state.getEntity(sourceId)?.get<CardComponent>()?.typeLine?.supertypes?.map { it.name }?.toSet().orEmpty()
    }

    private fun sourceCardTypes(
        state: GameState,
        projected: com.wingedsheep.engine.mechanics.layers.ProjectedState,
        sourceId: EntityId
    ): Set<String> = if (sourceId in state.getBattlefield()) {
        projected.getTypes(sourceId)
    } else {
        state.getEntity(sourceId)?.get<CardComponent>()?.typeLine?.cardTypes?.map { it.name }?.toSet().orEmpty()
    }
}
