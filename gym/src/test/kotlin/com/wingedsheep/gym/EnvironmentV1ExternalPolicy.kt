package com.wingedsheep.gym

import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.CombatResolutionDomain
import com.wingedsheep.gym.contract.DistributionDomain
import com.wingedsheep.gym.contract.ModeSelectionDomain
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.ReorderLibraryDomain
import com.wingedsheep.gym.contract.ReplacementDomain
import com.wingedsheep.gym.contract.SearchLibraryDomain
import com.wingedsheep.gym.contract.SplitPilesDomain
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.StructuredDecisionDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * State held by the test-only external chooser.
 *
 * The seed is deliberately explicit even though this policy currently uses a stable ordering
 * rather than random sampling. That makes the policy contract ready for a seeded tie-breaker
 * without introducing an implicit source of entropy.
 */
data class DeterministicPolicyState(
    val policySeed: Long,
    val choiceOrdinal: Long = 0L,
) {
    fun afterChoice(): DeterministicPolicyState = copy(choiceOrdinal = choiceOrdinal + 1L)
}

/**
 * Neutral semantic choice values returned by the observation-only policy.
 *
 * The action handle is carried only as a transport reference for the current observation. The
 * semantic key is computed from public action semantics and is what the policy uses to order
 * candidates. Decision IDs are intentionally absent here; the harness binds the public choice to
 * the current decision nonce immediately before submission.
 */
sealed interface SemanticChoice {
    val family: String

    data class Action(
        val actionId: Int,
        val semanticKey: String,
        val kind: String,
        val payload: JsonObject?,
    ) : SemanticChoice {
        override val family: String = if (kind == "DECISION") "DECISION" else kind
    }

    data class Structured(
        override val family: String,
        val selection: SemanticDecision,
    ) : SemanticChoice

    data class Gap(
        override val family: String,
        val code: String,
        val reason: String,
    ) : SemanticChoice
}

/** Decision payloads independent of engine decision IDs. */
sealed interface SemanticDecision {
    data class Targets(val selected: Map<Int, List<EntityId>>) : SemanticDecision
    data class Cards(val selected: List<EntityId>) : SemanticDecision
    data class Modes(val selected: List<Int>) : SemanticDecision
    data class Color(val selected: com.wingedsheep.sdk.core.Color) : SemanticDecision
    data class Number(val selected: Int) : SemanticDecision
    data class Distribution(val selected: Map<EntityId, Int>) : SemanticDecision
    data class Ordered(val selected: List<EntityId>) : SemanticDecision
    data class Piles(val selected: List<List<EntityId>>) : SemanticDecision
    data class Option(val selected: Int) : SemanticDecision
    data class Replacement(val from: Int, val to: Int) : SemanticDecision
    data class Budget(val selected: List<Int>) : SemanticDecision
    data class Damage(val selected: List<EdgeAmount>) : SemanticDecision
    data class Mana(
        val selectedSources: List<EntityId>,
        val waterbendPermanents: List<EntityId>,
        val declined: Boolean,
    ) : SemanticDecision
}

data class EdgeAmount(val edgeId: String, val amount: Int)

/**
 * Deterministic controller for the exact-pair acceptance corpus.
 *
 * This class intentionally has no environment, rules state, registry, action registry, or
 * diagnostic-ledger dependency. Its only input is the wire observation and a small deterministic
 * policy state.
 */
class DeterministicExternalPolicy {

    fun choose(
        observation: TrainingObservation,
        policyState: DeterministicPolicyState,
    ): SemanticChoice {
        if (observation.agentToAct == null) {
            return SemanticChoice.Gap(
                family = "TERMINAL",
                code = "A5_DECISION_GAP",
                reason = "No acting player was published for a nonterminal policy call",
            )
        }

        val pending = observation.pendingDecision
        if (pending != null && pending.requiresStructuredResponse) {
            return chooseStructured(observation, pending, policyState)
        }

        val candidates = observation.legalActions
            .filter { it.affordable || it.isDecisionOption }
            .sortedWith(
                compareBy(
                    { if (isPass(it.kind, it.description)) 1 else 0 },
                    { it.kind },
                    { canonical(it.actionSemantics) },
                    { it.sourceEntityId?.value ?: "" },
                    { it.targetEntityIds.joinToString(",") { id -> id.value } },
                )
            )
        val selected = candidates.firstOrNull()
            ?: return SemanticChoice.Gap(
                family = pending?.kind?.name ?: "PRIORITY",
                code = "A5_DECISION_GAP",
                reason = "No externally selectable legal action was published",
            )

        return chooseAction(observation, selected)
    }

    private fun chooseAction(
        observation: TrainingObservation,
        action: com.wingedsheep.gym.contract.LegalActionView,
    ): SemanticChoice {
        val semanticKey = canonical(action.actionSemantics)
        if (!action.requiresStructuredAction) {
            return SemanticChoice.Action(action.actionId, semanticKey, action.kind, null)
        }

        /*
         * The wire view deliberately does not publish a complete payment-source domain for a
         * mana-paying legal action. AutoPay/autoTapPreview is an advisory engine suggestion, not
         * proof of a unique external payment choice. Stop rather than reintroducing hidden
         * AutomaticPaymentSelection through a test helper.
         */
        if (action.manaCost != null) {
            return SemanticChoice.Gap(
                family = "PAYMENT",
                code = "A5_DECISION_GAP",
                reason = "Structured mana action has no published source-choice domain; " +
                    "manaCost=" + action.manaCost,
            )
        }

        val payload = linkedMapOf<String, JsonElement>()

        if (action.hasXCost) {
            val maxX = action.maxAffordableX
                ?: return SemanticChoice.Gap(
                    family = action.kind,
                    code = "A5_DECISION_GAP",
                    reason = "X action has no published upper bound",
                )
            payload["xValue"] = JsonPrimitive(maxX.coerceAtLeast(0))
        }

        if (action.minTargets > 0 || action.targetEntityIds.isNotEmpty()) {
            val targetCount = action.minTargets.coerceAtLeast(0)
            if (targetCount > action.maxTargets || targetCount > action.targetEntityIds.size) {
                return SemanticChoice.Gap(
                    family = "TARGETS",
                    code = "A5_DECISION_GAP",
                    reason = "Published target cardinality cannot be satisfied",
                )
            }
            val targetIds = action.targetEntityIds
                .sortedBy { it.value }
                .take(targetCount)
            val targetValues = targetIds.map { target ->
                publicTarget(observation, target)
                    ?: return SemanticChoice.Gap(
                        family = "TARGETS",
                        code = "A5_DECISION_GAP",
                        reason = "Target identity kind is not derivable from the public observation",
                    )
            }
            payload["targets"] = JsonArray(targetValues)
        }

        if (action.sacrificeCount > 0 || action.sacrificeMinCount > 0) {
            val count = if (action.sacrificeCount > 0) {
                action.sacrificeCount
            } else {
                action.sacrificeMinCount
            }
            val choices = action.validSacrificeTargets.sortedBy { it.value }.take(count)
            if (choices.size != count) {
                return SemanticChoice.Gap(
                    family = "ADDITIONAL_COST",
                    code = "A5_DECISION_GAP",
                    reason = "Published sacrifice domain cannot satisfy its cardinality",
                )
            }
            val field = if (action.kind.contains("Activate", ignoreCase = true)) {
                "costPayment"
            } else {
                "additionalCostPayment"
            }
            payload[field] = buildJsonObject {
                put(
                    "sacrificedPermanents",
                    JsonArray(choices.map { id -> JsonPrimitive(id.value) }),
                )
            }
        }

        if (action.requiresDamageDistribution) {
            return SemanticChoice.Gap(
                family = "DAMAGE_ASSIGNMENT",
                code = "A5_DECISION_GAP",
                reason = "Flat action does not publish a complete damage-distribution domain",
            )
        }

        /*
         * Crew, saddle, mana-color, alternative-payment, life-cost, modal, and combat
         * declarations have no complete actor-facing domain in LegalActionView. They must not be
         * completed from action descriptions or raw engine objects.
         */
        if (action.requiresStructuredAction && payload.isEmpty()) {
            return SemanticChoice.Gap(
                family = action.kind,
                code = "A5_DECISION_GAP",
                reason = "Structured action has no complete public choice domain",
            )
        }

        return SemanticChoice.Action(
            actionId = action.actionId,
            semanticKey = semanticKey,
            kind = action.kind,
            payload = JsonObject(payload),
        )
    }

    private fun chooseStructured(
        observation: TrainingObservation,
        pending: PendingDecisionView,
        policyState: DeterministicPolicyState,
    ): SemanticChoice {
        val domain = pending.structuredDomain
            ?: return SemanticChoice.Gap(
                family = pending.kind.name,
                code = "A5_DECISION_GAP",
                reason = "Structured decision is published without structuredDomain",
            )

        val selection: SemanticDecision = when (domain) {
            is TargetsDomain -> {
                val selected = linkedMapOf<Int, List<EntityId>>()
                for (requirement in domain.requirements.sortedBy { it.index }) {
                    val min = requirement.minTargets.coerceAtLeast(0)
                    if (min == 0) {
                        selected[requirement.index] = emptyList()
                        continue
                    }
                    if (requirement.candidates.size != 1 &&
                        (requirement.sameOwner ||
                            requirement.totalManaValueAtMost != null ||
                            requirement.differentNames)
                    ) {
                        return SemanticChoice.Gap(
                            family = "TARGETS",
                            code = "A5_DECISION_GAP",
                            reason = "Target metadata is insufficient to prove a constrained choice",
                        )
                    }
                    val candidates = requirement.candidates.sortedBy { it.value }
                    if (candidates.size < min || min > requirement.maxTargets) {
                        return SemanticChoice.Gap(
                            family = "TARGETS",
                            code = "A5_DECISION_GAP",
                            reason = "Target domain has insufficient candidates",
                        )
                    }
                    selected[requirement.index] = candidates.take(min)
                }
                SemanticDecision.Targets(selected)
            }

            is CardSelectionDomain -> {
                val result = selectCards(domain)
                    ?: return SemanticChoice.Gap(
                        family = "CARD_SELECTION",
                        code = "A5_DECISION_GAP",
                        reason = "Published card-selection domain cannot be completed deterministically",
                    )
                SemanticDecision.Cards(result)
            }

            is ModeSelectionDomain -> {
                val available = domain.modes
                    .filter { it.available }
                    .sortedBy { it.index }
                if (available.size < domain.minModes) {
                    return SemanticChoice.Gap(
                        family = "MODES",
                        code = "A5_DECISION_GAP",
                        reason = "Mode domain has fewer available modes than its minimum",
                    )
                }
                SemanticDecision.Modes(available.take(domain.minModes).map { it.index })
            }

            is DistributionDomain -> {
                val targets = domain.targets.sortedBy { it.value }
                val assignment = linkedMapOf<EntityId, Int>()
                targets.forEach { id -> assignment[id] = 0 }
                val minimum = domain.minPerTarget.coerceAtLeast(0)
                val capacity = targets.sumOf { id ->
                    (domain.maxPerTarget[id] ?: domain.totalAmount).coerceAtLeast(0)
                }
                val requiredMinimum = minimum * targets.size
                if (requiredMinimum > domain.totalAmount && !domain.allowPartial) {
                    return SemanticChoice.Gap(
                        family = "DISTRIBUTION",
                        code = "A5_DECISION_GAP",
                        reason = "Distribution minimum exceeds the published total",
                    )
                }
                var remaining = if (domain.allowPartial) {
                    domain.totalAmount.coerceAtMost(capacity)
                } else {
                    domain.totalAmount
                }
                for (id in targets) {
                    val max = (domain.maxPerTarget[id] ?: domain.totalAmount).coerceAtLeast(0)
                    val amount = minimum.coerceAtMost(max).coerceAtMost(remaining)
                    assignment[id] = amount
                    remaining -= amount
                }
                var index = 0
                while (remaining > 0 && targets.isNotEmpty()) {
                    val id = targets[index % targets.size]
                    val max = (domain.maxPerTarget[id] ?: domain.totalAmount).coerceAtLeast(0)
                    val next = (assignment[id] ?: 0) + 1
                    if (next <= max) {
                        assignment[id] = next
                        remaining--
                    }
                    index++
                    if (index > targets.size * (domain.totalAmount + 1)) break
                }
                if (remaining > 0 && !domain.allowPartial) {
                    return SemanticChoice.Gap(
                        family = "DISTRIBUTION",
                        code = "A5_DECISION_GAP",
                        reason = "Distribution capacity cannot satisfy the published total",
                    )
                }
                SemanticDecision.Distribution(assignment)
            }

            is OrderingDomain -> SemanticDecision.Ordered(domain.objects)

            is SplitPilesDomain -> {
                if (domain.numberOfPiles <= 0) {
                    return SemanticChoice.Gap(
                        family = "SPLIT_PILES",
                        code = "A5_DECISION_GAP",
                        reason = "Split-piles domain publishes no positive pile count",
                    )
                }
                val piles = List(domain.numberOfPiles) { mutableListOf<EntityId>() }
                domain.cards.forEachIndexed { index, id ->
                    piles[index % domain.numberOfPiles].add(id)
                }
                SemanticDecision.Piles(piles.map { it.toList() })
            }

            is SearchLibraryDomain -> {
                val options = domain.options.sortedBy { it.value }
                if (options.size < domain.minSelections) {
                    return SemanticChoice.Gap(
                        family = "SEARCH",
                        code = "A5_DECISION_GAP",
                        reason = "Search domain has fewer options than its minimum",
                    )
                }
                SemanticDecision.Cards(options.take(domain.minSelections))
            }

            is ReorderLibraryDomain -> SemanticDecision.Ordered(domain.cards)

            is CombatResolutionDomain -> {
                val actor = pending.playerId
                val edges = domain.edges
                    .filter { it.editableBy == actor }
                    .sortedBy { it.id }
                    .map { edge -> EdgeAmount(edge.id, edge.amount.coerceIn(0, edge.maximum)) }
                SemanticDecision.Damage(edges)
            }

            is ManaSourcesDomain -> {
                val selected = domain.autoPaySuggestion
                    .distinct()
                    .sortedBy { it.value }
                val declined = selected.isEmpty() && domain.canDecline
                if (selected.isEmpty() && !declined) {
                    return SemanticChoice.Gap(
                        family = "PAYMENT",
                        code = "A5_DECISION_GAP",
                        reason = "Mana-source domain has no explicit payable selection or decline",
                    )
                }
                SemanticDecision.Mana(
                    selectedSources = selected,
                    waterbendPermanents = emptyList(),
                    declined = declined,
                )
            }

            is ReplacementDomain -> {
                if (domain.fromOptions.isEmpty() || domain.toOptions.isEmpty()) {
                    return SemanticChoice.Gap(
                        family = "REPLACEMENT",
                        code = "A5_DECISION_GAP",
                        reason = "Replacement domain has an empty side",
                    )
                }
                val from = domain.defaultFromIndex
                    ?.takeIf { it in domain.fromOptions.indices }
                    ?: 0
                val allowed = domain.allowedToByFrom.getOrNull(from)
                val to = (allowed?.sorted()?.firstOrNull() ?: 0)
                    .takeIf { it in domain.toOptions.indices }
                    ?: return SemanticChoice.Gap(
                        family = "REPLACEMENT",
                        code = "A5_DECISION_GAP",
                        reason = "Replacement domain has no valid TO option",
                    )
                SemanticDecision.Replacement(from, to)
            }

            is com.wingedsheep.gym.contract.BudgetModalDomain -> {
                val selected = domain.modes
                    .mapIndexed { index, mode -> index to mode }
                    .filter { (_, mode) -> mode.cost <= domain.budget }
                    .firstOrNull()
                    ?.first
                SemanticDecision.Budget(if (selected == null) emptyList() else listOf(selected))
            }
        }

        return SemanticChoice.Structured(pending.kind.name, selection)
    }

    private fun selectCards(domain: CardSelectionDomain): List<EntityId>? {
        val options = domain.options
            .filterNot { it in domain.nonSelectableOptions }
            .distinct()
            .sortedBy { it.value }
        val needsInfo = domain.onePerCardType ||
            domain.onePerColor ||
            domain.onePerCardName ||
            domain.onePerBasicLandType ||
            domain.onePerPower ||
            domain.maxTotalManaValue != null ||
            domain.minTotalManaValue != null ||
            domain.maxTotalPower != null ||
            domain.conditionalMinimums.isNotEmpty()
        if (needsInfo && domain.cardInfo == null && domain.minSelections > 0) return null

        val selected = mutableListOf<EntityId>()
        val usedTypes = mutableSetOf<String>()
        val usedColors = mutableSetOf<String>()
        val usedNames = mutableSetOf<String>()
        val usedBasicTypes = mutableSetOf<String>()
        val usedPowers = mutableSetOf<Int>()
        var manaValue = 0
        var power = 0

        fun info(id: EntityId): StructuredCardInfo? = domain.cardInfo?.get(id)

        fun canAdd(id: EntityId): Boolean {
            val card = info(id)
            if (needsInfo && card == null) return false
            if (card == null) return true
            val typeKey = card.typeLine.substringBefore("—").trim()
            val basicTypes = card.typeLine
                .substringAfter("—", "")
                .split(" ", ",")
                .map { it.trim().uppercase() }
                .filter { it in BASIC_LAND_TYPES }
                .toSet()
            if (domain.onePerCardType && !usedTypes.add(typeKey)) return false
            if (domain.onePerColor && card.colors.any { it.uppercase() in usedColors }) return false
            if (domain.onePerCardName && !usedNames.add(card.name)) return false
            if (domain.onePerBasicLandType &&
                (basicTypes.isEmpty() || basicTypes.any { it in usedBasicTypes })
            ) return false
            if (domain.onePerPower && (card.power == null || !usedPowers.add(card.power))) return false
            val nextManaValue = manaValue + manaValue(card.manaCost)
            val nextPower = power + (card.power ?: 0)
            if (domain.maxTotalManaValue != null && nextManaValue > domain.maxTotalManaValue) return false
            if (domain.maxTotalPower != null && nextPower > domain.maxTotalPower) return false
            return true
        }

        fun commit(id: EntityId) {
            val card = info(id)
            selected += id
            if (card != null) {
                usedTypes += card.typeLine.substringBefore("—").trim()
                usedColors += card.colors.map { it.uppercase() }
                usedNames += card.name
                usedBasicTypes += card.typeLine
                    .substringAfter("—", "")
                    .split(" ", ",")
                    .map { it.trim().uppercase() }
                    .filter { it in BASIC_LAND_TYPES }
                card.power?.let { usedPowers += it }
                manaValue += manaValue(card.manaCost)
                power += card.power ?: 0
            }
        }

        for (id in options) {
            if (selected.size >= domain.maxSelections) break
            if (canAdd(id)) commit(id)
        }

        if (selected.size < domain.minSelections) return null
        if (domain.minTotalManaValue != null && manaValue < domain.minTotalManaValue) return null
        for (minimum in domain.conditionalMinimums) {
            if (selected.size >= minimum.requiredSelections) {
                val matches = selected.count { it in minimum.matchingOptions }
                if (matches < minimum.requiredMatches) return null
            }
        }
        return selected
    }

    private fun publicTarget(observation: TrainingObservation, id: EntityId): JsonObject? {
        if (observation.players.any { it.id == id }) {
            return buildJsonObject {
                put("type", "Player")
                put("playerId", id.value)
            }
        }
        if (observation.stack.any { it.entityId == id }) {
            return buildJsonObject {
                put("type", "Spell")
                put("spellEntityId", id.value)
            }
        }
        val feature = observation.zones
            .asSequence()
            .flatMap { it.cards.asSequence() }
            .firstOrNull { it.entityId == id }
            ?: return null
        return if (feature.zone == Zone.BATTLEFIELD) {
            buildJsonObject {
                put("type", "Permanent")
                put("entityId", id.value)
            }
        } else {
            val owner = feature.ownerId ?: return null
            buildJsonObject {
                put("type", "Card")
                put("cardId", id.value)
                put("ownerId", owner.value)
                put("zone", feature.zone.name)
            }
        }
    }

    private fun isPass(kind: String, description: String): Boolean =
        kind.contains("Pass", ignoreCase = true) ||
            description.contains("pass priority", ignoreCase = true)

    private fun canonical(element: JsonElement?): String = when (element) {
        null, JsonNull -> "null"
        is JsonObject -> element.keys.sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { key -> key + ":" + canonical(element[key]) }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonical(it)
        }
        is JsonPrimitive -> element.toString()
    }

    private fun manaValue(manaCost: String): Int =
        TOKEN_REGEX.findAll(manaCost).sumOf { match ->
            val token = match.groupValues[1]
            token.toIntOrNull() ?: if (token == "X") 0 else 1
        }

    private companion object {
        val TOKEN_REGEX = Regex("\\{([^}]+)}")
        val BASIC_LAND_TYPES = setOf("PLAINS", "ISLAND", "SWAMP", "MOUNTAIN", "FOREST")
    }
}
