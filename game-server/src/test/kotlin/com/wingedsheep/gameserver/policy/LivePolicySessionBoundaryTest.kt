package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.LivePolicyDecisionResponseV1
import com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

class LivePolicySessionBoundaryTest : FunSpec({
    test("ML policy capture uses the shared C1 snapshot and keeps exact bindings JVM-side") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))

        val capture = session.captureLivePolicyDecision(P1)

        capture.source.snapshot.completeLegalDomain.kind shouldBe CompleteLegalDomainKind.ACTION_CANDIDATES
        capture.source.snapshot.candidateFeatureViews.size shouldBe
            capture.source.snapshot.completeLegalDomain.candidates.size
        capture.source.exactSourceBindings.sourceBindingOrdinals shouldBe
            capture.source.snapshot.completeLegalDomain.candidates.indices.toSet()
        capture.source.snapshot.bindingDigest shouldBe capture.source.exactSourceBindings.bindingDigest
        capture.source.snapshot.toRequest("request-1", capture.policyState.toLiveState())
            .toString() shouldBe capture.source.snapshot.toRequest("request-1", capture.policyState.toLiveState()).toString()
    }

    test("accepted flat ordinal execution maps through the current exact JVM binding and commits RNG") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val capture = session.captureLivePolicyDecision(P1)
        val request = capture.toRequest("request-1")
        val response = response(request, selectedOrdinal = 0)

        val result = session.acceptLivePolicyDecision(capture, request, response)

        result.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
        session.getRecordedActions().size shouldBe 1
        session.getPolicySeatState(P1)?.cursor shouldBe 0uL
    }

    test("stale policy inference executes nothing and preserves persisted RNG state") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val capture = session.captureLivePolicyDecision(P1)
        val request = capture.toRequest("request-1")
        val response = response(request, selectedOrdinal = 0)
        session.resetStateForDevScenario(session.getStateForTesting()!!.copy(turnNumber = 2))

        val result = session.acceptLivePolicyDecision(capture, request, response)

        val rejected = result.shouldBeTypeOf<PolicySeatDecisionResult.Rejected>()
        rejected.failure.code shouldBe PolicySeatFailureCode.STALE_INFERENCE
        session.getRecordedActions().size shouldBe 0
        session.getPolicySeatState(P1)?.cursor shouldBe 0uL
    }

    test("invalid policy ordinal executes nothing and preserves persisted RNG state") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val capture = session.captureLivePolicyDecision(P1)
        val request = capture.toRequest("request-1")
        val response = response(request, selectedOrdinal = 99)

        val result = session.acceptLivePolicyDecision(capture, request, response)

        val rejected = result.shouldBeTypeOf<PolicySeatDecisionResult.Rejected>()
        rejected.failure.code shouldBe PolicySeatFailureCode.INVALID_RESPONSE
        session.getRecordedActions().size shouldBe 0
        session.getPolicySeatState(P1)?.cursor shouldBe 0uL
    }

    test("controller authority rejects every competing action origin at the session seam") {
        val session = session()
        val ml = ControllerAuthorityV1.mlPolicy(0, 42L)
        session.setControllerAuthority(P1, ml)

        val humanAttempt = session.executeActionFromController(
            P1,
            PassPriority(P1),
            ControllerKindV1.HUMAN,
        )
        val engineAttempt = session.executeActionFromController(
            P1,
            PassPriority(P1),
            ControllerKindV1.ENGINE_AI,
        )
        val legacyAttempt = session.executeActionFromController(
            P1,
            PassPriority(P1),
            ControllerKindV1.LEGACY_AI,
        )
        val legacyCallbackAttempt = session.executeActionFromAiController(P1, PassPriority(P1))

        listOf(humanAttempt, engineAttempt, legacyAttempt, legacyCallbackAttempt).forEach { result ->
            result.shouldBeTypeOf<GameSession.ActionResult.Failure>()
        }
        session.keepHand(P1).shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()
        session.takeMulligan(P1).shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()
        session.chooseBottomCards(P1, emptyList())
            .shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()
        session.keepHandFromAiController(P1).shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()
        session.takeMulliganFromAiController(P1).shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()
        session.chooseBottomCardsFromAiController(P1, emptyList())
            .shouldBeTypeOf<GameSession.MulliganActionResult.Failure>()
        session.executeAutoPass(P1).shouldBeTypeOf<GameSession.ActionResult.Failure>()
        session.playerConcedes(P1) shouldBe null
        session.getRecordedActions().size shouldBe 0

        session.setControllerAuthority(P1, ControllerAuthorityV1.human(0))
        session.executeActionFromController(P1, PassPriority(P1), ControllerKindV1.ML_POLICY)
            .shouldBeTypeOf<GameSession.ActionResult.Failure>()
        session.setControllerAuthority(P1, ControllerAuthorityV1.engineAi(0))
        session.executeActionFromController(P1, PassPriority(P1), ControllerKindV1.ML_POLICY)
            .shouldBeTypeOf<GameSession.ActionResult.Failure>()
        session.getRecordedActions().size shouldBe 0
    }

    test("ML policy fails closed for mulligan and bottom-card decisions") {
        val mulliganSession = session()
        mulliganSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        mulliganSession.resetStateForDevScenario(
            mulliganSession.getStateForTesting()!!.updateEntity(P1) {
                it.with(MulliganStateComponent())
            }.updateEntity(P2) {
                it.with(MulliganStateComponent())
            },
        )

        shouldThrow<PolicySeatFailure> {
            mulliganSession.captureLivePolicyDecision(P1)
        }.code shouldBe PolicySeatFailureCode.UNSUPPORTED_MULLIGAN_DECISION
        mulliganSession.policySeatToAct() shouldBe null

        val bottomSession = session()
        bottomSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        bottomSession.resetStateForDevScenario(
            bottomSession.getStateForTesting()!!.updateEntity(P1) {
                it.with(MulliganStateComponent(mulligansTaken = 1, hasKept = true))
            }.updateEntity(P2) {
                it.with(MulliganStateComponent(hasKept = true))
            },
        )

        shouldThrow<PolicySeatFailure> {
            bottomSession.captureLivePolicyDecision(P1)
        }.code shouldBe PolicySeatFailureCode.UNSUPPORTED_MULLIGAN_DECISION
        bottomSession.policySeatToAct() shouldBe null
        bottomSession.getRecordedActions().size shouldBe 0
    }
}) {
    companion object {
        private val P1 = EntityId("policy-player-1")
        private val P2 = EntityId("policy-player-2")

        private fun session(): GameSession {
            var state = GameState()
                .withEntity(
                    P1,
                    ComponentContainer.of(
                        PlayerComponent("Policy"),
                        LifeTotalComponent(20),
                        ManaPoolComponent(),
                        LandDropsComponent(remaining = 1, maxPerTurn = 1),
                    ),
                )
                .withEntity(
                    P2,
                    ComponentContainer.of(
                        PlayerComponent("Opponent"),
                        LifeTotalComponent(20),
                        ManaPoolComponent(),
                        LandDropsComponent(remaining = 1, maxPerTurn = 1),
                    ),
                )
                .copy(
                    turnOrder = listOf(P1, P2),
                    activePlayerId = P1,
                    priorityPlayerId = P1,
                    phase = Phase.PRECOMBAT_MAIN,
                    step = Step.PRECOMBAT_MAIN,
                    turnNumber = 1,
                )
            for (player in listOf(P1, P2)) {
                for (zone in listOf(Zone.HAND, Zone.LIBRARY, Zone.GRAVEYARD, Zone.BATTLEFIELD)) {
                    state = state.copy(zones = state.zones + (ZoneKey(player, zone) to emptyList()))
                }
            }
            return GameSession(cardRegistry = CardRegistry()).also { game ->
                game.injectStateForTesting(
                    state,
                    mapOf(
                        P1 to PlayerSession(ws("policy"), P1, "Policy"),
                        P2 to PlayerSession(ws("opponent"), P2, "Opponent"),
                    ),
                )
            }
        }

        private fun ws(id: String): WebSocketSession = mockk<WebSocketSession>(relaxed = true).also {
            every { it.id } returns id
        }

        private fun response(
            request: LivePolicyDecisionRequestV1,
            selectedOrdinal: Int,
        ): LivePolicyDecisionResponseV1 = LivePolicyDecisionResponseV1(
            requestId = request.requestId,
            selectedSourceBindingOrdinal = selectedOrdinal,
            policyRngState = request.policyRngState,
            rngCursorBefore = request.policyRngState.cursor,
            rngCursorAfter = request.policyRngState.cursor,
            rngDrawCount = 0uL,
        )
    }
}
