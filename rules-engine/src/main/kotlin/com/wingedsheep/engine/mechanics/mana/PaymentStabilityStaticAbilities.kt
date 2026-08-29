package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.ClassLevelComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.sdk.scripting.StaticAbility

/**
 * Resolves every static ability visible to the payment-program stability certificates.
 *
 * Normal battlefield permanents contribute their effective Rules-owned definition statics. Inline
 * tokens intentionally have no CardDefinition; their static abilities are materialized in
 * [GameState.grantedStaticAbilities] by token creation and are therefore the only authority for
 * those objects. Any other missing definition is an unknown Rules object and keeps the complete
 * V5 payment domain fail-closed.
 */
internal fun resolvePaymentStabilityStaticAbilities(
    state: GameState,
    cardRegistry: CardRegistry,
): List<StaticAbility>? {
    val abilities = mutableListOf<StaticAbility>()
    for (entityId in state.getBattlefield()) {
        val container = state.getEntity(entityId) ?: continue
        val card = container.get<CardComponent>() ?: continue
        val cardDefinition = cardRegistry.getCard(card.cardDefinitionId)
        if (cardDefinition != null) {
            val classLevel = container.get<ClassLevelComponent>()?.currentLevel
            abilities += cardDefinition.script.effectiveStaticAbilities(classLevel)
        } else if (!(container.has<TokenComponent>() && card.cardDefinitionId.startsWith("token:"))) {
            return null
        }
    }

    // Dynamic tokens intentionally have no CardDefinition. Their static abilities are stored in
    // this Rules-owned channel by token creation and must be included in every stability scan.
    abilities += state.grantedStaticAbilities.map { it.ability }
    return abilities
}
