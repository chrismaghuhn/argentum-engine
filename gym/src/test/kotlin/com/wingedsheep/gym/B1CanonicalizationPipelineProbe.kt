package com.wingedsheep.gym

import com.wingedsheep.gym.contract.TrainingObservation
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test-only call-graph and cost probe for the canonicalization pipeline. It is active only for
 * the explicit Characterization-20 run and retains no observation or JSON payload in its report.
 * Recursive canonicalize calls are counted without per-node timers to keep perturbation bounded.
 */
internal object B1CanonicalizationPipelineProbe {
    private val activeSession = AtomicReference<Session?>()
    private val allocationBean = (ManagementFactory.getThreadMXBean() as?
        com.sun.management.ThreadMXBean)?.takeIf {
        it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
    }

    @Serializable
    internal data class StageSnapshot(
        val calls: Long,
        val wallNanos: Long,
        val allocatedBytes: Long?,
        val allocationSamples: Long,
    )

    @Serializable
    internal data class DistributionSnapshot(
        val count: Long,
        val mean: Double,
        val p50: Long,
        val p95: Long,
        val max: Long,
    ) {
        companion object {
            fun from(values: List<Long>): DistributionSnapshot {
                if (values.isEmpty()) return DistributionSnapshot(0, 0.0, 0, 0, 0)
                val sorted = values.sorted()
                fun percentile(q: Double): Long {
                    val index = (q * sorted.size).toInt().coerceAtLeast(1) - 1
                    return sorted[index.coerceIn(sorted.indices)]
                }
                return DistributionSnapshot(
                    count = sorted.size.toLong(),
                    mean = sorted.average(),
                    p50 = percentile(0.50),
                    p95 = percentile(0.95),
                    max = sorted.last(),
                )
            }
        }
    }

    @Serializable
    internal data class RootStructureSnapshot(
        val roots: Long,
        val objectNodes: DistributionSnapshot,
        val arrayNodes: DistributionSnapshot,
        val primitiveNodes: DistributionSnapshot,
        val totalNodes: DistributionSnapshot,
        val objectMemberCount: DistributionSnapshot,
        val arrayElementCount: DistributionSnapshot,
        val maxDepth: DistributionSnapshot,
        val stringValueCount: DistributionSnapshot,
        val numberValueCount: DistributionSnapshot,
        val booleanValueCount: DistributionSnapshot,
        val nullValueCount: DistributionSnapshot,
        val stringLengthChars: DistributionSnapshot,
        val utf8LengthBytes: DistributionSnapshot,
        val objectKeysPerSort: DistributionSnapshot,
    )

    @Serializable
    internal data class SegmentSnapshot(
        val label: String,
        val transitions: Long,
        val apiCalls: Map<String, Long>,
        val counters: Map<String, Long>,
        val consumerCalls: Map<String, Long>,
        val stages: Map<String, StageSnapshot>,
        val fingerprintCounts: DistributionSnapshot,
        val canonicalizeNodeVisitsPerRoot: DistributionSnapshot,
        val rootStructure: RootStructureSnapshot,
        val distinctObservationsCanonicalized: Long,
        val sameObjectRecanonicalizationCount: Long,
        val samePublicObservationRecanonicalizationCount: Long,
    )

    @Serializable
    internal data class Snapshot(
        val schemaVersion: String,
        val allocationMeasurement: String,
        val integrity: String,
        val integrityDetails: Map<String, Long>,
        val segments: List<SegmentSnapshot>,
    )

    internal class Session internal constructor() {
        private val currentSegment = AtomicReference<Segment?>()
        private val segments = CopyOnWriteArrayList<Segment>()
        private val threadState = ThreadLocal<TransitionState?>()
        private val integrityErrors = AtomicLong()
        private val integrityDetails = ConcurrentHashMap<String, LongAdder>()

        internal fun beginSegment(label: String): SegmentHandle {
            val segment = Segment(label)
            segments += segment
            if (!currentSegment.compareAndSet(null, segment)) markIntegrityError("segment-overlap")
            return SegmentHandle(this, segment)
        }

        internal fun endSegment(segment: Segment) {
            if (!currentSegment.compareAndSet(segment, null)) markIntegrityError("segment-close-mismatch")
        }

        internal fun beginTransition() {
            val segment = currentSegment.get() ?: return
            if (threadState.get() != null) {
                markIntegrityError("transition-overlap")
                return
            }
            segment.transitions.increment()
            threadState.set(TransitionState(segment, System.nanoTime()))
        }

        internal fun endTransition() {
            val state = threadState.get() ?: return
            if (state.frames.isNotEmpty() || state.canonicalizeDepth != 0) {
                markIntegrityError("transition-close-with-open-frame")
            }
            threadState.remove()
        }

        internal fun enterMethod(kind: String, argument: Any?) {
            val state = threadState.get() ?: return
            when (kind) {
                "SEMANTIC_JSON" -> enterSemanticJson(state, argument)
                "STATE_DIGEST_COMPUTE" -> enterStateDigest(state, argument)
                "DIGEST_BODY" -> pushTimed(state, "DIGEST_BODY", "DIGEST_OTHER_RESIDUAL", EXCLUSIVE)
                "SEMANTIC_ACTION_FINGERPRINT" -> {
                    increment(state.segment.counters, "ACTION_FINGERPRINTS_BUILT_TOTAL")
                    nearestSemanticSource(state)?.let { source ->
                        source.fingerprintCount++
                    }
                    pushTimed(state, kind, "ACTION_FINGERPRINT_BUILD", EXCLUSIVE)
                }
                "SORT_FINGERPRINTS" -> {
                    increment(state.segment.counters, "FINGERPRINT_SORT_CALLS")
                    if (argument is List<*>) {
                        increment(
                            state.segment.counters,
                            "FINGERPRINT_SORT_KEYS_BUILT_FROM_LIST_TOTAL",
                            argument.size.toLong(),
                        )
                    }
                    pushTimed(state, kind, "ACTION_FINGERPRINT_SORT", EXCLUSIVE)
                }
                "PLAYER_OBSERVATION_JSON",
                "PLAYER_OBSERVATION_DIGEST",
                "CANONICAL_ELEMENT",
                "CANONICAL_JSON",
                "CANONICAL_DOMAIN_JSON",
                "WIRE_JSON",
                "SEMANTIC_STRUCTURED_DOMAIN",
                -> {
                    increment(state.segment.apiCalls, kind)
                    pushUntimed(state, kind)
                }
                else -> pushUntimed(state, kind)
            }
        }

        internal fun exitMethod(kind: String) {
            val state = threadState.get() ?: return
            val frame = state.frames.removeLastOrNull()
            if (frame == null || frame.rawKind != kind) {
                markIntegrityError("method-exit-mismatch expected=$kind actual=${frame?.rawKind}")
                return
            }
            finishFrame(state, frame)
            if (frame.isSemanticSource) {
                state.segment.fingerprintCounts += frame.fingerprintCount.toLong()
                state.segment.canonicalizeNodeVisitsPerRoot += frame.canonicalizeNodeVisits
            }
        }

        internal fun enterCanonicalize(element: Any?) {
            val state = threadState.get() ?: return
            increment(state.segment.counters, "CANONICALIZE_RECURSIVE_NODE_VISITS")
            when (element) {
                is JsonObject -> increment(state.segment.counters, "CANONICALIZE_OBJECT_CALLS")
                is JsonArray -> increment(state.segment.counters, "CANONICALIZE_ARRAY_CALLS")
                is JsonPrimitive -> increment(state.segment.counters, "CANONICALIZE_PRIMITIVE_CALLS")
                else -> increment(state.segment.counters, "CANONICALIZE_OTHER_CALLS")
            }
            state.frames.asReversed().firstOrNull { it.isSemanticSource }?.let {
                it.canonicalizeNodeVisits++
            }
            if (state.canonicalizeDepth > 0) {
                state.canonicalizeDepth++
                if (state.canonicalizeRootStage == "ROOT_CANONICALIZATION") {
                    state.rootShape?.observe(element, state.canonicalizeDepth)
                }
                recordContextNodeVisit(state, state.canonicalizeRootStage)
                return
            }
            val parent = state.frames.lastOrNull()?.family
            val stage = when (parent) {
                "SEMANTIC_CORE" -> "ROOT_CANONICALIZATION"
                "SORT_FINGERPRINTS" -> "ACTION_FINGERPRINT_SORT_KEY"
                else -> "CANONICALIZATION_OTHER"
            }
            increment(
                state.segment.counters,
                when (stage) {
                    "ROOT_CANONICALIZATION" -> "ROOT_CANONICALIZE_CALLS"
                    "ACTION_FINGERPRINT_SORT_KEY" -> "FINGERPRINT_CANONICALIZE_CALLS"
                    else -> "OTHER_ROOT_CANONICALIZE_CALLS"
                },
            )
            state.canonicalizeDepth = 1
            state.canonicalizeRootStage = stage
            state.rootShape = if (stage == "ROOT_CANONICALIZATION") RootShape() else null
            if (stage == "ROOT_CANONICALIZATION") {
                state.rootShape?.observe(element, state.canonicalizeDepth)
                if (element is JsonObject) {
                    state.frames.lastOrNull { it.family == "SEMANTIC_CORE" }?.apply {
                        referenceJson = B1CanonicalJsonReferenceWriter.canonicalJson(element)
                        referenceDigest = B1CanonicalJsonReferenceWriter.canonicalDigest(element)
                    }
                }
            }
            recordContextNodeVisit(state, stage)
            state.frames.addLast(
                Frame(
                    rawKind = "CANONICALIZE_ROOT",
                    family = "CANONICALIZE_ROOT",
                    stage = stage,
                    mode = INCLUSIVE,
                    startNanos = System.nanoTime(),
                    startAllocatedBytes = currentThreadAllocatedBytes(),
                ),
            )
        }

        internal fun exitCanonicalize() {
            val state = threadState.get() ?: return
            if (state.canonicalizeDepth <= 0) {
                markIntegrityError("canonicalize-exit-without-entry")
                return
            }
            state.canonicalizeDepth--
            if (state.canonicalizeDepth > 0) return
            val frame = state.frames.removeLastOrNull()
            if (frame == null || frame.rawKind != "CANONICALIZE_ROOT") {
                markIntegrityError("canonicalize-root-mismatch actual=${frame?.rawKind}")
                return
            }
            finishFrame(state, frame)
            if (frame.stage == "ROOT_CANONICALIZATION") {
                state.segment.recordRootShape(state.rootShape ?: run {
                    markIntegrityError("root-shape-missing")
                    return
                })
                state.rootShape = null
                state.canonicalizeRootStage = null
            }
        }

        internal fun recordSemanticJsonResult(result: Any?) {
            val state = threadState.get() ?: return
            val frame = state.frames.lastOrNull()
            val semantic = result as? String ?: run {
                markIntegrityError("semantic-result-not-string")
                return
            }
            if (frame?.family == "SEMANTIC_CORE") {
                val reference = frame.referenceJson ?: run {
                    markIntegrityError("reference-json-missing")
                    return
                }
                increment(state.segment.counters, "REFERENCE_WRITER_REAL_CASES_CHECKED")
                if (!semantic.toByteArray(StandardCharsets.UTF_8)
                        .contentEquals(reference.toByteArray(StandardCharsets.UTF_8))) {
                    increment(state.segment.counters, "REFERENCE_WRITER_BYTE_MISMATCHES")
                }
                state.pendingReferenceDigest = frame.referenceDigest
                return
            }
            if (frame?.isSemanticSource != true) return
            state.segment.recordRootStringLength(semantic.length.toLong())
            state.rootUtf8Pending = true
            val observation = frame.sourceObservation ?: return
            val category = referenceCategory(observation)
            if (category == null || !state.segment.referenceCategories.add(category)) return
            increment(state.segment.counters, "REFERENCE_CASE_$category")
        }

        internal fun recordDigestResult(result: Any?) {
            val state = threadState.get() ?: return
            val actual = result as? String ?: run {
                markIntegrityError("digest-result-not-string")
                return
            }
            val expected = state.pendingReferenceDigest ?: return
            increment(state.segment.counters, "DIRECT_DIGEST_REAL_CASES_CHECKED")
            if (actual != expected) increment(state.segment.counters, "DIRECT_DIGEST_REAL_MISMATCHES")
            state.pendingReferenceDigest = null
        }

        internal fun recordByteArrayResult(result: Any?) {
            val state = threadState.get() ?: return
            val bytes = result as? ByteArray ?: run {
                markIntegrityError("utf8-result-not-byte-array")
                return
            }
            if (state.rootUtf8Pending) {
                state.segment.recordRootUtf8Length(bytes.size.toLong())
                state.rootUtf8Pending = false
            }
        }

        private fun recordContextNodeVisit(state: TransitionState, stage: String?) {
            increment(
                state.segment.counters,
                when (stage) {
                    "ROOT_CANONICALIZATION" -> "ROOT_CANONICALIZATION_NODE_VISITS"
                    "ACTION_FINGERPRINT_SORT_KEY" -> "ACTION_SORT_KEY_CANONICALIZATION_NODE_VISITS"
                    else -> "OTHER_CANONICALIZATION_NODE_VISITS"
                },
            )
        }

        internal fun startOperation(operation: String) {
            val state = threadState.get() ?: return
            if (operation == "JSON_TO_STRING") {
                increment(state.segment.counters, "JSON_TO_STRING_CALLS")
                if (state.frames.any { it.family == "SORT_FINGERPRINTS" }) {
                    increment(state.segment.counters, "FINGERPRINT_TO_STRING_CALLS")
                }
            }
            val stage = operationStage(state, operation)
            state.frames.addLast(
                Frame(
                    rawKind = "OPERATION:$operation",
                    family = "OPERATION",
                    stage = stage,
                    mode = EXCLUSIVE,
                    startNanos = System.nanoTime(),
                    startAllocatedBytes = currentThreadAllocatedBytes(),
                ),
            )
            increment(state.segment.counters, operationCounter(operation))
        }

        internal fun endOperation(operation: String) {
            val state = threadState.get() ?: return
            val frame = state.frames.removeLastOrNull()
            if (frame == null || frame.rawKind != "OPERATION:$operation") {
                markIntegrityError("operation-exit-mismatch expected=$operation actual=${frame?.rawKind}")
                return
            }
            finishFrame(state, frame)
        }

        internal fun recordConsumer(family: String) {
            val state = threadState.get() ?: return
            increment(state.segment.consumerCalls, family)
        }

        internal fun snapshot(): Snapshot {
            if (currentSegment.get() != null || threadState.get() != null) {
                markIntegrityError("snapshot-with-active-state")
            }
            return Snapshot(
                schemaVersion = "argentum-b1-canonicalization-pipeline-v1",
                allocationMeasurement = if (allocationBean == null) {
                    "NOT_AVAILABLE"
                } else {
                    "ThreadMXBean.getThreadAllocatedBytes"
                },
                integrity = if (integrityErrors.get() == 0L) {
                    "PASS"
                } else {
                    "FAIL errors=${integrityErrors.get()}"
                },
                integrityDetails = integrityDetails.toSortedMap().mapValues { it.value.sum() },
                segments = segments.map(Segment::snapshot),
            )
        }

        private fun enterSemanticJson(state: TransitionState, argument: Any?) {
            when (argument) {
                is TrainingObservation -> {
                    increment(state.segment.counters, "SEMANTIC_JSON_SOURCE_CALLS")
                    if (state.observationObjects.add(argument)) {
                        state.segment.distinctObservationsCanonicalized++
                    } else {
                        state.segment.sameObjectRecanonicalizationCount++
                    }
                    if (state.observations.any { previous ->
                            previous.copy(stateDigest = "") == argument.copy(stateDigest = "")
                        }) {
                        state.segment.samePublicObservationRecanonicalizationCount++
                    }
                    state.observations += argument
                    pushTimed(
                        state,
                        "SEMANTIC_JSON",
                        "SEMANTIC_JSON_SOURCE_TOTAL",
                        INCLUSIVE,
                        isSemanticSource = true,
                        family = "SEMANTIC_JSON_SOURCE",
                        sourceObservation = argument,
                    )
                }
                is com.wingedsheep.gym.contract.PlayerObservationV1 -> {
                    increment(state.segment.counters, "SEMANTIC_JSON_DURABLE_CALLS")
                    pushTimed(
                        state,
                        "SEMANTIC_JSON",
                        "SEMANTIC_JSON_DURABLE_TOTAL",
                        INCLUSIVE,
                        family = "SEMANTIC_JSON_DURABLE",
                    )
                }
                is JsonObject -> {
                    pushTimed(
                        state,
                        "SEMANTIC_JSON",
                        "SEMANTIC_FIELD_PROJECTION",
                        EXCLUSIVE,
                        family = "SEMANTIC_CORE",
                    )
                }
                else -> {
                    increment(state.segment.counters, "SEMANTIC_JSON_UNKNOWN_ARGUMENT_CALLS")
                    pushUntimed(state, "SEMANTIC_JSON")
                }
            }
        }

        private fun enterStateDigest(state: TransitionState, argument: Any?) {
            when (argument) {
                is TrainingObservation -> {
                    increment(state.segment.counters, "SOURCE_OBSERVATION_DIGEST_CALLS")
                    pushTimed(state, "STATE_DIGEST_COMPUTE", "SOURCE_DIGEST_TOTAL", INCLUSIVE)
                }
                is com.wingedsheep.gym.contract.PlayerObservationV1 -> {
                    increment(state.segment.counters, "DURABLE_A1_A2_DIGEST_CALLS")
                    pushTimed(state, "STATE_DIGEST_COMPUTE", "DURABLE_DIGEST_TOTAL", INCLUSIVE)
                }
                else -> {
                    increment(state.segment.counters, "STATE_DIGEST_UNKNOWN_ARGUMENT_CALLS")
                    pushUntimed(state, "STATE_DIGEST_COMPUTE")
                }
            }
        }

        private fun operationStage(state: TransitionState, operation: String): String? = when (operation) {
            "ENCODE_TO_JSON_ELEMENT" -> when {
                state.frames.any { it.family == "SEMANTIC_ACTION_FINGERPRINT" } ->
                    "ACTION_FINGERPRINT_BUILD"
                state.frames.any { it.family == "SEMANTIC_JSON_SOURCE" || it.family == "SEMANTIC_JSON_DURABLE" } ->
                    "TRAINING_OBSERVATION_SERIALIZATION"
                else -> "OTHER_SERIALIZATION"
            }
            "JSON_TO_STRING" -> when {
                state.frames.any { it.family == "CANONICALIZE_ROOT" } -> null
                state.frames.any { it.family == "SORT_FINGERPRINTS" } -> "ACTION_FINGERPRINT_SORT_KEY"
                else -> "JSON_STRING_MATERIALIZATION"
            }
            "UTF8_ENCODING" -> "UTF8_ENCODING"
            "SHA256_DIGEST" -> "SHA256_DIGEST"
            "HEX_RENDER" -> "DIGEST_HEX_RENDER"
            else -> "PIPELINE_OTHER_OPERATION"
        }

        private fun operationCounter(operation: String): String = when (operation) {
            "ENCODE_TO_JSON_ELEMENT" -> "ENCODE_TO_JSON_ELEMENT_CALLS"
            "JSON_TO_STRING" -> "JSON_TO_STRING_OPERATION_MARKERS"
            "UTF8_ENCODING" -> "UTF8_ENCODING_CALLS"
            "SHA256_DIGEST" -> "SHA256_CALLS"
            "HEX_RENDER" -> "HEX_RENDER_CALLS"
            else -> "OTHER_OPERATION_CALLS"
        }

        private fun nearestSemanticSource(state: TransitionState): Frame? =
            state.frames.asReversed().firstOrNull { it.isSemanticSource }

        private fun referenceCategory(observation: TrainingObservation): String? = when {
            observation.pendingDecision?.requiresStructuredResponse == true -> "STRUCTURED_PENDING"
            observation.legalActions.size >= 20 -> "LARGE_LEGAL_ACTION"
            else -> "NORMAL_ACTION"
        }

        private fun pushTimed(
            state: TransitionState,
            rawKind: String,
            stage: String,
            mode: TimingMode,
            isSemanticSource: Boolean = false,
            family: String = rawKind,
            sourceObservation: TrainingObservation? = null,
            referenceJson: String? = null,
            referenceDigest: String? = null,
        ) {
            state.frames.addLast(
                Frame(
                    rawKind = rawKind,
                    family = family,
                    stage = stage,
                    mode = mode,
                    startNanos = System.nanoTime(),
                    startAllocatedBytes = currentThreadAllocatedBytes(),
                    isSemanticSource = isSemanticSource,
                    sourceObservation = sourceObservation,
                    referenceJson = referenceJson,
                    referenceDigest = referenceDigest,
                ),
            )
        }

        private fun pushUntimed(state: TransitionState, rawKind: String) {
            val family = when (rawKind) {
                "SEMANTIC_JSON" -> "SEMANTIC_JSON"
                "STATE_DIGEST_COMPUTE" -> "STATE_DIGEST_COMPUTE"
                else -> rawKind
            }
            state.frames.addLast(
                Frame(rawKind = rawKind, family = family, stage = null, mode = null),
            )
        }

        private fun finishFrame(state: TransitionState, frame: Frame) {
            if (frame.stage == null || frame.mode == null) return
            val inclusiveWall = (System.nanoTime() - frame.startNanos).coerceAtLeast(0L)
            val exclusiveWall = (inclusiveWall - frame.childWallNanos).coerceAtLeast(0L)
            val endAllocated = currentThreadAllocatedBytes()
            val inclusiveAllocated = if (
                frame.startAllocatedBytes != null && endAllocated != null
            ) {
                (endAllocated - frame.startAllocatedBytes).coerceAtLeast(0L)
            } else {
                null
            }
            val exclusiveAllocated = inclusiveAllocated?.let {
                (it - frame.childAllocatedBytes).coerceAtLeast(0L)
            }
            val wall = if (frame.mode == INCLUSIVE) inclusiveWall else exclusiveWall
            val allocated = if (frame.mode == INCLUSIVE) inclusiveAllocated else exclusiveAllocated
            state.segment.stage(frame.stage).record(wall, allocated)
            state.frames.lastOrNull()?.let { parent ->
                parent.childWallNanos += inclusiveWall
                if (inclusiveAllocated != null) parent.childAllocatedBytes += inclusiveAllocated
            }
        }

        private fun increment(target: ConcurrentHashMap<String, LongAdder>, key: String, amount: Long = 1L) {
            target.computeIfAbsent(key) { LongAdder() }.add(amount)
        }

        private fun markIntegrityError(reason: String) {
            integrityErrors.incrementAndGet()
            integrityDetails.computeIfAbsent(reason) { LongAdder() }.increment()
        }
    }

    internal class SegmentHandle internal constructor(
        private val session: Session,
        private val segment: Segment,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) session.endSegment(segment)
        }
    }

    private enum class TimingMode { EXCLUSIVE, INCLUSIVE }

    private val EXCLUSIVE = TimingMode.EXCLUSIVE
    private val INCLUSIVE = TimingMode.INCLUSIVE

    internal class RootShape {
        var objectNodes: Long = 0
        var arrayNodes: Long = 0
        var primitiveNodes: Long = 0
        var totalNodes: Long = 0
        var objectMemberCount: Long = 0
        var arrayElementCount: Long = 0
        var maxDepth: Long = 0
        var stringValueCount: Long = 0
        var numberValueCount: Long = 0
        var booleanValueCount: Long = 0
        var nullValueCount: Long = 0
        val objectKeysPerSort = mutableListOf<Long>()

        fun observe(element: Any?, depth: Int) {
            totalNodes++
            maxDepth = maxOf(maxDepth, depth.toLong())
            when (element) {
                is JsonObject -> {
                    objectNodes++
                    objectMemberCount += element.size.toLong()
                    objectKeysPerSort += element.size.toLong()
                }
                is JsonArray -> {
                    arrayNodes++
                    arrayElementCount += element.size.toLong()
                }
                is JsonNull -> {
                    primitiveNodes++
                    nullValueCount++
                }
                is JsonPrimitive -> {
                    primitiveNodes++
                    when {
                        element.isString -> stringValueCount++
                        element.content == "true" || element.content == "false" -> booleanValueCount++
                        else -> numberValueCount++
                    }
                }
            }
        }
    }

    private class TransitionState(
        val segment: Segment,
        val startNanos: Long,
    ) {
        val frames = ArrayDeque<Frame>()
        var canonicalizeDepth: Int = 0
        var canonicalizeRootStage: String? = null
        var rootShape: RootShape? = null
        var rootUtf8Pending: Boolean = false
        var pendingReferenceDigest: String? = null
        val observationObjects = Collections.newSetFromMap(IdentityHashMap<TrainingObservation, Boolean>())
        val observations = mutableListOf<TrainingObservation>()
    }

    private class Frame(
        val rawKind: String,
        val family: String,
        val stage: String?,
        val mode: TimingMode?,
        val startNanos: Long = 0L,
        val startAllocatedBytes: Long? = null,
        val isSemanticSource: Boolean = false,
        val sourceObservation: TrainingObservation? = null,
        var referenceJson: String? = null,
        var referenceDigest: String? = null,
    ) {
        var childWallNanos: Long = 0L
        var childAllocatedBytes: Long = 0L
        var fingerprintCount: Int = 0
        var canonicalizeNodeVisits: Long = 0L
    }

    internal class Segment(internal val label: String) {
        internal val transitions = LongAdder()
        internal val apiCalls = ConcurrentHashMap<String, LongAdder>()
        internal val counters = ConcurrentHashMap<String, LongAdder>()
        internal val consumerCalls = ConcurrentHashMap<String, LongAdder>()
        private val stages = ConcurrentHashMap<String, StageTotals>()
        internal val fingerprintCounts = CopyOnWriteArrayList<Long>()
        internal val canonicalizeNodeVisitsPerRoot = CopyOnWriteArrayList<Long>()
        private val rootObjectNodes = mutableListOf<Long>()
        private val rootArrayNodes = mutableListOf<Long>()
        private val rootPrimitiveNodes = mutableListOf<Long>()
        private val rootTotalNodes = mutableListOf<Long>()
        private val rootObjectMemberCounts = mutableListOf<Long>()
        private val rootArrayElementCounts = mutableListOf<Long>()
        private val rootMaxDepths = mutableListOf<Long>()
        private val rootStringValueCounts = mutableListOf<Long>()
        private val rootNumberValueCounts = mutableListOf<Long>()
        private val rootBooleanValueCounts = mutableListOf<Long>()
        private val rootNullValueCounts = mutableListOf<Long>()
        private val rootObjectKeysPerSort = mutableListOf<Long>()
        private val rootStringLengthChars = mutableListOf<Long>()
        private val rootUtf8LengthBytes = mutableListOf<Long>()
        internal val referenceCategories = mutableSetOf<String>()
        internal var distinctObservationsCanonicalized: Long = 0L
        internal var sameObjectRecanonicalizationCount: Long = 0L
        internal var samePublicObservationRecanonicalizationCount: Long = 0L

        internal fun stage(name: String): StageTotals = stages.computeIfAbsent(name) { StageTotals() }

        internal fun recordRootShape(shape: RootShape) {
            rootObjectNodes += shape.objectNodes
            rootArrayNodes += shape.arrayNodes
            rootPrimitiveNodes += shape.primitiveNodes
            rootTotalNodes += shape.totalNodes
            rootObjectMemberCounts += shape.objectMemberCount
            rootArrayElementCounts += shape.arrayElementCount
            rootMaxDepths += shape.maxDepth
            rootStringValueCounts += shape.stringValueCount
            rootNumberValueCounts += shape.numberValueCount
            rootBooleanValueCounts += shape.booleanValueCount
            rootNullValueCounts += shape.nullValueCount
            rootObjectKeysPerSort += shape.objectKeysPerSort
        }

        internal fun recordRootStringLength(value: Long) {
            rootStringLengthChars += value
        }

        internal fun recordRootUtf8Length(value: Long) {
            rootUtf8LengthBytes += value
        }

        private fun rootStructureSnapshot(): RootStructureSnapshot = RootStructureSnapshot(
            roots = rootTotalNodes.size.toLong(),
            objectNodes = DistributionSnapshot.from(rootObjectNodes),
            arrayNodes = DistributionSnapshot.from(rootArrayNodes),
            primitiveNodes = DistributionSnapshot.from(rootPrimitiveNodes),
            totalNodes = DistributionSnapshot.from(rootTotalNodes),
            objectMemberCount = DistributionSnapshot.from(rootObjectMemberCounts),
            arrayElementCount = DistributionSnapshot.from(rootArrayElementCounts),
            maxDepth = DistributionSnapshot.from(rootMaxDepths),
            stringValueCount = DistributionSnapshot.from(rootStringValueCounts),
            numberValueCount = DistributionSnapshot.from(rootNumberValueCounts),
            booleanValueCount = DistributionSnapshot.from(rootBooleanValueCounts),
            nullValueCount = DistributionSnapshot.from(rootNullValueCounts),
            stringLengthChars = DistributionSnapshot.from(rootStringLengthChars),
            utf8LengthBytes = DistributionSnapshot.from(rootUtf8LengthBytes),
            objectKeysPerSort = DistributionSnapshot.from(rootObjectKeysPerSort),
        )

        internal fun snapshot(): SegmentSnapshot = SegmentSnapshot(
            label = label,
            transitions = transitions.sum(),
            apiCalls = apiCalls.toSortedMap().mapValues { it.value.sum() },
            counters = counters.toSortedMap().mapValues { it.value.sum() },
            consumerCalls = consumerCalls.toSortedMap().mapValues { it.value.sum() },
            stages = stages.keys.sorted().associateWith { stages.getValue(it).snapshot() },
            fingerprintCounts = DistributionSnapshot.from(fingerprintCounts),
            canonicalizeNodeVisitsPerRoot = DistributionSnapshot.from(canonicalizeNodeVisitsPerRoot),
            rootStructure = rootStructureSnapshot(),
            distinctObservationsCanonicalized = distinctObservationsCanonicalized,
            sameObjectRecanonicalizationCount = sameObjectRecanonicalizationCount,
            samePublicObservationRecanonicalizationCount = samePublicObservationRecanonicalizationCount,
        )
    }

    internal class StageTotals {
        private val calls = LongAdder()
        private val wallNanos = LongAdder()
        private val allocatedBytes = LongAdder()
        private val allocationSamples = LongAdder()

        internal fun record(wall: Long, allocated: Long?) {
            calls.increment()
            wallNanos.add(wall)
            if (allocated != null) {
                allocatedBytes.add(allocated)
                allocationSamples.increment()
            }
        }

        internal fun snapshot(): StageSnapshot = StageSnapshot(
            calls = calls.sum(),
            wallNanos = wallNanos.sum(),
            allocatedBytes = allocatedBytes.sum().takeIf { allocationSamples.sum() > 0L },
            allocationSamples = allocationSamples.sum(),
        )
    }

    @JvmStatic
    @JvmName("start")
    internal fun start(): Session {
        val session = Session()
        check(activeSession.compareAndSet(null, session)) {
            "B1 canonicalization pipeline probe session is already active"
        }
        return session
    }

    @JvmStatic
    @JvmName("stop")
    internal fun stop(session: Session): Snapshot {
        check(activeSession.compareAndSet(session, null)) {
            "B1 canonicalization pipeline probe session is not active"
        }
        return session.snapshot()
    }

    @JvmStatic
    @JvmName("beginTransition")
    internal fun beginTransition() {
        activeSession.get()?.beginTransition()
    }

    @JvmStatic
    @JvmName("endTransition")
    internal fun endTransition() {
        activeSession.get()?.endTransition()
    }

    @JvmStatic
    @JvmName("enterMethod")
    internal fun enterMethod(kind: String, argument: Any?) {
        activeSession.get()?.enterMethod(kind, argument)
    }

    @JvmStatic
    @JvmName("exitMethod")
    internal fun exitMethod(kind: String) {
        activeSession.get()?.exitMethod(kind)
    }

    @JvmStatic
    @JvmName("enterCanonicalize")
    internal fun enterCanonicalize(element: Any?) {
        activeSession.get()?.enterCanonicalize(element)
    }

    @JvmStatic
    @JvmName("exitCanonicalize")
    internal fun exitCanonicalize() {
        activeSession.get()?.exitCanonicalize()
    }

    @JvmStatic
    @JvmName("startOperation")
    internal fun startOperation(operation: String) {
        activeSession.get()?.startOperation(operation)
    }

    @JvmStatic
    @JvmName("endOperation")
    internal fun endOperation(operation: String) {
        activeSession.get()?.endOperation(operation)
    }

    @JvmStatic
    @JvmName("recordSemanticJsonResult")
    internal fun recordSemanticJsonResult(result: Any?) {
        activeSession.get()?.recordSemanticJsonResult(result)
    }

    @JvmStatic
    @JvmName("recordDigestResult")
    internal fun recordDigestResult(result: Any?) {
        activeSession.get()?.recordDigestResult(result)
    }

    @JvmStatic
    @JvmName("recordByteArrayResult")
    internal fun recordByteArrayResult(result: Any?) {
        activeSession.get()?.recordByteArrayResult(result)
    }

    @JvmStatic
    @JvmName("recordConsumer")
    internal fun recordConsumer(family: String) {
        activeSession.get()?.recordConsumer(family)
    }

    internal fun beginSegment(label: String): SegmentHandle? = activeSession.get()?.beginSegment(label)

    private fun currentThreadAllocatedBytes(): Long? = allocationBean
        ?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        ?.takeIf { it >= 0L }
}
