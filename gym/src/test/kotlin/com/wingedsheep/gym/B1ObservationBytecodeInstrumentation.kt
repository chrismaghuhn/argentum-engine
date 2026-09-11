package com.wingedsheep.gym

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ASM9
import org.objectweb.asm.Opcodes.DUP
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
    private const val COST_PROBE_OWNER = "com/wingedsheep/gym/B1StepCostAttributionProbe"
    private const val PIPELINE_PROBE_OWNER = "com/wingedsheep/gym/B1CanonicalizationPipelineProbe"

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

    /** Install only the large-boundary timing hooks used by the post-Fix-18 coarse attribution. */
    internal fun installCoarseAttribution(): Handle = installTargets(
        listOf(
            Target(
                locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
                ::gameEnvironmentCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/GameGymEnv.class"),
                ::gameGymEnvCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
                ::observationBuilderCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/PerspectiveEventProjector.class"),
                ::historyACoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/HistoryCReferenceEnvelopeProducerV1.class"),
                ::historyCProducerCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/HistoryCReferenceAuthority.class"),
                ::historyCAuthorityCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/PerspectiveReferenceProjectorV1.class"),
                ::historyBCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/PerspectiveHistoryComposerV1.class"),
                ::historyDCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/StateDigest.class"),
                ::digestCoarseMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationCanonicalizer.class"),
                ::canonicalizationCoarseMethod,
            ),
        ),
    )

    /** Install only the test-only call and stage hooks for Characterization-20. */
    internal fun installCanonicalizationPipeline(): Handle = installTargets(
        listOf(
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationCanonicalizer.class"),
                ::canonicalizationPipelineMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/StateDigest.class"),
                ::stateDigestPipelineMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
                ::observationBuilderPipelineMethod,
            ),
        ),
    )

    internal fun canonicalizationPipelineClassOutputPathsForTest(): List<Path> = listOf(
        locate("gym", "com/wingedsheep/gym/contract/ObservationCanonicalizer.class"),
        locate("gym", "com/wingedsheep/gym/contract/StateDigest.class"),
        locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
    )

    internal fun coarseAttributionClassOutputPathsForTest(): List<Path> = listOf(
        locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
        locate("gym", "com/wingedsheep/gym/GameGymEnv.class"),
        locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
        locate("gym", "com/wingedsheep/gym/contract/PerspectiveEventProjector.class"),
        locate("gym", "com/wingedsheep/gym/history/HistoryCReferenceEnvelopeProducerV1.class"),
        locate("gym", "com/wingedsheep/gym/history/HistoryCReferenceAuthority.class"),
        locate("gym", "com/wingedsheep/gym/history/PerspectiveReferenceProjectorV1.class"),
        locate("gym", "com/wingedsheep/gym/history/PerspectiveHistoryComposerV1.class"),
        locate("gym", "com/wingedsheep/gym/contract/StateDigest.class"),
        locate("gym", "com/wingedsheep/gym/contract/ObservationCanonicalizer.class"),
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
        val callSites: List<CallSite> = emptyList(),
    )

    private sealed interface EntryAction {
        data object None : EntryAction
        data class Scalar(val family: String) : EntryAction
        data class Action(val family: String, val slot: Int) : EntryAction
        data class ActionView(val indexSlot: Int, val actionSlot: Int) : EntryAction
        data class Build(val listSlot: Int) : EntryAction
        data class DecisionOptions(val listSlot: Int) : EntryAction
        data class Timed(val family: String) : EntryAction
        data class PipelineMethod(val kind: String, val argumentSlot: Int = 1) : EntryAction
        data class PipelineCanonicalize(val argumentSlot: Int = 1) : EntryAction
    }

    private sealed interface CallSite {
        data class Operation(
            val owner: String,
            val name: String,
            val operation: String,
        ) : CallSite

        data class Consumer(
            val owner: String,
            val name: String,
            val family: String,
        ) : CallSite
    }

    private fun gameEnvironmentCoarseMethod(name: String): MethodPlan? = when (name) {
        "processAndCommit" -> MethodPlan(EntryAction.Timed("RULES_EXECUTION_PLUS_HISTORY_B_KNOWN_INFORMATION"))
        "legalActions" -> MethodPlan(EntryAction.Timed("LEGAL_ACTION_AND_DOMAIN"))
        else -> null
    }

    private fun gameGymEnvCoarseMethod(name: String): MethodPlan? = when (name) {
        "buildObservation" -> MethodPlan(EntryAction.Timed("OBSERVATION_BOUNDARY"))
        "appendAutomaticPerspectiveHistory" -> MethodPlan(EntryAction.Timed("HISTORY_D_ORCHESTRATION"))
        else -> null
    }

    private fun observationBuilderCoarseMethod(name: String): MethodPlan? =
        if (name.startsWith("build-") && !name.contains("\$default")) {
            MethodPlan(EntryAction.Timed("OBSERVATION_BUILDER"))
        } else {
            null
        }

    private fun historyACoarseMethod(name: String): MethodPlan? =
        if (name.startsWith("project-")) MethodPlan(EntryAction.Timed("HISTORY_A")) else null

    private fun historyCProducerCoarseMethod(name: String): MethodPlan? =
        if (name == "produce") MethodPlan(EntryAction.Timed("HISTORY_C_PRODUCER")) else null

    private fun historyCAuthorityCoarseMethod(name: String): MethodPlan? =
        if (name == "validate") MethodPlan(EntryAction.Timed("HISTORY_C_AUTHORITY")) else null

    private fun historyBCoarseMethod(name: String): MethodPlan? =
        if (name.startsWith("project-")) MethodPlan(EntryAction.Timed("HISTORY_B_REFERENCE")) else null

    private fun historyDCoarseMethod(name: String): MethodPlan? =
        if (name == "append") MethodPlan(EntryAction.Timed("HISTORY_D_APPEND")) else null

    private fun digestCoarseMethod(name: String): MethodPlan? =
        if (name == "compute") MethodPlan(EntryAction.Timed("DIGEST")) else null

    private fun canonicalizationCoarseMethod(name: String): MethodPlan? =
        if (name == "semanticJson") MethodPlan(EntryAction.Timed("CANONICALIZATION")) else null

    private fun canonicalizationPipelineMethod(name: String): MethodPlan? {
        val calls = canonicalizationPipelineCallSites()
        return when {
            name == "canonicalize" -> MethodPlan(EntryAction.PipelineCanonicalize())
            name == "semanticJson" -> MethodPlan(
                EntryAction.PipelineMethod("SEMANTIC_JSON"),
                callSites = calls,
            )
            name == "semanticJson\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("SEMANTIC_JSON"),
                callSites = calls,
            )
            name == "playerObservationJson\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("PLAYER_OBSERVATION_JSON"),
                callSites = calls,
            )
            name == "playerObservationDigest\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("PLAYER_OBSERVATION_DIGEST"),
                callSites = calls,
            )
            name == "canonicalElement\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("CANONICAL_ELEMENT"),
                callSites = calls,
            )
            name == "canonicalJson\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("CANONICAL_JSON"),
                callSites = calls,
            )
            name == "canonicalDomainJson\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("CANONICAL_DOMAIN_JSON"),
                callSites = calls,
            )
            name == "wireJson" -> MethodPlan(
                EntryAction.PipelineMethod("WIRE_JSON"),
                callSites = calls,
            )
            name.startsWith("semanticStructuredDomain\$") && !name.contains("\$default") -> MethodPlan(
                EntryAction.PipelineMethod("SEMANTIC_STRUCTURED_DOMAIN"),
                callSites = calls,
            )
            name == "sortSemanticActionFingerprints\$argentum_engine_gym" -> MethodPlan(
                EntryAction.PipelineMethod("SORT_FINGERPRINTS"),
                callSites = calls,
            )
            name == "semanticActionFingerprint" -> MethodPlan(
                EntryAction.PipelineMethod("SEMANTIC_ACTION_FINGERPRINT"),
                callSites = calls,
            )
            else -> null
        }
    }

    private fun stateDigestPipelineMethod(name: String): MethodPlan? = when (name) {
        "compute" -> MethodPlan(EntryAction.PipelineMethod("STATE_DIGEST_COMPUTE"))
        "digest" -> MethodPlan(
            EntryAction.PipelineMethod("DIGEST_BODY"),
            callSites = stateDigestPipelineCallSites(),
        )
        else -> null
    }

    private fun observationBuilderPipelineMethod(name: String): MethodPlan? =
        if (name.startsWith("build-") && !name.contains("\$default")) {
            MethodPlan(
                entry = EntryAction.None,
                callSites = listOf(
                    CallSite.Consumer(
                        owner = "com/wingedsheep/gym/contract/StateDigest",
                        name = "compute",
                        family = "OBSERVATION_BUILDER_STATE_DIGEST",
                    ),
                ),
            )
        } else {
            null
        }

    private fun canonicalizationPipelineCallSites(): List<CallSite> = listOf(
        CallSite.Operation(
            owner = "kotlinx/serialization/json/Json",
            name = "encodeToJsonElement",
            operation = "ENCODE_TO_JSON_ELEMENT",
        ),
        CallSite.Operation(
            owner = "kotlinx/serialization/json/JsonElement",
            name = "toString",
            operation = "JSON_TO_STRING",
        ),
        CallSite.Operation(
            owner = "kotlinx/serialization/json/JsonObject",
            name = "toString",
            operation = "JSON_TO_STRING",
        ),
        CallSite.Operation(
            owner = "kotlinx/serialization/json/JsonArray",
            name = "toString",
            operation = "JSON_TO_STRING",
        ),
        CallSite.Operation(
            owner = "java/lang/Object",
            name = "toString",
            operation = "JSON_TO_STRING",
        ),
    )

    private fun stateDigestPipelineCallSites(): List<CallSite> = listOf(
        CallSite.Operation(
            owner = "java/lang/String",
            name = "getBytes",
            operation = "UTF8_ENCODING",
        ),
        CallSite.Operation(
            owner = "java/security/MessageDigest",
            name = "digest",
            operation = "SHA256_DIGEST",
        ),
        CallSite.Operation(
            owner = "kotlin/collections/ArraysKt",
            name = "joinToString\$default",
            operation = "HEX_RENDER",
        ),
    )

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
                        plan.callSites.forEach { callSite ->
                            if (callSite.matches(owner, name)) emitCallSiteStart(callSite)
                        }
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
                        plan.callSites.forEach { callSite ->
                            if (callSite.matches(owner, name)) emitCallSiteEnd(callSite)
                        }
                    }

                    override fun visitInsn(opcode: Int) {
                        val isReturn = opcode in setOf(IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN)
                        if (plan.endBuildOnReturn && isReturn) {
                            visitMethodInsn(INVOKESTATIC, PROBE_OWNER, "endBuild", "()V", false)
                        }
                        if (plan.entry is EntryAction.Timed && isReturn) {
                            emitPhaseEnd(plan.entry.family)
                        }
                        if (isReturn) {
                            when (val entry = plan.entry) {
                                is EntryAction.PipelineMethod -> emitPipelineMethodEnd(entry)
                                is EntryAction.PipelineCanonicalize -> emitPipelineCanonicalizeEnd()
                                else -> Unit
                            }
                        }
                        super.visitInsn(opcode)
                    }

                    private fun emitEntry(entry: EntryAction) {
                        when (entry) {
                            EntryAction.None -> Unit
                            is EntryAction.Scalar -> emitScalar(entry.family)
                            is EntryAction.Action -> emitAction(entry.family, entry.slot)
                            is EntryAction.ActionView -> emitActionView(entry.indexSlot, entry.actionSlot)
                            is EntryAction.Build -> emitBuild(entry.listSlot)
                            is EntryAction.DecisionOptions -> emitDecisionOptions(entry.listSlot)
                            is EntryAction.Timed -> emitPhaseStart(entry.family)
                            is EntryAction.PipelineMethod -> emitPipelineMethodStart(entry)
                            is EntryAction.PipelineCanonicalize -> emitPipelineCanonicalizeStart(entry)
                        }
                    }

                    private fun emitPipelineMethodStart(entry: EntryAction.PipelineMethod) {
                        visitLdcInsn(entry.kind)
                        visitVarInsn(ALOAD, entry.argumentSlot)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PIPELINE_PROBE_OWNER,
                            "enterMethod",
                            "(Ljava/lang/String;Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitPipelineMethodEnd(entry: EntryAction.PipelineMethod) {
                        when (entry.kind) {
                            "SEMANTIC_JSON" -> emitResultProbe("recordSemanticJsonResult")
                            "STATE_DIGEST_COMPUTE" -> emitResultProbe("recordDigestResult")
                            else -> Unit
                        }
                        visitLdcInsn(entry.kind)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PIPELINE_PROBE_OWNER,
                            "exitMethod",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitResultProbe(method: String) {
                        visitInsn(DUP)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PIPELINE_PROBE_OWNER,
                            method,
                            "(Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitPipelineCanonicalizeStart(entry: EntryAction.PipelineCanonicalize) {
                        visitVarInsn(ALOAD, entry.argumentSlot)
                        visitMethodInsn(
                            INVOKESTATIC,
                            PIPELINE_PROBE_OWNER,
                            "enterCanonicalize",
                            "(Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitPipelineCanonicalizeEnd() {
                        visitMethodInsn(
                            INVOKESTATIC,
                            PIPELINE_PROBE_OWNER,
                            "exitCanonicalize",
                            "()V",
                            false,
                        )
                    }

                    private fun emitPhaseStart(family: String) {
                        visitLdcInsn(family)
                        visitMethodInsn(
                            INVOKESTATIC,
                            COST_PROBE_OWNER,
                            "startPhase",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitPhaseEnd(family: String) {
                        visitLdcInsn(family)
                        visitMethodInsn(
                            INVOKESTATIC,
                            COST_PROBE_OWNER,
                            "endPhase",
                            "(Ljava/lang/String;)V",
                            false,
                        )
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

                    private fun emitCallSiteStart(callSite: CallSite) {
                        when (callSite) {
                            is CallSite.Operation -> {
                                visitLdcInsn(callSite.operation)
                                visitMethodInsn(
                                    INVOKESTATIC,
                                    PIPELINE_PROBE_OWNER,
                                    "startOperation",
                                    "(Ljava/lang/String;)V",
                                    false,
                                )
                            }
                            is CallSite.Consumer -> {
                                visitLdcInsn(callSite.family)
                                visitMethodInsn(
                                    INVOKESTATIC,
                                    PIPELINE_PROBE_OWNER,
                                    "recordConsumer",
                                    "(Ljava/lang/String;)V",
                                    false,
                                )
                            }
                        }
                    }

                    private fun emitCallSiteEnd(callSite: CallSite) {
                        if (callSite is CallSite.Operation) {
                            if (callSite.operation == "UTF8_ENCODING") {
                                visitInsn(DUP)
                                visitMethodInsn(
                                    INVOKESTATIC,
                                    PIPELINE_PROBE_OWNER,
                                    "recordByteArrayResult",
                                    "(Ljava/lang/Object;)V",
                                    false,
                                )
                            }
                            visitLdcInsn(callSite.operation)
                            visitMethodInsn(
                                INVOKESTATIC,
                                PIPELINE_PROBE_OWNER,
                                "endOperation",
                                "(Ljava/lang/String;)V",
                                false,
                            )
                        }
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }

    private fun CallSite.matches(owner: String, name: String): Boolean = when (this) {
        is CallSite.Operation -> owner == this.owner && name == this.name
        is CallSite.Consumer -> owner == this.owner && name == this.name
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
