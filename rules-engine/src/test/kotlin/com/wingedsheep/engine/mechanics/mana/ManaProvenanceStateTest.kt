package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ManaProvenanceStateTest : FunSpec({

    test("tracked adds update totals, aggregate source, and source-color atomically") {
        val sourceId = EntityId("tracked-source")

        val pool = ManaPoolComponent().addTracked(
            color = PaymentManaColor.GREEN,
            sourceId = sourceId,
            subtypes = setOf(Subtype.FOREST),
            amount = 2,
        )

        pool.green shouldBe 2
        pool.manaBySource shouldBe mapOf(sourceId to 2)
        pool.manaBySourceAndColor shouldBe mapOf(
            sourceId to mapOf(PaymentManaColor.GREEN to 2),
        )
        pool.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.COMPLETE
    }

    test("tracked add cannot promote nonempty incomplete provenance") {
        val firstSource = EntityId("first-source")
        val secondSource = EntityId("second-source")
        val incomplete = ManaPoolComponent(
            green = 1,
            manaBySource = mapOf(firstSource to 1),
            manaProvenanceCompleteness = ManaProvenanceCompleteness.INCOMPLETE,
        )

        val result = incomplete.addTracked(
            color = PaymentManaColor.BLACK,
            sourceId = secondSource,
            subtypes = emptySet(),
            amount = 1,
        )

        result.black shouldBe 1
        result.green shouldBe 1
        result.manaBySourceAndColor shouldBe emptyMap()
        result.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
    }

    test("tracked add cannot extend an inconsistent COMPLETE detail map") {
        val sourceId = EntityId("inconsistent-source")
        val inconsistent = ManaPoolComponent(
            green = 1,
            manaBySource = mapOf(sourceId to 1),
            manaBySourceAndColor = mapOf(sourceId to mapOf(PaymentManaColor.BLACK to 1)),
            manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
        )

        val result = inconsistent.addTracked(
            color = PaymentManaColor.GREEN,
            sourceId = EntityId("new-source"),
            subtypes = emptySet(),
            amount = 1,
        )

        result.green shouldBe 2
        result.manaBySourceAndColor shouldBe emptyMap()
        result.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
    }

    test("untracked add marks detail incomplete and later tracked add cannot rebuild a partial matrix") {
        val firstSource = EntityId("first-source")
        val secondSource = EntityId("second-source")
        val result = ManaPoolComponent()
            .addTracked(PaymentManaColor.GREEN, firstSource, setOf(Subtype.FOREST))
            .add(Color.BLACK)
            .addTracked(PaymentManaColor.RED, secondSource, emptySet())

        result.green shouldBe 1
        result.black shouldBe 1
        result.red shouldBe 1
        result.manaBySourceAndColor shouldBe emptyMap()
        result.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
    }

    test("component toManaPool and fromManaPool preserve authoritative provenance") {
        val sourceId = EntityId("round-trip-source")
        val component = ManaPoolComponent().addTracked(
            color = PaymentManaColor.BLACK,
            sourceId = sourceId,
            subtypes = emptySet(),
            amount = 1,
        )

        fromManaPool(component.toManaPool()) shouldBe component
    }

    test("the conversion seam drops an inconsistent detail claim instead of publishing it") {
        val sourceId = EntityId("stale-detail-source")
        val transient = ManaPool(
            green = 2,
            manaBySource = mapOf(sourceId to 1),
            manaBySourceAndColor = mapOf(sourceId to mapOf(PaymentManaColor.GREEN to 1)),
            manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
        )

        val component = fromManaPool(transient)

        component.green shouldBe 2
        component.manaBySource shouldBe mapOf(sourceId to 1)
        component.manaBySourceAndColor shouldBe emptyMap()
        component.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
    }

    test("legacy color spend invalidates detail and clears every map when the pool empties") {
        val sourceId = EntityId("spend-source")
        val pool = ManaPoolComponent().addTracked(
            color = PaymentManaColor.GREEN,
            sourceId = sourceId,
            subtypes = setOf(Subtype.FOREST),
            amount = 2,
        )

        val after = pool.spend(com.wingedsheep.sdk.core.Color.GREEN, 2)!!

        after.green shouldBe 0
        after.manaBySubtype shouldBe emptyMap()
        after.manaBySource shouldBe emptyMap()
        after.manaBySourceAndColor shouldBe emptyMap()
        after.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.UNKNOWN
    }

    test("phase cleanup drops detailed provenance for retained unrestricted mana") {
        val sourceId = EntityId("retained-source")
        val pool = ManaPoolComponent().addTracked(
            color = PaymentManaColor.GREEN,
            sourceId = sourceId,
            subtypes = setOf(Subtype.FOREST),
        )

        val after = pool.emptyAtBoundary(convertToRed = false, retain = setOf(Color.GREEN))

        after.green shouldBe 1
        after.manaBySourceAndColor shouldBe emptyMap()
        after.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
    }
})
