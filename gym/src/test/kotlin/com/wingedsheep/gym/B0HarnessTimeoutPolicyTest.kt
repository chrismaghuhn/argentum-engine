package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class B0HarnessTimeoutPolicyTest : FunSpec({
    test("SMOKE_64 uses a bounded stage timeout above the measured corpus runtime") {
        B0HarnessTimeoutPolicy.timeoutForStage("SMOKE_64") shouldBe 3.hours
    }

    test("ordinary Gym tests retain the default bounded timeout") {
        B0HarnessTimeoutPolicy.timeoutForStage("CI_SMOKE") shouldBe 10.minutes
    }

    test("Gym ProjectConfig applies the SMOKE_64 stage timeout") {
        val previousStage = System.getProperty("b0.stage")
        try {
            System.setProperty("b0.stage", "SMOKE_64")
            io.kotest.provided.ProjectConfig().timeout shouldBe 3.hours
        } finally {
            if (previousStage == null) System.clearProperty("b0.stage")
            else System.setProperty("b0.stage", previousStage)
        }
    }
})
