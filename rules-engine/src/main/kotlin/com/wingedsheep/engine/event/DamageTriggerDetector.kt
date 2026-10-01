package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.core.GameEvent as EngineGameEvent
import com.wingedsheep.engine.core.effectiveRecipientKind
import com.wingedsheep.engine.core.effectiveRecipientKinds
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.isCapturedBattlefieldObjectLive
import com.wingedsheep.engine.state.components.stack.stampedFor
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.Recipient

/**
 * Handles all damage-related triggers.
 */
class DamageTriggerDetector(
    private val abilityResolver: TriggerAbilityResolver,
    private val matcher: TriggerMatcher,
    private val predicateEvaluator: PredicateEvaluator
) {


    companion object {
        /**
         * Whether [ability] is the SELF-bound "whenever a source deals damage to this creature"
         * shape ([GameObjectFilter.Any]) — the one whose triggering entity is the **damage source**
         * rather than the creature that was dealt the damage.
         *
         * "That source's controller mills that many cards" (Belltower Sphinx) has nothing to name
         * otherwise: the damaged creature is the trigger's own `sourceId`, and its controller is
         * already `controllerId`, so binding it carried no information. This matches what the
         * source-filtered variants have always done (`detectDamagedBySourceTriggers`) and what
         * `TriggerContext.fromEvent` does for `DamagePreventedEvent`.
         *
         * Shared because this trigger is detected in **two** places — the main battlefield scan in
         * `TriggerDetector` while the creature is still alive, and
         * [detectDamageReceivedTriggers] once it has died to that same damage. They must agree, or
         * a card would behave differently depending on whether the damage happened to be lethal.
         */
        fun bindsDamageSource(ability: TriggeredAbility): Boolean {
            val trigger = ability.trigger
            return ability.binding == TriggerBinding.SELF &&
                trigger is EventPattern.DamageReceivedEvent &&
                trigger.source == GameObjectFilter.Any
        }

        /**
         * The trigger context for [bindsDamageSource] abilities, built off the damage event: the
         * damage source is the triggering entity, and the event's damage roles, snapshots, amount,
         * excess and recipient toughness ride along.
         */
        fun damageReceivedContext(event: DamageDealtEvent): TriggerContext =
            TriggerContext.fromDamageEvent(event, triggeringEntityId = event.sourceId)
    }

    /**
     * Detect "whenever this creature is dealt damage" triggers on creatures that
     * are no longer on the battlefield (e.g., died from the damage via SBAs).
     * Similar to detectDeathTriggers pattern.
     */
    fun detectDamageReceivedTriggers(
        state: GameState,
        statics: BattlefieldStaticsIndex,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>
    ) {
        if (!event.effectiveRecipientKinds.contains(DamageRecipientKind.CREATURE)) return
        val entityId = event.targetId
        val recipientSnapshot = event.damageRecipientLastKnownSnapshot.stampedFor(entityId)
            ?: return
        val container = state.getEntity(entityId)
        val currentIsEventObject = state.isCapturedBattlefieldObjectLive(entityId, recipientSnapshot)
        val cardComponent = container?.get<CardComponent>()
        // Prefer the event-time controller. If the snapshot has no controller, use live state
        // only when its stamped incarnation is proven to be the same object.
        val controllerId = recipientSnapshot.controllerId
            ?: if (currentIsEventObject) {
                container?.get<ControllerComponent>()?.playerId
                    ?: cardComponent?.ownerId
            } else {
                null
            }
            ?: return

        // Face-down creatures have no abilities (Rule 708.2)
        // Check both current state AND the event's recorded face-down status, because
        // FaceDownComponent may have been stripped by stripBattlefieldComponents when
        // the creature died via SBAs before trigger detection runs.
        if (recipientSnapshot.wasFaceDown ||
            event.targetWasFaceDown ||
            (currentIsEventObject && container?.has<FaceDownComponent>() == true)
        ) return

        val abilities = abilitiesAtDamageTime(
            state = state,
            statics = statics,
            entityId = entityId,
            snapshot = recipientSnapshot,
        )
        val sourceName = recipientSnapshot.name
            ?: cardComponent?.name?.takeIf { currentIsEventObject }
            ?: return

        for (ability in abilities) {
            val trigger = ability.trigger
            // Only match generic (source=Any) DamageReceivedEvent triggers here.
            // Source-filtered triggers (DamagedByCreature, DamagedBySpell) are handled
            // exclusively by detectDamagedBySourceTriggers.
            if (trigger is EventPattern.DamageReceivedEvent && bindsDamageSource(ability)) {
                triggers.add(
                    PendingTrigger(
                        ability = ability,
                        sourceId = entityId,
                        sourceName = sourceName,
                        controllerId = controllerId,
                        // Binds the damage *source*, not the creature that was dealt the damage —
                        // see [bindsDamageSource]. The main battlefield scan in TriggerDetector
                        // applies the same rule for the case where the creature survived.
                        triggerContext = damageReceivedContext(event)
                    )
                )
            }
        }
    }

    fun detectDamageSourceTriggers(
        state: GameState,
        statics: BattlefieldStaticsIndex,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>,
        projected: ProjectedState
    ) {
        val sourceId = event.sourceId ?: return
        val sourceSnapshot = event.damageSourceLastKnownSnapshot.stampedFor(sourceId)
            ?: return
        val container = state.getEntity(sourceId)
        val cardComponent = container?.get<CardComponent>()
        val currentIsEventObject = state.isCapturedBattlefieldObjectLive(sourceId, sourceSnapshot)
        // Fall back to ownerId if ControllerComponent was stripped (e.g., creature died to SBA
        // during combat damage, but its damage trigger should still fire per Rule 603.10)
        val controllerId = sourceSnapshot.controllerId
            ?: if (currentIsEventObject) {
                projected.getController(sourceId)
                    ?: container?.get<ControllerComponent>()?.playerId
                    ?: cardComponent?.ownerId
            } else {
                null
            }
            ?: return

        // Face-down creatures have no abilities (Rule 708.2)
        if (sourceSnapshot.wasFaceDown ||
            (currentIsEventObject && container?.has<FaceDownComponent>() == true)
        ) return

        val abilities = abilitiesAtDamageTime(
            state = state,
            statics = statics,
            entityId = sourceId,
            snapshot = sourceSnapshot,
        )
        val sourceName = sourceSnapshot.name
            ?: cardComponent?.name?.takeIf { currentIsEventObject }
            ?: return

        for (ability in abilities) {
            val trigger = ability.trigger
            if (trigger is EventPattern.DealsDamageEvent && ability.binding == TriggerBinding.SELF) {
                // Pass the ability's controller and source so the recipient's relative readings
                // ("a creature an opponent controls", "enchanted player") resolve against them.
                if (matcher.matchesDealsDamageTrigger(trigger, event, state, controllerId, sourceId)) {
                    triggers.add(
                        PendingTrigger(
                            ability = ability,
                            sourceId = sourceId,
                            sourceName = sourceName,
                            controllerId = controllerId,
                            triggerContext = if (trigger.sourceFilter != null) {
                                TriggerContext.fromSourceFilteredDamageEvent(event) ?: continue
                            } else {
                                // Source-blind SELF damage triggers retain their legacy recipient
                                // TriggeringEntity semantics; explicit source filters bind the source.
                                TriggerContext.fromEvent(event)
                            }
                        )
                    )
                }
            }
        }
    }

    /**
     * Detect source-filtered "whenever [a source matching X] deals damage to this" triggers
     * (Tephraderm: "a creature", "a spell"). The triggering entity is the damage SOURCE, for
     * retaliation effects.
     *
     * Neither end has to still be on the battlefield: the damaged permanent may have died to the
     * damage, and combat damage is dealt simultaneously, so the attacker may have died to the same
     * exchange (CR 603.10). The source filter is evaluated against the source as it was when it
     * dealt the damage — the event's stamped source snapshot — so a same-id object that replaced the
     * source can never stand in for it; an unstamped source fails closed.
     */
    fun detectDamagedBySourceTriggers(
        state: GameState,
        statics: BattlefieldStaticsIndex,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>
    ) {
        if (!event.effectiveRecipientKinds.contains(DamageRecipientKind.CREATURE)) return
        val sourceId = event.sourceId ?: return
        val damagedEntityId = event.targetId
        // Both ends need their stamped damage-time identity: the source filter reads the source
        // snapshot (TriggerMatcher.matchesDamageReceivedSource), the abilities come from the
        // recipient's.
        event.damageSourceLastKnownSnapshot.stampedFor(sourceId) ?: return
        val recipientSnapshot = event.damageRecipientLastKnownSnapshot.stampedFor(damagedEntityId)
            ?: return

        // Get the damaged entity (might be on battlefield or in graveyard)
        val container = state.getEntity(damagedEntityId)
        val currentIsEventObject = state.isCapturedBattlefieldObjectLive(damagedEntityId, recipientSnapshot)
        val cardComponent = container?.get<CardComponent>()
        val controllerId = recipientSnapshot.controllerId
            ?: if (currentIsEventObject) {
                container?.get<ControllerComponent>()?.playerId
                    ?: cardComponent?.ownerId
            } else {
                null
            }
            ?: return

        // Face-down creatures have no abilities (Rule 708.2)
        if (recipientSnapshot.wasFaceDown ||
            event.targetWasFaceDown ||
            (currentIsEventObject && container?.has<FaceDownComponent>() == true)
        ) return

        val abilities = abilitiesAtDamageTime(
            state = state,
            statics = statics,
            entityId = damagedEntityId,
            snapshot = recipientSnapshot,
        )
        val recipientName = recipientSnapshot.name
            ?: cardComponent?.name?.takeIf { currentIsEventObject }
            ?: return

        for (ability in abilities) {
            val trigger = ability.trigger
            if (trigger !is EventPattern.DamageReceivedEvent || ability.binding != TriggerBinding.SELF) continue
            if (trigger.source == GameObjectFilter.Any) continue
            // The source filter is read from the source as it was when it dealt the damage — its
            // stamped event-time snapshot — never from a same-id object that replaced it. Neither end
            // has to still be on the battlefield (CR 603.10).
            if (!matcher.matchesDamageReceivedSource(trigger.source, event, state, controllerId, damagedEntityId)) continue
            triggers.add(
                PendingTrigger(
                    ability = ability,
                    sourceId = damagedEntityId,
                    sourceName = recipientName,
                    controllerId = controllerId,
                    triggerContext = TriggerContext.fromDamageEvent(
                        event,
                        triggeringEntityId = sourceId
                    )
                )
            )
        }
    }

    /**
     * Resolve the ability set from the object's event-time identity. A stamped snapshot selects
     * the abilities it captured — intrinsic ones from its definition plus the grants frozen into
     * [EntitySnapshot.grantedTriggeredAbilities] — even when the id is now gone or names a
     * replacement; a snapshot without a definition may use the live entity only when its
     * incarnation is proven unchanged. This keeps damage trigger discovery from silently switching
     * to a newer object.
     */
    private fun abilitiesAtDamageTime(
        state: GameState,
        statics: BattlefieldStaticsIndex,
        entityId: com.wingedsheep.sdk.model.EntityId,
        snapshot: EntitySnapshot,
    ): List<com.wingedsheep.sdk.scripting.TriggeredAbility> {
        if (snapshot.stampedFor(entityId) == null) return emptyList()
        // A live stamped object still has dynamic abilities granted by the current projected state
        // (for example The Ring's abilities on its current Ring-bearer). Use the normal resolver
        // only for that proven incarnation. A departed/replaced object uses only what its
        // damage-time snapshot captured (a creature that died to this damage still had its grants
        // when the trigger condition was met) and can never switch to the newer same-id object's
        // abilities.
        if (!state.isCapturedBattlefieldObjectLive(entityId, snapshot)) {
            if (snapshot.cardDefinitionId != null) {
                return abilityResolver.getTriggeredAbilitiesFromSnapshot(entityId, snapshot)
            }
            return emptyList()
        }
        val card = state.getEntity(entityId)?.get<CardComponent>() ?: return emptyList()
        return abilityResolver.getTriggeredAbilities(entityId, card.cardDefinitionId, state, statics)
    }

    /**
     * Detect "whenever [a source matching X] deals damage to you" triggers on permanents
     * controlled by the damaged player. Uses pre-indexed damage-to-you observers
     * instead of scanning all battlefield permanents.
     *
     * *What* may deal the damage comes from the trigger's own `sourceFilter`, not from a hardcoded
     * type check here: `GameObjectFilter.Creature` for Aurification's "whenever a creature deals
     * damage to you", `Any.opponentControls()` for Farsight Mask's "a source an opponent controls",
     * and null for Sun Droplet's source-blind "whenever you're dealt damage" — which must fire for
     * a burn spell or an artifact just as it does for a creature.
     */
    fun detectDamageToControllerTriggers(
        state: GameState,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>,
        projected: ProjectedState,
        index: TriggerIndex
    ) {
        if (!event.effectiveRecipientKinds.contains(DamageRecipientKind.PLAYER)) return
        val damagedPlayerId = event.targetId

        for (entry in index.damageToYouObservers) {
            // Only triggers on permanents controlled by the damaged player
            if (entry.controllerId != damagedPlayerId) continue

            for (ability in entry.abilities) {
                val trigger = ability.trigger
                if (trigger is EventPattern.DealsDamageEvent &&
                    trigger.recipient == Recipient.You &&
                    ability.binding == TriggerBinding.ANY &&
                    matchesDamageType(trigger.damageType, event) &&
                    matcher.matchesDamageSourceFilter(
                        trigger.sourceFilter, event, state, entry.controllerId
                    )) {
                    // This path binds the damage *source* as the triggering entity ("…exile it",
                    // Farsight Mask). A source filter needs the stamped source (fail closed); a
                    // source-blind observer ("whenever you're dealt damage", Sun Droplet) also accepts
                    // an unknown source and then names the damaged player instead. Both carry the
                    // event's damage roles and snapshots.
                    val triggerContext = if (trigger.sourceFilter == null) {
                        TriggerContext.fromDamageEvent(event, triggeringEntityId = event.sourceId ?: event.targetId)
                    } else {
                        TriggerContext.fromSourceFilteredDamageEvent(event)
                    } ?: continue
                    triggers.add(
                        PendingTrigger(
                            ability = ability,
                            sourceId = entry.entityId,
                            sourceName = entry.cardComponent.name,
                            controllerId = entry.controllerId,
                            triggerContext = triggerContext
                        )
                    )
                }
            }
        }
    }

    /** Combat/noncombat gate for a [EventPattern.DealsDamageEvent]; [DamageType.Any] matches both. */
    private fun matchesDamageType(damageType: DamageType, event: DamageDealtEvent): Boolean =
        damageType == DamageType.Any ||
            (damageType == DamageType.Combat && event.isCombatDamage) ||
            (damageType == DamageType.NonCombat && !event.isCombatDamage)

    /**
     * Detect general damage observer triggers (DealsDamageEvent with ANY binding)
     * that aren't handled by the specialized detectDamageToControllerTriggers or
     * detectSubtypeDamageToPlayerTriggers methods.
     * E.g., Kazarov: "Whenever a creature an opponent controls is dealt damage"
     */
    fun detectDamageObserverTriggers(
        state: GameState,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>,
        index: TriggerIndex
    ) {
        for (entry in index.damageObservers) {
            for (ability in entry.abilities) {
                if (!isGeneralDamageObserver(ability)) continue
                matchDamageObserver(
                    state = state,
                    event = event,
                    triggers = triggers,
                    ability = ability,
                    sourceId = entry.entityId,
                    sourceName = entry.cardComponent.name,
                    controllerId = entry.controllerId
                )
            }
        }

        // Global granted abilities are attached to no permanent, so they are absent from every
        // battlefield index — and the generic TriggerMatcher deliberately returns false for
        // DealsDamageEvent (all damage patterns route here). Without this pass a floating
        // "whenever a creature you control deals combat damage to a player" ability (Mistway Spy's
        // turned-face-up payoff) would never fire. They are few and only live for their duration,
        // so the extra walk costs nothing on a board without one.
        for (global in state.globalGrantedTriggeredAbilities) {
            matchDamageObserver(
                state = state,
                event = event,
                triggers = triggers,
                ability = global.ability,
                sourceId = global.sourceId,
                sourceName = global.sourceName,
                controllerId = global.controllerId
            )
        }
    }

    /**
     * Match one ANY-bound [EventPattern.DealsDamageEvent] observer against [event] and queue it.
     * Shared by the indexed battlefield observers and the global granted abilities, which differ
     * only in where their identity comes from.
     */
    private fun matchDamageObserver(
        state: GameState,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>,
        ability: com.wingedsheep.sdk.scripting.TriggeredAbility,
        sourceId: com.wingedsheep.sdk.model.EntityId,
        sourceName: String,
        controllerId: com.wingedsheep.sdk.model.EntityId
    ) {
        val trigger = ability.trigger
        if (trigger !is EventPattern.DealsDamageEvent || ability.binding != TriggerBinding.ANY) return
        // Batch ("one or more") observers fire once per event batch, not once per
        // damage event — handled by detectDamageObserverBatchTriggers.
        if (trigger.batch) return
        if (!matcher.matchesDealsDamageTrigger(trigger, event, state, controllerId, sourceId)) return
        // When the trigger has a sourceFilter (e.g., "creature you control deals
        // combat damage"), the triggering entity is the damage SOURCE (the creature),
        // not the damage recipient. This allows effects like "exile it" to reference
        // the creature that dealt damage.
        val context = if (trigger.sourceFilter != null) {
            // The triggering entity is the damage SOURCE (e.g. "a source you
            // control deals damage… exile it"). Still carry the recipient creature's
            // toughness so "equal to that creature's toughness" payoffs (Taii Wakeen)
            // can read it via ContextPropertyKey.TRIGGER_RECIPIENT_TOUGHNESS. When the
            // recipient is a player, also carry it as the triggering player so
            // "…to a player, [that player] …" payoffs (Fear of Burning Alive's
            // "target creature that player controls") resolve Player.TriggeringPlayer
            // to the damaged player rather than the source.
            TriggerContext.fromSourceFilteredDamageEvent(
                event,
                triggeringPlayerId = event.targetId.takeIf {
                    event.effectiveRecipientKinds.contains(DamageRecipientKind.PLAYER)
                }
            ) ?: return
        } else {
            TriggerContext.fromEvent(event)
        }
        triggers.add(
            PendingTrigger(
                ability = ability,
                sourceId = sourceId,
                sourceName = sourceName,
                controllerId = controllerId,
                triggerContext = context
            )
        )
    }

    /**
     * Detect batch ("one or more") damage observer triggers — `DealsDamageEvent(batch = true)`
     * with ANY binding, e.g. Magmatic Galleon's "Whenever one or more creatures your opponents
     * control are dealt excess noncombat damage, create a Treasure token."
     *
     * Runs once over the whole event batch (CR 603.2c: an ability triggers only once each time
     * its trigger event occurs): a sweeper dealing excess damage to several matching creatures
     * simultaneously fires the trigger once, not once per creature — the over-counting the
     * per-event [detectDamageObserverTriggers] path would produce. Each observer's filters
     * (damageType / recipient / sourceFilter / requireExcess) are evaluated per damage event via
     * the canonical [TriggerMatcher.matchesDealsDamageTrigger]; one matching event suffices.
     *
     * Source-filtered batches retain the first matching source as `triggeringEntityId`; source-blind
     * batches retain the first matching recipient. Batch triggers don't dispatch per pair, so cards
     * needing per-recipient context use the singular (non-batch) trigger.
     */
    fun detectDamageObserverBatchTriggers(
        state: GameState,
        events: List<EngineGameEvent>,
        triggers: MutableList<PendingTrigger>,
        index: TriggerIndex
    ) {
        val damageEvents = events.filterIsInstance<DamageDealtEvent>()
        if (damageEvents.isEmpty()) return

        for (entry in index.damageObservers) {
            for (ability in entry.abilities) {
                val trigger = ability.trigger
                if (trigger !is EventPattern.DealsDamageEvent || !trigger.batch) continue
                if (ability.binding != TriggerBinding.ANY) continue
                if (!isGeneralDamageObserver(ability)) continue

                val firstMatching = damageEvents.firstOrNull { event ->
                    matcher.matchesDealsDamageTrigger(trigger, event, state, entry.controllerId, entry.entityId)
                }
                if (firstMatching != null) {
                    val triggerContext = if (trigger.sourceFilter != null) {
                        TriggerContext.fromSourceFilteredDamageEvent(
                            firstMatching,
                            triggeringPlayerId = firstMatching.targetId.takeIf {
                                firstMatching.effectiveRecipientKinds.contains(DamageRecipientKind.PLAYER)
                            }
                        ) ?: continue
                    } else {
                        TriggerContext.fromEvent(firstMatching)
                    }
                    triggers.add(
                        PendingTrigger(
                            ability = ability,
                            sourceId = entry.entityId,
                            sourceName = entry.cardComponent.name,
                            controllerId = entry.controllerId,
                            triggerContext = triggerContext
                        )
                    )
                }
            }
        }
    }

    /**
     * Detect "whenever a [subtype] deals combat damage to a player" triggers.
     * Uses pre-indexed subtype damage observers instead of scanning all battlefield permanents.
     */
    fun detectSubtypeDamageToPlayerTriggers(
        state: GameState,
        event: DamageDealtEvent,
        triggers: MutableList<PendingTrigger>,
        projected: ProjectedState,
        index: TriggerIndex
    ) {
        if (!event.effectiveRecipientKinds.contains(DamageRecipientKind.PLAYER)) return
        if (!matcher.isDamageSourceCreatureAtDamage(state, projected, event)) return

        for (entry in index.subtypeDamageObservers) {
            for (ability in entry.abilities) {
                val trigger = ability.trigger
                if (trigger is EventPattern.DealsDamageEvent &&
                    trigger.damageType == DamageType.Combat &&
                    trigger.recipient == Recipient.AnyPlayer &&
                    trigger.sourceFilter != null) {
                    // Check if the sourceFilter has a subtype requirement
                    val filter = trigger.sourceFilter
                    val subtypeValue = if (filter is GameObjectFilter) matcher.extractSubtypeFromFilter(filter) else null
                    if (subtypeValue != null &&
                        matcher.matchesDamageSourceFilter(filter, event, state, entry.controllerId)
                    ) {
                        triggers.add(
                            PendingTrigger(
                                ability = ability,
                                sourceId = entry.entityId,
                                sourceName = entry.cardComponent.name,
                                controllerId = entry.controllerId,
                                triggerContext = TriggerContext.fromSourceFilteredDamageEvent(event)
                                    ?: continue
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Which detector owns an ANY-bound [EventPattern.DealsDamageEvent] observer. */
internal enum class DamageObserverBucket { ToYou, SubtypeToPlayer, General }

/**
 * Every "… deals damage to you" observer goes to the damage-to-you bucket, with or without a
 * sourceFilter, and only there: that path binds the damage *source* as the triggering entity
 * ("…exile it", Farsight Mask), and routing it to the general observers as well would fire it twice.
 */
internal fun damageObserverBucket(trigger: EventPattern.DealsDamageEvent): DamageObserverBucket {
    if (trigger.recipient == Recipient.You) return DamageObserverBucket.ToYou
    val filter = trigger.sourceFilter
    val subtypeCombatToPlayer = trigger.damageType == DamageType.Combat &&
        trigger.recipient == Recipient.AnyPlayer &&
        filter is GameObjectFilter &&
        filter.cardPredicates.any { it is com.wingedsheep.sdk.scripting.predicates.CardPredicate.HasSubtype }
    return if (subtypeCombatToPlayer) DamageObserverBucket.SubtypeToPlayer else DamageObserverBucket.General
}

/**
 * A permanent is filed under each bucket it has an observer for, so the general walk must skip the
 * abilities that belong to the You / subtype buckets or they would fire once per bucket.
 */
private fun isGeneralDamageObserver(ability: TriggeredAbility): Boolean {
    val trigger = ability.trigger
    return trigger is EventPattern.DealsDamageEvent && damageObserverBucket(trigger) == DamageObserverBucket.General
}
