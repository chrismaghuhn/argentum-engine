package com.wingedsheep.engine.event

import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.TriggeredAbility

/**
 * Private, deterministic identity used only to normalize an unordered trigger-choice domain.
 *
 * This deliberately does not use EntityId, AbilityId, delayed-trigger IDs, UUIDs, map iteration,
 * or object toString output. Equal keys mean that the two occurrences have the same semantic
 * payload available to this choice boundary, so retaining either internal pairing is equivalent;
 * the ordinal handles exposed to the actor remain the small, opaque decision-domain tokens.
 */
internal object TriggerOrderingKey {

    fun forTrigger(state: GameState, trigger: PendingTrigger): String = fields(
        "source", semanticEntityKey(state, trigger.sourceId), trigger.sourceName,
        "ability", abilityKey(trigger.ability),
        "controller-stage", trigger.placementStage.name,
        "trigger-stage", trigger.stage.name,
        "granter", semanticEntityKey(state, trigger.granterId),
        "context", contextKey(state, trigger.triggerContext),
        "delayed", trigger.consumesDelayedTriggerId != null,
        "saga", trigger.sagaChapterInfo?.let { fields(it.chapterNumber, it.finalChapterNumber) },
        "pipeline", pipelineKey(state, trigger.carriedPipeline),
        "occurrences", trigger.occurrenceChoice.joinToString("\u0002") {
            occurrenceKey(state, it)
        },
    )

    private fun occurrenceKey(
        state: GameState,
        candidate: DelayedTriggerOccurrenceCandidate,
    ): String = fields(
        candidate.sourceName,
        semanticEntityKey(state, candidate.sourceId),
        abilityKey(candidate.ability),
        candidate.stage.name,
        candidate.observedPlacementStage?.name ?: "<legacy>",
        contextKey(state, candidate.triggerContext),
        semanticEntityKey(state, candidate.granterId),
        candidate.consumesDelayedTriggerId != null,
        candidate.sagaChapterInfo?.let { fields(it.chapterNumber, it.finalChapterNumber) },
        pipelineKey(state, candidate.carriedPipeline),
    )

    private fun abilityKey(ability: TriggeredAbility): String = fields(
        ability.trigger.description,
        ability.binding.name,
        ability.effect.description,
        ability.targetRequirement?.description,
        ability.additionalTargetRequirements.map { it.description },
        ability.elseEffect?.description,
        ability.activeZones.map { it.name }.sorted(),
        ability.interveningIf?.description,
        ability.triggerRestriction?.description,
        ability.controlledByTriggeringEntityController,
        ability.oncePerTurn,
        ability.effectOncePerTurn,
        ability.triggersOnce,
        ability.description,
    )

    private fun contextKey(state: GameState, context: TriggerContext): String = fields(
        semanticEntityKey(state, context.triggeringEntityId),
        semanticEntityKey(state, context.triggeringPlayerId),
        context.damageAmount,
        context.step?.name,
        context.xValue,
        context.counterCount,
        context.totalCounterCount,
        context.minusOneMinusOneCounterCount,
        semanticEntityKey(state, context.targetingSourceEntityId),
        context.lastKnownPower,
        context.lastKnownToughness,
        context.diedBatchTotalPower,
        context.lastKnownSubtypes?.sorted(),
        context.lastKnownCardTypes?.sorted(),
        context.lastKnownCounters?.entries
            ?.sortedBy { it.key }
            ?.map { listOf(it.key, it.value) },
        context.lastKnownDamageDealtByPlayers?.entries
            ?.sortedBy { semanticEntityKey(state, it.key) }
            ?.map { listOf(semanticEntityKey(state, it.key), it.value) },
        context.lastKnownBlockingOrBlockedByIds?.map { semanticEntityKey(state, it) },
        context.modesChosenCount,
        context.manaSpentOnTriggeringSpell,
        context.colorsSpentOnTriggeringSpell,
        context.manaValueOfTriggeringSpell,
        context.xValueOfTriggeringSpell,
        context.enchantedCreatureLastKnownPower,
        context.scryCount,
        context.discardedCardCount,
        context.discoverValue,
        context.excessDamageAmount,
        context.recipientToughnessAtDamage,
        context.capturedEntityIds?.map { semanticEntityKey(state, it) },
        semanticEntityKey(state, context.attachedToEntityId),
        semanticEntityKey(state, context.unattachedFromEntityId),
    )

    private fun pipelineKey(state: GameState, pipeline: PipelineState?): String = pipeline?.let {
        fields(
            it.storedCollections.entries.sortedBy { entry -> entry.key }.map { entry ->
                listOf(entry.key, entry.value.map { id -> semanticEntityKey(state, id) })
            },
            it.namedTargets.entries.sortedBy { entry -> entry.key }.map { entry ->
                listOf(entry.key, chosenTargetKey(state, entry.value))
            },
            it.chosenValues.entries.sortedBy { entry -> entry.key }.map { listOf(it.key, it.value) },
            it.storedNumbers.entries.sortedBy { entry -> entry.key }.map { listOf(it.key, it.value) },
            it.storedStringLists.entries.sortedBy { entry -> entry.key }
                .map { listOf(it.key, it.value) },
            it.storedSubtypeGroups.entries.sortedBy { entry -> entry.key }.map { entry ->
                listOf(entry.key, entry.value.map { group -> group.sorted() })
            },
            semanticEntityKey(state, it.iterationTarget),
        )
    } ?: "<none>"

    private fun chosenTargetKey(state: GameState, target: ChosenTarget): String = when (target) {
        is ChosenTarget.Player -> fields("player", semanticEntityKey(state, target.playerId))
        is ChosenTarget.Permanent -> fields("permanent", semanticEntityKey(state, target.entityId))
        is ChosenTarget.Card -> fields(
            "card", semanticEntityKey(state, target.cardId),
            semanticEntityKey(state, target.ownerId), target.zone.name,
        )
        is ChosenTarget.Spell -> fields("spell", semanticEntityKey(state, target.spellEntityId))
    }

    /**
     * The state-relative description is enough to distinguish semantic roles without making the
     * routing identity depend on an allocation-order handle. Players use their turn-order role;
     * cards use definition/visible characteristics, zone role, and projected combat state.
     */
    private fun semanticEntityKey(
        state: GameState,
        entityId: EntityId?,
        visited: Set<EntityId> = emptySet(),
    ): String {
        if (entityId == null) return "<none>"
        val playerRole = state.turnOrder.indexOf(entityId)
        if (playerRole >= 0) return "player-role:$playerRole"
        if (entityId in visited) return "entity-cycle"

        val entity = state.getEntity(entityId)
        val card = entity?.get<CardComponent>()
            ?: return "entity-without-card"
        val nextVisited = visited + entityId
        val zoneRoles = state.zones.entries
            .filter { (_, contents) -> entityId in contents }
            .map { (key, _) ->
                val ownerRole = state.turnOrder.indexOf(key.ownerId)
                "${key.zoneType.name}:owner-role:$ownerRole"
            }
            .sorted()
        val counters = entity.get<CountersComponent>()?.counters
            ?.entries
            ?.sortedBy { it.key.name }
            ?.map { listOf(it.key.name, it.value) }
        val projected = state.projectedState
        val controllerRole = projected.getController(entityId)?.let { state.turnOrder.indexOf(it) }
        val attachedTo = entity.get<AttachedToComponent>()?.targetId

        return fields(
            card.cardDefinitionId,
            card.name,
            card.typeLine.cardTypes.map { it.name }.sorted(),
            card.typeLine.supertypes.map { it.name }.sorted(),
            card.typeLine.subtypes.map { it.value }.sorted(),
            zoneRoles,
            controllerRole,
            projected.getPower(entityId),
            projected.getToughness(entityId),
            entity.has<TappedComponent>(),
            counters,
            semanticEntityKey(state, attachedTo, nextVisited),
        )
    }

    private fun fields(vararg values: Any?): String = values.joinToString("\u0001") { value ->
        when (value) {
            null -> "0:"
            is Boolean -> if (value) "1:true" else "1:false"
            is Int, is Long, is Double, is Float -> {
                val text = value.toString()
                "${text.length}:$text"
            }
            is String -> "${value.length}:$value"
            is Iterable<*> -> {
                val nested = value.joinToString("\u0002") { item -> fields(item) }
                "${nested.length}:[$nested]"
            }
            else -> error("Trigger ordering key received an unsupported semantic value")
        }
    }
}
