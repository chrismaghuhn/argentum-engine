package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.core.GameEvent as EngineGameEvent
import com.wingedsheep.engine.core.effectiveRecipientKind
import com.wingedsheep.engine.core.effectiveRecipientKinds
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.isCapturedBattlefieldObjectLive
import com.wingedsheep.engine.state.components.stack.isStampedFor
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.events.SourceFilter

/**
 * Handles all damage-related triggers.
 */
class DamageTriggerDetector(
    private val abilityResolver: TriggerAbilityResolver,
    private val matcher: TriggerMatcher
) {

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
        val recipientSnapshot = event.damageRecipientLastKnownSnapshot
            ?.takeIf { it.isStampedFor(entityId) }
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
            // exclusively by detectDamagedBySourceTriggers to avoid firing with a wrong
            // triggeringEntityId (fromEvent uses targetId, not sourceId).
            if (trigger is EventPattern.DamageReceivedEvent &&
                ability.binding == TriggerBinding.SELF &&
                trigger.source == SourceFilter.Any
            ) {
                triggers.add(
                    PendingTrigger(
                        ability = ability,
                        sourceId = entityId,
                        sourceName = sourceName,
                        controllerId = controllerId,
                        triggerContext = TriggerContext.fromEvent(event)
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
        val sourceSnapshot = event.damageSourceLastKnownSnapshot
            ?.takeIf { it.isStampedFor(sourceId) }
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
                // Pass the ability's controller so RecipientFilter.Matching can evaluate
                // controller-relative recipient filters (e.g. "a creature an opponent controls").
                if (matcher.matchesDealsDamageTrigger(trigger, event, state, controllerId)) {
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
     * Detect "whenever a creature/spell deals damage to this" triggers.
     * For DamageReceivedEvent(source=Creature): source must be a creature on the battlefield.
     * For DamageReceivedEvent(source=Spell): source must be an instant or sorcery.
     * TriggeringEntityId is set to the damage SOURCE for retaliation effects.
     *
     * Handles both on-battlefield and off-battlefield cases (e.g., creature
     * dies from lethal damage but trigger still fires per Rule 603.10).
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
        val sourceSnapshot = event.damageSourceLastKnownSnapshot
            ?.takeIf { it.isStampedFor(sourceId) }
            ?: return
        val recipientSnapshot = event.damageRecipientLastKnownSnapshot
            ?.takeIf { it.isStampedFor(damagedEntityId) }
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

        // Determine source type from the event-time snapshot only. Direct damage-received source
        // dispatch is a look-back query: a live id is not enough to prove that the current printed
        // object is the source that dealt this damage, and a missing/unstamped snapshot cannot
        // answer the question safely. In particular, do not classify a same-id replacement from
        // its current CardComponent (or treat a missing snapshot as the original source).
        val sourceTypeLine = sourceSnapshot.typeLine ?: return
        // Do NOT require the source to still be on the battlefield: combat damage is dealt
        // simultaneously, so the attacker may have died from Tephraderm's damage in the same
        // combat step (Rule 603.10 look-back). We check the card's type line instead of
        // current zone to determine what it was when it dealt the damage.
        val isCreatureSource = sourceTypeLine.isCreature && !sourceSnapshot.wasFaceDown
        val isSpellSource =
            (sourceTypeLine.isInstant || sourceTypeLine.isSorcery) &&
                !sourceSnapshot.wasFaceDown

        for (ability in abilities) {
            val trigger = ability.trigger
            val matches = when {
                trigger is EventPattern.DamageReceivedEvent && ability.binding == TriggerBinding.SELF &&
                    trigger.source == SourceFilter.Creature && isCreatureSource -> true
                trigger is EventPattern.DamageReceivedEvent && ability.binding == TriggerBinding.SELF &&
                    trigger.source == SourceFilter.Spell && isSpellSource -> true
                else -> false
            }

            if (matches) {
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
    }

    /**
     * Resolve the ability set from the object's event-time identity. A stamped snapshot selects
     * snapshot-only intrinsic abilities even when the id is now gone or names a replacement; a
     * snapshot without a definition may use the live entity only when its incarnation is proven
     * unchanged. This keeps damage trigger discovery from silently switching to a newer object.
     */
    private fun abilitiesAtDamageTime(
        state: GameState,
        statics: BattlefieldStaticsIndex,
        entityId: com.wingedsheep.sdk.model.EntityId,
        snapshot: EntitySnapshot,
    ): List<com.wingedsheep.sdk.scripting.TriggeredAbility> {
        if (!snapshot.isStampedFor(entityId)) return emptyList()
        // A live stamped object still has dynamic abilities granted by the current projected state
        // (for example The Ring's abilities on its current Ring-bearer). Use the normal resolver
        // only for that proven incarnation. A departed/replaced object uses snapshot-only
        // intrinsic abilities and can never switch to the newer same-id object's abilities.
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
                    trigger.recipient == RecipientFilter.You &&
                    ability.binding == TriggerBinding.ANY &&
                    matchesDamageType(trigger.damageType, event) &&
                    matcher.matchesDamageSourceFilter(
                        trigger.sourceFilter, event, state, entry.controllerId
                    )) {
                    val triggerContext = if (trigger.sourceFilter == null) {
                        TriggerContext.fromEvent(event)
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
        if (!matcher.matchesDealsDamageTrigger(trigger, event, state, controllerId)) return
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

                val firstMatching = damageEvents.firstOrNull { event ->
                    matcher.matchesDealsDamageTrigger(trigger, event, state, entry.controllerId)
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
                    trigger.recipient == RecipientFilter.AnyPlayer &&
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
