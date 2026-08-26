package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.sdk.model.EntityId

/**
 * Rules-owned canonical ordering for the attackers in a declaration.
 *
 * The rank is the current battlefield object's state-owned identity stamp. It is serialized with
 * [GameState], survives forks and snapshots, and is reproduced by replay from the same state
 * transitions. The component fallback keeps older/synthetic states with the explicit
 * battlefield-entry marker usable. Neither source depends on an EntityId string, collection
 * iteration, allocation order, or object identity.
 */
internal object CombatAttackerOrder {

    /**
     * Return bands with members sorted by their canonical rank and bands sorted lexicographically
     * by those rank sequences. `null` means the state cannot provide a unique deterministic rank
     * for every member, so callers must reject the declaration rather than invent a tie-breaker.
     */
    fun canonicalizeBands(
        state: GameState,
        bands: List<Set<EntityId>>,
    ): List<List<EntityId>>? {
        if (bands.isEmpty()) return emptyList()

        val rankedBands = mutableListOf<RankedBand>()
        val seenRanks = mutableSetOf<Long>()
        for (band in bands) {
            val rankedMembers = band.map { attackerId ->
                val rank = rank(state, attackerId) ?: return null
                if (!seenRanks.add(rank)) return null
                RankedAttacker(attackerId, rank)
            }.sortedBy { it.rank }
            rankedBands += RankedBand(
                members = rankedMembers.map { it.attackerId },
                ranks = rankedMembers.map { it.rank },
            )
        }

        val ordered = rankedBands.sortedWith { left, right ->
            compareRankSequences(left.ranks, right.ranks)
        }
        if (ordered.zipWithNext().any { (left, right) -> left.ranks == right.ranks }) return null
        return ordered.map { it.members }
    }

    private fun rank(state: GameState, attackerId: EntityId): Long? =
        state.objectIdentityStamps[attackerId]
            ?: state.getEntity(attackerId)
                ?.get<BattlefieldEntryTimestampComponent>()
                ?.timestamp

    private fun compareRankSequences(left: List<Long>, right: List<Long>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private data class RankedAttacker(
        val attackerId: EntityId,
        val rank: Long,
    )

    private data class RankedBand(
        val members: List<EntityId>,
        val ranks: List<Long>,
    )
}
