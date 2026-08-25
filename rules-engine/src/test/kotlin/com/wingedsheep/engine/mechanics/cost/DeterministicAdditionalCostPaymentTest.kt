package com.wingedsheep.engine.mechanics.cost

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeterministicAdditionalCostPaymentTest : FunSpec({

    test("Mana + Tap + SacrificeSelf binds both existing payload lists to source") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{2}"), Costs.Tap, Costs.SacrificeSelf),
            EntityId("source"),
        ) shouldBe AdditionalCostPayment(
            tappedPermanents = listOf(EntityId("source")),
            sacrificedPermanents = listOf(EntityId("source")),
        )
    }

    test("choice-bearing permanent costs are not certified") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(
                Costs.Mana("{1}"),
                Costs.TapPermanents(count = 1, filter = GameObjectFilter.Creature),
                Costs.SacrificePermanents(filter = GameObjectFilter.Permanent),
            ),
            EntityId("source"),
        ) shouldBe null
    }

    test("self-bound slice has no card-name input") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{1}"), Costs.Tap, Costs.SacrificeSelf),
            EntityId("renamed-source"),
        ) shouldBe AdditionalCostPayment(
            tappedPermanents = listOf(EntityId("renamed-source")),
            sacrificedPermanents = listOf(EntityId("renamed-source")),
        )
    }

    test("unsupported self and player-selected costs remain fail-closed") {
        listOf(
            Costs.ExileSelf,
            Costs.ReturnSelfToHand,
            Costs.PayLife(1),
            Costs.DiscardCard,
            Costs.Sacrifice(GameObjectFilter.Permanent),
            Costs.TapPermanents(count = 1, filter = GameObjectFilter.Permanent),
            Costs.SacrificePermanents(filter = GameObjectFilter.Permanent),
        ).forEach { cost ->
            DeterministicAdditionalCostPayment.expectedFor(cost, EntityId("source")) shouldBe null
        }
    }

    test("CommanderColorIdentityCount is certified without making PayLife generally supported") {
        val certificate = DeterministicAdditionalCostPayment.certify(
            Costs.Composite(
                Costs.Mana("{3}"),
                Costs.Tap,
                Costs.PayLife(DynamicAmounts.commanderColorIdentityCount()),
            ),
            EntityId("source"),
        )

        certificate?.additionalCostPayment shouldBe AdditionalCostPayment(
            tappedPermanents = listOf(EntityId("source")),
        )
        certificate?.deterministicPayLifeExpressions shouldBe listOf(
            DynamicAmount.CommanderColorIdentityCount,
        )

        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(
                Costs.Mana("{3}"),
                Costs.Tap,
                Costs.PayLife(DynamicAmounts.sourcePower()),
            ),
            EntityId("source"),
        ) shouldBe null
    }

    test("more than one deterministic self payment is not certified") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Tap, Costs.Tap),
            EntityId("source"),
        ) shouldBe null
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.SacrificeSelf, Costs.SacrificeSelf),
            EntityId("source"),
        ) shouldBe null
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{1}"), Costs.Tap, Costs.Tap),
            EntityId("source"),
        ) shouldBe null
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{1}"), Costs.SacrificeSelf, Costs.SacrificeSelf),
            EntityId("source"),
        ) shouldBe null
    }

    test("only fixed ordinary mana atoms are certified") {
        listOf(
            Costs.Mana("{X}"),
            Costs.Mana("{W/U}"),
            Costs.Mana("{G/P}"),
            Costs.Mana("{2/W}"),
        ).forEach { manaCost ->
            DeterministicAdditionalCostPayment.expectedFor(
                Costs.Composite(manaCost, Costs.Tap, Costs.SacrificeSelf),
                EntityId("source"),
            ) shouldBe null
        }
    }

    test("only one canonical mana atom is certified") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{1}"), Costs.Mana("{1}"), Costs.Tap),
            EntityId("source"),
        ) shouldBe null
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Tap,
            EntityId("source"),
        ) shouldBe null
    }

    test("nested cost composites remain outside the certified shape") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(
                Costs.Mana("{1}"),
                Costs.Composite(Costs.Tap),
            ),
            EntityId("source"),
        ) shouldBe null
    }

    test("fixed ordinary zero mana is a deterministic certified mana atom") {
        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Mana("{0}"),
            EntityId("source"),
        ) shouldBe AdditionalCostPayment()

        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{0}"), Costs.Tap, Costs.SacrificeSelf),
            EntityId("source"),
        ) shouldBe AdditionalCostPayment(
            tappedPermanents = listOf(EntityId("source")),
            sacrificedPermanents = listOf(EntityId("source")),
        )

        DeterministicAdditionalCostPayment.expectedFor(
            Costs.Composite(Costs.Mana("{0}"), Costs.Mana("{X}")),
            EntityId("source"),
        ) shouldBe null
    }
})
