package com.wingedsheep.gameserver.handler

import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.lobby.LobbyPlayerState
import com.wingedsheep.gameserver.lobby.TournamentLobby
import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.protocol.ErrorCode
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gameserver.session.SessionRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

class SpectatingHandlerAdmissionTest : FunSpec({

    fun webSocket(id: String = "ws") = mockk<WebSocketSession>(relaxed = true) {
        every { this@mockk.id } returns id
    }

    fun identity(id: String = "spectator") = PlayerIdentity(
        token = "token-$id",
        playerId = com.wingedsheep.sdk.model.EntityId.of(id),
        playerName = id,
    )

    data class Fixture(
        val handler: SpectatingHandler,
        val context: LobbySharedContext,
        val sender: MessageSender,
        val gameRepository: GameRepository,
        val lobbyRepository: LobbyRepository,
        val sessionRegistry: SessionRegistry,
        val game: GameSession,
        val identity: PlayerIdentity,
        val playerSession: PlayerSession,
        val webSocket: WebSocketSession,
    )

    fun fixture(
        publicSpectate: Boolean = false,
        lobbyId: String? = null,
        lobby: TournamentLobby? = null,
    ): Fixture {
        val sender = mockk<MessageSender>(relaxed = true)
        val gameRepository = mockk<GameRepository>(relaxed = true)
        val lobbyRepository = mockk<LobbyRepository>(relaxed = true)
        val sessionRegistry = mockk<SessionRegistry>(relaxed = true)
        val aiGameManager = mockk<AiGameManager>(relaxed = true)
        val context = mockk<LobbySharedContext>(relaxed = true)
        every { context.sender } returns sender
        every { context.gameRepository } returns gameRepository
        every { context.lobbyRepository } returns lobbyRepository
        every { context.sessionRegistry } returns sessionRegistry

        val game = mockk<GameSession>(relaxed = true)
        every { game.sessionId } returns "game"
        every { game.publicSpectate } returns publicSpectate
        every { gameRepository.findById("game") } returns game
        every { gameRepository.getLobbyForGame("game") } returns lobbyId
        if (lobbyId != null) every { lobbyRepository.findLobbyById(lobbyId) } returns lobby

        val identity = identity()
        val webSocket = webSocket()
        identity.webSocketSession = webSocket
        val playerSession = PlayerSession(webSocket, identity.playerId, identity.playerName)
        every { sessionRegistry.getTokenByWsId("ws") } returns identity.token
        every { sessionRegistry.getIdentityByToken(identity.token) } returns identity
        every { sessionRegistry.getPlayerSession("ws") } returns playerSession

        return Fixture(
            handler = SpectatingHandler(context, SpectatorAdmissionPolicy(gameRepository, lobbyRepository)),
            context = context,
            sender = sender,
            gameRepository = gameRepository,
            lobbyRepository = lobbyRepository,
            sessionRegistry = sessionRegistry,
            game = game,
            identity = identity,
            playerSession = playerSession,
            webSocket = webSocket,
        )
    }

    test("an unrelated authenticated user cannot spectate a private game") {
        val f = fixture()
        every { f.game.getPlayerNames() } returns listOf("Alice", "Bob")
        every { f.game.buildSpectatorState() } returns null

        f.handler.handleSpectateGame(f.webSocket, ClientMessage.SpectateGame("game"))

        verify(exactly = 0) { f.game.addSpectator(any()) }
        verify(exactly = 0) { f.context.broadcastSpectatorCount(any()) }
        verify { f.sender.sendError(f.webSocket, ErrorCode.GAME_NOT_FOUND, any()) }
        verify(exactly = 0) { f.sender.send(f.webSocket, any()) }
        f.identity.currentSpectatingGameId shouldBe null
    }

    test("an authenticated spectator can join an intentionally public game") {
        val f = fixture(publicSpectate = true)

        f.handler.handleSpectateGame(f.webSocket, ClientMessage.SpectateGame("game"))

        verify { f.game.addSpectator(f.playerSession) }
        f.identity.currentSpectatingGameId shouldBe "game"
    }

    test("a public tournament lobby keeps its existing public match behavior") {
        val lobby = mockk<TournamentLobby>(relaxed = true)
        every { lobby.isPublic } returns true
        val f = fixture(lobbyId = "lobby", lobby = lobby)

        f.handler.handleSpectateGame(f.webSocket, ClientMessage.SpectateGame("game"))

        verify { f.game.addSpectator(f.playerSession) }
        f.identity.currentSpectatingGameId shouldBe "game"
    }

    test("a tournament member can spectate another match in the same private lobby") {
        val lobby = mockk<TournamentLobby>(relaxed = true)
        val f = fixture(lobbyId = "lobby", lobby = lobby)
        val members = ConcurrentHashMap<com.wingedsheep.sdk.model.EntityId, LobbyPlayerState>()
        members[f.identity.playerId] = LobbyPlayerState(f.identity)
        every { lobby.players } returns members

        f.handler.handleSpectateGame(f.webSocket, ClientMessage.SpectateGame("game"))

        verify { f.game.addSpectator(f.playerSession) }
        f.identity.currentSpectatingGameId shouldBe "game"
    }

    test("restore rechecks admission for a private game") {
        val f = fixture()
        every { f.game.getPlayerNames() } returns listOf("Alice", "Bob")
        every { f.game.buildSpectatorState() } returns null

        f.handler.restoreSpectating(f.identity, f.playerSession, f.webSocket, "game")

        verify(exactly = 0) { f.game.addSpectator(any()) }
        verify(exactly = 0) { f.context.broadcastSpectatorCount(any()) }
        verify(exactly = 0) { f.sender.send(f.webSocket, any()) }
        f.identity.currentSpectatingGameId shouldBe null
    }
})
