package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ActionRegistry
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.LiveExactSourceBindingEntry
import com.wingedsheep.gym.contract.LiveExactSourceBindingTable
import com.wingedsheep.gym.contract.LivePolicyDecisionSnapshotV1
import com.wingedsheep.gym.contract.LiveStructuredChoiceDomainV1
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.TrainingObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** JVM-only exact source value selected after the Python ordinal is revalidated. */
sealed interface PolicySeatExactBinding {
    data class GameActionBinding(val action: GameAction) : PolicySeatExactBinding
    data class LegalActionBinding(val legalAction: LegalAction) : PolicySeatExactBinding
    data class DecisionResponseBinding(val response: DecisionResponse) : PolicySeatExactBinding
}

/**
 * One source-owned C1_07A snapshot plus the exact table that may be used only by the JVM.
 * Nothing in this type is serialized into the Python request.
 */
data class LivePolicySourceSnapshot(
    val observation: TrainingObservation,
    val snapshot: LivePolicyDecisionSnapshotV1,
    val exactSourceBindings: LiveExactSourceBindingTable<PolicySeatExactBinding>,
) {
    fun requireCurrent(current: LivePolicySourceSnapshot): LivePolicySourceSnapshot {
        try {
            snapshot.requireCurrent(
                currentObservation = current.observation,
                currentExactSourceBindings = current.exactSourceBindings,
                currentStructuredChoices = current.snapshot.structuredChoiceDomain,
            )
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.STALE_INFERENCE,
                "live policy inference no longer matches the current decision boundary",
                failure,
            )
        }
        return current
    }
}

/**
 * Concrete source-owned adapter from the current observation registry to C1_07A exact bindings.
 * It is the only production location that supplies the semantic binding mapper.
 */
object LivePolicySourceAdapter {
    private val exactSerialization = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        allowStructuredMapKeys = true
        serializersModule = engineSerializersModule
    }

    internal fun semanticGameAction(action: GameAction): kotlinx.serialization.json.JsonObject =
        exactSerialization.encodeToJsonElement(GameAction.serializer(), action)
            .jsonObject

    internal fun canonicalExactBinding(binding: PolicySeatExactBinding): String =
        canonicalizeExactBinding(binding)

    fun fromObservationResult(result: ObservationResult): LivePolicySourceSnapshot {
        if (result.diagnostics.isNotEmpty()) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "live observation contains unsupported authoritative diagnostics: " +
                    result.diagnostics.joinToString(prefix = "[", postfix = "]") { it.semanticCode },
            )
        }
        val observation = result.observation as? TrainingObservation
            ?: throw PolicySeatFailure(
                PolicySeatFailureCode.SESSION_NOT_READY,
                "live policy requires a game TrainingObservation",
            )
        val domain = try {
            CompleteLegalDomainV1.from(observation)
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "current decision cannot be represented by the complete C1 domain",
                failure,
            )
        }
        if (domain.kind == CompleteLegalDomainKind.STRUCTURED_DECISION) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "C1_07C does not invent structured-choice alternatives",
            )
        }

        val exactEntries = exactEntries(domain.kind, result.registry)
        if (exactEntries.size != observation.legalActions.size || exactEntries.isEmpty()) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.EXACT_BINDING_FAILURE,
                "the current observation and exact registry do not have equal non-empty coverage",
            )
        }
        val semanticByBinding = exactEntries.zip(observation.legalActions)
            .associate { (binding, view) ->
                binding to ObservationCanonicalizer.semanticActionFingerprint(view)
            }
        val entries = exactEntries.mapIndexed { ordinal, binding ->
            LiveExactSourceBindingEntry(ordinal, binding)
        }
        val table = try {
            LiveExactSourceBindingTable.fromCurrentDomain(
                domain = domain,
                entries = entries,
                canonicalizeValue = ::canonicalizeExactBinding,
                semanticBindingOf = { binding ->
                    semanticByBinding[binding]
                        ?: error("source-owned exact binding is absent from its current semantic map")
                },
            )
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.EXACT_BINDING_FAILURE,
                "current exact bindings do not prove injective domain membership",
                failure,
            )
        }
        val snapshot = try {
            LivePolicyDecisionSnapshotV1.from(
                observation = observation,
                exactSourceBindings = table,
            )
        } catch (failure: UnsupportedPathFailure) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "current live decision is not admitted by C1_07A",
                failure,
            )
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.EXACT_BINDING_FAILURE,
                "current live observation cannot form a C1_07A snapshot",
                failure,
            )
        }
        return LivePolicySourceSnapshot(
            observation = observation,
            snapshot = snapshot,
            exactSourceBindings = table,
        )
    }

    /**
     * Build a live source snapshot for a source-owned structured choice list. The exact table and
     * complete alternatives are constructed by the JVM source adapter; this overload only joins
     * them to the shared observation/snapshot contract and never serializes either exact value.
     */
    fun fromStructuredObservationResult(
        result: ObservationResult,
        exactSourceBindings: LiveExactSourceBindingTable<PolicySeatExactBinding>,
        structuredChoices: LiveStructuredChoiceDomainV1,
    ): LivePolicySourceSnapshot {
        if (result.diagnostics.isNotEmpty()) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "live observation contains unsupported authoritative diagnostics: " +
                    result.diagnostics.joinToString(prefix = "[", postfix = "]") { it.semanticCode },
            )
        }
        val observation = result.observation as? TrainingObservation
            ?: throw PolicySeatFailure(
                PolicySeatFailureCode.SESSION_NOT_READY,
                "live policy requires a game TrainingObservation",
            )
        val snapshot = try {
            LivePolicyDecisionSnapshotV1.from(
                observation = observation,
                exactSourceBindings = exactSourceBindings,
                structuredChoices = structuredChoices,
            )
        } catch (failure: UnsupportedPathFailure) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.UNSUPPORTED_STRUCTURED_DECISION,
                "current live structured decision is not admitted by C1_07A",
                failure,
            )
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.EXACT_BINDING_FAILURE,
                "current live structured decision cannot form a C1_07A snapshot",
                failure,
            )
        }
        return LivePolicySourceSnapshot(
            observation = observation,
            snapshot = snapshot,
            exactSourceBindings = exactSourceBindings,
        )
    }

    private fun exactEntries(
        kind: CompleteLegalDomainKind,
        registry: ActionRegistry,
    ): List<PolicySeatExactBinding> = when (kind) {
        CompleteLegalDomainKind.ACTION_CANDIDATES ->
            registry.legalActions.map { (_, legalAction) ->
                PolicySeatExactBinding.LegalActionBinding(legalAction)
            }

        CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS ->
            registry.decisionResponses.map { (_, response) ->
                PolicySeatExactBinding.DecisionResponseBinding(response)
            }

        CompleteLegalDomainKind.STRUCTURED_DECISION -> emptyList()
    }

    private fun canonicalizeExactBinding(binding: PolicySeatExactBinding): String =
        when (binding) {
            is PolicySeatExactBinding.GameActionBinding ->
                A3SemanticJson.canonicalJson(
                    semanticGameAction(binding.action),
                )

            is PolicySeatExactBinding.LegalActionBinding ->
                A3SemanticJson.canonicalJson(
                    exactSerialization.encodeToJsonElement(GameAction.serializer(), binding.legalAction.action),
                )

            is PolicySeatExactBinding.DecisionResponseBinding ->
                A3SemanticJson.canonicalJson(
                    exactSerialization.encodeToJsonElement(DecisionResponse.serializer(), binding.response),
                )
        }
}
