package com.wingedsheep.rundiagnostics

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated heartbeat scheduler. Its task performs only a scalar tick and an optional non-blocking
 * callback (normally a coalescing publish request); it never derives heartbeat from useful progress.
 */
public class HeartbeatScheduler(
    private val recorder: DiagnosticsRecorder,
    interval: Duration,
    private val onHeartbeat: () -> Unit = {},
    private val onCallbackFailure: (StatusPublicationFailureCode) -> Unit = {},
    executor: ScheduledExecutorService? = null,
) : AutoCloseable {
    private val intervalNanos: Long = interval.toNanos()
    private val executor: ScheduledExecutorService = executor ?: newExecutor()
    private val ownsExecutor: Boolean = executor == null
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var scheduledFuture: ScheduledFuture<*>? = null

    init {
        require(intervalNanos > 0) { "interval must be positive" }
    }

    public val isClosed: Boolean
        get() = closed.get()

    public fun start() {
        synchronized(lifecycleLock) {
            if (closed.get() || !started.compareAndSet(false, true)) return
            scheduledFuture = try {
                executor.scheduleAtFixedRate(
                    ::runTick,
                    0,
                    intervalNanos,
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: Exception) {
                started.set(false)
                reportCallbackFailure(StatusPublicationFailureCode.HEARTBEAT_SCHEDULER_REJECTED)
                null
            }
        }
    }

    /** Runs one tick synchronously for deterministic tests or explicit lifecycle probes. */
    public fun tickOnce() {
        if (!closed.get()) runTick()
    }

    override public fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            scheduledFuture?.cancel(false)
            scheduledFuture = null
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

    private fun runTick() {
        if (closed.get()) return
        try {
            recorder.heartbeatTick()
            try {
                onHeartbeat()
            } catch (_: Exception) {
                reportCallbackFailure(StatusPublicationFailureCode.HEARTBEAT_CALLBACK_FAILED)
            }
        } catch (_: Exception) {
            reportCallbackFailure(StatusPublicationFailureCode.HEARTBEAT_CALLBACK_FAILED)
        }
    }

    private fun reportCallbackFailure(code: StatusPublicationFailureCode) {
        try {
            onCallbackFailure(code)
        } catch (_: Exception) {
            // Diagnostics failures must not take down the scheduler or its workload.
        }
    }

    private companion object {
        fun newExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "run-diagnostics-heartbeat").apply { isDaemon = true }
            }
    }
}
