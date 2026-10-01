package com.wingedsheep.engine.mechanics.layers

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The order [EffectSorter] hands to the projector, for layers with and without a dependency.
 *
 * Effects are compared by identity on purpose: two equal lord effects are two effects, and a
 * sorter that keyed on equality would apply one of them twice and the other never.
 */
class EffectSorterTest : FunSpec({

    val creature = EntityId("creature")
    val land = EntityId("land")

    fun effect(timestamp: Long, modification: Modification, affected: EntityId) = ContinuousEffect(
        sourceId = EntityId("source-$timestamp"),
        timestamp = timestamp,
        modification = modification,
        affectedEntities = setOf(affected),
    )

    fun List<ContinuousEffect>.shouldBeSameInstancesAs(expected: List<ContinuousEffect>) {
        size shouldBe expected.size
        indices.forEach { i -> (this[i] === expected[i]) shouldBe true }
    }

    test("a layer without dependencies comes back in timestamp order, ties in input order, equal effects kept") {
        val late = effect(30, Modification.ModifyPowerToughness(1, 1), creature)
        val tieFirst = effect(10, Modification.ModifyPowerToughness(2, 2), creature)
        val early = effect(5, Modification.ModifyPowerToughness(1, 1), creature)
        val tieSecond = effect(10, Modification.ModifyPowerToughness(2, 2), creature)
        // Equal by value to tieFirst, but its own effect.
        tieSecond shouldBe tieFirst

        val sorted = EffectSorter().sortByLayerAndDependency(listOf(late, tieFirst, early, tieSecond), GameState())

        sorted.shouldBeSameInstancesAs(listOf(early, tieFirst, tieSecond, late))
    }

    test("an effect that can be depended on still goes before its dependent, whatever the timestamps") {
        // A keyword granted before a lose-all-abilities effect is still re-added after it.
        val grant = effect(1, Modification.GrantKeyword("FLYING"), creature)
        val removal = effect(2, Modification.RemoveAllAbilities, creature)
        val unrelated = effect(0, Modification.GrantKeyword("TRAMPLE"), land)

        val sorted = EffectSorter().sortByLayerAndDependency(listOf(grant, removal, unrelated), GameState())

        sorted shouldContainExactly listOf(unrelated, removal, grant)
    }
})
