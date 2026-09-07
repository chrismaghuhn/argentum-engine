package com.wingedsheep.rundiagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.time.Duration
import kotlin.system.measureNanoTime

class DiagnosticsOverheadTest : FunSpec({
    test("measures disabled, scalar-only, and sidecar publication arms separately") {
        val iterations = 50_000
        val disabled: DiagnosticsRecorder? = null
        val disabledNanos = measureNanoTime {
            repeat(iterations) {
                if (disabled != null) {
                    disabled.recordUsefulProgress(engineProgressDelta = 1)
                }
            }
        }

        val scalarRecorder = newRecorder()
        val scalarNanos = measureNanoTime {
            repeat(iterations) {
                scalarRecorder.recordUsefulProgress(engineProgressDelta = 1)
            }
        }

        val directory = Files.createTempDirectory("run-diagnostics-overhead-")
        val sidecarPublisher = CoalescingStatusPublisher(
            target = directory.resolve("run-status.json"),
            statusSupplier = { scalarRecorder.snapshot()!! },
        )
        val sidecarNanos = measureNanoTime {
            repeat(3) {
                sidecarPublisher.publishNow()
            }
        }

        try {
            val last = sidecarPublisher.lastResult
            last.shouldBeInstanceOf<StatusPublicationResult.Published>()
            println(
                "DIAGNOSTICS_OVERHEAD_NANOS=" +
                    "disabled:$disabledNanos," +
                    "scalar:$scalarNanos," +
                    "sidecar:$sidecarNanos",
            )
            sidecarPublisher.awaitIdle(Duration.ofSeconds(1)) shouldBe true
        } finally {
            sidecarPublisher.close()
            scalarRecorder.close()
            directory.toFile().deleteRecursively()
        }
    }
})
