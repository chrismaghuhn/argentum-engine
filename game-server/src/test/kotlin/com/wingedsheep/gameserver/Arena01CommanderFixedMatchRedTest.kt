package com.wingedsheep.gameserver

import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.curriculum.CurriculumAiTournamentPreset
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.handler.SpectatorAdmissionPolicy
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.sdk.core.DeckFormat
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.GameRules
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.CommanderRegistryComponent
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.dev-endpoints.enabled=true",
        "game.ai.enabled=true",
        "game.ai.mode=engine",
        "game.ai.thinking-delay-ms=30000",
        "game.easter-eggs.enabled=true",
    ],
)
class Arena01CommanderFixedMatchTest : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var lobbyRepository: LobbyRepository

    @Autowired
    private lateinit var aiGameManager: AiGameManager

    @Autowired
    private lateinit var spectatorAdmissionPolicy: SpectatorAdmissionPolicy

    private val loader = CurriculumDeckSourceLoader()
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        test("legacy fixed AI decks remain standard") {
            val response = launch("{\"decks\":[{\"Forest\":40},{\"Forest\":40}]}")

            response.statusCode() shouldBe HttpStatus.OK.value()
            val responseBody = response.jsonObject()
            val lobbyId = responseBody["lobbyId"]!!.jsonPrimitive.content
            val lobby = lobbyRepository.findLobbyById(lobbyId).shouldNotBeNull()
            lobby.format shouldBe TournamentFormat.PREMADE_DECKS
            lobby.rules shouldBe GameRules.STANDARD
            lobby.deckFormat shouldBe null

            val game = awaitGame(lobbyId)
            game.engineFormat shouldBe Format.Standard
            game.getStateForTesting().shouldNotBeNull().format shouldBe Format.Standard
        }

        test("commander curriculum preset launches the exact two-seat Commander match") {
            val preset = CurriculumAiTournamentPreset.AKIRI_CHEVILL
            val response = launch("{\"preset\":\"${preset.identity}\"}")
            response.statusCode() shouldBe HttpStatus.OK.value()
            val responseBody = response.jsonObject()
            responseBody["presetIdentity"]!!.jsonPrimitive.content shouldBe preset.identity
            responseBody["curriculumSources"]!!.jsonArray shouldHaveSize 2

            val expectedSources = preset.sourcePaths.map(loader::load)
            val responseSources = responseBody["curriculumSources"]!!.jsonArray
            responseSources.map { it.jsonObject["sourcePath"]!!.jsonPrimitive.content } shouldBe expectedSources.map { it.sourcePath }
            responseSources.map { it.jsonObject["sourceDigest"]!!.jsonPrimitive.content } shouldBe expectedSources.map { it.sourceDigest }
            responseSources.map { it.jsonObject["cardCount"]!!.jsonPrimitive.int } shouldBe listOf(100, 100)
            responseSources.map { it.jsonObject["commander"]!!.jsonPrimitive.content } shouldBe expectedSources.map { it.commander }

            val lobbyId = responseBody["lobbyId"]!!.jsonPrimitive.content
            val lobby = lobbyRepository.findLobbyById(lobbyId).shouldNotBeNull()
            lobby.format shouldBe TournamentFormat.PREMADE_DECKS
            lobby.rules shouldBe GameRules.COMMANDER
            lobby.deckFormat shouldBe DeckFormat.COMMANDER
            lobby.deckSizeMin shouldBe 100
            lobby.allowDuplicates shouldBe false
            lobby.isPublic shouldBe true
            lobby.immutableFixedDeckSource shouldBe true
            lobby.engineAiOnly shouldBe true
            lobby.players.values shouldHaveSize 2
            lobby.players.values.map { it.commander }.toSet() shouldBe expectedSources.map { it.commander }.toSet()
            lobby.curriculumProvenance?.presetIdentity shouldBe preset.identity
            for (source in expectedSources) {
                lobby.players.values.single { it.commander == source.commander }.submittedDeck shouldBe source.deckList
            }

            val game = awaitGame(lobbyId)
            game.engineFormat shouldBe Format.Commander()
            val state = game.getStateForTesting().shouldNotBeNull()
            state.format shouldBe Format.Commander()
            game.getPlayers() shouldHaveSize 2

            for ((playerId, playerState) in lobby.players) {
                state.lifeTotal(playerId) shouldBe 40
                val commanderName = playerState.commander.shouldNotBeNull()
                val commandZone = state.getZone(ZoneKey(playerId, Zone.COMMAND))
                commandZone shouldHaveSize 1
                val commanderEntity = state.getEntity(commandZone.single()).shouldNotBeNull()
                commanderEntity.get<CardComponent>().shouldNotBeNull().name shouldBe commanderName
                commanderEntity.get<CommanderComponent>().shouldNotBeNull().ownerId shouldBe playerId
                state.getEntity(playerId)
                    ?.get<CommanderRegistryComponent>()
                    ?.commanderIds shouldBe commandZone

                val source = expectedSources.single { it.commander == commanderName }
                semanticCounts(game.getStartingDeckList(playerId).shouldNotBeNull()) shouldBe
                    source.asCommanderDeck().cards.groupingBy { it }.eachCount()
                game.getStartingDeckList(playerId)?.none { it.substringBefore('#') == "Sekshaas, Early Sleeper" } shouldBe true
            }

            game.getPlayerPersistenceInfo().values.all { it.isAi } shouldBe true
            game.getPlayers().all { aiGameManager.isAiPlayer(it.playerId) } shouldBe true
            aiGameManager.hasAiPlayer(game.sessionId) shouldBe true
            gameRepository.getLobbyForGame(game.sessionId) shouldBe lobbyId
            spectatorAdmissionPolicy.canSpectate(
                PlayerIdentity(playerId = com.wingedsheep.sdk.model.EntityId("arena-watcher"), playerName = "Watcher"),
                game,
            ) shouldBe true

            val status = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:$port/api/dev/ai-tournament/$lobbyId"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            status.statusCode() shouldBe HttpStatus.OK.value()
            status.jsonObject()["presetIdentity"]!!.jsonPrimitive.content shouldBe preset.identity
            status.jsonObject()["curriculumSources"]!!.jsonArray shouldHaveSize 2
        }

        test("locked curriculum preset rejects deck and model overrides") {
            val response = launch(
                "{\"preset\":\"${CurriculumAiTournamentPreset.AKIRI_CHEVILL.identity}\",\"models\":[\"caller-model\"]}",
            )
            response.statusCode() shouldBe HttpStatus.BAD_REQUEST.value()
            response.jsonObject()["message"]!!.jsonPrimitive.content shouldBe
                "The locked curriculum preset cannot be combined with caller overrides"
        }
    }

    private fun launch(body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/dev/ai-tournament"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun HttpResponse<String>.jsonObject(): JsonObject = json.parseToJsonElement(body()).jsonObject

    private suspend fun awaitGame(lobbyId: String): GameSession {
        var found: GameSession? = null
        eventually(30.seconds) {
            found = gameRepository.findAll().firstOrNull {
                gameRepository.getLobbyForGame(it.sessionId) == lobbyId && it.isStarted
            }
            found.shouldNotBeNull()
        }
        return found.shouldNotBeNull()
    }

    private fun semanticCounts(deck: List<String>): Map<String, Int> =
        deck.groupingBy { it.substringBefore('#') }.eachCount()
}
