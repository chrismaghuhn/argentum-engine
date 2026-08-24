package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ManaPoolConversionTest : FunSpec({

    test("toManaPool and fromManaPool preserve the complete joint bucket map") {
        val sourceId = EntityId("conversion-source")
        val component = ManaPoolComponent()
            .addTracked(PaymentManaColor.GREEN, sourceId, setOf(Subtype.FOREST), 2)
            .addTracked(PaymentManaColor.GREEN, sourceId, emptySet(), 1)

        val transient = component.toManaPool()
        transient.manaByFloatingBucket shouldBe mapOf(
            FloatingManaBucketKeyV1(sourceId, PaymentManaColor.GREEN, setOf(Subtype.FOREST)) to 2,
            FloatingManaBucketKeyV1(sourceId, PaymentManaColor.GREEN, emptySet()) to 1,
        )
        fromManaPool(transient) shouldBe component
    }

    test("fromManaPool marks a nonempty pool with missing joint detail incomplete") {
        val sourceId = EntityId("legacy-source")
        val transient = ManaPool(
            green = 1,
            manaBySource = mapOf(sourceId to 1),
            manaBySourceAndColor = mapOf(sourceId to mapOf(PaymentManaColor.GREEN to 1)),
            manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
        )

        val component = fromManaPool(transient)

        component.manaByFloatingBucket shouldBe emptyMap()
        component.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE
    }
})
