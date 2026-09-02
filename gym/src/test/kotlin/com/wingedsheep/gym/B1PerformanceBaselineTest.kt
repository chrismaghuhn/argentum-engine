package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.EnvId
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.StepRequest
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import io.kotest.core.spec.style.FunSpec
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.math.max
import kotlin.time.Duration.Companion.hours

/**
 * Opt-in, test-only performance measurement for the trusted Akiri/Chevill Gym path.
 *
 * This test intentionally lives under src/test and is disabled unless b1.profile=true. It drives
 * the same public MultiEnvService/DeterministicExternalPolicy boundary as the exact-pair
 * acceptance test. It does not change decks, policy choices, replay contracts, or horizons.
 */
class B1PerformanceBaselineTest : FunSpec({
    val enabled = System.getProperty("b1.profile") == "true"

    test("writes the requested B1 performance baseline").config(
        enabled = enabled,
        timeout = 4.hours,
    ) {
        runProfiledWorkload()
    }
})

private const val B1_BASE_HEAD = "f50c0c92249fe7d5c2f7b8044b1371462630135e"
private const val B1_MAX_STEPS = 2_000

private val b1Workloads = mapOf(
    "witness" to listOf(EpisodeSpec(0L, 0, "Akiri", "Chevill")),
    "normal4" to listOf(
        EpisodeSpec(0L, 0, "Akiri", "Chevill"),
        EpisodeSpec(0L, 1, "Akiri", "Chevill"),
        EpisodeSpec(0L, 0, "Chevill", "Akiri"),
        EpisodeSpec(0L, 1, "Chevill", "Akiri"),
    ),
    "corpus8" to (0L..3L).flatMap { seed ->
        listOf(
            EpisodeSpec(seed, 0, "Akiri", "Chevill"),
            EpisodeSpec(seed, 1, "Akiri", "Chevill"),
        )
    },
    "replay2" to listOf(
        EpisodeSpec(0L, 0, "Akiri", "Chevill"),
        EpisodeSpec(0L, 1, "Chevill", "Akiri"),
    ),
)

private val b1Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

private fun runProfiledWorkload() {
    val workloadName = System.getProperty("b1.workload") ?: "witness"
    val specs = b1Workloads[workloadName]
        ?: error("Unknown b1.workload=" + workloadName + "; expected " + b1Workloads.keys)
    val mode = System.getProperty("b1.mode") ?: "baseline"
    require(mode == "baseline" || mode == "replay") {
        "Unknown b1.mode=" + mode + "; expected baseline or replay"
    }
    if (mode == "replay") {
        require(workloadName == "replay2") {
            "Diagnostic replay mode is defined only for b1.workload=replay2"
        }
    }

    val outputDir = Path.of(
        System.getProperty(
            "b1.outputDir",
            Path.of(System.getProperty("user.dir"), "build", "reports", "b1").toString(),
        ),
    )
    Files.createDirectories(outputDir)
    val stem = workloadName + "-" + mode
    val characterize = System.getProperty("b1.characterize") == "true"
    val jfrPath = outputDir.resolve(stem + ".jfr")
    val jsonPath = outputDir.resolve(stem + ".json")
    val bytecodeInstrumentation = if (characterize) {
        B1ObservationBytecodeInstrumentation.install()
    } else {
        null
    }
    var characterizationSession: B1ObservationProbe.Session? = null

    try {
        characterizationSession = if (characterize) B1ObservationProbe.start() else null
        Files.deleteIfExists(jfrPath)
        Files.deleteIfExists(jsonPath)
        val recording = openJfrRecording()
        val countersBefore = JvmSnapshot.capture()
        val workloadStart = System.nanoTime()
        var measurement: WorkloadMeasurement? = null
        var workloadWallNanos = 0L
        var countersAfter: JvmSnapshot? = null
        var recordingStopNanos = 0L
        var recordingDumpNanos = 0L
        try {
            measurement = runWorkload(specs, mode)
        } finally {
            workloadWallNanos = System.nanoTime() - workloadStart
            countersAfter = JvmSnapshot.capture()
            recording?.let { current ->
                try {
                    val stopStart = System.nanoTime()
                    current.stop()
                    recordingStopNanos = System.nanoTime() - stopStart
                    val dumpStart = System.nanoTime()
                    current.dump(jfrPath)
                    recordingDumpNanos = System.nanoTime() - dumpStart
                } catch (failure: Exception) {
                    println("B1_JFR=NOT_RUN reason=" + (failure.message ?: failure::class.simpleName))
                } finally {
                    current.close()
                }
            }
        }
        val measured = checkNotNull(measurement) { "B1 profiling workload did not complete" }
        val measuredAfter = checkNotNull(countersAfter) {
            "B1 profiling after-snapshot was not captured"
        }
        val artifactStart = System.nanoTime()
        val finalMeasurement = measured.toMetrics(
            workload = workloadName,
            mode = mode,
            workloadWallNanos = workloadWallNanos,
            countersBefore = countersBefore,
            countersAfter = measuredAfter,
            recordingStopNanos = recordingStopNanos,
            recordingDumpNanos = recordingDumpNanos,
            jfrPath = if (Files.exists(jfrPath)) jfrPath.toString() else null,
        )
        val encoded = b1Json.encodeToString(BaselineMetrics.serializer(), finalMeasurement)
        val serializationNanos = System.nanoTime() - artifactStart
        val writeStart = System.nanoTime()
        Files.writeString(jsonPath, encoded)
        val jsonWriteNanos = System.nanoTime() - writeStart

        println("B1_METRICS_PATH=" + jsonPath)
        println("B1_JFR_PATH=" + (finalMeasurement.jfrPath ?: "NOT_RUN"))
        println("B1_WORKLOAD=" + workloadName)
        println("B1_MODE=" + mode)
        println("B1_EPISODES=" + finalMeasurement.episodes)
        println("B1_TRANSITIONS=" + finalMeasurement.externalTransitions)
        println("B1_SEMANTIC_DECISIONS=" + finalMeasurement.semanticDecisions)
        println("B1_WALL_SECONDS=" + formatSeconds(finalMeasurement.workloadWallNanos))
        println("B1_JFR_STOP_SECONDS=" + formatSeconds(finalMeasurement.recordingStopNanos))
        println("B1_JFR_DUMP_SECONDS=" + formatSeconds(finalMeasurement.recordingDumpNanos))
        println("B1_SERIALIZATION_SECONDS=" + formatSeconds(serializationNanos))
        println("B1_JSON_WRITE_SECONDS=" + formatSeconds(jsonWriteNanos))
        check(finalMeasurement.status == "PASS") {
            "B1 profiling workload failed: " + finalMeasurement.status
        }
    } finally {
        finishB1Characterization(
            session = characterizationSession,
            instrumentation = bytecodeInstrumentation,
        ) { snapshot ->
            val characterizationPath = outputDir.resolve("observation-duplication-" + stem + ".json")
            Files.writeString(
                characterizationPath,
                b1Json.encodeToString(B1ObservationProbe.Snapshot.serializer(), snapshot),
            )
            println("B1_OBSERVATION_CHARACTERIZATION_PATH=" + characterizationPath)
        }
    }
}

private fun openJfrRecording(): Recording? =
    try {
        Recording(Configuration.getConfiguration("profile")).also { recording ->
            recording.name = "argentum-b1-performance-baseline"
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10))
            recording.start()
        }
    } catch (failure: Exception) {
        println("B1_JFR=NOT_RUN reason=" + (failure.message ?: failure::class.simpleName))
        null
    }

private fun runWorkload(specs: List<EpisodeSpec>, mode: String): WorkloadMeasurement {
    val baseline = PassMetrics("baseline")
    val captureTraces = mutableListOf<CapturedEpisode>()
    val baselineJvmBefore = JvmSnapshot.capture()
    val baselineStart = System.nanoTime()
    runPass(
        specs = specs,
        pass = baseline,
        capture = mode == "replay",
        replayTraces = captureTraces,
    )
    baseline.wallNanos = System.nanoTime() - baselineStart
    val baselineJvmAfter = JvmSnapshot.capture()

    var replayJvm: PassJvmMeasurement? = null
    val replay = if (mode == "replay") {
        check(captureTraces.size == specs.size) {
            "Replay capture produced " + captureTraces.size + " traces for " + specs.size + " episodes"
        }
        val verifyPass = PassMetrics("replay-verify")
        val replayJvmBefore = JvmSnapshot.capture()
        val verifyStart = System.nanoTime()
        runReplayPass(captureTraces, verifyPass)
        verifyPass.wallNanos = System.nanoTime() - verifyStart
        val replayJvmAfter = JvmSnapshot.capture()
        replayJvm = PassJvmMeasurement(replayJvmBefore, replayJvmAfter)
        verifyPass
    } else {
        null
    }

    return WorkloadMeasurement(
        baseline = baseline,
        replay = replay,
        baselineJvm = PassJvmMeasurement(baselineJvmBefore, baselineJvmAfter),
        replayJvm = replayJvm,
        status = "PASS",
    )
}

private fun runPass(
    specs: List<EpisodeSpec>,
    pass: PassMetrics,
    capture: Boolean,
    replayTraces: MutableList<CapturedEpisode>,
) {
    val setupStart = System.nanoTime()
    val service = MultiEnvService(b1Registry())
    pass.serviceSetupNanos += System.nanoTime() - setupStart
    try {
        for (spec in specs) {
            val trace = runEpisode(
                service = service,
                spec = spec,
                pass = pass,
                capture = capture,
            )
            if (trace != null) replayTraces += trace
        }
    } finally {
        val cleanupStart = System.nanoTime()
        service.dispose(service.listEnvs())
        pass.cleanupNanos += System.nanoTime() - cleanupStart
    }
}

private fun runReplayPass(
    traces: List<CapturedEpisode>,
    pass: PassMetrics,
) {
    val setupStart = System.nanoTime()
    val service = MultiEnvService(b1Registry())
    pass.serviceSetupNanos += System.nanoTime() - setupStart
    try {
        traces.forEach { trace ->
            runReplayEpisode(service, trace, pass)
        }
    } finally {
        val cleanupStart = System.nanoTime()
        service.dispose(service.listEnvs())
        pass.cleanupNanos += System.nanoTime() - cleanupStart
    }
}

private fun runEpisode(
    service: MultiEnvService,
    spec: EpisodeSpec,
    pass: PassMetrics,
    capture: Boolean,
): CapturedEpisode? {
    val trace = if (capture) CapturedEpisode(spec) else null
    var envId: EnvId? = null
    var episodeTransitions = 0
    try {
        val resetStart = System.nanoTime()
        val created = service.create(spec.config())
        pass.resetNanos += System.nanoTime() - resetStart
        envId = created.envId
        var observation = requireObservation(service, created.envId, created.observation, pass)
        trace?.recordFrame(pass, observation)

        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(spec.policySeed())
        while (!observation.terminated && !observation.truncated) {
            check(episodeTransitions < B1_MAX_STEPS) {
                "Episode exceeded maxSteps=" + B1_MAX_STEPS + " for " + spec.label
            }
            if (observation.pendingDecision == null && observation.legalActions.isEmpty()) {
                error("Nonterminal observation published no externally selectable actions for " + spec.label)
            }

            pass.semanticDecisions++
            val policyStart = System.nanoTime()
            val choice = policy.choose(observation, policyState)
            pass.policyNanos += System.nanoTime() - policyStart
            policyState = policyState.afterChoice()

            when (choice) {
                is SemanticChoice.Gap -> error(
                    "Policy gap at " + spec.label + " transition " + episodeTransitions +
                        ": " + choice.code + " " + choice.reason,
                )
                is SemanticChoice.Action -> {
                    val selected = observation.legalActions.singleOrNull {
                        it.actionId == choice.actionId
                    } ?: error("Policy selected an action outside the current public list")
                    trace?.let { captured ->
                        val publicSemanticKey = semanticActionKey(selected)
                        val ordinal = semanticActionOrdinal(observation, selected, publicSemanticKey)
                        captured.choices += CapturedChoice.Action(
                            publicSemanticKey,
                            ordinal,
                            choice.kind,
                            choice.payload,
                        )
                    }
                    val result = timedTransition(pass) {
                        service.step(
                            StepRequest(
                                envId = envId!!,
                                actionId = choice.actionId,
                                action = choice.payload,
                            ),
                        )
                    }
                    pass.transitions++
                    episodeTransitions++
                    pass.engineProgress++
                    observation = requireObservation(service, envId!!, result, pass)
                    trace?.recordFrame(pass, observation)
                }
                is SemanticChoice.Structured -> {
                    val pending = observation.pendingDecision
                        ?: error("Structured policy choice had no pending decision")
                    val decisionId = pending.decisionId
                        ?: error("Structured policy choice had no decision ID")
                    trace?.choices?.add(
                        CapturedChoice.Structured(pending.kind.name, choice.selection),
                    )
                    val result = timedTransition(pass) {
                        service.submitDecision(
                            envId = envId!!,
                            response = toDecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        )
                    }
                    pass.transitions++
                    episodeTransitions++
                    pass.engineProgress++
                    observation = requireObservation(service, envId!!, result, pass)
                    trace?.recordFrame(pass, observation)
                }
            }
        }

        val outcome = EpisodeOutcome(
            label = spec.label,
            transitions = episodeTransitions,
            terminal = observation.terminated,
            truncated = observation.truncated,
        )
        pass.outcomes += outcome
        trace?.terminal = observation.terminated
        trace?.truncated = observation.truncated
        trace?.transitions = outcome.transitions
        return trace
    } finally {
        envId?.let { id ->
            val cleanupStart = System.nanoTime()
            service.dispose(listOf(id))
            pass.cleanupNanos += System.nanoTime() - cleanupStart
        }
    }
}

private fun runReplayEpisode(
    service: MultiEnvService,
    trace: CapturedEpisode,
    pass: PassMetrics,
) {
    var envId: EnvId? = null
    var episodeTransitions = 0
    try {
        val resetStart = System.nanoTime()
        val created = service.create(trace.spec.config())
        pass.resetNanos += System.nanoTime() - resetStart
        envId = created.envId
        var observation = requireObservation(service, created.envId, created.observation, pass)
        verifyFrame(pass, observation, trace.frames.firstOrNull())

        var choiceIndex = 0
        while (choiceIndex < trace.choices.size) {
            check(!observation.terminated && !observation.truncated) {
                "Replay reached terminal state before choice " + choiceIndex + " for " + trace.spec.label
            }
            val choice = trace.choices[choiceIndex]
            pass.semanticDecisions++
            when (choice) {
                is CapturedChoice.Action -> {
                    val candidates = externallySelectableActions(observation)
                    val matching = candidates.filter { semanticActionKey(it) == choice.semanticKey }
                    val selected = matching.getOrNull(choice.semanticOrdinal)
                        ?: error(
                            "Replay action semantic key was not found at choice " + choiceIndex +
                                " for " + trace.spec.label,
                        )
                    val result = timedTransition(pass) {
                        service.step(
                            StepRequest(
                                envId = envId!!,
                                actionId = selected.actionId,
                                action = choice.payload,
                            ),
                        )
                    }
                    pass.transitions++
                    episodeTransitions++
                    pass.engineProgress++
                    observation = requireObservation(service, envId!!, result, pass)
                    verifyFrame(pass, observation, trace.frames.getOrNull(choiceIndex + 1))
                }
                is CapturedChoice.Structured -> {
                    val pending = observation.pendingDecision
                        ?: error("Replay structured choice had no pending decision")
                    check(pending.kind.name == choice.family) {
                        "Replay decision family changed at choice " + choiceIndex
                    }
                    val decisionId = pending.decisionId
                        ?: error("Replay structured decision has no decision ID")
                    val result = timedTransition(pass) {
                        service.submitDecision(
                            envId = envId!!,
                            response = toDecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        )
                    }
                    pass.transitions++
                    episodeTransitions++
                    pass.engineProgress++
                    observation = requireObservation(service, envId!!, result, pass)
                    verifyFrame(pass, observation, trace.frames.getOrNull(choiceIndex + 1))
                }
            }
            choiceIndex++
        }

        check(observation.terminated == trace.terminal && observation.truncated == trace.truncated) {
            "Replay terminal status changed for " + trace.spec.label
        }
        check(episodeTransitions == trace.transitions) {
            "Replay transition count changed for " + trace.spec.label
        }
        pass.outcomes += EpisodeOutcome(
            label = trace.spec.label,
            transitions = trace.transitions,
            terminal = trace.terminal,
            truncated = trace.truncated,
        )
    } finally {
        envId?.let { id ->
            val cleanupStart = System.nanoTime()
            service.dispose(listOf(id))
            pass.cleanupNanos += System.nanoTime() - cleanupStart
        }
    }
}

private fun requireObservation(
    service: MultiEnvService,
    envId: EnvId,
    result: ObservationResult,
    pass: PassMetrics,
): TrainingObservation {
    check(result.diagnostics.isEmpty()) {
        "Public observation carried diagnostics: " + result.diagnostics
    }
    val observation = result.observation as? TrainingObservation
        ?: error("B1 profiler requires TrainingObservation")
    val diagnosticStart = System.nanoTime()
    val diagnostics = service.diagnostics(envId)
    pass.diagnosticsNanos += System.nanoTime() - diagnosticStart
    check(diagnostics.events.isEmpty()) {
        "Trusted episode emitted diagnostics: " + diagnostics.events
    }
    pass.recordObservation(observation)
    return observation
}

private fun timedTransition(
    pass: PassMetrics,
    block: () -> ObservationResult,
): ObservationResult {
    val start = System.nanoTime()
    return try {
        block()
    } finally {
        pass.transitionAndObservationNanos += System.nanoTime() - start
    }
}

private fun verifyFrame(
    pass: PassMetrics,
    observation: TrainingObservation,
    expected: CapturedFrame?,
) {
    check(expected != null) { "Replay produced more frames than capture" }
    val start = System.nanoTime()
    val semantic = ObservationCanonicalizer.semanticJson(observation)
    val digest = StateDigest.compute(observation)
    pass.canonicalizationNanos += System.nanoTime() - start
    check(semantic == expected.semantic) { "Replay semantic frame mismatch" }
    check(digest == expected.digest) { "Replay state digest mismatch" }
}

private fun CapturedEpisode.recordFrame(
    pass: PassMetrics,
    observation: TrainingObservation,
) {
    val start = System.nanoTime()
    val semantic = ObservationCanonicalizer.semanticJson(observation)
    val digest = StateDigest.compute(observation)
    pass.canonicalizationNanos += System.nanoTime() - start
    frames += CapturedFrame(semantic, digest)
}

private fun toDecisionResponse(
    decisionId: String,
    selection: SemanticDecision,
): com.wingedsheep.engine.core.DecisionResponse = when (selection) {
    is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
    is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
    is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Distribution -> DistributionResponse(decisionId, selection.selected)
    is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
    is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
    is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Replacement ->
        ReplacementChosenResponse(decisionId, selection.from, selection.to)
    is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
    is SemanticDecision.Damage -> CombatResolutionResponse(
        decisionId = decisionId,
        edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
    )
}

private fun semanticActionOrdinal(
    observation: TrainingObservation,
    selected: com.wingedsheep.gym.contract.LegalActionView,
    semanticKey: String,
): Int {
    val ordinal = externallySelectableActions(observation)
        .filter { semanticActionKey(it) == semanticKey }
        .indexOfFirst { it.actionId == selected.actionId }
    check(ordinal >= 0) { "Selected public action was not semantically indexed" }
    return ordinal
}

private fun semanticActionKey(
    action: com.wingedsheep.gym.contract.LegalActionView,
): String = canonicalSemanticJson(
    ObservationCanonicalizer.semanticActionFingerprint(action),
)

private fun externallySelectableActions(
    observation: TrainingObservation,
): List<com.wingedsheep.gym.contract.LegalActionView> =
    observation.legalActions
        .filter { it.affordable || it.isDecisionOption }
        .sortedWith(
            compareBy(
                { if (it.kind.contains("Pass", ignoreCase = true) ||
                    it.description.contains("pass priority", ignoreCase = true)
                ) 1 else 0 },
                { it.kind },
                { canonicalSemanticJson(it.actionSemantics ?: JsonNull) },
                { it.sourceEntityId?.value ?: "" },
                { it.targetEntityIds.joinToString(",") { id -> id.value } },
            ),
        )

private fun canonicalSemanticJson(
    element: JsonElement,
    propertyName: String? = null,
): String = when (element) {
    is JsonObject -> JsonObject(
        element.entries
            .sortedBy { it.key }
            .associate { (key, value) ->
                key to Json.parseToJsonElement(canonicalSemanticJson(value, key))
            },
    ).toString()
    is JsonArray -> {
        val values = element.map { canonicalSemanticJson(it) }
        val ordered = if (propertyName in b1SemanticUnorderedArrayKeys) values.sorted() else values
        "[" + ordered.joinToString(",") + "]"
    }
    else -> element.toString()
}

private val b1SemanticUnorderedArrayKeys = setOf(
    "types",
    "subtypes",
    "colors",
    "keywords",
    "availableColors",
    "attachments",
    "targetEntityIds",
    "validSacrificeTargets",
    "candidates",
    "nonSelectableOptions",
    "matchingOptions",
    "availableSources",
    "waterbendPermanents",
    "producesColors",
    "sourceSubtypes",
    "sourceBuckets",
    "sourceColorBuckets",
    "certifiedFloatingBuckets",
    "blockedByIds",
    "blockedAttackerIds",
)

private fun EpisodeSpec.config(): EnvConfig {
    val akiri = readB1LockedDeck("akiri-v0.1.txt")
    val chevill = readB1LockedDeck("chevill-v0.1.txt")
    val decks = mapOf("Akiri" to akiri, "Chevill" to chevill)

    fun player(name: String): PlayerSpec {
        val deck = decks.getValue(name)
        return PlayerSpec(
            name = name,
            deck = DeckSpec.Explicit(deck.cards.drop(1).groupingBy { it }.eachCount()),
            startingLife = 40,
            commanderCardName = deck.commander,
        )
    }

    return EnvConfig(
        players = listOf(player(seat0), player(seat1)),
        format = Format.Commander(),
        startingHandSize = 7,
        skipMulligans = true,
        useHandSmoother = false,
        startingPlayerIndex = startingPlayerIndex,
        seed = seed,
        maxSteps = B1_MAX_STEPS,
        perspectivePlayerIndex = 0,
    )
}

private fun b1Registry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun readB1LockedDeck(fileName: String): B1LockedDeck {
    val path = repositoryRootForB1()
        .resolve("docs")
        .resolve("ml")
        .resolve("curriculum")
        .resolve(fileName)
    val cards = Files.readAllLines(path)
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }
    return B1LockedDeck(cards.first(), cards)
}

private fun repositoryRootForB1(): Path {
    val workingDirectory = Path.of(System.getProperty("user.dir"))
    return generateSequence(workingDirectory) { it.parent }
        .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }
}

private data class EpisodeSpec(
    val seed: Long,
    val startingPlayerIndex: Int,
    val seat0: String,
    val seat1: String,
) {
    val label: String
        get() = seat0 + "-vs-" + seat1 + "-seed-" + seed + "-start-" + startingPlayerIndex

    fun policySeed(): Long {
        val roster = if (seat0 == "Akiri") 0x41L else 0x43L
        return seed * 1_000_003L +
            startingPlayerIndex * 97_409L +
            roster * 65_537L
    }
}

private data class B1LockedDeck(
    val commander: String,
    val cards: List<String>,
)

private sealed interface CapturedChoice {
    data class Action(
        val semanticKey: String,
        val semanticOrdinal: Int,
        val kind: String,
        val payload: JsonObject?,
    ) : CapturedChoice

    data class Structured(
        val family: String,
        val selection: SemanticDecision,
    ) : CapturedChoice
}

private data class CapturedFrame(
    val semantic: String,
    val digest: String,
)

private class CapturedEpisode(
    val spec: EpisodeSpec,
    val frames: MutableList<CapturedFrame> = mutableListOf(),
    val choices: MutableList<CapturedChoice> = mutableListOf(),
    var terminal: Boolean = false,
    var truncated: Boolean = false,
    var transitions: Int = 0,
)

@Serializable
private data class EpisodeOutcome(
    val label: String,
    val transitions: Int,
    val terminal: Boolean,
    val truncated: Boolean,
)

private class PassMetrics(
    val name: String,
) {
    var wallNanos: Long = 0
    var serviceSetupNanos: Long = 0
    var resetNanos: Long = 0
    var policyNanos: Long = 0
    var transitionAndObservationNanos: Long = 0
    var diagnosticsNanos: Long = 0
    var canonicalizationNanos: Long = 0
    var cleanupNanos: Long = 0
    var semanticDecisions: Long = 0
    var transitions: Long = 0
    var engineProgress: Long = 0
    var observations: Long = 0
    var legalCandidateCount: Long = 0
    var structuredDecisionCount: Long = 0
    var visibleCardCount: Long = 0
    var stackItemCount: Long = 0
    var heapPeakBytes: Long = 0
    val actionKinds = sortedMapOf<String, Long>()
    val decisionFamilies = sortedMapOf<String, Long>()
    val outcomes = mutableListOf<EpisodeOutcome>()

    fun recordObservation(observation: TrainingObservation) {
        observations++
        legalCandidateCount += observation.legalActions.size
        structuredDecisionCount += if (
            observation.pendingDecision?.requiresStructuredResponse == true
        ) 1 else 0
        visibleCardCount += observation.zones.sumOf { it.cards.size.toLong() }
        stackItemCount += observation.stack.size
        val family = observation.pendingDecision?.kind?.name ?: "PRIORITY"
        decisionFamilies[family] = (decisionFamilies[family] ?: 0L) + 1L
        observation.legalActions.forEach { action ->
            actionKinds[action.kind] = (actionKinds[action.kind] ?: 0L) + 1L
        }
        if (observations % HEAP_SAMPLE_INTERVAL == 0L) {
            heapPeakBytes = max(heapPeakBytes, currentHeapUsed())
        }
    }

    private companion object {
        const val HEAP_SAMPLE_INTERVAL = 64L
    }

}

private data class WorkloadMeasurement(
    val baseline: PassMetrics,
    val replay: PassMetrics?,
    val baselineJvm: PassJvmMeasurement,
    val replayJvm: PassJvmMeasurement?,
    val status: String,
) {
    fun toMetrics(
        workload: String,
        mode: String,
        workloadWallNanos: Long,
        countersBefore: JvmSnapshot,
        countersAfter: JvmSnapshot,
        recordingStopNanos: Long,
        recordingDumpNanos: Long,
        jfrPath: String?,
    ): BaselineMetrics {
        return BaselineMetrics(
            baseHead = B1_BASE_HEAD,
            profileWorkload = workload,
            mode = mode,
            maxSteps = B1_MAX_STEPS,
            status = status,
            episodes = baseline.outcomes.size,
            transitions = baseline.transitions,
            semanticDecisions = baseline.semanticDecisions,
            externalTransitions = baseline.transitions,
            engineProgress = baseline.engineProgress,
            workloadWallNanos = workloadWallNanos,
            baselineWallNanos = baseline.wallNanos,
            replayVerificationWallNanos = replay?.wallNanos ?: 0,
            baselinePass = baseline.toSnapshot(),
            replayPass = replay?.toSnapshot(),
            cpu = CpuMetrics.from(countersBefore, countersAfter, workloadWallNanos),
            allocation = baselineJvm.allocation(baseline.transitions),
            replayAllocation = replay?.let { pass ->
                replayJvm?.allocation(pass.transitions)
            },
            recordingStopNanos = recordingStopNanos,
            recordingDumpNanos = recordingDumpNanos,
            memory = MemoryMetrics(
                heapUsedStartBytes = countersBefore.heapUsedBytes,
                heapUsedEndBytes = countersAfter.heapUsedBytes,
                heapPeakBytes = max(
                    countersBefore.heapUsedBytes,
                    max(baseline.heapPeakBytes, replay?.heapPeakBytes ?: 0),
                ),
            ),
            gc = gcDelta(countersBefore, countersAfter),
            baselineGc = baselineJvm.gcDelta(),
            replayGc = replayJvm?.gcDelta(),
            jfrPath = jfrPath,
        )
    }
}

private data class PassJvmMeasurement(
    val before: JvmSnapshot,
    val after: JvmSnapshot,
) {
    fun allocation(transitions: Long): AllocationMetrics =
        AllocationMetrics.from(before, after, transitions)

    fun gcDelta(): Map<String, GcDelta> = gcDelta(before, after)
}

private fun gcDelta(
    before: JvmSnapshot,
    after: JvmSnapshot,
): Map<String, GcDelta> = after.gc.mapValues { (name, afterGc) ->
    val beforeGc = before.gc[name] ?: GcSnapshot(0, 0)
    GcDelta(
        collectionCount = delta(afterGc.collectionCount, beforeGc.collectionCount),
        collectionTimeMillis = delta(afterGc.collectionTimeMillis, beforeGc.collectionTimeMillis),
    )
}

@Serializable
private data class BaselineMetrics(
    val baseHead: String,
    val profileWorkload: String,
    val mode: String,
    val maxSteps: Int,
    val status: String,
    val episodes: Int,
    val transitions: Long,
    val semanticDecisions: Long,
    val externalTransitions: Long,
    val engineProgress: Long,
    val workloadWallNanos: Long,
    val baselineWallNanos: Long,
    val replayVerificationWallNanos: Long,
    val baselinePass: PassSnapshot,
    val replayPass: PassSnapshot? = null,
    val cpu: CpuMetrics,
    val allocation: AllocationMetrics,
    val replayAllocation: AllocationMetrics? = null,
    val recordingStopNanos: Long = 0,
    val recordingDumpNanos: Long = 0,
    val memory: MemoryMetrics,
    val gc: Map<String, GcDelta>,
    val baselineGc: Map<String, GcDelta> = emptyMap(),
    val replayGc: Map<String, GcDelta>? = null,
    val jfrPath: String? = null,
)

@Serializable
private data class PassSnapshot(
    val name: String,
    val wallNanos: Long,
    val serviceSetupNanos: Long,
    val resetNanos: Long,
    val policyNanos: Long,
    val transitionAndObservationNanos: Long,
    val diagnosticsNanos: Long,
    val canonicalizationNanos: Long,
    val cleanupNanos: Long,
    val semanticDecisions: Long,
    val transitions: Long,
    val engineProgress: Long,
    val observations: Long,
    val legalCandidateCount: Long,
    val structuredDecisionCount: Long,
    val visibleCardCount: Long,
    val stackItemCount: Long,
    val heapPeakBytes: Long,
    val actionKinds: Map<String, Long>,
    val decisionFamilies: Map<String, Long>,
    val outcomes: List<EpisodeOutcome>,
)

private fun PassMetrics.toSnapshot(): PassSnapshot = PassSnapshot(
    name = name,
    wallNanos = wallNanos,
    serviceSetupNanos = serviceSetupNanos,
    resetNanos = resetNanos,
    policyNanos = policyNanos,
    transitionAndObservationNanos = transitionAndObservationNanos,
    diagnosticsNanos = diagnosticsNanos,
    canonicalizationNanos = canonicalizationNanos,
    cleanupNanos = cleanupNanos,
    semanticDecisions = semanticDecisions,
    transitions = transitions,
    engineProgress = engineProgress,
    observations = observations,
    legalCandidateCount = legalCandidateCount,
    structuredDecisionCount = structuredDecisionCount,
    visibleCardCount = visibleCardCount,
    stackItemCount = stackItemCount,
    heapPeakBytes = heapPeakBytes,
    actionKinds = actionKinds,
    decisionFamilies = decisionFamilies,
    outcomes = outcomes,
)

@Serializable
private data class CpuMetrics(
    val processCpuNanos: Long,
    val oneCoreUtilizationPercent: Double? = null,
    val hostUtilizationPercent: Double? = null,
) {
    companion object {
        fun from(
            before: JvmSnapshot,
            after: JvmSnapshot,
            wallNanos: Long,
        ): CpuMetrics {
            val cpu = delta(after.processCpuNanos, before.processCpuNanos)
            if (cpu < 0 || wallNanos <= 0) return CpuMetrics(cpu)
            val oneCore = cpu.toDouble() * 100.0 / wallNanos.toDouble()
            val host = oneCore / Runtime.getRuntime().availableProcessors().toDouble()
            return CpuMetrics(cpu, oneCore, host)
        }
    }
}

@Serializable
private data class AllocationMetrics(
    val threadAllocatedBytes: Long,
    val bytesPerExternalTransition: Double? = null,
) {
    companion object {
        fun from(before: JvmSnapshot, after: JvmSnapshot, transitions: Long): AllocationMetrics {
            val allocated = delta(after.threadAllocatedBytes, before.threadAllocatedBytes)
            return AllocationMetrics(
                threadAllocatedBytes = allocated,
                bytesPerExternalTransition = if (allocated >= 0 && transitions > 0) {
                    allocated.toDouble() / transitions.toDouble()
                } else {
                    null
                },
            )
        }
    }
}

@Serializable
private data class MemoryMetrics(
    val heapUsedStartBytes: Long,
    val heapUsedEndBytes: Long,
    val heapPeakBytes: Long,
)

@Serializable
private data class GcDelta(
    val collectionCount: Long,
    val collectionTimeMillis: Long,
)

private data class JvmSnapshot(
    val processCpuNanos: Long,
    val threadAllocatedBytes: Long,
    val heapUsedBytes: Long,
    val gc: Map<String, GcSnapshot>,
) {
    companion object {
        fun capture(): JvmSnapshot {
            val os = ManagementFactory.getOperatingSystemMXBean() as?
                com.sun.management.OperatingSystemMXBean
            val thread = ManagementFactory.getThreadMXBean() as?
                com.sun.management.ThreadMXBean
            val allocated = if (thread?.isThreadAllocatedMemorySupported == true) {
                try {
                    if (!thread.isThreadAllocatedMemoryEnabled) {
                        thread.isThreadAllocatedMemoryEnabled = true
                    }
                    thread.getThreadAllocatedBytes(Thread.currentThread().id)
                } catch (_: Exception) {
                    -1L
                }
            } else {
                -1L
            }
            return JvmSnapshot(
                processCpuNanos = os?.processCpuTime ?: -1L,
                threadAllocatedBytes = allocated,
                heapUsedBytes = currentHeapUsed(),
                gc = ManagementFactory.getGarbageCollectorMXBeans()
                    .associate { bean -> bean.name to GcSnapshot(
                        bean.collectionCount,
                        bean.collectionTime,
                    ) },
            )
        }
    }
}

private data class GcSnapshot(
    val collectionCount: Long,
    val collectionTimeMillis: Long,
)

private fun currentHeapUsed(): Long =
    ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

private fun delta(after: Long, before: Long): Long =
    if (after < 0 || before < 0) -1L else after - before

private fun formatSeconds(nanos: Long): String =
    "%.3f".format(nanos / 1_000_000_000.0)
