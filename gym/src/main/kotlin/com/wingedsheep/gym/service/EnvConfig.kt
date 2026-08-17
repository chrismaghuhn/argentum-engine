package com.wingedsheep.gym.service

import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.core.Format
import kotlinx.serialization.Serializable

/**
 * Everything needed to spin up a new gym environment.
 *
 * Designed so a JSON payload can drive the HTTP layer in Phase 3 without
 * any translation.
 */
@Serializable
data class EnvConfig(
    val players: List<PlayerSpec>,

    /** Runtime rules format. Commander Gym is restricted to a two-player slice. */
    val format: Format = Format.Standard,

    /** Opening-hand size. Standard MTG = 7. */
    val startingHandSize: Int = 7,

    /**
     * Skip mulligan phase. Default `true` — training loops rarely care about
     * mulligans and the extra decision points slow rollouts down.
     */
    val skipMulligans: Boolean = true,

    /** MTGA-style hand smoothing (only useful for realistic play-feel runs). */
    val useHandSmoother: Boolean = false,

    /**
     * Which player goes first (0-indexed). `null` = random — which should be
     * the default for training diversity. Set explicitly only for reproducible
     * scenarios.
     */
    val startingPlayerIndex: Int? = null,

    /** Explicit seed for reproducible reset/fork experiments; null preserves live entropy. */
    val seed: Long? = null,

    /** Optional episode horizon. Reaching it truncates the episode without changing Magic state. */
    val maxSteps: Int? = null,

    /**
     * Which player's information-set the default [com.wingedsheep.gym.contract.TrainingObservation]
     * represents. Callers can still override per-request when observing.
     */
    val perspectivePlayerIndex: Int = 0,

) {
    init {
        require(players.size >= 2) { "Need at least 2 players" }
        if (format is Format.Commander) {
            require(players.size == 2) {
                "Commander Gym currently requires exactly 2 players, got ${players.size}"
            }
            require(players.all { !it.commanderCardName.isNullOrBlank() }) {
                "Commander Gym requires a non-blank commander identity for every player"
            }
        }
        require(maxSteps == null || maxSteps > 0) { "maxSteps must be positive when supplied" }
        require(perspectivePlayerIndex in players.indices) {
            "perspectivePlayerIndex=$perspectivePlayerIndex out of range for ${players.size} players"
        }
    }
}

/**
 * Everything needed to spin up a new **deckbuild** env (`POST /envs/deckbuild`).
 *
 * Opens [boosterCount] boosters from [setCode] into a sealed pool, then hands the agent
 * an enumerated build interface (add / remove / finalize) until it commits a [targetSize]-card
 * deck. The finished list is exposed on the terminal observation for the caller to feed into a
 * game env via [DeckSpec.Explicit].
 */
@Serializable
data class DeckbuildConfig(
    /** Set to open boosters from (e.g. "BLB"). Must be sealed-supported in the booster generator. */
    val setCode: String,

    /** Number of boosters to open (pack size follows the set's booster strategy). Tournament sealed = 6. */
    val boosterCount: Int = 6,

    /** Minimum legal deck size; `FINALIZE` unlocks once the build reaches it. */
    val targetSize: Int = 40
) {
    init {
        require(boosterCount > 0) { "boosterCount must be positive" }
        require(targetSize > 0) { "targetSize must be positive" }
    }
}

/** A single player's identity + deck. */
@Serializable
data class PlayerSpec(
    val name: String,
    val deck: DeckSpec,
    val startingLife: Int = 20,
    val playerId: EntityId? = null,
    /** Commander card identity, required by the rules engine for Commander format. */
    val commanderCardName: String? = null
)

/** A single environment's `step()` input — batched into [com.wingedsheep.gym.service.MultiEnvService.stepBatch]. */
@Serializable
data class StepRequest(
    val envId: EnvId,
    val actionId: Int
)

/** Result of deck validation. Surfaced by [DeckResolver.validate]. */
@Serializable
data class DeckValidation(
    val ok: Boolean,
    val errors: List<String> = emptyList(),
    val totalCards: Int = 0
)
