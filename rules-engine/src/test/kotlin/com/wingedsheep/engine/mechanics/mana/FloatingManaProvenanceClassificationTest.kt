package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
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

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedSingleUnit>()
        certified.candidate.poolColor shouldBe PaymentManaColor.GREEN
        certified.candidate.sourceId shouldBe sourceId
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

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedSingleUnit>()
        certified.candidate.poolColor shouldBe PaymentManaColor.COLORLESS
        certified.candidate.sourceId shouldBe sourceId
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

    test("rejects two source identities in the same colored pool") {
        val result = FloatingManaProvenanceClassification.classify(
            ManaPoolComponent(
                green = 2,
                manaBySource = mapOf(EntityId("source-a") to 1, EntityId("source-b") to 1),
                manaBySubtype = mapOf(Subtype.FOREST to 2),
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

        val certified = result.shouldBeInstanceOf<FloatingManaProvenanceClassification.CertifiedSingleUnit>()
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
