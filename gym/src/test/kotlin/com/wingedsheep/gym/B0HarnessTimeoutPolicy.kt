package com.wingedsheep.gym

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Stage-specific soft timeout for the external B0 acceptance harness. */
internal object B0HarnessTimeoutPolicy {
    const val SMOKE_64_STAGE = "SMOKE_64"

    private val defaultTimeout = 10.minutes
    private val smoke64Timeout = 3.hours

    /**
     * Keep the ordinary test budget unchanged while giving the measured B0-64 corpus a bounded
     * stage budget. The B0 per-episode watchdog remains the quicker no-progress protection.
     */
    fun timeoutForStage(stage: String?, configuredTimeout: Duration = defaultTimeout): Duration =
        if (stage == SMOKE_64_STAGE) maxOf(configuredTimeout, smoke64Timeout) else configuredTimeout
}
