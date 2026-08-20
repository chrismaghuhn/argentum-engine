package com.wingedsheep.engine.mechanics

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RoomFaceStatics
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.CompositeStaticAbility
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantKeywordAbility
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.StaticAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.Scope

/**
 * One effective keyword ability and the stable origin key that identifies that instance in an
 * action payload. The key is derived only from serialized game state (source entity, ability
 * position, and ability value), so replay and immutable-state forks see the same identity.
 */
internal data class EffectiveKeywordAbility(
    val ability: KeywordAbility,
    val key: String
)

internal data class EffectiveCrewAbility(
    val ability: KeywordAbility.Numeric,
    val key: String
)

/**
 * Resolves keyword abilities from the same three channels used by the engine's other grant
 * readers: printed abilities, direct runtime grants, and static abilities that grant a quoted
 * parameterized ability to a group.
 *
 * Parameterized abilities are deliberately resolved at point of use. Projected keyword names are
 * sufficient for display and simple keyword checks, but they cannot retain values such as Crew 1
 * versus Crew 3. The resolver therefore reuses projected state only for the static filter and
 * leaves the actual parameterized instance intact.
 */
internal object EffectiveKeywordAbilityResolver {

    private val predicateEvaluator = PredicateEvaluator()
    private val conditionEvaluator = ConditionEvaluator()

    fun effectiveCrewAbilities(
        state: GameState,
        cardRegistry: CardRegistry,
        targetId: EntityId
    ): List<EffectiveCrewAbility> = resolve(state, cardRegistry, targetId)
        .mapNotNull { effective ->
            val ability = effective.ability as? KeywordAbility.Numeric ?: return@mapNotNull null
            if (ability.keyword != Keyword.CREW) return@mapNotNull null
            EffectiveCrewAbility(ability = ability, key = effective.key)
        }

    fun resolve(
        state: GameState,
        cardRegistry: CardRegistry,
        targetId: EntityId
    ): List<EffectiveKeywordAbility> {
        val result = mutableListOf<EffectiveKeywordAbility>()
        val targetContainer = state.getEntity(targetId) ?: return emptyList()
        val targetCard = targetContainer.get<CardComponent>()

        // Printed keyword abilities keep their serialized list position as their origin. This
        // avoids choosing between two identical parameterized abilities by value alone.
        val targetDefinition = targetCard?.let { cardRegistry.getCard(it.cardDefinitionId) }
        targetDefinition?.keywordAbilities
            ?.withIndex()
            ?.forEach { (index, ability) ->
                result += EffectiveKeywordAbility(
                    ability = ability,
                    key = "printed:$index:${abilityKey(ability)}"
                )
            }

        // Direct grants are already keyed to the target entity. Identical active grants have the
        // same point-of-use meaning, so collapse them to one deterministic instance instead of
        // exposing duplicate indistinguishable actions.
        state.grantedKeywordAbilities
            .asSequence()
            .filter { it.entityId == targetId }
            .map { grant ->
                EffectiveKeywordAbility(
                    ability = grant.ability,
                    key = "runtime:${abilityKey(grant.ability)}"
                )
            }
            .forEach(result::add)

        // Printed static grants are read from the active static list, including class levels and
        // unlocked Room faces. The source order and static-list index are both serialized-state
        // order, so this remains stable across replay and fork.
        for (sourceId in state.getBattlefield()) {
            val sourceContainer = state.getEntity(sourceId) ?: continue
            if (sourceContainer.has<FaceDownComponent>()) continue
            val sourceCard = sourceContainer.get<CardComponent>() ?: continue
            val sourceDefinition = cardRegistry.getCard(sourceCard.cardDefinitionId) ?: continue
            val sourceController = state.projectedState.getController(sourceId) ?: continue
            val statics = RoomFaceStatics.activeStaticAbilities(sourceContainer, sourceDefinition)
            for ((index, staticAbility) in statics.withIndex()) {
                collectStaticGrant(
                    state = state,
                    sourceId = sourceId,
                    sourceController = sourceController,
                    ability = staticAbility,
                    origin = "static:${sourceId.value}:$index",
                    targetId = targetId,
                    result = result
                )
            }
        }

        // A GrantStaticAbilityEffect records the quoted static ability in the durable runtime
        // channel. Treat its holder as the static source, mirroring printed static abilities.
        for ((index, grant) in state.grantedStaticAbilities.withIndex()) {
            val sourceId = grant.entityId
            if (sourceId !in state.getBattlefield()) continue
            val sourceController = state.projectedState.getController(sourceId) ?: continue
            collectStaticGrant(
                state = state,
                sourceId = sourceId,
                sourceController = sourceController,
                ability = grant.ability,
                origin = "runtime-static:${sourceId.value}:$index",
                targetId = targetId,
                result = result
            )
        }

        return result
            .distinctBy { it.key }
            .sortedBy { it.key }
    }

    private fun collectStaticGrant(
        state: GameState,
        sourceId: EntityId,
        sourceController: EntityId,
        ability: StaticAbility,
        origin: String,
        targetId: EntityId,
        result: MutableList<EffectiveKeywordAbility>
    ) {
        when (ability) {
            is GrantKeywordAbility -> {
                if (matches(state, sourceId, sourceController, targetId, ability.filter)) {
                    result += EffectiveKeywordAbility(
                        ability = ability.ability,
                        key = "$origin:${abilityKey(ability.ability)}"
                    )
                }
            }
            is ConditionalStaticAbility -> {
                val context = EffectContext(
                    sourceId = sourceId,
                    controllerId = sourceController
                )
                if (conditionEvaluator.evaluate(state, ability.condition, context)) {
                    collectStaticGrant(
                        state = state,
                        sourceId = sourceId,
                        sourceController = sourceController,
                        ability = ability.ability,
                        origin = "$origin:conditional",
                        targetId = targetId,
                        result = result
                    )
                }
            }
            is CompositeStaticAbility -> {
                for ((index, nested) in ability.abilities.withIndex()) {
                    collectStaticGrant(
                        state = state,
                        sourceId = sourceId,
                        sourceController = sourceController,
                        ability = nested,
                        origin = "$origin:composite:$index",
                        targetId = targetId,
                        result = result
                    )
                }
            }
            else -> Unit
        }
    }

    private fun matches(
        state: GameState,
        sourceId: EntityId,
        sourceController: EntityId,
        targetId: EntityId,
        filter: GroupFilter
    ): Boolean {
        if (filter.chosenSubtypeKey != null || filter.excludeTarget) return false
        if (targetId !in state.getBattlefield()) return false

        val scope = filter.scope
        return when (scope) {
            Scope.Battlefield -> {
                if (filter.excludeSelf && targetId == sourceId) return false
                predicateEvaluator.matches(
                    state = state,
                    projected = state.projectedState,
                    entityId = targetId,
                    filter = filter.baseFilter,
                    context = PredicateContext(
                        controllerId = sourceController,
                        sourceId = sourceId
                    )
                )
            }
            Scope.Self -> targetId == sourceId
            Scope.AttachedTo ->
                state.getEntity(sourceId)?.get<AttachedToComponent>()?.targetId == targetId
            Scope.SoulbondPair -> SoulbondPairing.isInPairOf(state, sourceId, targetId)
            is Scope.Specific -> targetId == scope.entityId
        }
    }

    private fun abilityKey(ability: KeywordAbility): String = when (ability) {
        is KeywordAbility.Numeric ->
            "numeric:${ability.keyword.name}:${ability.n}:${ability.onceEachTurn}"
        else -> "${ability::class.qualifiedName}:${ability.description}"
    }
}
