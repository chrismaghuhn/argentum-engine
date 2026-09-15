package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.C1ModelFacingProjectionV1 as GymC1ModelFacingProjectionV1
import com.wingedsheep.gym.contract.C1ModelFacingRelationTable
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

data class C1ProjectionContext(
    val datasetId: String,
    val sourceManifestContentDigest: String,
)

internal typealias C1SampleRelationTable = C1ModelFacingRelationTable

object C1ModelFacingProjectionV1 {
    fun project(
        trajectory: TrajectoryV1,
        record: DecisionRecordV1,
        context: C1ProjectionContext,
        partition: C1DatasetPartition,
    ): C1DerivedSampleV1 {
        requireOwnedRecord(trajectory, record)

        val observation = record.observationBefore
        require(!observation.terminated) {
            "Learner projection requires an accepted pre-choice observation"
        }
        require(!observation.truncated) {
            "Learner projection requires an accepted pre-choice observation"
        }
        require(observation.winnerId == null) {
            "Learner projection must not admit a terminal winner field"
        }
        val domain = record.completeLegalDomain
        val chosenAction = record.chosenSemanticAction
        val chosenResponse = record.chosenSemanticResponse
        require((chosenAction == null) != (chosenResponse == null)) {
            "Decision record must contain exactly one chosen semantic value"
        }

        val target = when {
            chosenAction != null -> {
                val rebound = ChosenSemanticActionV1.from(
                    domain = domain,
                    candidate = chosenAction.candidate,
                    choicePayload = chosenAction.choicePayload,
                )
                C1DerivedTargetChannel(
                    chosenSemanticAction = rebound.replaySemanticElement(),
                )
            }

            chosenResponse != null -> {
                val rebound = ChosenSemanticResponseV1.from(
                    domain = domain,
                    response = chosenResponse.response,
                )
                C1DerivedTargetChannel(
                    chosenSemanticResponse = rebound.replaySemanticElement(),
                )
            }

            else -> error("Unreachable chosen semantic value")
        }
        val selectedBinding = target.chosenSemanticAction ?: target.chosenSemanticResponse
            ?: error("Target has no source binding")
        val projection = GymC1ModelFacingProjectionV1.project(
            observation = observation,
            domain = domain,
        )
        val sourceReference = C1DerivedSourceReference(
            datasetId = context.datasetId,
            sourceManifestContentDigest = context.sourceManifestContentDigest,
            trajectoryId = trajectory.trajectoryId,
            semanticEpisodeId = trajectory.semanticEpisodeId,
            collectionJobId = trajectory.collectionJobId,
            decisionIndex = record.decisionIndex,
            replayActionIndex = record.replayActionIndex,
            replayFrameIndex = record.replayFrameIndex,
            semanticDecisionId = A3SemanticJson.strictJson.encodeToJsonElement(
                record.semanticDecisionId,
            ).jsonObject,
            perspectivePlayerId = record.perspectivePlayerId.value,
        )
        val provenance = A3SemanticJson.strictJson.encodeToJsonElement(
            trajectory.episodeMetadata,
        ).jsonObject
        return C1DerivedSampleV1(
            partition = partition,
            sourceReference = sourceReference,
            input = projection.input,
            target = target,
            binding = C1DerivedBindingChannel(
                completeLegalDomain = encodeDomain(domain),
                selectedExactSourceBinding = selectedBinding,
                sourceBindingOrdinals = projection.sourceBindingOrdinals,
                semanticTieDiscriminators = projection.semanticTieDiscriminators,
                entityAliasBindings = projection.entityAliasBindings,
            ),
            provenance = provenance,
        )
    }

    internal fun projectStructuredDomainRepresentation(
        domain: com.wingedsheep.gym.contract.StructuredDecisionDomain,
        relations: C1SampleRelationTable = C1SampleRelationTable.empty(),
    ): JsonObject = GymC1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
        domain = domain,
        relations = relations,
    )

    private fun requireOwnedRecord(trajectory: TrajectoryV1, record: DecisionRecordV1) {
        val owned = trajectory.decisions.getOrNull(record.decisionIndex)
            ?: throw IllegalArgumentException("Decision record is absent from its trajectory")
        require(owned.decisionIndex == record.decisionIndex) {
            "Decision record index is not contiguous in its trajectory"
        }
        require(owned.replayActionIndex == record.replayActionIndex) {
            "Decision record replay coordinate does not belong to its trajectory"
        }
        require(owned.replayFrameIndex == record.replayFrameIndex) {
            "Decision record frame coordinate does not belong to its trajectory"
        }
        val expected = A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(DecisionRecordV1.serializer(), owned),
        )
        val actual = A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(DecisionRecordV1.serializer(), record),
        )
        require(expected == actual) {
            "Decision record does not belong to the supplied trajectory"
        }
    }

    private fun encodeDomain(domain: CompleteLegalDomainV1): JsonObject =
        A3SemanticJson.strictJson.encodeToJsonElement(
            CompleteLegalDomainV1.serializer(),
            domain,
        ).jsonObject
}
