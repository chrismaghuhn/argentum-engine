package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import java.security.MessageDigest

/**
 * A short, cheap digest of an observable game position.
 *
 * A recorded replay is an input stream that only reproduces the original game while the engine that
 * folds it stays behaviourally identical. When it doesn't — a card's implementation changed, a rules
 * fix landed — the re-simulation silently drifts: the actions keep applying, but the board they
 * produce is no longer the board that was played. Truncation (an action that outright fails) is the
 * loud half of that failure; drift is the quiet half, and the quiet half is worse because the viewer
 * happily renders a game that never happened.
 *
 * So the recorder stamps a fingerprint every [ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS]
 * actions while the game is live, and reconstruction re-computes it at the same points. A mismatch
 * pins divergence to a window of actions and downgrades the replay's
 * [ReplayFidelity] instead of pretending nothing happened.
 *
 * Version 1 and 2 use the historical short digest below. Version 3 uses the historical complete
 * transition-semantic canonicalizer. Action-level target-domain metadata is an additive Gym
 * observation contract and is deliberately excluded from this replay fingerprint, so CompactReplay
 * v3 checkpoints and payload semantics remain unchanged.
 */
object ReplayFingerprint {

    /** Current recorder/replay fingerprint: the v3 complete transition-semantic digest. */
    fun of(state: GameState): String = v3(state)

    /** Select the fingerprint semantics recorded by a specific CompactReplay version. */
    fun of(state: GameState, replayVersion: Int): String = when (replayVersion) {
        1, 2 -> legacy(state)
        3 -> v3(state)
        else -> throw UnsupportedReplayVersionException(replayVersion, CompactReplay.CURRENT_VERSION)
    }

    /** The historical v1/v2 16-hex digest. Never change its input fields or encoding. */
    internal fun legacy(state: GameState): String {
        val sb = StringBuilder(256)
        sb.append(state.turnNumber).append('|')
            .append(state.phase).append('|')
            .append(state.step).append('|')
            .append(state.activePlayerId?.value ?: "-").append('|')
            .append(state.priorityPlayerId?.value ?: "-").append('|')
            .append(state.nextEntityId).append('|')
            .append(state.timestamp).append('|')
            .append(state.stack.size).append('|')
            .append(state.gameOver).append('|')
            .append(state.winnerId?.value ?: "-").append('|')
            .append(state.pendingDecision?.let { it::class.simpleName } ?: "-").append('|')

        // Zone sizes, in a stable order (map iteration order is not guaranteed across runs).
        state.zones.entries
            .map { (key, ids) -> "${key.ownerId.value}:${key.zoneType}=${ids.size}" }
            .sorted()
            .forEach { sb.append(it).append(',') }
        sb.append('|')

        // Life totals in turn order — the single most player-visible number a divergence moves.
        for (playerId in state.turnOrder) {
            val life = state.getEntity(playerId)?.get<LifeTotalComponent>()?.life ?: 0
            sb.append(playerId.value).append('=').append(life).append(',')
        }

        return digest(sb.toString())
    }

    /** The historical v3 complete canonical state digest. */
    internal fun v3(state: GameState): String {
        val canonical = TransitionSemanticGameStateCanonicalizer.canonicalJson(state)
        return fullDigest("argentum-engine/replay-fingerprint/v3\n$canonical")
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (i in 0 until 8) append("%02x".format(bytes[i]))
        }
    }

    private fun fullDigest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(64) {
            bytes.forEach { append("%02x".format(it)) }
        }
    }
}
