package com.wingedsheep.gym

import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EnvironmentV1AcceptanceHarnessRepairTest : FunSpec({
    val player = EntityId("player-0")

    test("HARNESS-CLOSURE-01 truncation GENERIC placeholder is not policy-actionable") {
        val observation = observation(
            pending = pending(
                player = player,
                kind = PendingDecisionKind.GENERIC,
                decisionId = null,
                requiresStructuredResponse = true,
            ),
            agentToAct = null,
            truncated = true,
        )

        isExternallyActionablePendingBoundary(observation) shouldBe false
    }

    test("HARNESS-CLOSURE-02 exposed known pending decision remains policy-actionable") {
        val observation = observation(
            pending = pending(
                player = player,
                kind = PendingDecisionKind.YES_NO,
                decisionId = "decision-1",
                requiresStructuredResponse = false,
            ),
            agentToAct = player,
        )

        isExternallyActionablePendingBoundary(observation) shouldBe true
    }

    test("HARNESS-CLOSURE-03 actionable GENERIC is not filtered by family name") {
        val observation = observation(
            pending = pending(
                player = player,
                kind = PendingDecisionKind.GENERIC,
                decisionId = "decision-1",
                requiresStructuredResponse = true,
            ),
            agentToAct = player,
        )

        isExternallyActionablePendingBoundary(observation) shouldBe true
    }
})

private fun pending(
    player: EntityId,
    kind: PendingDecisionKind,
    decisionId: String?,
    requiresStructuredResponse: Boolean,
) = PendingDecisionView(
    decisionId = decisionId,
    kind = kind,
    playerId = player,
    prompt = "test",
    requiresStructuredResponse = requiresStructuredResponse,
)

private fun observation(
    pending: PendingDecisionView,
    agentToAct: EntityId?,
    terminated: Boolean = false,
    truncated: Boolean = false,
): TrainingObservation = TrainingObservation(
    schemaHash = "harness-repair-test",
    perspectivePlayerId = pending.playerId,
    agentToAct = agentToAct,
    turnNumber = 1,
    phase = Phase.PRECOMBAT_MAIN,
    step = Step.PRECOMBAT_MAIN,
    activePlayerId = pending.playerId,
    priorityPlayerId = agentToAct,
    players = emptyList(),
    zones = emptyList(),
    stack = emptyList(),
    pendingDecision = pending,
    legalActions = emptyList(),
    terminated = terminated,
    truncated = truncated,
    winnerId = null,
    stateDigest = "harness-repair-digest",
)
