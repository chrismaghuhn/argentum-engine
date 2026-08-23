package com.wingedsheep.engine.core

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetOther
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Proves that pending metadata and the public target domain share one source-derived atom. */
class SharedTargetRequirementAtomTest : FunSpec({

    test("TargetOther preserves every TargetObject semantic field") {
        val requirement = TargetOther(
            baseRequirement = TargetObject(
                count = 2,
                minCount = 1,
                filter = TargetFilter(
                    baseFilter = GameObjectFilter(
                        cardPredicates = listOf(
                            CardPredicate.ManaValueAtMostX,
                            CardPredicate.ManaValueEqualsX,
                            CardPredicate.PowerEqualsX,
                        )
                    ),
                    zone = Zone.GRAVEYARD,
                ),
                dynamicMaxCount = DynamicAmount.XValue,
                sameController = true,
                sameOwner = true,
                sameCreatureType = true,
                sameCardType = true,
                totalManaValueAtMost = DynamicAmount.Fixed(7),
                differentNames = true,
            )
        )

        val info = TargetRequirementInfo.fromRequirement(
            index = 3,
            requirement = requirement,
            minTargets = requirement.effectiveMinCount,
            resolvedMaxTargets = ResolvedTargetCount(requirement.count),
        ).shouldBeInstanceOf<TargetRequirementInfoResult.Supported>().info

        info.index shouldBe 3
        info.minTargets shouldBe 1
        info.maxTargets shouldBe 2
        info.targetZone shouldBe "Graveyard"
        info.mustDifferFromEarlier shouldBe true
        info.sameController shouldBe true
        info.sameOwner shouldBe true
        info.sameCreatureType shouldBe true
        info.sameCardType shouldBe true
        info.totalManaValueAtMost shouldBe 7
        info.differentNames shouldBe true
        info.xConstrainsManaValue shouldBe true
        info.xConstrainsManaValueExactly shouldBe true
        info.xConstrainsPower shouldBe true
        info.xConstrainsCount shouldBe true
    }

    test("unresolved aggregate target semantics return typed unsupported metadata") {
        val requirement = TargetObject(
            filter = TargetFilter(GameObjectFilter.Creature),
            totalManaValueAtMost = DynamicAmount.XValue,
        )

        val result = TargetRequirementInfo.fromRequirement(index = 0, requirement = requirement)

        result.shouldBeInstanceOf<TargetRequirementInfoResult.Unsupported>().reason shouldBe
            TargetRequirementUnsupportedReason.UNRESOLVED_TOTAL_MANA_VALUE
    }

    test("unresolved target cardinality returns typed unsupported metadata") {
        val unlimited = TargetObject(
            filter = TargetFilter(GameObjectFilter.Creature),
            unlimited = true,
        )
        val unresolvedDynamicCount = TargetObject(
            count = 2,
            filter = TargetFilter(GameObjectFilter.Creature),
            dynamicMaxCount = DynamicAmount.XValue,
        )

        listOf(unlimited, unresolvedDynamicCount).forEach { requirement ->
            val result = TargetRequirementInfo.fromRequirement(index = 0, requirement = requirement)

            result.shouldBeInstanceOf<TargetRequirementInfoResult.Unsupported>().reason shouldBe
                TargetRequirementUnsupportedReason.UNRESOLVED_TARGET_COUNT
        }
    }

    test("a raw maximum does not resolve an unresolved dynamic target cardinality") {
        val requirement = TargetObject(
            count = 2,
            filter = TargetFilter(GameObjectFilter.Creature),
            dynamicMaxCount = DynamicAmount.XValue,
        )

        val result = TargetRequirementInfo.fromRequirement(
            index = 0,
            requirement = requirement,
            maxTargets = requirement.count,
        )

        result.shouldBeInstanceOf<TargetRequirementInfoResult.Unsupported>().reason shouldBe
            TargetRequirementUnsupportedReason.UNRESOLVED_TARGET_COUNT
    }
})
