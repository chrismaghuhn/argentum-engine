package com.wingedsheep.engine.handlers

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.CantBeTargetedByOpponentAbilitiesComponent
import com.wingedsheep.engine.state.components.battlefield.CastChoicesComponent
import com.wingedsheep.engine.state.components.battlefield.ChoiceValue
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.mechanics.ControllerGrants
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.mechanics.targeting.ControllerHexproof
import com.wingedsheep.engine.mechanics.targeting.ControllerShroud
import com.wingedsheep.engine.mechanics.targeting.PlayerTargetRestriction
import com.wingedsheep.engine.mechanics.targeting.StackObjectTargeting
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ChoiceSlot
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.predicates.ControllerPredicate
import com.wingedsheep.sdk.scripting.predicates.StatePredicate
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.*
import com.wingedsheep.sdk.scripting.values.EntityReference

/**
 * Identifies the type of source that is doing the targeting.
 * Used to implement restrictions like "can't be the target of abilities your opponents control"
 * which only block abilities, not spells.
 */
enum class TargetingSourceType {
    /** The source is a spell (instant/sorcery/aura/etc.) */
    SPELL,
    /** The source is an activated or triggered ability */
    ABILITY,
    /** Unknown or default — no source-type-based restrictions apply */
    ANY
}

/**
 * Finds legal targets for a given target requirement.
 *
 * This class evaluates a TargetRequirement against the current game state
 * and returns a list of valid target EntityIds.
 */
class TargetFinder(
) {
    private val predicateEvaluator = PredicateEvaluator()

    private data class RequiredPredicateContext(
        val controllerId: Boolean = false,
        val sourceId: Boolean = false,
        val triggeringEntityId: Boolean = false,
        val triggeringPlayerId: Boolean = false,
        val defendingPlayerId: Boolean = false,
        val targetPlayerId: Boolean = false,
        val targetOpponentId: Boolean = false,
        val granterId: Boolean = false,
        val affectedEntityId: Boolean = false,
        val recipientId: Boolean = false,
        val damageSourceId: Boolean = false,
        val damageRecipientId: Boolean = false,
        val xValue: Boolean = false,
        val pipeline: Boolean = false,
        val chosenColor: Boolean = false,
        val sourceTextChoiceSlots: Set<ChoiceSlot> = emptySet(),
        val sourceColorChoiceSlots: Set<ChoiceSlot> = emptySet(),
        val chosenValues: Set<String> = emptySet(),
        val storedStringLists: Set<String> = emptySet(),
        val storedSubtypeGroups: Set<String> = emptySet(),
        val storedCollections: Map<String, Set<Int>> = emptyMap(),
        val targetIndexes: Set<Int> = emptySet(),
        val controllerTargetIndexes: Set<Int> = emptySet(),
        val namedTargets: Set<String> = emptySet(),
        val unsupported: Boolean = false,
    ) {
        operator fun plus(other: RequiredPredicateContext): RequiredPredicateContext =
            RequiredPredicateContext(
                controllerId = controllerId || other.controllerId,
                sourceId = sourceId || other.sourceId,
                triggeringEntityId = triggeringEntityId || other.triggeringEntityId,
                triggeringPlayerId = triggeringPlayerId || other.triggeringPlayerId,
                defendingPlayerId = defendingPlayerId || other.defendingPlayerId,
                targetPlayerId = targetPlayerId || other.targetPlayerId,
                targetOpponentId = targetOpponentId || other.targetOpponentId,
                granterId = granterId || other.granterId,
                affectedEntityId = affectedEntityId || other.affectedEntityId,
                recipientId = recipientId || other.recipientId,
                damageSourceId = damageSourceId || other.damageSourceId,
                damageRecipientId = damageRecipientId || other.damageRecipientId,
                xValue = xValue || other.xValue,
                pipeline = pipeline || other.pipeline,
                chosenColor = chosenColor || other.chosenColor,
                sourceTextChoiceSlots = sourceTextChoiceSlots + other.sourceTextChoiceSlots,
                sourceColorChoiceSlots = sourceColorChoiceSlots + other.sourceColorChoiceSlots,
                chosenValues = chosenValues + other.chosenValues,
                storedStringLists = storedStringLists + other.storedStringLists,
                storedSubtypeGroups = storedSubtypeGroups + other.storedSubtypeGroups,
                storedCollections = mergeRequirements(storedCollections, other.storedCollections),
                targetIndexes = targetIndexes + other.targetIndexes,
                controllerTargetIndexes = controllerTargetIndexes + other.controllerTargetIndexes,
                namedTargets = namedTargets + other.namedTargets,
                unsupported = unsupported || other.unsupported,
            )

        private fun mergeRequirements(
            left: Map<String, Set<Int>>,
            right: Map<String, Set<Int>>,
        ): Map<String, Set<Int>> = buildMap {
            left.forEach { (key, indices) -> put(key, indices) }
            right.forEach { (key, indices) -> put(key, (get(key) ?: emptySet()) + indices) }
        }

        fun isAvailable(
            state: GameState,
            controllerId: EntityId,
            sourceId: EntityId?,
            triggeringEntityId: EntityId?,
            pipelineContext: PredicateContext?,
        ): Boolean {
            if (unsupported) return false
            if (pipeline && pipelineContext == null) return false

            val context = (pipelineContext ?: PredicateContext(controllerId = controllerId)).copy(
                controllerId = controllerId,
                sourceId = sourceId ?: pipelineContext?.sourceId,
                triggeringEntityId = triggeringEntityId ?: pipelineContext?.triggeringEntityId,
            )
            if (this.controllerId && context.controllerId != controllerId) return false
            if (this.sourceId && (context.sourceId == null || state.getEntity(context.sourceId) == null)) return false
            if (this.triggeringEntityId && (context.triggeringEntityId == null || state.getEntity(context.triggeringEntityId) == null)) return false
            if (triggeringPlayerId && context.triggeringPlayerId == null && context.triggeringEntityId == null) return false
            if (defendingPlayerId && context.defendingPlayerId == null) return false
            if (targetPlayerId && context.targetPlayerId == null) return false
            if (targetOpponentId && context.targetOpponentId == null) return false
            if (granterId && context.granterId == null) return false
            if (affectedEntityId && context.affectedEntityId == null) return false
            if (recipientId && context.recipientId == null) return false
            if (damageSourceId && context.damageSourceId == null) return false
            if (damageRecipientId && context.damageRecipientId == null) return false
            if (xValue && context.xValue == null) return false
            if (chosenColor && context.chosenColor == null) return false
            val sourceChoices = context.sourceId
                ?.let { state.getEntity(it)?.get<CastChoicesComponent>()?.chosen }
            if (sourceTextChoiceSlots.any { slot ->
                    sourceChoices?.get(slot) !is ChoiceValue.TextChoice
                }) return false
            if (sourceColorChoiceSlots.any { slot ->
                    sourceChoices?.get(slot) !is ChoiceValue.ColorChoice
                }) return false
            if (chosenValues.any { it !in context.chosenValues }) return false
            if (storedStringLists.any { it !in context.storedStringLists }) return false
            if (storedSubtypeGroups.any { it !in context.storedSubtypeGroups }) return false
            if (targetIndexes.any { index ->
                    (context.targets.getOrNull(index) as? ChosenTarget.Player) == null
                }) return false
            if (controllerTargetIndexes.any { index ->
                    resolveControllerOfChosenTarget(
                        state,
                        state.projectedState,
                        context.targets.getOrNull(index),
                    ) == null
                }) return false
            if (namedTargets.any { name ->
                    (context.namedTargets[name] as? ChosenTarget.Player) == null
                }) return false
            if (storedCollections.any { (name, indices) ->
                    val collection = context.storedCollections[name] ?: return@any true
                    indices.any { index -> collection.getOrNull(index) == null }
                }) return false
            return true
        }
    }

    /**
     * Build the per-candidate [PredicateContext] for filter evaluation, folding in any
     * pipeline-derived fields (storedCollections, chosenValues, xValue, …) carried by
     * [pipelineContext]. Keeps the always-present `controllerId`/`sourceId`/`ownerId` from
     * the call site while letting resolution-time filters see the resolving effect's pipeline
     * state — needed for "power <= the amassed Army's power" (EntityReference.AmassedArmy).
     */
    private fun targetingContext(
        controllerId: EntityId,
        sourceId: EntityId? = null,
        ownerId: EntityId? = null,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null
    ): PredicateContext =
        (pipelineContext ?: PredicateContext(controllerId = controllerId)).copy(
            controllerId = controllerId,
            sourceId = sourceId ?: pipelineContext?.sourceId,
            ownerId = ownerId,
            // Carry the trigger's associated entity so a target filter can scope to "that player"
            // (ControllerPredicate.ControlledByTriggeringPlayer / OwnedByTriggeringPlayer) — e.g.
            // Dreadmaw's Ire's "destroy target artifact that player controls". Only overrides when a
            // triggering entity is supplied, so a pipeline-derived context keeps its own value.
            triggeringEntityId = triggeringEntityId ?: pipelineContext?.triggeringEntityId
        )

    /**
     * Find all legal targets for a given requirement.
     *
     * @param state The current game state
     * @param requirement The target requirement to satisfy
     * @param controllerId The player who is choosing targets (for "you control" filters)
     * @param sourceId The source of the targeting ability (to exclude "other" targets)
     * @param ignoreTargetingRestrictions If true, hexproof and shroud are bypassed.
     *   Use for aura attachment (Rule 303.4f): when an aura enters the battlefield without
     *   being cast, the controller chooses what it enchants — normal targeting restrictions
     *   like hexproof and shroud do not apply.
     * @return List of valid target EntityIds
     */
    fun findLegalTargets(
        state: GameState,
        requirement: TargetRequirement,
        controllerId: EntityId,
        sourceId: EntityId? = null,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        /**
         * Pipeline-derived predicate context (storedCollections, chosenValues, xValue, …) from the
         * resolving effect. Threaded so a target filter can compare candidates against a
         * resolution-time pipeline value — e.g. "power <= the amassed Army's power" reads
         * [EntityReference.AmassedArmy] out of `pipelineContext.storedCollections`. Null for
         * cast-time targeting where no pipeline state exists yet.
         */
        pipelineContext: PredicateContext? = null,
        /**
         * Pending target decisions must not use the legacy permissive behavior for an unbound X or
         * unavailable predicate relation. When enabled, the target requirement is structurally
         * inspected before enumeration and the candidate set is empty until every required fact is
         * available from the explicit source/trigger arguments or the supplied [pipelineContext].
         * This gate is deliberately stricter than the legacy evaluator: a predicate that would
         * otherwise default to false (and become true when negated) is never allowed to publish an
         * unknown candidate set.
         */
        requireAuthoritativeContext: Boolean = false,
    ): List<EntityId> {
        if (requireAuthoritativeContext) {
            val requiredContext = requirement.requiredPredicateContext()
            if (!requiredContext.isAvailable(state, controllerId, sourceId, triggeringEntityId, pipelineContext)) {
                return emptyList()
            }
        }
        return when (requirement) {
            is TargetPlayer -> findPlayerTargets(state, requirement, controllerId, sourceId, ignoreTargetingRestrictions)
            is TargetOpponent -> findOpponentTargets(state, requirement, controllerId, sourceId, ignoreTargetingRestrictions)
            is AnyTarget -> findAnyTargets(state, controllerId, sourceId, ignoreTargetingRestrictions, targetingSourceType)
            is TargetCreatureOrPlayer -> findCreatureOrPlayerTargets(
                state,
                controllerId,
                sourceId,
                ignoreTargetingRestrictions,
                targetingSourceType,
                pipelineContext
            )
            is TargetPermanentOrPlayer -> findPermanentOrPlayerTargets(
                state,
                requirement,
                controllerId,
                sourceId,
                ignoreTargetingRestrictions,
                targetingSourceType,
                triggeringEntityId,
                pipelineContext
            )
            is TargetOpponentOrPlaneswalker -> findOpponentOrPlaneswalkerTargets(
                state,
                controllerId,
                sourceId,
                ignoreTargetingRestrictions,
                targetingSourceType
            )
            is TargetPlayerOrPlaneswalker -> findPlayerOrPlaneswalkerTargets(
                state,
                controllerId,
                sourceId,
                ignoreTargetingRestrictions,
                targetingSourceType
            )
            is TargetCreatureOrPlaneswalker -> findCreatureOrPlaneswalkerTargets(
                state,
                controllerId,
                sourceId,
                ignoreTargetingRestrictions,
                targetingSourceType
            )
            is TargetObject -> findObjectTargets(state, requirement, controllerId, sourceId, ignoreTargetingRestrictions, targetingSourceType, triggeringEntityId, pipelineContext)
            is TargetSpellOrPermanent -> findSpellOrPermanentTargets(
                state,
                requirement,
                controllerId,
                sourceId,
                ignoreTargetingRestrictions,
                targetingSourceType,
                triggeringEntityId,
                pipelineContext,
            )
            is TargetOther -> {
                // For TargetOther, find targets for the base requirement but exclude the source
                // (or, for "enchanted creature deals damage to any other target", the attached creature).
                val baseTargets = findLegalTargets(
                    state,
                    requirement.baseRequirement,
                    controllerId,
                    sourceId,
                    ignoreTargetingRestrictions,
                    targetingSourceType,
                    triggeringEntityId,
                    pipelineContext,
                    requireAuthoritativeContext,
                )
                val excludeId = requirement.excludeSourceId
                    ?: if (requirement.excludeAttachedCreature) {
                        sourceId?.let { state.getEntity(it)?.get<AttachedToComponent>()?.targetId }
                    } else {
                        sourceId
                    }
                if (excludeId != null) baseTargets.filter { it != excludeId } else baseTargets
            }
        }
    }

    private fun TargetRequirement.requiredPredicateContext(): RequiredPredicateContext = when (this) {
        is TargetObject -> filter.clauses().fold(RequiredPredicateContext()) { required, clause ->
            required + clause.baseFilter.requiredPredicateContext()
        }
        is TargetPermanentOrPlayer -> permanentFilter.clauses().fold(RequiredPredicateContext()) { required, clause ->
            required + clause.baseFilter.requiredPredicateContext()
        }
        is TargetSpellOrPermanent -> permanentFilter?.requiredPredicateContext() ?: RequiredPredicateContext()
        is TargetOther -> baseRequirement.requiredPredicateContext()
        else -> RequiredPredicateContext()
    }

    private fun GameObjectFilter.requiredPredicateContext(): RequiredPredicateContext =
        (controllerPredicate?.requiredPredicateContext() ?: RequiredPredicateContext()) +
            statePredicates.fold(RequiredPredicateContext()) { required, predicate ->
                required + predicate.requiredPredicateContext()
            } +
            cardPredicates.fold(RequiredPredicateContext()) { required, predicate ->
                required + predicate.requiredPredicateContext()
            } +
            anyOf.fold(RequiredPredicateContext()) { required, nested ->
                required + nested.requiredPredicateContext()
            }

    private fun ControllerPredicate.requiredPredicateContext(): RequiredPredicateContext = when (this) {
        ControllerPredicate.ControlledByYou,
        ControllerPredicate.ControlledByOpponent,
        ControllerPredicate.ControlledByAny,
        ControllerPredicate.ControlledByActivePlayer,
        ControllerPredicate.OwnedByYou,
        ControllerPredicate.OwnedByOpponent -> RequiredPredicateContext(controllerId = true)
        ControllerPredicate.ControlledByTargetOpponent -> RequiredPredicateContext(
            controllerId = true,
            targetOpponentId = true,
            pipeline = true,
        )
        ControllerPredicate.ControlledByTargetPlayer,
        ControllerPredicate.OwnedByTargetPlayer -> RequiredPredicateContext(
            controllerId = true,
            targetPlayerId = true,
            pipeline = true,
        )
        ControllerPredicate.ControlledByTriggeringPlayer,
        ControllerPredicate.OwnedByTriggeringPlayer -> RequiredPredicateContext(
            controllerId = true,
            triggeringPlayerId = true,
            pipeline = true,
        )
        is ControllerPredicate.ControlledByReferencedPlayer ->
            RequiredPredicateContext(controllerId = true, pipeline = true) +
                targetReferenceContext(target)
        is ControllerPredicate.And -> predicates.fold(RequiredPredicateContext()) { required, predicate ->
            required + predicate.requiredPredicateContext()
        }
        is ControllerPredicate.Or -> predicates.fold(RequiredPredicateContext()) { required, predicate ->
            required + predicate.requiredPredicateContext()
        }
        is ControllerPredicate.Not -> predicate.requiredPredicateContext()
    }

    private fun targetReferenceContext(target: EffectTarget): RequiredPredicateContext = when (target) {
        EffectTarget.Controller -> RequiredPredicateContext(controllerId = true)
        is EffectTarget.ContextTarget -> RequiredPredicateContext(
            pipeline = true,
            targetIndexes = setOf(target.index),
        )
        is EffectTarget.BoundVariable -> RequiredPredicateContext(
            pipeline = true,
            namedTargets = setOf(target.name),
        )
        EffectTarget.ControllerOfTriggeringEntity -> RequiredPredicateContext(
            pipeline = true,
            triggeringEntityId = true,
        )
        EffectTarget.TargetController -> RequiredPredicateContext(
            pipeline = true,
            controllerTargetIndexes = setOf(0),
        )
        is EffectTarget.PipelineTarget -> RequiredPredicateContext(
            pipeline = true,
            storedCollections = mapOf(target.collectionName to setOf(target.index)),
        )
        is EffectTarget.ControllerOfPipelineTarget -> RequiredPredicateContext(
            pipeline = true,
            storedCollections = mapOf(target.collectionName to setOf(target.index)),
        )
        is EffectTarget.PlayerRef -> when (target.player) {
            Player.You -> RequiredPredicateContext(controllerId = true)
            Player.TargetPlayer -> RequiredPredicateContext(targetPlayerId = true, pipeline = true)
            Player.TargetOpponent -> RequiredPredicateContext(targetOpponentId = true, pipeline = true)
            Player.TriggeringPlayer -> RequiredPredicateContext(triggeringPlayerId = true, pipeline = true)
            Player.DefendingPlayer -> RequiredPredicateContext(defendingPlayerId = true, pipeline = true)
            else -> RequiredPredicateContext(unsupported = true, pipeline = true)
        }
        else -> RequiredPredicateContext(unsupported = true, pipeline = true)
    }

    private fun StatePredicate.requiredPredicateContext(): RequiredPredicateContext = when (this) {
        StatePredicate.IsAttackingAnOpponent -> RequiredPredicateContext(controllerId = true)
        StatePredicate.InSameBandAsSource,
        StatePredicate.IsBlockingSource,
        StatePredicate.CreatedBySource,
        StatePredicate.NotTargetedByAbilityFromSameNamedSource,
        StatePredicate.CrewedOrSaddledSourceThisTurn,
        StatePredicate.CrewedOrSaddledBySourceThisTurn,
        StatePredicate.DealtCombatDamageToSourceControllerThisTurn,
        StatePredicate.ControllerDealtCombatDamageBySourceThisTurn,
        StatePredicate.IsSource,
        StatePredicate.IsAttachedToBySource,
        StatePredicate.IsAttachedToSource,
        StatePredicate.ExiledWithSource -> RequiredPredicateContext(sourceId = true)
        StatePredicate.IsGrantingPermanent -> RequiredPredicateContext(granterId = true)
        is StatePredicate.IsEnchantedByAura -> auraController.requiredPredicateContext()
        is StatePredicate.AttachedTo -> filter.requiredPredicateContext()
        is StatePredicate.Or -> predicates.fold(RequiredPredicateContext()) { required, predicate ->
            required + predicate.requiredPredicateContext()
        }
        is StatePredicate.And -> predicates.fold(RequiredPredicateContext()) { required, predicate ->
            required + predicate.requiredPredicateContext()
        }
        is StatePredicate.Not -> predicate.requiredPredicateContext()
        else -> RequiredPredicateContext()
    }

    private fun CardPredicate.requiredPredicateContext(): RequiredPredicateContext = when (this) {
        CardPredicate.ManaValueEqualsX,
        CardPredicate.ManaValueAtMostX,
        CardPredicate.PowerEqualsX,
        CardPredicate.PowerAtLeastX,
        CardPredicate.ToughnessAtMostX -> RequiredPredicateContext(
            xValue = true,
            pipeline = true,
        )
        is CardPredicate.ManaValueAtMostEntity -> reference.requiredPredicateContext()
        is CardPredicate.ManaValueAtMostEntityManaSpent -> reference.requiredPredicateContext()
        is CardPredicate.ManaValueAtMostColorsSpent -> reference.requiredPredicateContext()
        is CardPredicate.PowerGreaterThanEntity -> reference.requiredPredicateContext()
        is CardPredicate.PowerAtMostEntity -> reference.requiredPredicateContext()
        is CardPredicate.PowerLessThanEntity -> reference.requiredPredicateContext()
        is CardPredicate.ManaValueAtMostDynamic,
        is CardPredicate.ManaValueEqualsDynamic,
        is CardPredicate.PowerEqualsDynamic,
        is CardPredicate.ToughnessEqualsDynamic -> RequiredPredicateContext(pipeline = true)
        is CardPredicate.NameEqualsChosen -> RequiredPredicateContext(
            pipeline = true,
            chosenValues = setOf(variableName),
        )
        is CardPredicate.NameEqualsChosenComponent -> RequiredPredicateContext(
            sourceId = true,
            sourceTextChoiceSlots = setOf(slot),
        )
        is CardPredicate.CardTypeEqualsChosenComponent -> RequiredPredicateContext(
            sourceId = true,
            sourceTextChoiceSlots = setOf(slot),
        )
        is CardPredicate.HasSubtypeFromVariable -> RequiredPredicateContext(
            pipeline = true,
            chosenValues = setOf(variableName),
        )
        is CardPredicate.HasSubtypeInStoredList -> RequiredPredicateContext(
            pipeline = true,
            storedStringLists = setOf(listName),
        )
        is CardPredicate.HasSubtypeInEachStoredGroup -> RequiredPredicateContext(
            pipeline = true,
            storedSubtypeGroups = setOf(groupName),
        )
        CardPredicate.HasChosenColor -> RequiredPredicateContext(
            pipeline = true,
            chosenColor = true,
        )
        CardPredicate.NotOfSourceChosenType,
        CardPredicate.HasChosenSubtype -> RequiredPredicateContext(
            sourceId = true,
            sourceTextChoiceSlots = setOf(ChoiceSlot.CREATURE_TYPE),
        )
        CardPredicate.SharesCreatureTypeWithSource -> RequiredPredicateContext(sourceId = true)
        CardPredicate.SharesChosenColorWithSource -> RequiredPredicateContext(
            sourceId = true,
            sourceColorChoiceSlots = setOf(ChoiceSlot.COLOR),
        )
        CardPredicate.SharesCreatureTypeWithTriggeringEntity -> RequiredPredicateContext(
            triggeringEntityId = true,
        )
        CardPredicate.SharesColorWithRecipient -> RequiredPredicateContext(
            pipeline = true,
            recipientId = true,
        )
        is CardPredicate.SharesCreatureTypeWith -> entity.requiredPredicateContext()
        is CardPredicate.SharesColorWith -> entity.requiredPredicateContext()
        is CardPredicate.SharesColorWithPermanentYouControl ->
            RequiredPredicateContext(controllerId = true) + filter.requiredPredicateContext()
        is CardPredicate.SharesNameWithPermanentYouControl ->
            RequiredPredicateContext(controllerId = true) + filter.requiredPredicateContext()
        is CardPredicate.DoesNotShareCreatureTypeWithPermanentYouControl ->
            RequiredPredicateContext(controllerId = true) + filter.requiredPredicateContext()
        is CardPredicate.DoesNotShareLandTypeWithPermanentYouControl ->
            RequiredPredicateContext(controllerId = true) + filter.requiredPredicateContext()
        is CardPredicate.TargetsMatching -> subfilter.requiredPredicateContext()
        is CardPredicate.AbilitySourceMatches -> subfilter.requiredPredicateContext()
        is CardPredicate.And -> predicates.fold(RequiredPredicateContext()) { required, predicate ->
            required + predicate.requiredPredicateContext()
        }
        is CardPredicate.Or -> predicates.fold(RequiredPredicateContext()) { required, predicate ->
            required + predicate.requiredPredicateContext()
        }
        is CardPredicate.Not -> predicate.requiredPredicateContext()
        else -> RequiredPredicateContext()
    }

    private fun EntityReference.requiredPredicateContext(): RequiredPredicateContext = when (this) {
        EntityReference.Source -> RequiredPredicateContext(sourceId = true)
        EntityReference.Triggering -> RequiredPredicateContext(triggeringEntityId = true)
        EntityReference.DamageSource -> RequiredPredicateContext(
            pipeline = true,
            damageSourceId = true,
        )
        EntityReference.DamageRecipient -> RequiredPredicateContext(
            pipeline = true,
            damageRecipientId = true,
        )
        EntityReference.AffectedEntity -> RequiredPredicateContext(
            pipeline = true,
            affectedEntityId = true,
        )
        is EntityReference.FromCostStorage -> RequiredPredicateContext(
            pipeline = true,
            storedCollections = mapOf(collectionName to setOf(index)),
        )
        EntityReference.AmassedArmy -> RequiredPredicateContext(
            pipeline = true,
            storedCollections = mapOf(EntityReference.AmassedArmy.STORAGE_KEY to setOf(0)),
        )
        else -> RequiredPredicateContext(unsupported = true, pipeline = true)
    }

    private fun findPlayerTargets(
        state: GameState,
        requirement: TargetPlayer,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false
    ): List<EntityId> {
        return state.turnOrder.filter { playerId ->
            state.hasEntity(playerId) &&
                (ignoreTargetingRestrictions || (!playerHasShroud(state, playerId) &&
                    !playerHasHexproofAgainst(state, playerId, controllerId))) &&
                PlayerTargetRestriction.isSatisfied(state, requirement.restriction, playerId, controllerId, sourceId)
        }
    }

    private fun findOpponentTargets(
        state: GameState,
        requirement: TargetOpponent,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false
    ): List<EntityId> {
        return state.turnOrder.filter { it != controllerId && state.hasEntity(it) &&
            (ignoreTargetingRestrictions || (!playerHasShroud(state, it) && !playerHasHexproof(state, it))) &&
            PlayerTargetRestriction.isSatisfied(state, requirement.restriction, it, controllerId, sourceId) }
    }

    /**
     * Check if a permanent is restricted from being targeted by the given source type.
     * Checks for CantBeTargetedByOpponentAbilitiesComponent — which blocks opponent abilities
     * but not opponent spells.
     */
    private fun hasCantBeTargetedRestriction(
        state: GameState,
        entityId: EntityId,
        entityController: EntityId?,
        controllerId: EntityId,
        targetingSourceType: TargetingSourceType,
        sourceId: EntityId? = null
    ): Boolean {
        // Source-card-type restriction (Artifact Ward) is checked first because, unlike the
        // opponent-ability restriction, it is NOT controller-gated: a matching source can't target
        // the warded creature even if the same player controls both. It still only blocks abilities
        // (not spells); the helper handles the spell/unknown-source short-circuit.
        if (SourceTypeTargeting.cantBeTargetedBySourceTypeAbility(state, entityId, sourceId, targetingSourceType)) {
            return true
        }

        if (entityController == controllerId) return false  // own permanents are never restricted
        if (targetingSourceType == TargetingSourceType.SPELL) return false  // spells bypass this restriction

        // For ABILITY source type, always blocked. For ANY (unknown), conservatively block since
        // we don't know the source type. Read through ControllerGrants so a gated form of the
        // ability switches off with its condition instead of sticking on.
        return ControllerGrants.isActiveOn<CantBeTargetedByOpponentAbilitiesComponent>(state, entityId)
    }

    private fun findOpponentOrPlaneswalkerTargets(
        state: GameState,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY
    ): List<EntityId> {
        val projected = state.projectedState
        val targets = mutableListOf<EntityId>()

        // Add opponents (excluding those with shroud or hexproof)
        targets.addAll(state.turnOrder.filter { it != controllerId && state.hasEntity(it) &&
            (ignoreTargetingRestrictions || (!playerHasShroud(state, it) && !playerHasHexproof(state, it))) })

        // Add all planeswalkers on the battlefield
        val battlefield = state.getBattlefield()
        for (entityId in battlefield) {
            val container = state.getEntity(entityId) ?: continue
            if (!container.has<CardComponent>()) continue
            val entityController = container.get<ControllerComponent>()?.playerId

            // Read the PROJECTED type line, not the printed one, so a permanent that
            // becomes a planeswalker via a continuous effect is offered (CR 115.4 /
            // projection rule), consistent with findAnyTargets.
            if (!projected.isPlaneswalker(entityId)) continue

            if (!ignoreTargetingRestrictions) {
                // Check hexproof/shroud
                if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != controllerId) continue
                if (projected.hasKeyword(entityId, Keyword.SHROUD)) continue
                // Check hexproof from color
                if (hasHexproofFromSource(state, projected, entityId, entityController, controllerId, sourceId)) continue
                // Check can't-be-targeted-by-abilities
                if (hasCantBeTargetedRestriction(state, entityId, entityController, controllerId, targetingSourceType, sourceId)) continue
            }

            targets.add(entityId)
        }

        return targets
    }

    private fun findPlayerOrPlaneswalkerTargets(
        state: GameState,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY
    ): List<EntityId> {
        val projected = state.projectedState
        val targets = mutableListOf<EntityId>()

        // Add all players (excluding those with shroud or hexproof from opponents)
        targets.addAll(state.turnOrder.filter { state.hasEntity(it) &&
            (ignoreTargetingRestrictions || (!playerHasShroud(state, it) &&
                !playerHasHexproofAgainst(state, it, controllerId))) })

        // Add all planeswalkers on the battlefield
        val battlefield = state.getBattlefield()
        for (entityId in battlefield) {
            val container = state.getEntity(entityId) ?: continue
            if (!container.has<CardComponent>()) continue
            val entityController = container.get<ControllerComponent>()?.playerId

            // Read the PROJECTED type line, not the printed one, so a permanent that
            // becomes a planeswalker via a continuous effect is offered (CR 115.4 /
            // projection rule), consistent with findAnyTargets.
            if (!projected.isPlaneswalker(entityId)) continue

            if (!ignoreTargetingRestrictions) {
                // Check hexproof/shroud
                if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != controllerId) continue
                if (projected.hasKeyword(entityId, Keyword.SHROUD)) continue
                // Check hexproof from color
                if (hasHexproofFromSource(state, projected, entityId, entityController, controllerId, sourceId)) continue
                // Check can't-be-targeted-by-abilities
                if (hasCantBeTargetedRestriction(state, entityId, entityController, controllerId, targetingSourceType, sourceId)) continue
            }

            targets.add(entityId)
        }

        return targets
    }

    private fun findPermanentTargets(
        state: GameState,
        requirement: TargetObject,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null
    ): List<EntityId> {
        val projected = state.projectedState
        val battlefield = state.getBattlefield()
        val filter = requirement.filter

        return battlefield.filter { entityId ->
            // Exclude self if filter says "other"
            if (filter.excludeSelf && entityId == sourceId) {
                return@filter false
            }
            // Exclude the trigger's triggering entity (e.g., "other than that creature"
            // for Pawpatch-style triggers where "that creature" is the targeted permanent).
            if (filter.excludeTriggeringEntity && triggeringEntityId != null && entityId == triggeringEntityId) {
                return@filter false
            }

            val container = state.getEntity(entityId) ?: return@filter false
            container.get<CardComponent>() ?: return@filter false
            val entityController = container.get<ControllerComponent>()?.playerId

            if (!ignoreTargetingRestrictions) {
                // Check hexproof/shroud
                if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != controllerId) {
                    return@filter false
                }
                if (projected.hasKeyword(entityId, Keyword.SHROUD)) {
                    return@filter false
                }
                // Check hexproof from color
                if (hasHexproofFromSource(state, projected, entityId, entityController, controllerId, sourceId)) {
                    return@filter false
                }
                // Check can't-be-targeted-by-abilities
                if (hasCantBeTargetedRestriction(state, entityId, entityController, controllerId, targetingSourceType, sourceId)) {
                    return@filter false
                }
            }

            // Use unified filter with projected state
            val predicateContext = targetingContext(controllerId, sourceId, triggeringEntityId = triggeringEntityId, pipelineContext = pipelineContext)
            predicateEvaluator.matches(state, projected, entityId, filter.baseFilter, predicateContext)
        }
    }

    private fun findAnyTargets(
        state: GameState,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY
    ): List<EntityId> {
        val projected = state.projectedState
        val targets = mutableListOf<EntityId>()

        // Add all players (excluding those with shroud or hexproof from opponents)
        targets.addAll(state.turnOrder.filter { state.hasEntity(it) &&
            (ignoreTargetingRestrictions || (!playerHasShroud(state, it) &&
                !playerHasHexproofAgainst(state, it, controllerId))) })

        // Add all creatures, planeswalkers and battles
        val battlefield = state.getBattlefield()
        for (entityId in battlefield) {
            val container = state.getEntity(entityId) ?: continue
            if (!container.has<CardComponent>()) continue
            val entityController = container.get<ControllerComponent>()?.playerId

            // CR 115.4 — "any target" means a creature, player, planeswalker, or battle, and
            // nothing else. Read the PROJECTED type line, not the printed one, so animated lands
            // (Earthbend) and face-down 2/2 creatures are valid targets (projection rule).
            if (!projected.isCreature(entityId) &&
                !projected.isPlaneswalker(entityId) &&
                !projected.isBattle(entityId)
            ) {
                continue
            }

            if (!ignoreTargetingRestrictions) {
                // Check hexproof/shroud
                if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != controllerId) {
                    continue
                }
                if (projected.hasKeyword(entityId, Keyword.SHROUD)) {
                    continue
                }
                // Check hexproof from color
                if (hasHexproofFromSource(state, projected, entityId, entityController, controllerId, sourceId)) {
                    continue
                }
                // Check can't-be-targeted-by-abilities
                if (hasCantBeTargetedRestriction(state, entityId, entityController, controllerId, targetingSourceType, sourceId)) {
                    continue
                }
            }

            targets.add(entityId)
        }

        return targets
    }

    private fun findCreatureOrPlayerTargets(
        state: GameState,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        pipelineContext: PredicateContext? = null
    ): List<EntityId> {
        val targets = mutableListOf<EntityId>()

        // Add all players (excluding those with shroud or hexproof from opponents)
        targets.addAll(state.turnOrder.filter { state.hasEntity(it) &&
            (ignoreTargetingRestrictions || (!playerHasShroud(state, it) &&
                !playerHasHexproofAgainst(state, it, controllerId))) })

        // Add all creatures
        targets.addAll(
            findPermanentTargets(
                state,
                TargetCreature(),
                controllerId,
                sourceId,
                ignoreTargetingRestrictions = ignoreTargetingRestrictions,
                targetingSourceType = targetingSourceType,
                pipelineContext = pipelineContext
            )
        )

        return targets
    }

    /**
     * "Target permanent or player" — every player that can be targeted, plus every battlefield
     * permanent matching the requirement's `permanentFilter` (default: any permanent). Both halves
     * reuse the same legality checks as their single-kind counterparts.
     */
    private fun findPermanentOrPlayerTargets(
        state: GameState,
        requirement: TargetPermanentOrPlayer,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null
    ): List<EntityId> {
        val targets = mutableListOf<EntityId>()

        // Add all players (excluding those with shroud or hexproof from opponents)
        targets.addAll(state.turnOrder.filter { state.hasEntity(it) &&
            (ignoreTargetingRestrictions || (!playerHasShroud(state, it) &&
                !playerHasHexproofAgainst(state, it, controllerId))) })

        // Add all permanents matching the filter
        targets.addAll(
            findPermanentTargets(
                state,
                TargetObject(filter = requirement.permanentFilter),
                controllerId,
                sourceId,
                ignoreTargetingRestrictions = ignoreTargetingRestrictions,
                targetingSourceType = targetingSourceType,
                triggeringEntityId = triggeringEntityId,
                pipelineContext = pipelineContext
            )
        )

        return targets
    }

    private fun findCreatureOrPlaneswalkerTargets(
        state: GameState,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY
    ): List<EntityId> {
        val projected = state.projectedState
        val battlefield = state.getBattlefield()

        return battlefield.filter { entityId ->
            val container = state.getEntity(entityId) ?: return@filter false
            if (!container.has<CardComponent>()) return@filter false
            val entityController = container.get<ControllerComponent>()?.playerId

            // Must be creature or planeswalker. Read the PROJECTED type line, not the
            // printed one, so animated lands (Earthbend) and face-down 2/2 creatures
            // are valid targets (projection rule, see CR 115.4).
            if (!projected.isCreature(entityId) && !projected.isPlaneswalker(entityId)) {
                return@filter false
            }

            if (!ignoreTargetingRestrictions) {
                // Check hexproof/shroud
                if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != controllerId) {
                    return@filter false
                }
                if (projected.hasKeyword(entityId, Keyword.SHROUD)) {
                    return@filter false
                }
                // Check hexproof from color
                if (hasHexproofFromSource(state, projected, entityId, entityController, controllerId, sourceId)) {
                    return@filter false
                }
                // Check can't-be-targeted-by-abilities
                if (hasCantBeTargetedRestriction(state, entityId, entityController, controllerId, targetingSourceType, sourceId)) {
                    return@filter false
                }
            }

            true
        }
    }

    private fun findGraveyardTargets(
        state: GameState,
        filter: TargetFilter,
        controllerId: EntityId,
        sourceId: EntityId?,
        pipelineContext: PredicateContext? = null
    ): List<EntityId> {
        val targets = mutableListOf<EntityId>()

        // Check all graveyards - the unified filter's OwnedByYou predicate handles "your graveyard" restriction
        for (playerId in state.turnOrder) {
            val graveyardKey = ZoneKey(playerId, Zone.GRAVEYARD)
            val graveyard = state.getZone(graveyardKey)

            for (cardId in graveyard) {
                if (filter.excludeSelf && cardId == sourceId) continue
                val predicateContext = targetingContext(controllerId, sourceId, ownerId = playerId, pipelineContext = pipelineContext)
                if (predicateEvaluator.matches(state, state.projectedState, cardId, filter.baseFilter, predicateContext)) {
                    targets.add(cardId)
                }
            }
        }

        return targets
    }

    private fun findSpellTargets(
        state: GameState,
        requirement: TargetObject,
        controllerId: EntityId,
        sourceId: EntityId? = null,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null,
    ): List<EntityId> {
        val filter = requirement.filter
        // Whether this requirement is allowed to target *abilities* on the stack, not just spells.
        // "Target spell" (the common case, base filter `Any`) must never reach an ability — a spell
        // is a card on the stack (CR 112.1) while an ability on the stack is a separate object kind
        // (CR 113.3b/c, 113.7a). So an ability entity is offered only when the filter *explicitly*
        // names an ability predicate (Stifle's "counter target ability", Willbender's "spell or
        // ability", Return the Favor's "spell or ability"). For spells the predicate decides as
        // before. This is the single seam where both spells and abilities become legal targets.
        val abilitiesAllowed = StackObjectTargeting.permitsAbilities(filter.baseFilter)
        return state.stack.filter { stackId ->
            val isAbility = !state.isSpellOnStack(stackId)
            if (isAbility && !abilitiesAllowed) return@filter false
            val predicateContext = targetingContext(
                controllerId = controllerId,
                sourceId = sourceId,
                triggeringEntityId = triggeringEntityId,
                pipelineContext = pipelineContext,
            )
            predicateEvaluator.matches(state, state.projectedState, stackId, filter.baseFilter, predicateContext)
        }
    }

    /**
     * Find targets for TargetObject, dispatching based on the filter's zone.
     */
    private fun findObjectTargets(
        state: GameState,
        requirement: TargetObject,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null
    ): List<EntityId> {
        val filter = requirement.filter
        // Cross-zone union ("from your graveyard or exiled card with flashback"): the legal set is
        // the union over each single-zone clause. Recurse per clause (each has no alternatives, so
        // this terminates) and dedupe — a single object can't legally match two clauses anyway, but
        // distinct() guards against overlapping filters.
        if (filter.isUnion) {
            return filter.clauses().flatMap { clause ->
                findObjectTargets(state, requirement.copy(filter = clause), controllerId, sourceId, ignoreTargetingRestrictions, targetingSourceType, triggeringEntityId, pipelineContext)
            }.distinct()
        }
        return when (filter.zone) {
            Zone.BATTLEFIELD -> findPermanentTargets(state, requirement, controllerId, sourceId, ignoreTargetingRestrictions, targetingSourceType, triggeringEntityId, pipelineContext)
            Zone.GRAVEYARD -> findGraveyardTargets(state, filter, controllerId, sourceId, pipelineContext)
            Zone.STACK -> findSpellTargets(
                state = state,
                requirement = requirement,
                controllerId = controllerId,
                sourceId = sourceId,
                triggeringEntityId = triggeringEntityId,
                pipelineContext = pipelineContext,
            )
            else -> findCardTargetsInZone(
                state = state,
                filter = filter,
                controllerId = controllerId,
                sourceId = sourceId,
                triggeringEntityId = triggeringEntityId,
                pipelineContext = pipelineContext,
            )
        }
    }

    /**
     * Find targets that are either permanents on the battlefield or spells on the stack.
     * Used by Artificial Evolution's "target spell or permanent" requirement.
     */
    private fun findSpellOrPermanentTargets(
        state: GameState,
        requirement: TargetSpellOrPermanent,
        controllerId: EntityId,
        sourceId: EntityId?,
        ignoreTargetingRestrictions: Boolean = false,
        targetingSourceType: TargetingSourceType = TargetingSourceType.ANY,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null,
    ): List<EntityId> {
        val projected = state.projectedState
        val targets = mutableListOf<EntityId>()
        val permanentFilter = requirement.permanentFilter

        // Add all permanents on the battlefield matching the optional filter
        for (entityId in state.getBattlefield()) {
            val container = state.getEntity(entityId) ?: continue
            container.get<CardComponent>() ?: continue
            val entityController = container.get<ControllerComponent>()?.playerId

            if (!ignoreTargetingRestrictions) {
                if (projected.hasKeyword(entityId, Keyword.HEXPROOF) && entityController != controllerId) continue
                if (projected.hasKeyword(entityId, Keyword.SHROUD)) continue
                // Check hexproof from color
                if (hasHexproofFromSource(state, projected, entityId, entityController, controllerId, sourceId)) continue
                if (hasCantBeTargetedRestriction(state, entityId, entityController, controllerId, targetingSourceType, sourceId)) continue
            }

            if (permanentFilter != null &&
                !predicateEvaluator.matches(
                    state,
                    projected,
                    entityId,
                    permanentFilter,
                    targetingContext(
                        controllerId = controllerId,
                        sourceId = sourceId,
                        triggeringEntityId = triggeringEntityId,
                        pipelineContext = pipelineContext,
                    ),
                )
            ) continue

            targets.add(entityId)
        }

        // Add all spells on the stack — only actual spells (CR 112.1), never abilities
        // on the stack (CR 113.3b/c, 113.7a), consistent with findSpellTargets above.
        targets.addAll(state.stack.filter { spellId -> state.isSpellOnStack(spellId) })

        return targets
    }

    /**
     * Check if a player has shroud (e.g., from True Believer's "You have shroud"
     * or Gilded Light's "You gain shroud until end of turn").
     */
    private fun playerHasShroud(state: GameState, playerId: EntityId): Boolean =
        ControllerShroud.appliesTo(state, playerId)

    /**
     * Check if a player has hexproof (from a permanent like Shalai, Voice of Plenty).
     * Unlike shroud, hexproof only prevents opponents from targeting — the player can still
     * target themselves.
     */
    private fun playerHasHexproof(state: GameState, playerId: EntityId): Boolean =
        ControllerHexproof.appliesTo(state, playerId)

    /**
     * Check if a player has hexproof against a specific controller.
     * Returns true if the player has hexproof AND the controller is an opponent.
     */
    private fun playerHasHexproofAgainst(state: GameState, playerId: EntityId, controllerId: EntityId): Boolean {
        return playerId != controllerId && playerHasHexproof(state, playerId)
    }

    /**
     * Check if a permanent has "hexproof from [quality]" matching the targeting source — either one
     * of its colors ("hexproof from white") or one of its card types ("hexproof from instants").
     * Rule 702.11b: opponents can't target it with spells/abilities of that quality.
     *
     * Gets the source's colors/types from projected state (for battlefield permanents) and falls
     * back to the base [CardComponent] (for spells in hand/on the stack, which aren't projected).
     *
     * @return true if the entity is protected by hexproof-from against the source
     */
    private fun hasHexproofFromSource(
        state: GameState,
        projected: ProjectedState,
        entityId: EntityId,
        entityController: EntityId?,
        controllerId: EntityId,
        sourceId: EntityId?
    ): Boolean {
        if (entityController == controllerId || sourceId == null) return false
        // Try projected colors first (for permanents on the battlefield),
        // then fall back to base CardComponent colors (for spells in hand/on stack)
        var sourceColors = projected.getColors(sourceId)
        if (sourceColors.isEmpty()) {
            sourceColors = state.getEntity(sourceId)?.get<CardComponent>()
                ?.colors?.map { it.name }?.toSet() ?: emptySet()
        }
        if (sourceColors.any { colorName -> projected.hasKeyword(entityId, "HEXPROOF_FROM_$colorName") }) {
            return true
        }
        // Hexproof from monocolored: a source with exactly one color can't target (CR 105.2).
        if (sourceColors.size == 1 && projected.hasKeyword(entityId, "HEXPROOF_FROM_MONOCOLORED")) {
            return true
        }
        return SourceTypeTargeting.sourceCardTypes(state, sourceId).any { cardType ->
            projected.hasKeyword(entityId, "HEXPROOF_FROM_CARDTYPE_${cardType.uppercase()}")
        }
    }

    /**
     * Find card targets in non-battlefield, non-stack zones (hand, library, exile, command).
     */
    private fun findCardTargetsInZone(
        state: GameState,
        filter: TargetFilter,
        controllerId: EntityId,
        sourceId: EntityId? = null,
        triggeringEntityId: EntityId? = null,
        pipelineContext: PredicateContext? = null,
    ): List<EntityId> {
        val zoneType = filter.zone
        val targets = mutableListOf<EntityId>()

        for (playerId in state.turnOrder) {
            val zoneKey = ZoneKey(playerId, zoneType)
            val zone = state.getZone(zoneKey)

            for (cardId in zone) {
                val predicateContext = targetingContext(
                    controllerId = controllerId,
                    sourceId = sourceId,
                    ownerId = playerId,
                    triggeringEntityId = triggeringEntityId,
                    pipelineContext = pipelineContext,
                )
                if (predicateEvaluator.matches(state, state.projectedState, cardId, filter.baseFilter, predicateContext)) {
                    targets.add(cardId)
                }
            }
        }

        return targets
    }
}
