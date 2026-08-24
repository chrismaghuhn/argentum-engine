package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class FloatingManaProvenanceClassificationTest : FunSpec({

    test("certifies one tracked green Forest unit") {
        val sourceId = EntityId("forest-source")

        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                manaBySource = mapOf(sourceId to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 1),
            ),
        )

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
        certified.candidate.poolColor shouldBe PaymentManaColor.GREEN
        certified.candidate.sourceBuckets shouldBe listOf(CertifiedFloatingManaSourceBucket(sourceId, 1))
        certified.candidate.sourceSubtypes shouldBe setOf(Subtype.FOREST)
    }

    test("certifies one tracked colorless unit") {
        val sourceId = EntityId("colorless-source")

        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                colorless = 1,
                manaBySource = mapOf(sourceId to 1),
                manaBySubtype = mapOf(Subtype.CAVE to 1),
            ),
        )

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
        certified.candidate.poolColor shouldBe PaymentManaColor.COLORLESS
        certified.candidate.sourceBuckets shouldBe listOf(CertifiedFloatingManaSourceBucket(sourceId, 1))
        certified.candidate.sourceSubtypes shouldBe setOf(Subtype.CAVE)
    }

    test("reports that empty maps contain no tracked provenance") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(green = 1),
        )

        result shouldBe FloatingManaProvenanceClassification.NoTrackedProvenance
    }

    test("rejects a source tag that could belong to either of two colors") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                red = 1,
                manaBySource = mapOf(EntityId("source") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 1),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("rejects heterogeneous aggregate totals without source-color provenance") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                black = 1,
                green = 3,
                manaBySource = mapOf(
                    EntityId("e108") to 1,
                    EntityId("e117") to 1,
                    EntityId("e136") to 2,
                ),
                manaBySubtype = mapOf(Subtype.FOREST to 4),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("does not treat an empty detail map as complete for a nonempty pool") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                manaBySource = mapOf(EntityId("source") to 1),
                manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("certifies a complete heterogeneous source-color matrix") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                black = 1,
                green = 3,
                manaBySource = mapOf(
                    EntityId("e108") to 1,
                    EntityId("e117") to 1,
                    EntityId("e136") to 2,
                ),
                manaBySubtype = mapOf(Subtype.FOREST to 4),
                manaBySourceAndColor = mapOf(
                    EntityId("e108") to mapOf(PaymentManaColor.BLACK to 1),
                    EntityId("e117") to mapOf(PaymentManaColor.GREEN to 1),
                    EntityId("e136") to mapOf(PaymentManaColor.GREEN to 2),
                ),
                manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
            ),
        )

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHeterogeneous>()
        certified.candidate.sourceColorBuckets shouldBe listOf(
            CertifiedFloatingManaSourceColorBucket(EntityId("e108"), PaymentManaColor.BLACK, 1),
            CertifiedFloatingManaSourceColorBucket(EntityId("e117"), PaymentManaColor.GREEN, 1),
            CertifiedFloatingManaSourceColorBucket(EntityId("e136"), PaymentManaColor.GREEN, 2),
        )
    }

    test("complete source-color detail supports exact homogeneous payment without a subtype matrix") {
        val sourceId = EntityId("source-without-subtype")
        val classification = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                manaBySource = mapOf(sourceId to 1),
                manaBySourceAndColor = mapOf(
                    sourceId to mapOf(PaymentManaColor.GREEN to 1),
                ),
                manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
            ),
        )
        val candidate = classification
            .shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
            .candidate

        val (remaining, spent) = ManaPool(
            green = 1,
            manaBySource = mapOf(sourceId to 1),
            manaBySourceAndColor = mapOf(
                sourceId to mapOf(PaymentManaColor.GREEN to 1),
            ),
            manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
        ).consumeCertifiedHomogeneous(candidate, mapOf(sourceId to 1)) ?: error("expected exact payment")

        remaining.green shouldBe 0
        remaining.manaBySourceAndColor shouldBe emptyMap()
        spent.bySubtype shouldBe emptyMap()
    }

    test("rejects one tagged unit mixed with one untagged unit") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(EntityId("source") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 1),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("certifies two source identities when every unit shares the same color and subtype") {
        val sourceA = EntityId("source-a")
        val sourceB = EntityId("source-b")
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(sourceA to 1, sourceB to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 2),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
            .candidate.sourceBuckets shouldBe listOf(
            CertifiedFloatingManaSourceBucket(sourceA, 1),
            CertifiedFloatingManaSourceBucket(sourceB, 1),
        )
    }

    test("rejects homogeneous-looking sources when their colors are mixed") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                red = 1,
                manaBySource = mapOf(EntityId("source-a") to 1, EntityId("source-b") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 2),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("rejects multiple sources when subtype coverage is incomplete") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(EntityId("source-a") to 1, EntityId("source-b") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 1),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("certifies a multi-unit bucket from one source") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(EntityId("source") to 2),
                manaBySubtype = mapOf(Subtype.FOREST to 2),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
            .candidate.sourceBuckets shouldBe listOf(
            CertifiedFloatingManaSourceBucket(EntityId("source"), 2),
        )
    }

    test("certifies multiple sources when every unit shares multiple subtypes") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 3,
                manaBySource = mapOf(EntityId("source-a") to 2, EntityId("source-b") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 3, Subtype.CAVE to 3),
            ),
        )

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
        certified.candidate.sourceBuckets shouldBe listOf(
            CertifiedFloatingManaSourceBucket(EntityId("source-a"), 2),
            CertifiedFloatingManaSourceBucket(EntityId("source-b"), 1),
        )
        certified.candidate.sourceSubtypes shouldBe setOf(Subtype.FOREST, Subtype.CAVE)
    }

    test("rejects incomplete source provenance") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(EntityId("source") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 2),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("rejects incomplete subtype homogeneity from one source") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(EntityId("source") to 2),
                manaBySubtype = mapOf(Subtype.FOREST to 1, Subtype.CAVE to 1),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("certifies multiple subtype tags when they necessarily belong to one unit") {
        val sourceId = EntityId("multi-subtype-source")

        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                manaBySource = mapOf(sourceId to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 1, Subtype.CAVE to 1),
            ),
        )

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedHomogeneous>()
        certified.candidate.sourceSubtypes shouldBe setOf(Subtype.FOREST, Subtype.CAVE)
    }

    test("rejects malformed provenance counts") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                manaBySource = mapOf(EntityId("source") to 2),
                manaBySubtype = mapOf(Subtype.FOREST to 1),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }

    test("direct certified consumer rejects a malformed aggregate even with a forged matching candidate") {
        val sourceId = EntityId("forged-source")

        ManaPool(
            blue = -1,
            red = 1,
            green = 1,
            manaBySource = mapOf(sourceId to 1),
            manaBySubtype = mapOf(Subtype.FOREST to 1),
        ).consumeCertifiedHomogeneous(
            CertifiedHomogeneousFloatingMana(
                poolColor = PaymentManaColor.GREEN,
                sourceSubtypes = setOf(Subtype.FOREST),
                sourceBuckets = listOf(CertifiedFloatingManaSourceBucket(sourceId, 1)),
            ),
            spentBySource = mapOf(sourceId to 1),
        ) shouldBe null
    }

    test("does not certify a pool that also contains restricted mana") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 1,
                restrictedMana = listOf(RestrictedManaEntry(Color.GREEN, ManaRestriction.AnySpend)),
                manaBySource = mapOf(EntityId("source") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 1),
            ),
        )

        result.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
    }
})
