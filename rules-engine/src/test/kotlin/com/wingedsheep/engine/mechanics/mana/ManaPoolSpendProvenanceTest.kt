package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Exact consumption tests for the Rules-owned joint floating-mana buckets. */
class ManaPoolSpendProvenanceTest : FunSpec({

    test("exact joint spend returns selected provenance and preserves unselected buckets") {
        val e1 = EntityId("e1")
        val e2 = EntityId("e2")
        val forestKey = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, setOf(Subtype.FOREST))
        val emptyKey = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, emptySet())
        val caveKey = FloatingManaBucketKeyV1(e2, PaymentManaColor.BLACK, setOf(Subtype.CAVE))
        val pool = ManaPool()
            .addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST), amount = 2)
            .addTracked(PaymentManaColor.GREEN, e1, emptySet())
            .addTracked(PaymentManaColor.BLACK, e2, setOf(Subtype.CAVE))

        val (remaining, spent) = pool.consumeCertifiedJoint(mapOf(forestKey to 1))
            ?: error("expected exact joint spend to succeed")

        spent.bySubtype shouldBe mapOf(Subtype.FOREST to 1)
        spent.sourceIds shouldBe setOf(e1)
        remaining.manaByFloatingBucket shouldBe mapOf(forestKey to 1, emptyKey to 1, caveKey to 1)
        remaining.manaBySource shouldBe mapOf(e1 to 2, e2 to 1)
        remaining.manaBySourceAndColor shouldBe mapOf(
            e1 to mapOf(PaymentManaColor.GREEN to 2),
            e2 to mapOf(PaymentManaColor.BLACK to 1),
        )
        remaining.manaBySubtype shouldBe mapOf(Subtype.FOREST to 1, Subtype.CAVE to 1)
        remaining.green shouldBe 2
        remaining.black shouldBe 1
    }

    test("joint spend rejects an invented key and leaves the pool unchanged") {
        val e1 = EntityId("e1")
        val forestKey = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, setOf(Subtype.FOREST))
        val pool = ManaPool().addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST))
        val inventedKey = forestKey.copy(sourceSubtypes = setOf(Subtype.CAVE))

        pool.consumeCertifiedJoint(mapOf(inventedKey to 1)) shouldBe null
        pool.manaByFloatingBucket shouldBe mapOf(forestKey to 1)
    }

    test("joint spend rejects overspend without changing any projection") {
        val e1 = EntityId("e1")
        val forestKey = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, setOf(Subtype.FOREST))
        val pool = ManaPool().addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST))

        pool.consumeCertifiedJoint(mapOf(forestKey to 2)) shouldBe null
        pool.manaByFloatingBucket shouldBe mapOf(forestKey to 1)
        pool.manaBySource shouldBe mapOf(e1 to 1)
        pool.manaBySubtype shouldBe mapOf(Subtype.FOREST to 1)
    }

    test("full spend returns source and subtype provenance before the pool becomes empty") {
        val e1 = EntityId("e1")
        val pool = ManaPool().addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST))

        val postSpend = pool.spend(Color.GREEN) ?: error("expected the pool to pay one green")
        val (provenancePool, spent) = pool.consumeProvenance(1)
        val remaining = postSpend.withProvenanceFrom(provenancePool)

        spent.sourceIds shouldBe setOf(e1)
        spent.bySubtype shouldBe mapOf(Subtype.FOREST to 1)
        remaining.unrestrictedTotal shouldBe 0
        remaining.manaBySource shouldBe emptyMap()
        remaining.manaBySubtype shouldBe emptyMap()
        remaining.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.UNKNOWN
    }
})
