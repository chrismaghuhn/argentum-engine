package io.kotest.provided

import com.wingedsheep.gym.B0HarnessTimeoutPolicy
import io.kotest.core.config.AbstractProjectConfig
import kotlin.time.Duration

/** Gym test configuration with a bounded, stage-aware B0 acceptance timeout. */
class ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration = B0HarnessTimeoutPolicy.timeoutForStage(
        System.getProperty("b0.stage"),
    )
}
