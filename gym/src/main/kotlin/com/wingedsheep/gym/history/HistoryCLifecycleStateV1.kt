package com.wingedsheep.gym.history

import com.wingedsheep.sdk.model.EntityId

/** Immutable authoritative History-C state for one semantic episode. */
internal data class HistoryCLifecycleStateV1(
    val semanticEpisodeId: String,
    val playerIds: List<EntityId>,
    val registries: Map<EntityId, PerspectiveAliasRegistryV1>,
) {
    init {
        require(semanticEpisodeId.isNotBlank()) {
            "History-C lifecycle requires a non-blank semantic episode identity"
        }
        require(
            playerIds.isNotEmpty() &&
                playerIds.all { it.value.isNotBlank() } &&
                playerIds.distinct().size == playerIds.size,
        ) {
            "History-C lifecycle requires a non-empty distinct player roster"
        }
        require(registries.keys == playerIds.toSet()) {
            "History-C lifecycle registries must cover exactly the player roster"
        }
        require(registries.all { (perspectivePlayerId, registry) ->
            registry.semanticEpisodeId == semanticEpisodeId &&
                registry.perspectivePlayerId == perspectivePlayerId &&
                perspectivePlayerId in playerIds
        }) {
            "History-C lifecycle registry namespaces must match their perspective"
        }
    }

    fun withRegistry(registry: PerspectiveAliasRegistryV1): HistoryCLifecycleStateV1 {
        require(registry.semanticEpisodeId == semanticEpisodeId) {
            "History-C registry episode does not match lifecycle episode"
        }
        require(registry.perspectivePlayerId in playerIds) {
            "History-C registry perspective is not in the lifecycle roster"
        }
        return copy(registries = registries + (registry.perspectivePlayerId to registry))
    }

    companion object {
        fun start(
            semanticEpisodeId: String,
            playerIds: List<EntityId>,
        ): HistoryCLifecycleStateV1 {
            require(semanticEpisodeId.isNotBlank()) {
                "History-C lifecycle requires a non-blank semantic episode identity"
            }
            require(
                playerIds.isNotEmpty() &&
                    playerIds.all { it.value.isNotBlank() } &&
                    playerIds.distinct().size == playerIds.size,
            ) {
                "History-C lifecycle requires a non-empty distinct player roster"
            }
            return HistoryCLifecycleStateV1(
                semanticEpisodeId = semanticEpisodeId,
                playerIds = playerIds.toList(),
                registries = playerIds.associateWith { perspectivePlayerId ->
                    PerspectiveAliasRegistryV1(
                        semanticEpisodeId = semanticEpisodeId,
                        perspectivePlayerId = perspectivePlayerId,
                    )
                },
            )
        }
    }
}
