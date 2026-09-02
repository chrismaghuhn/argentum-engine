package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Versioned, public-safe closure of one Gym game episode.
 *
 * This is lifecycle metadata, not Rules state and not a reward contract. A null value means that
 * the current episode is still open. Failure has precedence over terminal/truncated projections
 * once a semantic or integrity boundary has failed.
 */
@Serializable
sealed interface EpisodeClosureV1 {
    val kind: Kind
    val stepCount: Int

    @Serializable
    enum class Kind {
        GAME_TERMINAL,
        INTERRUPTED,
        FAILED,
    }

    @Serializable
    @SerialName("GAME_TERMINAL")
    data class GameTerminal(
        override val stepCount: Int,
        val winnerId: EntityId?,
        /** Null means the Rules state was authoritative but supplied no event reason. */
        val reason: GameEndReason? = null,
    ) : EpisodeClosureV1 {
        init {
            require(stepCount >= 0) { "stepCount must not be negative" }
        }

        override val kind: Kind get() = Kind.GAME_TERMINAL
    }

    @Serializable
    @SerialName("INTERRUPTED")
    data class Interrupted(
        override val stepCount: Int,
        val reason: EpisodeInterruptionReason,
    ) : EpisodeClosureV1 {
        init {
            require(stepCount >= 0) { "stepCount must not be negative" }
        }

        override val kind: Kind get() = Kind.INTERRUPTED
    }

    @Serializable
    @SerialName("FAILED")
    data class Failed(
        override val stepCount: Int,
        val reason: EpisodeFailureReason,
    ) : EpisodeClosureV1 {
        init {
            require(stepCount >= 0) { "stepCount must not be negative" }
        }

        override val kind: Kind get() = Kind.FAILED
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Explicit controlled conditions that may close a valid nonterminal prefix. */
@Serializable
enum class EpisodeInterruptionReason {
    HORIZON_REACHED,
    CALLER_CANCELLED,
}

/** Typed, bounded failure categories; no exception text or internal state is carried. */
@Serializable
enum class EpisodeFailureReason {
    UNSUPPORTED_DIAGNOSTIC,
    PUBLIC_CHOICE_REJECTED,
    ENGINE_EXCEPTION,
    OBSERVATION_FAILURE,
}
