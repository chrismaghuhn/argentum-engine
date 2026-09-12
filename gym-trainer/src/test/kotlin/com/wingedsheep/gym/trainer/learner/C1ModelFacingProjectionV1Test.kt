package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.EpisodeInterruptionReason
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.CombatResolutionDomain
import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.DistributionDomain
import com.wingedsheep.gym.contract.ModeSelectionDomain
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ReorderLibraryDomain
import com.wingedsheep.gym.contract.ReplacementDomain
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.SearchLibraryDomain
import com.wingedsheep.gym.contract.SemanticDecisionKindV1
import com.wingedsheep.gym.contract.SplitPilesDomain
import com.wingedsheep.gym.contract.StructuredDecisionDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.BudgetModalDomain
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

class C1ModelFacingProjectionV1Test : FunSpec({
    test("changing only the chosen action leaves canonical input bytes unchanged") {
        val first = fixture(chosenCandidateIndex = 0)
        val second = fixture(chosenCandidateIndex = 1)
        val context = C1ProjectionContext(
            datasetId = "b".repeat(64),
            sourceManifestContentDigest = "c".repeat(64),
        )

        val firstSample = C1ModelFacingProjectionV1.project(
            trajectory = first.trajectory,
            record = first.record,
            context = context,
            partition = C1DatasetPartition.TRAIN,
        )
        val secondSample = C1ModelFacingProjectionV1.project(
            trajectory = second.trajectory,
            record = second.record,
            context = context,
            partition = C1DatasetPartition.TRAIN,
        )

        A3SemanticJson.canonicalJson(firstSample.input) shouldBe
            A3SemanticJson.canonicalJson(secondSample.input)
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1DerivedTargetChannel.serializer(),
                firstSample.target,
            ),
        ) shouldNotBe A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1DerivedTargetChannel.serializer(),
                secondSample.target,
            ),
        )
    }

    test("rejects a decision record owned by another trajectory") {
        val owner = fixture(chosenCandidateIndex = 0)
        val foreign = fixture(chosenCandidateIndex = 1)

        shouldThrow<IllegalArgumentException> {
            C1ModelFacingProjectionV1.project(
                trajectory = owner.trajectory,
                record = foreign.record,
                context = C1ProjectionContext("b".repeat(64), "c".repeat(64)),
                partition = C1DatasetPartition.TRAIN,
            )
        }
    }

    test("keeps raw IDs out of input and feature views") {
        val source = fixture(chosenCandidateIndex = 0)
        val sample = C1ModelFacingProjectionV1.project(
            trajectory = source.trajectory,
            record = source.record,
            context = C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            partition = C1DatasetPartition.TRAIN,
        )
        val input = A3SemanticJson.canonicalJson(sample.input)

        input shouldNotContain "entity-raw-source"
        input shouldNotContain "actionId"
        input shouldNotContain "decisionId"
        input shouldNotContain "policySeed"
        input shouldNotContain "collectionJobId"
        input shouldNotContain "trajectoryId"
        sample.binding.completeLegalDomain.toString() shouldBe sample.binding.completeLegalDomain.toString()
    }

    test("retains every flat candidate and exact selected binding") {
        val source = fixture(chosenCandidateIndex = 1)
        val sample = C1ModelFacingProjectionV1.project(
            trajectory = source.trajectory,
            record = source.record,
            context = C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            partition = C1DatasetPartition.TRAIN,
        )
        val candidates = sample.binding.completeLegalDomain["candidates"]
            ?.let { requireNotNull(it) }
            ?: error("missing complete candidates")

        candidates.toString().contains("entity-raw-source") shouldBe true
        sample.binding.semanticTieDiscriminators.keys shouldBe setOf("0", "1")
        A3SemanticJson.canonicalJson(sample.target.chosenSemanticAction!!).also { chosen ->
            A3SemanticJson.canonicalJson(sample.binding.selectedExactSourceBinding) shouldBe chosen
        }
    }

    test("represents all twelve structured domain variants") {
        val domains: List<StructuredDecisionDomain> = listOf(
            TargetsDomain(requirements = emptyList(), canCancel = true),
            CardSelectionDomain(
                options = emptyList(),
                minSelections = 0,
                maxSelections = 0,
                ordered = false,
                cardInfo = null,
                useTargetingUI = false,
                selectedLabel = null,
                remainderLabel = null,
                nonSelectableOptions = emptyList(),
                onePerCardType = false,
                onePerColor = false,
                availableColors = null,
                onePerCardName = false,
                onePerBasicLandType = false,
                onePerPower = false,
                maxTotalManaValue = null,
                minTotalManaValue = null,
                maxTotalPower = null,
                conditionalMinimums = emptyList(),
            ),
            ModeSelectionDomain(modes = emptyList(), minModes = 0, maxModes = 0),
            DistributionDomain(
                totalAmount = 0,
                targets = emptyList(),
                minPerTarget = 0,
                maxPerTarget = emptyMap(),
                allowPartial = true,
            ),
            OrderingDomain(objects = emptyList()),
            SplitPilesDomain(
                cards = emptyList(),
                numberOfPiles = 1,
                pileLabels = listOf("pile"),
            ),
            SearchLibraryDomain(
                options = emptyList(),
                minSelections = 0,
                maxSelections = 0,
                cards = emptyMap(),
                filterDescription = "",
            ),
            ReorderLibraryDomain(cards = emptyList(), cardInfo = emptyMap()),
            CombatResolutionDomain(
                firstStrike = false,
                attackers = emptyList(),
                blockers = emptyList(),
                defenders = emptyList(),
                edges = emptyList(),
                coChooserId = null,
            ),
            ManaSourcesDomain(
                paymentDomain = PaymentDomainV5(
                    requiredCost = "",
                    outerAtomicCostUnits = emptyList(),
                    initialPoolBuckets = emptyList(),
                    sourceActivationOptions = emptyList(),
                ),
                canDecline = true,
            ),
            ReplacementDomain(
                fromOptions = emptyList(),
                toOptions = emptyList(),
                fromMetadata = emptyList(),
                toMetadata = emptyList(),
                allowedToByFrom = emptyList(),
                defaultFromIndex = null,
            ),
            BudgetModalDomain(budget = 0, modes = emptyList()),
        )
        val expectedTypes = listOf(
            "targets",
            "card-selection",
            "mode-selection",
            "distribution",
            "ordering",
            "split-piles",
            "search-library",
            "reorder-library",
            "combat-resolution",
            "mana-sources",
            "replacement",
            "budget-modal",
        )

        domains.zip(expectedTypes).forEach { (domain, expectedType) ->
            val representation = C1ModelFacingProjectionV1
                .projectStructuredDomainRepresentation(domain)
            representation["type"]?.jsonPrimitive?.content shouldBe expectedType
            representation["version"]?.jsonPrimitive?.content?.toInt() shouldBe domain.version
        }
    }
})

private data class ProjectionFixture(
    val trajectory: TrajectoryV1,
    val record: DecisionRecordV1,
)

private fun fixture(chosenCandidateIndex: Int): ProjectionFixture {
    val self = EntityId("self")
    val opponent = EntityId("opponent")
    val observation = PlayerObservationV1(
        wireSchemaHash = SchemaHash.CURRENT,
        perspectivePlayerId = self,
        agentToAct = self,
        turnNumber = 3,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = self,
        priorityPlayerId = self,
        players = emptyList(),
        zones = emptyList(),
        stack = emptyList(),
        pendingDecision = null,
        terminated = false,
        truncated = false,
        winnerId = null,
        observationDigest = "0".repeat(64),
    )
    val candidates = listOf(
        candidate(kind = "PassPriority", sourceEntityId = "entity-raw-source"),
        candidate(kind = "PlayLand", sourceEntityId = "entity-second-source"),
    )
    val domain = CompleteLegalDomainV1(
        kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
        candidates = candidates,
    )
    val chosen = ChosenSemanticActionV1.from(
        domain = domain,
        candidate = candidates[chosenCandidateIndex],
    )
    val record = DecisionRecordV1(
        decisionIndex = 0,
        replayActionIndex = 0,
        replayFrameIndex = 0,
        perspectivePlayerId = self,
        decisionKind = SemanticDecisionKindV1.PRIORITY,
        semanticDecisionId = SemanticDecisionIdV1(value = "1".repeat(64)),
        observationBefore = observation,
        completeLegalDomain = domain,
        candidateDomainDigest = CandidateDomainDigestV1.from(domain),
        chosenSemanticAction = chosen,
    )
    val metadata = episodeMetadata(self, opponent)
    val trajectoryBase = TrajectoryV1(
        trajectoryId = "0".repeat(64),
        episodeMetadata = metadata,
        decisions = listOf(record),
    )
    val trajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    return ProjectionFixture(trajectory = trajectory, record = record)
}

private fun candidate(kind: String, sourceEntityId: String): JsonObject = buildJsonObject {
    put("kind", kind)
    put("affordable", true)
    put("sourceEntityId", sourceEntityId)
    put("targetEntityIds", buildJsonArray { })
    put("manaCost", JsonNull)
    put("hasXCost", false)
    put("maxAffordableX", JsonNull)
    put("minTargets", 0)
    put("maxTargets", 0)
    put("validSacrificeTargets", buildJsonArray { })
    put("sacrificeCount", 0)
    put("sacrificeMinCount", 0)
    put("sacrificeMaxCount", 0)
    put("requiresDamageDistribution", false)
    put("isManaAbility", false)
    put("requiresStructuredAction", false)
    put("requiredPayloadFields", buildJsonArray { })
    put("actionSemantics", buildJsonObject { put("type", kind) })
    put("isDecisionOption", false)
}

private fun episodeMetadata(self: EntityId, opponent: EntityId): EpisodeMetadataV1 {
    val roster = listOf(
        RosterSeatV1(
            seatIndex = 0,
            playerId = self,
            role = "SELF",
            deckIdentity = "deck-self",
        ),
        RosterSeatV1(
            seatIndex = 1,
            playerId = opponent,
            role = "OPPONENT",
            deckIdentity = "deck-opponent",
        ),
    )
    val environment = EnvironmentIdentityV1(
        engineCommit = "a".repeat(40),
        cardDefinitionIdentity = "cards-v1",
        akiriDeckIdentity = "akiri-v1",
        chevillDeckIdentity = "chevill-v1",
        format = "COMMANDER",
        attackMode = "MULTIPLE",
        startingHandSize = 0,
        skipMulligans = true,
        useHandSmoother = false,
        roster = roster,
        startingPlayer = self,
        actualEngineSeed = 1L,
    )
    val policy = PolicyProvenanceV1(
        behaviorPolicyIdentity = "behavior-v1",
        opponentPolicyIdentity = "opponent-v1",
        behaviorPolicyRole = "EXTERNAL_CONTROLLER",
        opponentPolicyRole = "EXTERNAL_CONTROLLER",
        policyRngIdentity = "explicit-seed/kotlin-policy-state-v1",
        policySeed = 1L,
        policySourceIdentity = "b".repeat(64),
    )
    val replay = CompactReplayLinkV1(
        replayContentIdentity = "c".repeat(64),
        replayActionCount = 1,
    )
    val closure = EpisodeClosureV1.Interrupted(
        stepCount = 1,
        reason = EpisodeInterruptionReason.HORIZON_REACHED,
    )
    val base = EpisodeMetadataV1(
        semanticEpisodeId = "0".repeat(64),
        collectionJobId = "0".repeat(64),
        environmentIdentity = environment,
        policyProvenance = policy,
        compactReplayLink = replay,
        closure = closure,
    )
    val semantic = base.copy(semanticEpisodeId = base.recomputeSemanticEpisodeId())
    return semantic.copy(collectionJobId = semantic.recomputeCollectionJobId())
}
