package com.wingedsheep.gym

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class B1ObservationInstrumentationTest : FunSpec({
    test("counts distinct affected action identities separately from duplicate calls") {
        val first = Any()
        val second = Any()
        val third = Any()
        val session = B1ObservationProbe.start()
        B1ObservationProbe.beginBuild(3)
        B1ObservationProbe.recordAction("family", first)
        B1ObservationProbe.recordAction("family", first)
        B1ObservationProbe.recordAction("family", second)
        B1ObservationProbe.recordAction("family", second)
        B1ObservationProbe.recordAction("family", second)
        B1ObservationProbe.recordAction("family", third)
        B1ObservationProbe.endBuild()
        val snapshot = B1ObservationProbe.stop(session)

        snapshot.sameActionDuplicateCalls["family"] shouldBe 3L
        snapshot.actionsWithSameActionDuplicates["family"] shouldBe 2L
        snapshot.maxCallsForOneAction["family"] shouldBe 3L
    }

    test("partial installation failure restores every already-written output") {
        withTemporaryOutputs { paths, originals ->
            shouldThrow<IllegalStateException> {
                B1ObservationBytecodeInstrumentation.installForTest(paths, failOnWriteIndex = 1)
            }
            assertExactBytes(paths, originals)
        }
    }

    test("evidence finalization failure still restores instrumentation") {
        withTemporaryOutputs { paths, originals ->
            val handle = B1ObservationBytecodeInstrumentation.installForTest(paths)
            val session = B1ObservationProbe.start()
            shouldThrow<IllegalStateException> {
                finishB1Characterization(session, handle) {
                    error("synthetic evidence serialization failure")
                }
            }
            assertExactBytes(paths, originals)
        }
    }

    test("probe-stop failure still restores instrumentation") {
        withTemporaryOutputs { paths, originals ->
            val handle = B1ObservationBytecodeInstrumentation.installForTest(paths)
            val inactiveSession = B1ObservationProbe.Session()
            shouldThrow<IllegalStateException> {
                finishB1Characterization(inactiveSession, handle) { }
            }
            assertExactBytes(paths, originals)
        }
    }

    test("successful characterization restores exact original class bytes") {
        val paths = B1ObservationBytecodeInstrumentation.classOutputPathsForTest()
        val originals = paths.map(Files::readAllBytes)
        val handle = B1ObservationBytecodeInstrumentation.install()
        try {
            check(paths.zip(originals).any { (path, original) ->
                !Files.readAllBytes(path).contentEquals(original)
            }) { "test installation did not patch any class output" }
        } finally {
            handle.close()
        }
        assertExactBytes(paths, originals)
    }
})

private fun withTemporaryOutputs(block: (List<Path>, List<ByteArray>) -> Unit) {
    val directory = Files.createTempDirectory("b1-observation-instrumentation")
    val originals = listOf(
        byteArrayOf(0x01, 0x02, 0x03, 0x04),
        byteArrayOf(0x11, 0x12, 0x13, 0x14),
        byteArrayOf(0x21, 0x22, 0x23, 0x24),
    )
    val paths = originals.indices.map { directory.resolve("class-$it.bin") }
    paths.zip(originals).forEach { (path, bytes) -> Files.write(path, bytes) }
    try {
        block(paths, originals)
    } finally {
        paths.forEach(Files::deleteIfExists)
        Files.deleteIfExists(directory)
    }
}

private fun assertExactBytes(paths: List<Path>, originals: List<ByteArray>) {
    paths.zip(originals).forEach { (path, original) ->
        check(Files.readAllBytes(path).contentEquals(original)) {
            "restoration changed bytes for $path"
        }
    }
}
