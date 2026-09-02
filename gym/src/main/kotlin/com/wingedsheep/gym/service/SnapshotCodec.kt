package com.wingedsheep.gym.service

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.EpisodeDiagnostics
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Opaque handle pointing at a saved game state.
 *
 * For now only the in-process variant is implemented; the sealed design
 * leaves room for a cross-process byte-blob variant once we need it for
 * distributed MCTS.
 */
@Serializable
sealed interface SnapshotHandle {
    /** An in-process slot managed by [SnapshotCodec]. */
    @Serializable
    data class Slot(val slotId: Long) : SnapshotHandle
}

/**
 * Stores [GameState] snapshots and their player-ID roster in-process. Since
 * `GameState` is fully immutable, saving is free — we just hold a reference
 * — and restoring is also free: the restored env's state field is set back
 * to the referenced object, no deep copy required.
 *
 * Slots are keyed by a monotonically-increasing `Long`. `dispose` is
 * optional but recommended for long-lived training sessions so the JVM
 * can collect old snapshots.
 */
class SnapshotCodec {
    private val slots = ConcurrentHashMap<Long, Entry>()
    private val nextId = AtomicLong(1)

    data class Entry(
        val state: GameState,
        val playerIds: List<EntityId>,
        val stepCount: Int,
        val maxSteps: Int?,
        /** In-process episode evidence; not part of the serialized slot handle. */
        val diagnostics: EpisodeDiagnostics = EpisodeDiagnostics.EMPTY,
        /** Environment-local state generation used by the observation exactly-once cursor. */
        val projectionGeneration: Long = 0L,
        /** Explicit semantic/integrity failure closure, when the source episode has failed. */
        val failureClosure: EpisodeClosureV1.Failed? = null,
    )

    fun save(
        state: GameState,
        playerIds: List<EntityId>,
        stepCount: Int,
        maxSteps: Int?,
        diagnostics: EpisodeDiagnostics = EpisodeDiagnostics.EMPTY,
        projectionGeneration: Long = 0L,
        failureClosure: EpisodeClosureV1.Failed? = null,
    ): SnapshotHandle.Slot {
        val id = nextId.getAndIncrement()
        slots[id] = Entry(
            state,
            playerIds,
            stepCount,
            maxSteps,
            diagnostics,
            projectionGeneration,
            failureClosure,
        )
        return SnapshotHandle.Slot(id)
    }

    fun load(handle: SnapshotHandle): Entry = when (handle) {
        is SnapshotHandle.Slot -> slots[handle.slotId]
            ?: throw NoSuchElementException("Snapshot slot ${handle.slotId} not found")
    }

    fun dispose(handle: SnapshotHandle) {
        if (handle is SnapshotHandle.Slot) slots.remove(handle.slotId)
    }

    fun size(): Int = slots.size
}
