package com.wingedsheep.gameserver.handler

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.policy.PolicySeatRuntimeManager
import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.replay.EngineVersion
import com.wingedsheep.gameserver.replay.ReplayCheckpointFlusher
import com.wingedsheep.gameserver.replay.ReplayService
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gameserver.session.SessionRegistry
import com.wingedsheep.gameserver.stats.DeckProfiler
import com.wingedsheep.gameserver.stats.MatchResultSink
import com.wingedsheep.gameserver.ranking.RankedResultSink
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.web.socket.WebSocketSession

class GamePlayHandlerPolicyPregameDispatchTest : FunSpec({
    test("a human mulligan completion dispatches the next ML pregame decision") {
        val playerId = EntityId("human")
        val webSocket = mockk<WebSocketSession>(relaxed = true) {
            every { id } returns "human-ws"
        }
        val player = PlayerSession(
            webSocketSession = webSocket,
            playerId = playerId,
            playerName = "Human",
            currentGameSessionId = "game",
        )
        val sessionRegistry = mockk<SessionRegistry>(relaxed = true)
        val gameRepository = mockk<GameRepository>(relaxed = true)
        val game = mockk<GameSession>(relaxed = true)
        val policyRuntimeManager = mockk<PolicySeatRuntimeManager>(relaxed = true)

        every { sessionRegistry.getPlayerSession("human-ws") } returns player
        every { gameRepository.findById("game") } returns game
        every { game.isMulliganPhase } returns true
        every { game.keepHand(playerId) } returns GameSession.MulliganActionResult.Success
        every { game.getHand(playerId) } returns emptyList()
        every { game.allMulligansComplete } returns false
        every { game.getPlayers() } returns emptyList()
        every { policyRuntimeManager.dispatchIfNeeded(game, any()) } returns true

        val handler = GamePlayHandler(
            sessionRegistry = sessionRegistry,
            gameRepository = gameRepository,
            lobbyRepository = mockk<LobbyRepository>(relaxed = true),
            sender = mockk<MessageSender>(relaxed = true),
            cardRegistry = mockk<CardRegistry>(relaxed = true),
            printingRegistry = mockk(relaxed = true),
            tokenArtRegistry = mockk(relaxed = true),
            deckGenerator = mockk(relaxed = true),
            gameProperties = GameProperties(),
            replayService = mockk<ReplayService>(relaxed = true),
            replayCheckpointFlusher = mockk<ReplayCheckpointFlusher>(relaxed = true),
            engineVersion = mockk<EngineVersion>(relaxed = true),
            aiGameManager = mockk<AiGameManager>(relaxed = true),
            policySeatRuntimeManager = policyRuntimeManager,
            matchResultSink = mockk<MatchResultSink>(relaxed = true),
            rankedResultSink = mockk<RankedResultSink>(relaxed = true),
            deckProfiler = mockk<DeckProfiler>(relaxed = true),
        )

        handler.handle(webSocket, ClientMessage.KeepHand)

        verify(exactly = 1) { policyRuntimeManager.dispatchIfNeeded(game, any()) }
    }
})
