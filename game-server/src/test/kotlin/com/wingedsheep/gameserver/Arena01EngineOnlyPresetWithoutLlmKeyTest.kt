package com.wingedsheep.gameserver

import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * RED characterization for the review finding: an Engine-only preset must not require an LLM key.
 * The current implementation fails before it can create the lobby because it uses [isEnabled].
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.dev-endpoints.enabled=true",
        "game.ai.enabled=true",
        "game.ai.mode=llm",
        "game.ai.api-key=",
        "game.ai.open-router-api-key=",
        "game.ai.thinking-delay-ms=0",
        "game.hand-smoother.enabled=false",
    ],
)
class Arena01EngineOnlyPresetWithoutLlmKeyTest : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var aiGameManager: AiGameManager

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val preset = "argentum-mtg-ml-akiri-chevill-curriculum@v1"

    init {
        test("engine-only curriculum preset starts with LLM mode and no API key") {
            val response = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:$port/api/dev/ai-tournament"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"preset\":\"$preset\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            // RED on the reviewed head: the launcher currently rejects this before creating a lobby.
            response.statusCode() shouldBe 200
            val lobbyId = json.parseToJsonElement(response.body()).jsonObject["lobbyId"]!!.jsonPrimitive.content

            var game: GameSession? = null
            eventually(20.seconds) {
                game = gameRepository.findAll().firstOrNull {
                    gameRepository.getLobbyForGame(it.sessionId) == lobbyId && it.isStarted
                }
                (game != null) shouldBe true
            }
            val started = requireNotNull(game)
            started.getPlayers().size shouldBe 2
            started.getPlayerPersistenceInfo().values.count { it.isAi } shouldBe 2
            aiGameManager.hasAiPlayer(started.sessionId) shouldBe true

            // A real Engine controller can complete the no-input mulligan without an LLM service.
            eventually(20.seconds) {
                started.allMulligansComplete shouldBe true
            }
            response.body() shouldContain "\"presetIdentity\":\"$preset\""
        }

        test("engine-only AI identity rehydrates with LLM mode and no API key") {
            val identity = PlayerIdentity(
                token = "recovered-engine-only-token",
                playerId = EntityId("recovered-engine-only"),
                playerName = "Recovered Engine AI",
                isAi = true,
                forceEngine = true,
            )

            aiGameManager.rehydrateAiIdentity(identity)

            identity.webSocketSession.shouldNotBeNull()
            identity.forceEngine shouldBe true
        }
    }
}
