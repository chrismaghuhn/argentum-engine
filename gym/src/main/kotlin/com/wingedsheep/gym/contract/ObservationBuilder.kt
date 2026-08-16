package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.ForetellCard
import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SuspendCardFromHand
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.engine.state.components.stack.AbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/**
 * Converts `(GameState, perspectivePlayerId)` into a [TrainingObservation].
 *
 * ## Information hiding
 *
 * Opponent hand and everyone's library are always perspective-masked
 * ([ZoneView.hidden] = true, [ZoneView.cards] empty when no individual card is
 * visible). There is no production bypass for this boundary.
 *
 * ## Projected vs. base state
 *
 * All per-entity fields (types, subtypes, colors, keywords, power, toughness,
 * controller) are read from [GameState.projectedState] so Rule 613 continuous
 * effects are reflected. The zone a card sits in still comes from the base
 * zone map (control-changing effects don't move cards between owner-keyed
 * zones — see `GameState.getBattlefield`).
 */
class ObservationBuilder(
    private val schemaHash: String = SchemaHash.CURRENT,
    cardRegistry: CardRegistry = CardRegistry()
) {
    private val visibility = Visibility(cardRegistry)

    fun build(
        state: GameState,
        perspectivePlayerId: EntityId,
        legalActions: List<LegalAction>
    ): ObservationResult {
        val players = state.turnOrder.map { buildPlayerView(state, it, perspectivePlayerId) }

        val zones = buildZones(state, perspectivePlayerId)

        val agentToAct = state.pendingDecision?.playerId ?: state.priorityPlayerId
        val mayReceiveActions = !state.gameOver && perspectivePlayerId == agentToAct

        val stack = state.stack.map { entityId ->
            buildStackItem(state, entityId, perspectivePlayerId)
        }

        val pendingDecisionAndRegistry = state.pendingDecision
            ?.let { buildPendingDecision(it, mayReceiveActions) }
        val pendingDecisionView = pendingDecisionAndRegistry?.first
        val decisionRegistry = pendingDecisionAndRegistry?.second ?: ActionRegistry.EMPTY

        // Build legal-action views and their registry. When mid-decision the
        // engine's `legalActions` is empty — we use the decision options instead.
        val legalActionViews: List<LegalActionView>
        val actionRegistry: ActionRegistry
        if (!mayReceiveActions) {
            legalActionViews = emptyList()
            actionRegistry = ActionRegistry.EMPTY
        } else if (state.pendingDecision != null) {
            val responses = decisionRegistry.decisionResponses.map { it.second }
            legalActionViews = buildDecisionOptionViews(state.pendingDecision!!, responses)
            actionRegistry = decisionRegistry
        } else {
            legalActionViews = legalActions.mapIndexed { idx, la -> legalActionToView(idx, la) }
            actionRegistry = ActionRegistry.ofLegalActions(legalActions)
        }

        val obs = TrainingObservation(
            schemaHash = schemaHash,
            perspectivePlayerId = perspectivePlayerId,
            agentToAct = agentToAct,
            turnNumber = state.turnNumber,
            phase = state.phase,
            step = state.step,
            activePlayerId = state.activePlayerId,
            priorityPlayerId = state.priorityPlayerId,
            players = players,
            zones = zones,
            stack = stack,
            pendingDecision = pendingDecisionView,
            legalActions = legalActionViews,
            terminated = state.gameOver,
            winnerId = state.winnerId,
            stateDigest = ""
        )
        val digested = obs.copy(stateDigest = StateDigest.compute(obs))
        return ObservationResult(digested, actionRegistry)
    }

    // =========================================================================
    // Players
    // =========================================================================

    private fun buildPlayerView(
        state: GameState,
        playerId: EntityId,
        perspectivePlayerId: EntityId
    ): PlayerView {
        val container = state.getEntity(playerId)
        val playerComp = container?.get<PlayerComponent>()
        val life = container?.get<LifeTotalComponent>()?.life ?: 0
        val manaPool = container?.get<ManaPoolComponent>()
        val hasLost = container?.get<PlayerLostComponent>() != null

        return PlayerView(
            id = playerId,
            name = playerComp?.name ?: playerId.value,
            lifeTotal = life,
            handSize = state.getHand(playerId).size,
            librarySize = state.getLibrary(playerId).size,
            graveyardSize = state.getGraveyard(playerId).size,
            exileSize = state.getExile(playerId).size,
            manaPool = manaPool?.let {
                ManaPoolView(
                    white = it.white,
                    blue = it.blue,
                    black = it.black,
                    red = it.red,
                    green = it.green,
                    colorless = it.colorless
                )
            } ?: ManaPoolView(),
            isPerspective = playerId == perspectivePlayerId,
            isActive = playerId == state.activePlayerId,
            hasPriority = playerId == state.priorityPlayerId,
            hasLost = hasLost
        )
    }

    // =========================================================================
    // Zones
    // =========================================================================

    private fun buildZones(
        state: GameState,
        perspectivePlayerId: EntityId
    ): List<ZoneView> {
        // Emit a view for every (player, zone) in turn order so trainers see a
        // consistent shape regardless of whether a zone happens to be empty.
        val perPlayerZones = listOf(
            Zone.HAND,
            Zone.LIBRARY,
            Zone.GRAVEYARD,
            Zone.EXILE,
            Zone.BATTLEFIELD,
            Zone.COMMAND
        )
        val views = mutableListOf<ZoneView>()
        for (playerId in state.turnOrder) {
            for (zone in perPlayerZones) {
                val key = ZoneKey(playerId, zone)
                val ids = state.getZone(key)
                val cards = ids.mapNotNull { entityId ->
                    when (cardVisibility(state, key, entityId, perspectivePlayerId, zone)) {
                        CardVisibility.HIDDEN -> null
                        CardVisibility.VISIBLE_IDENTITY -> buildEntityFeatures(state, entityId, zone)
                        CardVisibility.PUBLIC_FACE_DOWN_ONLY ->
                            buildEntityFeatures(state, entityId, zone, maskFaceDownIdentity = true)
                    }
                }
                views += ZoneView(
                    ownerId = playerId,
                    zoneType = zone,
                    hidden = cards.size != ids.size,
                    size = ids.size,
                    cards = cards
                )
            }
        }
        return views
    }

    private enum class CardVisibility {
        HIDDEN,
        VISIBLE_IDENTITY,
        PUBLIC_FACE_DOWN_ONLY
    }

    private fun cardVisibility(
        state: GameState,
        key: ZoneKey,
        entityId: EntityId,
        perspective: EntityId,
        zone: Zone
    ): CardVisibility {
        if (zone == Zone.LIBRARY) return CardVisibility.HIDDEN
        if (!visibility.isZoneVisibleTo(state, key, perspective)) return CardVisibility.HIDDEN

        val container = state.getEntity(entityId) ?: return CardVisibility.HIDDEN
        if (!container.has<FaceDownComponent>()) return CardVisibility.VISIBLE_IDENTITY
        if (visibility.isCardRevealedTo(state, entityId, perspective)) {
            return CardVisibility.VISIBLE_IDENTITY
        }

        val controller = state.projectedState.getController(entityId)
            ?: container.get<ControllerComponent>()?.playerId
        if (zone == Zone.BATTLEFIELD &&
            controller == perspective &&
            visibility.hasLookAtFaceDownCreatures(state, perspective)
        ) {
            return CardVisibility.VISIBLE_IDENTITY
        }

        if (zone == Zone.EXILE) return CardVisibility.HIDDEN
        return CardVisibility.PUBLIC_FACE_DOWN_ONLY
    }

    // =========================================================================
    // Entities
    // =========================================================================

    private fun buildEntityFeatures(
        state: GameState,
        entityId: EntityId,
        zone: Zone,
        maskFaceDownIdentity: Boolean = false
    ): EntityFeatures {
        val container = state.getEntity(entityId) ?: ComponentContainer.EMPTY
        val card = container.get<CardComponent>()
        val projected = state.projectedState
        val pv = projected.getProjectedValues(entityId)

        val onBattlefield = zone == Zone.BATTLEFIELD
        val publicFaceDown = maskFaceDownIdentity && container.has<FaceDownComponent>()

        val types: Set<String> = when {
            publicFaceDown && onBattlefield -> pv?.types?.toSet() ?: setOf("CREATURE")
            publicFaceDown -> emptySet()
            pv != null -> pv.types.toSet()
            card != null -> card.typeLine.cardTypes.mapTo(mutableSetOf()) { it.name }
            else -> emptySet()
        }
        val subtypes: Set<String> = when {
            publicFaceDown -> if (onBattlefield) pv?.subtypes?.toSet() ?: emptySet() else emptySet()
            pv != null -> pv.subtypes.toSet()
            card != null -> card.typeLine.subtypes.mapTo(mutableSetOf()) { it.value }
            else -> emptySet()
        }
        val colors: Set<String> = when {
            publicFaceDown -> if (onBattlefield) pv?.colors?.toSet() ?: emptySet() else emptySet()
            pv != null -> pv.colors.toSet()
            card != null -> card.colors.mapTo(mutableSetOf()) { it.name }
            else -> emptySet()
        }
        val keywords: Set<String> = when {
            publicFaceDown -> if (onBattlefield) pv?.keywords?.toSet() ?: emptySet() else emptySet()
            pv != null -> pv.keywords.toSet()
            card != null -> card.baseKeywords.mapTo(mutableSetOf()) { it.name }
            else -> emptySet()
        }

        val sortedTypes = types.sorted().toCollection(LinkedHashSet())
        val sortedSubtypes = subtypes.sorted().toCollection(LinkedHashSet())
        val sortedColors = colors.sorted().toCollection(LinkedHashSet())
        val sortedKeywords = keywords.sorted().toCollection(LinkedHashSet())
        val ownerId = container.get<OwnerComponent>()?.playerId ?: card?.ownerId

        return EntityFeatures(
            entityId = entityId,
            cardDefinitionId = if (publicFaceDown) null else card?.cardDefinitionId,
            name = if (publicFaceDown) {
                if (onBattlefield) "Face-down permanent" else "Face-down card"
            } else {
                card?.name ?: ""
            },
            zone = zone,
            ownerId = ownerId,
            controllerId = if (onBattlefield) projected.getController(entityId) else null,
            types = sortedTypes,
            subtypes = sortedSubtypes,
            colors = sortedColors,
            keywords = sortedKeywords,
            manaCost = if (publicFaceDown) "" else card?.manaCost?.toString() ?: "",
            manaValue = if (publicFaceDown) 0 else card?.manaValue ?: 0,
            oracleText = if (publicFaceDown) "" else card?.oracleText ?: "",
            power = if (onBattlefield) {
                if (publicFaceDown) pv?.power ?: 2 else projected.getPower(entityId)
            } else null,
            toughness = if (onBattlefield) {
                if (publicFaceDown) pv?.toughness ?: 2 else projected.getToughness(entityId)
            } else null,
            tapped = onBattlefield && container.get<TappedComponent>() != null,
            // Only creatures meaningfully suffer summoning sickness — the engine attaches the
            // marker to every entering permanent so Vehicles / animated lands inherit the
            // restriction when they become creatures, but for non-creatures the marker is a
            // no-op (all {T}/attack gates are creature-conditional). Reporting it on a freshly
            // played Mountain would mislead the agent into thinking it can't tap for mana.
            summoningSick = onBattlefield
                && container.get<SummoningSicknessComponent>() != null
                && projected.isCreature(entityId),
            faceDown = container.get<FaceDownComponent>() != null,
            damageMarked = container.get<DamageComponent>()?.amount ?: 0,
            counters = container.get<CountersComponent>()?.counters
                ?.mapKeys { it.key.name }
                ?.toSortedMap() ?: emptyMap(),
            attachedTo = container.get<AttachedToComponent>()?.targetId,
            attachments = container.get<AttachmentsComponent>()?.attachedIds ?: emptyList()
        )
    }

    // =========================================================================
    // Stack
    // =========================================================================

    private fun buildStackItem(
        state: GameState,
        entityId: EntityId,
        perspectivePlayerId: EntityId
    ): StackItemView {
        val container = state.getEntity(entityId)
        val card = container?.get<CardComponent>()
        val spell = container?.get<SpellOnStackComponent>()
        val triggered = container?.get<TriggeredAbilityOnStackComponent>()
        val activated = container?.get<ActivatedAbilityOnStackComponent>()
        val legacyAbility = container?.get<AbilityOnStackComponent>()

        val kind = when {
            spell != null || card?.spellEffect != null -> StackItemKind.SPELL
            triggered != null -> StackItemKind.TRIGGERED_ABILITY
            activated != null || legacyAbility != null -> StackItemKind.ACTIVATED_ABILITY
            else -> StackItemKind.OTHER
        }

        val controllerId = spell?.casterId
            ?: triggered?.controllerId
            ?: activated?.controllerId
            ?: legacyAbility?.controllerId
            ?: state.projectedState.getController(entityId)
            ?: container?.get<ControllerComponent>()?.playerId
        val sourceEntityId = when {
            spell != null -> entityId
            triggered != null -> triggered.sourceId
            activated != null -> activated.sourceId
            legacyAbility != null -> legacyAbility.sourceId
            else -> null
        }
        val faceDown = container?.has<FaceDownComponent>() == true
        val identityVisible = !faceDown ||
            visibility.isCardRevealedTo(state, entityId, perspectivePlayerId) ||
            controllerId == perspectivePlayerId
        val targets = container?.get<TargetsComponent>()?.targets
            ?.map { target ->
                when (target) {
                    is ChosenTarget.Player -> target.playerId
                    is ChosenTarget.Permanent -> target.entityId
                    is ChosenTarget.Card -> target.cardId
                    is ChosenTarget.Spell -> target.spellEntityId
                }
            } ?: emptyList()

        return StackItemView(
            entityId = entityId,
            controllerId = controllerId,
            sourceEntityId = sourceEntityId,
            name = if (identityVisible) card?.name ?: "" else "Face-down spell",
            kind = kind,
            oracleText = if (identityVisible) card?.oracleText ?: "" else "",
            targets = targets
        )
    }

    // =========================================================================
    // Legal actions
    // =========================================================================

    private fun legalActionToView(actionId: Int, la: LegalAction): LegalActionView {
        return LegalActionView(
            actionId = actionId,
            kind = la.actionType,
            description = la.description,
            affordable = la.affordable,
            sourceEntityId = actionSourceEntityId(la),
            targetEntityIds = la.validTargets ?: emptyList(),
            manaCost = la.manaCostString,
            hasXCost = la.hasXCost,
            maxAffordableX = la.maxAffordableX,
            minTargets = la.minTargets,
            maxTargets = la.targetCount,
            requiresDamageDistribution = la.requiresDamageDistribution,
            isManaAbility = la.isManaAbility,
            isDecisionOption = false
        )
    }

    private fun actionSourceEntityId(legalAction: LegalAction): EntityId? = when (val action = legalAction.action) {
        is CastSpell -> action.cardId
        is ActivateAbility -> action.sourceId
        is CycleCard -> action.cardId
        is PlotCard -> action.cardId
        is ForetellCard -> action.cardId
        is SuspendCardFromHand -> action.cardId
        is TypecycleCard -> action.cardId
        is PlayLand -> action.cardId
        is CrewVehicle -> action.vehicleId
        is SaddleMount -> action.mountId
        is TurnFaceUp -> action.sourceId
        is UnlockRoomDoor -> action.roomId
        is BottomCards -> action.cardIds.firstOrNull()
        else -> null
    }

    // =========================================================================
    // Pending decisions
    // =========================================================================

    /**
     * For simple decisions (yes/no, choose-number, choose-mode, choose-color,
     * choose-option, single-select cards) we enumerate every concrete response
     * into the unified action-ID space. For complex decisions (targets,
     * distribute, order, split, search, reorder, damage, mana sources) we emit
     * [PendingDecisionView.requiresStructuredResponse] = true; the trainer
     * submits a `DecisionResponse` via a dedicated endpoint (Phase 3).
     */
    private fun buildPendingDecision(
        decision: PendingDecision,
        exposeToPerspective: Boolean
    ): Pair<PendingDecisionView, ActionRegistry> {
        if (!exposeToPerspective) {
            return PendingDecisionView(
                decisionId = null,
                kind = PendingDecisionKind.GENERIC,
                playerId = decision.playerId,
                prompt = "",
                sourceEntityId = null,
                sourceName = null,
                triggeringEntityId = null,
                effectHint = null,
                requiresStructuredResponse = true,
                shape = DecisionShape()
            ) to ActionRegistry.EMPTY
        }

        val ctx = decision.context
        val baseShape = DecisionShape()

        return when (decision) {
            is YesNoDecision -> {
                val responses = listOf(
                    YesNoResponse(decision.id, true),
                    YesNoResponse(decision.id, false)
                )
                val view = baseView(decision, PendingDecisionKind.YES_NO, baseShape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is BatchYesNoDecision -> {
                // Folded to two whole-run actions (yes-to-all / no-to-all); peel-off isn't an
                // observation action. Reuses the YES_NO encoding kind.
                val responses = listOf(
                    BatchYesNoResponse(decision.id, choice = true, applyToAll = true),
                    BatchYesNoResponse(decision.id, choice = false, applyToAll = true)
                )
                val view = baseView(decision, PendingDecisionKind.YES_NO, baseShape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseNumberDecision -> {
                val responses = (decision.minValue..decision.maxValue).map {
                    NumberChosenResponse(decision.id, it)
                }
                val shape = DecisionShape(
                    numericMin = decision.minValue,
                    numericMax = decision.maxValue
                )
                val view = baseView(decision, PendingDecisionKind.CHOOSE_NUMBER, shape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseModeDecision -> {
                // Folds only single-mode choices into IDs; multi-mode uses structured response.
                if (decision.minModes == 1 && decision.maxModes == 1) {
                    val responses = decision.modes
                        .filter { it.available }
                        .map { ModesChosenResponse(decision.id, listOf(it.index)) }
                    val shape = DecisionShape(
                        minSelections = decision.minModes,
                        maxSelections = decision.maxModes
                    )
                    val view = baseView(decision, PendingDecisionKind.CHOOSE_MODE, shape, structured = false)
                    view to ActionRegistry.ofDecisionResponses(responses)
                } else {
                    val shape = DecisionShape(
                        minSelections = decision.minModes,
                        maxSelections = decision.maxModes
                    )
                    baseView(decision, PendingDecisionKind.CHOOSE_MODE, shape, structured = true) to
                        ActionRegistry.EMPTY
                }
            }
            is ChooseColorDecision -> {
                val responses = decision.availableColors.map {
                    ColorChosenResponse(decision.id, it)
                }
                val shape = DecisionShape(availableColors = decision.availableColors)
                val view = baseView(decision, PendingDecisionKind.CHOOSE_COLOR, shape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseOptionDecision -> {
                val responses = decision.options.indices.map {
                    OptionChosenResponse(decision.id, it)
                }
                val view = baseView(decision, PendingDecisionKind.CHOOSE_OPTION, baseShape, structured = false)
                view to ActionRegistry.ofDecisionResponses(responses)
            }
            is ChooseReplacementDecision ->
                // Two-index (from, to) pick — emitted as a structured decision (trainer submits the
                // DecisionResponse directly rather than via the flat action-ID space).
                baseView(decision, PendingDecisionKind.CHOOSE_REPLACEMENT, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SelectCardsDecision -> {
                if (decision.minSelections == 1 && decision.maxSelections == 1 && !decision.ordered) {
                    val responses = decision.options.map {
                        CardsSelectedResponse(decision.id, listOf(it))
                    }
                    val shape = DecisionShape(
                        minSelections = decision.minSelections,
                        maxSelections = decision.maxSelections
                    )
                    val view = baseView(decision, PendingDecisionKind.SELECT_CARDS, shape, structured = false)
                    view to ActionRegistry.ofDecisionResponses(responses)
                } else {
                    val shape = DecisionShape(
                        minSelections = decision.minSelections,
                        maxSelections = decision.maxSelections
                    )
                    baseView(decision, PendingDecisionKind.SELECT_CARDS, shape, structured = true) to
                        ActionRegistry.EMPTY
                }
            }
            is BudgetModalDecision -> {
                val shape = DecisionShape(budget = decision.budget)
                baseView(decision, PendingDecisionKind.BUDGET_MODAL, shape, structured = true) to
                    ActionRegistry.EMPTY
            }
            is ChooseTargetsDecision ->
                baseView(decision, PendingDecisionKind.CHOOSE_TARGETS, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is DistributeDecision -> {
                val shape = DecisionShape(totalToDistribute = decision.totalAmount)
                baseView(decision, PendingDecisionKind.DISTRIBUTE, shape, structured = true) to
                    ActionRegistry.EMPTY
            }
            is OrderObjectsDecision ->
                baseView(decision, PendingDecisionKind.ORDER_OBJECTS, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SplitPilesDecision ->
                baseView(decision, PendingDecisionKind.SPLIT_PILES, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SearchLibraryDecision -> {
                val shape = DecisionShape(
                    minSelections = decision.minSelections,
                    maxSelections = decision.maxSelections
                )
                baseView(decision, PendingDecisionKind.SEARCH_LIBRARY, shape, structured = true) to
                    ActionRegistry.EMPTY
            }
            is ReorderLibraryDecision ->
                baseView(decision, PendingDecisionKind.REORDER_LIBRARY, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is AssignDamageDecision ->
                baseView(decision, PendingDecisionKind.ASSIGN_DAMAGE, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is CombatResolutionDecision ->
                baseView(decision, PendingDecisionKind.COMBAT_RESOLUTION, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is SelectManaSourcesDecision ->
                baseView(decision, PendingDecisionKind.SELECT_MANA_SOURCES, baseShape, structured = true) to
                    ActionRegistry.EMPTY
        }
    }

    private fun baseView(
        decision: PendingDecision,
        kind: PendingDecisionKind,
        shape: DecisionShape,
        structured: Boolean
    ): PendingDecisionView {
        val ctx = decision.context
        return PendingDecisionView(
            decisionId = decision.id,
            kind = kind,
            playerId = decision.playerId,
            prompt = decision.prompt,
            sourceEntityId = ctx.sourceId,
            sourceName = ctx.sourceName,
            triggeringEntityId = ctx.triggeringEntityId,
            effectHint = ctx.effectHint,
            requiresStructuredResponse = structured,
            shape = shape
        )
    }

    private fun buildDecisionOptionViews(
        decision: PendingDecision,
        responses: List<DecisionResponse>
    ): List<LegalActionView> {
        return responses.mapIndexed { idx, response ->
            LegalActionView(
                actionId = idx,
                kind = "DECISION",
                description = describeResponse(decision, response),
                affordable = true,
                isDecisionOption = true
            )
        }
    }

    private fun describeResponse(decision: PendingDecision, response: DecisionResponse): String = when (response) {
        is YesNoResponse -> if (response.choice) (decision as? YesNoDecision)?.yesText ?: "Yes" else
            (decision as? YesNoDecision)?.noText ?: "No"
        is NumberChosenResponse -> response.number.toString()
        is ModesChosenResponse -> response.selectedModes.joinToString(",") { idx ->
            (decision as? ChooseModeDecision)?.modes?.getOrNull(idx)?.text ?: idx.toString()
        }
        is ColorChosenResponse -> response.color.name
        is OptionChosenResponse ->
            (decision as? ChooseOptionDecision)?.options?.getOrNull(response.optionIndex)
                ?: response.optionIndex.toString()
        is CardsSelectedResponse -> response.selectedCards.joinToString(",") { it.value }
        else -> response.toString()
    }
}

/**
 * Build output pairing an [Observation] with its server-side [ActionRegistry].
 * The observation is safe to serialize; the registry must be retained on the
 * server so it can resolve incoming action IDs. Both game envs ([TrainingObservation])
 * and deckbuild envs ([DeckbuildObservation]) produce this shape.
 */
data class ObservationResult(
    val observation: Observation,
    val registry: ActionRegistry
)
