package com.wingedsheep.gym

import com.wingedsheep.gym.contract.ObservationCanonicalizer
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Test-only real-root A/B probe. It retains sizes, hashes, and timings, never root payloads. */
internal object B1DirectWriterRealRootProbe {
    private val activeSession = AtomicReference<Session?>()
    private val allocationBean = (ManagementFactory.getThreadMXBean() as?
        com.sun.management.ThreadMXBean)?.takeIf {
        it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
    }

    @Serializable
    internal data class ModeSnapshot(
        val roots: Long,
        val writeNanos: B1CanonicalizationPipelineProbe.DistributionSnapshot,
        val utf8Nanos: B1CanonicalizationPipelineProbe.DistributionSnapshot,
        val digestNanos: B1CanonicalizationPipelineProbe.DistributionSnapshot,
        val hexNanos: B1CanonicalizationPipelineProbe.DistributionSnapshot,
        val totalNanos: B1CanonicalizationPipelineProbe.DistributionSnapshot,
        val allocatedBytes: B1CanonicalizationPipelineProbe.DistributionSnapshot,
        val outputBytes: B1CanonicalizationPipelineProbe.DistributionSnapshot,
    )

    @Serializable
    internal data class SegmentSnapshot(
        val label: String,
        val roots: Long,
        val current: ModeSnapshot,
        val candidateUtf8: ModeSnapshot,
        val directDigest: ModeSnapshot,
        val byteMismatches: Long,
        val digestMismatches: Long,
        val outputLengthMismatches: Long,
        val candidateRepeatabilityMismatches: Long,
        val directDigestRepeatabilityMismatches: Long,
    )

    @Serializable
    internal data class Snapshot(
        val schemaVersion: String,
        val allocationMeasurement: String,
        val warmupTarget: Long,
        val warmupOperations: Long,
        val integrity: String,
        val integrityDetails: Map<String, Long>,
        val segments: List<SegmentSnapshot>,
    )

    internal class Session internal constructor() {
        private val currentSegment = AtomicReference<Segment?>()
        private val segments = CopyOnWriteArrayList<Segment>()
        private val integrityErrors = AtomicLong()
        private val integrityDetails = ConcurrentHashMap<String, AtomicLong>()
        private val writers = ThreadLocal.withInitial { B1DirectCanonicalUtf8Writer() }
        private val suppressed = ThreadLocal.withInitial { false }
        private val warmupTarget = 1_000L
        private val warmupOperations = AtomicLong()

        internal fun beginSegment(label: String): SegmentHandle {
            val segment = Segment(label)
            check(currentSegment.compareAndSet(null, segment)) {
                "real-root writer probe segment is already active"
            }
            segments += segment
            return SegmentHandle(this, segment)
        }

        internal fun endSegment(segment: Segment) {
            check(currentSegment.compareAndSet(segment, null)) {
                "real-root writer probe segment is not active"
            }
        }

        internal fun recordRoot(element: JsonObject) {
            if (suppressed.get()) return
            val segment = currentSegment.get()
            if (segment == null) {
                while (true) {
                    val completed = warmupOperations.get()
                    if (completed >= warmupTarget) return
                    if (warmupOperations.compareAndSet(completed, completed + 1L)) {
                        runWarmup(element, writers.get())
                        return
                    }
                }
            }
            val rootIndex = segment.roots.getAndIncrement()
            val writer = writers.get()
            val outcomes = arrayOfNulls<RootOutcome>(3)
            val order = when (rootIndex % 3L) {
                0L -> intArrayOf(CURRENT, CANDIDATE_UTF8, DIRECT_DIGEST)
                1L -> intArrayOf(CANDIDATE_UTF8, DIRECT_DIGEST, CURRENT)
                else -> intArrayOf(DIRECT_DIGEST, CURRENT, CANDIDATE_UTF8)
            }
            order.forEach { mode ->
                outcomes[mode] = when (mode) {
                    CURRENT -> runCurrent(element, segment)
                    CANDIDATE_UTF8 -> runCandidateUtf8(element, writer, segment)
                    DIRECT_DIGEST -> runDirectDigest(element, writer, segment)
                    else -> error("unknown real-root writer mode: $mode")
                }
            }

            val current = checkNotNull(outcomes[CURRENT])
            val candidate = checkNotNull(outcomes[CANDIDATE_UTF8])
            val direct = checkNotNull(outcomes[DIRECT_DIGEST])
            if (current.outputBytes.contentEquals(candidate.outputBytes)) {
                // Expected path; the comparison is kept explicit for the exact-byte contract.
            } else {
                segment.byteMismatches.incrementAndGet()
            }
            if (current.outputLength != candidate.outputLength ||
                current.outputLength != direct.outputLength
            ) {
                segment.outputLengthMismatches.incrementAndGet()
            }
            if (current.digest != candidate.digest || current.digest != direct.digest) {
                segment.digestMismatches.incrementAndGet()
            }
            segment.checkRepeatability(current.digest, candidate.digest, direct.digest, current.outputLength)
        }

        private fun runWarmup(element: JsonObject, writer: B1DirectCanonicalUtf8Writer) {
            suppressInstrumentation {
                val current = ObservationCanonicalizer.canonicalJson(element)
                    .toByteArray(StandardCharsets.UTF_8)
                MessageDigest.getInstance("SHA-256").digest(current)
                val candidate = writer.writeToBuffer(element)
                MessageDigest.getInstance("SHA-256").run {
                    update(candidate.bytes, 0, candidate.size)
                    digest()
                }
                MessageDigest.getInstance("SHA-256").also { digest ->
                    writer.writeToDigest(element, digest)
                    digest.digest()
                }
            }
        }

        private fun runCurrent(element: JsonObject, segment: Segment): RootOutcome =
            suppressInstrumentation {
                val totalStart = System.nanoTime()
                val allocationStart = currentThreadAllocatedBytes()
                val writeStart = System.nanoTime()
                val json = ObservationCanonicalizer.canonicalJson(element)
                val writeNanos = System.nanoTime() - writeStart
                val utf8Start = System.nanoTime()
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                val utf8Nanos = System.nanoTime() - utf8Start
                val digestStart = System.nanoTime()
                val digestBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
                val digestNanos = System.nanoTime() - digestStart
                val hexStart = System.nanoTime()
                val digest = hex(digestBytes)
                val hexNanos = System.nanoTime() - hexStart
                val totalNanos = System.nanoTime() - totalStart
                val allocated = allocatedSince(allocationStart)
                segment.current.record(writeNanos, utf8Nanos, digestNanos, hexNanos, totalNanos, allocated, bytes.size)
                RootOutcome(bytes, digest, bytes.size)
            }

        private fun runCandidateUtf8(
            element: JsonObject,
            writer: B1DirectCanonicalUtf8Writer,
            segment: Segment,
        ): RootOutcome {
            val totalStart = System.nanoTime()
            val allocationStart = currentThreadAllocatedBytes()
            val writeStart = System.nanoTime()
            val encoded = writer.writeToBuffer(element)
            val writeNanos = System.nanoTime() - writeStart
            val digestStart = System.nanoTime()
            val digestBytes = MessageDigest.getInstance("SHA-256").run {
                update(encoded.bytes, 0, encoded.size)
                digest()
            }
            val digestNanos = System.nanoTime() - digestStart
            val hexStart = System.nanoTime()
            val digest = hex(digestBytes)
            val hexNanos = System.nanoTime() - hexStart
            val totalNanos = System.nanoTime() - totalStart
            val allocated = allocatedSince(allocationStart)
            segment.candidateUtf8.record(writeNanos, 0L, digestNanos, hexNanos, totalNanos, allocated, encoded.size)
            val bytes = encoded.bytes.copyOf(encoded.size)
            return RootOutcome(bytes, digest, encoded.size)
        }

        private fun runDirectDigest(
            element: JsonObject,
            writer: B1DirectCanonicalUtf8Writer,
            segment: Segment,
        ): RootOutcome = suppressInstrumentation {
            val totalStart = System.nanoTime()
            val allocationStart = currentThreadAllocatedBytes()
            val writeStart = System.nanoTime()
            val digest = MessageDigest.getInstance("SHA-256")
            val outputLength = writer.writeToDigest(element, digest)
            val digestBytes = digest.digest()
            val writeAndHashNanos = System.nanoTime() - writeStart
            val hexStart = System.nanoTime()
            val digestHex = hex(digestBytes)
            val hexNanos = System.nanoTime() - hexStart
            val totalNanos = System.nanoTime() - totalStart
            val allocated = allocatedSince(allocationStart)
            segment.directDigest.record(
                writeAndHashNanos,
                0L,
                0L,
                hexNanos,
                totalNanos,
                allocated,
                outputLength,
            )
            RootOutcome(ByteArray(0), digestHex, outputLength)
        }

        private fun <T> suppressInstrumentation(block: () -> T): T {
            val previous = suppressed.get()
            suppressed.set(true)
            return try {
                B1CanonicalizationPipelineProbe.suppress(block)
            } finally {
                suppressed.set(previous)
            }
        }

        internal fun snapshot(): Snapshot = Snapshot(
            schemaVersion = "argentum-b1-direct-writer-real-root-v1",
            allocationMeasurement = if (allocationBean == null) {
                "NOT_AVAILABLE"
            } else {
                "ThreadMXBean.getThreadAllocatedBytes"
            },
            warmupTarget = warmupTarget,
            warmupOperations = warmupOperations.get(),
            integrity = if (integrityErrors.get() == 0L) {
                "PASS"
            } else {
                "FAIL errors=${integrityErrors.get()}"
            },
            integrityDetails = integrityDetails.entries.associate { it.key to it.value.get() },
            segments = segments.map(Segment::snapshot),
        )
    }

    internal class SegmentHandle internal constructor(
        private val session: Session,
        private val segment: Segment,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                session.endSegment(segment)
            }
        }
    }

    internal class Segment(private val label: String) {
        val roots = AtomicLong()
        internal val current = ModeAccumulator()
        internal val candidateUtf8 = ModeAccumulator()
        internal val directDigest = ModeAccumulator()
        val byteMismatches = AtomicLong()
        val digestMismatches = AtomicLong()
        val outputLengthMismatches = AtomicLong()
        val candidateRepeatabilityMismatches = AtomicLong()
        val directDigestRepeatabilityMismatches = AtomicLong()
        private val repeatability = ConcurrentHashMap<String, RepeatSignature>()

        fun checkRepeatability(currentDigest: String, candidateDigest: String, directDigest: String, length: Int) {
            val previous = repeatability.putIfAbsent(
                currentDigest,
                RepeatSignature(candidateDigest, directDigest, length),
            ) ?: return
            if (previous.candidateDigest != candidateDigest || previous.length != length) {
                candidateRepeatabilityMismatches.incrementAndGet()
            }
            if (previous.directDigest != directDigest || previous.length != length) {
                directDigestRepeatabilityMismatches.incrementAndGet()
            }
        }

        fun snapshot(): SegmentSnapshot = SegmentSnapshot(
            label = label,
            roots = roots.get(),
            current = current.snapshot(),
            candidateUtf8 = candidateUtf8.snapshot(),
            directDigest = directDigest.snapshot(),
            byteMismatches = byteMismatches.get(),
            digestMismatches = digestMismatches.get(),
            outputLengthMismatches = outputLengthMismatches.get(),
            candidateRepeatabilityMismatches = candidateRepeatabilityMismatches.get(),
            directDigestRepeatabilityMismatches = directDigestRepeatabilityMismatches.get(),
        )
    }

    internal class ModeAccumulator {
        private val writeNanos = CopyOnWriteArrayList<Long>()
        private val utf8Nanos = CopyOnWriteArrayList<Long>()
        private val digestNanos = CopyOnWriteArrayList<Long>()
        private val hexNanos = CopyOnWriteArrayList<Long>()
        private val totalNanos = CopyOnWriteArrayList<Long>()
        private val allocatedBytes = CopyOnWriteArrayList<Long>()
        private val outputBytes = CopyOnWriteArrayList<Long>()

        fun record(
            write: Long,
            utf8: Long,
            digest: Long,
            hex: Long,
            total: Long,
            allocated: Long?,
            output: Int,
        ) {
            writeNanos += write
            utf8Nanos += utf8
            digestNanos += digest
            hexNanos += hex
            totalNanos += total
            allocated?.let { allocatedBytes += it }
            outputBytes += output.toLong()
        }

        fun snapshot(): ModeSnapshot = ModeSnapshot(
            roots = totalNanos.size.toLong(),
            writeNanos = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(writeNanos),
            utf8Nanos = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(utf8Nanos),
            digestNanos = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(digestNanos),
            hexNanos = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(hexNanos),
            totalNanos = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(totalNanos),
            allocatedBytes = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(allocatedBytes),
            outputBytes = B1CanonicalizationPipelineProbe.DistributionSnapshot.from(outputBytes),
        )
    }

    private data class RepeatSignature(
        val candidateDigest: String,
        val directDigest: String,
        val length: Int,
    )

    private data class RootOutcome(
        val outputBytes: ByteArray,
        val digest: String,
        val outputLength: Int,
    )

    private const val CURRENT = 0
    private const val CANDIDATE_UTF8 = 1
    private const val DIRECT_DIGEST = 2
    private val hexChars = "0123456789abcdef".toCharArray()

    private fun allocatedSince(start: Long?): Long? = start?.let {
        currentThreadAllocatedBytes()?.minus(it)?.coerceAtLeast(0L)
    }

    private fun currentThreadAllocatedBytes(): Long? = allocationBean
        ?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        ?.takeIf { it >= 0L }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append(hexChars[unsigned ushr 4])
            append(hexChars[unsigned and 0x0f])
        }
    }

    @JvmStatic
    internal fun start(): Session {
        val session = Session()
        check(activeSession.compareAndSet(null, session)) {
            "real-root writer probe session is already active"
        }
        return session
    }

    @JvmStatic
    internal fun stop(session: Session): Snapshot {
        check(activeSession.compareAndSet(session, null)) {
            "real-root writer probe session is not active"
        }
        return session.snapshot()
    }

    internal fun beginSegment(label: String): SegmentHandle? = activeSession.get()?.beginSegment(label)

    @JvmStatic
    internal fun recordRoot(element: Any?) {
        val root = element as? JsonObject ?: return
        activeSession.get()?.recordRoot(root)
    }
}
