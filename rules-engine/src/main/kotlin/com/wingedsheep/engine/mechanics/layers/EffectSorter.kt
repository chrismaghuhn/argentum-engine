package com.wingedsheep.engine.mechanics.layers

import com.wingedsheep.engine.state.GameState

/**
 * Sorts continuous effects by layer, sublayer, dependency, and timestamp (Rule 613).
 */
internal class EffectSorter {

    fun sortByLayerAndDependency(
        effects: List<ContinuousEffect>,
        state: GameState
    ): List<ContinuousEffect> {
        val byLayer = effects.groupBy { it.layer }
        val result = mutableListOf<ContinuousEffect>()

        for (layer in Layer.entries) {
            val layerEffects = byLayer[layer] ?: continue

            if (layer == Layer.POWER_TOUGHNESS) {
                val bySublayer = layerEffects.groupBy { it.sublayer }
                for (sublayer in Sublayer.entries) {
                    val sublayerEffects = bySublayer[sublayer] ?: continue
                    result.addAll(sortByDependencyAndTimestamp(sublayerEffects, state))
                }
            } else {
                result.addAll(sortByDependencyAndTimestamp(layerEffects, state))
            }
        }

        return result
    }

    private fun sortByDependencyAndTimestamp(
        effects: List<ContinuousEffect>,
        state: GameState
    ): List<ContinuousEffect> {
        if (effects.size <= 1) return effects

        // Only an effect that changes types or removes all abilities can be depended on (see
        // [dependsOn]). Without one the dependency graph is empty, and the topological sort below
        // reduces to exactly this stable timestamp sort — at O(n log n) instead of the pairwise
        // check plus an O(n² log n) selection loop, which a board of equipped tokens (dozens of
        // P/T and keyword grants in one layer) made the single hottest thing in engine AI rollouts.
        val dependencySources = effects.filter(::canBeDependedOn)
        if (dependencySources.isEmpty()) return effects.sortedBy { it.timestamp }

        val dependencies = mutableMapOf<ContinuousEffect, Set<ContinuousEffect>>()

        for (effectA in effects) {
            val dependsOn = mutableSetOf<ContinuousEffect>()
            for (effectB in dependencySources) {
                if (effectA === effectB) continue
                if (dependsOn(effectA, effectB, state)) {
                    dependsOn.add(effectB)
                }
            }
            dependencies[effectA] = dependsOn
        }

        // Topological sort with timestamp as tiebreaker
        // Use identity-based tracking to avoid deduplicating equal-but-distinct effects
        val result = mutableListOf<ContinuousEffect>()
        val remaining = effects.toMutableList()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { effect ->
                dependencies[effect]?.none { dep -> remaining.any { it === dep } } ?: true
            }.sortedBy { it.timestamp }

            if (ready.isEmpty()) {
                result.addAll(remaining.sortedBy { it.timestamp })
                break
            }

            val next = ready.first()
            result.add(next)
            remaining.removeAt(remaining.indexOfFirst { it === next })
        }

        return result
    }

    private fun dependsOn(
        effectA: ContinuousEffect,
        effectB: ContinuousEffect,
        state: GameState
    ): Boolean {
        // Type-changing effects: any effect that changes types, subtypes, or land types
        // creates a dependency for other effects sharing those entities, because changing
        // types can change what other effects apply to or what they accomplish.
        // This covers Blood Moon + Urborg: SetBasicLandTypes on Urborg changes what
        // Urborg's AddSubtype effect does (it ceases to exist once Urborg is a Mountain).
        if (changesTypes(effectB)) {
            return effectA.affectedEntities.any { it in effectB.affectedEntities }
        }
        // GrantKeyword depends on RemoveAllAbilities — apply removal first, then re-add specific keywords
        if (effectB.modification is Modification.RemoveAllAbilities && effectA.modification is Modification.GrantKeyword) {
            return effectA.affectedEntities.any { it in effectB.affectedEntities }
        }
        return false
    }

    /** Whether any effect can depend on [effect] — the precondition of every branch of [dependsOn]. */
    private fun canBeDependedOn(effect: ContinuousEffect): Boolean =
        changesTypes(effect) || effect.modification is Modification.RemoveAllAbilities

    private fun changesTypes(effect: ContinuousEffect): Boolean =
        effect.modification is Modification.AddType ||
            effect.modification is Modification.RemoveType ||
            effect.modification is Modification.SetBasicLandTypes ||
            effect.modification is Modification.SetBasicLandTypesFromChosen ||
            effect.modification is Modification.SetCardTypes ||
            effect.modification is Modification.SetAllSubtypes ||
            effect.modification is Modification.SetCreatureSubtypes ||
            effect.modification is Modification.AddSubtype
}
