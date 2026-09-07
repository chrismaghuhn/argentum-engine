package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Projects the raw event batch from one committed Rules transition for one player.
 *
 * This projector is deliberately not a history accumulator. It consumes one already committed
 * event batch, reuses [Visibility] for zone-level visibility, emits only identity-free semantic
 * facts, and reports every omitted event as either intentionally hidden or unsupported.
 */
internal class PerspectiveEventProjector(
    cardRegistry: CardRegistry,
) {
    private val visibility = Visibility(cardRegistry)

    fun project(
        events: List<GameEvent>,
        perspectivePlayerId: EntityId,
        beforeState: GameState? = null,
        afterState: GameState? = null,
    ): PerspectiveEventProjectionResult {
        require(perspectivePlayerId.value.isNotBlank()) {
            "Perspective event projection requires a perspective player"
        }

        val projected = mutableListOf<ProjectedEvent>()
        val classifications = events.map { event ->
            val rawEventType = event::class.simpleName ?: "UnknownGameEvent"
            when (val decision = projectEvent(event, perspectivePlayerId, beforeState, afterState)) {
                is ProjectionDecision.Emit -> {
                    projected += ProjectedEvent(decision.family, decision.payload)
                    PerspectiveEventClassification(
                        rawEventType = rawEventType,
                        disposition = PerspectiveEventDisposition.EMITTED,
                    )
                }

                is ProjectionDecision.Hidden -> PerspectiveEventClassification(
                    rawEventType = rawEventType,
                    disposition = PerspectiveEventDisposition.INTENTIONALLY_HIDDEN,
                    visibilityRationale = decision.rationale,
                )

                is ProjectionDecision.Unsupported -> PerspectiveEventClassification(
                    rawEventType = rawEventType,
                    disposition = PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY,
                    reason = decision.reason,
                )
            }
        }

        val entries = projected.mapIndexed { ordinal, event ->
            PerspectiveEventV1(
                perspectiveEventOrdinal = ordinal,
                eventFamily = event.family,
                semanticPayload = event.payload,
            )
        }
        return PerspectiveEventProjectionResult(
            batch = PerspectiveEventBatchV1(
                perspectivePlayerId = perspectivePlayerId,
                entries = entries,
            ),
            classifications = classifications,
        )
    }

    private fun projectEvent(
        event: GameEvent,
        perspectivePlayerId: EntityId,
        beforeState: GameState?,
        afterState: GameState?,
    ): ProjectionDecision = when (event) {
        is PhaseChangedEvent -> emit(PerspectiveEventFamily.PHASE_CHANGED) {
            put("phase", event.newPhase.name)
        }

        is StepChangedEvent -> emit(PerspectiveEventFamily.STEP_CHANGED) {
            put("step", event.newStep.name)
        }

        is TurnChangedEvent -> emit(PerspectiveEventFamily.TURN_CHANGED) {
            put("turnNumber", event.turnNumber)
            put("activePlayerRole", playerRole(event.activePlayerId, perspectivePlayerId))
        }

        is DayNightChangedEvent -> emit(PerspectiveEventFamily.DAY_NIGHT_CHANGED) {
            event.oldDesignation?.let { put("oldDesignation", it.name) }
            put("newDesignation", event.newDesignation.name)
        }

        is PriorityChangedEvent -> emit(PerspectiveEventFamily.PRIORITY_CHANGED) {
            put("priorityPlayerRole", playerRole(event.playerId, perspectivePlayerId))
        }

        is LifeChangedEvent -> emit(PerspectiveEventFamily.LIFE_CHANGED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("oldLife", event.oldLife)
            put("newLife", event.newLife)
            put("reason", event.reason.name)
            put("firstThisTurn", event.firstThisTurn)
        }

        is DamageDealtEvent -> if (event.targetIsPlayer) {
            emit(PerspectiveEventFamily.DAMAGE_TO_PLAYER) {
                put("targetRole", playerRole(event.targetId, perspectivePlayerId))
                put("amount", event.amount)
                put("combatDamage", event.isCombatDamage)
            }
        } else {
            unsupported(PerspectiveEventUnsupportedReason.REQUIRES_SEMANTIC_REFERENCE_C)
        }

        // The event itself is public, but this source deliberately omits card name, mana value,
        // X, mode, and payment details: the raw event has no face-down identity witness, so those
        // fields could turn a face-down cast into a hidden card-characteristic leak.
        is SpellCastEvent -> emit(PerspectiveEventFamily.SPELL_CAST) {
            put("casterRole", playerRole(event.casterId, perspectivePlayerId))
        }

        is AbilityActivatedEvent -> emit(PerspectiveEventFamily.ABILITY_ACTIVATED) {
            put("controllerRole", playerRole(event.controllerId, perspectivePlayerId))
            put("costsTap", event.costsTap)
            put("isManaAbility", event.isManaAbility)
            put("isExhaust", event.isExhaust)
        }

        is LandPlayedEvent -> emit(PerspectiveEventFamily.LAND_PLAYED) {
            put("controllerRole", playerRole(event.controllerId, perspectivePlayerId))
        }

        is ZoneChangeEvent -> projectZoneChange(event, perspectivePlayerId, beforeState, afterState)

        is CardsDrawnEvent -> emit(PerspectiveEventFamily.CARDS_DRAWN) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("count", event.count)
        }

        is CardRevealedFromDrawEvent -> emit(PerspectiveEventFamily.CARD_REVEALED_FROM_DRAW) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("isCreature", event.isCreature)
        }

        is DrawFailedEvent -> emit(PerspectiveEventFamily.DRAW_FAILED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
        }

        is CardsDiscardedEvent -> emit(PerspectiveEventFamily.CARDS_DISCARDED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("count", event.cardIds.size)
            put("asCyclingCost", event.asCyclingCost)
        }

        is DiscardRequiredEvent -> emit(PerspectiveEventFamily.DISCARD_REQUIRED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("count", event.count)
        }

        is LibraryShuffledEvent -> emit(PerspectiveEventFamily.LIBRARY_SHUFFLED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
        }

        is LibrarySearchedEvent -> emit(PerspectiveEventFamily.LIBRARY_SEARCHED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
        }

        is ScriedEvent -> emit(PerspectiveEventFamily.SCRY_COMPLETED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("count", event.count)
        }

        is SurveiledEvent -> emit(PerspectiveEventFamily.SURVEIL_COMPLETED) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("count", event.count)
        }

        is HandLookedAtEvent -> if (event.viewingPlayerId == perspectivePlayerId) {
            emit(PerspectiveEventFamily.PRIVATE_HAND_LOOKED_AT) {
                put("targetPlayerRole", playerRole(event.targetPlayerId, perspectivePlayerId))
                put("cardCount", event.cardIds.size)
            }
        } else {
            hidden("Hand-look event is authorized only for its viewing player")
        }

        is HandRevealedEvent -> emit(PerspectiveEventFamily.PUBLIC_HAND_REVEALED) {
            put("revealingPlayerRole", playerRole(event.revealingPlayerId, perspectivePlayerId))
            put("cardCount", event.cardIds.size)
        }

        is CardsRevealedEvent -> if (!event.revealToSelf &&
            event.revealingPlayerId == perspectivePlayerId
        ) {
            hidden("Reveal event explicitly excludes its revealing player")
        } else {
            emit(PerspectiveEventFamily.PUBLIC_CARDS_REVEALED) {
                put("revealingPlayerRole", playerRole(event.revealingPlayerId, perspectivePlayerId))
                put("cardCount", event.cardIds.size)
                event.fromZone?.let { put("fromZone", it.name) }
                event.toZone?.let { put("toZone", it.name) }
            }
        }

        is LookedAtCardsEvent -> if (event.playerId == perspectivePlayerId) {
            emit(PerspectiveEventFamily.PRIVATE_CARDS_LOOKED_AT) {
                put("cardCount", event.cardIds.size)
            }
        } else {
            hidden("Card-look event is authorized only for its viewing player")
        }

        is AttackersDeclaredEvent -> event.attackingPlayerId?.let { attackingPlayerId ->
            emit(PerspectiveEventFamily.ATTACKERS_DECLARED) {
                put("attackingPlayerRole", playerRole(attackingPlayerId, perspectivePlayerId))
                put("attackerCount", event.attackers.size)
            }
        } ?: unsupported(PerspectiveEventUnsupportedReason.UNCHARACTERIZED)

        is BlockersDeclaredEvent -> emit(PerspectiveEventFamily.BLOCKERS_DECLARED) {
            put("blockerCount", event.blockers.size)
            put("blockedAssignmentCount", event.blockers.values.sumOf { it.size })
        }

        is DamageAssignedEvent -> emit(PerspectiveEventFamily.DAMAGE_ASSIGNED) {
            put("assignmentCount", event.assignments.size)
            put("totalDamage", event.assignments.values.sum())
        }

        is CreatureTypeChosenEvent -> emit(PerspectiveEventFamily.CREATURE_TYPE_CHOSEN) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("chosenType", event.chosenType)
        }

        is GameEndedEvent -> emit(PerspectiveEventFamily.GAME_ENDED) {
            put(
                "winnerRole",
                when (event.winnerId) {
                    null -> "DRAW"
                    perspectivePlayerId -> "SELF"
                    else -> "OTHER"
                },
            )
            put("reason", event.reason.name)
        }

        is PlayerLostEvent -> emit(PerspectiveEventFamily.PLAYER_LOST) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("reason", event.reason.name)
        }

        is PlayerLeftGameEvent -> emit(PerspectiveEventFamily.PLAYER_LEFT) {
            put("playerRole", playerRole(event.playerId, perspectivePlayerId))
            put("reason", event.reason.name)
            put("removedObjectCount", event.removedObjectCount)
        }

        is TurnFaceUpEvent -> emit(PerspectiveEventFamily.TURNED_FACE_UP) {
            put("controllerRole", playerRole(event.controllerId, perspectivePlayerId))
        }

        is TurnedFaceDownEvent -> emit(PerspectiveEventFamily.TURNED_FACE_DOWN) {
            put("controllerRole", playerRole(event.controllerId, perspectivePlayerId))
        }

        is TransformedEvent -> emit(PerspectiveEventFamily.TRANSFORMED) {
            put("intoBackFace", event.intoBackFace)
            put("controllerRole", playerRole(event.controllerId, perspectivePlayerId))
        }

        is SpellCopiedEvent -> emit(PerspectiveEventFamily.SPELL_COPIED) {
            put("controllerRole", playerRole(event.controllerId, perspectivePlayerId))
            event.copyIndex?.let { put("copyIndex", it) }
            event.copyTotal?.let { put("copyTotal", it) }
        }

        is ResolvedEvent -> emit(PerspectiveEventFamily.RESOLVED) { }

        else -> unsupportedReasonFor(event)
    }

    private fun projectZoneChange(
        event: ZoneChangeEvent,
        perspectivePlayerId: EntityId,
        beforeState: GameState?,
        afterState: GameState?,
    ): ProjectionDecision {
        val before = beforeState ?: return unsupported(PerspectiveEventUnsupportedReason.EVENT_TIME_STATE_REQUIRED)
        val after = afterState ?: return unsupported(PerspectiveEventUnsupportedReason.EVENT_TIME_STATE_REQUIRED)
        val fromVisible = event.fromZone?.let { zone ->
            visibility.isZoneVisibleTo(
                state = before,
                zoneKey = ZoneKey(event.ownerId, zone),
                viewingPlayerId = perspectivePlayerId,
            )
        } == true
        val toVisible = visibility.isZoneVisibleTo(
            state = after,
            zoneKey = ZoneKey(event.ownerId, event.toZone),
            viewingPlayerId = perspectivePlayerId,
        )
        if (!fromVisible && !toVisible) {
            return hidden("Neither zone endpoint is visible to this perspective")
        }

        return emit(PerspectiveEventFamily.ZONE_CHANGED) {
            put("ownerRole", playerRole(event.ownerId, perspectivePlayerId))
            put("fromZone", event.fromZone?.name ?: "UNKNOWN")
            put("toZone", event.toZone.name)
        }
    }

    private fun unsupportedReasonFor(event: GameEvent): ProjectionDecision = when (event) {
        is LibraryReorderedEvent,
        -> unsupported(PerspectiveEventUnsupportedReason.REQUIRES_KNOWLEDGE_LEDGER_B)

        is DamagePreventedEvent,
        is CardPlayedFromPermissionEvent,
        is StatsModifiedEvent,
        is KeywordGrantedEvent,
        is RingTemptedEvent,
        is EvidenceCollectedEvent,
        is PermanentExploredEvent,
        is ManifestedDreadEvent,
        is CreatureTypeChangedEvent,
        is SpellCounteredEvent,
        is AbilityCounteredEvent,
        is SpellFizzledEvent,
        is AbilityResolvedEvent,
        is SagaChapterResolvedEvent,
        is ReflexiveAbilityTriggeredEvent,
        is TappedEvent,
        is ExertedEvent,
        is BecameSaddledEvent,
        is PermanentAttachedEvent,
        is PermanentUnattachedEvent,
        is LandTappedForManaEvent,
        is UntappedEvent,
        is CreaturesPairedEvent,
        is CreaturesUnpairedEvent,
        is PhasedOutEvent,
        is PhasedInEvent,
        is CountersAddedEvent,
        is CountersRemovedEvent,
        is LoyaltyChangedEvent,
        is PermanentsSacrificedEvent,
        is ExploitedEvent,
        is TrainedEvent,
        is ClassLevelChangedEvent,
        is CreatureDestroyedEvent,
        is ControlChangedEvent,
        is BecomesTargetEvent,
        is CardCycledEvent,
        is CrewOrSaddleContributionEvent,
        is CardPlottedEvent,
        is CardExiledWithMadnessEvent,
        is GiftGivenEvent,
        is CoinFlipEvent,
        is TurnHijackedEvent,
        is RoomFullyUnlockedEvent,
        is DoorUnlockedEvent,
        is DoorLockedEvent,
        -> unsupported(PerspectiveEventUnsupportedReason.REQUIRES_SEMANTIC_REFERENCE_C)

        is AbilityTriggeredEvent,
        is TargetReselectedEvent,
        is AbilityAutoAnsweredEvent,
        is CommitCrimeEvent,
        is TargetsChosenEvent,
        is BlockerOrderDeclaredEvent,
        is AttackerOrderDeclaredEvent,
        is DecisionRequestedEvent,
        is DecisionSubmittedEvent,
        -> unsupported(PerspectiveEventUnsupportedReason.REQUIRES_BOTH_B_AND_C)

        is ManaAddedEvent,
        is ManaSpentEvent,
        is BendPerformedEvent,
        is DiscoveredEvent,
        is MaximumHandSizeRemovedEvent,
        is MaximumHandSizeReducedEvent,
        is CitysBlessingGainedEvent,
        is EnduringStoryGainedEvent,
        is SpeedChangedEvent,
        -> unsupported(PerspectiveEventUnsupportedReason.UNCHARACTERIZED)

        else -> unsupported(PerspectiveEventUnsupportedReason.UNCHARACTERIZED)
    }

    private fun playerRole(playerId: EntityId, perspectivePlayerId: EntityId): String =
        if (playerId == perspectivePlayerId) "SELF" else "OTHER"

    private fun emit(
        family: PerspectiveEventFamily,
        fields: JsonObjectBuilder.() -> Unit,
    ): ProjectionDecision.Emit = ProjectionDecision.Emit(
        family = family,
        payload = buildJsonObject {
            put("type", family.payloadType)
            fields()
        },
    )

    private fun hidden(rationale: String): ProjectionDecision.Hidden =
        ProjectionDecision.Hidden(rationale)

    private fun unsupported(reason: PerspectiveEventUnsupportedReason): ProjectionDecision.Unsupported =
        ProjectionDecision.Unsupported(reason)

    private data class ProjectedEvent(
        val family: PerspectiveEventFamily,
        val payload: JsonObject,
    )

    private sealed interface ProjectionDecision {
        data class Emit(
            val family: PerspectiveEventFamily,
            val payload: JsonObject,
        ) : ProjectionDecision

        data class Hidden(val rationale: String) : ProjectionDecision

        data class Unsupported(val reason: PerspectiveEventUnsupportedReason) : ProjectionDecision
    }
}
