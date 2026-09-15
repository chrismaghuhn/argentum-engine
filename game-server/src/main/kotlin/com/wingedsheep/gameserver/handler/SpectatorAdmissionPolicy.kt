package com.wingedsheep.gameserver.handler

import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerIdentity
import org.springframework.stereotype.Component

/**
 * Server-owned admission rule for attaching a spectator to a live game.
 *
 * A session id is only a lookup key. It is not an authorization capability for
 * private games. Public games use the session's explicit public-spectate flag;
 * private tournament games use the existing game -> lobby link and the lobby's
 * authoritative participant/spectator membership.
 */
@Component
class SpectatorAdmissionPolicy(
    private val gameRepository: GameRepository,
    private val lobbyRepository: LobbyRepository,
) {
    fun canSpectate(identity: PlayerIdentity, gameSession: GameSession): Boolean {
        if (gameSession.isGameOver()) return false
        if (gameSession.publicSpectate) return true

        // A seated player already has an authoritative relationship with this
        // session, even when the originating lobby is no longer available.
        if (gameSession.getPlayers().any { it.playerId == identity.playerId }) return true

        val lobbyId = gameRepository.getLobbyForGame(gameSession.sessionId) ?: return false
        val lobby = lobbyRepository.findLobbyById(lobbyId) ?: return false

        // Tournament matches inherit the existing lobby-level public-live
        // semantics; those sessions predate the session-local flag.
        if (lobby.isPublic) return true

        return lobby.players.containsKey(identity.playerId) || lobby.isSpectator(identity.playerId)
    }
}
