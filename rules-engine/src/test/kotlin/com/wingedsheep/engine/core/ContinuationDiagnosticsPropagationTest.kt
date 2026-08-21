package com.wingedsheep.engine.core

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.costs.PayCost
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContinuationDiagnosticsPropagationTest : FunSpec({

    test("a resumed unsupported continuation reaches ActionProcessor exactly once") {
        val playerId = EntityId("continuation-player")
        val decisionId = "unsupported-continuation"
        val continuation = AnyPlayerMayPayContinuation(
            decisionId = decisionId,
            currentPlayerId = playerId,
            remainingPlayers = emptyList(),
            sourceId = EntityId("continuation-source"),
            sourceName = "Synthetic source",
            controllerId = playerId,
            cost = PayCost.Choice(emptyList()),
            requiredCount = 0,
            filter = GameObjectFilter(),
        )
        val state = GameState(
            turnOrder = listOf(playerId),
            pendingDecision = YesNoDecision(
                id = decisionId,
                playerId = playerId,
                prompt = "Synthetic unsupported continuation",
                context = DecisionContext(),
            ),
            continuationStack = listOf(continuation),
        )

        val result = ActionProcessor(CardRegistry()).process(
            state,
            SubmitDecision(playerId, YesNoResponse(decisionId, choice = true)),
        ).result

        result.error shouldBe "Unsupported cost type for AnyPlayerMayPay resume"
        result.diagnostics shouldBe listOf(
            DiagnosticSignal(DiagnosticCode.SACRIFICE_AND_PAY_COST_UNSUPPORTED)
        )
        result.diagnostics.size shouldBe 1
    }
})
