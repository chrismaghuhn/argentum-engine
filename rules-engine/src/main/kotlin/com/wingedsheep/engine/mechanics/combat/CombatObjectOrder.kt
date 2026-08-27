package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.sdk.model.EntityId

/**
 * Rules-owned ordering for a collection of current combat battlefield objects.
 *
 * The rank is state-owned object identity when available, with the explicit battlefield-entry
 * timestamp as the compatibility fallback for older or synthetic objects. Missing ranks,
 * duplicate ranks, and duplicate requested objects fail closed. EntityId values and collection
 * iteration order are never used as an ordering authority.
 */
internal object CombatObjectOrder {

    fun order(state: GameState, entityIds: Collection<EntityId>): List<EntityId>? {
        val requested = entityIds.toList()
        if (requested.size != requested.distinct().size) return null

        val ranked = requested.map { entityId ->
            val rank = rank(state, entityId) ?: return null
            entityId to rank
        }
        if (ranked.map { it.second }.distinct().size != ranked.size) return null

        return ranked.sortedBy { it.second }.map { it.first }
    }

    fun rank(state: GameState, entityId: EntityId): Long? =
        state.objectIdentityStamps[entityId]
            ?: state.getEntity(entityId)
                ?.get<BattlefieldEntryTimestampComponent>()
                ?.timestamp
}
