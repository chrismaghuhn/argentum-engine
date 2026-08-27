package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackingComponent
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

    private const val BAND_ID_PREFIX = "combat-band-"

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
                val rank = CombatObjectOrder.rank(state, attackerId) ?: return null
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

    /**
     * Return the first ordinal in a contiguous range of [bandCount] IDs available to a new
     * declaration in this combat. Existing [AttackingComponent] values are part of the current
     * combat state, so the ordinal lifetime extends across separate declarations (for example,
     * one submitted by each teammate in a shared-team combat). Only IDs in the canonical form
     * produced here participate; legacy or otherwise ephemeral IDs are deliberately ignored
     * rather than allowed to influence identity. `null` means the whole requested range cannot
     * be represented without overflow.
     */
    fun firstBandOrdinal(state: GameState, bandCount: Int): Long? {
        require(bandCount >= 0) { "Band count cannot be negative" }
        if (bandCount == 0) return 0L

        val highestExistingOrdinal = state.entities.values.asSequence()
            .mapNotNull { it.get<AttackingComponent>()?.bandId }
            .mapNotNull(::parseBandOrdinal)
            .maxOrNull()
            ?: -1L
        if (highestExistingOrdinal == Long.MAX_VALUE) return null

        val first = highestExistingOrdinal + 1
        val lastOffset = bandCount.toLong() - 1
        return if (lastOffset <= Long.MAX_VALUE - first) first else null
    }

    fun bandId(ordinal: Long): String = "$BAND_ID_PREFIX$ordinal"

    private fun parseBandOrdinal(bandId: String): Long? {
        if (!bandId.startsWith(BAND_ID_PREFIX)) return null
        val decimal = bandId.removePrefix(BAND_ID_PREFIX)
        if (decimal.isEmpty() || decimal.any { it !in '0'..'9' }) return null
        return decimal.toLongOrNull()
    }

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
