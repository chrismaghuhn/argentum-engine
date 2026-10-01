package com.wingedsheep.engine.mechanics.stack

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.mechanics.targeting.TargetValidator
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.TargetedByControllerThisTurnComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.stack.*
import com.wingedsheep.engine.state.nameVisibleToAll
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.*

/**
 * Puts the non-cast stack objects on the stack — a copy of a spell (CR 707.10), a triggered
 * ability, an activated ability — and owns the "becomes the target" announcement every stack
 * object makes as it goes on the stack (CR 601.2c / 602.2b), including crime detection and the
 * Valiant "first time each turn" tracking.
 *
 * Each put function takes an optional `targetValidator` — the engine's canonical
 * [TargetValidator]. When given, the target requirements a stack object stores are *locked* to
 * the targets actually chosen (CR 601.2c: choices are made as the object is put on the stack, so a
 * dynamic or "up to" count is never re-evaluated against a later board), which is the shape the
 * CR 608.2b resolution re-check consumes. The [StackResolver] façade always passes it.
 */
internal object StackPlacement {
    /**
     * Whether a stack object stores a [TargetsComponent]: it has targets, or a mandatory locked slot
     * with none — such a malformed payload is kept so resolution rejects it fail-closed (CR 608.2b)
     * rather than resolving as targetless. An optional targetless payload stays targetless.
     */
    fun hasTargetPayload(
        targets: List<ChosenTarget>,
        requirements: List<TargetRequirement>
    ): Boolean = targets.isNotEmpty() || requirements.any { it.effectiveMinCount > 0 }

    /**
     * Put a triggered ability on the stack.
     */
    fun putTriggeredAbility(
        state: GameState,
        ability: TriggeredAbilityOnStackComponent,
        targets: List<ChosenTarget> = emptyList(),
        targetRequirements: List<TargetRequirement> = emptyList(),
        /**
         * True when this ability fired because its own source creature was declared as an attacker
         * (a SELF-bound attacks trigger). Stamped onto the emitted [AbilityTriggeredEvent] so
         * Firebender Ascension's "attacking causes a triggered ability of that creature to trigger"
         * meta-trigger can key on it.
         */
        causedByAttack: Boolean = false,
        /**
         * True when this stack object was created because the ability actually triggered. A copy
         * of a triggered ability is put onto the stack by a copy effect, but does not trigger a
         * second time (CR 603.2 / 707.10), so copy paths suppress only this event. Crime detection
         * remains independent because CR 700.13 counts putting a targeted triggered-ability copy
         * onto the stack.
         */
        emitTriggeredEvent: Boolean = true,
        /** The engine's canonical validator; when given, the stored requirements are locked. */
        targetValidator: TargetValidator? = null
    ): ExecutionResult {
        // Create a new entity for the ability on the stack
        val (abilityId, stateWithId) = state.newEntity()

        val sourceCard = state.getEntity(ability.sourceId)?.get<CardComponent>()
        val sourceColors = sourceCard?.colors ?: emptySet()
        val sourceSubtypes = sourceCard?.typeLine?.subtypes?.map { it.value }?.toSet() ?: emptySet()
        val triggeringEntityId = ability.triggerContext?.triggeringEntityId
        val triggeringPlayerId = ability.triggerContext?.triggeringPlayerId
        val storedCollections = ability.carriedPipeline?.storedCollections ?: emptyMap()
        val lockedTargetRequirements = if (targetValidator != null && targets.isNotEmpty()) {
            targetValidator.lockRequirementsForTargets(
                state = state,
                targets = targets,
                requirements = targetRequirements,
                casterId = ability.controllerId,
                sourceColors = sourceColors,
                sourceSubtypes = sourceSubtypes,
                sourceId = ability.sourceId,
                xValue = ability.xValue,
                targetingSourceType = TargetingSourceType.TRIGGERED_ABILITY,
                triggeringEntityId = triggeringEntityId,
                triggeringPlayerId = triggeringPlayerId,
                storedCollections = storedCollections
            )
        } else {
            targetRequirements
        }
        // A modal trigger's per-mode requirements are locked the same way, mode by mode, so the
        // per-mode queue re-checks each mode's own locked slots at resolution.
        val lockedAbility = if (targetValidator != null && ability.chosenModes.isNotEmpty()) {
            val lockedModeRequirements = ability.chosenModes.mapIndexed { ordinal, modeIndex ->
                val raw = ability.modeTargetRequirementsOrdered.getOrNull(ordinal)
                    ?: ability.modeTargetRequirements[modeIndex].orEmpty()
                targetValidator.lockRequirementsForTargets(
                    state = state,
                    targets = ability.modeTargetsOrdered.getOrNull(ordinal).orEmpty(),
                    requirements = raw,
                    casterId = ability.controllerId,
                    sourceColors = sourceColors,
                    sourceSubtypes = sourceSubtypes,
                    sourceId = ability.sourceId,
                    xValue = ability.xValue,
                    targetingSourceType = TargetingSourceType.TRIGGERED_ABILITY,
                    triggeringEntityId = triggeringEntityId,
                    triggeringPlayerId = triggeringPlayerId,
                    storedCollections = storedCollections
                )
            }
            ability.copy(
                modeTargetRequirementsOrdered = lockedModeRequirements,
                modeTargetRequirements = ability.chosenModes.distinct().associateWith { modeIndex ->
                    val ordinal = ability.chosenModes.indexOf(modeIndex)
                    lockedModeRequirements.getOrNull(ordinal)
                        ?: ability.modeTargetRequirements[modeIndex].orEmpty()
                }
            )
        } else {
            ability
        }

        var container = ComponentContainer.of(lockedAbility)
        if (hasTargetPayload(targets, lockedTargetRequirements)) {
            container = container.with(TargetsComponent.capture(state, targets, lockedTargetRequirements))
        }

        var newState = stateWithId.withEntity(abilityId, container)
        newState = newState.pushToStack(abilityId)
            .copy(priorityPassedBy = emptySet())

        // The only ability a face-down permanent can put on the stack is the ward its face-down
        // mode grants (CR 702.168a disguise / 701.58a cloak), and reporting `ability.sourceName`
        // for it announced exactly which card had just refused to be targeted.
        val sourceDisplayName = nameVisibleToAll(state, ability.sourceId, ability.sourceName)

        val events = mutableListOf<GameEvent>()
        if (emitTriggeredEvent) {
            events.add(
                AbilityTriggeredEvent(
                    ability.sourceId,
                    sourceDisplayName,
                    ability.controllerId,
                    ability.description,
                    abilityEntityId = abilityId,
                    causedByAttack = causedByAttack,
                    sourceEndpointAuthority = ability.sourceEndpointAuthority,
                    sourceObjectIncarnationStamp = ability.sourceObjectIncarnationStamp
                        ?: state.objectIdentityStamps[ability.sourceId],
                )
            )
        }

        if (CrimeDetector.isCrime(newState, ability.controllerId, targets)) {
            events.add(CommitCrimeEvent(ability.controllerId, abilityId, sourceDisplayName))
            newState = recordCrime(newState, ability.controllerId)
        }

        if (targets.isNotEmpty()) {
            events.add(TargetsChosenEvent(ability.controllerId, abilityId, sourceDisplayName))
        }

        // Emit BecomesTargetEvent for each permanent, spell, or player target
        // Use abilityId (the entity on the stack) as source so ward can counter it
        for (target in targets) {
            newState = emitBecomesTarget(
                newState, target, abilityId, ability.controllerId, events, sourceIsSpell = false
            )
        }

        return ExecutionResult.success(
            newState.tick(),
            events
        )
    }

    /**
     * Put a copy of a spell on the stack.
     *
     * Per rule 707.10, a copy of an instant or sorcery spell is itself a spell on the
     * stack with the original's characteristics. We clone the source's [CardComponent] and
     * [SpellOnStackComponent] onto a new entity, tag it with [CopyOfComponent], and push it.
     *
     * Per rule 707.10 a copy isn't cast — this emits a [SpellCopiedEvent], not a
     * [SpellCastEvent], so "whenever you cast a spell" triggers don't fire.
     *
     * Targets and modal choices default to inheriting from the source. Callers may override
     * them (e.g., Storm's per-copy retargeting).
     *
     * [resolvingSpellCopyPayload] is the source spell captured while it was still on the stack
     * ([StackResolver.captureResolvingSpellCopyPayload]); when given, the copy is built from it, so
     * a copy made after the source has left the stack — a resolution-time copy answering its
     * may/retarget decision late, or a cost-linked trigger copying a spell that was countered —
     * still gets the original's characteristics, choices and targets.
     */
    fun putSpellCopy(
        state: GameState,
        sourceSpellId: EntityId,
        targets: List<ChosenTarget> = emptyList(),
        targetRequirements: List<TargetRequirement> = emptyList(),
        chosenModes: List<Int>? = null,
        modeTargetsOrdered: List<List<ChosenTarget>>? = null,
        modeTargetRequirements: Map<Int, List<TargetRequirement>>? = null,
        copyIndex: Int? = null,
        copyTotal: Int? = null,
        controllerId: EntityId? = null,
        modeTargetRequirementsOrdered: List<List<TargetRequirement>>? = null,
        resolvingSpellCopyPayload: ResolvingSpellCopyPayload? = null,
        /** The engine's canonical validator; when given, explicitly retargeted requirements are locked. */
        targetValidator: TargetValidator? = null
    ): ExecutionResult {
        val sourceContainer = state.getEntity(sourceSpellId)
        // CR 707.10: a spell that can't be copied yields no copy. Succeed without change.
        val cantBeCopied = resolvingSpellCopyPayload?.cantBeCopied
            ?: (sourceContainer?.has<com.wingedsheep.engine.state.components.identity.CantBeCopiedComponent>() == true)
        if (cantBeCopied) {
            return ExecutionResult.success(state)
        }
        if (resolvingSpellCopyPayload == null && sourceContainer == null) {
            return ExecutionResult.error(state, "Source spell not found: $sourceSpellId")
        }
        val sourceCard = resolvingSpellCopyPayload?.card ?: sourceContainer?.get<CardComponent>()
            ?: return ExecutionResult.error(state, "Source is not a card: $sourceSpellId")
        val sourceSpell = resolvingSpellCopyPayload?.spell ?: sourceContainer?.get<SpellOnStackComponent>()
            ?: return ExecutionResult.error(state, "Source is not a spell on stack: $sourceSpellId")
        val sourceTargets = resolvingSpellCopyPayload?.targets ?: sourceContainer?.get<TargetsComponent>()

        val (copyId, stateWithId) = state.newEntity()
        val copyController = controllerId ?: sourceSpell.casterId

        val effectiveModes = chosenModes ?: sourceSpell.chosenModes
        val effectiveModeTargets = modeTargetsOrdered ?: sourceSpell.modeTargetsOrdered
        val effectiveModeRequirements = modeTargetRequirements ?: sourceSpell.modeTargetRequirements
        val effectiveModeRequirementsOrdered = modeTargetRequirementsOrdered
            ?: sourceSpell.modeTargetRequirementsOrdered

        val inheritsSourceTargetPayload = chosenModes == null &&
            targets.isEmpty() &&
            targetRequirements.isEmpty() &&
            modeTargetsOrdered == null &&
            modeTargetRequirements == null &&
            modeTargetRequirementsOrdered == null &&
            sourceTargets != null
        val inheritedSourceTargets = sourceTargets.takeIf { inheritsSourceTargetPayload }

        // Determine final flat targets/requirements for the copy's TargetsComponent.
        val effectiveTargets = if (inheritedSourceTargets != null) {
            inheritedSourceTargets.targets
        } else when {
            targets.isNotEmpty() -> targets
            effectiveModes.isNotEmpty() -> effectiveModeTargets.flatten()
            else -> sourceTargets?.targets ?: emptyList()
        }
        val effectiveRequirements = if (inheritedSourceTargets != null) {
            inheritedSourceTargets.targetRequirements
        } else when {
            targetRequirements.isNotEmpty() -> targetRequirements
            effectiveModes.isNotEmpty() ->
                effectiveModes.flatMap { effectiveModeRequirements[it] ?: emptyList() }
            else -> sourceTargets?.targetRequirements ?: emptyList()
        }
        // An inherited copy carries the original choice, including its original object identity.
        // Re-locking against the current state would make a target that left and returned before
        // the copy was created look like a newly chosen object. Explicit retarget paths below
        // recapture the target stamps at the moment of that new choice.
        val lockedRequirements = if (inheritedSourceTargets != null || targetValidator == null) {
            effectiveRequirements
        } else {
            targetValidator.lockRequirementsForTargets(
                state = state,
                targets = effectiveTargets,
                requirements = effectiveRequirements,
                casterId = copyController,
                sourceColors = sourceCard.colors,
                sourceSubtypes = sourceCard.typeLine.subtypes.map { it.value }.toSet(),
                sourceId = copyId,
                targetingSourceType = TargetingSourceType.SPELL,
                xValue = sourceSpell.xValue
            )
        }

        // Clone the card characteristics. The CardComponent keeps the same cardDefinitionId,
        // name, types, colors, mana cost, and spellEffect (707.10).
        val copiedCardComp = sourceCard.copy(ownerId = copyController)

        // Clone cast-time state; per 707.10 the copy inherits every decision made for
        // the original. The data-class copy preserves: xValue, declaredCostSlot, wasBlightPaid,
        // wasWarped, wasEvoked, sacrificedPermanents (snapshots of P/T + subtypes), damageDistribution,
        // chosenCreatureType, exiledCardCount, castFromZone, beheldCards, convokedCreatures (CR 707.10: an
        // effect of the copy that refers to objects used to pay its costs uses the original's).
        // Actual mana payment is not a copied decision: no mana was spent to cast the copy.
        // Clear every payment bucket and provenance map while retaining choices such as X.
        // The caster and modal fields may also change. Payment events (ManaSpentEvent, SpellCastEvent) are
        // deliberately not re-emitted — a copy isn't cast (707.10). For the same reason no mana
        // was spent on the copy, so a mana rider's entry keyword grant stays with the original.
        // A copy built from a captured payload resolves with the effect the original resolved with
        // (its face / kicker / cleave / text-changed variant), carried as the effect override.
        val copiedSpellComp = sourceSpell.copy(
            casterId = copyController,
            entryKeywordGrants = emptyList(),
            manaSpentWhite = 0,
            manaSpentBlue = 0,
            manaSpentBlack = 0,
            manaSpentRed = 0,
            manaSpentGreen = 0,
            manaSpentColorless = 0,
            manaSpentBySubtype = emptyMap(),
            manaSpentByCardType = emptyMap(),
            manaSpentOnXByColor = emptyMap(),
            chosenModes = effectiveModes,
            modeTargetsOrdered = effectiveModeTargets,
            modeTargetRequirements = effectiveModeRequirements,
            modeTargetRequirementsOrdered = effectiveModeRequirementsOrdered,
            resolvingSpellEffectOverride = resolvingSpellCopyPayload?.effectiveSpellEffect
                ?: sourceSpell.resolvingSpellEffectOverride
        )

        var container = ComponentContainer.of(copiedCardComp, copiedSpellComp)
        if (hasTargetPayload(effectiveTargets, lockedRequirements)) {
            container = container.with(
                if (inheritedSourceTargets != null) {
                    TargetsComponent(
                        targets = effectiveTargets,
                        targetRequirements = lockedRequirements,
                        targetEntryStamps = inheritedSourceTargets.targetEntryStamps
                    )
                } else {
                    TargetsComponent.capture(state, effectiveTargets, lockedRequirements)
                }
            )
        }
        container = container.with(
            CopyOfComponent(
                originalCardDefinitionId = sourceCard.cardDefinitionId,
                copiedCardDefinitionId = sourceCard.cardDefinitionId
            )
        )

        var newState = stateWithId.withEntity(copyId, container)
        sourceContainer?.get<com.wingedsheep.engine.mechanics.BestowedComponent>()?.let { bestowed ->
            newState = newState.updateEntity(copyId) { it.with(bestowed.copy(original = bestowed.original.copy(ownerId = copyController))) }
        }
        newState = newState.pushToStack(copyId).copy(priorityPassedBy = emptySet())

        val events = mutableListOf<GameEvent>(
            SpellCopiedEvent(
                copyEntityId = copyId,
                cardName = sourceCard.name,
                controllerId = copyController,
                originalSpellId = sourceSpellId,
                copyIndex = copyIndex,
                copyTotal = copyTotal
            )
        )

        // Emit BecomesTargetEvent for each permanent, spell, or player target — the copy is its own
        // source on the stack (ward on the target can counter the copy independently).
        for (target in effectiveTargets) {
            newState = emitBecomesTarget(newState, target, copyId, copyController, events, sourceIsSpell = true)
        }

        return ExecutionResult.success(newState.tick(), events)
    }

    /**
     * Put an activated ability on the stack.
     *
     * [emitActivationEvent] is true for a genuine activation. A **copy** of an activated ability is
     * *not* activated (CR 707.10), so the copy paths pass false to suppress the
     * [AbilityActivatedEvent] — otherwise placing the copy would itself re-fire
     * "whenever you activate an ability" triggers (e.g. Ertha Jo, Frontier Mentor would copy its own
     * copies endlessly) — and the crime a genuine activation commits (CR 700.13). The copy still
     * becomes a stack object with its own targets, so `BecomesTargetEvent`/`TargetsChosenEvent` are
     * still emitted below.
     */
    fun putActivatedAbility(
        state: GameState,
        ability: ActivatedAbilityOnStackComponent,
        targets: List<ChosenTarget> = emptyList(),
        targetRequirements: List<TargetRequirement> = emptyList(),
        emitActivationEvent: Boolean = true,
        costsTap: Boolean = false,
        isExhaust: Boolean = false,
        cantBeCopied: Boolean = false,
        isLoyalty: Boolean = false,
        loyaltyCountersRemoved: Int = 0,
        /** State at CR 602.2b's announcement/target-selection point, before costs are paid. */
        targetLockState: GameState = state,
        /** The engine's canonical validator; when given, the stored requirements are locked. */
        targetValidator: TargetValidator? = null
    ): ExecutionResult {
        val (abilityId, stateWithId) = state.newEntity()

        // CR 602.2b applies CR 601.2b-i: choices are locked before payment. The stack object is
        // created in the post-payment state, but target requirements and object-identity stamps
        // must come from the pre-payment choice state so a cost cannot rewrite the payload.
        val sourceCard = targetLockState.getEntity(ability.sourceId)?.get<CardComponent>()
        val lockedTargetRequirements = if (targetValidator != null && targets.isNotEmpty()) {
            targetValidator.lockRequirementsForTargets(
                state = targetLockState,
                targets = targets,
                requirements = targetRequirements,
                casterId = ability.controllerId,
                sourceColors = sourceCard?.colors ?: emptySet(),
                sourceSubtypes = sourceCard?.typeLine?.subtypes?.map { it.value }?.toSet() ?: emptySet(),
                sourceId = ability.sourceId,
                xValue = ability.xValue,
                targetingSourceType = TargetingSourceType.ACTIVATED_ABILITY
            )
        } else {
            targetRequirements
        }
        // Capture the source identity from the activation boundary. Genuine activations use the
        // pre-cost target-lock state; copies retain the original activation metadata instead of
        // treating copy placement as a new activation.
        val sourceAuthority = if (emitActivationEvent) {
            AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        } else {
            ability.sourceEndpointAuthority
        }
        val sourceIncarnationStamp = if (emitActivationEvent) {
            targetLockState.objectIdentityStamps[ability.sourceId]
        } else {
            ability.sourceObjectIncarnationStamp
        }
        val lockedAbility = ability.copy(
            sourceEndpointAuthority = sourceAuthority,
            sourceObjectIncarnationStamp = sourceIncarnationStamp,
        )
        var container = ComponentContainer.of(lockedAbility)
        if (hasTargetPayload(targets, lockedTargetRequirements)) {
            container = container.with(
                TargetsComponent.capture(targetLockState, targets, lockedTargetRequirements)
            )
        }
        // CR 707.10e — "This ability can't be copied": tag the ability instance on the stack so a
        // copy-ability effect (e.g. Gogo, Master of Mimicry) makes no copy of it.
        if (cantBeCopied) {
            container = container.with(
                com.wingedsheep.engine.state.components.identity.CantBeCopiedComponent
            )
        }

        var newState = stateWithId.withEntity(abilityId, container)
        newState = newState.pushToStack(abilityId)
            .copy(priorityPassedBy = emptySet())

        val events = mutableListOf<GameEvent>()
        if (emitActivationEvent) {
            // Abilities reaching the stack are never mana abilities (CR 605.3 — mana abilities
            // resolve without the stack). costsTap lets the {T}-in-cost trigger family distinguish
            // tap-cost abilities (which it must skip) from non-tap ones.
            events.add(
                AbilityActivatedEvent(
                    ability.sourceId,
                    ability.sourceName,
                    ability.controllerId,
                    abilityEntityId = abilityId,
                    costsTap = costsTap,
                    isManaAbility = false,
                    isExhaust = isExhaust,
                    isLoyalty = isLoyalty,
                    loyaltyCountersRemoved = loyaltyCountersRemoved,
                )
            )
        }

        // CR 700.13 requires a real activation; an activated-ability copy is not activated (CR 707.10).
        if (emitActivationEvent && CrimeDetector.isCrime(newState, ability.controllerId, targets)) {
            events.add(CommitCrimeEvent(ability.controllerId, abilityId, ability.sourceName))
            newState = recordCrime(newState, ability.controllerId)
        }

        if (targets.isNotEmpty()) {
            events.add(TargetsChosenEvent(ability.controllerId, abilityId, ability.sourceName))
        }

        // Emit BecomesTargetEvent for each permanent, spell, or player target
        // Use abilityId (the entity on the stack) as source so ward can counter it
        for (target in targets) {
            newState = emitBecomesTarget(
                newState, target, abilityId, ability.controllerId, events, sourceIsSpell = false
            )
        }

        return ExecutionResult.success(
            newState.tick(),
            events
        )
    }

    /**
     * Record that [playerId] committed a crime this turn (CR Outlaws of Thunder Junction). Folded
     * in at every [CommitCrimeEvent] emit site so the `PlayerCommittedCrimeThisTurn` condition (e.g.
     * Seize the Secrets' cost reduction) can read it. Cleared at each turn boundary by `TurnManager`.
     */
    fun recordCrime(state: GameState, playerId: EntityId): GameState =
        if (playerId in state.playersWhoCommittedCrimeThisTurn) state
        else state.copy(playersWhoCommittedCrimeThisTurn = state.playersWhoCommittedCrimeThisTurn + playerId)

    /**
     * Emit a [BecomesTargetEvent] for a permanent, spell, or player target (CR 601.2c — "The chosen
     * objects and/or players each become a target of that spell"). A [ChosenTarget.Card] (a card
     * targeted in a non-battlefield zone) still emits nothing: no printed "becomes the target"
     * trigger reaches into those zones, and the trigger side has no vocabulary to ask for it.
     * Returns the updated state.
     *
     * Spell targets are left out of the "targeted by this controller this turn" tracking (Valiant's
     * "first time each turn") and always carry `firstTime = true`: a spell's stack entity can be
     * reused as the resolved permanent's entity, so marking it would leak a stale flag onto the
     * permanent. Permanents and players are tracked; `CleanupPhaseManager` clears the component for
     * every entity, players included.
     *
     * [sourceIsSpell] is required rather than defaulted so every call site has to state whether a
     * spell or an ability did the targeting — `spellsOnly` / `abilitiesOnly` read nothing else.
     */
    fun emitBecomesTarget(
        state: GameState,
        target: ChosenTarget,
        sourceEntityId: EntityId,
        controllerId: EntityId,
        events: MutableList<GameEvent>,
        sourceIsSpell: Boolean
    ): GameState {
        val isSpell = target is ChosenTarget.Spell
        val isPlayer = target is ChosenTarget.Player
        val targetEntityId = when (target) {
            is ChosenTarget.Permanent -> target.entityId
            is ChosenTarget.Spell -> target.spellEntityId
            is ChosenTarget.Player -> target.playerId
            is ChosenTarget.Card -> return state
        }
        val targetName = if (isPlayer) {
            state.getEntity(targetEntityId)?.get<PlayerComponent>()?.name ?: "Unknown"
        } else {
            state.getEntity(targetEntityId)?.get<CardComponent>()?.name ?: "Unknown"
        }
        val firstTime = isSpell || !hasBeenTargetedByController(state, targetEntityId, controllerId)
        events.add(
            BecomesTargetEvent(
                targetEntityId,
                targetName,
                sourceEntityId,
                controllerId,
                firstTime,
                targetIsSpell = isSpell,
                sourceIsSpell = sourceIsSpell,
                targetIsPlayer = isPlayer
            )
        )
        return if (isSpell) state else markTargetedByController(state, targetEntityId, controllerId)
    }

    // =========================================================================
    // Valiant / "first time targeted" tracking
    // =========================================================================

    /**
     * Check if the target entity has already been targeted by the given controller this turn.
     */
    private fun hasBeenTargetedByController(state: GameState, targetId: EntityId, controllerId: EntityId): Boolean {
        val component = state.getEntity(targetId)?.get<TargetedByControllerThisTurnComponent>()
        return component?.hasBeenTargetedBy(controllerId) == true
    }

    /**
     * Mark the target entity as having been targeted by the given controller this turn.
     */
    private fun markTargetedByController(state: GameState, targetId: EntityId, controllerId: EntityId): GameState {
        return state.updateEntity(targetId) { container ->
            val existing = container.get<TargetedByControllerThisTurnComponent>()
                ?: TargetedByControllerThisTurnComponent()
            container.with(existing.withController(controllerId))
        }
    }
}
