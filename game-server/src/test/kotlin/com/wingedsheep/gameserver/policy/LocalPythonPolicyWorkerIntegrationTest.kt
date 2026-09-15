package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
