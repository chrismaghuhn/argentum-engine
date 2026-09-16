package com.wingedsheep.gameserver.persistence.dto

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.gameserver.policy.ControllerAuthorityV1
import com.wingedsheep.gameserver.policy.PolicySeatStateV1
import kotlinx.serialization.Serializable

/**
 * Persistent representation of a GameSession for Redis storage.
 * Excludes transient WebSocket references and reconstructable objects.
 *
 * Deliberately *not* including the compact-replay recording, which it used to carry. Redis holds
 * live sessions with a TTL; replays are a durable artefact with a different lifetime and a different
 * consumer, and keeping a second copy here meant two stores that could disagree about what a game
 * was. The recording now lives only in [com.wingedsheep.gameserver.replay.ReplayStore], flushed
 * periodically while the game is in progress and resumed from there on restart — see
 * [com.wingedsheep.gameserver.replay.ReplayCheckpointFlusher].
 */
@Serializable
data class PersistentGameSession(
    val sessionId: String,
    val gameState: GameState?,
    val deckLists: Map<String, List<String>>,  // playerId.value -> card names
    val lastProcessedMessageId: Map<String, String>,  // playerId.value -> messageId
    val gameLogs: Map<String, List<ClientEvent>>,  // playerId.value -> events
    val playerInfos: List<PersistentPlayerInfo>,
    val lobbyId: String?,
    val sideboards: Map<String, List<String>> = emptyMap(),  // playerId.value -> sideboard card names
)

/**
 * Persistent player info - contains only the data needed to restore a player's session.
 */
@Serializable
data class PersistentPlayerInfo(
    val playerId: String,
    val playerName: String,
    val token: String,
    val isAi: Boolean = false,
    val aiModelOverride: String? = null,
    /** True when the recovered AI identity must use the built-in Engine AI. */
    val forceEngine: Boolean = false,
    /** Explicit server-owned controller authority; null only for pre-C1_07C persisted sessions. */
    val controllerAuthority: ControllerAuthorityV1? = null,
    /** Mutable PolicyTieRng state; present only for ML_POLICY authorities. */
    val policySeatState: PolicySeatStateV1? = null,
)
