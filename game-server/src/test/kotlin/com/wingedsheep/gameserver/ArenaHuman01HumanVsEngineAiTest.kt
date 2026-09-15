package com.wingedsheep.gameserver

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.handler.GamePlayHandler
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.persistence.restoreGameSession
import com.wingedsheep.gameserver.persistence.restoreTournamentLobby
import com.wingedsheep.gameserver.persistence.toPersistent
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.protocol.ErrorCode
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.stats.MatchResultSink
import com.wingedsheep.gameserver.stats.RecordedMatch
import com.wingedsheep.gameserver.stats.RecordedTournament
import com.wingedsheep.gameserver.stats.TournamentResultSink
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import kotlin.time.Duration.Companion.seconds

@TestConfiguration(proxyBeanMethods = false)
class ArenaHuman01MatchSinkTestConfiguration {
    @Bean
    @Primary
    fun capturingMatchResultSink() = CapturingMatchResultSink()

    @Bean
    @Primary
    fun capturingTournamentResultSink() = CapturingTournamentResultSink()
}

class CapturingMatchResultSink : MatchResultSink {
    val matches = java.util.concurrent.CopyOnWriteArrayList<RecordedMatch>()

    override fun record(match: RecordedMatch) {
        matches += match
    }
}

class CapturingTournamentResultSink : TournamentResultSink {
    val snapshots = java.util.concurrent.CopyOnWriteArrayList<RecordedTournament>()

    override fun recordStarted(tournament: RecordedTournament) {
        snapshots += tournament
    }

    override fun recordProgress(tournament: RecordedTournament) {
        snapshots += tournament
    }

    override fun recordCompleted(tournament: RecordedTournament) {
        snapshots += tournament
    }

    override fun recordAbandoned(lobbyId: String) = Unit
}

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
@Import(ArenaHuman01MatchSinkTestConfiguration::class)
class ArenaHuman01HumanVsEngineAiTest : GameServerTestBase() {

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var lobbyRepository: LobbyRepository

    @Autowired
    private lateinit var aiGameManager: AiGameManager

    @Autowired
    private lateinit var gamePlayHandler: GamePlayHandler

    @Autowired
    private lateinit var cardRegistry: CardRegistry

    @Autowired
    private lateinit var printingRegistry: PrintingRegistry

    @Autowired
    private lateinit var tokenArtRegistry: TokenArtRegistry

    @Autowired
    private lateinit var matchResultSink: CapturingMatchResultSink

    @Autowired
    private lateinit var tournamentResultSink: CapturingTournamentResultSink

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

            eventually(10.seconds) {
                client.latestMulliganDecision().shouldNotBeNull()
            }
            game.hasMulliganComplete(humanId) shouldBe false
            client.send(ClientMessage.KeepHand)
            eventually(10.seconds) {
                game.hasMulliganComplete(humanId) shouldBe true
            }

            // HUMAN_AI_09: a post-mulligan choice still travels through the normal player
            // protocol. Wait for a legal priority window instead of guessing whose turn is first.
            var humanActionSent = false
            eventually(20.seconds) {
                if (!humanActionSent && game.getLegalActions(humanId).any { it.action is PassPriority }) {
                    client.send(ClientMessage.SubmitAction(PassPriority(humanId)))
                    humanActionSent = true
                }
                game.getRecordedActions().any {
                    it.playerId == humanId && it is PassPriority
                } shouldBe true
            }

            // HUMAN_AI_08/HUMAN_AI_10: the human action is attributed to Akiri, while a later
            // priority action is attributed to Chevill's existing forced Engine AI seat.
            game.getRecordedActions().any { it.playerId == aiSeat.identity.playerId } shouldBe true
            game.getRecordedActions().filter { it is PassPriority && it.playerId == humanId }.shouldHaveSize(1)

            val intruder = createClient()
            intruder.connectAs("Arena Intruder")
            intruder.send(ClientMessage.SubmitAction(PassPriority(humanId)))
            eventually(10.seconds) {
                intruder.latestError()?.code shouldBe ErrorCode.GAME_NOT_FOUND
            }

            client.send(ClientMessage.Concede)
            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.GameOver } shouldBe true
            }
            // HUMAN_AI_17/HUMAN_AI_18/HUMAN_AI_21: the linked Research Arena policy reaches both
            // durable sinks while ordinary sink tests retain the default-true behavior.
            matchResultSink.matches shouldBe emptyList()
            tournamentResultSink.snapshots.isNotEmpty() shouldBe true
            tournamentResultSink.snapshots.all { !it.recordDurableStats } shouldBe true
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

        test("reconnect restores the existing human tournament seat") {
            val client = createClient()
            client.connectAs("Arena Reconnect")
            client.send(ClientMessage.StartCurriculumHumanVsEngineAi)

            eventually(20.seconds) {
                client.messages.any { it is ServerMessage.TournamentMatchStarting } shouldBe true
            }
            val matchStarting = client.messages
                .filterIsInstance<ServerMessage.TournamentMatchStarting>()
                .last()
            val token = client.messages.filterIsInstance<ServerMessage.Connected>().first().token
            client.close()

            val reconnected = createClient()
            reconnected.connect()
            reconnected.send(ClientMessage.Connect("Different Display Name", token = token))

            eventually(20.seconds) {
                reconnected.messages.any {
                    it is ServerMessage.Reconnected &&
                        it.context == "tournament" &&
                        it.contextId == matchStarting.lobbyId
                } shouldBe true
            }
            eventually(20.seconds) {
                reconnected.messages.any {
                    it is ServerMessage.TournamentMatchStarting &&
                        it.gameSessionId == matchStarting.gameSessionId
                } shouldBe true
            }
        }

        test("recovered Research Arena game preserves its lobby link and durable-stats policy") {
            val client = createClient()
            val humanId = EntityId(client.connectAs("Arena Recovery"))
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
            val game = awaitStartedGame(matchStarting.gameSessionId)
            eventually(10.seconds) {
                client.latestMulliganDecision().shouldNotBeNull()
            }
            client.send(ClientMessage.KeepHand)
            eventually(20.seconds) {
                game.allMulligansComplete shouldBe true
            }

            // HUMAN_AI_20/HUMAN_AI_22: freeze the original AI callback before replacing the in-memory object with the
            // persisted/recovered representation. The recovery assertion is about the restored
            // authority and stats link, not about starting a second AI loop.
            aiGameManager.cleanupGame(game.sessionId)
            val humanSession = game.getPlayerSession(humanId).shouldNotBeNull()
            val aiId = lobby.players.values.single { it.identity.isAi }.identity.playerId
            val aiSession = game.getPlayerSession(aiId).shouldNotBeNull()

            val persistentGame = game.toPersistent(lobby.lobbyId)
            persistentGame.lobbyId shouldBe lobby.lobbyId
            val (recoveredGame, recoveredIdentities) = restoreGameSession(
                persistentGame,
                cardRegistry,
                printingRegistry,
                tokenArtRegistry,
            )
            recoveredIdentities.all { it.currentLobbyId == lobby.lobbyId } shouldBe true
            recoveredIdentities.all { it.currentGameSessionId == game.sessionId } shouldBe true

            val recoveredLobby = restoreTournamentLobby(
                lobby.toPersistent(),
                cardRegistry,
                BoosterGenerator(emptyMap()),
            ).first
            recoveredLobby.recordDurableStats shouldBe false
            lobbyRepository.saveLobby(recoveredLobby)

            gameRepository.remove(game.sessionId)
            gameRepository.save(recoveredGame)
            gameRepository.linkToLobby(recoveredGame.sessionId, recoveredLobby.lobbyId)
            recoveredGame.associatePlayer(humanSession)
            recoveredGame.associatePlayer(aiSession)
            matchResultSink.matches.clear()
            tournamentResultSink.snapshots.clear()

            // Complete the recovered GameSession through the existing game-over path. The
            // restored lobby link makes GamePlayHandler consult recordDurableStats=false.
            gamePlayHandler.concedeSeat(recoveredGame, humanId)

            eventually(10.seconds) {
                gameRepository.findById(recoveredGame.sessionId) shouldBe null
                matchResultSink.matches shouldBe emptyList()
                tournamentResultSink.snapshots.isNotEmpty() shouldBe true
                tournamentResultSink.snapshots.all { !it.recordDurableStats } shouldBe true
            }
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
