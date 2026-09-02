package com.wingedsheep.gym

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.Opcodes.ILOAD
import org.objectweb.asm.Opcodes.INVOKEINTERFACE
import org.objectweb.asm.Opcodes.INVOKESTATIC
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.DRETURN
import org.objectweb.asm.Opcodes.FRETURN
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.LRETURN
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test-only offline bytecode instrumentation. It patches build outputs only for one explicitly
 * enabled characterization JVM and restores every class file when the run ends. This keeps the
 * production source and production runtime free of profiler hooks.
 */
internal object B1ObservationBytecodeInstrumentation {
    private const val PROBE_OWNER = "com/wingedsheep/gym/B1ObservationProbe"

    internal fun install(): Handle = installTargets(
        listOf(
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
                ::observationBuilderMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/PaymentDomainBuilder.class"),
                ::paymentDomainBuilderMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
                ::gameEnvironmentMethod,
            ),
            Target(
                locate("rules-engine", "com/wingedsheep/engine/mechanics/mana/ManaSolver.class"),
                ::manaSolverMethod,
            ),
        ),
    )

    /** Test seam for proving the restoration transaction without changing production classes. */
    internal fun installForTest(
        paths: List<Path>,
        failOnWriteIndex: Int? = null,
    ): Handle {
        var writeIndex = 0
        return installTargets(
            paths.map { path -> Target(path) { null } },
            transformBytes = { bytes, _ ->
                bytes.mapIndexed { index, byte ->
                    (byte.toInt() xor (index + 1)).toByte()
                }.toByteArray()
            },
            writeBytes = { path, bytes ->
                val currentIndex = writeIndex++
                if (currentIndex == failOnWriteIndex) {
                    error("synthetic B1 installation failure at write $currentIndex")
                }
                Files.write(path, bytes)
            },
        )
    }

    internal fun classOutputPathsForTest(): List<Path> = listOf(
        locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
        locate("gym", "com/wingedsheep/gym/contract/PaymentDomainBuilder.class"),
        locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
        locate("rules-engine", "com/wingedsheep/engine/mechanics/mana/ManaSolver.class"),
    )

    private fun installTargets(
        targets: List<Target>,
        transformBytes: (ByteArray, (String) -> MethodPlan?) -> ByteArray = ::transform,
        writeBytes: (Path, ByteArray) -> Unit = { path, bytes -> Files.write(path, bytes) },
    ): Handle {
        val originals = mutableListOf<Pair<Path, ByteArray>>()
        try {
            targets.forEach { target ->
                val original = Files.readAllBytes(target.path)
                // Register before the write so a short/failed write is restored as well.
                originals += target.path to original
                val transformed = transformBytes(original, target.methodSelector)
                writeBytes(target.path, transformed)
            }
            return Handle(originals.toList())
        } catch (failure: Throwable) {
            restoreAll(originals, failure)
            throw failure
        }
    }

    internal class Handle(
        private val originals: List<Pair<Path, ByteArray>>,
    ) : AutoCloseable {
        override fun close() {
            restoreAll(originals)
        }
    }

    private fun restoreAll(
        originals: List<Pair<Path, ByteArray>>,
        installationFailure: Throwable? = null,
    ) {
        var restorationFailure: Throwable? = null
        originals.asReversed().forEach { (path, original) ->
            try {
                Files.write(path, original)
            } catch (failure: Throwable) {
                if (restorationFailure == null) {
                    restorationFailure = failure
                } else {
                    checkNotNull(restorationFailure).addSuppressed(failure)
                }
            }
        }
        if (installationFailure != null) {
            restorationFailure?.let(installationFailure::addSuppressed)
        } else {
            restorationFailure?.let { throw it }
        }
    }

    private data class Target(
        val path: Path,
        val methodSelector: (String) -> MethodPlan?,
    )

    private data class MethodPlan(
        val entry: EntryAction,
        val endBuildOnReturn: Boolean = false,
    )

    private sealed interface EntryAction {
        data class Scalar(val family: String) : EntryAction
        data class Action(val family: String, val slot: Int) : EntryAction
        data class ActionView(val indexSlot: Int, val actionSlot: Int) : EntryAction
        data class Build(val listSlot: Int) : EntryAction
        data class DecisionOptions(val listSlot: Int) : EntryAction
    }

    private fun observationBuilderMethod(name: String): MethodPlan? = when {
        name.startsWith("build-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.Build(listSlot = 3), endBuildOnReturn = true)
        name == "legalActionToView" -> MethodPlan(EntryAction.ActionView(indexSlot = 2, actionSlot = 3))
        name.startsWith("mapPublicTargetDomain-") ->
            MethodPlan(EntryAction.Action("targetDomain", slot = 2))
        name.startsWith("mapPublicAttackDeclarationDomain-") ->
            MethodPlan(EntryAction.Action("attackDomain", slot = 2))
        name.startsWith("mapPublicBlockerDeclarationDomain-") ->
            MethodPlan(EntryAction.Action("blockerDomain", slot = 2))
        name == "targetPaymentQualificationFor" ->
            MethodPlan(EntryAction.Action("paymentQualification", slot = 2))
        name == "targetPaymentDomainV1For" ->
            MethodPlan(EntryAction.Action("targetPaymentDomain", slot = 2))
        name == "targetCostDependencyFor" ->
            MethodPlan(EntryAction.Action("targetCostDependency", slot = 3))
        name == "paymentDomainRequestFor" ->
            MethodPlan(EntryAction.Action("paymentDomainRequest", slot = 2))
        name == "paymentDomainV5For\$argentum_engine_gym" ->
            MethodPlan(EntryAction.Action("paymentDomainV5", slot = 2))
        name == "requiredPayloadFieldsFor\$argentum_engine_gym" ->
            MethodPlan(EntryAction.Action("requiredPayloadFields", slot = 2))
        name == "actionSemantic" ->
            MethodPlan(EntryAction.Action("actionSemantic", slot = 2))
        name == "resolveActivatedAbility" ->
            MethodPlan(EntryAction.Action("resolveActivatedAbility", slot = 2))
        name == "stableAbilityKey" ->
            MethodPlan(EntryAction.Action("stableAbilityKey", slot = 2))
        name == "stableAbilityOrdinal" ->
            MethodPlan(EntryAction.Scalar("stableAbilityOrdinal"))
        name == "structuralAbilitySignature" ->
            MethodPlan(EntryAction.Scalar("structuralAbilitySignature"))
        name == "structuralAbilityJson" ->
            MethodPlan(EntryAction.Scalar("structuralAbilityJson"))
        name == "buildDecisionOptionViews" ->
            MethodPlan(EntryAction.DecisionOptions(listSlot = 3))
        else -> null
    }

    private fun paymentDomainBuilderMethod(name: String): MethodPlan? =
        if (name.startsWith("buildV5-") && !name.contains("\$default")) {
            MethodPlan(EntryAction.Scalar("paymentDomainBuilderV5"))
        } else {
            null
        }

    private fun gameEnvironmentMethod(name: String): MethodPlan? =
        if (name == "legalActions") {
            MethodPlan(EntryAction.Scalar("gameEnvironmentLegalActions"))
        } else {
            null
        }

    private fun manaSolverMethod(name: String): MethodPlan? =
        when {
            name.startsWith("findAvailableManaSourcesInternal-") && !name.contains("\$default") ->
                MethodPlan(EntryAction.Scalar("manaSourceDiscovery"))
            name.startsWith("findAvailableManaSources-") && !name.contains("\$default") ->
                MethodPlan(EntryAction.Scalar("manaSourceDiscoveryApi"))
            else -> null
        }

    private fun transform(
        original: ByteArray,
        selector: (String) -> MethodPlan?,
    ): ByteArray {
        val reader = ClassReader(original)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        reader.accept(object : ClassVisitor(ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                val plan = selector(name) ?: return delegate
                return object : MethodVisitor(ASM9, delegate) {
                    private val interceptPaymentBuilderManaCalls =
                        plan.entry.let { it is EntryAction.Scalar && it.family == "paymentDomainBuilderV5" }
                    private val interceptGameEnvironmentEnumeratorCalls =
                        plan.entry.let { it is EntryAction.Scalar && it.family == "gameEnvironmentLegalActions" }

                    override fun visitCode() {
                        super.visitCode()
                        emitEntry(plan.entry)
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (interceptPaymentBuilderManaCalls &&
                            owner == "com/wingedsheep/engine/mechanics/mana/ManaSolver" &&
                            name.startsWith("findAvailableManaSources")
                        ) {
                            emitScalar("manaSourceDiscovery")
                        }
                        if (interceptGameEnvironmentEnumeratorCalls &&
                            owner == "com/wingedsheep/engine/legalactions/LegalActionEnumerator" &&
                            name.startsWith("enumerate-")
                        ) {
                            emitScalar("legalActionEnumerator")
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }

                    override fun visitInsn(opcode: Int) {
                        if (plan.endBuildOnReturn && opcode in setOf(IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN)) {
                            visitMethodInsn(INVOKESTATIC, PROBE_OWNER, "endBuild", "()V", false)
                        }
                        super.visitInsn(opcode)
                    }

                    private fun emitEntry(entry: EntryAction) {
                        when (entry) {
                            is EntryAction.Scalar -> emitScalar(entry.family)
                            is EntryAction.Action -> emitAction(entry.family, entry.slot)
                            is EntryAction.ActionView -> emitActionView(entry.indexSlot, entry.actionSlot)
                            is EntryAction.Build -> emitBuild(entry.listSlot)
                            is EntryAction.DecisionOptions -> emitDecisionOptions(entry.listSlot)
                        }
                    }

                    private fun emitScalar(family: String) {
                        visitLdcInsn(family)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PROBE_OWNER,
                            "recordScalar",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitAction(family: String, actionSlot: Int) {
                        visitLdcInsn(family)
                        visitVarInsn(ALOAD, actionSlot)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PROBE_OWNER,
                            "recordAction",
                            "(Ljava/lang/String;Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitActionView(indexSlot: Int, actionSlot: Int) {
                        visitVarInsn(ILOAD, indexSlot)
                        visitVarInsn(ALOAD, actionSlot)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PROBE_OWNER,
                            "recordActionView",
                            "(ILjava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitBuild(listSlot: Int) {
                        visitVarInsn(ALOAD, listSlot)
                        visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true)
                        visitMethodInsn(INVOKESTATIC, PROBE_OWNER, "beginBuild", "(I)V", false)
                    }

                    private fun emitDecisionOptions(listSlot: Int) {
                        visitVarInsn(ALOAD, listSlot)
                        visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true)
                        visitMethodInsn(INVOKESTATIC, PROBE_OWNER, "recordDecisionOptionViews", "(I)V", false)
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }

    private fun locate(module: String, suffix: String): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .flatMap { base ->
                sequenceOf(
                    base.resolve(module).resolve("build/classes/kotlin/main").resolve(suffix),
                    base.resolve("build/classes/kotlin/main").resolve(suffix).takeIf { module == "gym" },
                )
            }
            .filterNotNull()
            .firstOrNull(Files::exists)
            ?: error("B1 characterization class file not found: module=$module suffix=$suffix")
    }
}

/**
 * Finalize the test-only evidence first, but always restore the bytecode patch. If probe stopping
 * or evidence writing throws, the restoration failure (if any) is still reported by [close].
 */
internal fun finishB1Characterization(
    session: B1ObservationProbe.Session?,
    instrumentation: B1ObservationBytecodeInstrumentation.Handle?,
    writeEvidence: (B1ObservationProbe.Snapshot) -> Unit,
) {
    try {
        session?.let { writeEvidence(B1ObservationProbe.stop(it)) }
    } finally {
        instrumentation?.close()
    }
}
