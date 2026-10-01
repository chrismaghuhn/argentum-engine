package com.wingedsheep.engine.event

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.ClassLevelComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantTriggeredAbility
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.filters.unified.Scope

/**
 * "Enchanted/equipped creature has '<triggered ability>' [as long as …]" — the triggered abilities
 * Auras and Equipment grant the permanent they're attached to through a [Scope.AttachedTo]
 * [GrantTriggeredAbility] (Ceremonial Knife, Curious Inquiry), a conditional one only while its
 * [ConditionalStaticAbility] condition holds with the attachment as the source (Essence Leak).
 *
 * Live, [TriggerAbilityResolver] reads them for the attachments currently on a permanent. A damage
 * trigger can't always be read that way: the state-based actions that destroy a creature dealt
 * lethal combat damage also unattach its Equipment (CR 704.5n) and put its Auras into the
 * graveyard (CR 704.5m) before the trigger is detected, although the creature still had the
 * granted ability when the damage was dealt.
 * [com.wingedsheep.engine.handlers.effects.DamageUtils.captureDamageRoleSnapshot] therefore freezes
 * [active] onto the damage event's snapshots.
 */
internal object AttachedTriggerGrants {

    /** The triggered abilities the attached permanents [attachmentIds] grant in [state]. */
    fun active(
        state: GameState,
        attachmentIds: List<EntityId>,
        cardRegistry: CardRegistry,
        conditionEvaluator: ConditionEvaluator,
    ): List<TriggeredAbility> {
        if (attachmentIds.isEmpty()) return emptyList()
        val result = mutableListOf<TriggeredAbility>()

        for (permanentId in attachmentIds) {
            val container = state.getEntity(permanentId) ?: continue

            val card = container.get<CardComponent>() ?: continue
            if (container.has<FaceDownComponent>()) continue

            val sourceDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue
            val classLevel = container.get<ClassLevelComponent>()?.currentLevel
            val allStaticAbilities = sourceDef.script.effectiveStaticAbilities(classLevel)

            for (ability in allStaticAbilities) {
                when (ability) {
                    is GrantTriggeredAbility ->
                        if (ability.filter.scope is Scope.AttachedTo) result.add(ability.ability)

                    // "As long as enchanted permanent is X, it has '<triggered ability>'" —
                    // a conditional grant (e.g. Essence Leak). Only contribute the granted ability
                    // while the gating condition holds, evaluated with the Aura as the source so
                    // EnchantedPermanentMatches resolves the attached permanent.
                    is ConditionalStaticAbility -> {
                        val grant = ability.ability as? GrantTriggeredAbility ?: continue
                        if (grant.filter.scope !is Scope.AttachedTo) continue
                        val controllerId = state.projectedState.getController(permanentId) ?: continue
                        val context = EffectContext(
                            sourceId = permanentId,
                            controllerId = controllerId,
                        )
                        if (conditionEvaluator.evaluate(state, ability.condition, context)) {
                            result.add(grant.ability)
                        }
                    }

                    else -> {}
                }
            }
        }

        return result
    }
}
