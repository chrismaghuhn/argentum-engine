package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** RED contract tests for the versioned exact joint-bucket payment carrier. */
class PaymentPlanV2Test : FunSpec({

    fun game(): Pair<GameTestDriver, EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver to driver.activePlayer!!
    }

    fun planFor(reference: ManaSpendReferenceV2): PaymentPlanV2 = PaymentPlanV2(
        poolSpend = PoolSpend(green = 1),
        spendAllocation = SpendAllocationV2(
            costUnits = listOf(CostUnitAllocationV2(0, listOf(reference))),
        ),
    )

    test("V2 exact bucket echo spends Forest mana and preserves the remaining buckets") {
        val (driver, player) = game()
        val e1 = EntityId("e1")
        val e2 = EntityId("e2")
        val forestKey = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, setOf(Subtype.FOREST))
        val emptyKey = FloatingManaBucketKeyV1(e1, PaymentManaColor.GREEN, emptySet())
        val caveKey = FloatingManaBucketKeyV1(e2, PaymentManaColor.BLACK, setOf(Subtype.CAVE))
        val pool = ManaPoolComponent()
            .addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST))
            .addTracked(PaymentManaColor.GREEN, e1, emptySet())
            .addTracked(PaymentManaColor.BLACK, e2, setOf(Subtype.CAVE))
        driver.addComponent(player, pool)

        val validation = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validateV2(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = planFor(
                ManaSpendReferenceV2(
                    floatingSourceId = e1,
                    poolColor = PaymentManaColor.GREEN,
                    amount = 1,
                    floatingSourceSubtypes = listOf(Subtype.FOREST.value),
                ),
            ),
        ).shouldBeInstanceOf<PaymentPlanValidation.Accepted>()

        validation.materialization.spentManaProvenance.bySubtype shouldBe
            mapOf(Subtype.FOREST to 1)
        validation.materialization.spentManaProvenance.sourceIds shouldBe setOf(e1)
        validation.poolAfterSpend.manaByFloatingBucket shouldBe mapOf(emptyKey to 1, caveKey to 1)
    }

    test("V2 rejects a client-invented subtype snapshot") {
        val (driver, player) = game()
        val e1 = EntityId("e1")
        val pool = ManaPoolComponent().addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST))
        driver.addComponent(player, pool)

        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validateV2(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = planFor(
                ManaSpendReferenceV2(
                    floatingSourceId = e1,
                    poolColor = PaymentManaColor.GREEN,
                    floatingSourceSubtypes = listOf(Subtype.CAVE.value),
                ),
            ),
        ).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        result.reason shouldBe "PaymentPlanV2 floating bucket key is not currently certified"
    }

    test("V2 rejects duplicate subtype names instead of normalizing a client key") {
        val (driver, player) = game()
        val e1 = EntityId("e1")
        driver.addComponent(
            player,
            ManaPoolComponent().addTracked(PaymentManaColor.GREEN, e1, setOf(Subtype.FOREST)),
        )

        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validateV2(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = planFor(
                ManaSpendReferenceV2(
                    floatingSourceId = e1,
                    poolColor = PaymentManaColor.GREEN,
                    floatingSourceSubtypes = listOf(Subtype.FOREST.value, Subtype.FOREST.value),
                ),
            ),
        ).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        result.reason shouldBe "PaymentPlanV2 floating subtype snapshot must be canonical"
    }
})
