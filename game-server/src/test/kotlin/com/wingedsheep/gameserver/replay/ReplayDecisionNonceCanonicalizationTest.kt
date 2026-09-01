package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.CombatResolutionContinuation
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ReplayDecisionNonceCanonicalizationTest : FunSpec({

    test("equivalent combat decision references ignore runtime nonces") {
        ReplayFingerprint.of(combatState("pending-a", "pending-a", "pending-a")) shouldBe
            ReplayFingerprint.of(combatState("pending-b", "pending-b", "pending-b"))
    }

    test("decision reference alias relationships remain semantic") {
        ReplayFingerprint.of(combatState("pending-a", "pending-a", "pending-a")) shouldNotBe
            ReplayFingerprint.of(combatState("pending-a", "continuation-b", "continuation-b"))
    }
})

private fun combatState(
    pendingId: String,
    continuationId: String,
    decisionShapeId: String,
): GameState {
    val decisionShape = CombatResolutionDecision(
        id = decisionShapeId,
        playerId = EntityId("p1"),
        prompt = "Assign combat damage",
        context = DecisionContext(),
        firstStrike = false,
        attackers = emptyList(),
        blockers = emptyList(),
        defenders = emptyList(),
        edges = emptyList(),
    )
    return GameState(
        pendingDecision = CombatResolutionDecision(
            id = pendingId,
            playerId = EntityId("p1"),
            prompt = "Assign combat damage",
            context = DecisionContext(),
            firstStrike = false,
            attackers = emptyList(),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = emptyList(),
        ),
        continuationStack = listOf(
            CombatResolutionContinuation(
                decisionId = continuationId,
                firstStrike = false,
                pendingChoosers = listOf(EntityId("p1")),
                decisionShape = decisionShape,
            ),
        ),
    )
}
