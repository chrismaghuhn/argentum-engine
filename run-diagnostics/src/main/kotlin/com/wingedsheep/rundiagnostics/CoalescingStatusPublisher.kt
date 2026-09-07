package com.wingedsheep.rundiagnostics

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes status snapshots away from the workload thread and coalesces bursts of requests. A
 * request made while a publication is active schedules at most one follow-up publication.
 */
public class CoalescingStatusPublisher(
    target: Path,
    private val statusSupplier: () -> RunStatusV1,
    private val maxSerializedBytes: Int = DiagnosticsSchema.DEFAULT_MAX_SERIALIZED_STATUS_BYTES,
    atomicFile: AtomicStatusFile? = null,
    executor: ExecutorService? = null,
) : AutoCloseable {
    private val atomicFile = atomicFile ?: AtomicStatusFile(target, maxSerializedBytes)
    private val executor = executor ?: newExecutor()
    private val ownsExecutor = executor == null
    private val closed = AtomicBoolean(false)
    private val queueLock = Any()
    private var pending = false
    private var drainScheduled = false
    private val activeTasks = AtomicInteger(0)
    private val lastResultRef = AtomicReference<StatusPublicationResult?>(null)
    private val idleLock = ReentrantLock()
    private val idleCondition = idleLock.newCondition()
    private val publicationLock = Any()
    private var successfulPublicationSequence: Long = 0
    private var pendingFailureCode: StatusPublicationFailureCode? = null

    init {
        require(maxSerializedBytes > 0) { "maxSerializedBytes must be positive" }
    }

    public val lastResult: StatusPublicationResult?
        get() = lastResultRef.get()

    /** Returns true only when this request creates a queued drain task. */
    public fun requestPublish(): Boolean {
        val shouldSchedule = synchronized(queueLock) {
            if (closed.get()) return false
            pending = true
            if (drainScheduled) {
                false
            } else {
                drainScheduled = true
                true
            }
        }
        if (!shouldSchedule) return false

        return try {
            executor.execute(::drain)
            true
        } catch (_: RejectedExecutionException) {
            synchronized(queueLock) {
                pending = false
                drainScheduled = false
            }
            rememberFailure(StatusPublicationFailureCode.PUBLISHER_QUEUE_REJECTED)
            false
        } catch (_: Exception) {
            synchronized(queueLock) {
                pending = false
                drainScheduled = false
            }
            rememberFailure(StatusPublicationFailureCode.PUBLISHER_QUEUE_REJECTED)
            false
        }
    }

    /** Performs one publication synchronously; it is still serialized with asynchronous drains. */
    public fun publishNow(): StatusPublicationResult {
        if (closed.get()) {
            val result = StatusPublicationResult.Failed(StatusPublicationFailureCode.PUBLISHER_CLOSED)
            lastResultRef.set(result)
            return result
        }

        val result = synchronized(publicationLock) {
            if (closed.get()) {
                StatusPublicationResult.Failed(StatusPublicationFailureCode.PUBLISHER_CLOSED)
            } else {
                val candidateSequence = successfulPublicationSequence + 1
                when (val publication = buildAndPublish(candidateSequence)) {
                    is StatusPublicationResult.Published -> {
                        successfulPublicationSequence = candidateSequence
                        pendingFailureCode = null
                        publication.copy(publicationSequence = candidateSequence)
                    }

                    is StatusPublicationResult.Failed -> {
                        rememberFailure(publication.code)
                        publication
                    }
                }
            }
        }
        lastResultRef.set(result)
        return result
    }

    /** Waits until no queued or active publication remains, using a monotonic wait deadline. */
    public fun awaitIdle(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        val timeoutNanos = timeout.toNanos()
        val deadline = System.nanoTime() + timeoutNanos
        idleLock.lock()
        try {
            while (hasQueuedWork() || activeTasks.get() != 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                if (!idleCondition.await(remaining, TimeUnit.NANOSECONDS)) return false
            }
            return true
        } finally {
            idleLock.unlock()
        }
    }

    override public fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(queueLock) {
            pending = false
        }
        if (!ownsExecutor) return

        executor.shutdown()
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                executor.awaitTermination(2, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun drain() {
        activeTasks.incrementAndGet()
        try {
            while (true) {
                val shouldPublish = synchronized(queueLock) {
                    if (!pending) {
                        drainScheduled = false
                        false
                    } else {
                        pending = false
                        true
                    }
                }
                if (!shouldPublish) break
                publishNow()
            }
        } finally {
            activeTasks.decrementAndGet()
            idleLock.lock()
            try {
                idleCondition.signalAll()
            } finally {
                idleLock.unlock()
            }
        }
    }

    private fun hasQueuedWork(): Boolean = synchronized(queueLock) {
        pending || drainScheduled
    }

    private fun buildAndPublish(candidateSequence: Long): StatusPublicationResult {
        val snapshot = try {
            statusSupplier()
        } catch (_: Exception) {
            return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_SNAPSHOT_FAILED)
        }
        val statusForPublication = try {
            snapshot.copy(
                statusPublication = snapshot.statusPublication.copy(
                    successfulPublicationSequence = candidateSequence,
                    lastFailureCode = pendingFailureCode,
                ),
            )
        } catch (_: Exception) {
            return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_SCHEMA_REJECTED)
        }
        val encoded = try {
            RunStatusCodec.encode(statusForPublication, maxSerializedBytes)
        } catch (exception: StatusSerializationException) {
            return StatusPublicationResult.Failed(exception.code)
        } catch (_: Exception) {
            return StatusPublicationResult.Failed(StatusPublicationFailureCode.STATUS_SCHEMA_REJECTED)
        }
        return atomicFile.publish(encoded)
    }

    private fun rememberFailure(code: StatusPublicationFailureCode) {
        synchronized(publicationLock) {
            pendingFailureCode = code
        }
    }

    private companion object {
        fun newExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "run-diagnostics-status-publisher").apply { isDaemon = true }
            }
    }
}
