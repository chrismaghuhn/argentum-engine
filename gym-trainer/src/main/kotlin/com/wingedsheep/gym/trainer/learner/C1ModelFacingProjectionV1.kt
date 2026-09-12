package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class C1ProjectionContext(
    val datasetId: String,
    val sourceManifestContentDigest: String,
)

object C1ModelFacingProjectionV1 {
    fun project(
        trajectory: TrajectoryV1,
        record: DecisionRecordV1,
        context: C1ProjectionContext,
        partition: C1DatasetPartition,
    ): C1DerivedSampleV1 {
        requireOwnedRecord(trajectory, record)

        val observation = record.observationBefore
        val domain = record.completeLegalDomain
        val chosenAction = record.chosenSemanticAction
        val chosenResponse = record.chosenSemanticResponse
        require((chosenAction == null) != (chosenResponse == null)) {
            "Decision record must contain exactly one chosen semantic value"
        }

        val target = when {
            chosenAction != null -> C1DerivedTargetChannel(
                chosenSemanticAction = chosenAction.replaySemanticElement(),
            )

            chosenResponse != null -> C1DerivedTargetChannel(
                chosenSemanticResponse = chosenResponse.response,
            )

            else -> error("Unreachable chosen semantic value")
        }
        val selectedBinding = target.chosenSemanticAction ?: target.chosenSemanticResponse
            ?: error("Target has no source binding")
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
        val tieDiscriminators = when (domain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> C1SourceTieDiscriminatorV1.produce(
                domain.candidates.mapIndexed { index, candidate ->
                    C1ProjectedCandidateForTie(index, projectCandidate(candidate))
                },
            ).mapKeys { (ordinal, _) -> ordinal.toString() }

            CompleteLegalDomainKind.STRUCTURED_DECISION -> emptyMap()
        }
        return C1DerivedSampleV1(
            partition = partition,
            sourceReference = sourceReference,
            input = projectInput(observation, domain),
            target = target,
            binding = C1DerivedBindingChannel(
                completeLegalDomain = encodeDomain(domain),
                selectedExactSourceBinding = selectedBinding,
                semanticTieDiscriminators = tieDiscriminators,
            ),
            provenance = provenance,
        )
    }

    private fun requireOwnedRecord(trajectory: TrajectoryV1, record: DecisionRecordV1) {
        val owned = trajectory.decisions.singleOrNull { it.decisionIndex == record.decisionIndex }
            ?: throw IllegalArgumentException("Decision record is absent from its trajectory")
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

    private fun projectInput(
        observation: com.wingedsheep.gym.contract.PlayerObservationV1,
        domain: CompleteLegalDomainV1,
    ): JsonObject = buildJsonObject {
        put("contractIdentity", C1_MODEL_FACING_CONTRACT_IDENTITY)
        put(
            "decisionContext",
            buildJsonObject {
                put("domainKind", domain.kind.name)
                put("turnNumber", observation.turnNumber)
                put("phase", observation.phase.name)
                put("step", observation.step.name)
                put("actingRole", "SELF")
            },
        )
        put(
            "observation",
            buildJsonObject {
                put("turnNumber", observation.turnNumber)
                put("phase", observation.phase.name)
                put("step", observation.step.name)
                put("terminated", observation.terminated)
                put("truncated", observation.truncated)
            },
        )
        put("domain", projectDomain(domain))
    }

    private fun projectDomain(domain: CompleteLegalDomainV1): JsonObject = buildJsonObject {
        put("kind", domain.kind.name)
        domain.decisionKind?.let { put("decisionKind", it.name) }
        domain.shape?.let {
            put(
                "shape",
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.DecisionShape.serializer(),
                    it,
                ),
            )
        }
        when (domain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> put(
                "candidates",
                buildJsonArray { domain.candidates.forEach { add(projectCandidate(it)) } },
            )

            CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                val structured = requireNotNull(domain.structuredDomain)
                put(
                    "structuredType",
                    projectStructuredDomainRepresentation(structured),
                )
            }
        }
    }

    internal fun projectStructuredDomainRepresentation(
        domain: com.wingedsheep.gym.contract.StructuredDecisionDomain,
    ): JsonObject = when (domain) {
        is com.wingedsheep.gym.contract.TargetsDomain -> buildJsonObject {
            put("type", "targets")
            put("version", domain.version)
            put("requirementCount", domain.requirements.size)
            put("canCancel", domain.canCancel)
        }

        is com.wingedsheep.gym.contract.CardSelectionDomain -> buildJsonObject {
            put("type", "card-selection")
            put("version", domain.version)
            put("optionCount", domain.options.size)
            put("nonSelectableCount", domain.nonSelectableOptions.size)
            put("ordered", domain.ordered)
        }

        is com.wingedsheep.gym.contract.ModeSelectionDomain -> buildJsonObject {
            put("type", "mode-selection")
            put("version", domain.version)
            put("modeCount", domain.modes.size)
            put("minModes", domain.minModes)
            put("maxModes", domain.maxModes)
        }

        is com.wingedsheep.gym.contract.DistributionDomain -> buildJsonObject {
            put("type", "distribution")
            put("version", domain.version)
            put("targetCount", domain.targets.size)
            put("totalAmount", domain.totalAmount)
            put("allowPartial", domain.allowPartial)
        }

        is com.wingedsheep.gym.contract.OrderingDomain -> buildJsonObject {
            put("type", "ordering")
            put("version", domain.version)
            put("objectCount", domain.objects.size)
        }

        is com.wingedsheep.gym.contract.SplitPilesDomain -> buildJsonObject {
            put("type", "split-piles")
            put("version", domain.version)
            put("cardCount", domain.cards.size)
            put("numberOfPiles", domain.numberOfPiles)
        }

        is com.wingedsheep.gym.contract.SearchLibraryDomain -> buildJsonObject {
            put("type", "search-library")
            put("version", domain.version)
            put("optionCount", domain.options.size)
            put("minSelections", domain.minSelections)
            put("maxSelections", domain.maxSelections)
        }

        is com.wingedsheep.gym.contract.ReorderLibraryDomain -> buildJsonObject {
            put("type", "reorder-library")
            put("version", domain.version)
            put("cardCount", domain.cards.size)
        }

        is com.wingedsheep.gym.contract.CombatResolutionDomain -> buildJsonObject {
            put("type", "combat-resolution")
            put("version", domain.version)
            put("attackerCount", domain.attackers.size)
            put("blockerCount", domain.blockers.size)
            put("edgeCount", domain.edges.size)
        }

        is com.wingedsheep.gym.contract.ManaSourcesDomain -> buildJsonObject {
            put("type", "mana-sources")
            put("version", domain.version)
            put("paymentDomainVersion", domain.paymentDomain.version)
            put("canDecline", domain.canDecline)
        }

        is com.wingedsheep.gym.contract.ReplacementDomain -> buildJsonObject {
            put("type", "replacement")
            put("version", domain.version)
            put("fromCount", domain.fromOptions.size)
            put("toCount", domain.toOptions.size)
        }

        is com.wingedsheep.gym.contract.BudgetModalDomain -> buildJsonObject {
            put("type", "budget-modal")
            put("version", domain.version)
            put("budget", domain.budget)
            put("modeCount", domain.modes.size)
        }
    }

    private fun projectCandidate(candidate: JsonObject): JsonObject = buildJsonObject {
        listOf(
            "kind",
            "affordable",
            "manaCost",
            "hasXCost",
            "maxAffordableX",
            "minTargets",
            "maxTargets",
            "sacrificeCount",
            "sacrificeMinCount",
            "sacrificeMaxCount",
            "requiresDamageDistribution",
            "isManaAbility",
            "requiresStructuredAction",
            "requiredPayloadFields",
            "actionSemantics",
            "isDecisionOption",
        ).forEach { key ->
            candidate[key]?.let { value -> put(key, value) }
        }
    }

    private fun encodeDomain(domain: CompleteLegalDomainV1): JsonObject =
        A3SemanticJson.strictJson.encodeToJsonElement(
            CompleteLegalDomainV1.serializer(),
            domain,
        ).jsonObject
}
