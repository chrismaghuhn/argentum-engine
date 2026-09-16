package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.KeepHand
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.YieldKind
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.HotseatControlComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.C1ModelFacingProjectionV1
import com.wingedsheep.gym.contract.LivePolicyDecisionResponseV1
import com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
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

    test("controller authority follows GameState.actorFor for legitimate turn control") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.human(0))
        session.setControllerAuthority(P2, ControllerAuthorityV1.mlPolicy(1, 42L))
        session.resetStateForDevScenario(
            session.getStateForTesting()!!
                .copy(priorityPlayerId = P2)
                .updateEntity(P2) { it.with(HotseatControlComponent(controllerId = P1)) },
        )

        session.getStateForTesting()!!.actorFor(P2) shouldBe P1
        session.executeActionFromController(P1, PassPriority(P2), ControllerKindV1.HUMAN)
            .shouldBeTypeOf<GameSession.ActionResult.Success>()
    }

    test("ML policy cannot mutate undo state or replay history") {
        val session = session()
        session.executeAction(P1, PassPriority(P1))
            .shouldBeTypeOf<GameSession.ActionResult.Success>()
        session.isUndoAvailable(P1) shouldBe true
        val stateBeforeUnauthorizedUndo = session.getStateForTesting()
        val actionsBeforeUnauthorizedUndo = session.getRecordedActions()

        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.executeUndo(P1).shouldBeTypeOf<GameSession.ActionResult.Failure>()

        session.getStateForTesting() shouldBe stateBeforeUnauthorizedUndo
        session.getRecordedActions() shouldBe actionsBeforeUnauthorizedUndo
    }

    test("ML policy cannot set or clear persistent yields") {
        val identity = AbilityIdentity("test-card", AbilityId("test-ability"))

        val setSession = session()
        val stateBeforeSet = setSession.getStateForTesting()
        val yieldsBeforeSet = setSession.getReplayYields()
        setSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        setSession.setAbilityYield(P1, identity, YieldKind.ALWAYS_ANSWER_YES)
        setSession.getStateForTesting() shouldBe stateBeforeSet
        setSession.getReplayYields() shouldBe yieldsBeforeSet

        val clearSession = session()
        clearSession.setAbilityYield(P1, identity, YieldKind.YIELD_WHOLE_GAME)
        val stateBeforeClear = clearSession.getStateForTesting()
        val yieldsBeforeClear = clearSession.getReplayYields()
        clearSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        clearSession.clearAbilityYield(P1, identity)
        clearSession.getStateForTesting() shouldBe stateBeforeClear
        clearSession.getReplayYields() shouldBe yieldsBeforeClear

        val clearAllSession = session()
        clearAllSession.setAbilityYield(P1, identity, YieldKind.YIELD_WHOLE_GAME)
        val stateBeforeClearAll = clearAllSession.getStateForTesting()
        val yieldsBeforeClearAll = clearAllSession.getReplayYields()
        clearAllSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        clearAllSession.clearAllYields(P1)
        clearAllSession.getStateForTesting() shouldBe stateBeforeClearAll
        clearAllSession.getReplayYields() shouldBe yieldsBeforeClearAll
    }

    test("ML policy exposes the complete current mulligan domain") {
        val mulliganSession = session()
        mulliganSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        mulliganSession.resetStateForDevScenario(mulliganState(mulliganSession, MulliganStateComponent()))

        mulliganSession.policySeatToAct() shouldBe P1
        val capture = mulliganSession.captureLivePolicyDecision(P1)
        val domain = capture.source.snapshot.completeLegalDomain
        domain.kind shouldBe CompleteLegalDomainKind.STRUCTURED_DECISION
        val cardSelection = domain.structuredDomain
            .shouldBeTypeOf<com.wingedsheep.gym.contract.CardSelectionDomain>()
        cardSelection.minSelections shouldBe 0
        cardSelection.maxSelections shouldBe 3
        cardSelection.ordered shouldBe false
        capture.source.snapshot.structuredChoiceDomain!!.alternatives.size shouldBe 2
        capture.source.exactSourceBindings.exactBindingFor(0)
            .shouldBeTypeOf<PolicySeatExactBinding.GameActionBinding>()
            .action.shouldBeTypeOf<KeepHand>()
        capture.source.exactSourceBindings.exactBindingFor(1)
            .shouldBeTypeOf<PolicySeatExactBinding.GameActionBinding>()
            .action.shouldBeTypeOf<TakeMulligan>()
        val requestText = capture.source.snapshot
            .toRequest("mulligan-request", capture.policyState.toLiveState())
            .toString()
        requestText.contains(HAND_CARD_1.value) shouldBe false
        requestText.contains("bindingDigest") shouldBe false
        requestText.contains("KeepHand") shouldBe false
        requestText.contains("TakeMulligan") shouldBe false
    }

    test("accepted ML KEEP and MULLIGAN use the authoritative existing actions") {
        val keepSession = session()
        keepSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        keepSession.resetStateForDevScenario(mulliganState(keepSession, MulliganStateComponent()))
        val keepCapture = keepSession.captureLivePolicyDecision(P1)
        val keepRequest = keepCapture.toRequest("keep-request")
        val keepResult = keepSession.acceptLivePolicyDecision(
            keepCapture,
            keepRequest,
            response(keepRequest, selectedOrdinal = 0),
        )
        keepResult.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
        keepSession.getStateForTesting()!!.getEntity(P1)
            ?.get<MulliganStateComponent>()?.hasKept shouldBe true
        keepSession.getRecordedActions().single().shouldBeTypeOf<KeepHand>()
        keepSession.getPolicySeatState(P1)?.cursor shouldBe 0uL

        val mulliganSession = session()
        mulliganSession.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        mulliganSession.resetStateForDevScenario(mulliganState(mulliganSession, MulliganStateComponent()))
        val mulliganCapture = mulliganSession.captureLivePolicyDecision(P1)
        val mulliganRequest = mulliganCapture.toRequest("mulligan-request")
        val mulliganResult = mulliganSession.acceptLivePolicyDecision(
            mulliganCapture,
            mulliganRequest,
            response(mulliganRequest, selectedOrdinal = 1),
        )
        mulliganResult.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
        mulliganSession.getStateForTesting()!!.getEntity(P1)
            ?.get<MulliganStateComponent>()?.mulligansTaken shouldBe 1
        mulliganSession.getStateForTesting()!!.getEntity(P1)
            ?.get<MulliganStateComponent>()?.hasKept shouldBe false
        mulliganSession.getRecordedActions().single().shouldBeTypeOf<TakeMulligan>()
        mulliganSession.getPolicySeatState(P1)?.cursor shouldBe 0uL
    }

    test("ML mulligan domain omits TakeMulligan when the authoritative state disallows it") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.resetStateForDevScenario(
            mulliganState(
                session,
                MulliganStateComponent(mulligansTaken = 7),
            ),
        )

        val capture = session.captureLivePolicyDecision(P1)
        capture.source.snapshot.structuredChoiceDomain!!.alternatives.size shouldBe 1
        capture.source.exactSourceBindings.exactBindingFor(0)
            .shouldBeTypeOf<PolicySeatExactBinding.GameActionBinding>()
            .action.shouldBeTypeOf<KeepHand>()
    }

    test("ML bottom-card domain is complete, ordered, and maps exact cards only in the JVM") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.resetStateForDevScenario(
            mulliganState(
                session,
                MulliganStateComponent(mulligansTaken = 1, hasKept = true),
            ),
        )

        val capture = session.captureLivePolicyDecision(P1)
        val domain = capture.source.snapshot.completeLegalDomain
        val cardSelection = domain.structuredDomain
            .shouldBeTypeOf<com.wingedsheep.gym.contract.CardSelectionDomain>()
        cardSelection.minSelections shouldBe 1
        cardSelection.maxSelections shouldBe 1
        cardSelection.ordered shouldBe true
        capture.source.snapshot.structuredChoiceDomain!!.alternatives.size shouldBe 3
        capture.source.snapshot.structuredChoiceDomain!!.alternatives.forEach { alternative ->
            alternative.featureView.toString().contains(HAND_CARD_1.value) shouldBe false
            capture.source.exactSourceBindings.exactBindingFor(alternative.sourceBindingOrdinal)
                .shouldBeTypeOf<PolicySeatExactBinding.GameActionBinding>()
                .action.shouldBeTypeOf<BottomCards>()
                .cardIds.size shouldBe 1
        }

        val request = capture.toRequest("bottom-request")
        request.toString().contains("bindingDigest") shouldBe false
        request.toString().contains("BottomCards") shouldBe false
        request.toString().contains(HAND_CARD_1.value) shouldBe false
        val result = session.acceptLivePolicyDecision(capture, request, response(request, 0))
        result.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
        session.getStateForTesting()!!.getEntity(P1)
            ?.get<MulliganStateComponent>()?.mulligansTaken shouldBe 0
        session.getRecordedActions().single().shouldBeTypeOf<BottomCards>()
        session.getPolicySeatState(P1)?.cursor shouldBe 0uL
    }

    test("identical physical hand cards retain complete multiplicity and distinct stable aliases") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.resetStateForDevScenario(
            mulliganState(
                session,
                MulliganStateComponent(mulligansTaken = 1, hasKept = true),
                duplicateFirstTwo = true,
            ),
        )

        val capture = session.captureLivePolicyDecision(P1)
        val alternatives = capture.source.snapshot.structuredChoiceDomain!!.alternatives
        val aliasesBySourceId = C1ModelFacingProjectionV1.project(
            observation = PlayerObservationV1.from(capture.source.observation),
            domain = capture.source.snapshot.completeLegalDomain,
        ).entityAliasBindings.associate { it.sourceEntityId to it.alias }
        alternatives.size shouldBe 3
        alternatives.map { it.featureView.toString() }.distinct().size shouldBe 3
        capture.source.exactSourceBindings.sourceBindingOrdinals shouldBe setOf(0, 1, 2)
        alternatives.forEach { alternative ->
            val binding = capture.source.exactSourceBindings.exactBindingFor(alternative.sourceBindingOrdinal)
                .shouldBeTypeOf<PolicySeatExactBinding.GameActionBinding>()
            val action = binding.action.shouldBeTypeOf<BottomCards>()
            action.cardIds.size shouldBe 1
            alternative.featureView.toString().contains(
                aliasesBySourceId.getValue(action.cardIds.single().value),
            ) shouldBe true
        }
    }

    test("stale pregame inference executes nothing and advances no PolicyTieRng") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.resetStateForDevScenario(mulliganState(session, MulliganStateComponent()))
        val capture = session.captureLivePolicyDecision(P1)
        val request = capture.toRequest("stale-pregame-request")
        session.resetStateForDevScenario(
            mulliganState(session, MulliganStateComponent(mulligansTaken = 1)),
        )

        val result = session.acceptLivePolicyDecision(capture, request, response(request, 0))
        result.shouldBeTypeOf<PolicySeatDecisionResult.Rejected>()
            .failure.code shouldBe PolicySeatFailureCode.STALE_INFERENCE
        session.getRecordedActions().size shouldBe 0
        session.getPolicySeatState(P1)?.cursor shouldBe 0uL
    }

    test("human mulligan remains available while ML is owned by the other seat") {
        val session = session()
        session.setControllerAuthority(P2, ControllerAuthorityV1.mlPolicy(1, 42L))
        session.resetStateForDevScenario(
            session.getStateForTesting()!!
                .updateEntity(P1) { it.with(MulliganStateComponent(hasKept = false)) }
                .updateEntity(P2) { it.with(MulliganStateComponent(hasKept = false)) },
        )

        session.keepHand(P1).shouldBeTypeOf<GameSession.MulliganActionResult.Success>()
        session.getStateForTesting()!!.getEntity(P1)
            ?.get<MulliganStateComponent>()?.hasKept shouldBe true
        session.getRecordedActions().single().shouldBeTypeOf<KeepHand>()
    }

    test("ML policy rejects a game-over pregame boundary without mutation") {
        val session = session()
        session.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.resetStateForDevScenario(session.getStateForTesting()!!.copy(gameOver = true))

        shouldThrow<PolicySeatFailure> {
            session.captureLivePolicyDecision(P1)
        }.code shouldBe PolicySeatFailureCode.SESSION_NOT_READY
        session.policySeatToAct() shouldBe null
        session.getRecordedActions().size shouldBe 0
    }
}) {
    companion object {
        private val P1 = EntityId("policy-player-1")
        private val P2 = EntityId("policy-player-2")
        private val HAND_CARD_1 = EntityId("hand-card-1")
        private val HAND_CARD_2 = EntityId("hand-card-2")
        private val HAND_CARD_3 = EntityId("hand-card-3")

        private fun mulliganState(
            session: GameSession,
            playerState: MulliganStateComponent,
            duplicateFirstTwo: Boolean = false,
        ): GameState {
            var state = session.getStateForTesting()!!
                .withEntity(
                    HAND_CARD_1,
                    ComponentContainer.of(
                        CardComponent(
                            cardDefinitionId = if (duplicateFirstTwo) "Forest#POR-211" else "Mountain#POR-211",
                            name = "Mountain",
                            manaCost = ManaCost.ZERO,
                            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                            ownerId = P1,
                        ),
                    ),
                )
                .withEntity(
                    HAND_CARD_2,
                    ComponentContainer.of(
                        CardComponent(
                            cardDefinitionId = "Forest#POR-211",
                            name = "Forest",
                            manaCost = ManaCost.ZERO,
                            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                            ownerId = P1,
                        ),
                    ),
                )
                .withEntity(
                    HAND_CARD_3,
                    ComponentContainer.of(
                        CardComponent(
                            cardDefinitionId = "Plains#POR-211",
                            name = "Plains",
                            manaCost = ManaCost.ZERO,
                            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                            ownerId = P1,
                        ),
                    ),
                )
                .copy(
                    zones = session.getStateForTesting()!!.zones +
                        (ZoneKey(P1, Zone.HAND) to listOf(HAND_CARD_1, HAND_CARD_2, HAND_CARD_3)),
                )
                .updateEntity(P1) { it.with(playerState) }
                .updateEntity(P2) { it.with(MulliganStateComponent(hasKept = true)) }
            return state
        }

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
