package com.wingedsheep.gym.contract

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.sdk.model.EntityId
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Version of the transport-free verified replay-frame contract. */
const val VERIFIED_REPLAY_FRAME_V1_VERSION: Int = 1

/** Stable identity of one public frame reconstructed from an authoritative replay. */
const val VERIFIED_REPLAY_FRAME_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-verified-replay-frame@v1"

/** Version of the complete verified-replay result contract. */
const val VERIFIED_REPLAY_VERIFICATION_V1_VERSION: Int = 1

/** Stable identity of a complete verified-replay result. */
const val VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-verified-replay-verification@v1"

/** How faithfully the authoritative replay fold was reproduced. */
@Serializable
enum class ReplayFidelity {
    /** Every declared action and required checkpoint matched, including the tail. */
    EXACT,

    /** The input stream folded, but the replay proof was absent or incomplete. */
    UNVERIFIED,

    /** The forward fold stopped at a mismatch, unsupported path, or invalid input. */
    DIVERGED,
}

/**
 * One transport-free public decision boundary from a replay.
 *
 * The frame carries the number of replay actions already consumed, not a live action/decision
 * handle. [observation] and [domain] are the existing A1/A2 authorities; this type only binds them
 * to one replay coordinate and to the digest of that exact domain.
 */
@Serializable
data class VerifiedReplayFrame(
    val version: Int = VERIFIED_REPLAY_FRAME_V1_VERSION,
    val schemaIdentity: String = VERIFIED_REPLAY_FRAME_V1_SCHEMA_IDENTITY,
    /** Number of recorded replay actions applied before this public boundary. */
    val replayActionIndex: Int,
    val perspectivePlayerId: EntityId,
    val observation: PlayerObservationV1,
    val domain: CompleteLegalDomainV1,
    val candidateDomainDigest: CandidateDomainDigestV1,
) {
    init {
        require(version == VERIFIED_REPLAY_FRAME_V1_VERSION) {
            "Unsupported verified replay-frame version: $version"
        }
        require(schemaIdentity == VERIFIED_REPLAY_FRAME_V1_SCHEMA_IDENTITY) {
            "Unsupported verified replay-frame identity: $schemaIdentity"
        }
        require(replayActionIndex >= 0) {
            "Verified replay-frame action index must not be negative"
        }
        require(observation.perspectivePlayerId == perspectivePlayerId) {
            "Verified replay-frame perspective does not match its public observation"
        }
        require(candidateDomainDigest == CandidateDomainDigestV1.from(domain)) {
            "Verified replay-frame candidate-domain digest does not match its complete domain"
        }
    }

    /** Explicitly named alias for consumers that prefer the full contract type name. */
    val playerObservation: PlayerObservationV1 get() = observation

    /** Explicitly named alias for consumers that prefer the full contract type name. */
    val completeLegalDomain: CompleteLegalDomainV1 get() = domain
}

/**
 * Result of one complete attempt to reconstruct the public boundaries of a replay.
 *
 * Failed verification may retain only the prefix that was independently established. The
 * constructor makes an incomplete prefix incapable of being labelled [ReplayFidelity.EXACT].
 */
@Serializable(with = VerifiedReplayVerificationSerializer::class)
class VerifiedReplayVerification(
    val version: Int = VERIFIED_REPLAY_VERIFICATION_V1_VERSION,
    val schemaIdentity: String = VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY,
    val replayVersion: Int,
    val replayActionCount: Int,
    val verifiedActionCount: Int,
    val fidelity: ReplayFidelity,
    frames: List<VerifiedReplayFrame> = emptyList(),
    /**
     * The initial public boundary was reconstructed. For non-empty v6 replays, action-count 0 is
     * not required to be persisted; when a zero-action checkpoint is present, its fingerprint must
     * still match the authoritative initial state.
     */
    val initialCheckpointVerified: Boolean = false,
    val intermediateCheckpointsVerified: Boolean = false,
    val tailCheckpointVerified: Boolean = false,
    val closure: EpisodeClosureV1? = null,
    val failureAtReplayActionIndex: Int? = null,
    val failureReason: String? = null,
) {
    /** Defensive immutable copy; callers cannot mutate a successful result after construction. */
    val frames: List<VerifiedReplayFrame> = Collections.unmodifiableList(frames.toList())

    init {
        require(version == VERIFIED_REPLAY_VERIFICATION_V1_VERSION) {
            "Unsupported verified replay-verification version: $version"
        }
        require(schemaIdentity == VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY) {
            "Unsupported verified replay-verification identity: $schemaIdentity"
        }
        require(replayVersion >= 1) { "Replay version must be positive" }
        require(replayActionCount >= 0) { "Replay action count must not be negative" }
        require(verifiedActionCount in 0..replayActionCount) {
            "Verified action count must be within the replay action range"
        }
        require(failureAtReplayActionIndex == null ||
            failureAtReplayActionIndex in 0..replayActionCount
        ) {
            "Replay verification failure coordinate is outside the replay action range"
        }

        val frameCoordinates = frames.map(VerifiedReplayFrame::replayActionIndex)
        val expectedFramePrefix = if (frameCoordinates.isEmpty()) {
            emptyList()
        } else {
            (frameCoordinates.first()..frameCoordinates.last()).toList()
        }
        require(frameCoordinates == expectedFramePrefix) {
            "Verified replay frames must be in contiguous replay-coordinate order"
        }
        require(frameCoordinates.isEmpty() || frameCoordinates.first() == 0) {
            "Verified replay frame prefixes must begin at the initial boundary"
        }
        require(frameCoordinates.all { it in 0..replayActionCount }) {
            "Verified replay frame coordinate is outside the replay action range"
        }
        require(frames.size <= replayActionCount + 1) {
            "Verified replay contains more frames than its declared action range"
        }
        require(frames.isEmpty() || frames.last().replayActionIndex <= verifiedActionCount) {
            "Verified replay frame prefix exceeds the verified action count"
        }

        if (fidelity == ReplayFidelity.EXACT) {
            require(verifiedActionCount == replayActionCount) {
                "Exact verification requires every replay action to be verified"
            }
            require(frameCoordinates == (0..replayActionCount).toList()) {
                "Exact verification requires the initial, intermediate, and tail frames"
            }
            require(initialCheckpointVerified) {
                "Exact verification requires the initial public boundary"
            }
            require(intermediateCheckpointsVerified) {
                "Exact verification requires all intermediate checkpoints"
            }
            require(tailCheckpointVerified) {
                "Exact verification requires the tail checkpoint"
            }
            require(closure != null) {
                "Exact verification requires factual tail closure evidence"
            }
            require(failureAtReplayActionIndex == null && failureReason == null) {
                "Exact verification cannot carry a failure"
            }
        }
    }

    /** True only when the entire replay range and all public boundaries are trusted. */
    val completeRangeVerified: Boolean
        get() = fidelity == ReplayFidelity.EXACT &&
            verifiedActionCount == replayActionCount &&
            frames.size == replayActionCount + 1 &&
            frames.map(VerifiedReplayFrame::replayActionIndex) == (0..replayActionCount).toList() &&
            initialCheckpointVerified &&
            intermediateCheckpointsVerified &&
            tailCheckpointVerified &&
            closure != null &&
            failureAtReplayActionIndex == null &&
            failureReason == null

    /** Number of public frame boundaries retained by this result. */
    val frameCount: Int get() = frames.size

    /** Data-class-compatible copying while preserving the immutable frame list. */
    fun copy(
        version: Int = this.version,
        schemaIdentity: String = this.schemaIdentity,
        replayVersion: Int = this.replayVersion,
        replayActionCount: Int = this.replayActionCount,
        verifiedActionCount: Int = this.verifiedActionCount,
        fidelity: ReplayFidelity = this.fidelity,
        frames: List<VerifiedReplayFrame> = this.frames,
        initialCheckpointVerified: Boolean = this.initialCheckpointVerified,
        intermediateCheckpointsVerified: Boolean = this.intermediateCheckpointsVerified,
        tailCheckpointVerified: Boolean = this.tailCheckpointVerified,
        closure: EpisodeClosureV1? = this.closure,
        failureAtReplayActionIndex: Int? = this.failureAtReplayActionIndex,
        failureReason: String? = this.failureReason,
    ): VerifiedReplayVerification = VerifiedReplayVerification(
        version = version,
        schemaIdentity = schemaIdentity,
        replayVersion = replayVersion,
        replayActionCount = replayActionCount,
        verifiedActionCount = verifiedActionCount,
        fidelity = fidelity,
        frames = frames,
        initialCheckpointVerified = initialCheckpointVerified,
        intermediateCheckpointsVerified = intermediateCheckpointsVerified,
        tailCheckpointVerified = tailCheckpointVerified,
        closure = closure,
        failureAtReplayActionIndex = failureAtReplayActionIndex,
        failureReason = failureReason,
    )

    override fun equals(other: Any?): Boolean = other is VerifiedReplayVerification &&
        version == other.version &&
        schemaIdentity == other.schemaIdentity &&
        replayVersion == other.replayVersion &&
        replayActionCount == other.replayActionCount &&
        verifiedActionCount == other.verifiedActionCount &&
        fidelity == other.fidelity &&
        frames == other.frames &&
        initialCheckpointVerified == other.initialCheckpointVerified &&
        intermediateCheckpointsVerified == other.intermediateCheckpointsVerified &&
        tailCheckpointVerified == other.tailCheckpointVerified &&
        closure == other.closure &&
        failureAtReplayActionIndex == other.failureAtReplayActionIndex &&
        failureReason == other.failureReason

    override fun hashCode(): Int = listOf(
        version,
        schemaIdentity,
        replayVersion,
        replayActionCount,
        verifiedActionCount,
        fidelity,
        frames,
        initialCheckpointVerified,
        intermediateCheckpointsVerified,
        tailCheckpointVerified,
        closure,
        failureAtReplayActionIndex,
        failureReason,
    ).hashCode()

    override fun toString(): String = "VerifiedReplayVerification(" +
        "version=$version, schemaIdentity=$schemaIdentity, replayVersion=$replayVersion, " +
        "replayActionCount=$replayActionCount, verifiedActionCount=$verifiedActionCount, " +
        "fidelity=$fidelity, frames=$frames, initialCheckpointVerified=$initialCheckpointVerified, " +
        "intermediateCheckpointsVerified=$intermediateCheckpointsVerified, " +
        "tailCheckpointVerified=$tailCheckpointVerified, closure=$closure, " +
        "failureAtReplayActionIndex=$failureAtReplayActionIndex, failureReason=$failureReason)"
}

/** Explicit wire serializer keeps [VerifiedReplayVerification.frames] immutable in memory. */
@OptIn(ExperimentalSerializationApi::class)
object VerifiedReplayVerificationSerializer : KSerializer<VerifiedReplayVerification> {
    private val frameListSerializer = ListSerializer(VerifiedReplayFrame.serializer())
    private val closureSerializer = EpisodeClosureV1.serializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY,
    ) {
        element<Int>("version", isOptional = true)
        element<String>("schemaIdentity", isOptional = true)
        element<Int>("replayVersion")
        element<Int>("replayActionCount")
        element<Int>("verifiedActionCount")
        element<ReplayFidelity>("fidelity")
        element<List<VerifiedReplayFrame>>("frames", isOptional = true)
        element<Boolean>("initialCheckpointVerified", isOptional = true)
        element<Boolean>("intermediateCheckpointsVerified", isOptional = true)
        element<Boolean>("tailCheckpointVerified", isOptional = true)
        element<EpisodeClosureV1?>("closure", isOptional = true)
        element<Int?>("failureAtReplayActionIndex", isOptional = true)
        element<String?>("failureReason", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: VerifiedReplayVerification) {
        val output = encoder.beginStructure(descriptor)
        output.encodeIntElement(descriptor, 0, value.version)
        output.encodeStringElement(descriptor, 1, value.schemaIdentity)
        output.encodeIntElement(descriptor, 2, value.replayVersion)
        output.encodeIntElement(descriptor, 3, value.replayActionCount)
        output.encodeIntElement(descriptor, 4, value.verifiedActionCount)
        output.encodeSerializableElement(descriptor, 5, ReplayFidelity.serializer(), value.fidelity)
        output.encodeSerializableElement(descriptor, 6, frameListSerializer, value.frames)
        output.encodeBooleanElement(descriptor, 7, value.initialCheckpointVerified)
        output.encodeBooleanElement(descriptor, 8, value.intermediateCheckpointsVerified)
        output.encodeBooleanElement(descriptor, 9, value.tailCheckpointVerified)
        output.encodeNullableSerializableElement(descriptor, 10, closureSerializer, value.closure)
        value.failureAtReplayActionIndex?.let {
            output.encodeIntElement(descriptor, 11, it)
        }
        value.failureReason?.let {
            output.encodeStringElement(descriptor, 12, it)
        }
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): VerifiedReplayVerification {
        val input = decoder.beginStructure(descriptor)
        var version = VERIFIED_REPLAY_VERIFICATION_V1_VERSION
        var schemaIdentity = VERIFIED_REPLAY_VERIFICATION_V1_SCHEMA_IDENTITY
        var replayVersion: Int? = null
        var replayActionCount: Int? = null
        var verifiedActionCount: Int? = null
        var fidelity: ReplayFidelity? = null
        var frames: List<VerifiedReplayFrame> = emptyList()
        var initialCheckpointVerified = false
        var intermediateCheckpointsVerified = false
        var tailCheckpointVerified = false
        var closure: EpisodeClosureV1? = null
        var failureAtReplayActionIndex: Int? = null
        var failureReason: String? = null
        while (true) {
            when (val index = input.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> version = input.decodeIntElement(descriptor, 0)
                1 -> schemaIdentity = input.decodeStringElement(descriptor, 1)
                2 -> replayVersion = input.decodeIntElement(descriptor, 2)
                3 -> replayActionCount = input.decodeIntElement(descriptor, 3)
                4 -> verifiedActionCount = input.decodeIntElement(descriptor, 4)
                5 -> fidelity = input.decodeSerializableElement(descriptor, 5, ReplayFidelity.serializer())
                6 -> frames = input.decodeSerializableElement(descriptor, 6, frameListSerializer)
                7 -> initialCheckpointVerified = input.decodeBooleanElement(descriptor, 7)
                8 -> intermediateCheckpointsVerified = input.decodeBooleanElement(descriptor, 8)
                9 -> tailCheckpointVerified = input.decodeBooleanElement(descriptor, 9)
                10 -> closure = input.decodeNullableSerializableElement(descriptor, 10, closureSerializer)
                11 -> failureAtReplayActionIndex = input.decodeIntElement(descriptor, 11)
                12 -> failureReason = input.decodeStringElement(descriptor, 12)
                else -> throw SerializationException("Unknown verified replay-verification field index $index")
            }
        }
        input.endStructure(descriptor)
        return VerifiedReplayVerification(
            version = version,
            schemaIdentity = schemaIdentity,
            replayVersion = checkNotNull(replayVersion) {
                "Verified replay-verification is missing replayVersion"
            },
            replayActionCount = checkNotNull(replayActionCount) {
                "Verified replay-verification is missing replayActionCount"
            },
            verifiedActionCount = checkNotNull(verifiedActionCount) {
                "Verified replay-verification is missing verifiedActionCount"
            },
            fidelity = checkNotNull(fidelity) {
                "Verified replay-verification is missing fidelity"
            },
            frames = frames,
            initialCheckpointVerified = initialCheckpointVerified,
            intermediateCheckpointsVerified = intermediateCheckpointsVerified,
            tailCheckpointVerified = tailCheckpointVerified,
            closure = closure,
            failureAtReplayActionIndex = failureAtReplayActionIndex,
            failureReason = failureReason,
        )
    }
}

/** Neutral Gym-owned source of one replay's verified public frame stream. */
interface VerifiedReplayFrameSource {
    /** Fold the bound replay once and return either complete exact evidence or a failed result. */
    fun verify(): VerifiedReplayVerification
}
