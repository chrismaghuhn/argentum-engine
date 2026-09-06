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
    ): CommanderPublicStateV1 {
        require(perspectivePlayerId in state.turnOrder) {
            "Commander-public projection perspective is not a game player"
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
                    publicCurrentZone = currentZone(state, commanderId),
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
     * The zone kind is public semantic knowledge; no physical EntityId, library index, or hidden
     * card slot is carried by CommanderPublicStateV1. The designation identity comes from the
     * public CommanderComponent/CardComponent relationship rather than the current zone.
     */
    private fun currentZone(state: GameState, commanderId: EntityId): CommanderPublicZoneKind {
        if (commanderId in state.stack) return CommanderPublicZoneKind.STACK
        val zone = state.zones.entries
            .firstOrNull { (_, entityIds) -> commanderId in entityIds }
            ?.key
            ?.zoneType
        return when (zone) {
            Zone.COMMAND -> CommanderPublicZoneKind.COMMAND
            Zone.BATTLEFIELD -> CommanderPublicZoneKind.BATTLEFIELD
            Zone.GRAVEYARD -> CommanderPublicZoneKind.GRAVEYARD
            Zone.EXILE -> CommanderPublicZoneKind.EXILE
            Zone.HAND -> CommanderPublicZoneKind.HAND
            Zone.LIBRARY -> CommanderPublicZoneKind.LIBRARY
            else -> CommanderPublicZoneKind.UNKNOWN
        }
    }
}
