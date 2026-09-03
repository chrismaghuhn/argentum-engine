package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/** Version of the transport-free public observation projection. */
const val PLAYER_OBSERVATION_V1_VERSION: Int = 1

/** Durable identity of the A1 observation-only projection. */
const val PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-player-observation@v1"

/**
 * Durable, transport-free projection of one perspective-safe [TrainingObservation].
 *
 * This type deliberately contains no legal-action list or structured decision domain. Those
 * are the separate CompleteLegalDomainV1 authority planned for A2. [from] is the only builder:
 * it accepts the already privacy-projected TrainingObservation and cannot inspect GameState.
 */
@Serializable
data class PlayerObservationV1(
    val projectionVersion: Int = PLAYER_OBSERVATION_V1_VERSION,
    val projectionSchemaIdentity: String = PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY,
    /** The existing wire-contract anchor carried by the source observation. */
    val wireSchemaHash: String,

    val perspectivePlayerId: EntityId,
    val agentToAct: EntityId?,
    val turnNumber: Int,
    val phase: Phase,
    val step: Step,
    val activePlayerId: EntityId?,
    val priorityPlayerId: EntityId?,
    val players: List<PlayerView>,
    val zones: List<ZoneView>,
    val stack: List<StackItemView>,
    val pendingDecision: PlayerObservationPendingDecisionV1?,
    val terminated: Boolean,
    val truncated: Boolean,
    val winnerId: EntityId?,

    /** Binds this projection to the source TrainingObservation.stateDigest. */
    val observationDigest: String,
) {
    init {
        require(projectionVersion == PLAYER_OBSERVATION_V1_VERSION) {
            "Unsupported player observation projection version: $projectionVersion"
        }
        require(projectionSchemaIdentity == PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY) {
            "Unsupported player observation projection identity: $projectionSchemaIdentity"
        }
    }

    /** Deterministic canonical bytes represented as UTF-8 JSON text. */
    fun canonicalJson(): String = ObservationCanonicalizer.playerObservationJson(this)

    /** SHA-256 of [canonicalJson], independent of live routing handles. */
    fun semanticDigest(): String = ObservationCanonicalizer.playerObservationDigest(this)

    companion object {
        /** Project only the already perspective-safe public observation. */
        fun from(observation: TrainingObservation): PlayerObservationV1 = PlayerObservationV1(
            wireSchemaHash = observation.schemaHash,
            perspectivePlayerId = observation.perspectivePlayerId,
            agentToAct = observation.agentToAct,
            turnNumber = observation.turnNumber,
            phase = observation.phase,
            step = observation.step,
            activePlayerId = observation.activePlayerId,
            priorityPlayerId = observation.priorityPlayerId,
            players = observation.players,
            zones = observation.zones,
            stack = observation.stack,
            pendingDecision = observation.pendingDecision?.let { pending ->
                PlayerObservationPendingDecisionV1(
                    kind = pending.kind,
                    playerId = pending.playerId,
                    sourceEntityId = pending.sourceEntityId,
                    triggeringEntityId = pending.triggeringEntityId,
                    requiresStructuredResponse = pending.requiresStructuredResponse,
                    shape = pending.shape,
                )
            },
            terminated = observation.terminated,
            truncated = observation.truncated,
            winnerId = observation.winnerId,
            observationDigest = observation.stateDigest,
        )
    }
}

/** Semantic pending-decision context retained by A1 without routing or presentation fields. */
@Serializable
data class PlayerObservationPendingDecisionV1(
    val kind: PendingDecisionKind,
    val playerId: EntityId,
    val sourceEntityId: EntityId?,
    val triggeringEntityId: EntityId?,
    val requiresStructuredResponse: Boolean,
    val shape: DecisionShape,
)
