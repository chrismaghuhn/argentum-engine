package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.FACE_DOWN_DISPLAY_NAME
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

class DecisionEnricher(private val cardRegistry: CardRegistry) {
    private val visibility = Visibility(cardRegistry, conditionEvaluator = PredicateEvaluator(cardRegistry = null).conditions)

    /**
     * Whether [entityId]'s real name must be hidden from [viewerId]. The engine visibility authority
     * combines controller access with explicit reveals and effect-granted access; this presenter only
     * chooses the generic label once that semantic answer is known. A spectator controls no seat,
     * so a face-down identity is always private to them.
     */
    private fun isHiddenFrom(
        state: GameState,
        entityId: EntityId,
        viewerId: EntityId?,
        isSpectator: Boolean = false,
    ): Boolean = state.getEntity(entityId)?.has<FaceDownComponent>() == true &&
        (isSpectator || viewerId == null ||
            !visibility.isCardIdentityVisibleTo(state, Zone.BATTLEFIELD, entityId, viewerId))

    /**
     * The source name to display for [decision] to [viewerId]. The combat board copies the (single)
     * attacker's real name into [DecisionContext.sourceName]; mask it when the viewer isn't its
     * controller. The multi-attacker board already uses a generic "Combat damage" label.
     */
    /**
     * The art to display for [entityId].
     *
     * Reads the entity's own [CardComponent.imageUri], which `CardEntityFactory` stamps from the
     * printing the player actually put in their deck. Re-deriving it from the canonical
     * [com.wingedsheep.sdk.model.CardDefinition] metadata instead (as this used to) shows the
     * *original* printing's art for every reprint, so a card in a search/reveal prompt didn't match
     * the same card in hand or on the battlefield — both of which read the component. The definition
     * lookup remains only as a fallback for entities with no image stamped.
     */
    private fun imageUriFor(state: GameState, entityId: EntityId): String? {
        val cardComponent = state.getEntity(entityId)?.get<CardComponent>() ?: return null
        return cardComponent.imageUri
            ?: cardRegistry.getCard(cardComponent.cardDefinitionId)?.metadata?.imageUri
    }

    private fun maskedSourceName(decision: PendingDecision, state: GameState, viewerId: EntityId): String? =
        maskedSourceName(decision, state, viewerId, isSpectator = false)

    /**
     * Project the source label used by the spectator status bar. A spectator
     * controls no seat, so a face-down combat source is always private to them.
     * This reuses the same combat/source relationship as player-facing status.
     */
    fun maskedSpectatorSourceName(decision: PendingDecision, state: GameState): String? =
        maskedSourceName(decision, state, viewerId = null, isSpectator = true)

    private fun maskedSourceName(
        decision: PendingDecision,
        state: GameState,
        viewerId: EntityId?,
        isSpectator: Boolean,
    ): String? {
        val sourceName = decision.context.sourceName ?: return null
        val sourceId = decision.context.sourceId
        if (sourceId != null && isIdentityHiddenFrom(state, sourceId, viewerId, isSpectator)) {
            return if (decision is CombatResolutionDecision && sourceName != "Combat damage") {
                FACE_DOWN_DISPLAY_NAME
            } else {
                null
            }
        }
        if (decision is CombatResolutionDecision) {
            val single = decision.attackers.singleOrNull() ?: return sourceName
            if (single.name == sourceName && isHiddenFrom(state, single.id, viewerId, isSpectator)) {
                return FACE_DOWN_DISPLAY_NAME
            }
        }
        return sourceName
    }

    /**
     * The source id the spectator decision banner carries, from which the client draws the source's
     * art out of its own masked state. A spectator gets no per-seat card names, so a source sitting
     * where the spectator can't look (a hand, a library, a sideboard) is not referenced at all: the
     * client couldn't draw it anyway, and the raw id would be a handle onto a hidden card. A
     * face-down permanent or spell keeps its id, since that handle is already on the public board.
     */
    fun spectatorSourceId(decision: PendingDecision, state: GameState): String? {
        val sourceId = decision.context.sourceId ?: return null
        val zoneKey = zoneKeyOf(state, sourceId) ?: return sourceId.value
        val viewer = nominalSpectatorViewer(state) ?: return null
        val referenceable = visibility.isZoneVisibleTo(state, zoneKey, viewer, isSpectator = true) ||
            visibility.isCardIdentityVisibleTo(state, zoneKey, sourceId, viewer, isSpectator = true)
        return if (referenceable) sourceId.value else null
    }

    /**
     * Source identities are public only when the source object is public to the
     * viewer. Hidden-zone cards and face-down public-zone cards are not. The engine
     * [Visibility] authority answers for the source's actual zone; a spectator holds no seat,
     * so it is asked as a spectator, where the nominal seat only names the zone and never
     * grants access.
     */
    private fun isIdentityHiddenFrom(
        state: GameState,
        entityId: EntityId,
        viewerId: EntityId?,
        isSpectator: Boolean,
    ): Boolean {
        val zoneKey = zoneKeyOf(state, entityId) ?: return false
        val viewer = viewerId ?: nominalSpectatorViewer(state) ?: return true
        return !visibility.isCardIdentityVisibleTo(
            state,
            zoneKey,
            entityId,
            viewer,
            isSpectator = isSpectator || viewerId == null,
        )
    }

    /**
     * Where [entityId] currently is, keyed the way [Visibility] expects: the stack (kept outside
     * [GameState.zones]) by caster, everything else by its zone entry. Null when the entity is gone
     * or sits in no zone at all; such a source names nothing hidden.
     */
    private fun zoneKeyOf(state: GameState, entityId: EntityId): ZoneKey? {
        val container = state.getEntity(entityId) ?: return null
        if (entityId in state.stack) {
            val casterId = container.get<SpellOnStackComponent>()?.casterId
                ?: state.projectedState.getController(entityId)
                ?: return null
            return ZoneKey(casterId, Zone.STACK)
        }
        return state.zones.entries.firstOrNull { (_, ids) -> entityId in ids }?.key
    }

    /** Any seated player: asked with `isSpectator = true`, the visibility authority grants it nothing. */
    private fun nominalSpectatorViewer(state: GameState): EntityId? = state.turnOrder.firstOrNull()

    fun enrich(decision: PendingDecision, state: GameState, viewerId: EntityId): PendingDecision {
        return when (decision) {
            is SearchLibraryDecision -> decision.copy(
                cards = decision.cards.mapValues { (entityId, cardInfo) ->
                    cardInfo.copy(imageUri = imageUriFor(state, entityId))
                }
            )
            is ReorderLibraryDecision -> decision.copy(
                cardInfo = decision.cardInfo.mapValues { (entityId, cardInfo) ->
                    cardInfo.copy(imageUri = imageUriFor(state, entityId))
                }
            )
            is SelectCardsDecision -> decision.copy(
                cardInfo = decision.cardInfo?.mapValues { (entityId, cardInfo) ->
                    cardInfo.copy(imageUri = imageUriFor(state, entityId))
                }
            )
            is OrderObjectsDecision -> decision.copy(
                cardInfo = decision.cardInfo?.mapValues { (entityId, cardInfo) ->
                    // Don't enrich face-down creatures - would leak their identity
                    if (state.getEntity(entityId)?.has<FaceDownComponent>() == true) cardInfo
                    else cardInfo.copy(imageUri = imageUriFor(state, entityId))
                }
            )
            is SplitPilesDecision -> decision.copy(
                cardInfo = decision.cardInfo?.mapValues { (entityId, cardInfo) ->
                    cardInfo.copy(imageUri = imageUriFor(state, entityId))
                }
            )
            is CombatResolutionDecision -> {
                // The combat-damage board is shown to every chooser (the attacker assigns its damage,
                // the defender assigns any blocker damage), so a face-down creature's real name would
                // leak to the opponent through a shared node. Mask per viewer: the controller keeps
                // its own creature's name, everyone else sees the generic label.
                val maskedAttackers = decision.attackers.map {
                    if (isHiddenFrom(state, it.id, viewerId)) it.copy(name = FACE_DOWN_DISPLAY_NAME) else it
                }
                val maskedBlockers = decision.blockers.map {
                    if (isHiddenFrom(state, it.id, viewerId)) it.copy(name = FACE_DOWN_DISPLAY_NAME) else it
                }
                // The single-attacker prompt embeds that attacker's name; mask it in lockstep.
                val single = decision.attackers.singleOrNull()
                val maskedPrompt = if (single != null && isHiddenFrom(state, single.id, viewerId)) {
                    decision.prompt.replaceFirst(single.name, FACE_DOWN_DISPLAY_NAME)
                } else {
                    decision.prompt
                }
                decision.copy(
                    attackers = maskedAttackers,
                    blockers = maskedBlockers,
                    prompt = maskedPrompt,
                    context = decision.context.copy(sourceName = maskedSourceName(decision, state, viewerId)),
                )
            }
            // Other decision types don't have card info to enrich
            else -> decision
        }
    }

    fun createOpponentDecisionStatus(
        decision: PendingDecision,
        state: GameState,
        viewerId: EntityId,
    ): ServerMessage.OpponentDecisionStatus {
        val displayText = when (decision) {
            is SelectCardsDecision -> "Selecting cards"
            is ChooseTargetsDecision -> "Choosing targets"
            is YesNoDecision -> "Making a choice"
            is BatchYesNoDecision -> "Making a choice"
            is ChooseModeDecision -> "Choosing mode"
            is ChooseColorDecision -> "Choosing a color"
            is ChooseNumberDecision -> "Choosing a number"
            is DistributeDecision -> "Distributing"
            is OrderObjectsDecision -> "Ordering blockers"
            is SplitPilesDecision -> "Splitting piles"
            is SearchLibraryDecision -> "Searching library"
            is ReorderLibraryDecision -> "Reordering cards"
            is AssignDamageDecision -> "Assigning damage"
            is CombatResolutionDecision -> "Assigning combat damage"
            is ChooseOptionDecision -> "Making a choice"
            is ChooseReplacementDecision -> "Changing text"
            is BudgetModalDecision -> "Choosing modes"
            is SelectManaSourcesDecision -> "Selecting mana sources"
        }
        return ServerMessage.OpponentDecisionStatus(
            playerId = decision.playerId.value,
            decisionType = decision::class.simpleName ?: "Unknown",
            displayText = displayText,
            sourceName = maskedSourceName(decision, state, viewerId),
            sourceId = decision.context.sourceId?.value
        )
    }
}
