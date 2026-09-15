package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.config.MlPolicyProperties
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gym.contract.LivePolicyDecisionResponseV1
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.web.socket.WebSocketSession

class PolicySeatRuntimeTest : FunSpec({
    test("one policy seat owns one runtime and duplicate inference is rejected") {
        val game = session()
        game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val worker = BlockingWorker()
        val runtime = PolicySeatRuntime(game, P1, worker)

        runtime.decideAsync { _ -> }
        worker.started.await(2, TimeUnit.SECONDS) shouldBe true
        runtime.decide().shouldBeTypeOf<PolicySeatDecisionResult.Rejected>()
            .failure.code shouldBe PolicySeatFailureCode.DUPLICATE_IN_FLIGHT
        worker.release.countDown()
        runtime.close()
    }

    test("blocking Python inference does not hold the GameSession state lock") {
        val game = session()
        game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val worker = BlockingWorker()
        val runtime = PolicySeatRuntime(game, P1, worker)

        runtime.decideAsync { _ -> }
        worker.started.await(2, TimeUnit.SECONDS) shouldBe true
        game.resetStateForDevScenario(game.getStateForTesting()!!.copy(turnNumber = 2))
        worker.release.countDown()
        runtime.close()
    }

    test("worker failure executes no action and advances no PolicyTieRng state") {
        val game = session()
        game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val runtime = PolicySeatRuntime(
            game,
            P1,
            object : PolicySeatWorker {
                override fun decide(request: com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1): LivePolicyDecisionResponseV1 =
                    throw PolicySeatFailure(
                        PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                        "synthetic worker failure",
                    )

                override fun close() = Unit
            },
        )

        val result = runtime.decide()

        result.shouldBeTypeOf<PolicySeatDecisionResult.Rejected>()
            .failure.code shouldBe PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE
        game.getRecordedActions().size shouldBe 0
        game.getPolicySeatState(P1)?.cursor shouldBe 0uL
        runtime.close()
    }

    test("runtime close shuts down its worker and rejects later inference") {
        val game = session()
        game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        val worker = RecordingWorker()
        val runtime = PolicySeatRuntime(game, P1, worker)

        runtime.close()

        worker.closed shouldBe true
        runtime.decide().shouldBeTypeOf<PolicySeatDecisionResult.Rejected>()
            .failure.code shouldBe PolicySeatFailureCode.RUNTIME_CLOSED
    }

    test("runtime manager does not share worker or PolicyTieRng state between seats") {
        val properties = GameProperties(
            mlPolicy = MlPolicyProperties(
                enabled = true,
                checkpointDirectory = "C:/server-owned/checkpoint",
            ),
        )
        val created = AtomicInteger(0)
        val workers = mutableListOf<RecordingWorker>()
        val manager = PolicySeatRuntimeManager(
            properties,
            PolicySeatWorkerFactory {
                created.incrementAndGet()
                RecordingWorker().also(workers::add)
            },
        )
        val game = session(maxPlayers = 2)
        game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
        game.setControllerAuthority(P2, ControllerAuthorityV1.mlPolicy(1, 42L))

        val first = manager.runtimeFor(game, P1)
        val same = manager.runtimeFor(game, P1)
        val second = manager.runtimeFor(game, P2)

        first shouldBe same
        first shouldNotBe second
        created.get() shouldBe 2
        game.getPolicySeatState(P1)?.streamKeyHex shouldNotBe game.getPolicySeatState(P2)?.streamKeyHex
        manager.closeGame(game.sessionId)
        workers.all { it.closed } shouldBe true
    }
}) {
    companion object {
        private val P1 = EntityId("policy-player-1")
        private val P2 = EntityId("policy-player-2")

        private fun session(maxPlayers: Int = 2): GameSession {
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
    }
}

private open class RecordingWorker : PolicySeatWorker {
    var closed = false
    val requestCount = AtomicInteger(0)

    override fun decide(
        request: com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1,
    ): LivePolicyDecisionResponseV1 {
        requestCount.incrementAndGet()
        return LivePolicyDecisionResponseV1(
            requestId = request.requestId,
            selectedSourceBindingOrdinal = 0,
            policyRngState = request.policyRngState,
            rngCursorBefore = request.policyRngState.cursor,
            rngCursorAfter = request.policyRngState.cursor,
            rngDrawCount = 0uL,
        )
    }

    override fun close() {
        closed = true
    }
}

private class BlockingWorker : RecordingWorker() {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun decide(
        request: com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1,
    ): LivePolicyDecisionResponseV1 {
        started.countDown()
        release.await(2, TimeUnit.SECONDS)
        return super.decide(request)
    }
}
