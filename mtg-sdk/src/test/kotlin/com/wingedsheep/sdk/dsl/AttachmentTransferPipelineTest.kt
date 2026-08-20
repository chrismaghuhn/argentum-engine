package com.wingedsheep.sdk.dsl

import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CollectionFilter
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.FilterCollectionEffect
import com.wingedsheep.sdk.scripting.effects.AttachCollectionToTargetEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.serialization.CardSerialization
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class AttachmentTransferPipelineTest : DescribeSpec({

    it("builds a generic attach pipeline without card-specific vocabulary") {
        val pipeline = Effects.Pipeline {
            val candidates = gather(CardSource.ControlledPermanents(), name = "candidates")
            val legal = filter(
                from = candidates,
                filter = CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
                name = "legal",
            )
            val selected = chooseAnyNumber(from = legal, name = "selected")
            attach(from = selected, target = EffectTarget.ContextTarget(0))
        } as CompositeEffect

        pipeline.effects[1] shouldBe FilterCollectionEffect(
            from = "candidates",
            filter = CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
            storeMatching = "legal",
        )
        pipeline.effects.last() shouldBe AttachCollectionToTargetEffect(
            from = "selected",
            target = EffectTarget.ContextTarget(0),
        )

        val serialized = CardSerialization.compactJson.encodeToString(
            com.wingedsheep.sdk.scripting.effects.Effect.serializer(),
            pipeline,
        )
        serialized shouldContain "AttachCollectionToTarget"
        serialized shouldContain "AttachableTo"
    }
})
