package com.wingedsheep.gym.trainer.actor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ActorMeasurementV1Test : FunSpec({
    test("measurement summary uses deterministic nearest-rank percentiles") {
        val summary = ActorMeasurementSummaryV1.from(listOf(1L, 2L, 4L, 8L))

        summary.count shouldBe 4
        summary.total shouldBe 15L
        summary.min shouldBe 1L
        summary.p50 shouldBe 2L
        summary.p95 shouldBe 8L
        summary.max shouldBe 8L
    }

    test("measurement summary rejects empty, negative, and overflowing samples") {
        shouldThrow<IllegalArgumentException> { ActorMeasurementSummaryV1.from(emptyList()) }
        shouldThrow<IllegalArgumentException> { ActorMeasurementSummaryV1.from(listOf(-1L)) }
        shouldThrow<ArithmeticException> {
            ActorMeasurementSummaryV1.from(listOf(Long.MAX_VALUE, 1L))
        }
    }
})
