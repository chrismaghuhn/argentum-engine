package com.wingedsheep.rundiagnostics

import java.util.ArrayDeque

/**
 * Thread-safe fixed-capacity FIFO history. It stores only values supplied by the caller; the ring
 * does not serialize or inspect them. The recorder uses it exclusively for scalar operational events.
 */
public class BoundedHistoryRing<T>(
    capacity: Int,
) {
    private val capacity: Int = capacity.also {
        require(it > 0) { "capacity must be positive" }
    }
    private val values = ArrayDeque<T>(this.capacity)

    @Synchronized
    public fun add(value: T) {
        if (values.size == capacity) {
            values.removeFirst()
        }
        values.addLast(value)
    }

    @Synchronized
    public fun snapshot(): List<T> = values.toList()

    @Synchronized
    public fun size(): Int = values.size

    @Synchronized
    public fun clear() {
        values.clear()
    }
}
