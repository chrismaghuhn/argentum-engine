package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResolvedAlternativeCastPaymentTest : FunSpec({
    val target = ChosenTarget.Permanent(EntityId("target"))
    val fixedCost = ManaCost.parse("{1}{W}")

    test("targeted fixed alternative payment is supported when target cost is independent") {
        val action = CastSpell(
            playerId = EntityId("player"),
            cardId = EntityId("spell"),
            targets = listOf(target),
            useAlternativeCost = true,
            alternativeCostType = AlternativeCostType.FLASHBACK,
        )

        isResolvedFixedAlternativeCastPayment(action, fixedCost, false, false) shouldBe true
    }

    test("target-dependent alternative payment remains unsupported") {
        val action = CastSpell(
            playerId = EntityId("player"),
            cardId = EntityId("spell"),
            targets = listOf(target),
            useAlternativeCost = true,
            alternativeCostType = AlternativeCostType.FLASHBACK,
        )

        isResolvedFixedAlternativeCastPayment(action, fixedCost, true, false) shouldBe false
    }
})
