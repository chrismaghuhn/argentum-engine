package com.wingedsheep.gym.trainer.actor

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Admission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

const val ACTOR_REPRODUCER_V1_VERSION: Int = 1
const val ACTOR_REPRODUCER_V1_SCHEMA_IDENTITY: String =
    "argentum-ml-environment-replay-reproducer@v1"

/** Semantic evidence compared by the environment/replay reproducer. */
@Serializable
data class ActorReproducerSampleV1(
    val version: Int = ACTOR_REPRODUCER_V1_VERSION,
    val schemaIdentity: String = ACTOR_REPRODUCER_V1_SCHEMA_IDENTITY,
    val semanticJobIdentity: SemanticJobIdentityV1,
    val trajectory: TrajectoryV1,
    val replayTrajectoryBinding: ReplayTrajectoryBindingV1,
) {
    init {
        require(version == ACTOR_REPRODUCER_V1_VERSION) {
            "Unsupported actor-reproducer version: $version"
        }
        require(schemaIdentity == ACTOR_REPRODUCER_V1_SCHEMA_IDENTITY) {
            "Unsupported actor-reproducer schema identity: $schemaIdentity"
        }
    }

    internal fun canonicalElement(): JsonObject = buildJsonObject {
        put("version", version)
        put("schemaIdentity", schemaIdentity)
        put(
            "semanticJobIdentity",
            A3SemanticJson.strictJson.encodeToJsonElement(
                SemanticJobIdentityV1.serializer(),
                semanticJobIdentity,
            ),
        )
        put(
            "trajectory",
            A3SemanticJson.strictJson.encodeToJsonElement(
                TrajectoryV1.serializer(),
                trajectory,
            ),
        )
        put(
            "replayTrajectoryBinding",
            A3SemanticJson.strictJson.encodeToJsonElement(
                ReplayTrajectoryBindingV1.serializer(),
                replayTrajectoryBinding,
            ),
        )
    }

    companion object {
        fun from(
            item: WorkItemV1,
            trajectory: TrajectoryV1,
            replayTrajectoryBinding: ReplayTrajectoryBindingV1,
        ): ActorReproducerSampleV1 {
            require(trajectory.semanticEpisodeId == item.expectedSemanticEpisodeId) {
                "Reproducer trajectory semantic episode identity disagrees with the work item"
            }
            require(trajectory.collectionJobId == item.expectedCollectionJobId) {
                "Reproducer trajectory collection-job identity disagrees with the work item"
            }
            require(trajectory.episodeMetadata.environmentIdentity == item.environmentIdentity) {
                "Reproducer environment identity disagrees with the work item"
            }
            require(trajectory.episodeMetadata.policyProvenance == item.policyProvenance) {
                "Reproducer policy provenance disagrees with the work item"
            }
            require(
                TrajectoryV1Admission.admit(
                    trajectory = trajectory,
                    binding = replayTrajectoryBinding,
                    episodeOrdinal = 0,
                ) is TrajectoryAdmissionResult.Admitted,
            ) {
                "Reproducer sample requires exact existing B2 admission evidence"
            }
            return ActorReproducerSampleV1(
                semanticJobIdentity = item.semanticJobIdentity,
                trajectory = trajectory,
                replayTrajectoryBinding = replayTrajectoryBinding,
            )
        }
    }
}

@Serializable
enum class ActorReproducerComparisonStatusV1 {
    EXACT,
    SEMANTIC_JOB_IDENTITY_MISMATCH,
    TRAJECTORY_OR_REPLAY_DIVERGED,
}

data class ActorReproducerComparisonV1(
    val status: ActorReproducerComparisonStatusV1,
    val expectedSemanticJobIdentity: String,
    val actualSemanticJobIdentity: String,
    val expectedContentDigest: String,
    val actualContentDigest: String,
)

/** Compares only existing semantic B2/replay evidence; no runtime or provider fields are inputs. */
object EnvironmentReplayReproducerV1 {
    fun compare(
        expected: ActorReproducerSampleV1,
        actual: ActorReproducerSampleV1,
    ): ActorReproducerComparisonV1 {
        val expectedDigest = digest(expected)
        val actualDigest = digest(actual)
        val status = when {
            expected.semanticJobIdentity != actual.semanticJobIdentity ->
                ActorReproducerComparisonStatusV1.SEMANTIC_JOB_IDENTITY_MISMATCH

            expectedDigest != actualDigest ->
                ActorReproducerComparisonStatusV1.TRAJECTORY_OR_REPLAY_DIVERGED

            else -> ActorReproducerComparisonStatusV1.EXACT
        }
        return ActorReproducerComparisonV1(
            status = status,
            expectedSemanticJobIdentity = expected.semanticJobIdentity.value,
            actualSemanticJobIdentity = actual.semanticJobIdentity.value,
            expectedContentDigest = expectedDigest,
            actualContentDigest = actualDigest,
        )
    }

    private fun digest(sample: ActorReproducerSampleV1): String = A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(sample.canonicalElement())
            .toByteArray(StandardCharsets.UTF_8),
    )
}
