package com.wingedsheep.gameserver

import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.DeckFormat
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.GameRules
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.dev-endpoints.enabled=true",
        "game.ai.enabled=true",
        "game.ai.mode=llm",
        "game.ai.api-key=",
        "game.ai.open-router-api-key=",
        "game.ai.thinking-delay-ms=30000",
        "game.hand-smoother.enabled=false",
    ],
)
class ArenaHuman01HumanVsEngineAiTest : GameServerTestBase() {

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var lobbyRepository: LobbyRepository

    @Autowired
    private lateinit var aiGameManager: AiGameManager

    private val loader = CurriculumDeckSourceLoader()

    init {
        test("binds the current human to Akiri and Chevill to forced Engine AI") {
            val client = createClient()
            val humanId = EntityId(client.connectAs("Arena Human"))

            client.send(ClientMessage.StartCurriculumHumanVsEngineAi)

            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.TournamentMatchStarting } shouldBe true
            }
            val matchStarting = client.messages
                .filterIsInstance<ServerMessage.TournamentMatchStarting>()
                .last()
            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.GameStarted } shouldBe true
            }

            val lobby = lobbyRepository.findLobbyById(matchStarting.lobbyId).shouldNotBeNull()
            lobby.players.values shouldHaveSize 2
            lobby.format shouldBe TournamentFormat.PREMADE_DECKS
            lobby.rules shouldBe GameRules.COMMANDER
            lobby.deckFormat shouldBe DeckFormat.COMMANDER
            lobby.deckSizeMin shouldBe 100
            lobby.allowDuplicates shouldBe false
            lobby.immutableFixedDeckSource shouldBe true
            lobby.recordDurableStats shouldBe false

            val humanSeat = lobby.players.values.single { !it.identity.isAi }
            humanSeat.identity.playerId shouldBe humanId
            humanSeat.commander shouldBe "Akiri, Fearless Voyager"

            val aiSeat = lobby.players.values.single { it.identity.isAi }
            aiSeat.identity.forceEngine shouldBe true
            aiSeat.commander shouldBe "Chevill, Bane of Monsters"

            val sources = listOf(
                loader.load("docs/ml/curriculum/akiri-v0.1.txt"),
                loader.load("docs/ml/curriculum/chevill-v0.1.txt"),
            )
            humanSeat.submittedDeck shouldBe sources[0].deckList
            aiSeat.submittedDeck shouldBe sources[1].deckList
            aiGameManager.isAiPlayer(aiSeat.identity.playerId) shouldBe true

            val game = awaitStartedGame(matchStarting.gameSessionId)
            game.engineFormat shouldBe Format.Commander()
            val state = game.getStateForTesting().shouldNotBeNull()
            state.lifeTotal(humanId) shouldBe 40
            state.lifeTotal(aiSeat.identity.playerId) shouldBe 40
            state.getZone(ZoneKey(humanId, Zone.COMMAND)) shouldHaveSize 1
            state.getZone(ZoneKey(aiSeat.identity.playerId, Zone.COMMAND)) shouldHaveSize 1
            game.getPlayerPersistenceInfo()[humanId]?.isAi shouldBe false
            game.getPlayerPersistenceInfo()[aiSeat.identity.playerId]?.isAi shouldBe true
            game.getPlayerPersistenceInfo()[aiSeat.identity.playerId]?.forceEngine shouldBe true
            gameRepository.getLobbyForGame(game.sessionId) shouldBe matchStarting.lobbyId
        }

        test("one ordinary start action does not create two human curriculum lobbies") {
            val client = createClient()
            client.connectAs("Arena Duplicate Guard")

            client.send(ClientMessage.StartCurriculumHumanVsEngineAi)
            client.send(ClientMessage.StartCurriculumHumanVsEngineAi)

            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.TournamentMatchStarting } shouldBe true
            }
            val humanLobbies = lobbyRepository.findAllLobbies().count { lobby ->
                lobby.players.values.any { player ->
                    !player.identity.isAi && player.identity.playerName == "Arena Duplicate Guard"
                }
            }
            humanLobbies shouldBe 1
        }
    }

    private suspend fun awaitStartedGame(gameSessionId: String): GameSession {
        var game: GameSession? = null
        eventually(20.seconds) {
            game = gameRepository.findById(gameSessionId)
                ?.takeIf { it.isStarted }
            game.shouldNotBeNull()
        }
        return game.shouldNotBeNull()
    }
}
