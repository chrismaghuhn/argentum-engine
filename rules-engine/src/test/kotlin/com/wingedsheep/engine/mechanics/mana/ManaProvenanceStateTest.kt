package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
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
})
