package com.wingedsheep.gameserver

import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.SessionRegistry
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration.Companion.seconds

/**
 * Failure-injection coverage for the one mixed-controller launch step that can notify a player
 * before the remaining startup work has completed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.dev-endpoints.enabled=true",
        "game.dev-endpoints.test-failure-after-tournament-match-starting=true",
        "game.ai.enabled=true",
        "game.ai.mode=llm",
        "game.ai.api-key=",
        "game.ai.open-router-api-key=",
        "game.ai.thinking-delay-ms=0",
        "game.hand-smoother.enabled=false",
    ],
)
class ArenaHuman01PartialStartRollbackTest : GameServerTestBase() {

    @Autowired
    private lateinit var sessionRegistry: SessionRegistry

    @Autowired
    private lateinit var lobbyRepository: LobbyRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    init {
        test("failure after match notification resets the player before rollback completes") {
            // HUMAN_AI_14/HUMAN_AI_19: exercise the partial-start compensating cleanup after the
            // client has already received TournamentMatchStarting.
            val client = createClient()
            client.connectAs("Arena Partial Failure")
            client.send(ClientMessage.StartCurriculumHumanVsEngineAi)

            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.TournamentMatchStarting } shouldBe true
            }
            val matchStartingIndex = client.messages.indexOfFirst { it is ServerMessage.TournamentMatchStarting }

            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.GameCancelled } shouldBe true
            }
            val cancelledIndex = client.messages.indexOfFirst { it is ServerMessage.GameCancelled }
            (cancelledIndex > matchStartingIndex) shouldBe true
            client.messages.none { it is ServerMessage.GameStarted } shouldBe true

            eventually(10.seconds) {
                lobbyRepository.findAllLobbies().none { lobby ->
                    lobby.players.values.any { it.identity.playerName == "Arena Partial Failure" }
                } shouldBe true
                gameRepository.findAll().none { game ->
                    gameRepository.getLobbyForGame(game.sessionId) != null
                } shouldBe true
            }

            val identity = sessionRegistry.getAllIdentities().singleOrNull {
                !it.isAi && it.playerName == "Arena Partial Failure"
            }
            identity?.currentLobbyId shouldBe null
            identity?.currentGameSessionId shouldBe null
        }
    }
}
