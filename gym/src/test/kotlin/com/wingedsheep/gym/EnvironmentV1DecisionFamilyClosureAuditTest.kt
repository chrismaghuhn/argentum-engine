package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

/**
 * Reads an already-finalized A9 dataset through the strict A7 reader and checks the accepted A8
 * decision-family closure. This test never starts Gym gameplay or reconstructs a live state.
 */
class EnvironmentV1DecisionFamilyClosureAuditTest : FunSpec({
    test("finalized A9 dataset passes serialized decision-family closure audit")
        .config(timeout = 2.hours) {
        val datasetRoot = Path.of(
            requireNotNull(System.getProperty("a9.auditDatasetRoot")) {
                "-Da9.auditDatasetRoot must point at a finalized A9 dataset"
            },
        )
        val audit = A9DecisionFamilyClosureAudit.auditPublishedDataset(datasetRoot)

        println(audit.render())
        audit.pass shouldBe true
        audit.episodeCount shouldBe 64
        audit.decisionCount shouldBe 125471
    }
})
