package com.wingedsheep.gym

import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

/**
 * Test-only exclusive phase timer used by the B1 attribution characterization.
 *
 * The probe is active only when the attribution system property is enabled. Each measured
 * executeChoice call gets a thread-local frame stack, so nested instrumented methods charge their
 * child time and allocation to the child phase instead of double-counting it in the parent.
 */
internal object B1StepCostAttributionProbe {
    private val activeSession = AtomicReference<Session?>()
    private val allocationBean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }

    @Serializable
    internal data class PhaseSnapshot(
        val exclusiveWallNanos: Long,
        val exclusiveAllocatedBytes: Long?,
        val allocationSamples: Long,
    )

    @Serializable
    internal data class SegmentSnapshot(
        val label: String,
        val transitions: Long,
        val transitionWallNanos: Long,
        val phases: Map<String, PhaseSnapshot>,
    )

    @Serializable
    internal data class Snapshot(
        val schemaVersion: String,
        val allocationMeasurement: String,
        val integrity: String,
        val segments: List<SegmentSnapshot>,
    )

    internal class Session internal constructor() {
        private val currentSegment = AtomicReference<Segment?>()
        private val segments = CopyOnWriteArrayList<Segment>()
        private val threadState = ThreadLocal<TransitionState?>()
        private val integrityErrors = AtomicLong(0)

        internal fun beginSegment(label: String): SegmentHandle {
            val segment = Segment(label)
            segments += segment
            if (!currentSegment.compareAndSet(null, segment)) {
                markIntegrityError()
            }
            return SegmentHandle(this, segment)
        }

        internal fun endSegment(segment: Segment) {
            if (!currentSegment.compareAndSet(segment, null)) {
                markIntegrityError()
            }
        }

        internal fun beginTransition() {
            val segment = currentSegment.get() ?: return
            if (threadState.get() != null) {
                markIntegrityError()
                return
            }
            segment.transitions.increment()
            threadState.set(TransitionState(segment, System.nanoTime()))
        }

        internal fun endTransition() {
            val state = threadState.get() ?: return
            state.segment.transitionWallNanos.add(
                (System.nanoTime() - state.startNanos).coerceAtLeast(0L),
            )
            if (state.frames.isNotEmpty()) markIntegrityError()
            threadState.remove()
        }

        internal fun startPhase(family: String) {
            val state = threadState.get() ?: return
            state.frames.addLast(
                PhaseFrame(
                    family = family,
                    startNanos = System.nanoTime(),
                    startAllocatedBytes = currentThreadAllocatedBytes(),
                )
            )
        }

        internal fun endPhase(family: String) {
            val state = threadState.get() ?: return
            val frame = state.frames.removeLastOrNull()
            if (frame == null || frame.family != family) {
                markIntegrityError()
                return
            }

            val inclusiveWallNanos = (System.nanoTime() - frame.startNanos).coerceAtLeast(0L)
            val exclusiveWallNanos = (inclusiveWallNanos - frame.childWallNanos).coerceAtLeast(0L)
            val endAllocatedBytes = currentThreadAllocatedBytes()
            val inclusiveAllocatedBytes = if (
                frame.startAllocatedBytes != null && endAllocatedBytes != null
            ) {
                (endAllocatedBytes - frame.startAllocatedBytes).coerceAtLeast(0L)
            } else {
                null
            }
            val exclusiveAllocatedBytes = inclusiveAllocatedBytes?.let {
                (it - frame.childAllocatedBytes).coerceAtLeast(0L)
            }
            state.segment.phase(family).record(
                exclusiveWallNanos = exclusiveWallNanos,
                exclusiveAllocatedBytes = exclusiveAllocatedBytes,
            )

            val parent = state.frames.lastOrNull()
            if (parent != null) {
                parent.childWallNanos += inclusiveWallNanos
                if (inclusiveAllocatedBytes != null) {
                    parent.childAllocatedBytes += inclusiveAllocatedBytes
                }
            }
        }

        internal fun snapshot(): Snapshot {
            if (currentSegment.get() != null) markIntegrityError()
            val integrity = if (integrityErrors.get() == 0L) {
                "PASS"
            } else {
                "FAIL errors=${integrityErrors.get()}"
            }
            return Snapshot(
                schemaVersion = "argentum-b1-step-cost-attribution-v1",
                allocationMeasurement = if (allocationBean == null) {
                    "NOT_AVAILABLE"
                } else {
                    "ThreadMXBean.getThreadAllocatedBytes"
                },
                integrity = integrity,
                segments = segments.map(Segment::snapshot),
            )
        }

        private fun markIntegrityError() {
            integrityErrors.incrementAndGet()
        }
    }

    internal class SegmentHandle internal constructor(
        private val session: Session,
        private val segment: Segment,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) session.endSegment(segment)
        }
    }

    internal class Segment(
        val label: String,
    ) {
        val transitions = LongAdder()
        val transitionWallNanos = LongAdder()
        private val phases = ConcurrentHashMap<String, PhaseTotals>()

        fun phase(family: String): PhaseTotals = phases.computeIfAbsent(family) { PhaseTotals() }

        fun snapshot(): SegmentSnapshot = SegmentSnapshot(
            label = label,
            transitions = transitions.sum(),
            transitionWallNanos = transitionWallNanos.sum(),
            phases = phases.keys.sorted().associateWith { family -> phases.getValue(family).snapshot() },
        )
    }

    internal class PhaseTotals {
        private val wallNanos = LongAdder()
        private val allocatedBytes = LongAdder()
        private val allocationSamples = LongAdder()

        fun record(exclusiveWallNanos: Long, exclusiveAllocatedBytes: Long?) {
            wallNanos.add(exclusiveWallNanos)
            if (exclusiveAllocatedBytes != null) {
                allocatedBytes.add(exclusiveAllocatedBytes)
                allocationSamples.increment()
            }
        }

        fun snapshot(): PhaseSnapshot {
            val samples = allocationSamples.sum()
            return PhaseSnapshot(
                exclusiveWallNanos = wallNanos.sum(),
                exclusiveAllocatedBytes = allocatedBytes.sum().takeIf { samples > 0L },
                allocationSamples = samples,
            )
        }
    }

    private class TransitionState(
        val segment: Segment,
        val startNanos: Long,
    ) {
        val frames = ArrayDeque<PhaseFrame>()
    }

    private class PhaseFrame(
        val family: String,
        val startNanos: Long,
        val startAllocatedBytes: Long?,
    ) {
        var childWallNanos: Long = 0L
        var childAllocatedBytes: Long = 0L
    }

    @JvmStatic
    @JvmName("start")
    internal fun start(): Session {
        val session = Session()
        check(activeSession.compareAndSet(null, session)) {
            "B1 step-cost attribution probe session is already active"
        }
        return session
    }

    @JvmStatic
    @JvmName("stop")
    internal fun stop(session: Session): Snapshot {
        check(activeSession.compareAndSet(session, null)) {
            "B1 step-cost attribution probe session is not active"
        }
        return session.snapshot()
    }

    @JvmStatic
    @JvmName("beginTransition")
    internal fun beginTransition() {
        activeSession.get()?.beginTransition()
    }

    @JvmStatic
    @JvmName("endTransition")
    internal fun endTransition() {
        activeSession.get()?.endTransition()
    }

    @JvmStatic
    @JvmName("startPhase")
    internal fun startPhase(family: String) {
        activeSession.get()?.startPhase(family)
    }

    @JvmStatic
    @JvmName("endPhase")
    internal fun endPhase(family: String) {
        activeSession.get()?.endPhase(family)
    }

    internal fun beginSegment(label: String): SegmentHandle? =
        activeSession.get()?.beginSegment(label)

    internal fun isActive(): Boolean = activeSession.get() != null

    private fun currentThreadAllocatedBytes(): Long? = allocationBean
        ?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        ?.takeIf { it >= 0L }
}
