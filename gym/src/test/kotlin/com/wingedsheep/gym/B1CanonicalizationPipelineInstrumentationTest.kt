package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class B1CanonicalizationPipelineInstrumentationTest : FunSpec({
    test("pipeline instrumentation restores every patched class byte-for-byte") {
        val paths = B1ObservationBytecodeInstrumentation.canonicalizationPipelineClassOutputPathsForTest()
        val originals = paths.associateWith(Files::readAllBytes)

        B1ObservationBytecodeInstrumentation.installCanonicalizationPipeline().use {
            paths.forEach { path ->
                Files.readAllBytes(path).contentEquals(originals.getValue(path)) shouldBe false
            }
        }

        paths.forEach { path ->
            Files.readAllBytes(path) shouldBe originals.getValue(path)
        }
    }

    test("pipeline probe preserves a nested root accounting frame") {
        val session = B1CanonicalizationPipelineProbe.start()
        try {
            val segment = session.beginSegment("focused")
            try {
                B1CanonicalizationPipelineProbe.beginTransition()
                B1CanonicalizationPipelineProbe.enterMethod("STATE_DIGEST_COMPUTE", Any())
                B1CanonicalizationPipelineProbe.enterMethod("DIGEST_BODY", "semantic")
                B1CanonicalizationPipelineProbe.startOperation("UTF8_ENCODING")
                B1CanonicalizationPipelineProbe.endOperation("UTF8_ENCODING")
                B1CanonicalizationPipelineProbe.exitMethod("DIGEST_BODY")
                B1CanonicalizationPipelineProbe.exitMethod("STATE_DIGEST_COMPUTE")
                B1CanonicalizationPipelineProbe.endTransition()
            } finally {
                segment.close()
            }
            session.snapshot().integrity shouldBe "PASS"
        } finally {
            B1CanonicalizationPipelineProbe.stop(session)
        }
    }

})
