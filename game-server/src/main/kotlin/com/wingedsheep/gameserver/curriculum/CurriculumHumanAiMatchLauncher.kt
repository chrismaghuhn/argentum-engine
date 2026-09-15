package com.wingedsheep.gameserver.curriculum

import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.lobby.AiDeckSpec
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.lobby.TournamentLobby
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.gameserver.session.SessionRegistry
import com.wingedsheep.gameserver.handler.TournamentMatchHandler
import com.wingedsheep.sdk.core.DeckFormat
import com.wingedsheep.sdk.core.GameRules
import com.wingedsheep.sdk.model.EntityId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

class CurriculumLaunchRejected(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Server-owned adapter for the one authorized mixed-controller curriculum profile.
 *
 * The module accepts a current server identity, not a player id, deck, source, or controller map.
 * It owns only the profile setup and rollback; TournamentMatchHandler remains the GameSession owner.
 */
@Component
class CurriculumHumanAiMatchLauncher(
    private val sessionRegistry: SessionRegistry,
    private val lobbyRepository: LobbyRepository,
    private val gameRepository: GameRepository,
    private val aiGameManager: AiGameManager,
    private val curriculumPresetService: CurriculumPresetService,
    private val tournamentMatchHandler: TournamentMatchHandler,
    private val boosterGenerator: BoosterGenerator,
    @Value("\${game.dev-endpoints.enabled:false}")
    private val devEndpointsEnabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(CurriculumHumanAiMatchLauncher::class.java)

    fun start(identity: PlayerIdentity): String {
        return synchronized(identity) { startLocked(identity) }
    }

    private fun startLocked(identity: PlayerIdentity): String {
        if (!devEndpointsEnabled) {
            throw CurriculumLaunchRejected("Research Arena dev endpoint is not enabled on this server.")
        }
        if (identity.isAi) {
            throw CurriculumLaunchRejected("An AI identity cannot start a human Research Arena match")
        }

        val socket = identity.webSocketSession
        val playerSession = socket?.let { sessionRegistry.getPlayerSession(it.id) }
        if (socket == null || !socket.isOpen || playerSession == null || playerSession.playerId != identity.playerId) {
            throw CurriculumLaunchRejected("The current human player session is not connected")
        }
        if (identity.currentLobbyId != null || identity.currentQuickGameLobbyId != null || identity.currentGameSessionId != null) {
            throw CurriculumLaunchRejected("The current player is already in a lobby or game")
        }
        if (!aiGameManager.aiEnabledToggle) {
            throw CurriculumLaunchRejected("Engine AI is not enabled on this server")
        }

        val validated = try {
            curriculumPresetService.loadValidated(CurriculumAiTournamentPreset.AKIRI_CHEVILL)
        } catch (e: Exception) {
            throw CurriculumLaunchRejected("The locked curriculum preset is invalid: ${e.message}", e)
        }
        val sources = validated.sources
        val humanSource = sources[0]
        val aiSource = sources[1]
        if (humanSource.commander != AKIRI_COMMANDER || aiSource.commander != CHEVILL_COMMANDER) {
            throw CurriculumLaunchRejected("The locked curriculum source order does not match the Human-Akiri profile")
        }

        var lobby: TournamentLobby? = null
        var aiIdentity: PlayerIdentity? = null
        try {
            lobby = TournamentLobby(
                setCodes = emptyList(),
                setNames = emptyList(),
                boosterGenerator = boosterGenerator,
                format = TournamentFormat.PREMADE_DECKS,
                boosterCount = 0,
                boosterDistribution = emptyMap(),
                maxPlayers = 2,
                gamesPerMatch = 1,
                isPublic = false,
                deckFormat = DeckFormat.COMMANDER,
                rules = GameRules.COMMANDER,
                deckSizeMin = 100,
                allowDuplicates = false,
                immutableFixedDeckSource = true,
                curriculumProvenance = validated.provenance,
                recordDurableStats = false,
                engineAiOnly = false,
            )

            val humanId = lobby.addPlayer(identity)
            submitExactDeck(lobby, humanId, humanSource)

            aiIdentity = aiGameManager.createAiIdentity(forceEngine = true)
            val aiId = lobby.addPlayer(aiIdentity)
            lobby.players.getValue(aiId).aiDeckSpec = AiDeckSpec.Fixed(
                deckList = aiSource.libraryDeckList(),
                label = aiSource.commander,
                commander = aiSource.commander,
            )
            submitExactDeck(lobby, aiId, aiSource)

            lobby.activatePremadeTournament()
            lobbyRepository.saveLobby(lobby)

            val tournament = tournamentMatchHandler.ensureTournamentCreated(lobby)
            lobby.players.values.forEach { player ->
                tournamentMatchHandler.sendTournamentStartedToPlayer(lobby, tournament, player.identity)
            }
            tournamentMatchHandler.autoReadyAiPlayers(
                lobby = lobby,
                tournament = tournament,
                autoReadyHumansVsAi = true,
            )
            lobbyRepository.saveLobby(lobby)

            logger.info(
                "Started Human-Akiri/Engine-Chevill curriculum match: lobbyId={}, preset={}",
                lobby.lobbyId,
                validated.preset.identity,
            )
            return lobby.lobbyId
        } catch (e: CurriculumLaunchRejected) {
            cleanupFailedLaunch(identity, lobby, aiIdentity)
            throw e
        } catch (e: Exception) {
            cleanupFailedLaunch(identity, lobby, aiIdentity)
            throw CurriculumLaunchRejected("Failed to start the locked Human-vs-Engine match: ${e.message}", e)
        }
    }

    private fun submitExactDeck(
        lobby: TournamentLobby,
        playerId: EntityId,
        source: CurriculumDeckSourceV1,
    ) {
        when (val result = lobby.submitDeck(playerId, source.deckList, commander = source.commander)) {
            is TournamentLobby.DeckSubmissionResult.Success -> Unit
            is TournamentLobby.DeckSubmissionResult.Error -> {
                throw CurriculumLaunchRejected("Failed to submit ${source.commander}'s locked curriculum deck: ${result.message}")
            }
        }
    }

    private fun cleanupFailedLaunch(
        humanIdentity: PlayerIdentity,
        lobby: TournamentLobby?,
        aiIdentity: PlayerIdentity?,
    ) {
        val lobbyId = lobby?.lobbyId
        if (lobbyId != null) {
            val tournament = lobbyRepository.findTournamentById(lobbyId)
            val gameIds = mutableSetOf<String>()
            tournament?.getAllInProgressMatches()?.mapNotNullTo(gameIds) { it.gameSessionId }
            gameRepository.findAll()
                .filter { gameRepository.getLobbyForGame(it.sessionId) == lobbyId }
                .mapTo(gameIds) { it.sessionId }

            gameIds.forEach { gameId ->
                aiGameManager.cleanupGame(gameId)
                gameRepository.removeLobbyLink(gameId)
                gameRepository.remove(gameId)
            }

            lobby.players.values.forEach { player ->
                player.identity.currentLobbyId = null
                player.identity.currentGameSessionId = null
            }
            lobbyRepository.removeTournament(lobbyId)
            lobbyRepository.removeLobby(lobbyId)
        }

        humanIdentity.currentLobbyId = null
        humanIdentity.currentGameSessionId = null
        humanIdentity.webSocketSession?.let { ws ->
            sessionRegistry.getPlayerSession(ws.id)?.currentGameSessionId = null
        }
        aiIdentity?.let { ai ->
            runCatching { aiGameManager.disposeAiIdentity(ai) }
                .onFailure { logger.warn("Failed to dispose rolled-back AI identity ${ai.playerId.value}", it) }
        }
    }

    private companion object {
        const val AKIRI_COMMANDER = "Akiri, Fearless Voyager"
        const val CHEVILL_COMMANDER = "Chevill, Bane of Monsters"
    }
}
