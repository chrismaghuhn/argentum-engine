package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BatchYesNoResponse
import com.wingedsheep.engine.core.BottomCards
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.ForetellCard
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlotCard
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SaddleMount
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.SuspendCardFromHand
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.TypecycleCard
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.engine.core.WaterbendPermanentChoice
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.mechanics.mana.IntrinsicManaAbilities
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.CostCalculator
import com.wingedsheep.engine.mechanics.mana.ModalPaymentPlanSupport
import com.wingedsheep.engine.mechanics.mana.PaidManaSourceTimingCertifier
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidator
import com.wingedsheep.engine.mechanics.mana.buildAbilityPaymentContext
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCost
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCostWireString
import com.wingedsheep.engine.mechanics.mana.isFixedOrdinaryManaCost
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.spellPaymentContextFor
import com.wingedsheep.engine.mechanics.cost.ActivatedAbilityCostCalculator
import com.wingedsheep.engine.mechanics.cost.CostAmountResolver
import com.wingedsheep.engine.mechanics.cost.DeterministicAdditionalCostPayment
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.ClassLevelComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.EmblemActivatedAbilityComponent
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
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCost
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.ChoiceSlot
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.manaCostOrNull
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetChooser
import com.wingedsheep.sdk.scripting.effects.LevelUpClassEffect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private sealed interface TargetPaymentQualification {
    data object NotApplicable : TargetPaymentQualification
    data class Supported(val domain: TargetPaymentDomainV1) : TargetPaymentQualification
    data object Unsupported : TargetPaymentQualification
}

private enum class TargetCostDependency {
    INDEPENDENT,
    DEPENDENT,
    UNRESOLVED,
}

private const val TARGET_COST_COMBINATION_LIMIT: Int = 4096

/**
 * Converts `(GameState, perspectivePlayerId)` into a [TrainingObservation].
 *
 * ## Information hiding
 *
 * Opponent hand and library identities are perspective-masked. Individually
 * revealed cards and Visibility-authorized top-of-library cards may appear in
 * [ZoneView.cards], while fully hidden members never do. There is no production
 * bypass for this boundary.
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
    private val cardRegistry: CardRegistry,
    private val paidManaSourceTimingCertifier: PaidManaSourceTimingCertifier? = null,
) {
    private val visibility = Visibility(cardRegistry)
    private val predicateEvaluator = PredicateEvaluator()
    private val conditionEvaluator = ConditionEvaluator()
    private val castPermissionUtils by lazy {
        CastPermissionUtils(cardRegistry, predicateEvaluator, conditionEvaluator)
    }
    private val paymentDomainBuilder by lazy {
        PaymentDomainBuilder(
            manaSolver = ManaSolver(cardRegistry),
            visibility = visibility,
            activatedAbilityCostCalculator = activatedAbilityCostCalculator,
            paidManaSourceTimingCertifier = paidManaSourceTimingCertifier
                ?: PaidManaSourceTimingCertifier.fixedFirstSlice(cardRegistry),
        )
    }
    private val costCalculator by lazy { CostCalculator(cardRegistry) }
    private val manaSolver by lazy { ManaSolver(cardRegistry) }
    private val paymentPlanValidator by lazy { PaymentPlanValidator(manaSolver) }
    private val activatedAbilityCostCalculator by lazy {
        ActivatedAbilityCostCalculator(castPermissionUtils)
    }
    private val actionSerialization = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun build(
        state: GameState,
        perspectivePlayerId: EntityId,
        legalActions: List<LegalAction>,
        truncated: Boolean = false
    ): ObservationResult {
        val players = state.turnOrder.map { buildPlayerView(state, it, perspectivePlayerId) }

        val zones = buildZones(state, perspectivePlayerId)

        val agentToAct = if (state.gameOver || truncated) null
        else state.pendingDecision?.playerId ?: state.priorityPlayerId
        val mayReceiveActions = !state.gameOver && !truncated && perspectivePlayerId == agentToAct

        val stack = state.stack.map { entityId ->
            buildStackItem(state, entityId, perspectivePlayerId)
        }

        val pendingDecisionAndRegistry = state.pendingDecision
            ?.let { buildPendingDecision(state, it, mayReceiveActions) }
        val pendingDecisionView = pendingDecisionAndRegistry?.first
        val decisionRegistry = pendingDecisionAndRegistry?.second ?: ActionRegistry.EMPTY
        val actionDomainMappings = if (mayReceiveActions && state.pendingDecision == null) {
            legalActions.map { action ->
                val targetResult = mapPublicTargetDomain(state, action, perspectivePlayerId)
                ActionDomainMapping(
                    action = action,
                    targetResult = targetResult,
                    attackResult = mapPublicAttackDeclarationDomain(state, action, perspectivePlayerId),
                    blockerResult = mapPublicBlockerDeclarationDomain(state, action, perspectivePlayerId),
                    targetPaymentQualification = targetPaymentQualificationFor(state, action, targetResult),
                )
            }
        } else {
            emptyList()
        }
        val supportedActionMappings = actionDomainMappings.mapNotNull { mapping ->
            val target = mapping.targetResult as? ActionTargetDomainMapper.Result.Supported
            val attack = mapping.attackResult as? AttackDeclarationDomainMapper.Result.Supported
            val blocker = mapping.blockerResult as? BlockerDeclarationDomainMapper.Result.Supported
            if (target == null || attack == null || blocker == null) {
                null
            } else {
                SupportedActionDomain(
                    action = mapping.action,
                    targetDomain = target.domain,
                    attackDeclarationDomain = attack.domain,
                    blockerDeclarationDomain = blocker.domain,
                    targetPaymentQualification = mapping.targetPaymentQualification,
                )
            }
        }
        val targetDomainDiagnostics = actionDomainMappings
            .mapNotNull { mapping ->
                (mapping.targetResult as? ActionTargetDomainMapper.Result.Unsupported)?.diagnostic
            }
            .distinct()
        val attackDeclarationDomainDiagnostics = actionDomainMappings
            .mapNotNull { mapping ->
                (mapping.attackResult as? AttackDeclarationDomainMapper.Result.Unsupported)?.diagnostic
            }
            .distinct()
        val blockerDeclarationDomainDiagnostics = actionDomainMappings
            .mapNotNull { mapping ->
                (mapping.blockerResult as? BlockerDeclarationDomainMapper.Result.Unsupported)?.diagnostic
            }
            .distinct()
        val diagnostics = buildList {
            if (
                mayReceiveActions &&
                state.pendingDecision != null &&
                pendingDecisionView?.requiresStructuredResponse == true &&
                pendingDecisionView.structuredDomain == null
            ) {
                add(DiagnosticSignal(code = DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING))
            }
            if (mayReceiveActions && state.pendingDecision == null && actionDomainMappings.any { mapping ->
                    when (mapping.targetPaymentQualification) {
                        TargetPaymentQualification.NotApplicable ->
                            mapping.action.affordable &&
                                mapping.action.manaCostString != null &&
                                paymentDomainV5For(state, mapping.action) == null

                        is TargetPaymentQualification.Supported -> false
                        TargetPaymentQualification.Unsupported -> true
                    }
                }) {
                add(DiagnosticSignal(code = DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED))
            }
            // Keep payment diagnostics and their existing order intact. Target-domain failures
            // and attack-domain failures are appended as separate trusted-path signals;
            // GameGymEnv treats any diagnostic as whole-observation fatal and never exposes a
            // silently reduced action list.
            addAll(targetDomainDiagnostics)
            addAll(attackDeclarationDomainDiagnostics)
            addAll(blockerDeclarationDomainDiagnostics)
        }

        // Build legal-action views and their registry. When mid-decision the
        // engine's `legalActions` is empty — we use the decision options instead.
        val legalActionViews: List<LegalActionView>
        val actionRegistry: ActionRegistry
        if (!mayReceiveActions) {
            legalActionViews = emptyList()
            actionRegistry = ActionRegistry.EMPTY
        } else if (state.pendingDecision != null) {
            val responses = decisionRegistry.decisionResponses.map { it.second }
            legalActionViews = buildDecisionOptionViews(state, state.pendingDecision!!, responses)
            actionRegistry = decisionRegistry
        } else {
            legalActionViews = supportedActionMappings.mapIndexed { idx, mapped ->
                legalActionToView(
                    state,
                    idx,
                    mapped.action,
                    mapped.targetDomain,
                    mapped.attackDeclarationDomain,
                    mapped.blockerDeclarationDomain,
                    mapped.targetPaymentQualification,
                )
            }
            actionRegistry = ActionRegistry.ofLegalActions(supportedActionMappings.map { it.action })
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
            truncated = truncated,
            winnerId = state.winnerId,
            stateDigest = ""
        )
        val digested = obs.copy(stateDigest = StateDigest.compute(obs))
        return ObservationResult(digested, actionRegistry, diagnostics)
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
        if (zone == Zone.LIBRARY) {
            if (!isLibraryCardVisibleTo(state, key, entityId, perspective)) {
                return CardVisibility.HIDDEN
            }
        } else if (!visibility.isZoneVisibleTo(state, key, perspective)) {
            return CardVisibility.HIDDEN
        }

        val container = state.getEntity(entityId) ?: return CardVisibility.HIDDEN
        if (!container.has<FaceDownComponent>()) return CardVisibility.VISIBLE_IDENTITY
        if (visibility.isCardRevealedTo(state, entityId, perspective)) {
            return CardVisibility.VISIBLE_IDENTITY
        }

        val controller = state.projectedState.getController(entityId)
            ?: container.get<ControllerComponent>()?.playerId
        if (zone == Zone.BATTLEFIELD &&
            (controller == perspective || visibility.hasLookAtFaceDownCreatures(state, perspective))
        ) {
            return CardVisibility.VISIBLE_IDENTITY
        }

        if (zone == Zone.EXILE) return CardVisibility.HIDDEN
        return CardVisibility.PUBLIC_FACE_DOWN_ONLY
    }

    private fun isLibraryCardVisibleTo(
        state: GameState,
        key: ZoneKey,
        entityId: EntityId,
        perspective: EntityId
    ): Boolean {
        if (visibility.isCardRevealedTo(state, entityId, perspective)) return true
        val isTopCard = state.getLibrary(key.ownerId).firstOrNull() == entityId
        if (!isTopCard) return false
        return visibility.revealsTopOfLibraryPublicly(state, key.ownerId) ||
            (key.ownerId == perspective && visibility.hasLookAtTopOfLibrary(state, perspective))
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
            attachments = container.get<AttachmentsComponent>()?.attachedIds
                ?.sortedBy { it.value }
                ?: emptyList()
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
        val faceDown = container?.has<FaceDownComponent>() == true || spell?.castFaceDown == true
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

    private fun legalActionToView(
        state: GameState,
        actionId: Int,
        la: LegalAction,
        targetDomain: ActionTargetDomainV1,
        attackDeclarationDomain: AttackDeclarationDomainV2?,
        blockerDeclarationDomain: BlockerDeclarationDomainV1?,
        targetPaymentQualification: TargetPaymentQualification,
    ): LegalActionView {
        val sacrificeInfo = la.additionalCostInfo
            ?.takeIf { it.costType.contains("Sacrifice") || it.costType == "Casualty" }
        val singleRequirement = targetDomain.requirements.singleOrNull()
        val requiredPayloadFields = requiredPayloadFieldsFor(state, la)
        val targetPaymentDomain =
            (targetPaymentQualification as? TargetPaymentQualification.Supported)?.domain
        val affordable = when (targetPaymentQualification) {
            TargetPaymentQualification.NotApplicable -> la.affordable
            is TargetPaymentQualification.Supported ->
                targetPaymentQualification.domain.targetBindings.any { it.affordable }

            TargetPaymentQualification.Unsupported -> false
        }
        val manaCost = when (targetPaymentQualification) {
            TargetPaymentQualification.NotApplicable -> la.manaCostString
            is TargetPaymentQualification.Supported,
            TargetPaymentQualification.Unsupported -> null
        }
        val paymentDomain = when (targetPaymentQualification) {
            TargetPaymentQualification.NotApplicable ->
                if (la.affordable) paymentDomainV5For(state, la) else null

            is TargetPaymentQualification.Supported,
            TargetPaymentQualification.Unsupported -> null
        }
        return LegalActionView(
            actionId = actionId,
            kind = la.actionType,
            description = la.description,
            affordable = affordable,
            sourceEntityId = actionSourceEntityId(la),
            targetEntityIds = singleRequirement?.candidates ?: emptyList(),
            targetDomain = targetDomain,
            attackDeclarationDomain = attackDeclarationDomain,
            blockerDeclarationDomain = blockerDeclarationDomain,
            manaCost = manaCost,
            paymentDomain = paymentDomain,
            hasXCost = la.hasXCost,
            maxAffordableX = la.maxAffordableX,
            minTargets = singleRequirement?.minTargets ?: 0,
            maxTargets = singleRequirement?.maxTargets ?: 0,
            validSacrificeTargets = sacrificeInfo?.validSacrificeTargets
                ?.sortedBy { it.value }
                ?: emptyList(),
            sacrificeCount = sacrificeInfo?.sacrificeCount ?: 0,
            sacrificeMinCount = sacrificeInfo?.sacrificeMinCount ?: 0,
            sacrificeMaxCount = sacrificeInfo?.sacrificeMaxCount
                ?.takeIf { it > 0 }
                ?: sacrificeInfo?.sacrificeCount?.takeIf { it > 0 }
                ?: 0,
            requiresDamageDistribution = la.requiresDamageDistribution,
            isManaAbility = la.isManaAbility,
            availableManaColors = publicManaColorDomain(la),
            requiresStructuredAction = requiredPayloadFields.isNotEmpty(),
            requiredPayloadFields = requiredPayloadFields,
            actionSemantics = actionSemantic(state, la.action),
            isDecisionOption = false,
            targetPaymentDomain = targetPaymentDomain,
        )
    }

    /**
     * Classifies whether this action needs a target-bound payment relation. Target dependence is
     * detected from the Rules-owned effective-cost calculator for the public candidates, with the
     * calculator's explicit target-aware reduction marker retained as a conservative signal when
     * the target domain itself is unsupported. This is qualification only; it never chooses a
     * target or a payment.
     */
    private fun targetPaymentQualificationFor(
        state: GameState,
        legalAction: LegalAction,
        targetResult: ActionTargetDomainMapper.Result,
    ): TargetPaymentQualification {
        val action = legalAction.action as? ActivateAbility
            ?: return TargetPaymentQualification.NotApplicable
        return when (targetCostDependencyFor(state, action, legalAction, targetResult)) {
            TargetCostDependency.INDEPENDENT -> TargetPaymentQualification.NotApplicable
            TargetCostDependency.UNRESOLVED -> TargetPaymentQualification.Unsupported
            TargetCostDependency.DEPENDENT -> {
                val targetDomain = (targetResult as? ActionTargetDomainMapper.Result.Supported)?.domain
                    ?: return TargetPaymentQualification.Unsupported
                val relation = targetPaymentDomainV1For(state, legalAction, targetDomain)
                    ?: return TargetPaymentQualification.Unsupported
                TargetPaymentQualification.Supported(relation)
            }
        }
    }

    /**
     * Proves target independence only from the complete finite set of target bindings that the
     * Rules target mapper published. A target-independent dynamic reduction therefore remains on
     * the historical action-level V5 path; a missing binding or an unenumerable shape is never
     * treated as equality.
     */
    private fun targetCostDependencyFor(
        state: GameState,
        action: ActivateAbility,
        legalAction: LegalAction,
        targetResult: ActionTargetDomainMapper.Result,
    ): TargetCostDependency {
        val ability = resolveActivatedAbility(state, action) ?: return TargetCostDependency.UNRESOLVED
        if (!ability.cost.hasManaComponent()) return TargetCostDependency.INDEPENDENT

        // ActivatedAbilityEnumerator emits an unaffordable target-bearing ability as a greyed-out
        // targetless placeholder before it has built target infos. There is no public target/payment
        // choice to qualify in that representation, and no action-level V5 domain is exposed while
        // it is unaffordable. Do not turn that historical placeholder into an observation-wide
        // PAYMENT_DOMAIN_UNSUPPORTED diagnostic; real target-bearing shapes below remain strict.
        if (!legalAction.affordable &&
            legalAction.targetRequirements.isEmpty() &&
            targetResult is ActionTargetDomainMapper.Result.Supported &&
            targetResult.domain.requirements.isEmpty()
        ) {
            return TargetCostDependency.INDEPENDENT
        }

        // These are the current Rules-owned inputs that can make the calculator's effective cost
        // target-sensitive. With neither present, the calculator is target-independent by contract.
        if (ability.genericCostReduction == null && !ability.isEquipAbility) {
            return TargetCostDependency.INDEPENDENT
        }

        val targetDomain = (targetResult as? ActionTargetDomainMapper.Result.Supported)?.domain
            ?: return TargetCostDependency.UNRESOLVED
        val requirements = targetDomain.requirements
        if (requirements.size != ability.targetRequirements.size || requirements.isEmpty()) {
            return TargetCostDependency.UNRESOLVED
        }
        if (requirements.any { it.maxTargets > 1 }) return TargetCostDependency.UNRESOLVED

        val targetOptions = requirements.map { requirement ->
            val choices = requirement.candidates.map { candidateId ->
                chosenTargetFor(state, candidateId)
            }
            if (choices.any { it == null }) return TargetCostDependency.UNRESOLVED
            val selected = choices.filterNotNull().map { listOf(it) }
            if (requirement.minTargets == 0) listOf(emptyList<ChosenTarget>()) + selected else selected
        }
        if (targetOptions.any { it.isEmpty() }) return TargetCostDependency.UNRESOLVED

        var combinations = listOf(emptyList<ChosenTarget>())
        for (options in targetOptions) {
            if (options.isEmpty() || combinations.size > TARGET_COST_COMBINATION_LIMIT / options.size) {
                return TargetCostDependency.UNRESOLVED
            }
            combinations = combinations.flatMap { prefix -> options.map { prefix + it } }
        }

        val unboundCost = activatedAbilityCostCalculator.calculate(
            state = state,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            ability = ability,
            equipPayment = action.alternativePayment?.equipPayment,
        ).canonicalPaymentCost()
        val boundCosts = combinations.map { targets ->
            activatedAbilityCostCalculator.calculate(
                state = state,
                sourceId = action.sourceId,
                controllerId = action.playerId,
                ability = ability,
                targets = targets,
                equipPayment = action.alternativePayment?.equipPayment,
            ).canonicalPaymentCost()
        }
        return if (boundCosts.all { it == unboundCost }) {
            TargetCostDependency.INDEPENDENT
        } else {
            TargetCostDependency.DEPENDENT
        }
    }

    /** Build the complete relation from the already mapped, canonical target candidate list. */
    private fun targetPaymentDomainV1For(
        state: GameState,
        legalAction: LegalAction,
        targetDomain: ActionTargetDomainV1,
    ): TargetPaymentDomainV1? {
        val action = legalAction.action as? ActivateAbility ?: return null
        // Resource-payment alternatives (Convoke, Delve, Harmonize, Waterbend, ...) carry an
        // unresolved external choice that V1 cannot represent. An explicitly selected equip
        // payment is the only alternative retained by this slice because the cost calculator can
        // consume that already-bound choice deterministically.
        if (action.alternativePayment?.let { it.hasResourcePayment || it.equipPayment == null } == true) {
            return null
        }
        val requirement = targetDomain.requirements.singleOrNull() ?: return null
        if (requirement.minTargets != 1 || requirement.maxTargets != 1 || requirement.candidates.isEmpty()) {
            return null
        }
        val legalRequirement = legalAction.targetRequirements.singleOrNull() ?: return null
        if (legalRequirement.minTargets != 1 ||
            legalRequirement.maxTargets != 1 ||
            legalRequirement.targetChooser != TargetChooser.Controller
        ) return null
        val ability = resolveActivatedAbility(state, action) ?: return null
        val abilityRequirement = ability.targetRequirements.singleOrNull() as? TargetObject ?: return null
        if (abilityRequirement.count != 1 ||
            abilityRequirement.minCount != 1 ||
            abilityRequirement.optional ||
            abilityRequirement.unlimited ||
            abilityRequirement.dynamicMaxCount != null ||
            abilityRequirement.chooser != TargetChooser.Controller ||
            abilityRequirement.filter.clauses().any { it.zone != Zone.BATTLEFIELD }
        ) return null
        if (requirement.candidates.any { !isBattlefieldPermanent(state, it) }) return null

        val bindings = requirement.candidates.map { targetId ->
            val boundAction = action.copy(targets = listOf(ChosenTarget.Permanent(targetId)))
            val effectiveCost = activatedAbilityCostCalculator.calculate(
                state = state,
                sourceId = boundAction.sourceId,
                controllerId = boundAction.playerId,
                ability = ability,
                targets = boundAction.targets,
                equipPayment = boundAction.alternativePayment?.equipPayment,
            )
            val request = targetBoundPaymentRequest(
                state = state,
                template = legalAction,
                action = boundAction,
                ability = ability,
                effectiveCost = effectiveCost,
            ) ?: return null
            val paymentDomain = paymentDomainBuilder.buildV5(
                state = state,
                playerId = request.playerId,
                requiredCost = request.requiredCost,
                spellContext = request.spellContext,
                excludeSources = request.excludeSources,
                reservedOuterLifePayment = request.reservedOuterLifePayment,
            ) ?: return null
            TargetPaymentBindingV1(
                target = targetId,
                affordable = targetBoundAffordable(state, request),
                paymentDomain = paymentDomain,
            )
        }
        return runCatching { TargetPaymentDomainV1(targetBindings = bindings) }.getOrNull()
    }

    private data class TargetBoundPaymentRequest(
        val playerId: EntityId,
        val requiredCost: String,
        val spellContext: SpellPaymentContext,
        val excludeSources: Set<EntityId>,
        val reservedOuterLifePayment: Int,
    )

    private fun targetBoundPaymentRequest(
        state: GameState,
        template: LegalAction,
        action: ActivateAbility,
        ability: ActivatedAbility,
        effectiveCost: AbilityCost,
    ): TargetBoundPaymentRequest? {
        if (action.alternativePayment?.let { it.hasResourcePayment || it.equipPayment == null } == true) {
            return null
        }
        val manaComponents = when (effectiveCost) {
            is AbilityCost.Atom -> listOfNotNull(effectiveCost.manaCostOrNull)
            is AbilityCost.Composite -> effectiveCost.costs.mapNotNull { it.manaCostOrNull }
            else -> emptyList()
        }
        val manaCost = manaComponents.singleOrNull()?.canonicalPaymentManaCost() ?: return null
        val requiredCost = manaCost.canonicalPaymentManaCostWireString()
        val deterministic = deterministicAdditionalCostPaymentFor(
            state,
            template.copy(action = action, manaCostString = requiredCost),
        ) ?: return null
        val card = state.getEntity(action.sourceId)?.get<CardComponent>() ?: return null
        val spellContext = buildAbilityPaymentContext(
            cardComponent = card,
            projected = state.projectedState,
            sourceId = action.sourceId,
            ability = ability,
        )
        val reservedOuterLifePayment = CostAmountResolver.resolvePayLifeTotal(
            state = state,
            cost = effectiveCost,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            cardRegistry = cardRegistry,
        ) ?: return null
        return TargetBoundPaymentRequest(
            playerId = action.playerId,
            requiredCost = requiredCost,
            spellContext = spellContext,
            excludeSources = if (deterministic.tappedPermanents.isNotEmpty()) {
                setOf(action.sourceId)
            } else {
                emptySet()
            },
            reservedOuterLifePayment = reservedOuterLifePayment,
        )
    }

    private fun targetBoundAffordable(
        state: GameState,
        request: TargetBoundPaymentRequest,
    ): Boolean = manaSolver.canPay(
        state = state,
        playerId = request.playerId,
        cost = ManaCost.parse(request.requiredCost),
        excludeSources = request.excludeSources,
        spellContext = request.spellContext,
        additionalPayLife = request.reservedOuterLifePayment,
    )

    /**
     * Read-only strict preflight for a target-bound ExplicitV3 submission. The trusted Gym seam
     * will bind the submitted target to the registered/current observation snapshots in Task 4;
     * this method owns the Rules-side recomputation and delegates all ledger validation to the
     * existing V3 validator.
     */
    internal fun validateTargetPaymentPlanV3(
        state: GameState,
        template: LegalAction,
        submitted: ActivateAbility,
        plan: PaymentPlanV3,
    ): PaymentPlanValidation {
        if (submitted.targets.singleOrNull() !is ChosenTarget.Permanent) {
            return PaymentPlanValidation.Rejected(
                "TargetPaymentDomainV1 requires one permanent target",
            )
        }
        val ability = resolveActivatedAbility(state, submitted)
            ?: return PaymentPlanValidation.Rejected("Target-bound ActivatedAbility is stale")
        val effectiveCost = activatedAbilityCostCalculator.calculate(
            state = state,
            sourceId = submitted.sourceId,
            controllerId = submitted.playerId,
            ability = ability,
            targets = submitted.targets,
            equipPayment = submitted.alternativePayment?.equipPayment,
        )
        val request = targetBoundPaymentRequest(
            state = state,
            template = template,
            action = submitted,
            ability = ability,
            effectiveCost = effectiveCost,
        ) ?: return PaymentPlanValidation.Rejected("Target-bound payment request is unsupported")
        return paymentPlanValidator.validateV3(
            state = state,
            playerId = request.playerId,
            cost = ManaCost.parse(request.requiredCost),
            plan = plan,
            spellContext = request.spellContext,
            reservedOuterLifePayment = request.reservedOuterLifePayment,
            excludeSources = request.excludeSources,
        )
    }

    private fun AbilityCost.hasManaComponent(): Boolean = when (this) {
        is AbilityCost.Atom -> manaCostOrNull != null
        is AbilityCost.Composite -> costs.any { it.manaCostOrNull != null }
        else -> false
    }

    private fun chosenTargetFor(state: GameState, entityId: EntityId): ChosenTarget? {
        if (entityId in state.turnOrder) return ChosenTarget.Player(entityId)
        val zone = state.zones.entries.firstOrNull { (_, ids) -> entityId in ids }?.key?.zoneType
        return when (zone) {
            Zone.BATTLEFIELD -> ChosenTarget.Permanent(entityId)
            Zone.STACK -> ChosenTarget.Spell(entityId)
            Zone.HAND,
            Zone.LIBRARY,
            Zone.GRAVEYARD,
            Zone.EXILE,
            Zone.COMMAND,
            Zone.SIDEBOARD -> ChosenTarget.Card(
                cardId = entityId,
                ownerId = state.getEntity(entityId)?.get<CardComponent>()?.ownerId ?: return null,
                zone = zone,
            )
            null -> null
        }
    }

    private fun isBattlefieldPermanent(state: GameState, entityId: EntityId): Boolean =
        state.zones.any { (key, ids) ->
            key.zoneType == Zone.BATTLEFIELD && entityId in ids
        } && state.getEntity(entityId)?.get<CardComponent>() != null

    /**
     * Project the Rules-owned mana-color candidate set into the public action domain. A null
     * Rules value means an unrestricted choice for the existing engine contract, so a required
     * public choice receives the explicit five-color list instead of forcing the consumer to
     * interpret null. The list is a set-shaped domain and is therefore canonically WUBRG ordered.
     */
    private fun publicManaColorDomain(action: LegalAction): List<Color>? {
        if (!action.requiresManaColorChoice) return null
        return (action.availableManaColors ?: Color.entries.toList())
            .distinct()
            .sortedBy(Color::ordinal)
    }

    internal fun requiredPayloadFieldsFor(state: GameState, legalAction: LegalAction): List<String> {
        val additionalRequiredFields = if (requiresDeterministicSourceCostPayment(state, legalAction)) {
            setOf("costPayment")
        } else {
            emptySet()
        }
        return ActionPayloadRequirements.requiredPayloadFields(legalAction, additionalRequiredFields)
    }

    internal fun requiresStructuredActionFor(state: GameState, legalAction: LegalAction): Boolean =
        requiredPayloadFieldsFor(state, legalAction).isNotEmpty()

    internal fun missingRequiredFieldsFor(
        state: GameState,
        legalAction: LegalAction,
        payload: JsonObject,
    ): List<String> = requiredPayloadFieldsFor(state, legalAction).filterNot(payload::containsKey)

    private data class PaymentDomainRequest(
        val playerId: EntityId,
        val requiredCost: String,
        val spellContext: SpellPaymentContext,
        val excludeSources: Set<EntityId> = emptySet(),
    )

    /**
     * Resolves the action-owned cost/context/exclusion tuple shared by the historical V4 and
     * current V5 publication paths. This is intentionally before either DTO builder so the source
     * exclusion semantics cannot drift between observation and strict submission validation.
     */
    private fun paymentDomainRequestFor(
        state: GameState,
        legalAction: LegalAction,
    ): PaymentDomainRequest? {
        val requiredCost = legalAction.manaCostString ?: return null
        return when (val action = legalAction.action) {
            is ActivateAbility -> {
                val ability = resolveActivatedAbility(state, action) ?: return null
                val expectedAdditionalCostPayment =
                    deterministicAdditionalCostPaymentFor(state, legalAction) ?: return null
                val targetCostDependency = targetCostDependencyFor(
                    state = state,
                    action = action,
                    legalAction = legalAction,
                    targetResult = mapPublicTargetDomain(state, legalAction, action.playerId),
                )
                if (legalAction.hasXCost ||
                    legalAction.hasConvoke ||
                    legalAction.hasTapForGeneric ||
                    action.alternativePayment != null ||
                    ability.hasConvoke ||
                    ability.hasWaterbend ||
                    (ability.isEquipAbility &&
                        !isSupportedEquipPayment(state, legalAction, action, ability, requiredCost)) ||
                    targetCostDependency != TargetCostDependency.INDEPENDENT
                ) {
                    // The action payload does not yet carry the non-mana/target choices that
                    // determine these costs. Publishing the enumerator's optimistic cost would
                    // make the payment domain describe a different action than the handler later
                    // validates.
                    return null
                }

                val source = state.getEntity(action.sourceId)?.get<CardComponent>() ?: return null
                val spellContext = buildAbilityPaymentContext(
                    cardComponent = source,
                    projected = state.projectedState,
                    sourceId = action.sourceId,
                    ability = ability,
                )
                val excludeSources = if (expectedAdditionalCostPayment.tappedPermanents.isNotEmpty()) {
                    setOf(action.sourceId)
                } else {
                    emptySet()
                }
                PaymentDomainRequest(
                    playerId = action.playerId,
                    requiredCost = requiredCost,
                    spellContext = spellContext,
                    excludeSources = excludeSources,
                )
            }

            is CastSpell -> {
                if (!isSupportedCastSpellPayment(legalAction, action, state)) return null
                val card = state.getEntity(action.cardId)?.get<CardComponent>() ?: return null
                val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: return null
                val parsedCost = runCatching { ManaCost.parse(requiredCost) }.getOrNull() ?: return null
                if (parsedCost.symbols.any {
                        it !is ManaSymbol.Colored && it !is ManaSymbol.Colorless && it !is ManaSymbol.Generic
                    }) return null
                val boundTargetCandidates = action.targets
                    .plus(action.modeTargetsOrdered.flatten())
                    .map(::entityIdForChosenTarget)
                val targetCandidates = legalAction.validTargets.orEmpty() +
                    legalAction.targetRequirements.orEmpty().flatMap { it.validTargets } +
                    boundTargetCandidates
                val targetCount = maxOf(
                    legalAction.targetCount,
                    legalAction.targetRequirements.orEmpty().sumOf { it.maxTargets },
                )
                val targetRequirements = legalAction.targetRequirements
                val minimumTargetCount = if (targetRequirements.isNullOrEmpty()) {
                    if (!legalAction.requiresTargets && targetCandidates.isEmpty()) {
                        0
                    } else {
                        legalAction.minTargets
                    }
                } else {
                    targetRequirements.sumOf { it.minTargets }
                }
                if (costCalculator.hasTargetDependentCastCost(
                        state = state,
                        cardDef = cardDef,
                        casterId = action.playerId,
                        advertisedCost = parsedCost,
                        legalTargets = targetCandidates,
                        targetCount = targetCount,
                        minimumTargetCount = minimumTargetCount,
                        fromZone = cardZone(state, action.cardId),
                        declaredCostSlot = action.declaredCostSlot,
                    )
                ) return null
                val effectivePaymentCost = castPermissionUtils.relaxSpellCostColorsIfAny(
                    state = state,
                    playerId = action.playerId,
                    cardId = action.cardId,
                    cost = parsedCost,
                )
                val spellContext = if (action.castFaceDown) {
                    SpellPaymentContext.faceDownCast(isFromHand = isInZone(state, action.cardId, Zone.HAND))
                } else {
                    spellPaymentContextFor(
                        cardComponent = card,
                        isKicked = action.declaredCostSlot == com.wingedsheep.sdk.scripting.ChoiceSlot.KICKED,
                        isFromExile = isInZone(state, action.cardId, Zone.EXILE),
                        isFromHand = isInZone(state, action.cardId, Zone.HAND),
                    )
                }
                PaymentDomainRequest(
                    playerId = action.playerId,
                    requiredCost = effectivePaymentCost.toString(),
                    spellContext = spellContext,
                )
            }

            is CycleCard -> {
                if (!legalAction.affordable) return null
                val card = state.getEntity(action.cardId)?.get<CardComponent>() ?: return null
                val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: return null
                val cyclingAbility = cardDef.keywordAbilities
                    .filterIsInstance<com.wingedsheep.sdk.scripting.KeywordAbility.Cycling>()
                    .firstOrNull { it.searchFilter == null }
                    ?: return null
                if (legalAction.hasXCost ||
                    legalAction.additionalCostInfo != null ||
                    legalAction.hasConvoke ||
                    legalAction.hasTapForGeneric ||
                    action.xValue != null
                ) return null
                val parsedCost = runCatching { ManaCost.parse(requiredCost) }.getOrNull() ?: return null
                if (parsedCost != cyclingAbility.cost || !cyclingAbility.cost.isFixedOrdinaryManaCost()) {
                    return null
                }
                val paymentContext = buildAbilityPaymentContext(
                    cardComponent = card,
                    projected = state.projectedState,
                    sourceId = action.cardId,
                    ability = null,
                )
                PaymentDomainRequest(
                    playerId = action.playerId,
                    requiredCost = requiredCost,
                    spellContext = paymentContext,
                )
            }

            else -> null
        }
    }

    /**
     * Historical V4 publication retained for V1/V2-compatible callers and fixtures. The V4
     * source predicate remains unchanged; paid activation costs therefore still fail closed here.
     */
    internal fun paymentDomainFor(state: GameState, legalAction: LegalAction): PaymentDomainV4? {
        val request = paymentDomainRequestFor(state, legalAction) ?: return null
        val domain = paymentDomainBuilder.build(
            state = state,
            playerId = request.playerId,
            requiredCost = request.requiredCost,
            spellContext = request.spellContext,
            excludeSources = request.excludeSources,
        ) ?: return null
        return if (legalAction.action is CastSpell &&
            hasUnrepresentableAdditionalPayment(legalAction, domain.sourceActivations.mapTo(mutableSetOf()) { it.sourceId })
        ) {
            null
        } else {
            domain
        }
    }

    /**
     * Current V5 publication path. A null result is a strict unsupported boundary: no partial
     * source list, solver-selected fallback, or legacy AutoPay interpretation is permitted.
     */
    internal fun paymentDomainV5For(state: GameState, legalAction: LegalAction): PaymentDomainV5? {
        val request = paymentDomainRequestFor(state, legalAction) ?: return null
        val reservedOuterLifePayment = reservedOuterLifePaymentForV5(state, legalAction) ?: return null
        val domain = paymentDomainBuilder.buildV5(
            state = state,
            playerId = request.playerId,
            requiredCost = request.requiredCost,
            spellContext = request.spellContext,
            excludeSources = request.excludeSources,
            reservedOuterLifePayment = reservedOuterLifePayment,
        ) ?: return null
        return if (legalAction.action is CastSpell &&
            hasUnrepresentableAdditionalPayment(
                legalAction,
                domain.sourceActivationOptions.mapTo(mutableSetOf()) { it.sourceId },
            )
        ) {
            null
        } else {
            domain
        }
    }

    /** Resolve only the deterministic outer PayLife supported by the current V5 action slice. */
    private fun reservedOuterLifePaymentForV5(
        state: GameState,
        legalAction: LegalAction,
    ): Int? {
        val action = legalAction.action as? ActivateAbility ?: return 0
        val ability = resolveActivatedAbility(state, action) ?: return null
        val effectiveCost = activatedAbilityCostCalculator.calculate(
            state = state,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            ability = ability,
            targets = action.targets,
            equipPayment = action.alternativePayment?.equipPayment,
        )
        return CostAmountResolver.resolvePayLifeTotal(
            state = state,
            cost = effectiveCost,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            cardRegistry = cardRegistry,
        )
    }

    /**
     * Map the exact public target domain used by observations and trusted submission proof.
     * The acting player's visibility perspective is part of the support certificate; replacing
     * it with an unconditional predicate would certify hidden or otherwise unaddressable targets.
     */
    private fun mapPublicTargetDomain(
        state: GameState,
        legalAction: LegalAction,
        viewingPlayerId: EntityId,
    ): ActionTargetDomainMapper.Result =
        ActionTargetDomainMapper.map(legalAction) { entityId ->
            visibility.isEntityReferenceAddressableTo(
                state = state,
                entityId = entityId,
                viewingPlayerId = viewingPlayerId,
            )
        }

    private fun mapPublicAttackDeclarationDomain(
        state: GameState,
        legalAction: LegalAction,
        viewingPlayerId: EntityId,
    ): AttackDeclarationDomainMapper.Result =
        AttackDeclarationDomainMapper.map(legalAction) { entityId ->
            visibility.isEntityReferenceAddressableTo(
                state = state,
                entityId = entityId,
                viewingPlayerId = viewingPlayerId,
            )
        }

    private fun mapPublicBlockerDeclarationDomain(
        state: GameState,
        legalAction: LegalAction,
        viewingPlayerId: EntityId,
    ): BlockerDeclarationDomainMapper.Result =
        BlockerDeclarationDomainMapper.map(legalAction) { entityId ->
            visibility.isEntityReferenceAddressableTo(
                state = state,
                entityId = entityId,
                viewingPlayerId = viewingPlayerId,
            )
        }

    /**
     * Equip enumeration is allowed to publish a payment domain only when its public target
     * contract is fixed and every public target produces the same complete effective AbilityCost
     * as the unbound enumerated action. The handler later reruns the same Rules-owned calculator
     * with the submitted target, so this is an equality proof rather than a numeric-cost guess.
     */
    private fun isSupportedEquipPayment(
        state: GameState,
        legalAction: LegalAction,
        action: ActivateAbility,
        ability: ActivatedAbility,
        requiredCost: String,
    ): Boolean {
        val targetDomain = when (val mapping = mapPublicTargetDomain(state, legalAction, action.playerId)) {
            is ActionTargetDomainMapper.Result.Supported -> mapping.domain
            ActionTargetDomainMapper.Result.Unsupported -> return false
        }
        val publicTargetRequirement = targetDomain.requirements.singleOrNull() ?: return false
        if (publicTargetRequirement.minTargets != 1 ||
            publicTargetRequirement.maxTargets != 1 ||
            publicTargetRequirement.candidates.isEmpty()
        ) return false

        val legalTargetRequirement = legalAction.targetRequirements.singleOrNull() ?: return false
        if (legalTargetRequirement.minTargets != 1 || legalTargetRequirement.maxTargets != 1) {
            return false
        }

        val abilityTargetRequirement = ability.targetRequirements.singleOrNull() as? TargetObject
            ?: return false
        if (abilityTargetRequirement.count != 1 ||
            abilityTargetRequirement.minCount != 1 ||
            abilityTargetRequirement.optional ||
            abilityTargetRequirement.unlimited ||
            abilityTargetRequirement.dynamicMaxCount != null ||
            abilityTargetRequirement.totalManaValueAtMost != null
        ) return false

        if (ability.cost.manaCostOrNull == null) {
            return false
        }
        val parsedPublicCost = runCatching { ManaCost.parse(requiredCost) }
            .getOrNull()
            ?.canonicalPaymentManaCost() ?: return false
        if (parsedPublicCost.symbols.any {
                it !is ManaSymbol.Colored && it !is ManaSymbol.Colorless && it !is ManaSymbol.Generic
            }
        ) return false

        val advertisedCost = AbilityCost.Atom(CostAtom.Mana(parsedPublicCost)).canonicalPaymentCost()
        val unboundCost = activatedAbilityCostCalculator.calculate(
            state = state,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            ability = ability,
            equipPayment = action.alternativePayment?.equipPayment,
        ).canonicalPaymentCost()
        if (unboundCost != advertisedCost) return false

        return publicTargetRequirement.candidates.all { candidateId ->
            activatedAbilityCostCalculator.calculate(
                state = state,
                sourceId = action.sourceId,
                controllerId = action.playerId,
                ability = ability,
                targets = listOf(ChosenTarget.Permanent(candidateId)),
                equipPayment = action.alternativePayment?.equipPayment,
            ).canonicalPaymentCost() == advertisedCost
        }
    }

    private fun AbilityCost.canonicalPaymentCost(): AbilityCost = when (this) {
        is AbilityCost.Atom -> when (val atom = atom) {
            is CostAtom.Mana -> AbilityCost.Atom(CostAtom.Mana(atom.cost.canonicalPaymentManaCost()))
            else -> this
        }
        else -> this
    }

    private fun isSupportedCastSpellPayment(
        legalAction: LegalAction,
        action: CastSpell,
        state: GameState,
    ): Boolean {
        val isCastSpellMode = legalAction.actionType == "CastSpellMode"
        val isCastWithKicker = legalAction.actionType == "CastWithKicker"
        if ((legalAction.actionType != "CastSpell" && !isCastSpellMode && !isCastWithKicker) ||
            legalAction.hasXCost ||
            (legalAction.hasConvoke && !legalAction.convokeCreatures.isNullOrEmpty()) ||
            (legalAction.hasDelve && !legalAction.delveCards.isNullOrEmpty()) ||
            (legalAction.hasTapForGeneric && !legalAction.tapForGenericPermanents.isNullOrEmpty()) ||
            (legalAction.hasHarmonize && !legalAction.harmonizeCreatures.isNullOrEmpty()) ||
            legalAction.modalEnumeration != null
        ) return false

        val card = state.getEntity(action.cardId)?.get<CardComponent>() ?: return false
        val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: return false
        // Plain kicker's additional mana cost is already folded into the enumerated fixed
        // manaCostString and the declared ChoiceSlot. It has no separate DTO choice. Other
        // optional-cost variants remain closed unless they have their own explicit contract.
        if (legalAction.actionType == "CastWithKicker") {
            val kicker = cardDef.keywordAbilities
                .filterIsInstance<KeywordAbility.OptionalAdditionalCost>()
                .singleOrNull { it.declaredSlot == ChoiceSlot.KICKED }
            val isPlainFixedKicker = action.declaredCostSlot ==
                ChoiceSlot.KICKED &&
                legalAction.additionalCostInfo == null &&
                kicker != null &&
                kicker.manaCost != null &&
                kicker.additionalCost == null &&
                !kicker.multi &&
                kicker.keyword == null &&
                !kicker.grantsFlashTiming
            if (!isPlainFixedKicker) return false
        }
        if (isCastSpellMode) {
            if (!ModalPaymentPlanSupport.supportsFixedChooseOne(
                    state = state,
                    cardDef = cardDef,
                    action = action,
                    conditionEvaluator = conditionEvaluator,
                )
            ) return false
        } else if (action.chosenModes.isNotEmpty() || action.modeTargetsOrdered.isNotEmpty()) {
            return false
        }

        if (action.castFaceDown ||
            action.xValue != null ||
            action.alternativePayment?.hasResourcePayment == true ||
            action.wasWaterbendPaid ||
            action.splicedCardIds.isNotEmpty() ||
            action.useAlternativeCost ||
            action.useWithoutPayingManaCost ||
            action.faceIndex != null
        ) return false
        return cardDef.script.additionalCosts.none(::containsSecondaryManaCost)
    }

    /**
     * A non-mana cast choice may still invalidate the published mana domain. Sacrificing, tapping,
     * or bouncing a candidate that is itself one of the published mana sources changes what the
     * submitted plan can legally activate. Cost reductions and variable X-like additional choices
     * also make the enumerator's advertised mana cost non-final. Choices that are inert for this
     * concrete state remain compatible with PaymentPlanV1 and are carried by the normal action
     * payload alongside the mana plan.
     */
    private fun hasUnrepresentableAdditionalPayment(
        legalAction: LegalAction,
        publishedSources: Set<EntityId>,
    ): Boolean {
        val info = legalAction.additionalCostInfo ?: return false
        if (info.costAfterSacrifice.isNotEmpty() ||
            (info.costType == "SacrificeForCostReduction" && info.validSacrificeTargets.isNotEmpty()) ||
            (info.costType == "BlightVariable" && info.blightVariableMaxX > 0) ||
            (info.costType == "PayXLife" && info.payXLifeMaxX > 0)
        ) return true

        val additionalCostCandidates = buildSet {
            addAll(info.validSacrificeTargets)
            addAll(info.validTapTargets)
            addAll(info.validBounceTargets)
            addAll(info.tapForPowerCreatures.map { it.entityId })
            addAll(info.validCraftMaterials)
        }
        return additionalCostCandidates.any { it in publishedSources }
    }

    private fun isInZone(state: GameState, cardId: EntityId, zone: Zone): Boolean =
        state.turnOrder.any { ownerId -> cardId in state.getZone(ZoneKey(ownerId, zone)) }

    private fun cardZone(state: GameState, cardId: EntityId): Zone? =
        state.zones.entries.firstOrNull { (_, ids) -> cardId in ids }?.key?.zoneType

    private fun entityIdForChosenTarget(target: ChosenTarget): EntityId = when (target) {
        is ChosenTarget.Player -> target.playerId
        is ChosenTarget.Permanent -> target.entityId
        is ChosenTarget.Card -> target.cardId
        is ChosenTarget.Spell -> target.spellEntityId
    }

    private fun containsSecondaryManaCost(cost: AdditionalCost): Boolean = when (cost) {
        is AdditionalCost.Atom -> cost.atom is CostAtom.Mana
        is AdditionalCost.Choice -> cost.options.any(::containsSecondaryManaCost)
        is AdditionalCost.OrPay -> true
        is AdditionalCost.BlightOrPay -> true
        is AdditionalCost.Composite -> cost.steps.any(::containsSecondaryManaCost)
        else -> false
    }

    private fun deterministicAdditionalCostPaymentFor(
        state: GameState,
        legalAction: LegalAction,
    ): AdditionalCostPayment? {
        val action = legalAction.action as? ActivateAbility ?: return null
        val ability = resolveActivatedAbility(state, action) ?: return null
        val effectiveCost = activatedAbilityCostCalculator.calculate(
            state = state,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            ability = ability,
            targets = action.targets,
            equipPayment = action.alternativePayment?.equipPayment,
        )
        return DeterministicAdditionalCostPayment.expectedFor(effectiveCost, action.sourceId)
    }

    private fun requiresDeterministicSourceCostPayment(
        state: GameState,
        legalAction: LegalAction,
    ): Boolean = deterministicAdditionalCostPaymentFor(state, legalAction)?.let {
        it.tappedPermanents.isNotEmpty() || it.sacrificedPermanents.isNotEmpty()
    } == true

    /** Resolve the same printed/granted/intrinsic ability provenance used by [stableAbilityKey]. */
    private fun resolveActivatedAbility(state: GameState, action: ActivateAbility): ActivatedAbility? {
        val source = state.getEntity(action.sourceId)
        val card = source?.get<CardComponent>()
        val cardDefinition = card?.cardDefinitionId?.let(cardRegistry::getCard)
        val classLevel = source?.get<ClassLevelComponent>()?.currentLevel
        val printedAbility = cardDefinition?.script
            ?.effectiveActivatedAbilities(classLevel)
            ?.firstOrNull { it.id == action.abilityId }
        if (printedAbility != null) return printedAbility

        val classLevelUp = classLevelUpAbility(cardDefinition, source, action.abilityId)
        if (classLevelUp != null) return classLevelUp

        val grantedAbility = state.grantedActivatedAbilities
            .firstOrNull { it.entityId == action.sourceId && it.ability.id == action.abilityId }
            ?.ability
        if (grantedAbility != null) return grantedAbility

        val staticAbility = castPermissionUtils
            .getStaticGrantedAbilitiesWithGranter(action.sourceId, state)
            .firstOrNull { it.ability.id == action.abilityId }
            ?.ability
        if (staticAbility != null) return staticAbility

        val emblemAbility = activeEmblemAbilities(state, action.sourceId)
            .firstOrNull { it.id == action.abilityId }
        if (emblemAbility != null) return emblemAbility

        return IntrinsicManaAbilities.forEntity(state, state.projectedState, action.sourceId)
            .firstOrNull { it.id == action.abilityId }
    }

    private fun actionSemantic(state: GameState, action: GameAction): JsonObject {
        val encoded = actionSerialization
            .encodeToJsonElement(GameAction.serializer(), action)
            .jsonObject
        if (action !is ActivateAbility) return encoded

        return buildJsonObject {
            encoded.forEach { (key, value) ->
                if (key != "abilityId") put(key, value)
            }
            put("abilityKey", stableAbilityKey(state, action))
        }
    }

    /**
     * AbilityId is an engine handle and the default generated value contains a JVM-global counter.
     * Resolve the action against the same authoritative provenance sources used by legal-action
     * enumeration before it enters the semantic observation. The structural payload protects
     * against two abilities with the same ordinal accidentally being treated as equivalent; the
     * canonical ordinal protects against genuinely separate but structurally identical grants.
     */
    private fun stableAbilityKey(state: GameState, action: ActivateAbility): JsonObject {
        val source = state.getEntity(action.sourceId)
        val card = source?.get<CardComponent>()
        val cardDefinition = card?.cardDefinitionId?.let(cardRegistry::getCard)
        val classLevel = source?.get<ClassLevelComponent>()?.currentLevel
        val printedAbilities = cardDefinition?.script
            ?.effectiveActivatedAbilities(classLevel)
            .orEmpty()
        val printedOrdinal = printedAbilities.indexOfFirst { it.id == action.abilityId }
        if (printedOrdinal >= 0) {
            return abilityKey(
                origin = "printed",
                ordinal = printedOrdinal,
                ability = printedAbilities[printedOrdinal],
                cardDefinitionId = card?.cardDefinitionId
            )
        }

        val classLevelUp = classLevelUpAbility(cardDefinition, source, action.abilityId)
        if (classLevelUp != null) {
            return abilityKey(
                origin = "classLevelUp",
                ordinal = classLevelUpOrdinal(source),
                ability = classLevelUp,
                cardDefinitionId = card?.cardDefinitionId
            )
        }

        val grantedAbilities = state.grantedActivatedAbilities
            .asSequence()
            .filter { it.entityId == action.sourceId }
            .map { it.ability }
            .toList()
        val grantedOrdinal = grantedAbilities.indexOfFirst { it.id == action.abilityId }
        if (grantedOrdinal >= 0) {
            return abilityKey(
                origin = "granted",
                ordinal = stableAbilityOrdinal(grantedAbilities, grantedOrdinal),
                ability = grantedAbilities[grantedOrdinal],
                cardDefinitionId = card?.cardDefinitionId
            )
        }

        val staticGrants = castPermissionUtils.getStaticGrantedAbilitiesWithGranter(action.sourceId, state)
        val staticOrdinal = staticGrants.indexOfFirst { it.ability.id == action.abilityId }
        if (staticOrdinal >= 0) {
            val grant = staticGrants[staticOrdinal]
            val granterCardDefinitionId = state.getEntity(grant.granterId)
                ?.get<CardComponent>()
                ?.cardDefinitionId
            return abilityKey(
                origin = "static",
                ordinal = stableAbilityOrdinal(staticGrants.map { it.ability }, staticOrdinal),
                ability = grant.ability,
                cardDefinitionId = granterCardDefinitionId
            )
        }

        val emblemAbilities = activeEmblemAbilities(state, action.sourceId)
        val emblemOrdinal = emblemAbilities.indexOfFirst { it.id == action.abilityId }
        if (emblemOrdinal >= 0) {
            return abilityKey(
                origin = "emblem",
                ordinal = stableAbilityOrdinal(emblemAbilities, emblemOrdinal),
                ability = emblemAbilities[emblemOrdinal],
                cardDefinitionId = null
            )
        }

        val intrinsicAbilities = IntrinsicManaAbilities.forEntity(state, state.projectedState, action.sourceId)
        val intrinsicOrdinal = intrinsicAbilities.indexOfFirst { it.id == action.abilityId }
        if (intrinsicOrdinal >= 0) {
            return abilityKey(
                origin = "intrinsic",
                ordinal = stableAbilityOrdinal(intrinsicAbilities, intrinsicOrdinal),
                ability = intrinsicAbilities[intrinsicOrdinal],
                cardDefinitionId = null
            )
        }

        // A legal ActivateAbility must come from one of the authoritative sources above. Keep
        // synthetic/manual caller input fail-closed rather than reintroducing the runtime handle
        // (including donor_<entity>_<printedId>) into semantic equality or StateDigest.
        return buildJsonObject { put("unresolved", true) }
    }

    private fun classLevelUpAbility(
        cardDefinition: com.wingedsheep.sdk.model.CardDefinition?,
        source: ComponentContainer?,
        abilityId: AbilityId,
    ): ActivatedAbility? {
        val currentLevel = source?.get<ClassLevelComponent>()?.currentLevel ?: return null
        val targetLevel = currentLevel + 1
        if (abilityId != AbilityId.classLevelUp(targetLevel)) return null
        val level = cardDefinition?.classLevels?.find { it.level == targetLevel } ?: return null
        return ActivatedAbility(
            id = abilityId,
            cost = AbilityCost.Atom(CostAtom.Mana(level.cost)),
            effect = LevelUpClassEffect(targetLevel),
            timing = TimingRule.SorcerySpeed,
            descriptionOverride = "Level up to level $targetLevel"
        )
    }

    private fun classLevelUpOrdinal(source: ComponentContainer?): Int =
        source?.get<ClassLevelComponent>()?.currentLevel?.plus(1) ?: 0

    private fun activeEmblemAbilities(state: GameState, sourceId: EntityId): List<ActivatedAbility> =
        state.entities.flatMap { (emblemId, emblemContainer) ->
            val grant = emblemContainer.get<EmblemActivatedAbilityComponent>() ?: return@flatMap emptyList()
            val controllerId = emblemContainer.get<ControllerComponent>()?.playerId ?: return@flatMap emptyList()
            val matches = predicateEvaluator.matches(
                state,
                state.projectedState,
                sourceId,
                grant.filter.baseFilter,
                PredicateContext(controllerId = controllerId, sourceId = emblemId),
            ) && (!grant.filter.excludeSelf || sourceId != emblemId)
            if (matches) grant.abilities else emptyList()
        }

    private fun stableAbilityOrdinal(abilities: List<ActivatedAbility>, targetIndex: Int): Int {
        val targetSignature = structuralAbilitySignature(abilities[targetIndex])
        val structurallyBefore = abilities
            .take(targetIndex)
            .count { structuralAbilitySignature(it) == targetSignature }
        return abilities.count { structuralAbilitySignature(it) < targetSignature } + structurallyBefore
    }

    private fun structuralAbilitySignature(ability: ActivatedAbility): String =
        structuralAbilityJson(ability).toString()

    private fun structuralAbilityJson(ability: ActivatedAbility): JsonObject {
        val encoded = actionSerialization
            .encodeToJsonElement(ActivatedAbility.serializer(), ability)
            .jsonObject
        return JsonObject(encoded.filterKeys { it != "id" && it != "descriptionOverride" })
    }

    private fun abilityKey(
        origin: String,
        ordinal: Int,
        ability: ActivatedAbility,
        cardDefinitionId: String?
    ): JsonObject = buildJsonObject {
        put("origin", origin)
        put("ordinal", ordinal)
        cardDefinitionId?.let { put("cardDefinitionId", it) }
        put(
            "ability",
            structuralAbilityJson(ability)
        )
    }

    private fun decisionSemantic(decision: PendingDecision, response: DecisionResponse): JsonObject {
        val encoded = actionSerialization
            .encodeToJsonElement(DecisionResponse.serializer(), response)
            .jsonObject
        val transportFree = encoded.filterKeys { it != "decisionId" }
        if (response !is OptionChosenResponse) return JsonObject(transportFree)

        val metadata = (decision as? ChooseOptionDecision)
            ?.optionMetadata
            ?.getOrNull(response.optionIndex)
            ?: return JsonObject(transportFree)

        return buildJsonObject {
            transportFree.forEach { (key, value) -> put(key, value) }
            put(
                "optionMetadata",
                actionSerialization.encodeToJsonElement(OptionMetadata.serializer(), metadata)
            )
        }
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
     * submits a complete `DecisionResponse` via the dedicated decision endpoint.
     */
    private fun buildPendingDecision(
        state: GameState,
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
                    val domain = ModeSelectionDomain(
                        modes = decision.modes.sortedBy { it.index }.map(::modeDomain),
                        minModes = decision.minModes,
                        maxModes = decision.maxModes
                    )
                    baseView(
                        decision,
                        PendingDecisionKind.CHOOSE_MODE,
                        shape,
                        structured = true,
                        structuredDomain = domain
                    ) to
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
                baseView(
                    decision,
                    PendingDecisionKind.CHOOSE_REPLACEMENT,
                    baseShape,
                    structured = true,
                    structuredDomain = ReplacementDomain(
                        fromOptions = decision.fromOptions,
                        toOptions = decision.toOptions,
                        fromMetadata = decision.fromMetadata.map(::optionMetadataDomain),
                        toMetadata = decision.toMetadata.map(::optionMetadataDomain),
                        allowedToByFrom = decision.allowedToByFrom,
                        defaultFromIndex = decision.defaultFromIndex
                    )
                ) to
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
                    baseView(
                        decision,
                        PendingDecisionKind.SELECT_CARDS,
                        shape,
                        structured = true,
                        structuredDomain = decision.cardSelectionDomain()
                    ) to
                        ActionRegistry.EMPTY
                }
            }
            is BudgetModalDecision -> {
                val shape = DecisionShape(budget = decision.budget)
                baseView(
                    decision,
                    PendingDecisionKind.BUDGET_MODAL,
                    shape,
                    structured = true,
                    structuredDomain = BudgetModalDomain(
                        budget = decision.budget,
                        modes = decision.modes.map(::budgetModeDomain)
                    )
                ) to
                    ActionRegistry.EMPTY
            }
            is ChooseTargetsDecision ->
                baseView(
                    decision,
                    PendingDecisionKind.CHOOSE_TARGETS,
                    baseShape,
                    structured = true,
                    structuredDomain = TargetsDomain(
                        requirements = decision.targetRequirements.sortedBy { it.index }.map { requirement ->
                            targetRequirementDomain(requirement, decision.legalTargets[requirement.index].orEmpty())
                        },
                        canCancel = decision.canCancel
                    )
                ) to
                    ActionRegistry.EMPTY
            is DistributeDecision -> {
                val shape = DecisionShape(totalToDistribute = decision.totalAmount)
                baseView(
                    decision,
                    PendingDecisionKind.DISTRIBUTE,
                    shape,
                    structured = true,
                    structuredDomain = DistributionDomain(
                        totalAmount = decision.totalAmount,
                        targets = unorderedEntityIds(decision.targets),
                        minPerTarget = decision.minPerTarget,
                        maxPerTarget = decision.maxPerTarget.filterKeys { it in decision.targets },
                        allowPartial = decision.allowPartial
                    )
                ) to
                    ActionRegistry.EMPTY
            }
            is OrderObjectsDecision ->
                baseView(
                    decision,
                    PendingDecisionKind.ORDER_OBJECTS,
                    baseShape,
                    structured = true,
                    structuredDomain = OrderingDomain(
                        objects = unorderedEntityIds(decision.objects),
                        cardInfo = decision.cardInfo
                            ?.filterKeys { it in decision.objects }
                            ?.mapValues { (_, info) -> structuredCardInfo(info) },
                        objectLabels = decision.objectLabels?.filterKeys { it in decision.objects }
                    )
                ) to
                    ActionRegistry.EMPTY
            is SplitPilesDecision ->
                baseView(
                    decision,
                    PendingDecisionKind.SPLIT_PILES,
                    baseShape,
                    structured = true,
                    structuredDomain = SplitPilesDomain(
                        cards = unorderedEntityIds(decision.cards),
                        numberOfPiles = decision.numberOfPiles,
                        pileLabels = decision.pileLabels,
                        cardInfo = decision.cardInfo
                            ?.filterKeys { it in decision.cards }
                            ?.mapValues { (_, info) -> structuredCardInfo(info) }
                    )
                ) to
                    ActionRegistry.EMPTY
            is SearchLibraryDecision -> {
                val shape = DecisionShape(
                    minSelections = decision.minSelections,
                    maxSelections = decision.maxSelections
                )
                baseView(
                    decision,
                    PendingDecisionKind.SEARCH_LIBRARY,
                    shape,
                    structured = true,
                    structuredDomain = SearchLibraryDomain(
                        options = unorderedEntityIds(decision.options),
                        minSelections = decision.minSelections,
                        maxSelections = decision.maxSelections,
                        cards = decision.cards
                            .filterKeys { it in decision.options }
                            .mapValues { (_, info) -> structuredCardInfo(info) },
                        filterDescription = decision.filterDescription
                    )
                ) to
                    ActionRegistry.EMPTY
            }
            is ReorderLibraryDecision ->
                baseView(
                    decision,
                    PendingDecisionKind.REORDER_LIBRARY,
                    baseShape,
                    structured = true,
                    structuredDomain = ReorderLibraryDomain(
                        cards = decision.cards,
                        cardInfo = decision.cardInfo
                            .filterKeys { it in decision.cards }
                            .mapValues { (_, info) -> structuredCardInfo(info) }
                    )
                ) to
                    ActionRegistry.EMPTY
            is AssignDamageDecision ->
                baseView(decision, PendingDecisionKind.ASSIGN_DAMAGE, baseShape, structured = true) to
                    ActionRegistry.EMPTY
            is CombatResolutionDecision ->
                baseView(
                    decision,
                    PendingDecisionKind.COMBAT_RESOLUTION,
                    baseShape,
                    structured = true,
                    structuredDomain = decision.combatResolutionDomain()
                ) to
                    ActionRegistry.EMPTY
            is SelectManaSourcesDecision ->
                baseView(
                    decision,
                    PendingDecisionKind.SELECT_MANA_SOURCES,
                    baseShape,
                    structured = true,
                    structuredDomain = decision.manaSourcesDomain(state)
                ) to
                    ActionRegistry.EMPTY
        }
    }

    private fun baseView(
        decision: PendingDecision,
        kind: PendingDecisionKind,
        shape: DecisionShape,
        structured: Boolean,
        structuredDomain: StructuredDecisionDomain? = null
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
            shape = shape,
            structuredDomain = structuredDomain
        )
    }

    private fun targetRequirementDomain(
        requirement: TargetRequirementInfo,
        candidates: List<EntityId>
    ): TargetRequirementDomain = TargetRequirementDomain(
        index = requirement.index,
        description = requirement.description,
        minTargets = requirement.minTargets,
        maxTargets = requirement.maxTargets,
        candidates = unorderedEntityIds(candidates),
        targetZone = requirement.targetZone,
        mustDifferFromEarlier = requirement.mustDifferFromEarlier,
        sameController = requirement.sameController,
        sameOwner = requirement.sameOwner,
        sameCreatureType = requirement.sameCreatureType,
        sameCardType = requirement.sameCardType,
        totalManaValueAtMost = requirement.totalManaValueAtMost,
        differentNames = requirement.differentNames,
        xConstrainsManaValue = requirement.xConstrainsManaValue,
        xConstrainsManaValueExactly = requirement.xConstrainsManaValueExactly,
        xConstrainsPower = requirement.xConstrainsPower,
        xConstrainsCount = requirement.xConstrainsCount,
    )

    private fun SelectCardsDecision.cardSelectionDomain(): CardSelectionDomain = CardSelectionDomain(
        options = unorderedEntityIds(options),
        minSelections = minSelections,
        maxSelections = maxSelections,
        ordered = ordered,
        cardInfo = cardInfo
            ?.filterKeys { it in options || it in nonSelectableOptions }
            ?.mapValues { (_, info) -> structuredCardInfo(info) },
        useTargetingUI = useTargetingUI,
        selectedLabel = selectedLabel,
        remainderLabel = remainderLabel,
        nonSelectableOptions = unorderedEntityIds(nonSelectableOptions),
        onePerCardType = onePerCardType,
        onePerColor = onePerColor,
        availableColors = availableColors?.sorted(),
        onePerCardName = onePerCardName,
        onePerBasicLandType = onePerBasicLandType,
        onePerPower = onePerPower,
        maxTotalManaValue = maxTotalManaValue,
        minTotalManaValue = minTotalManaValue,
        maxTotalPower = maxTotalPower,
        conditionalMinimums = conditionalMinimums.map { minimum ->
            ConditionalSelectionMinimumDomain(
                requiredSelections = minimum.requiredSelections,
                minimumSelections = minimum.minimumSelections,
                matchingOptions = unorderedEntityIds(minimum.matchingOptions),
                requiredMatches = minimum.requiredMatches,
                description = minimum.description
            )
        }
    )

    private fun structuredCardInfo(info: SearchCardInfo): StructuredCardInfo = StructuredCardInfo(
        name = info.name,
        manaCost = info.manaCost,
        typeLine = info.typeLine,
        imageUri = info.imageUri,
        colors = info.colors.sorted(),
        power = info.power
    )

    private fun modeDomain(mode: ModeOption): ModeOptionDomain = ModeOptionDomain(
        index = mode.index,
        text = mode.text,
        available = mode.available
    )

    private fun optionMetadataDomain(metadata: OptionMetadata): OptionMetadataDomain = OptionMetadataDomain(
        id = metadata.id,
        description = metadata.description,
        iconKey = metadata.iconKey,
        triggeringPlayerId = metadata.triggeringPlayerId
    )

    private fun budgetModeDomain(mode: BudgetModeOption): BudgetModeDomain = BudgetModeDomain(
        cost = mode.cost,
        description = mode.description
    )

    private fun CombatResolutionDecision.combatResolutionDomain(): CombatResolutionDomain =
        CombatResolutionDomain(
            firstStrike = firstStrike,
            attackers = attackers.sortedBy { it.id.value }.map { attacker ->
                CombatAttackerDomain(
                    id = attacker.id,
                    name = attacker.name,
                    power = attacker.power,
                    toughness = attacker.toughness,
                    hasTrample = attacker.hasTrample,
                    hasDeathtouch = attacker.hasDeathtouch,
                    hasFirstStrike = attacker.hasFirstStrike,
                    hasDoubleStrike = attacker.hasDoubleStrike,
                    dealsDamageThisStep = attacker.dealsDamageThisStep,
                    bandId = attacker.bandId,
                    attackedDefenderId = attacker.attackedDefenderId,
                    blockedByIds = unorderedEntityIds(attacker.blockedByIds),
                    markedDamage = attacker.markedDamage
                )
            },
            blockers = blockers.sortedBy { it.id.value }.map { blocker ->
                CombatBlockerDomain(
                    id = blocker.id,
                    name = blocker.name,
                    power = blocker.power,
                    toughness = blocker.toughness,
                    hasDeathtouch = blocker.hasDeathtouch,
                    hasFirstStrike = blocker.hasFirstStrike,
                    hasDoubleStrike = blocker.hasDoubleStrike,
                    dealsDamageThisStep = blocker.dealsDamageThisStep,
                    blockedAttackerIds = unorderedEntityIds(blocker.blockedAttackerIds),
                    markedDamage = blocker.markedDamage
                )
            },
            defenders = defenders.sortedBy { it.id.value }.map { defender ->
                CombatDefenderDomain(
                    id = defender.id,
                    kind = CombatTargetKind.valueOf(defender.kind.name),
                    name = defender.name,
                    lifeOrLoyaltyOrDefense = defender.lifeOrLoyaltyOrDefense
                )
            },
            edges = edges.sortedBy { it.id }.map { edge ->
                CombatDamageEdgeDomain(
                    id = edge.id,
                    sourceId = edge.sourceId,
                    targetId = edge.targetId,
                    direction = CombatDamageDirection.valueOf(edge.direction.name),
                    amount = edge.amount,
                    maximum = edge.maximum,
                    lethal = edge.lethal,
                    isTrampleDrain = edge.isTrampleDrain,
                    editableBy = edge.editableBy
                )
            },
            coChooserId = coChooserId
        )

    private fun SelectManaSourcesDecision.manaSourcesDomain(state: GameState): ManaSourcesDomain {
        val runtimeSources = manaSolver.findAvailableManaSources(state, playerId).associateBy { it.entityId }
        return ManaSourcesDomain(
            availableSources = availableSources.sortedBy { it.entityId.value }.map { source ->
                ManaSourceDomain(
                    entityId = source.entityId,
                    name = source.name,
                    producesColors = source.producesColors,
                    producesColorless = source.producesColorless,
                    requiresSacrifice = source.requiresSacrifice,
                    requiresTappingAnotherPermanent = source.requiresTappingAnotherPermanent,
                    manaAbilityKey = source.manaAbilityId?.let { runtimeId ->
                        runtimeSources[source.entityId]
                            ?.let { runtimeSource ->
                                (runtimeSource.producesColors.flatMap(runtimeSource::manaAbilityOptionsFor) +
                                    runtimeSource.manaAbilityOptionsFor(null))
                                    .firstOrNull { it.id == runtimeId }
                            }
                            ?.let(ManaAbilityIdentity::key)
                    }
                )
            },
            requiredCost = requiredCost,
            autoPaySuggestion = autoPaySuggestion,
            canDecline = canDecline,
            waterbendPermanents = waterbendPermanents.sortedBy { it.entityId.value }.map(::waterbendDomain)
        )
    }

    private fun waterbendDomain(choice: WaterbendPermanentChoice): WaterbendPermanentDomain =
        WaterbendPermanentDomain(
            entityId = choice.entityId,
            name = choice.name,
            isCreature = choice.isCreature
        )

    private fun unorderedEntityIds(ids: List<EntityId>): List<EntityId> =
        ids.sortedBy { it.value }

    private fun buildDecisionOptionViews(
        state: GameState,
        decision: PendingDecision,
        responses: List<DecisionResponse>
    ): List<LegalActionView> {
        return responses.mapIndexed { idx, response ->
            LegalActionView(
                actionId = idx,
                kind = "DECISION",
                description = describeResponse(decision, response),
                affordable = true,
                actionSemantics = decisionSemantic(decision, response),
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
        is OptionChosenResponse -> {
            val chooseOption = decision as? ChooseOptionDecision
            chooseOption?.optionMetadata?.getOrNull(response.optionIndex)?.description
                ?: chooseOption?.options?.getOrNull(response.optionIndex)
                ?: response.optionIndex.toString()
        }
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
    val registry: ActionRegistry,
    /** Internal non-wire diagnostics; the observation DTO itself remains unchanged. */
    val diagnostics: List<DiagnosticSignal> = emptyList(),
)

private data class ActionDomainMapping(
    val action: LegalAction,
    val targetResult: ActionTargetDomainMapper.Result,
    val attackResult: AttackDeclarationDomainMapper.Result,
    val blockerResult: BlockerDeclarationDomainMapper.Result,
    val targetPaymentQualification: TargetPaymentQualification,
)

private data class SupportedActionDomain(
    val action: LegalAction,
    val targetDomain: ActionTargetDomainV1,
    val attackDeclarationDomain: AttackDeclarationDomainV2?,
    val blockerDeclarationDomain: BlockerDeclarationDomainV1?,
    val targetPaymentQualification: TargetPaymentQualification,
)
