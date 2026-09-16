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
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.web.socket.WebSocketSession

class LocalPythonPolicyWorkerIntegrationTest : FunSpec({
    test("JVM PolicySeatRuntime round-trips one real C1_07B CUDA decision")
        .config(enabled = REAL_WORKER_AVAILABLE) {
            val game = session()
            game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
            val worker = LocalPythonPolicyWorker.start(realConfiguration())
            val runtime = PolicySeatRuntime(game, P1, worker)

            try {
                val result = runtime.decide()

                val accepted = result.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
                accepted.actionResult.shouldBeTypeOf<GameSession.ActionResult.Success>()
                game.getRecordedActions().size shouldBe 1
                game.getPolicySeatState(P1)?.cursor shouldBe 0uL
            } finally {
                runtime.close()
            }
        }

    test("real ML seat progresses from normal game start through pregame to gameplay")
        .config(enabled = REAL_WORKER_AVAILABLE) {
            val registry = CardRegistry().apply { register(TestCards.all) }
            val game = GameSession(cardRegistry = registry)
            game.addPlayer(
                PlayerSession(ws("real-policy"), P1, "Policy"),
                mapOf("Forest" to 40),
            )
            game.addPlayer(
                PlayerSession(ws("real-human"), P2, "Human"),
                mapOf("Island" to 40),
            )
            game.setControllerAuthority(P1, ControllerAuthorityV1.mlPolicy(0, 42L))
            game.startGame()

            val runtime = PolicySeatRuntime(game, P1, LocalPythonPolicyWorker.start(realConfiguration()))
            try {
                var mlPregameDecisions = 0
                while (!game.allMulligansComplete) {
                    if (game.policySeatToAct() == P1) {
                        val result = runtime.decide()
                        result.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
                        mlPregameDecisions++
                    } else {
                        val state = game.getStateForTesting()!!
                        val owner = state.turnOrder.first { playerId ->
                            state.getEntity(playerId)?.get<MulliganStateComponent>()?.let {
                                !it.hasKept || it.cardsToBottom > 0
                            } == true
                        }
                        owner shouldBe P2
                        val mulligan = state.getEntity(P2)!!.get<MulliganStateComponent>()!!
                        if (!mulligan.hasKept) {
                            when (val keep = game.keepHand(P2)) {
                                is GameSession.MulliganActionResult.NeedsBottomCards ->
                                    game.chooseBottomCards(P2, game.getHand(P2).take(keep.count))
                                        .shouldBeTypeOf<GameSession.MulliganActionResult.Success>()
                                is GameSession.MulliganActionResult.Success -> Unit
                                is GameSession.MulliganActionResult.Failure ->
                                    error("human pregame keep failed: ${keep.reason}")
                            }
                        } else {
                            game.chooseBottomCards(P2, game.getHand(P2).take(mulligan.cardsToBottom))
                                .shouldBeTypeOf<GameSession.MulliganActionResult.Success>()
                        }
                    }
                    check(mlPregameDecisions < 20) {
                        "real ML pregame smoke exceeded the bounded decision count"
                    }
                }

                mlPregameDecisions shouldBeGreaterThan 0
                val actionsBeforeGameplay = game.getRecordedActions().size
                var humanPasses = 0
                while (game.policySeatToAct() != P1) {
                    val state = game.getStateForTesting() ?: error("game state disappeared")
                    val priority = state.priorityPlayerId ?: error("game has no gameplay priority")
                    state.actorFor(priority) shouldBe P2
                    game.executeAction(P2, PassPriority(priority))
                        .shouldBeTypeOf<GameSession.ActionResult.Success>()
                    humanPasses++
                    check(humanPasses < 20) {
                        "real ML gameplay smoke could not reach the ML seat"
                    }
                }

                val gameplay = runtime.decide()
                gameplay.shouldBeTypeOf<PolicySeatDecisionResult.Accepted>()
                game.getRecordedActions().size shouldBeGreaterThan actionsBeforeGameplay
            } finally {
                runtime.close()
            }
        }

    test("JVM worker startup rejects an unavailable checkpoint before READY")
        .config(enabled = PYTHON_AVAILABLE) {
            val configuration = PolicySeatRuntimeConfiguration(
                pythonExecutable = pythonExecutable,
                checkpointDirectory = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "argentum-c1-07c-missing-checkpoint",
                ),
            )

            val failure = io.kotest.assertions.throwables.shouldThrow<PolicySeatFailure> {
                LocalPythonPolicyWorker.start(configuration)
            }

            failure.code shouldBe PolicySeatFailureCode.WORKER_STARTUP_FAILURE
        }
}) {
    companion object {
        private val P1 = EntityId("policy-player-1")
        private val P2 = EntityId("policy-player-2")
        private val checkpointDirectory = Path.of(
            System.getenv("ARGENTUM_C1_06_CHECKPOINT_DIR")
                ?: Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "argentum-c1-06-gpu-smoke-943338abbaf47f289cfe606acd50caf0a2b15ef5",
                ).toString(),
        )
        private val pythonExecutable = System.getenv("ARGENTUM_ML_PYTHON")
            ?: "C:\\Python313\\python.exe"
        private val PYTHON_AVAILABLE = Files.isRegularFile(Path.of(pythonExecutable))
        private val REAL_WORKER_AVAILABLE =
            PYTHON_AVAILABLE && Files.isDirectory(checkpointDirectory)

        private fun realConfiguration() = PolicySeatRuntimeConfiguration(
            pythonExecutable = pythonExecutable,
            checkpointDirectory = checkpointDirectory,
        )

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
    }
}
