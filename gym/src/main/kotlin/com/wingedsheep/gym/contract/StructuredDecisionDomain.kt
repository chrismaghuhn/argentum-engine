package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the typed structured-decision payloads nested in [PendingDecisionView]. */
const val STRUCTURED_DECISION_DOMAIN_VERSION: Int = 1
/** Version of the mana-source pending-decision payload after replacing runtime ability handles. */
const val MANA_SOURCES_DOMAIN_VERSION: Int = 2

/**
 * Perspective-safe, typed descriptions of decisions that cannot be represented by one flat
 * action ID.  Every member is a projection of an authoritative Rules decision; this hierarchy
 * contains no engine continuation or raw [com.wingedsheep.engine.core.PendingDecision].
 */
@Serializable
sealed interface StructuredDecisionDomain {
    val version: Int
}

@Serializable
data class TargetRequirementDomain(
    val index: Int,
    val description: String,
    val minTargets: Int,
    val maxTargets: Int,
    val candidates: List<EntityId>,
    val sameOwner: Boolean,
    val totalManaValueAtMost: Int?,
    val differentNames: Boolean
)

@Serializable
@SerialName("targets")
data class TargetsDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val requirements: List<TargetRequirementDomain>,
    val canCancel: Boolean
) : StructuredDecisionDomain

@Serializable
data class StructuredCardInfo(
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val imageUri: String? = null,
    val colors: List<String> = emptyList(),
    val power: Int? = null
)

@Serializable
data class ConditionalSelectionMinimumDomain(
    val requiredSelections: Int,
    val minimumSelections: Int,
    val matchingOptions: List<EntityId>,
    val requiredMatches: Int,
    val description: String?
)

@Serializable
@SerialName("card-selection")
data class CardSelectionDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val options: List<EntityId>,
    val minSelections: Int,
    val maxSelections: Int,
    val ordered: Boolean,
    val cardInfo: Map<EntityId, StructuredCardInfo>? = null,
    val useTargetingUI: Boolean,
    val selectedLabel: String?,
    val remainderLabel: String?,
    val nonSelectableOptions: List<EntityId>,
    val onePerCardType: Boolean,
    val onePerColor: Boolean,
    val availableColors: List<String>?,
    val onePerCardName: Boolean,
    val onePerBasicLandType: Boolean,
    val onePerPower: Boolean,
    val maxTotalManaValue: Int?,
    val minTotalManaValue: Int?,
    val maxTotalPower: Int?,
    val conditionalMinimums: List<ConditionalSelectionMinimumDomain>
) : StructuredDecisionDomain

@Serializable
data class ModeOptionDomain(
    val index: Int,
    val text: String,
    val available: Boolean
)

@Serializable
@SerialName("mode-selection")
data class ModeSelectionDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val modes: List<ModeOptionDomain>,
    val minModes: Int,
    val maxModes: Int
) : StructuredDecisionDomain

@Serializable
@SerialName("distribution")
data class DistributionDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val totalAmount: Int,
    val targets: List<EntityId>,
    val minPerTarget: Int,
    val maxPerTarget: Map<EntityId, Int>,
    val allowPartial: Boolean
) : StructuredDecisionDomain

@Serializable
@SerialName("ordering")
data class OrderingDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val objects: List<EntityId>,
    val cardInfo: Map<EntityId, StructuredCardInfo>? = null,
    val objectLabels: Map<EntityId, String>? = null
) : StructuredDecisionDomain

@Serializable
@SerialName("split-piles")
data class SplitPilesDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val cards: List<EntityId>,
    val numberOfPiles: Int,
    val pileLabels: List<String>,
    val cardInfo: Map<EntityId, StructuredCardInfo>? = null
) : StructuredDecisionDomain

@Serializable
data class OptionMetadataDomain(
    val id: String?,
    val description: String?,
    val iconKey: String?,
    val triggeringPlayerId: EntityId?
)

@Serializable
@SerialName("search-library")
data class SearchLibraryDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val options: List<EntityId>,
    val minSelections: Int,
    val maxSelections: Int,
    val cards: Map<EntityId, StructuredCardInfo>,
    val filterDescription: String
) : StructuredDecisionDomain

@Serializable
@SerialName("reorder-library")
data class ReorderLibraryDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    /** First element is the current top of library; this order is semantic. */
    val cards: List<EntityId>,
    val cardInfo: Map<EntityId, StructuredCardInfo>
) : StructuredDecisionDomain

@Serializable
enum class CombatDamageDirection {
    ATTACKER_TO_BLOCKER,
    BLOCKER_TO_ATTACKER,
    ATTACKER_TO_PLAYER,
    ATTACKER_TO_PLANESWALKER,
    ATTACKER_TO_BATTLE
}

@Serializable
enum class CombatTargetKind {
    PLAYER,
    PLANESWALKER,
    BATTLE
}

@Serializable
data class CombatDamageEdgeDomain(
    val id: String,
    val sourceId: EntityId,
    val targetId: EntityId,
    val direction: CombatDamageDirection,
    val amount: Int,
    val maximum: Int,
    val lethal: Int,
    val isTrampleDrain: Boolean,
    val editableBy: EntityId
)

@Serializable
data class CombatAttackerDomain(
    val id: EntityId,
    val name: String,
    val power: Int,
    val toughness: Int,
    val hasTrample: Boolean,
    val hasDeathtouch: Boolean,
    val hasFirstStrike: Boolean,
    val hasDoubleStrike: Boolean,
    val dealsDamageThisStep: Boolean,
    val bandId: String?,
    val attackedDefenderId: EntityId,
    val blockedByIds: List<EntityId>,
    val markedDamage: Int
)

@Serializable
data class CombatBlockerDomain(
    val id: EntityId,
    val name: String,
    val power: Int,
    val toughness: Int,
    val hasDeathtouch: Boolean,
    val hasFirstStrike: Boolean,
    val hasDoubleStrike: Boolean,
    val dealsDamageThisStep: Boolean,
    val blockedAttackerIds: List<EntityId>,
    val markedDamage: Int
)

@Serializable
data class CombatDefenderDomain(
    val id: EntityId,
    val kind: CombatTargetKind,
    val name: String,
    val lifeOrLoyaltyOrDefense: Int?
)

@Serializable
@SerialName("combat-resolution")
data class CombatResolutionDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val firstStrike: Boolean,
    val attackers: List<CombatAttackerDomain>,
    val blockers: List<CombatBlockerDomain>,
    val defenders: List<CombatDefenderDomain>,
    val edges: List<CombatDamageEdgeDomain>,
    val coChooserId: EntityId?
) : StructuredDecisionDomain

@Serializable
data class ManaSourceDomain(
    val entityId: EntityId,
    val name: String,
    val producesColors: Set<Color>,
    val producesColorless: Boolean,
    val requiresSacrifice: Boolean,
    val requiresTappingAnotherPermanent: Boolean,
    /** Stable structural identity; runtime AbilityId handles are never published. */
    val manaAbilityKey: String?
)

@Serializable
data class WaterbendPermanentDomain(
    val entityId: EntityId,
    val name: String,
    val isCreature: Boolean
)

@Serializable
@SerialName("mana-sources")
data class ManaSourcesDomain(
    override val version: Int = MANA_SOURCES_DOMAIN_VERSION,
    val availableSources: List<ManaSourceDomain>,
    val requiredCost: String,
    /** Advisory only; it is never treated as the sole legal response. */
    val autoPaySuggestion: List<EntityId>,
    val canDecline: Boolean,
    val waterbendPermanents: List<WaterbendPermanentDomain>
) : StructuredDecisionDomain

@Serializable
@SerialName("replacement")
data class ReplacementDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val fromOptions: List<String>,
    val toOptions: List<String>,
    val fromMetadata: List<OptionMetadataDomain>,
    val toMetadata: List<OptionMetadataDomain>,
    val allowedToByFrom: List<List<Int>>,
    val defaultFromIndex: Int?
) : StructuredDecisionDomain

@Serializable
data class BudgetModeDomain(
    val cost: Int,
    val description: String
)

@Serializable
@SerialName("budget-modal")
data class BudgetModalDomain(
    override val version: Int = STRUCTURED_DECISION_DOMAIN_VERSION,
    val budget: Int,
    val modes: List<BudgetModeDomain>
) : StructuredDecisionDomain
