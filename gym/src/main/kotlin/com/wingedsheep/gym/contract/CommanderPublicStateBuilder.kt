package com.wingedsheep.gym.contract

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/** Pure Rules-to-Gym projection of the additive Commander-public contract. */
internal object CommanderPublicStateBuilder {

    fun build(
        state: GameState,
        perspectivePlayerId: EntityId,
        observation: TrainingObservation,
    ): CommanderPublicStateV1 {
        require(perspectivePlayerId in state.turnOrder) {
            "Commander-public projection perspective is not a game player"
        }
        require(observation.perspectivePlayerId == perspectivePlayerId) {
            "Commander-public projection observation has a different perspective"
        }

        val playerOrder = state.turnOrder.withIndex().associate { it.value to it.index }
        val entries = state.findEntitiesWith<CommanderComponent>()
            .map { (commanderId, component) ->
                val card = state.getEntity(commanderId)?.get<CardComponent>()
                    ?: error("Commander entity has no public card component")
                val ownerIndex = playerOrder[component.ownerId]
                    ?: error("Commander owner is absent from the player roster")
                Triple(ownerIndex, card.cardDefinitionId, commanderId to component)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (_, _, pair) ->
                val (commanderId, commander) = pair
                val card = checkNotNull(state.getEntity(commanderId)?.get<CardComponent>())
                CommanderPublicEntryV1(
                    ownerPlayerId = commander.ownerId,
                    publicCommanderIdentity = card.cardDefinitionId,
                    publicCurrentZone = currentZone(observation, commanderId),
                    castsFromCommandZone = commander.castsFromCommandZone,
                    commanderDamageThreshold = state.format.commanderDamageThreshold,
                    damageByDefendingPlayer = state.turnOrder.map { defendingPlayerId ->
                        CommanderDamageByDefendingPlayerV1(
                            defendingPlayerId = defendingPlayerId,
                            cumulativeDamage = state.commanderDamageOf(commanderId, defendingPlayerId),
                        )
                    },
                )
            }

        return CommanderPublicStateV1(
            perspectivePlayerId = perspectivePlayerId,
            commanders = entries,
        )
    }

    /**
     * The zone kind is read from the already perspective-safe observation; hidden objects are not
     * recovered from GameState. No physical EntityId, library index, or hidden card slot is carried
     * by CommanderPublicStateV1. The designation identity comes from the public
     * CommanderComponent/CardComponent relationship rather than the current zone.
     */
    private fun currentZone(
        observation: TrainingObservation,
        commanderId: EntityId,
    ): CommanderPublicZoneKind {
        val visibleZones = buildList {
            observation.zones.forEach { zoneView ->
                if (zoneView.cards.any { it.entityId == commanderId }) {
                    add(zoneView.zoneType.toCommanderPublicZoneKind())
                }
            }
            if (observation.stack.any { it.entityId == commanderId }) {
                add(CommanderPublicZoneKind.STACK)
            }
        }

        // A hidden object is absent from the perspective-safe observation. Multiple matches are
        // also fail-closed rather than selecting a location from malformed or contradictory data.
        return visibleZones.singleOrNull() ?: CommanderPublicZoneKind.UNKNOWN
    }

    private fun Zone.toCommanderPublicZoneKind(): CommanderPublicZoneKind = when (this) {
        Zone.COMMAND -> CommanderPublicZoneKind.COMMAND
        Zone.BATTLEFIELD -> CommanderPublicZoneKind.BATTLEFIELD
        Zone.STACK -> CommanderPublicZoneKind.STACK
        Zone.GRAVEYARD -> CommanderPublicZoneKind.GRAVEYARD
        Zone.EXILE -> CommanderPublicZoneKind.EXILE
        Zone.HAND -> CommanderPublicZoneKind.HAND
        Zone.LIBRARY -> CommanderPublicZoneKind.LIBRARY
        else -> CommanderPublicZoneKind.UNKNOWN
    }
}
