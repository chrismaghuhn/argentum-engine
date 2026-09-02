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
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.EnvId
import com.wingedsheep.gym.service.EnvWorkerPool
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.StepRequest
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration.Companion.hours

private const val B1_SCALING_BASE_ORIGIN_MAIN = "f50c0c92249fe7d5c2f7b8044b1371462630135e"
private const val B1_SCALING_ACCEPTED_HEAD = "9140a56be0c93e9b0cdbbb43f1a39ea88d6a67cf"
private const val B1_SCALING_MAX_STEPS = 2_000
private const val B1_SCALING_EPISODES = 8
private const val B1_SCALING_DEFAULT_REPETITIONS = 3
private const val B1_SCALING_DEFAULT_WARMUP_STEPS = 256
private const val B1_SCALING_MEMORY_STABILIZATION_MILLIS = 100L

private val b1ScalingJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

private val b1ScalingCorpus = (0L..3L).flatMap { seed ->
    listOf(
        ScalingEpisodeSpec(seed, 0, "Akiri", "Chevill"),
        ScalingEpisodeSpec(seed, 1, "Akiri", "Chevill"),
    )
}

/**
 * Opt-in test-only B1 scaling measurement. It is disabled unless `b1.scaling=true` is forwarded
 * to the test worker. The benchmark uses one service and one owner task per independent env; it
 * never changes production scheduling or Rules semantics.
 */
class B1ScalingMeasurementTest : FunSpec({
    val enabled = System.getProperty("b1.scaling") == "true"

    test("writes B1 1-2-4-8 environment scaling measurements").config(
        enabled = enabled,
        timeout = 4.hours,
    ) {
        runB1ScalingMeasurement()
    }
})

/**
 * Opt-in, test-only isolation characterization. It compares compact public semantic traces
 * while exercising both [MultiEnvService.stepBatch] and deliberately different operation
 * interleavings. No GameState or TrainingObservation is retained after a frame is reduced to a
 * digest/count signature.
 */
class B1MultiEnvIsolationTest : FunSpec({
    val enabled = System.getProperty("b1.scaling.isolation") == "true"

    test("proves multi-environment semantic and lifecycle isolation").config(
        enabled = enabled,
        timeout = 4.hours,
    ) {
        runB1MultiEnvIsolation()
    }
})

private fun runB1MultiEnvIsolation() {
    val outputDir = Path.of(
        System.getProperty(
            "b1.scaling.isolationOutputDir",
            Path.of(System.getProperty("user.dir"), "build", "reports", "b1-scaling-isolation").toString(),
        ),
    )
    Files.createDirectories(outputDir)
    val traceDir = outputDir.resolve("reference-traces")
    Files.createDirectories(traceDir)
    val registry = b1ScalingRegistry()
    val referenceStore = IsolationReferenceStore(traceDir)
    val scenarios = mutableListOf<IsolationScenarioReport>()

    scenarios += runIsolationScenario(
        name = "single-env-reference",
        environmentCount = 1,
        schedule = IsolationSchedule.SINGLE_REFERENCE,
        registry = registry,
        referenceStore = referenceStore,
        referenceMode = true,
    )
    referenceStore.markReady()
    listOf(2, 4, 8).forEach { environmentCount ->
        scenarios += runIsolationScenario(
            name = "parallel-${environmentCount}",
            environmentCount = environmentCount,
            schedule = IsolationSchedule.NATURAL_BATCH,
            registry = registry,
            referenceStore = referenceStore,
            referenceMode = false,
        )
    }
    scenarios += runIsolationScenario(
        name = "parallel-8-reverse-batch",
        environmentCount = 8,
        schedule = IsolationSchedule.REVERSE_BATCH,
        registry = registry,
        referenceStore = referenceStore,
        referenceMode = false,
    )
    scenarios += runIsolationScenario(
        name = "sequential-8-rotating-interleave",
        environmentCount = 8,
        schedule = IsolationSchedule.ROTATING_SEQUENTIAL,
        registry = registry,
        referenceStore = referenceStore,
        referenceMode = false,
    )

    val lifecycle = runResetDisposeIsolation(registry)
    val report = B1MultiEnvIsolationReport(
        benchmarkSchemaVersion = "argentum-b1-multi-env-isolation-v1",
        sourceHead = B1_SCALING_ACCEPTED_HEAD,
        corpusEpisodes = B1_SCALING_EPISODES,
        corpusExternalTransitions = B1_SCALING_EPISODES.toLong() * B1_SCALING_MAX_STEPS,
        scenarios = scenarios,
        resetDispose = lifecycle,
        actionHandleIsolation = lifecycle.actionHandleIsolation,
        decisionHandleIsolation = lifecycle.decisionHandleIsolation,
        registryIsolation = lifecycle.registryIsolation,
        hiddenInformationIsolation = lifecycle.hiddenInformationIsolation,
        status = "PASS",
        dataTrusted = "NO",
    )
    val reportPath = outputDir.resolve("b1-multi-env-isolation.json")
    Files.writeString(reportPath, b1ScalingJson.encodeToString(B1MultiEnvIsolationReport.serializer(), report))
    println("B1_MULTI_ENV_ISOLATION_PATH=" + reportPath)
    println("B1_MULTI_ENV_OBSERVATION_ISOLATION=PASS")
    scenarios.forEach { scenario ->
        println(
            "B1_MULTI_ENV_ISOLATION_ROW=" + scenario.name +
                " envs=" + scenario.environmentCount +
                " stepBatchCalls=" + scenario.stepBatchCalls +
                " trajectory=" + scenario.semanticTrajectory,
        )
    }
}

private fun runIsolationScenario(
    name: String,
    environmentCount: Int,
    schedule: IsolationSchedule,
    registry: CardRegistry,
    referenceStore: IsolationReferenceStore,
    referenceMode: Boolean,
): IsolationScenarioReport {
    val assignments = Array(environmentCount) { slotIndex ->
        b1ScalingCorpus.filterIndexed { episodeIndex, _ -> episodeIndex % environmentCount == slotIndex }
    }
    val service = MultiEnvService(
        cardRegistry = registry,
        workerPool = EnvWorkerPool(environmentCount),
    )
    val slots = assignments.map { specs ->
        require(specs.isNotEmpty()) { "Every isolation environment needs an episode" }
        val created = service.create(specs.first().config())
        ScalingSlot(created.envId, trainingObservation(created.observation))
    }
    val counters = IsolationExecutionCounters()
    val trajectories = linkedMapOf<String, IsolationTrajectoryAccumulator>()
    var observations = 0L
    var publicLegalCandidates = 0L
    var structuredDecisionObservations = 0L
    var transitions = 0L
    var episodes = 0
    try {
        val rounds = assignments.maxOf { it.size }
        for (round in 0 until rounds) {
            val activeSlots = slots.indices.filter { round < assignments[it].size }
            val specs = activeSlots.associateWith { slotIndex -> assignments[slotIndex][round] }
            val resetResults = isolationParallel(
                service = service,
                slotIndexes = activeSlots,
                tracker = counters.tracker,
            ) { slotIndex ->
                service.reset(slots[slotIndex].envId, specs.getValue(slotIndex).config())
            }
            counters.resetBatches++
            resetResults.forEach { (slotIndex, result) ->
                val observation = isolationObservation(service, slots[slotIndex].envId, result)
                val spec = specs.getValue(slotIndex)
                slots[slotIndex].observation = observation
                slots[slotIndex].spec = spec
                slots[slotIndex].policyState = DeterministicPolicyState(spec.policySeed())
                slots[slotIndex].transitions = 0
                observations++
                publicLegalCandidates += observation.legalActions.size
                if (observation.pendingDecision?.requiresStructuredResponse == true) {
                    structuredDecisionObservations++
                }
                trajectories[spec.label] = IsolationTrajectoryAccumulator(
                    label = spec.label,
                    trace = referenceStore.open(spec.label, referenceMode),
                ).also { it.recordObservation(observation) }
            }

            var interleaveRound = 0
            while (activeSlots.any { slotIndex ->
                    !slots[slotIndex].observation.terminated && !slots[slotIndex].observation.truncated
                }) {
                val liveSlots = activeSlots.filter { slotIndex ->
                    !slots[slotIndex].observation.terminated && !slots[slotIndex].observation.truncated
                }
                val policy = DeterministicExternalPolicy()
                val choices = liveSlots.map { slotIndex ->
                    val slot = slots[slotIndex]
                    val choice = policy.choose(slot.observation, slot.policyState)
                    check(choice !is SemanticChoice.Gap) {
                        "Isolation policy gap at scenario=$name slot=$slotIndex " +
                            "transition=${slot.transitions}: $choice"
                    }
                    trajectories.getValue(slot.spec.label).recordChoice(choice)
                    slot.policyState = slot.policyState.afterChoice()
                    choice
                }

                if (schedule == IsolationSchedule.ROTATING_SEQUENTIAL) {
                    val order = rotated(liveSlots, interleaveRound++)
                    order.forEach { slotIndex ->
                        val choice = choices[liveSlots.indexOf(slotIndex)]
                        val result = executeChoice(
                            service = service,
                            slot = slots[slotIndex],
                            observation = slots[slotIndex].observation,
                            choice = choice,
                        )
                        val observation = isolationObservation(service, slots[slotIndex].envId, result)
                        slots[slotIndex].observation = observation
                        slots[slotIndex].transitions++
                        transitions++
                        observations++
                        publicLegalCandidates += observation.legalActions.size
                        if (observation.pendingDecision?.requiresStructuredResponse == true) {
                            structuredDecisionObservations++
                        }
                        trajectories.getValue(slots[slotIndex].spec.label).recordObservation(observation)
                    }
                } else {
                    val order = if (schedule == IsolationSchedule.REVERSE_BATCH) {
                        liveSlots.asReversed()
                    } else {
                        liveSlots
                    }
                    val results = executeIsolationBatch(
                        service = service,
                        slots = slots,
                        liveSlots = liveSlots,
                        choices = choices,
                        order = order,
                        counters = counters,
                    )
                    results.forEach { (slotIndex, result) ->
                        val observation = isolationObservation(service, slots[slotIndex].envId, result)
                        slots[slotIndex].observation = observation
                        slots[slotIndex].transitions++
                        transitions++
                        observations++
                        publicLegalCandidates += observation.legalActions.size
                        if (observation.pendingDecision?.requiresStructuredResponse == true) {
                            structuredDecisionObservations++
                        }
                        trajectories.getValue(slots[slotIndex].spec.label).recordObservation(observation)
                    }
                }
            }

            activeSlots.forEach { slotIndex ->
                val slot = slots[slotIndex]
                check(slot.observation.terminated || slot.observation.truncated) {
                    "Isolation episode did not finish: scenario=$name label=${slot.spec.label}"
                }
                check(slot.transitions <= B1_SCALING_MAX_STEPS) {
                    "Isolation episode exceeded maxSteps: scenario=$name label=${slot.spec.label}"
                }
                trajectories.getValue(slot.spec.label).finish(slot.observation)
                episodes++
            }
        }
        check(episodes == B1_SCALING_EPISODES) {
            "Expected $B1_SCALING_EPISODES isolation episodes, got $episodes"
        }
        check(transitions == B1_SCALING_EPISODES.toLong() * B1_SCALING_MAX_STEPS) {
            "Expected exact 16,000 isolation transitions, got $transitions"
        }
        trajectories.values.forEach { it.close() }
        return IsolationScenarioReport(
            name = name,
            environmentCount = environmentCount,
            schedule = schedule.name,
            episodes = episodes,
            externalTransitions = transitions,
            observations = observations,
            publicLegalCandidates = publicLegalCandidates,
            structuredDecisionObservations = structuredDecisionObservations,
            stepBatchCalls = counters.stepBatchCalls,
            stepBatchRequests = counters.stepBatchRequests,
            workerPoolOperationBatches = counters.workerPoolOperationBatches,
            resetBatches = counters.resetBatches,
            actualConcurrency = counters.tracker.summary(),
            semanticTrajectory = "PASS",
        )
    } finally {
        trajectories.values.forEach { runCatching { it.close() } }
        service.dispose(service.listEnvs())
        service.workerPool.close()
    }
}

private fun executeIsolationBatch(
    service: MultiEnvService,
    slots: List<ScalingSlot>,
    liveSlots: List<Int>,
    choices: List<SemanticChoice>,
    order: List<Int>,
    counters: IsolationExecutionCounters,
): List<Pair<Int, ObservationResult>> {
    if (choices.all { it is SemanticChoice.Action }) {
        counters.stepBatchCalls++
        val requests = order.map { slotIndex ->
            val choice = choices[liveSlots.indexOf(slotIndex)] as SemanticChoice.Action
            StepRequest(
                envId = slots[slotIndex].envId,
                actionId = choice.actionId,
                action = choice.payload,
            )
        }
        counters.stepBatchRequests += requests.size
        return service.stepBatch(requests).map { (envId, result) ->
            val slotIndex = slots.indexOfFirst { it.envId == envId }
            check(slotIndex >= 0) { "stepBatch returned an unknown environment" }
            slotIndex to result
        }
    }

    counters.workerPoolOperationBatches++
    return service.workerPool.invokeAll(
        order.map { slotIndex ->
            Callable {
                counters.tracker.track {
                    slotIndex to executeChoice(
                        service = service,
                        slot = slots[slotIndex],
                        observation = slots[slotIndex].observation,
                        choice = choices[liveSlots.indexOf(slotIndex)],
                    )
                }
            }
        },
    )
}

private fun <T> isolationParallel(
    service: MultiEnvService,
    slotIndexes: List<Int>,
    tracker: ConcurrencyTracker,
    call: (Int) -> T,
): List<Pair<Int, T>> = service.workerPool.invokeAll(
    slotIndexes.map { slotIndex ->
        Callable {
            slotIndex to tracker.track { call(slotIndex) }
        }
    },
)

private fun isolationObservation(
    service: MultiEnvService,
    envId: EnvId,
    result: ObservationResult,
): TrainingObservation {
    check(result.diagnostics.isEmpty()) {
        "Isolation observation carried diagnostics for $envId: ${result.diagnostics}"
    }
    check(service.diagnostics(envId).events.isEmpty()) {
        "Isolation environment carried diagnostics for $envId"
    }
    return result.observation as? TrainingObservation
        ?: error("Isolation requires TrainingObservation")
}

private fun rotated(values: List<Int>, amount: Int): List<Int> {
    if (values.isEmpty()) return values
    val offset = amount % values.size
    return values.drop(offset) + values.take(offset)
}

private fun runResetDisposeIsolation(registry: CardRegistry): IsolationLifecycleReport {
    val service = MultiEnvService(registry, workerPool = EnvWorkerPool(2))
    val controlService = MultiEnvService(registry, workerPool = EnvWorkerPool(1))
    val specA = b1ScalingCorpus[0]
    val specB = b1ScalingCorpus[2]
    var envA: EnvId? = null
    var envB: EnvId? = null
    var controlEnv: EnvId? = null
    try {
        val createdA = service.create(specA.config())
        envA = createdA.envId
        val createdB = service.create(specB.config())
        envB = createdB.envId
        val createdControl = controlService.create(specB.config())
        controlEnv = createdControl.envId

        var observationA = isolationObservation(service, envA, createdA.observation)
        var observationB = isolationObservation(service, envB, createdB.observation)
        var controlObservation = isolationObservation(
            controlService,
            controlEnv,
            createdControl.observation,
        )
        check(lifecycleFrameSignature(observationB) == lifecycleFrameSignature(controlObservation)) {
            "Reset/dispose control did not start at the same public frame"
        }

        val policy = DeterministicExternalPolicy()
        var policyStateA = DeterministicPolicyState(specA.policySeed())
        val initialBActionMaximum = observationB.legalActions.maxOfOrNull { it.actionId } ?: -1
        var actionHandleIsolation = false
        var decisionHandleIsolation = false
        var actionProbeSteps = 0
        while ((!actionHandleIsolation || !decisionHandleIsolation) &&
            !observationA.terminated && !observationA.truncated && actionProbeSteps < 512
        ) {
            val choice = policy.choose(observationA, policyStateA)
            check(choice !is SemanticChoice.Gap) {
                "Lifecycle isolation policy gap while probing env A: $choice"
            }
            if (!actionHandleIsolation) {
                val foreignActionId = observationA.legalActions
                    .firstOrNull { it.actionId > initialBActionMaximum }
                    ?.actionId
                if (foreignActionId != null) {
                    val before = lifecycleFrameSignature(observationB)
                    val failure = runCatching {
                        service.step(StepRequest(envB, foreignActionId))
                    }.exceptionOrNull()
                    check(failure is IllegalArgumentException) {
                        "A action handle unexpectedly resolved in B: id=$foreignActionId failure=$failure"
                    }
                    val after = isolationObservation(service, envB, service.observe(envB))
                    check(lifecycleFrameSignature(after) == before) {
                        "Foreign action probe changed env B"
                    }
                    actionHandleIsolation = true
                }
            }
            if (!decisionHandleIsolation && observationA.pendingDecision?.requiresStructuredResponse == true) {
                val pending = checkNotNull(observationA.pendingDecision)
                val structured = choice as? SemanticChoice.Structured
                    ?: error("Isolation policy did not produce a structured choice for pending decision")
                val decisionId = pending.decisionId
                    ?: error("Isolation probe pending decision had no decision ID")
                val before = lifecycleFrameSignature(observationB)
                val failure = runCatching {
                    service.submitDecision(
                        envId = envB,
                        response = toScalingDecisionResponse(decisionId, structured.selection),
                        actorId = observationA.agentToAct,
                    )
                }.exceptionOrNull()
                check(failure is IllegalStateException) {
                    "A decision handle unexpectedly resolved in B: id=$decisionId failure=$failure"
                }
                val after = isolationObservation(service, envB, service.observe(envB))
                check(lifecycleFrameSignature(after) == before) {
                    "Foreign decision probe changed env B"
                }
                decisionHandleIsolation = true
            }

            observationA = isolationObservation(
                service,
                envA,
                executeChoice(service, ScalingSlot(envA, observationA), observationA, choice),
            )
            policyStateA = policyStateA.afterChoice()
            actionProbeSteps++
        }
        check(actionHandleIsolation) {
            "Could not obtain a post-generation action handle from env A for isolation probe"
        }
        check(decisionHandleIsolation) {
            "Could not obtain a structured decision handle from env A for isolation probe"
        }

        isolationObservation(service, envA, service.reset(envA, specA.config()))
        service.dispose(listOf(envA))
        check(!service.listEnvs().contains(envA)) { "Disposed env A remained registered" }
        check(service.listEnvs().contains(envB)) { "Disposing env A removed env B" }
        observationB = isolationObservation(service, envB, service.observe(envB))
        check(lifecycleFrameSignature(observationB) == lifecycleFrameSignature(controlObservation)) {
            "Reset/dispose of env A changed env B before its next step"
        }

        var policyStateB = DeterministicPolicyState(specB.policySeed())
        var policyStateControl = DeterministicPolicyState(specB.policySeed())
        var continuationSteps = 0
        while (continuationSteps < 16 &&
            !observationB.terminated && !observationB.truncated &&
            !controlObservation.terminated && !controlObservation.truncated
        ) {
            val choiceB = policy.choose(observationB, policyStateB)
            val choiceControl = policy.choose(controlObservation, policyStateControl)
            check(choiceB !is SemanticChoice.Gap && choiceControl !is SemanticChoice.Gap) {
                "Reset/dispose continuation reached a policy gap"
            }
            check(choiceFingerprint(choiceB) == choiceFingerprint(choiceControl)) {
                "Reset/dispose continuation changed the external semantic choice at " +
                    "step=$continuationSteps"
            }
            observationB = isolationObservation(
                service,
                envB,
                executeChoice(service, ScalingSlot(envB, observationB), observationB, choiceB),
            )
            controlObservation = isolationObservation(
                controlService,
                controlEnv,
                executeChoice(
                    controlService,
                    ScalingSlot(controlEnv, controlObservation),
                    controlObservation,
                    choiceControl,
                ),
            )
            check(lifecycleFrameSignature(observationB) == lifecycleFrameSignature(controlObservation)) {
                "First reset/dispose continuation divergence at step=$continuationSteps"
            }
            policyStateB = policyStateB.afterChoice()
            policyStateControl = policyStateControl.afterChoice()
            continuationSteps++
        }
        check(continuationSteps > 0) { "Reset/dispose isolation had no continuation steps" }

        return IsolationLifecycleReport(
            actionHandleIsolation = "PASS: env-A action handle was rejected by env-B and B stayed unchanged",
            decisionHandleIsolation = "PASS: env-A decision ID was rejected while env-B had no matching pending decision",
            registryIsolation = "PASS: action resolution remained scoped to the addressed GameGymEnv; CardRegistry was shared read-only",
            hiddenInformationIsolation =
                "PASS: distinct seed/start public observations stayed perspective-safe and env-B remained unchanged while env-A was advanced, reset, and disposed",
            resetDisposeIsolation = "PASS",
            continuationSteps = continuationSteps,
        )
    } finally {
        service.dispose(service.listEnvs())
        controlService.dispose(controlService.listEnvs())
        service.workerPool.close()
        controlService.workerPool.close()
    }
}

private fun lifecycleFrameSignature(observation: TrainingObservation): String = buildString {
    append(observation.stateDigest)
    append('|').append(observation.legalActions.size)
    append('|').append(observation.pendingDecision?.kind?.name ?: "-")
    append('|').append(observation.pendingDecision?.requiresStructuredResponse ?: false)
    append('|').append(observation.agentToAct?.value ?: "-")
    append('|').append(observation.terminated)
    append('|').append(observation.truncated)
}

private enum class IsolationSchedule {
    SINGLE_REFERENCE,
    NATURAL_BATCH,
    REVERSE_BATCH,
    ROTATING_SEQUENTIAL,
}

private class IsolationExecutionCounters {
    val tracker = ConcurrencyTracker()
    var stepBatchCalls: Int = 0
    var stepBatchRequests: Int = 0
    var workerPoolOperationBatches: Int = 0
    var resetBatches: Int = 0
}

private class IsolationReferenceStore(
    private val traceDirectory: Path,
) {
    private val labels = mutableSetOf<String>()
    private var ready = false

    fun open(label: String, referenceMode: Boolean): IsolationTraceSession {
        val path = traceDirectory.resolve("$label.trace")
        return if (referenceMode) {
            check(!ready) { "Isolation reference store is already sealed" }
            check(labels.add(label)) { "Duplicate isolation reference label: $label" }
            IsolationTraceSession(writer = Files.newBufferedWriter(path), reader = null)
        } else {
            check(ready) { "Isolation reference store is not ready" }
            check(label in labels) { "Missing isolation reference trace: $label" }
            IsolationTraceSession(writer = null, reader = Files.newBufferedReader(path))
        }
    }

    fun markReady() {
        check(labels.size == B1_SCALING_EPISODES) {
            "Expected $B1_SCALING_EPISODES isolation reference traces, got ${labels.size}"
        }
        ready = true
    }
}

private class IsolationTraceSession(
    private val writer: java.io.BufferedWriter?,
    private val reader: java.io.BufferedReader?,
) : AutoCloseable {
    private var eventIndex = 0
    private var closed = false

    fun recordFrame(observation: TrainingObservation) {
        val event = "F|${lifecycleFrameSignature(observation)}"
        record(event)
    }

    fun recordChoice(choice: SemanticChoice) {
        val event = "C|${scalingHexLowercase(MessageDigest.getInstance("SHA-256").digest(choiceFingerprint(choice).toByteArray(StandardCharsets.UTF_8)))}"
        record(event)
    }

    private fun record(actual: String) {
        check(!closed) { "Isolation trace session already closed" }
        if (writer != null) {
            writer.write(actual)
            writer.newLine()
        } else {
            val expected = reader?.readLine()
                ?: error("First isolation divergence at event=$eventIndex: reference ended before $actual")
            check(expected == actual) {
                "First isolation divergence at event=$eventIndex: expected=$expected actual=$actual"
            }
        }
        eventIndex++
    }

    override fun close() {
        if (closed) return
        closed = true
        if (writer != null) {
            writer.close()
        } else {
            check(reader?.readLine() == null) {
                "Isolation reference contains an extra event after event=$eventIndex"
            }
            reader?.close()
        }
    }
}

private class IsolationTrajectoryAccumulator(
    private val label: String,
    private val trace: IsolationTraceSession,
) : AutoCloseable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var observations = 0
    private var choices = 0
    private var publicLegalCandidates = 0L
    private var structuredDecisionObservations = 0L
    private var closed = false

    fun recordObservation(observation: TrainingObservation) {
        trace.recordFrame(observation)
        update("observation|${lifecycleFrameSignature(observation)}\n")
        observations++
        publicLegalCandidates += observation.legalActions.size
        if (observation.pendingDecision?.requiresStructuredResponse == true) {
            structuredDecisionObservations++
        }
    }

    fun recordChoice(choice: SemanticChoice) {
        trace.recordChoice(choice)
        update("choice|${choiceFingerprint(choice)}\n")
        choices++
    }

    fun finish(observation: TrainingObservation): IsolationTrajectorySummary {
        val summary = IsolationTrajectorySummary(
            label = label,
            observationCount = observations,
            publicLegalCandidates = publicLegalCandidates,
            structuredDecisionObservations = structuredDecisionObservations,
            choiceCount = choices,
            terminal = observation.terminated,
            truncated = observation.truncated,
            trajectoryHash = scalingHexLowercase(digest.digest()),
        )
        close()
        return summary
    }

    private fun update(value: String) {
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
    }

    override fun close() {
        if (closed) return
        closed = true
        trace.close()
    }
}

private data class IsolationTrajectorySummary(
    val label: String,
    val observationCount: Int,
    val publicLegalCandidates: Long,
    val structuredDecisionObservations: Long,
    val choiceCount: Int,
    val terminal: Boolean,
    val truncated: Boolean,
    val trajectoryHash: String,
)

@Serializable
private data class B1MultiEnvIsolationReport(
    val benchmarkSchemaVersion: String,
    val sourceHead: String,
    val corpusEpisodes: Int,
    val corpusExternalTransitions: Long,
    val scenarios: List<IsolationScenarioReport>,
    val resetDispose: IsolationLifecycleReport,
    val actionHandleIsolation: String,
    val decisionHandleIsolation: String,
    val registryIsolation: String,
    val hiddenInformationIsolation: String,
    val status: String,
    val dataTrusted: String,
)

@Serializable
private data class IsolationScenarioReport(
    val name: String,
    val environmentCount: Int,
    val schedule: String,
    val episodes: Int,
    val externalTransitions: Long,
    val observations: Long,
    val publicLegalCandidates: Long,
    val structuredDecisionObservations: Long,
    val stepBatchCalls: Int,
    val stepBatchRequests: Int,
    val workerPoolOperationBatches: Int,
    val resetBatches: Int,
    val actualConcurrency: ActualConcurrency,
    val semanticTrajectory: String,
)

@Serializable
private data class IsolationLifecycleReport(
    val actionHandleIsolation: String,
    val decisionHandleIsolation: String,
    val registryIsolation: String,
    val hiddenInformationIsolation: String,
    val resetDisposeIsolation: String,
    val continuationSteps: Int,
)

private fun runB1ScalingMeasurement() {
    val repetitions = positiveProperty("b1.scaling.repetitions", B1_SCALING_DEFAULT_REPETITIONS)
    val warmupSteps = positiveProperty("b1.scaling.warmupSteps", B1_SCALING_DEFAULT_WARMUP_STEPS)
    require(b1ScalingCorpus.size == B1_SCALING_EPISODES) {
        "B1 scaling corpus must contain exactly $B1_SCALING_EPISODES episodes"
    }
    val outputDir = Path.of(
        System.getProperty(
            "b1.scaling.outputDir",
            Path.of(System.getProperty("user.dir"), "build", "reports", "b1-scaling").toString(),
        ),
    )
    Files.createDirectories(outputDir)
    val jfrPath = outputDir.resolve("b1-scaling.jfr")
    val jsonPath = outputDir.resolve("b1-scaling.json")
    Files.deleteIfExists(jfrPath)
    Files.deleteIfExists(jsonPath)

    val recording = openScalingJfrRecording()
    val referenceHolder = ReferenceTrajectoryHolder()
    val conditions = try {
        listOf(1, 2, 4, 8).map { environmentCount ->
            measureScalingCondition(
                environmentCount = environmentCount,
                repetitions = repetitions,
                warmupSteps = warmupSteps,
                referenceHolder = referenceHolder,
            )
        }
    } finally {
        recording?.let { current ->
            runCatching {
                current.stop()
                current.dump(jfrPath)
            }.onFailure { failure ->
                println("B1_SCALING_JFR=NOT_RUN reason=" + (failure.message ?: failure::class.simpleName))
            }.also {
                current.close()
            }
        }
    }

    val report = B1ScalingReport(
        benchmarkSchemaVersion = "argentum-b1-scaling-v1",
        baseOriginMain = B1_SCALING_BASE_ORIGIN_MAIN,
        acceptedCharacterizationHead = B1_SCALING_ACCEPTED_HEAD,
        sourceHead = B1_SCALING_ACCEPTED_HEAD,
        hardware = hardwareMetadata(),
        warmupStepsPerEnvironment = warmupSteps,
        measuredRepetitions = repetitions,
        environments = conditions,
        memoryMeasurement =
            "retained setup heap/RSS delta uses test-only System.gc/runFinalization stabilization; workload timing is unaffected",
        observationBuildLatency = "NOT_SEPARATELY_MEASURABLE: returned step latency includes public observation construction",
        legalDomainPublicationLatency = "NOT_SEPARATELY_MEASURABLE: returned step latency includes legal-domain publication",
        semanticTrajectoryRegression = "PASS: compact state-digest/action trajectory hashes matched the 1-env reference",
        replayExactness = "MEASURED_BY_SEPARATE_EXACT_PAIR_GATE",
        b0TrustInvariants = "PRESERVED_BY_SCOPE: no candidate/order/privacy/RNG/replay production behavior changed",
        hostedCi = "NOT_ESTABLISHED",
        dataTrusted = "NO",
    )
    Files.writeString(jsonPath, b1ScalingJson.encodeToString(B1ScalingReport.serializer(), report))
    println("B1_SCALING_METRICS_PATH=" + jsonPath)
    println("B1_SCALING_JFR_PATH=" + if (Files.exists(jfrPath)) jfrPath else "NOT_RUN")
    println("B1_SCALING_ENVIRONMENTS=1,2,4,8")
    conditions.forEach { condition ->
        println(
            "B1_SCALING_ROW=" + condition.environmentCount +
                " medianWallSeconds=" + formatSeconds(condition.medianWorkloadWallNanos) +
                " medianTransitionsPerSecond=" + formatDouble(condition.medianTransitionsPerSecond) +
                " medianEpisodesPerSecond=" + formatDouble(condition.medianEpisodesPerSecond) +
                " maxConcurrency=" + condition.actualConcurrency.maxConcurrentCalls,
        )
    }
}

private fun positiveProperty(name: String, default: Int): Int {
    val value = System.getProperty(name)?.toIntOrNull() ?: default
    require(value > 0) { "$name must be positive" }
    return value
}

private fun openScalingJfrRecording(): Recording? =
    try {
        Recording(Configuration.getConfiguration("profile")).also { recording ->
            recording.name = "argentum-b1-scaling"
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10))
            recording.start()
        }
    } catch (failure: Exception) {
        println("B1_SCALING_JFR=NOT_RUN reason=" + (failure.message ?: failure::class.simpleName))
        null
    }

private fun measureScalingCondition(
    environmentCount: Int,
    repetitions: Int,
    warmupSteps: Int,
    referenceHolder: ReferenceTrajectoryHolder,
): ScalingConditionReport {
    val assignments = Array(environmentCount) { slotIndex ->
        b1ScalingCorpus.filterIndexed { episodeIndex, _ -> episodeIndex % environmentCount == slotIndex }
    }
    val beforeSetup = ScalingJvmSnapshot.capture(includeProcessRss = true)
    val setupStart = System.nanoTime()
    val registry = b1ScalingRegistry()
    val service = MultiEnvService(
        cardRegistry = registry,
        workerPool = EnvWorkerPool(environmentCount),
    )
    stabilizeHeapForMemorySnapshot()
    val beforeEnvironmentSetup = ScalingJvmSnapshot.capture(includeProcessRss = true)
    val slots = assignments.map { specs ->
        require(specs.isNotEmpty()) { "Every scaling environment needs at least one episode" }
        val created = service.create(specs.first().config())
        ScalingSlot(created.envId, trainingObservation(created.observation))
    }
    val setupWallNanos = System.nanoTime() - setupStart
    stabilizeHeapForMemorySnapshot()
    val afterSetup = ScalingJvmSnapshot.capture(includeProcessRss = true)
    try {
        warmup(service, slots, assignments, warmupSteps)
        val beforeMeasured = ScalingJvmSnapshot.capture(includeProcessRss = true)
        val repetitionsMeasured = (0 until repetitions).map { repetition ->
            measureRepetition(
                service = service,
                slots = slots,
                assignments = assignments,
                repetition = repetition,
                referenceHolder = referenceHolder,
                tracker = ConcurrencyTracker(),
            )
        }
        val measuredAfter = ScalingJvmSnapshot.capture(includeProcessRss = true)
        return buildConditionReport(
            environmentCount = environmentCount,
            repetitions = repetitionsMeasured,
            setupWallNanos = setupWallNanos,
            beforeSetup = beforeSetup,
            afterSetup = afterSetup,
            beforeEnvironmentSetup = beforeEnvironmentSetup,
            beforeMeasured = beforeMeasured,
            measuredAfter = measuredAfter,
        )
    } finally {
        service.dispose(service.listEnvs())
        service.workerPool.close()
    }
}

private fun warmup(
    service: MultiEnvService,
    slots: List<ScalingSlot>,
    assignments: Array<List<ScalingEpisodeSpec>>,
    warmupSteps: Int,
) {
    val activeSlots = slots.indices.toList()
    resetSlots(service, slots, assignments.map { it.first() }, activeSlots, ConcurrencyTracker())
    val policy = DeterministicExternalPolicy()
    val policyStates = slots.indices
        .map { index -> DeterministicPolicyState(assignments[index].first().policySeed()) }
        .toMutableList()
    val counts = slots.map { 0 }.toMutableList()
    var observations = slots.map { it.observation }
    while (counts.any { it < warmupSteps }) {
        val choices = activeSlots.map { index ->
            val choice = policy.choose(observations[index], policyStates[index])
            check(choice !is SemanticChoice.Gap) {
                "B1 scaling warmup policy gap at slot=$index: $choice"
            }
            choice
        }
        val calls = timedParallel(service, activeSlots, ConcurrencyTracker()) { index ->
            executeChoice(service, slots[index], observations[index], choices[index])
        }
        calls.forEachIndexed { offset, timed ->
            val index = activeSlots[offset]
            observations = observations.toMutableList().also { it[index] = trainingObservation(timed.value) }
            policyStates[index] = policyStates[index].afterChoice()
            counts[index]++
        }
    }
}

private fun measureRepetition(
    service: MultiEnvService,
    slots: List<ScalingSlot>,
    assignments: Array<List<ScalingEpisodeSpec>>,
    repetition: Int,
    referenceHolder: ReferenceTrajectoryHolder,
    tracker: ConcurrencyTracker,
): ScalingRepetitionReport {
    val resetLatencies = mutableListOf<Long>()
    val stepLatencies = mutableListOf<Long>()
    val resetMemorySamples = mutableListOf<Long>()
    val trajectories = linkedMapOf<String, TrajectoryAccumulator>()
    val jvmBefore = ScalingJvmSnapshot.capture()
    val wallStart = System.nanoTime()
    var heapPeakBytes = jvmBefore.heapUsedBytes
    var observationsSinceHeapSample = 0
    var policyNanos = 0L
    var transitions = 0L
    var episodes = 0
    var observations = 0L
    var publicLegalCandidates = 0L
    var structuredDecisionObservations = 0L
    val policy = DeterministicExternalPolicy()

    fun recordObservation(observation: TrainingObservation) {
        observations++
        publicLegalCandidates += observation.legalActions.size
        if (observation.pendingDecision?.requiresStructuredResponse == true) {
            structuredDecisionObservations++
        }
    }

    val rounds = assignments.maxOf { it.size }
    for (round in 0 until rounds) {
        val activeSlots = slots.indices.filter { round < assignments[it].size }
        val specs = activeSlots.associateWith { slotIndex -> assignments[slotIndex][round] }
        val resetCalls = timedParallel(service, activeSlots, tracker) { slotIndex ->
            service.reset(slots[slotIndex].envId, specs.getValue(slotIndex).config())
        }
        resetCalls.forEachIndexed { offset, timed ->
            val slotIndex = activeSlots[offset]
            val spec = specs.getValue(slotIndex)
            slots[slotIndex].observation = trainingObservation(timed.value)
            slots[slotIndex].spec = spec
            slots[slotIndex].policyState = DeterministicPolicyState(spec.policySeed())
            slots[slotIndex].transitions = 0
            resetLatencies += timed.elapsedNanos
            val resetHeap = currentHeapUsed()
            resetMemorySamples += resetHeap
            heapPeakBytes = max(heapPeakBytes, resetHeap)
            recordObservation(slots[slotIndex].observation)
            trajectories[spec.label] = TrajectoryAccumulator(spec.label).also {
                it.recordObservation(slots[slotIndex].observation)
            }
        }

        while (activeSlots.any { slotIndex ->
                !slots[slotIndex].observation.terminated && !slots[slotIndex].observation.truncated
            }) {
            val liveSlots = activeSlots.filter { slotIndex ->
                !slots[slotIndex].observation.terminated && !slots[slotIndex].observation.truncated
            }
            val choices = liveSlots.map { slotIndex ->
                val slot = slots[slotIndex]
                val choiceStart = System.nanoTime()
                val choice = policy.choose(slot.observation, slot.policyState)
                policyNanos += System.nanoTime() - choiceStart
                check(choice !is SemanticChoice.Gap) {
                    "B1 scaling policy gap at repetition=$repetition slot=$slotIndex " +
                        "transition=${slot.transitions}: $choice"
                }
                choice
            }
            choices.forEachIndexed { offset, choice ->
                val slotIndex = liveSlots[offset]
                trajectories.getValue(slots[slotIndex].spec.label).recordChoice(choice)
                slots[slotIndex].policyState = slots[slotIndex].policyState.afterChoice()
            }

            val stepCalls = timedParallel(service, liveSlots, tracker) { slotIndex ->
                val choiceIndex = liveSlots.indexOf(slotIndex)
                executeChoice(
                    service = service,
                    slot = slots[slotIndex],
                    observation = slots[slotIndex].observation,
                    choice = choices[choiceIndex],
                )
            }
            stepCalls.forEachIndexed { offset, timed ->
                val slotIndex = liveSlots[offset]
                val observation = trainingObservation(timed.value)
                slots[slotIndex].observation = observation
                slots[slotIndex].transitions++
                transitions++
                stepLatencies += timed.elapsedNanos
                recordObservation(observation)
                trajectories.getValue(slots[slotIndex].spec.label).recordObservation(observation)
            }
            observationsSinceHeapSample += stepCalls.size
            if (observationsSinceHeapSample >= HEAP_SAMPLE_INTERVAL) {
                heapPeakBytes = max(heapPeakBytes, currentHeapUsed())
                observationsSinceHeapSample = 0
            }
        }

        activeSlots.forEach { slotIndex ->
            val slot = slots[slotIndex]
            check(slot.observation.terminated || slot.observation.truncated) {
                "B1 scaling episode did not finish: ${slot.spec.label}"
            }
            check(slot.transitions <= B1_SCALING_MAX_STEPS) {
                "B1 scaling episode exceeded maxSteps: ${slot.spec.label}"
            }
            trajectories.getValue(slot.spec.label).finish(slot.observation).also { summary ->
                referenceHolder.checkOrSet(summary)
            }
            episodes++
        }
    }

    val wallNanos = System.nanoTime() - wallStart
    heapPeakBytes = max(heapPeakBytes, currentHeapUsed())
    val jvmAfter = ScalingJvmSnapshot.capture()
    val allocationBytes = positiveDelta(jvmAfter.threadAllocatedBytes, jvmBefore.threadAllocatedBytes)
    val gc = gcDelta(jvmBefore, jvmAfter)
    check(episodes == B1_SCALING_EPISODES) { "Expected 8 measured episodes, got $episodes" }
    check(transitions == B1_SCALING_EPISODES.toLong() * B1_SCALING_MAX_STEPS) {
        "Expected exact 16,000 measured transitions, got $transitions"
    }
    return ScalingRepetitionReport(
        repetition = repetition,
        episodes = episodes,
        externalTransitions = transitions,
        observations = observations,
        publicLegalCandidates = publicLegalCandidates,
        structuredDecisionObservations = structuredDecisionObservations,
        workloadWallNanos = wallNanos,
        transitionsPerSecond = transitions.toDouble() / wallNanos.toDouble() * 1_000_000_000.0,
        episodesPerSecond = episodes.toDouble() / wallNanos.toDouble() * 1_000_000_000.0,
        policyNanos = policyNanos,
        processCpuNanos = positiveDelta(jvmAfter.processCpuNanos, jvmBefore.processCpuNanos),
        stepLatency = latencySummary(stepLatencies),
        resetLatency = latencySummary(resetLatencies),
        resetMemorySamples = resetMemorySamples,
        heapPeakBytes = heapPeakBytes,
        allocationBytes = allocationBytes,
        allocationPerTransition = if (allocationBytes >= 0L && transitions > 0L) {
            allocationBytes.toDouble() / transitions.toDouble()
        } else {
            null
        },
        gcCollections = aggregateGc(gc) { it.collectionCount },
        gcTimeMillis = aggregateGc(gc) { it.collectionTimeMillis },
        actualConcurrency = tracker.summary(),
        semanticTrajectory = "PASS",
    )
}

private const val HEAP_SAMPLE_INTERVAL = 64

private fun resetSlots(
    service: MultiEnvService,
    slots: List<ScalingSlot>,
    specs: List<ScalingEpisodeSpec>,
    activeSlots: List<Int>,
    tracker: ConcurrencyTracker,
): List<Timed<ObservationResult>> = timedParallel(service, activeSlots, tracker) { slotIndex ->
    service.reset(slots[slotIndex].envId, specs[slotIndex].config())
}.also { calls ->
    calls.forEachIndexed { offset, timed ->
        slots[activeSlots[offset]].observation = trainingObservation(timed.value)
    }
}

private fun <T> timedParallel(
    service: MultiEnvService,
    slotIndexes: List<Int>,
    tracker: ConcurrencyTracker,
    call: (Int) -> T,
): List<Timed<T>> {
    if (slotIndexes.isEmpty()) return emptyList()
    val tasks = slotIndexes.map { slotIndex ->
        Callable {
            val start = System.nanoTime()
            val value = tracker.track { call(slotIndex) }
            Timed(value, System.nanoTime() - start)
        }
    }
    return service.workerPool.invokeAll(tasks)
}

private fun executeChoice(
    service: MultiEnvService,
    slot: ScalingSlot,
    observation: TrainingObservation,
    choice: SemanticChoice,
): ObservationResult = when (choice) {
    is SemanticChoice.Action -> {
        val selected = observation.legalActions.singleOrNull { it.actionId == choice.actionId }
            ?: error("Scaling policy selected an action outside the current public list")
        check(selected.affordable || selected.isDecisionOption) {
            "Scaling policy selected an unaffordable/non-decision action"
        }
        service.step(
            StepRequest(
                envId = slot.envId,
                actionId = choice.actionId,
                action = choice.payload,
            ),
        )
    }

    is SemanticChoice.Structured -> {
        val pending = observation.pendingDecision
            ?: error("Structured scaling choice had no pending decision")
        val decisionId = pending.decisionId
            ?: error("Structured scaling choice had no decision ID")
        service.submitDecision(
            envId = slot.envId,
            response = toScalingDecisionResponse(decisionId, choice.selection),
            actorId = observation.agentToAct,
        )
    }

    is SemanticChoice.Gap -> error("Scaling policy gap reached execution")
}

private fun buildConditionReport(
    environmentCount: Int,
    repetitions: List<ScalingRepetitionReport>,
    setupWallNanos: Long,
    beforeSetup: ScalingJvmSnapshot,
    afterSetup: ScalingJvmSnapshot,
    beforeEnvironmentSetup: ScalingJvmSnapshot,
    beforeMeasured: ScalingJvmSnapshot,
    measuredAfter: ScalingJvmSnapshot,
): ScalingConditionReport {
    val allocationBytes = if (repetitions.all { it.allocationBytes >= 0L }) {
        repetitions.sumOf { it.allocationBytes }
    } else {
        -1L
    }
    val transitions = repetitions.sumOf { it.externalTransitions }
    val gcCollections = if (repetitions.all { it.gcCollections >= 0L }) {
        repetitions.sumOf { it.gcCollections }
    } else {
        -1L
    }
    val gcTimeMillis = if (repetitions.all { it.gcTimeMillis >= 0L }) {
        repetitions.sumOf { it.gcTimeMillis }
    } else {
        -1L
    }
    val measuredHeapPeak = repetitions.maxOfOrNull { it.heapPeakBytes } ?: measuredAfter.heapUsedBytes
    val resetMemoryValues = repetitions.flatMap { it.resetMemorySamples }
    val resetTrend = ResetMemoryTrend.from(resetMemoryValues)
    val observedRss = listOfNotNull(
        beforeSetup.processRssBytes,
        afterSetup.processRssBytes,
        beforeMeasured.processRssBytes,
        measuredAfter.processRssBytes,
    )
    return ScalingConditionReport(
        environmentCount = environmentCount,
        workersRequested = environmentCount,
        setupWallNanos = setupWallNanos,
        setupHeapBeforeBytes = beforeSetup.heapUsedBytes,
        setupHeapAfterBytes = afterSetup.heapUsedBytes,
        setupRssBeforeBytes = beforeSetup.processRssBytes,
        setupRssAfterBytes = afterSetup.processRssBytes,
        environmentSetupHeapBeforeBytes = beforeEnvironmentSetup.heapUsedBytes,
        environmentSetupHeapAfterBytes = afterSetup.heapUsedBytes,
        environmentSetupRssBeforeBytes = beforeEnvironmentSetup.processRssBytes,
        environmentSetupRssAfterBytes = afterSetup.processRssBytes,
        memoryPerEnvironmentBytes = positiveDelta(afterSetup.heapUsedBytes, beforeEnvironmentSetup.heapUsedBytes)
            .takeIf { it >= 0L }
            ?.div(environmentCount.toDouble()),
        measuredHeapBeforeBytes = beforeMeasured.heapUsedBytes,
        measuredHeapAfterBytes = measuredAfter.heapUsedBytes,
        peakHeapBytes = max(measuredHeapPeak, maxOf(beforeMeasured.heapUsedBytes, measuredAfter.heapUsedBytes)),
        observedRssPeakBytes = observedRss.maxOrNull(),
        memoryRssPerEnvironmentBytes = if (beforeEnvironmentSetup.processRssBytes != null &&
            afterSetup.processRssBytes != null
        ) {
            optionalPositiveDelta(afterSetup.processRssBytes, beforeEnvironmentSetup.processRssBytes)
                ?.div(environmentCount.toDouble())
        } else {
            null
        },
        allocationBytes = allocationBytes,
        allocationPerExternalTransition = if (allocationBytes >= 0L && transitions > 0L) {
            allocationBytes.toDouble() / transitions.toDouble()
        } else {
            null
        },
        gcCollections = gcCollections,
        gcTimeMillis = gcTimeMillis,
        observations = medianLong(repetitions.map { it.observations }),
        publicLegalCandidates = medianLong(repetitions.map { it.publicLegalCandidates }),
        structuredDecisionObservations = medianLong(
            repetitions.map { it.structuredDecisionObservations },
        ),
        repetitions = repetitions,
        medianWorkloadWallNanos = medianLong(repetitions.map { it.workloadWallNanos }),
        minWorkloadWallNanos = repetitions.minOf { it.workloadWallNanos },
        maxWorkloadWallNanos = repetitions.maxOf { it.workloadWallNanos },
        medianTransitionsPerSecond = medianDouble(repetitions.map { it.transitionsPerSecond }),
        medianEpisodesPerSecond = medianDouble(repetitions.map { it.episodesPerSecond }),
        medianStepLatency = medianLatency(repetitions.map { it.stepLatency }),
        medianResetLatency = medianLatency(repetitions.map { it.resetLatency }),
        resetMemoryTrend = resetTrend,
        actualConcurrency = ActualConcurrencySummary.from(repetitions.map { it.actualConcurrency }),
        semanticTrajectory = "PASS",
    )
}

private fun toScalingDecisionResponse(
    decisionId: String,
    selection: SemanticDecision,
): DecisionResponse = when (selection) {
    is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
    is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
    is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Distribution -> DistributionResponse(decisionId, selection.selected)
    is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
    is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
    is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Replacement -> ReplacementChosenResponse(decisionId, selection.from, selection.to)
    is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
    is SemanticDecision.Damage -> CombatResolutionResponse(
        decisionId = decisionId,
        edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
    )
}

private fun trainingObservation(result: ObservationResult): TrainingObservation {
    check(result.diagnostics.isEmpty()) {
        "B1 scaling observation carried diagnostics: ${result.diagnostics}"
    }
    return result.observation as? TrainingObservation
        ?: error("B1 scaling requires TrainingObservation")
}

private fun b1ScalingRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun ScalingEpisodeSpec.config(): EnvConfig {
    val akiri = readScalingLockedDeck("akiri-v0.1.txt")
    val chevill = readScalingLockedDeck("chevill-v0.1.txt")
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
        maxSteps = B1_SCALING_MAX_STEPS,
        perspectivePlayerIndex = 0,
    )
}

private fun readScalingLockedDeck(fileName: String): ScalingLockedDeck {
    val root = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }
    val cards = Files.readAllLines(root.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName))
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }
    return ScalingLockedDeck(cards.first(), cards)
}

private fun choiceFingerprint(choice: SemanticChoice): String = when (choice) {
    is SemanticChoice.Action -> "action|${choice.kind}|${choice.semanticKey}|${choice.payload ?: ""}"
    is SemanticChoice.Structured -> "structured|${choice.family}|${selectionFingerprint(choice.selection)}"
    is SemanticChoice.Gap -> "gap|${choice.family}|${choice.code}|${choice.reason}"
}

private fun selectionFingerprint(selection: SemanticDecision): String = when (selection) {
    is SemanticDecision.Targets -> selection.selected.toSortedMap().entries.joinToString(";") { (slot, ids) ->
        "$slot=${ids.joinToString(",") { it.value }}"
    }
    is SemanticDecision.Cards -> selection.selected.joinToString(",") { it.value }
    is SemanticDecision.Modes -> selection.selected.joinToString(",")
    is SemanticDecision.Color -> selection.selected.name
    is SemanticDecision.Number -> selection.selected.toString()
    is SemanticDecision.Distribution -> selection.selected.toSortedMap(compareBy { it.value })
        .entries.joinToString(",") { (id, amount) -> "${id.value}:$amount" }
    is SemanticDecision.Ordered -> selection.selected.joinToString(",") { it.value }
    is SemanticDecision.Piles -> selection.selected.joinToString(";") { pile ->
        pile.joinToString(",") { it.value }
    }
    is SemanticDecision.Option -> selection.selected.toString()
    is SemanticDecision.Replacement -> "${selection.from}->${selection.to}"
    is SemanticDecision.Budget -> selection.selected.joinToString(",")
    is SemanticDecision.Damage -> selection.selected.joinToString(",") { "${it.edgeId}:${it.amount}" }
}

private data class ScalingEpisodeSpec(
    val seed: Long,
    val startingPlayerIndex: Int,
    val seat0: String,
    val seat1: String,
) {
    val label: String
        get() = "$seat0-vs-$seat1-seed-$seed-start-$startingPlayerIndex"

    fun policySeed(): Long {
        val roster = if (seat0 == "Akiri") 0x41L else 0x43L
        return seed * 1_000_003L + startingPlayerIndex * 97_409L + roster * 65_537L
    }
}

private data class ScalingLockedDeck(
    val commander: String,
    val cards: List<String>,
)

private class ScalingSlot(
    val envId: EnvId,
    var observation: TrainingObservation,
    var spec: ScalingEpisodeSpec = b1ScalingCorpus.first(),
    var policyState: DeterministicPolicyState = DeterministicPolicyState(0L),
    var transitions: Int = 0,
)

private data class Timed<T>(
    val value: T,
    val elapsedNanos: Long,
)

private class ConcurrencyTracker {
    private val active = AtomicInteger(0)
    private val maximum = AtomicInteger(0)
    private val threads = ConcurrentHashMap.newKeySet<Long>()

    fun <T> track(block: () -> T): T {
        val current = active.incrementAndGet()
        updateMaximum(current)
        threads += Thread.currentThread().threadId()
        return try {
            block()
        } finally {
            active.decrementAndGet()
        }
    }

    fun summary(): ActualConcurrency = ActualConcurrency(
        maxConcurrentCalls = maximum.get(),
        workerThreadsObserved = threads.size,
    )

    private fun updateMaximum(candidate: Int) {
        while (true) {
            val observed = maximum.get()
            if (candidate <= observed || maximum.compareAndSet(observed, candidate)) return
        }
    }
}

private class ReferenceTrajectoryHolder {
    private val reference = linkedMapOf<String, TrajectorySummary>()
    private var collectingReference = true

    fun checkOrSet(summary: TrajectorySummary) {
        if (collectingReference) {
            check(reference.put(summary.label, summary) == null) {
                "Duplicate reference trajectory label: ${summary.label}"
            }
            if (reference.size == B1_SCALING_EPISODES) collectingReference = false
            return
        }
        val expected = reference[summary.label]
            ?: error("No reference trajectory for ${summary.label}")
        check(expected == summary) {
            "B1 scaling semantic trajectory divergence for ${summary.label}: " +
                "expected=$expected actual=$summary"
        }
    }
}

private class TrajectoryAccumulator(
    private val label: String,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var observations = 0
    private var choices = 0

    fun recordObservation(observation: TrainingObservation) {
        update("observation|${observation.stateDigest}\n")
        observations++
    }

    fun recordChoice(choice: SemanticChoice) {
        update("choice|${choiceFingerprint(choice)}\n")
        choices++
    }

    fun finish(observation: TrainingObservation): TrajectorySummary = TrajectorySummary(
        label = label,
        observationCount = observations,
        choiceCount = choices,
        terminal = observation.terminated,
        truncated = observation.truncated,
        trajectoryHash = scalingHexLowercase(digest.digest()),
    )

    private fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
}

@Serializable
private data class B1ScalingReport(
    val benchmarkSchemaVersion: String,
    val baseOriginMain: String,
    val acceptedCharacterizationHead: String,
    val sourceHead: String,
    val hardware: ScalingHardwareMetadata,
    val warmupStepsPerEnvironment: Int,
    val measuredRepetitions: Int,
    val environments: List<ScalingConditionReport>,
    val memoryMeasurement: String,
    val observationBuildLatency: String,
    val legalDomainPublicationLatency: String,
    val semanticTrajectoryRegression: String,
    val replayExactness: String,
    val b0TrustInvariants: String,
    val hostedCi: String,
    val dataTrusted: String,
)

@Serializable
private data class ScalingHardwareMetadata(
    val osName: String,
    val osVersion: String,
    val architecture: String,
    val javaVersion: String,
    val jvm: String,
    val availableProcessors: Int,
    val processId: Long,
)

@Serializable
private data class ScalingConditionReport(
    val environmentCount: Int,
    val workersRequested: Int,
    val setupWallNanos: Long,
    val setupHeapBeforeBytes: Long,
    val setupHeapAfterBytes: Long,
    val setupRssBeforeBytes: Long? = null,
    val setupRssAfterBytes: Long? = null,
    val environmentSetupHeapBeforeBytes: Long,
    val environmentSetupHeapAfterBytes: Long,
    val environmentSetupRssBeforeBytes: Long? = null,
    val environmentSetupRssAfterBytes: Long? = null,
    val memoryPerEnvironmentBytes: Double? = null,
    val measuredHeapBeforeBytes: Long,
    val measuredHeapAfterBytes: Long,
    val peakHeapBytes: Long,
    val observedRssPeakBytes: Long? = null,
    val memoryRssPerEnvironmentBytes: Double? = null,
    val allocationBytes: Long,
    val allocationPerExternalTransition: Double? = null,
    val gcCollections: Long,
    val gcTimeMillis: Long,
    val observations: Long,
    val publicLegalCandidates: Long,
    val structuredDecisionObservations: Long,
    val repetitions: List<ScalingRepetitionReport>,
    val medianWorkloadWallNanos: Long,
    val minWorkloadWallNanos: Long,
    val maxWorkloadWallNanos: Long,
    val medianTransitionsPerSecond: Double,
    val medianEpisodesPerSecond: Double,
    val medianStepLatency: LatencySummary,
    val medianResetLatency: LatencySummary,
    val resetMemoryTrend: ResetMemoryTrend,
    val actualConcurrency: ActualConcurrencySummary,
    val semanticTrajectory: String,
)

@Serializable
private data class ScalingRepetitionReport(
    val repetition: Int,
    val episodes: Int,
    val externalTransitions: Long,
    val observations: Long,
    val publicLegalCandidates: Long,
    val structuredDecisionObservations: Long,
    val workloadWallNanos: Long,
    val transitionsPerSecond: Double,
    val episodesPerSecond: Double,
    val policyNanos: Long,
    val processCpuNanos: Long,
    val stepLatency: LatencySummary,
    val resetLatency: LatencySummary,
    val resetMemorySamples: List<Long>,
    val heapPeakBytes: Long,
    val allocationBytes: Long,
    val allocationPerTransition: Double? = null,
    val gcCollections: Long,
    val gcTimeMillis: Long,
    val actualConcurrency: ActualConcurrency,
    val semanticTrajectory: String,
)

@Serializable
private data class LatencySummary(
    val count: Int,
    val minNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val maxNanos: Long,
)

@Serializable
private data class ResetMemoryTrend(
    val sampleCount: Int,
    val firstHeapUsedBytes: Long,
    val lastHeapUsedBytes: Long,
    val minHeapUsedBytes: Long,
    val maxHeapUsedBytes: Long,
    val deltaBytes: Long,
    val deltaPerResetBytes: Double?,
) {
    companion object {
        fun from(samples: List<Long>): ResetMemoryTrend {
            require(samples.isNotEmpty()) { "Reset-memory trend requires at least one sample" }
            val delta = samples.last() - samples.first()
            return ResetMemoryTrend(
                sampleCount = samples.size,
                firstHeapUsedBytes = samples.first(),
                lastHeapUsedBytes = samples.last(),
                minHeapUsedBytes = samples.minOrNull() ?: samples.first(),
                maxHeapUsedBytes = samples.maxOrNull() ?: samples.first(),
                deltaBytes = delta,
                deltaPerResetBytes = if (samples.size > 1) {
                    delta.toDouble() / (samples.size - 1).toDouble()
                } else {
                    null
                },
            )
        }
    }
}

@Serializable
private data class ActualConcurrency(
    val maxConcurrentCalls: Int,
    val workerThreadsObserved: Int,
)

@Serializable
private data class ActualConcurrencySummary(
    val maxConcurrentCalls: Int,
    val maxWorkerThreadsObserved: Int,
) {
    companion object {
        fun from(values: List<ActualConcurrency>): ActualConcurrencySummary = ActualConcurrencySummary(
            maxConcurrentCalls = values.maxOf { it.maxConcurrentCalls },
            maxWorkerThreadsObserved = values.maxOf { it.workerThreadsObserved },
        )
    }
}

@Serializable
private data class TrajectorySummary(
    val label: String,
    val observationCount: Int,
    val choiceCount: Int,
    val terminal: Boolean,
    val truncated: Boolean,
    val trajectoryHash: String,
)

private fun latencySummary(values: List<Long>): LatencySummary {
    require(values.isNotEmpty()) { "Latency summary requires at least one sample" }
    val sorted = values.sorted()
    fun percentile(quantile: Double): Long {
        val rank = ceil(quantile * sorted.size.toDouble()).toInt() - 1
        return sorted[rank.coerceIn(0, sorted.lastIndex)]
    }
    return LatencySummary(
        count = sorted.size,
        minNanos = sorted.first(),
        p50Nanos = percentile(0.50),
        p95Nanos = percentile(0.95),
        p99Nanos = percentile(0.99),
        maxNanos = sorted.last(),
    )
}

private fun medianLong(values: List<Long>): Long {
    require(values.isNotEmpty()) { "Median requires at least one value" }
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        ((sorted[sorted.size / 2 - 1].toDouble() + sorted[sorted.size / 2].toDouble()) / 2.0)
            .toLong()
    }
}

private fun medianDouble(values: List<Double>): Double {
    require(values.isNotEmpty()) { "Median requires at least one value" }
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}

private fun medianLatency(values: List<LatencySummary>): LatencySummary {
    require(values.isNotEmpty()) { "Latency median requires at least one value" }
    return LatencySummary(
        count = medianLong(values.map { it.count.toLong() }).toInt(),
        minNanos = medianLong(values.map { it.minNanos }),
        p50Nanos = medianLong(values.map { it.p50Nanos }),
        p95Nanos = medianLong(values.map { it.p95Nanos }),
        p99Nanos = medianLong(values.map { it.p99Nanos }),
        maxNanos = medianLong(values.map { it.maxNanos }),
    )
}

private fun aggregateGc(values: Map<String, ScalingGcDelta>, field: (ScalingGcDelta) -> Long): Long {
    val selected = values.values.map(field)
    return if (selected.any { it < 0L }) -1L else selected.sum()
}

private fun gcDelta(before: ScalingJvmSnapshot, after: ScalingJvmSnapshot): Map<String, ScalingGcDelta> =
    after.gc.mapValues { (name, afterGc) ->
        val beforeGc = before.gc[name] ?: ScalingGcSnapshot(0L, 0L)
        ScalingGcDelta(
            collectionCount = positiveDelta(afterGc.collectionCount, beforeGc.collectionCount),
            collectionTimeMillis = positiveDelta(afterGc.collectionTimeMillis, beforeGc.collectionTimeMillis),
        )
    }

@Serializable
private data class ScalingGcDelta(
    val collectionCount: Long,
    val collectionTimeMillis: Long,
)

private data class ScalingGcSnapshot(
    val collectionCount: Long,
    val collectionTimeMillis: Long,
)

private data class ScalingJvmSnapshot(
    val processCpuNanos: Long,
    val threadAllocatedBytes: Long,
    val heapUsedBytes: Long,
    val processRssBytes: Long?,
    val gc: Map<String, ScalingGcSnapshot>,
) {
    companion object {
        fun capture(includeProcessRss: Boolean = false): ScalingJvmSnapshot {
            val os = ManagementFactory.getOperatingSystemMXBean() as?
                com.sun.management.OperatingSystemMXBean
            val thread = ManagementFactory.getThreadMXBean() as?
                com.sun.management.ThreadMXBean
            val allocated = allocatedBytes(thread)
            return ScalingJvmSnapshot(
                processCpuNanos = os?.processCpuTime ?: -1L,
                threadAllocatedBytes = allocated,
                heapUsedBytes = currentHeapUsed(),
                processRssBytes = if (includeProcessRss) currentProcessRssBytes() else null,
                gc = ManagementFactory.getGarbageCollectorMXBeans()
                    .associate {
                        bean -> bean.name to ScalingGcSnapshot(bean.collectionCount, bean.collectionTime)
                    },
            )
        }
    }
}

private fun allocatedBytes(thread: com.sun.management.ThreadMXBean?): Long {
    if (thread?.isThreadAllocatedMemorySupported != true) return -1L
    return try {
        if (!thread.isThreadAllocatedMemoryEnabled) {
            thread.isThreadAllocatedMemoryEnabled = true
        }
        thread.getThreadAllocatedBytes(thread.allThreadIds)
            .filter { it >= 0L }
            .sum()
    } catch (_: Exception) {
        -1L
    }
}

private fun currentHeapUsed(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

private fun stabilizeHeapForMemorySnapshot() {
    System.gc()
    System.runFinalization()
    Thread.sleep(B1_SCALING_MEMORY_STABILIZATION_MILLIS)
}

private fun currentProcessRssBytes(): Long? {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return null
    val pid = ProcessHandle.current().pid()
    return runCatching {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-Process -Id $pid).WorkingSet64",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5L, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readText().trim().lines().firstOrNull()?.trim()?.toLongOrNull()
        }
    }.getOrNull()
}

private fun positiveDelta(after: Long, before: Long): Long =
    if (after >= 0L && before >= 0L && after >= before) after - before else -1L

private fun optionalPositiveDelta(after: Long?, before: Long?): Long? =
    if (after != null && before != null && after >= before) after - before else null

private fun hardwareMetadata(): ScalingHardwareMetadata = ScalingHardwareMetadata(
    osName = System.getProperty("os.name"),
    osVersion = System.getProperty("os.version"),
    architecture = System.getProperty("os.arch"),
    javaVersion = System.getProperty("java.version"),
    jvm = System.getProperty("java.vm.name"),
    availableProcessors = Runtime.getRuntime().availableProcessors(),
    processId = ProcessHandle.current().pid(),
)

private fun formatSeconds(nanos: Long): String =
    String.format(Locale.ROOT, "%.3f", nanos / 1_000_000_000.0)

private fun formatDouble(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

private fun scalingHexLowercase(bytes: ByteArray): String =
    bytes.joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
