package com.wingedsheep.gym.service

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.CommittedPerspectiveEventSourceSnapshot
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

/** Provenance of a privileged History-C continuation stored in an in-process snapshot slot. */
enum class HistoryCContinuationAuthorityV1 {
    TRUSTED_COMMITTED,
    SPECULATIVE_FORK,
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
    private val historyCSourceSnapshots = ConcurrentHashMap<Long, CommittedPerspectiveEventSourceSnapshot>()
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
        /** Privileged versioned History-C continuation bytes, absent for ordinary snapshots. */
        val historyCContinuation: ByteArray? = null,
        /** Explicit provenance for [historyCContinuation]; null means no History-C continuation. */
        val historyCContinuationAuthority: HistoryCContinuationAuthorityV1? = null,
    )

    fun save(
        state: GameState,
        playerIds: List<EntityId>,
        stepCount: Int,
        maxSteps: Int?,
        diagnostics: EpisodeDiagnostics = EpisodeDiagnostics.EMPTY,
        projectionGeneration: Long = 0L,
        failureClosure: EpisodeClosureV1.Failed? = null,
        historyCContinuation: ByteArray? = null,
        historyCContinuationAuthority: HistoryCContinuationAuthorityV1? = null,
    ): SnapshotHandle.Slot {
        require(historyCContinuation != null || historyCContinuationAuthority == null) {
            "History-C snapshot authority requires continuation bytes"
        }
        val id = nextId.getAndIncrement()
        slots[id] = Entry(
            state,
            playerIds,
            stepCount,
            maxSteps,
            diagnostics,
            projectionGeneration,
            failureClosure,
            historyCContinuation?.copyOf(),
            historyCContinuationAuthority,
        )
        return SnapshotHandle.Slot(id)
    }

    fun load(handle: SnapshotHandle): Entry = when (handle) {
        is SnapshotHandle.Slot -> {
            val entry = slots[handle.slotId]
                ?: throw NoSuchElementException("Snapshot slot ${handle.slotId} not found")
            entry.copy(historyCContinuation = entry.historyCContinuation?.copyOf())
        }
    }

    internal fun attachHistoryCSource(
        handle: SnapshotHandle,
        source: CommittedPerspectiveEventSourceSnapshot,
    ) {
        val slot = handle as? SnapshotHandle.Slot
            ?: error("Unsupported snapshot handle")
        check(slots.containsKey(slot.slotId)) { "Snapshot slot ${slot.slotId} not found" }
        historyCSourceSnapshots[slot.slotId] = source.copy(
            transition = source.transition?.copy(events = source.transition.events.toList()),
        )
    }

    internal fun loadHistoryCSource(handle: SnapshotHandle): CommittedPerspectiveEventSourceSnapshot? {
        val slot = handle as? SnapshotHandle.Slot ?: return null
        val source = historyCSourceSnapshots[slot.slotId] ?: return null
        return source.copy(
            transition = source.transition?.copy(events = source.transition.events.toList()),
        )
    }

    fun dispose(handle: SnapshotHandle) {
        if (handle is SnapshotHandle.Slot) {
            slots.remove(handle.slotId)
            historyCSourceSnapshots.remove(handle.slotId)
        }
    }

    fun size(): Int = slots.size
}
