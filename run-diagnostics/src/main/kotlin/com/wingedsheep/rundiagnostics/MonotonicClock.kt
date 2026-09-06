package com.wingedsheep.rundiagnostics

/**
 * A source of elapsed-time ticks. Implementations must be monotonic for intervals observed by a
 * recorder; the system implementation delegates directly to [System.nanoTime].
 */
public fun interface MonotonicClock {
    public fun nowNanos(): Long

    public companion object {
        public fun system(): MonotonicClock = SystemMonotonicClock
    }
}
public object SystemMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}

/** Converts one JVM-relative [MonotonicClock] origin into elapsed nanoseconds. */
public class ElapsedMonotonicClock(
    private val source: MonotonicClock,
) {
    private val originNanos: Long = source.nowNanos()

    public fun nowElapsedNanos(): Long = elapsedFrom(originNanos)

    public fun elapsedSince(startElapsedNanos: Long): Long {
        require(startElapsedNanos >= 0) { "startElapsedNanos must be non-negative" }
        return (nowElapsedNanos() - startElapsedNanos).coerceAtLeast(0)
    }

    private fun elapsedFrom(origin: Long): Long =
        (source.nowNanos() - origin).coerceAtLeast(0)
}
