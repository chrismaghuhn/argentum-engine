package com.wingedsheep.gym

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.CHECKCAST
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
import org.objectweb.asm.Opcodes.SWAP
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Test-only offline bytecode instrumentation. It patches build outputs only for one explicitly
 * enabled characterization JVM and restores every class file when the run ends. This keeps the
 * production source and production runtime free of profiler hooks.
 */
internal object B1ObservationBytecodeInstrumentation {
    private const val PROBE_OWNER = "com/wingedsheep/gym/B1ObservationProbe"
    private const val COST_PROBE_OWNER = "com/wingedsheep/gym/B1StepCostAttributionProbe"
    private const val LEGAL_DOMAIN_PROBE_OWNER = "com/wingedsheep/gym/B1LegalActionDomainProbe"

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

    /**
     * Install optional test-only exclusive phase timers for one attribution JVM. The transformed
     * class files are restored by the returned handle before the test exits.
     */
    internal fun installStepCostAttribution(): Handle = installTargets(
        listOf(
            Target(
                locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
                ::gameEnvironmentMethodForAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/GameGymEnv.class"),
                ::gameGymEnvMethodForAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
                ::observationBuilderMethodForAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/PerspectiveEventProjector.class"),
                ::historyAMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/HistoryCReferenceEnvelopeProducerV1.class"),
                ::historyCProducerMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/HistoryCReferenceAuthority.class"),
                ::historyCAuthorityMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/PerspectiveReferenceProjectorV1.class"),
                ::historyBReferenceMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/history/PerspectiveHistoryComposerV1.class"),
                ::historyDMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/StateDigest.class"),
                ::digestMethod,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationCanonicalizer.class"),
                ::canonicalizationMethod,
            ),
        ),
    )

    /**
     * Install the deeper legal-action/domain characterization probes. Every target is a compiled
     * build output and is restored by the returned handle; no production source or runtime class
     * remains instrumented after the run.
     */
    internal fun installLegalActionDomainDeep(includeRulesEngineJar: Boolean = true): Handle {
        val targets = mutableListOf(
            Target(
                locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
                ::gameEnvironmentMethodForDeepAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/GameGymEnv.class"),
                ::gameGymEnvMethodForDeepAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
                ::observationBuilderMethodForDeepAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/contract/PaymentDomainBuilder.class"),
                ::paymentDomainBuilderMethodForDeepAttribution,
            ),
            Target(
                locate("gym", "com/wingedsheep/gym/ActionPaymentPlanValidator.class"),
                ::actionPaymentPlanValidatorMethodForDeepAttribution,
            ),
            Target(
                locate("rules-engine", "com/wingedsheep/engine/mechanics/mana/ManaSolver.class"),
                ::manaSolverMethodForDeepAttribution,
            ),
            Target(
                locate("rules-engine", "com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.class"),
                ::paymentPlanValidatorMethodForDeepAttribution,
            ),
        )
        deepEnumeratorClasses.forEach { (className, family) ->
            targets += Target(
                locate(
                    "rules-engine",
                    "com/wingedsheep/engine/legalactions/enumerators/$className.class",
                ),
                { name ->
                    if (name == "enumerate") MethodPlan(EntryAction.DeepList(family)) else null
                },
            )
        }
        val classOutputHandle = installTargets(targets)
        if (!includeRulesEngineJar) return classOutputHandle
        return try {
            classOutputHandle.plus(installRulesEngineJarTargets(locateJar("rules-engine")))
        } catch (failure: Throwable) {
            classOutputHandle.close()
            throw failure
        }
    }

    internal fun legalActionDomainDeepClassOutputPathsForTest(): List<Path> = listOf(
        locate("gym", "com/wingedsheep/gym/GameEnvironment.class"),
        locate("gym", "com/wingedsheep/gym/GameGymEnv.class"),
        locate("gym", "com/wingedsheep/gym/contract/ObservationBuilder.class"),
        locate("gym", "com/wingedsheep/gym/contract/PaymentDomainBuilder.class"),
        locate("gym", "com/wingedsheep/gym/ActionPaymentPlanValidator.class"),
        locate("rules-engine", "com/wingedsheep/engine/mechanics/mana/ManaSolver.class"),
        locate("rules-engine", "com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.class"),
    ) + deepEnumeratorClasses.map { (className, _) ->
        locate("rules-engine", "com/wingedsheep/engine/legalactions/enumerators/$className.class")
    }

    internal fun rulesEngineJarPathForTest(): Path = locateJar("rules-engine")

    internal fun installRulesEngineJarTargetsForTest(jarPath: Path): Handle =
        installRulesEngineJarTargets(jarPath)

    /** Create a patched shadow jar without touching the runtime jar. */
    internal fun writePatchedRulesEngineJarForTest(destination: Path) {
        Files.createDirectories(destination.parent)
        val tempJar = Files.createTempFile(destination.parent, "rules-engine-deep-shadow-", ".jar.tmp")
        try {
            writeTransformedRulesEngineJar(locateJar("rules-engine"), tempJar)
            Files.move(tempJar, destination, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            Files.deleteIfExists(tempJar)
            throw failure
        }
    }

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

    internal fun attributionClassOutputPathsForTest(): List<Path> = listOf(
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
            return Handle(
                originals.map { (path, bytes) ->
                    { Files.write(path, bytes) }
                },
            )
        } catch (failure: Throwable) {
            restoreAll(originals, failure)
            throw failure
        }
    }

    internal class Handle(
        private val restorers: List<() -> Unit>,
    ) : AutoCloseable {
        override fun close() {
            restoreActions(restorers)
        }

        internal fun plus(other: Handle): Handle = Handle(restorers + other.restorers)
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
        data class Timed(val family: String) : EntryAction
        data object DeepLegalActions : EntryAction
        data class DeepContext(val purpose: String) : EntryAction
        data class DeepPhase(val family: String) : EntryAction
        data class DeepList(val family: String) : EntryAction
        data class DeepAction(val family: String, val actionSlot: Int) : EntryAction
        data class DeepBuild(val listSlot: Int, val family: String = "OBSERVATION_BUILD") : EntryAction
    }

    private fun restoreActions(restorers: List<() -> Unit>) {
        var restorationFailure: Throwable? = null
        restorers.asReversed().forEach { restore ->
            try {
                restore()
            } catch (failure: Throwable) {
                if (restorationFailure == null) {
                    restorationFailure = failure
                } else {
                    checkNotNull(restorationFailure).addSuppressed(failure)
                }
            }
        }
        restorationFailure?.let { throw it }
    }

    private fun installRulesEngineJarTargets(jarPath: Path): Handle {
        val originalJar = Files.readAllBytes(jarPath)
        val tempJar = Files.createTempFile(jarPath.parent, "rules-engine-deep-", ".jar.tmp")
        try {
            writeTransformedRulesEngineJar(jarPath, tempJar)
            Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING)
            return Handle(listOf { Files.write(jarPath, originalJar) })
        } catch (failure: Throwable) {
            Files.deleteIfExists(tempJar)
            throw failure
        }
    }

    private fun writeTransformedRulesEngineJar(source: Path, destination: Path) {
        val entrySelectors = buildMap<String, (String) -> MethodPlan?> {
            put("com/wingedsheep/engine/mechanics/mana/ManaSolver.class", ::manaSolverMethodForDeepAttribution)
            put(
                "com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.class",
                ::paymentPlanValidatorMethodForDeepAttribution,
            )
            deepEnumeratorClasses.forEach { (className, family) ->
                put(
                    "com/wingedsheep/engine/legalactions/enumerators/$className.class",
                    { name ->
                        if (name == "enumerate") MethodPlan(EntryAction.DeepList(family)) else null
                    },
                )
            }
        }
        ZipInputStream(Files.newInputStream(source)).use { input ->
            ZipOutputStream(Files.newOutputStream(destination)).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    val bytes = input.readBytes()
                    val transformed = entrySelectors[entry.name]?.let { selector ->
                        transform(bytes, selector)
                    } ?: bytes
                    val outputEntry = ZipEntry(entry.name).also {
                        if (entry.time >= 0L) it.time = entry.time
                    }
                    output.putNextEntry(outputEntry)
                    output.write(transformed)
                    output.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
    }

    private val deepEnumeratorClasses = listOf(
        "CombatEnumerator" to "COMBAT_ACTIONS",
        "PassPriorityEnumerator" to "PRIORITY_SPECIAL_ACTIONS",
        "PlayLandEnumerator" to "LAND_ACTIONS",
        "MorphCastEnumerator" to "CAST_SPELL_ACTIONS",
        "CastSpellEnumerator" to "CAST_SPELL_ACTIONS",
        "SneakCastEnumerator" to "CAST_SPELL_ACTIONS",
        "EmergeCastEnumerator" to "CAST_SPELL_ACTIONS",
        "WebSlingingCastEnumerator" to "CAST_SPELL_ACTIONS",
        "CyclingEnumerator" to "SPECIAL_ACTIONS",
        "PlotEnumerator" to "SPECIAL_ACTIONS",
        "ForetellEnumerator" to "SPECIAL_ACTIONS",
        "SuspendEnumerator" to "SPECIAL_ACTIONS",
        "CastFromZoneEnumerator" to "CAST_SPELL_ACTIONS",
        "ManaAbilityEnumerator" to "ACTIVATED_ABILITY_ACTIONS",
        "TurnFaceUpEnumerator" to "SPECIAL_ACTIONS",
        "UnlockRoomDoorEnumerator" to "SPECIAL_ACTIONS",
        "ActivatedAbilityEnumerator" to "ACTIVATED_ABILITY_ACTIONS",
        "CrewEnumerator" to "SPECIAL_ACTIONS",
        "SaddleEnumerator" to "SPECIAL_ACTIONS",
        "ZoneActivatedAbilityEnumerator" to "ACTIVATED_ABILITY_ACTIONS",
        "CommandZoneAbilityEnumerator" to "ACTIVATED_ABILITY_ACTIONS",
    )

    private fun gameEnvironmentMethodForAttribution(name: String): MethodPlan? = when {
        name == "processAndCommit" ->
            MethodPlan(EntryAction.Timed("RULES_EXECUTION_PLUS_HISTORY_B_KNOWN_INFORMATION"))
        name == "legalActions" -> MethodPlan(EntryAction.Timed("LEGAL_ACTION_AND_DOMAIN"))
        else -> null
    }

    private fun gameGymEnvMethodForAttribution(name: String): MethodPlan? = when {
        name == "buildObservation" -> MethodPlan(EntryAction.Timed("OBSERVATION_BOUNDARY"))
        name == "appendAutomaticPerspectiveHistory" ->
            MethodPlan(EntryAction.Timed("HISTORY_D_ORCHESTRATION"))
        else -> null
    }

    private fun observationBuilderMethodForAttribution(name: String): MethodPlan? =
        if (name.startsWith("build-") && !name.contains("\$default")) {
            MethodPlan(EntryAction.Timed("OBSERVATION_BUILDER"))
        } else {
            null
        }

    private fun historyAMethod(name: String): MethodPlan? =
        if (name.startsWith("project-")) MethodPlan(EntryAction.Timed("HISTORY_A")) else null

    private fun historyCProducerMethod(name: String): MethodPlan? =
        if (name == "produce") MethodPlan(EntryAction.Timed("HISTORY_C_PRODUCER")) else null

    private fun historyCAuthorityMethod(name: String): MethodPlan? =
        if (name == "validate") MethodPlan(EntryAction.Timed("HISTORY_C_AUTHORITY")) else null

    private fun historyBReferenceMethod(name: String): MethodPlan? =
        if (name.startsWith("project-")) MethodPlan(EntryAction.Timed("HISTORY_B_REFERENCE")) else null

    private fun historyDMethod(name: String): MethodPlan? =
        if (name == "append") MethodPlan(EntryAction.Timed("HISTORY_D_APPEND")) else null

    private fun digestMethod(name: String): MethodPlan? =
        if (name == "compute") MethodPlan(EntryAction.Timed("DIGEST")) else null

    private fun canonicalizationMethod(name: String): MethodPlan? =
        if (name == "semanticJson") MethodPlan(EntryAction.Timed("CANONICALIZATION")) else null

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

    private fun gameEnvironmentMethodForDeepAttribution(name: String): MethodPlan? = when {
        name == "legalActions" -> MethodPlan(EntryAction.DeepLegalActions)
        name.startsWith("isCurrentActionCandidate") ->
            MethodPlan(EntryAction.DeepPhase("ACTION_CANDIDATE_MATCHING"))
        name.startsWith("stepFromCandidateStrict") ->
            MethodPlan(EntryAction.DeepContext("OLD_STATE_SELECTED_ACTION_REVALIDATION"))
        name.startsWith("stepStrict") ->
            MethodPlan(EntryAction.DeepContext("OLD_STATE_STRUCTURED_ACTION_REVALIDATION"))
        else -> null
    }

    private fun gameGymEnvMethodForDeepAttribution(name: String): MethodPlan? = when {
        name == "buildObservation" ->
            MethodPlan(EntryAction.DeepContext("NEXT_OBSERVATION_PUBLICATION"))
        name == "currentTargetPaymentSnapshot" ->
            MethodPlan(EntryAction.DeepContext("TARGET_PAYMENT_REFRESH"))
        else -> null
    }

    private fun observationBuilderMethodForDeepAttribution(name: String): MethodPlan? = when {
        name.startsWith("build-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepBuild(listSlot = 3))
        name == "legalActionToView" ->
            MethodPlan(EntryAction.DeepAction("ACTION_VIEW_BUILD", actionSlot = 3))
        name.startsWith("mapPublicTargetDomain-") -> MethodPlan(EntryAction.DeepPhase("TARGET_DOMAIN"))
        name.startsWith("mapPublicAttackDeclarationDomain-") ->
            MethodPlan(EntryAction.DeepPhase("ATTACK_DOMAIN"))
        name.startsWith("mapPublicBlockerDeclarationDomain-") ->
            MethodPlan(EntryAction.DeepPhase("BLOCKER_DOMAIN"))
        name == "targetPaymentQualificationFor" ->
            MethodPlan(EntryAction.DeepPhase("TARGET_PAYMENT_QUALIFICATION"))
        name == "targetPaymentDomainV1For" ->
            MethodPlan(EntryAction.DeepPhase("TARGET_PAYMENT_DOMAIN"))
        name == "targetCostDependencyFor" -> MethodPlan(EntryAction.DeepPhase("TARGET_COST_DEPENDENCY"))
        name == "paymentDomainRequestFor" -> MethodPlan(EntryAction.DeepPhase("PAYMENT_DOMAIN_REQUEST"))
        name == "paymentDomainV5For\$argentum_engine_gym" ->
            MethodPlan(EntryAction.DeepPhase("PAYMENT_DOMAIN_V5"))
        name == "requiredPayloadFieldsFor\$argentum_engine_gym" ->
            MethodPlan(EntryAction.DeepPhase("REQUIRED_PAYLOAD_FIELDS"))
        name == "actionSemantic" -> MethodPlan(EntryAction.DeepPhase("ACTION_SEMANTIC"))
        name == "resolveActivatedAbility" -> MethodPlan(EntryAction.DeepPhase("RESOLVE_ACTIVATED_ABILITY"))
        name == "stableAbilityKey" -> MethodPlan(EntryAction.DeepPhase("STABLE_ABILITY_KEY"))
        name == "stableAbilityOrdinal" -> MethodPlan(EntryAction.DeepPhase("STABLE_ABILITY_ORDINAL"))
        name == "structuralAbilitySignature" ->
            MethodPlan(EntryAction.DeepPhase("STRUCTURAL_ABILITY_SIGNATURE"))
        name == "structuralAbilityJson" -> MethodPlan(EntryAction.DeepPhase("STRUCTURAL_ABILITY_JSON"))
        name == "buildDecisionOptionViews" ->
            MethodPlan(EntryAction.DeepList("STRUCTURED_DECISION_DOMAIN"))
        else -> null
    }

    private fun paymentDomainBuilderMethodForDeepAttribution(name: String): MethodPlan? = when {
        name.startsWith("buildV5-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepPhase("PAYMENT_DOMAIN_BUILDER_V5"))
        name.startsWith("build-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepPhase("PAYMENT_DOMAIN_BUILDER_V4"))
        else -> null
    }

    private fun actionPaymentPlanValidatorMethodForDeepAttribution(name: String): MethodPlan? = when {
        name == "require" || name == "requireOrdinary" || name.startsWith("requireTargetPaymentPlan") ->
            MethodPlan(EntryAction.DeepPhase("ACTION_PAYMENT_VALIDATION"))
        else -> null
    }

    private fun paymentPlanValidatorMethodForDeepAttribution(name: String): MethodPlan? =
        if (name.startsWith("validate") && !name.contains("\$default")) {
            MethodPlan(EntryAction.DeepPhase("PAYMENT_PLAN_VALIDATION"))
        } else {
            null
        }

    private fun manaSolverMethodForDeepAttribution(name: String): MethodPlan? = when {
        name.startsWith("findAvailableManaSourcesInternal-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepList("MANA_SOURCE_DISCOVERY_INTERNAL"))
        name.startsWith("findAvailableManaSources-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepList("MANA_SOURCE_DISCOVERY"))
        name.startsWith("solve-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepPhase("MANA_SOLVE"))
        name.startsWith("canPay-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepPhase("MANA_CAN_PAY"))
        name.startsWith("getAvailableManaCount-") && !name.contains("\$default") ->
            MethodPlan(EntryAction.DeepPhase("MANA_AVAILABLE_COUNT"))
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
                    private val interceptDeepGameEnvironmentEnumeratorCalls =
                        plan.entry is EntryAction.DeepLegalActions

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
                        if (interceptDeepGameEnvironmentEnumeratorCalls &&
                            owner == "com/wingedsheep/engine/legalactions/LegalActionEnumerator" &&
                            name.startsWith("enumerate-") &&
                            !name.contains("\$default")
                        ) {
                            emitDeepPhaseStart("LEGAL_ACTION_ENUMERATOR")
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                            emitDeepListEnd("LEGAL_ACTION_ENUMERATOR")
                            return
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
                    }

                    override fun visitInsn(opcode: Int) {
                        if (plan.endBuildOnReturn && opcode in setOf(IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN)) {
                            visitMethodInsn(INVOKESTATIC, PROBE_OWNER, "endBuild", "()V", false)
                        }
                        if (plan.entry is EntryAction.Timed &&
                            opcode in setOf(IRETURN, LRETURN, DRETURN, FRETURN, ARETURN, RETURN)
                        ) {
                            emitPhaseEnd(plan.entry.family)
                        }
                        if (opcode in setOf(IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN)) {
                            when (val entry = plan.entry) {
                                EntryAction.DeepLegalActions -> emitDeepLegalActionsEnd()
                                is EntryAction.DeepContext -> emitDeepContextEnd(entry.purpose)
                                is EntryAction.DeepPhase -> emitDeepPhaseEnd(entry.family)
                                is EntryAction.DeepList -> emitDeepListEnd(entry.family)
                                is EntryAction.DeepAction -> emitDeepPhaseEnd(entry.family)
                                is EntryAction.DeepBuild -> emitDeepPhaseEnd(entry.family)
                                else -> Unit
                            }
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
                            is EntryAction.Timed -> emitPhaseStart(entry.family)
                            EntryAction.DeepLegalActions -> emitDeepLegalActionsStart()
                            is EntryAction.DeepContext -> emitDeepContextStart(entry.purpose)
                            is EntryAction.DeepPhase -> emitDeepPhaseStart(entry.family)
                            is EntryAction.DeepList -> emitDeepPhaseStart(entry.family)
                            is EntryAction.DeepAction -> emitDeepActionStart(entry.family, entry.actionSlot)
                            is EntryAction.DeepBuild -> emitDeepBuildStart(entry.family, entry.listSlot)
                        }
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

                    private fun emitDeepLegalActionsStart() {
                        visitVarInsn(ALOAD, 0)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "beginLegalActions",
                            "(Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitDeepLegalActionsEnd() {
                        visitInsn(DUP)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "endLegalActions",
                            "(Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitDeepContextStart(purpose: String) {
                        visitLdcInsn(purpose)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "pushPurpose",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitDeepContextEnd(purpose: String) {
                        visitLdcInsn(purpose)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "popPurpose",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitDeepPhaseStart(family: String) {
                        visitLdcInsn(family)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "startPhase",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitDeepPhaseEnd(family: String) {
                        visitLdcInsn(family)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "endPhase",
                            "(Ljava/lang/String;)V",
                            false,
                        )
                    }

                    private fun emitDeepListEnd(family: String) {
                        visitInsn(DUP)
                        visitTypeInsn(CHECKCAST, "java/util/List")
                        visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true)
                        visitLdcInsn(family)
                        visitInsn(SWAP)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "endListPhase",
                            "(Ljava/lang/String;I)V",
                            false,
                        )
                    }

                    private fun emitDeepActionStart(family: String, actionSlot: Int) {
                        emitDeepPhaseStart(family)
                        visitVarInsn(ALOAD, actionSlot)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "recordActionView",
                            "(Ljava/lang/Object;)V",
                            false,
                        )
                    }

                    private fun emitDeepBuildStart(family: String, listSlot: Int) {
                        emitDeepPhaseStart(family)
                        visitVarInsn(ALOAD, listSlot)
                        visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true)
                        visitMethodInsn(
                            INVOKESTATIC,
                            LEGAL_DOMAIN_PROBE_OWNER,
                            "recordObservationCandidateCount",
                            "(I)V",
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

    private fun locateJar(module: String): Path {
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .map { base -> base.resolve(module).resolve("build/libs/$module.jar") }
            .firstOrNull(Files::exists)
            ?: error("B1 characterization jar not found: module=$module")
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
