package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.CombatResolutionContinuation
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.ReopenManaPaymentDecisionContinuation
import com.wingedsheep.engine.core.Suspension
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The combat question used to be stored three times — as the pending decision, as the answer
 * continuation's `decisionId`, and as the continuation's cached `decisionShape`. It now lives once,
 * as the question of the [Suspension] whose answer is the [CombatResolutionContinuation], and its
 * id is a routing id allocated from `GameState.nextRoutingId`.
 */
class ReplayDecisionNonceCanonicalizationTest : FunSpec({

    test("equivalent combat decision references ignore runtime nonces") {
        val first = combatState(routingId = 0L)
        val second = combatState(routingId = 5L)
        first.pendingDecision!!.id shouldNotBe second.pendingDecision!!.id
        first.nextRoutingId shouldBe second.nextRoutingId

        ReplayFingerprint.of(first) shouldBe ReplayFingerprint.of(second)
    }

    test("decision reference alias relationships remain semantic") {
        // Two decision references in one state: a question set aside inside its reopen frame and
        // the question pending above it. "Both name the same routing id" must stay distinguishable
        // from "they name different routing ids".
        ReplayFingerprint.of(nestedQuestions(setAsideRoutingId = 0L, pendingRoutingId = 0L)) shouldNotBe
            ReplayFingerprint.of(nestedQuestions(setAsideRoutingId = 0L, pendingRoutingId = 1L))
    }
})

private val chooser = EntityId("p1")

/** The routing allocator position is fingerprinted state; every fixture is parked here after asking. */
private const val PARKED_ROUTING_ID = 100L

private fun combatQuestion(id: String) = CombatResolutionDecision(
    id = id,
    playerId = chooser,
    prompt = "Assign combat damage",
    context = DecisionContext(),
    firstStrike = false,
    attackers = emptyList(),
    blockers = emptyList(),
    defenders = emptyList(),
    edges = emptyList(),
)

private val combatAnswer = CombatResolutionContinuation(
    firstStrike = false,
    pendingChoosers = listOf(chooser),
)

/** The combat question asked from routing allocator position [routingId] (its id is "r<routingId>"). */
private fun combatState(routingId: Long): GameState =
    GameState(nextRoutingId = routingId)
        .suspendForDecision(question = { id -> combatQuestion(id) }, answer = combatAnswer)
        .state
        .copy(nextRoutingId = PARKED_ROUTING_ID)

/**
 * A combat question set aside inside a [ReopenManaPaymentDecisionContinuation] (asked from
 * [setAsideRoutingId]) with an identical question pending above it (asked from [pendingRoutingId]).
 */
private fun nestedQuestions(setAsideRoutingId: Long, pendingRoutingId: Long): GameState {
    val setAside = GameState(nextRoutingId = setAsideRoutingId)
        .suspendForDecision(question = { id -> combatQuestion(id) }, answer = combatAnswer)
        .state
        .continuationStack
        .single() as Suspension
    return GameState(nextRoutingId = pendingRoutingId)
        .pushContinuation(ReopenManaPaymentDecisionContinuation(setAside))
        .suspendForDecision(question = { id -> combatQuestion(id) }, answer = combatAnswer)
        .state
        .copy(nextRoutingId = PARKED_ROUTING_ID)
}
