package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.C1ModelFacingProjectionV1
import com.wingedsheep.gym.contract.LiveExactSourceBindingEntry
import com.wingedsheep.gym.contract.LiveExactSourceBindingTable
import com.wingedsheep.gym.contract.LiveStructuredChoiceAlternativeV1
import com.wingedsheep.gym.contract.LiveStructuredChoiceCompletenessSource
import com.wingedsheep.gym.contract.LiveStructuredChoiceCompletenessWitnessV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A source-owned exact action plus the model-facing selected-card representation it corresponds to. */
internal data class LivePregameActionChoice(
    val action: GameAction,
    val selectedCards: List<EntityId>,
)

/**
 * Generic adapter for the engine's existing [SelectCardsDecision] primitive. It builds the
 * structured C1 snapshot from a synthetic decision view while keeping the exact action values in
 * the JVM-only binding table. The real [GameState] is never serialized as part of this view.
 */
internal object LivePregameDecisionSource {
    private const val MAX_EXPLICIT_ALTERNATIVES: Long = 10_000L

    fun capture(
        state: GameState,
        perspectivePlayerId: EntityId,
        decision: SelectCardsDecision,
        choices: List<LivePregameActionChoice>,
        sourceContext: JsonObject,
        observationBuilder: ObservationBuilder,
    ): LivePolicySourceSnapshot {
        require(choices.isNotEmpty()) {
            "Pregame policy source requires at least one complete choice"
        }
        choices.forEach { choice ->
            require(choice.action.playerId == decision.playerId) {
                "Pregame exact action belongs to a different player than its decision"
            }
            require(choice.selectedCards.distinct().size == choice.selectedCards.size) {
                "Pregame selected-card choices must be injective"
            }
            require(choice.selectedCards.all { it in decision.options }) {
                "Pregame selected-card choice contains a card outside the decision domain"
            }
            require(choice.selectedCards.size in decision.minSelections..decision.maxSelections) {
                "Pregame selected-card choice violates the decision cardinality"
            }
        }

        val result = observationBuilder.build(
            state = state.copy(pendingDecision = decision),
            perspectivePlayerId = perspectivePlayerId,
            legalActions = emptyList(),
            truncated = false,
        )
        val observation = result.observation as? TrainingObservation
            ?: throw PolicySeatFailure(
                PolicySeatFailureCode.SESSION_NOT_READY,
                "pregame policy requires a game TrainingObservation",
            )
        val domain = try {
            CompleteLegalDomainV1.from(observation)
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "pregame decision cannot be represented by the complete C1 domain",
                failure,
            )
        }
        if (domain.kind != CompleteLegalDomainKind.STRUCTURED_DECISION) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "pregame SelectCardsDecision did not produce a structured domain",
            )
        }

        val projection = C1ModelFacingProjectionV1.project(
            observation = PlayerObservationV1.from(observation),
            domain = domain,
        )
        val aliasesBySourceId = projection.entityAliasBindings.associate {
            it.sourceEntityId to it.alias
        }
        val semanticBindingsByExactValue = choices.associate { choice ->
            val exactBinding = PolicySeatExactBinding.GameActionBinding(choice.action)
            LivePolicySourceAdapter.canonicalExactBinding(exactBinding) to buildJsonObject {
                put("type", "PregameActionBinding")
                put("action", LivePolicySourceAdapter.semanticGameAction(choice.action))
                put("selectedCardCount", choice.selectedCards.size)
                put("sourceContext", sourceContext)
            }
        }
        val alternatives = choices.mapIndexed { ordinal, choice ->
            val exactBinding = PolicySeatExactBinding.GameActionBinding(choice.action)
            LiveStructuredChoiceAlternativeV1(
                sourceBindingOrdinal = ordinal,
                featureView = selectedCardsFeatureView(choice.selectedCards, aliasesBySourceId),
                authoritativeSemanticBinding = semanticBindingsByExactValue.getValue(
                    LivePolicySourceAdapter.canonicalExactBinding(exactBinding),
                ),
            )
        }
        val exactEntries = choices.mapIndexed { ordinal, choice ->
            LiveExactSourceBindingEntry<PolicySeatExactBinding>(
                sourceBindingOrdinal = ordinal,
                value = PolicySeatExactBinding.GameActionBinding(choice.action),
            )
        }
        val exactBindings = try {
            LiveExactSourceBindingTable.fromStructuredChoices<PolicySeatExactBinding>(
                domain = domain,
                alternatives = alternatives,
                entries = exactEntries,
                canonicalizeValue = LivePolicySourceAdapter::canonicalExactBinding,
                semanticBindingOf = { binding ->
                    semanticBindingsByExactValue.getValue(
                        LivePolicySourceAdapter.canonicalExactBinding(binding),
                    )
                },
            )
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.EXACT_BINDING_FAILURE,
                "pregame exact actions do not prove injective current-domain membership",
                failure,
            )
        }
        val completenessSource = LiveStructuredChoiceCompletenessSource { currentDomain ->
            LiveStructuredChoiceCompletenessWitnessV1(
                sourceDomainDigest = com.wingedsheep.gym.contract.CandidateDomainDigestV1.from(currentDomain),
                sourceBindingOrdinals = alternatives.map { it.sourceBindingOrdinal }.toSet(),
                semanticBindingsByOrdinal = alternatives.associate {
                    it.sourceBindingOrdinal to it.authoritativeSemanticBinding
                },
            )
        }
        val structuredChoices = try {
            com.wingedsheep.gym.contract.LiveStructuredChoiceDomainV1.from(
                domain = domain,
                alternatives = alternatives,
                exactSourceBindings = exactBindings,
                completenessSource = completenessSource,
            )
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "pregame structured choices are not complete and injective",
                failure,
            )
        }
        return LivePolicySourceAdapter.fromStructuredObservationResult(
            result = result,
            exactSourceBindings = exactBindings,
            structuredChoices = structuredChoices,
        )
    }

    fun <T> orderedSelections(options: List<T>, count: Int): List<List<T>> {
        require(count >= 0 && count <= options.size) {
            "Ordered selection cardinality is outside its source domain"
        }
        require(options.distinct().size == options.size) {
            "Ordered selection options must be distinct"
        }
        var alternativeCount = 1L
        repeat(count) { index ->
            alternativeCount = alternativeCount * (options.size - index)
            if (alternativeCount > MAX_EXPLICIT_ALTERNATIVES) {
                throw PolicySeatFailure(
                    PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                    "pregame ordered selection domain exceeds the explicit alternative bound",
                )
            }
        }
        val selections = mutableListOf<List<T>>()

        fun visit(prefix: List<T>, remaining: List<T>) {
            if (prefix.size == count) {
                selections += prefix
                return
            }
            remaining.forEachIndexed { index, value ->
                visit(
                    prefix = prefix + value,
                    remaining = remaining.take(index) + remaining.drop(index + 1),
                )
            }
        }
        visit(emptyList(), options)
        return selections
    }

    private fun selectedCardsFeatureView(
        selectedCards: List<EntityId>,
        aliasesBySourceId: Map<String, String>,
    ) = buildJsonObject {
        put("type", "CardsSelectedResponse")
        put("selectedCardsAliases", buildJsonArray {
            selectedCards.forEach { cardId ->
                add(JsonPrimitive(aliasesBySourceId.getValue(cardId.value)))
            }
        })
    }
}
