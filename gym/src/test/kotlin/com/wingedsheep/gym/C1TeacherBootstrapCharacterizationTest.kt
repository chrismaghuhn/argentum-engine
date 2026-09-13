package com.wingedsheep.gym

import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ManaPoolView
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.PlayerView
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test-only C1_01 characterization of the existing A9 public-observation policy.
 *
 * The expected runtime-ID-sensitive results are deliberately asserted as findings. They prevent
 * a future admission report from silently describing A9 as Selection-V2-compatible until a
 * separately authorized generic Teacher resolves the symmetry boundary.
 */
class C1TeacherBootstrapCharacterizationTest : FunSpec({
    val policy = DeterministicExternalPolicy()
    val state = DeterministicPolicyState(policySeed = 4259905L)

    test("A9 sourceEntityId ordering selects the lower raw runtime ID") {
        val alternatives = listOf(
            FlatAlternative(label = "LEFT", sourceId = "source-z"),
            FlatAlternative(label = "RIGHT", sourceId = "source-a"),
        )

        selectedFlatLabel(policy.choose(flatObservation(alternatives), state), alternatives) shouldBe "RIGHT"
    }

    test("A9 feature-identical source alternatives change label under runtime-ID renaming") {
        val original = listOf(
            FlatAlternative(label = "LEFT", sourceId = "source-z"),
            FlatAlternative(label = "RIGHT", sourceId = "source-a"),
        )
        val renamed = listOf(
            FlatAlternative(label = "LEFT", sourceId = "source-a"),
            FlatAlternative(label = "RIGHT", sourceId = "source-z"),
        )

        selectedFlatLabel(policy.choose(flatObservation(original), state), original) shouldBe "RIGHT"
        selectedFlatLabel(policy.choose(flatObservation(renamed), state), renamed) shouldBe "LEFT"
    }

    test("A9 flat candidate permutation preserves the selected source when IDs are unchanged") {
        val original = listOf(
            FlatAlternative(label = "LEFT", sourceId = "source-z"),
            FlatAlternative(label = "RIGHT", sourceId = "source-a"),
        )
        val permuted = original.asReversed()

        selectedFlatLabel(policy.choose(flatObservation(original), state), original) shouldBe
            selectedFlatLabel(policy.choose(flatObservation(permuted), state), permuted)
    }

    test("A9 equal ordering keys change the selected label under candidate permutation") {
        val original = listOf(
            FlatAlternative(label = "LEFT", sourceId = "source-a", manaCost = "{1}"),
            FlatAlternative(label = "RIGHT", sourceId = "source-a", manaCost = "{2}"),
        )
        val permuted = original.asReversed()

        selectedFlatLabel(policy.choose(flatObservation(original), state), original) shouldBe "LEFT"
        selectedFlatLabel(policy.choose(flatObservation(permuted), state), permuted) shouldBe "RIGHT"
    }

    test("A9 targetEntityIds also change a feature-identical label under runtime-ID renaming") {
        val original = listOf(
            FlatAlternative(label = "LEFT", targetId = "target-z"),
            FlatAlternative(label = "RIGHT", targetId = "target-a"),
        )
        val renamed = listOf(
            FlatAlternative(label = "LEFT", targetId = "target-a"),
            FlatAlternative(label = "RIGHT", targetId = "target-z"),
        )

        selectedFlatLabel(policy.choose(flatObservation(original), state), original) shouldBe "RIGHT"
        selectedFlatLabel(policy.choose(flatObservation(renamed), state), renamed) shouldBe "LEFT"
    }

    test("A9 structured target ordering changes label under runtime-ID renaming") {
        val original = listOf(
            StructuredTarget(label = "LEFT", entityId = "target-z"),
            StructuredTarget(label = "RIGHT", entityId = "target-a"),
        )
        val renamed = listOf(
            StructuredTarget(label = "LEFT", entityId = "target-a"),
            StructuredTarget(label = "RIGHT", entityId = "target-z"),
        )

        selectedStructuredTargetLabel(policy.choose(targetObservation(original), state), original) shouldBe "RIGHT"
        selectedStructuredTargetLabel(policy.choose(targetObservation(renamed), state), renamed) shouldBe "LEFT"
    }

    test("A9 structured target permutation preserves the selected target when IDs are unchanged") {
        val original = listOf(
            StructuredTarget(label = "LEFT", entityId = "target-z"),
            StructuredTarget(label = "RIGHT", entityId = "target-a"),
        )
        val permuted = original.asReversed()

        selectedStructuredTargetLabel(policy.choose(targetObservation(original), state), original) shouldBe
            selectedStructuredTargetLabel(policy.choose(targetObservation(permuted), state), permuted)
    }

    test("same declared observation and policy state produce the same A9 semantic choice") {
        val alternatives = listOf(
            FlatAlternative(label = "LEFT", sourceId = "source-z"),
            FlatAlternative(label = "RIGHT", sourceId = "source-a"),
        )
        val observation = flatObservation(alternatives)

        policy.choose(observation, state) shouldBe policy.choose(observation, state)
    }

    test("unsupported required payload produces a gap without source-choice substitution") {
        val alternatives = listOf(
            FlatAlternative(label = "ONLY", sourceId = "source-a"),
        )
        val choice = policy.choose(
            flatObservation(
                alternatives,
                requiredPayloadFields = listOf("unsupportedField"),
            ),
            state,
        ).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.code shouldBe "A5_DECISION_GAP"
    }

    test("incomplete acting structured domain produces a gap") {
        val choice = policy.choose(
            baseObservation(
                pendingDecision = PendingDecisionView(
                    decisionId = "decision-1",
                    kind = PendingDecisionKind.CHOOSE_TARGETS,
                    playerId = ACTOR,
                    prompt = "Choose a target",
                    requiresStructuredResponse = true,
                    shape = DecisionShape(minSelections = 1, maxSelections = 1),
                    structuredDomain = null,
                ),
            ),
            state,
        ).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.family shouldBe PendingDecisionKind.CHOOSE_TARGETS.name
        choice.code shouldBe "A5_DECISION_GAP"
    }

    test("an all-unaffordable flat domain produces a gap instead of first-legal fallback") {
        val alternatives = listOf(
            FlatAlternative(label = "ONLY", sourceId = "source-a"),
        )
        val choice = policy.choose(
            flatObservation(alternatives, affordable = false),
            state,
        ).shouldBeInstanceOf<SemanticChoice.Gap>()

        choice.code shouldBe "A5_DECISION_GAP"
    }
})

private const val TEST_SCHEMA = "test-schema"
private val ACTOR = EntityId("actor")
private val OPPONENT = EntityId("opponent")

private data class FlatAlternative(
    val label: String,
    val sourceId: String? = null,
    val targetId: String? = null,
    val manaCost: String? = null,
)

private data class StructuredTarget(
    val label: String,
    val entityId: String,
)

private fun selectedFlatLabel(
    choice: SemanticChoice,
    alternatives: List<FlatAlternative>,
): String {
    val action = choice.shouldBeInstanceOf<SemanticChoice.Action>()
    return alternatives[action.actionId - 1].label
}

private fun selectedStructuredTargetLabel(
    choice: SemanticChoice,
    alternatives: List<StructuredTarget>,
): String {
    val structured = choice.shouldBeInstanceOf<SemanticChoice.Structured>()
    val targets = structured.selection.shouldBeInstanceOf<SemanticDecision.Targets>()
    val selected = targets.selected.getValue(0).single()
    return alternatives.single { it.entityId == selected.value }.label
}

private fun flatObservation(
    alternatives: List<FlatAlternative>,
    affordable: Boolean = true,
    requiredPayloadFields: List<String> = emptyList(),
): TrainingObservation = baseObservation(
    legalActions = alternatives.mapIndexed { index, alternative ->
        LegalActionView(
            actionId = index + 1,
            kind = "PlayLand",
            description = "Play a land",
            affordable = affordable,
            sourceEntityId = alternative.sourceId?.let(::EntityId),
            targetEntityIds = alternative.targetId?.let(::EntityId)?.let(::listOf) ?: emptyList(),
            manaCost = alternative.manaCost,
            requiresStructuredAction = requiredPayloadFields.isNotEmpty(),
            requiredPayloadFields = requiredPayloadFields,
            actionSemantics = buildJsonObject {
                put("type", "PlayLand")
                put("landClass", "basic")
            },
        )
    },
)

private fun targetObservation(
    alternatives: List<StructuredTarget>,
): TrainingObservation = baseObservation(
    pendingDecision = PendingDecisionView(
        decisionId = "decision-1",
        kind = PendingDecisionKind.CHOOSE_TARGETS,
        playerId = ACTOR,
        prompt = "Choose a target",
        requiresStructuredResponse = true,
        shape = DecisionShape(minSelections = 1, maxSelections = 1),
        structuredDomain = TargetsDomain(
            requirements = listOf(
                TargetRequirementDomain(
                    index = 0,
                    description = "one target",
                    minTargets = 1,
                    maxTargets = 1,
                    candidates = alternatives.map { EntityId(it.entityId) },
                    targetZone = null,
                    mustDifferFromEarlier = false,
                    sameController = false,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = false,
                    totalManaValueAtMost = null,
                    differentNames = false,
                    xConstrainsManaValue = false,
                    xConstrainsManaValueExactly = false,
                    xConstrainsPower = false,
                    xConstrainsCount = false,
                ),
            ),
            canCancel = false,
        ),
    ),
)

private fun baseObservation(
    pendingDecision: PendingDecisionView? = null,
    legalActions: List<LegalActionView> = emptyList(),
): TrainingObservation = TrainingObservation(
    schemaHash = TEST_SCHEMA,
    perspectivePlayerId = ACTOR,
    agentToAct = ACTOR,
    turnNumber = 1,
    phase = Phase.PRECOMBAT_MAIN,
    step = Step.PRECOMBAT_MAIN,
    activePlayerId = ACTOR,
    priorityPlayerId = ACTOR,
    players = listOf(
        PlayerView(
            id = ACTOR,
            name = "Self",
            lifeTotal = 20,
            handSize = 0,
            librarySize = 0,
            graveyardSize = 0,
            exileSize = 0,
            manaPool = ManaPoolView(),
            isPerspective = true,
            isActive = true,
            hasPriority = true,
            hasLost = false,
        ),
        PlayerView(
            id = OPPONENT,
            name = "Opponent",
            lifeTotal = 20,
            handSize = 0,
            librarySize = 0,
            graveyardSize = 0,
            exileSize = 0,
            manaPool = ManaPoolView(),
            isPerspective = false,
            isActive = false,
            hasPriority = false,
            hasLost = false,
        ),
    ),
    zones = emptyList(),
    stack = emptyList(),
    pendingDecision = pendingDecision,
    legalActions = legalActions,
    terminated = false,
    truncated = false,
    winnerId = null,
    stateDigest = "digest",
)
