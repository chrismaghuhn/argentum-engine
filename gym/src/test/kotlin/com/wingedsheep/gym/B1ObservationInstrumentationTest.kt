package com.wingedsheep.gym

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM9
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    test("step-cost attribution restores exact original class bytes") {
        val paths = B1ObservationBytecodeInstrumentation.attributionClassOutputPathsForTest()
        val originals = paths.map(Files::readAllBytes)
        val handle = B1ObservationBytecodeInstrumentation.installStepCostAttribution()
        try {
            check(paths.zip(originals).any { (path, original) ->
                !Files.readAllBytes(path).contentEquals(original)
            }) { "attribution installation did not patch any class output" }
        } finally {
            handle.close()
        }
        assertExactBytes(paths, originals)
    }

    test("deep legal-action instrumentation restores exact original class bytes") {
        val paths = B1ObservationBytecodeInstrumentation.legalActionDomainDeepClassOutputPathsForTest()
        val originals = paths.map(Files::readAllBytes)
        val handle = B1ObservationBytecodeInstrumentation.installLegalActionDomainDeep(includeRulesEngineJar = false)
        try {
            check(paths.zip(originals).any { (path, original) ->
                !Files.readAllBytes(path).contentEquals(original)
            }) { "deep legal-action installation did not patch any class output" }
        } finally {
            handle.close()
        }
        assertExactBytes(paths, originals)
    }

    test("deep legal-action instrumentation restores the rules-engine jar") {
        val tempDirectory = Files.createTempDirectory("b1-legal-domain-jar")
        val jar = tempDirectory.resolve("rules-engine.jar")
        Files.copy(
            B1ObservationBytecodeInstrumentation.rulesEngineJarPathForTest(),
            jar,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val original = Files.readAllBytes(jar)
        try {
            val handle = B1ObservationBytecodeInstrumentation.installRulesEngineJarTargetsForTest(jar)
            try {
                check(!Files.readAllBytes(jar).contentEquals(original)) {
                    "deep legal-action installation did not patch the rules-engine jar"
                }
            } finally {
                handle.close()
            }
            check(Files.readAllBytes(jar).contentEquals(original)) {
                "deep legal-action installation did not restore the rules-engine jar"
            }
        } finally {
            Files.deleteIfExists(jar)
            Files.deleteIfExists(tempDirectory)
        }
    }

    test("deep legal-action instrumentation contains the requested method probes") {
        val paths = B1ObservationBytecodeInstrumentation.legalActionDomainDeepClassOutputPathsForTest()
        val handle = B1ObservationBytecodeInstrumentation.installLegalActionDomainDeep(includeRulesEngineJar = false)
        try {
            val environmentPath = paths.first { it.toString().endsWith("GameEnvironment.class") }
            val abilityPath = paths.first { it.toString().endsWith("ActivatedAbilityEnumerator.class") }
            check(containsProbeInvocation(Files.readAllBytes(environmentPath), "beginLegalActions")) {
                "GameEnvironment.legalActions was not instrumented"
            }
            check(containsProbeInvocation(Files.readAllBytes(environmentPath), "endListPhase")) {
                "GameEnvironment coordinator call was not instrumented"
            }
            check(containsProbeInvocation(Files.readAllBytes(abilityPath), "startPhase")) {
                "ActivatedAbilityEnumerator was not instrumented"
            }
        } finally {
            handle.close()
        }
    }

    test("deep legal-action probe records call purpose and state repeat classes") {
        val session = B1LegalActionDomainProbe.start()
        val segment = B1LegalActionDomainProbe.beginSegment("synthetic")
        check(B1LegalActionDomainProbe.beginDecision("ACTION"))
        val actions = listOf(Any(), Any())
        B1LegalActionDomainProbe.pushPurpose("OLD_STATE_SELECTED_ACTION_REVALIDATION")
        B1LegalActionDomainProbe.beginLegalActions(null)
        B1LegalActionDomainProbe.endLegalActions(actions)
        B1LegalActionDomainProbe.popPurpose("OLD_STATE_SELECTED_ACTION_REVALIDATION")
        B1LegalActionDomainProbe.beginLegalActions(null)
        B1LegalActionDomainProbe.endLegalActions(actions)
        B1LegalActionDomainProbe.recordObservationCandidateCount(2)
        B1LegalActionDomainProbe.endDecision()
        segment?.close()
        val snapshot = B1LegalActionDomainProbe.stop(session)
        val measured = snapshot.segments.single()

        measured.decisions shouldBe 1L
        measured.actionChoices shouldBe 1L
        measured.legalActionCallsTotal shouldBe 2L
        measured.sameStateLegalActionRepeatCount shouldBe 1L
        measured.stateChangedLegalActionRepeatCount shouldBe 0L
        measured.sameStateSemanticallyEqualResults shouldBe 1L
        measured.sameStateSemanticallyDifferentResults shouldBe 0L
        measured.legalActionCallsByPurpose["OLD_STATE_SELECTED_ACTION_REVALIDATION"] shouldBe 1L
        measured.legalActionCallsByPurpose["OTHER"] shouldBe 1L
    }
})

private fun containsProbeInvocation(bytes: ByteArray, methodName: String): Boolean {
    var found = false
    ClassReader(bytes).accept(object : ClassVisitor(ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(ASM9, delegate) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    if (owner == "com/wingedsheep/gym/B1LegalActionDomainProbe" && name == methodName) {
                        found = true
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                }
            }
        }
    }, 0)
    return found
}

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
