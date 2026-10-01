package com.wingedsheep.engine.mechanics.stack

import com.wingedsheep.engine.state.components.identity.TextChanges
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.permanent.types.restoreDfcFrontFace
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.replacement.PendingGameEvent
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.AfterResolveDestinationComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.*
import com.wingedsheep.engine.state.nameVisibleToAll
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.*

/**
 * Resolves a spell (CR 608.2): re-checks its targets (CR 608.2b), fizzles it when every target has
 * become illegal, and otherwise routes it to [PermanentSpellResolver] or [NonPermanentSpellResolver]
 * by the type line of the face it was cast as.
 */
internal class SpellResolver(
    private val zones: ZoneTransitionService,
    private val cardRegistry: CardRegistry,
    private val predicateEvaluator: PredicateEvaluator,
    private val targetValidator: ResolutionTargetValidator,
    private val permanentSpellResolver: PermanentSpellResolver,
    private val nonPermanentSpellResolver: NonPermanentSpellResolver
) {
    /**
     * Resolve a spell.
     */
    fun resolveSpell(
        state: GameState,
        spellId: EntityId,
        container: ComponentContainer
    ): ExecutionResult {
        val cardComponent = container.get<CardComponent>()
        val spellComponent = container.get<SpellOnStackComponent>()!!
        val targetsComponent = container.get<TargetsComponent>()

        // Validate targets if spell has any (including protection check - Rule 702.16)
        val sourceColors = cardComponent?.colors ?: emptySet()
        val sourceSubtypes = cardComponent?.typeLine?.subtypes?.map { it.value }?.toSet() ?: emptySet()
        // `resolvedTargets` is the compacted (drop-illegal) list used as `context.targets`
        // — same shape every executor has always seen. `alignedResolvedTargets` is a parallel
        // list the same length as the originally-chosen targets, with `null` in slots whose
        // target was dropped by 608.2b validation. It is forwarded to `buildNamedTargets`
        // so a sub-effect that references a now-illegal target through its declared
        // [EffectTarget.BoundVariable] (e.g. Diplomatic Relations' `myCreature` after its
        // FROM creature dies in response) resolves to `null` and fizzles, instead of
        // silently consuming the NEXT still-valid target whose position shifted forward
        // in the compacted list.
        val resolvedTargets: List<ChosenTarget>
        val alignedResolvedTargets: List<ChosenTarget?>
        // A malformed locked payload (a mandatory slot left without a target) is still a target
        // payload: it is re-checked, and rejected, rather than resolving as targetless.
        if (targetsComponent?.hasResolutionTargetPayload() == true) {
            // 608.2b re-checks targets against the spell's text as it is now (CR 613.1c) — the
            // stack keeps the printed requirements, so a text change that began or ended while
            // the spell waited is honoured.
            val spellText = TextChanges.forSpell(state, spellId)
            val resolutionPayload = targetValidator.filterAtResolution(
                state = state,
                targets = targetsComponent.targets,
                requirements = targetsComponent.targetRequirements
                    .map { req -> spellText?.let { req.applyTextReplacement(it) } ?: req },
                casterId = spellComponent.casterId,
                sourceColors = sourceColors,
                sourceSubtypes = sourceSubtypes,
                sourceId = spellId,
                xValue = spellComponent.xValue,
                targetEntryStamps = targetsComponent.targetEntryStamps,
                targetingSourceType = TargetingSourceType.SPELL
            )
            if (resolutionPayload.targets.isEmpty()) {
                if (container.has<com.wingedsheep.engine.mechanics.BestowedComponent>()) {
                    val restored = com.wingedsheep.engine.mechanics.BestowCasts.end(state, spellId)
                        .updateEntity(spellId) { it.without<TargetsComponent>() }
                    return resolveSpell(restored, spellId, restored.getEntity(spellId)!!)
                }
                // All targets invalid - spell fizzles
                return fizzleSpell(state, spellId, cardComponent, spellComponent)
            }
            resolvedTargets = resolutionPayload.targets
            alignedResolvedTargets = resolutionPayload.alignedTargets
        } else {
            resolvedTargets = targetsComponent?.targets ?: emptyList()
            alignedResolvedTargets = resolvedTargets
        }

        var newState = state
        val events = mutableListOf<GameEvent>()

        // Check if permanent or non-permanent.
        // Adventure / split face cast (CR 715 / 709) — when the spell was cast as a face, route
        // resolution by the face's type line. An Adventure (instant/sorcery) face on a creature
        // card must take the non-permanent path even though the card's primary characteristics
        // describe a creature.
        val faceTypeLine = spellComponent.faceIndex?.let { idx ->
            val def = cardComponent?.let { cardRegistry.getCard(it.name) }
            def?.cardFaces?.getOrNull(idx)?.typeLine
        }
        val resolvedTypeLine = faceTypeLine ?: cardComponent?.typeLine
        val isPermanent = resolvedTypeLine?.isPermanent ?: false

        if (isPermanent) {
            // Put permanent on battlefield
            val permanentResult = permanentSpellResolver.resolvePermanentSpell(newState, spellId, spellComponent, cardComponent)
            if (permanentResult.outcome is Outcome.Paused) {
                return ExecutionResult.propagatePause(
                    permanentResult.state,
                    events + permanentResult.events,
                    diagnostics = permanentResult.diagnostics
                )
            }
            newState = permanentResult.state
            events.addAll(permanentResult.events)
            // CR 708.2a — a permanent that entered face down has no name, so neither the
            // "resolved" line nor the "entered the battlefield" line may carry the printed one.
            // The cast line has its own event-time client presentation; leaving these two
            // audience-agnostic log events unmasked made the log contradict it and told the
            // opponent exactly what they were looking at.
            // Read the resolved entity rather than `spellComponent.castFaceDown` so every route
            // that lands a permanent face down is covered, not only a face-down cast.
            val permanentName = nameVisibleToAll(newState, spellId, cardComponent?.name ?: "Unknown")
            events.add(ResolvedEvent(spellId, permanentName))
            return ExecutionResult.success(newState, events, diagnostics = permanentResult.diagnostics)
        } else {
            // Execute effects; the final non-permanent disposition follows only once resolution drains.
            val effectResult = nonPermanentSpellResolver.resolveNonPermanentSpell(
                newState, spellId, spellComponent, cardComponent,
                resolvedTargets,
                alignedResolvedTargets
            )
            if (effectResult.outcome is Outcome.Paused) {
                // The spell remains on the stack until its final continuation completes; the
                // disposition and the ResolvedEvent are emitted only after the continuation chain
                // drains.
                val allEvents = events + effectResult.events
                return ExecutionResult.propagatePause(
                    effectResult.state,
                    allEvents,
                    diagnostics = effectResult.diagnostics
                )
            }
            newState = effectResult.newState
            events.addAll(effectResult.events)
            // A resolution that stopped fail-closed on an unsupported path (its diagnostics say why)
            // never reached its final disposition, so it reports no ResolvedEvent; the diagnostics
            // carry the failure to the trusted caller.
            if (effectResult.outcome !is Outcome.Rejected) {
                events.add(ResolvedEvent(spellId, cardComponent?.name ?: "Unknown"))
            }
            return ExecutionResult.success(newState, events, diagnostics = effectResult.diagnostics)
        }
    }

    /**
     * Spell fizzles because all targets are invalid (CR 608.2b): it is removed from the stack and put
     * into its owner's graveyard — or wherever a replacement sends it.
     *
     * A copy of a spell takes the same route: CR 608.2b puts it into the graveyard like any other
     * spell, and the phantom-copy state-based action then makes it cease to exist there
     * ([com.wingedsheep.engine.mechanics.sba.zone.PhantomCardCopiesCheck]).
     */
    private fun fizzleSpell(
        state: GameState,
        spellId: EntityId,
        cardComponent: CardComponent?,
        spellComponent: SpellOnStackComponent
    ): ExecutionResult {
        val ownerId = cardComponent?.ownerId ?: spellComponent.casterId
        val cardDef = cardComponent?.let { cardRegistry.getCard(it.name) }
        // Flashback (printed or granted — Archmage's Newt) or Harmonize (printed or granted —
        // Songcrafter Mage): a graveyard cast exiles on resolution instead of returning to the
        // graveyard. The alternative cost recorded at cast time is the authority, so a spell cast
        // *with* flashback is exiled even if a conditional flashback's condition has since lapsed
        // (Viral Spawning) or a granting permanent left in the meantime.
        val flashbackExile = SpellZoneMoves.exilesAfterGraveyardCast(
            state, spellId, spellComponent, cardDef, cardRegistry, predicateEvaluator
        )
        val exileAfterResolveComp = state.getEntity(spellId)?.get<AfterResolveDestinationComponent>()
        // Goliath Daydreamer-style components only redirect on actual resolution; if the spell
        // fizzles or is countered they go to graveyard normally.
        val riderOnFizzle = exileAfterResolveComp?.takeIf { !it.onlyIfResolved }
        // A fizzled spell heading to its owner's graveyard is a card put into a graveyard
        // "from anywhere" — honor RedirectZoneChange replacements (Valgavoth, Leyline).
        val fizzleRedirect = if (flashbackExile || riderOnFizzle != null) {
            com.wingedsheep.engine.handlers.effects.ZoneChangeRedirectResult(
                riderOnFizzle?.zone ?: Zone.EXILE
            )
        } else {
            com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
                .checkZoneChangeRedirect(state, spellId, Zone.STACK, Zone.GRAVEYARD, predicateEvaluator = predicateEvaluator)
        }

        // If an ordinary replacement redirects the fizzle into hand/library, let the pending
        // event pipeline ask the commander owner about 903.9b before the card leaves the stack,
        // and only report the fizzle once the physical move has happened. Graveyard/exile keep the
        // direct path so the post-move 903.9a behaviour is unchanged.
        val replacementRedirectsToHandOrLibrary = !flashbackExile && riderOnFizzle == null &&
            (fizzleRedirect.destinationZone == Zone.HAND || fizzleRedirect.destinationZone == Zone.LIBRARY)
        if (replacementRedirectsToHandOrLibrary) {
            val pendingMove = zones.moveToZoneWithReplacements(
                state = state,
                entityId = spellId,
                destinationZone = Zone.GRAVEYARD,
                fromZoneKey = ZoneKey(ownerId, Zone.STACK),
                context = EffectContext(
                    sourceId = spellId,
                    controllerId = spellComponent.casterId,
                ),
                completion = PendingGameEvent.StackSpellDispositionZoneChangeCompletion(
                    fizzled = true,
                    cardName = cardComponent?.name ?: "Unknown",
                    reason = "All targets are invalid",
                ),
            )
            if (pendingMove.outcome is Outcome.Paused) {
                return ExecutionResult.propagatePause(pendingMove.state, pendingMove.events, diagnostics = pendingMove.diagnostics)
            }
            if (pendingMove.error != null) return pendingMove.toExecutionResult()
            return ExecutionResult.success(
                pendingMove.state,
                listOf(SpellFizzledEvent(spellId, cardComponent?.name ?: "Unknown", "All targets are invalid")) +
                    pendingMove.events,
                diagnostics = pendingMove.diagnostics,
            )
        }

        val destZone = fizzleRedirect.destinationZone
        val destZoneKey = ZoneKey(ownerId, destZone)

        var newState = state.updateEntity(spellId) { c ->
            c.without<SpellOnStackComponent>()
                .without<TextReplacementComponent>()
                .without<TargetsComponent>()
        }
        newState = newState.addToZone(destZoneKey, spellId)
        // CR 712.8a — a fizzled card cast transformed is front face up again once off the stack.
        newState = restoreDfcFrontFace(newState, cardRegistry, spellId)
        val destinationObject = newState.objectRef(spellId)
        // A card-intrinsic redirect into the library shuffles the card in (Progenitus).
        if (destZone == Zone.LIBRARY && fizzleRedirect.shuffleIntoLibrary) {
            newState = SpellZoneMoves.shuffleOwnerLibrary(newState, ownerId)
        }
        if (destZone == Zone.EXILE && fizzleRedirect.linkSourceId != null) {
            newState = com.wingedsheep.engine.handlers.effects.ZoneMovementUtils
                .linkExiledToSource(newState, spellId, fizzleRedirect.linkSourceId)
        }

        return ExecutionResult.success(
            newState,
            listOf(
                SpellFizzledEvent(spellId, cardComponent?.name ?: "Unknown", "All targets are invalid"),
                ZoneChangeEvent(
                    spellId,
                    cardComponent?.name ?: "Unknown",
                    Zone.STACK,
                    destZone,
                    ownerId, oldObject = state.objectRef(spellId), newObject = destinationObject
                )
            )
        )
    }
}
