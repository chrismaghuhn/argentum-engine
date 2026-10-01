package com.wingedsheep.engine.handlers.actions.ability

import com.wingedsheep.engine.mechanics.mana.withSpendingColors
import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.core.CardsDiscardedEvent
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.tapForMana
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.actions.ActionHandler
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.drawing.DrawCardsExecutor
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.mechanics.mana.ExplicitPaymentPlanExecutor
import com.wingedsheep.engine.mechanics.mana.OrderedPaymentProgramExecutor
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidator
import com.wingedsheep.engine.mechanics.mana.buildAbilityPaymentContext
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCost
import com.wingedsheep.engine.mechanics.mana.fromManaPool
import com.wingedsheep.engine.mechanics.mana.isFixedOrdinaryManaCost
import com.wingedsheep.engine.mechanics.mana.toManaPool
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.replacement.ReplacementEffectProcessor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.PreventCycling
import kotlin.reflect.KClass
import com.wingedsheep.engine.core.Outcome

/**
 * Handler for the CycleCard action.
 *
 * Cycling allows a player to pay a cost, discard the card,
 * and draw a new card. It's an activated ability from hand.
 */
class CycleCardHandler(
    private val zones: ZoneTransitionService,
    private val cardRegistry: CardRegistry,
    private val manaSolver: ManaSolver,
    private val manaAbilitySideEffectExecutor: ManaAbilitySideEffectExecutor,
    private val effectExecutor: ((GameState, Effect, EffectContext) -> EffectResult)?,
    private val replacementProcessor: ReplacementEffectProcessor,
    private val castPermissionUtils: com.wingedsheep.engine.legalactions.utils.CastPermissionUtils? = null
) : ActionHandler<CycleCard> {
    private val amountEvaluator = zones.predicateEvaluator.amounts
    override val actionType: KClass<CycleCard> = CycleCard::class

    private val paymentPlanValidator = PaymentPlanValidator(manaSolver)
    private val explicitPaymentPlanExecutor = ExplicitPaymentPlanExecutor(
        manaSolver = manaSolver,
        manaAbilitySideEffectExecutor = manaAbilitySideEffectExecutor,
    )
    private val orderedPaymentProgramExecutor = OrderedPaymentProgramExecutor(
        manaSolver = manaSolver,
        manaAbilitySideEffectExecutor = manaAbilitySideEffectExecutor,
    )

    override fun validate(state: GameState, action: CycleCard): String? {
        if (!state.hasPriority(action.playerId)) {
            return "You don't have priority"
        }

        // Check if cycling is prevented by any permanent on the battlefield (e.g., Stabilizer)
        if (isCyclingPrevented(state)) {
            return "Cycling is prevented"
        }

        // Cycling is an activated ability of the card in hand (CR 702.29a): an any-zone
        // "players can't activate abilities" (Yuriko, Blade of the Mighty) forbids it.
        if (castPermissionUtils?.isActivationPreventedForPlayer(state, action.cardId, action.playerId) == true) {
            return "An effect prevents you from activating that ability right now"
        }

        val container = state.getEntity(action.cardId)
            ?: return "Card not found: ${action.cardId}"

        val cardComponent = container.get<CardComponent>()
            ?: return "Not a card: ${action.cardId}"

        val handZone = ZoneKey(action.playerId, Zone.HAND)
        if (action.cardId !in state.getZone(handZone)) {
            return "Card is not in your hand"
        }

        val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId)
            ?: return "Card definition not found"

        val cyclingAbility = cardDef.keywordAbilities.filterIsInstance<KeywordAbility.Cycling>()
            .firstOrNull { it.searchFilter == null }
            ?: return "This card doesn't have cycling"

        val paymentContext = buildAbilityPaymentContext(
            cardComponent = cardComponent,
            projected = state.projectedState,
            sourceId = action.cardId,
            ability = null,
        )

        if (action.paymentStrategy is PaymentStrategy.ExplicitV3) {
            if (action.xValue != null || !cyclingAbility.cost.isFixedOrdinaryManaCost()) {
                return "PaymentStrategy.ExplicitV3 supports only fixed ordinary cycling costs"
            }
            val plan = action.paymentStrategy.paymentPlan
                ?: return "PaymentStrategy.ExplicitV3 requires PaymentPlanV3"
            return when (
                val validation = paymentPlanValidator.validateV3(
                    state = state,
                    playerId = action.playerId,
                    cost = cyclingAbility.cost.canonicalPaymentManaCost(),
                    plan = plan,
                    spellContext = paymentContext,
                )
            ) {
                is PaymentPlanValidation.AcceptedV3 -> null
                is PaymentPlanValidation.Accepted ->
                    "PaymentPlanV1/V2 is not the current cycling payment contract"
                is PaymentPlanValidation.Rejected -> validation.reason
            }
        }
        if (action.paymentStrategy is PaymentStrategy.ExplicitV2) {
            if (action.xValue != null || !cyclingAbility.cost.isFixedOrdinaryManaCost()) {
                return "PaymentStrategy.ExplicitV2 supports only fixed ordinary cycling costs"
            }
            val plan = action.paymentStrategy.paymentPlan
                ?: return "PaymentStrategy.ExplicitV2 requires PaymentPlanV2"
            return when (
                val validation = paymentPlanValidator.validateV2(
                    state = state,
                    playerId = action.playerId,
                    cost = cyclingAbility.cost.canonicalPaymentManaCost(),
                    plan = plan,
                    spellContext = paymentContext,
                )
            ) {
                is PaymentPlanValidation.Accepted -> null
                is PaymentPlanValidation.AcceptedV3 ->
                    "PaymentPlanV3 is not supported for cycling yet"
                is PaymentPlanValidation.Rejected -> validation.reason
            }
        }

        // The action is client-supplied: a negative X would underflow the cost substitution.
        if (action.xValue != null && action.xValue < 0) {
            return "X can't be negative"
        }

        // Affordability is judged against X=0 when the player hasn't announced X yet — the
        // handler raises the choice, and X=0 is always a legal announcement (CR 107.3a).
        val effectiveCost = cyclingAbility.cost.withXAs(action.xValue ?: 0)

        if (action.paymentStrategy is PaymentStrategy.Explicit) {
            for (sourceId in action.paymentStrategy.manaAbilitiesToActivate) {
                val sourceContainer = state.getEntity(sourceId)
                    ?: return "Mana source not found: $sourceId"
                if (sourceContainer.has<TappedComponent>()) {
                    return "Mana source is already tapped: $sourceId"
                }
            }
        } else if (!manaSolver.canPay(
                state = state,
                playerId = action.playerId,
                cost = effectiveCost,
                spellContext = paymentContext,
            )
        ) {
            return "Not enough mana to cycle this card"
        }

        return null
    }

    override fun execute(state: GameState, action: CycleCard): ExecutionResult {
        val container = state.getEntity(action.cardId)
            ?: return ExecutionResult.error(state, "Card not found")

        val cardComponent = container.get<CardComponent>()
            ?: return ExecutionResult.error(state, "Not a card")

        val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId)
            ?: return ExecutionResult.error(state, "Card definition not found")

        val cyclingAbility = cardDef.keywordAbilities.filterIsInstance<KeywordAbility.Cycling>()
            .firstOrNull { it.searchFilter == null }
            ?: return ExecutionResult.error(state, "This card doesn't have cycling")

        val paymentContext = buildAbilityPaymentContext(
            cardComponent = cardComponent,
            projected = state.projectedState,
            sourceId = action.cardId,
            ability = null,
        )

        // ---------------------------------------------------------------------
        // {X} cycling cost — announce X (CR 107.3a).
        //
        // Cycling is an activated ability (CR 702.29a), so its `{X}` is chosen as the ability is
        // activated. The legal-actions submission path sends a bare CycleCard with no xValue; pause
        // for the choice and re-enter with it bound. The engine-direct path (xValue pre-filled,
        // as scenario tests and the AI use) skips this. Mirrors the mana-X pause in
        // ActivateAbilityHandler.
        // ---------------------------------------------------------------------
        if (cyclingAbility.cost.hasX && action.xValue == null) {
            val fixedMana = cyclingAbility.cost.withXAs(0).cmc
            val maxX = ((manaSolver.getAvailableManaCount(
                state = state,
                playerId = action.playerId,
                spellContext = paymentContext,
            ) - fixedMana) / cyclingAbility.cost.xCount.coerceAtLeast(1)).coerceAtLeast(0)
            val continuation = com.wingedsheep.engine.core.CycleCardChooseXContinuation(
                action = action
            )
            return state.suspendForDecision(
                question = { decisionId ->
                    com.wingedsheep.engine.core.ChooseNumberDecision(
                        id = decisionId,
                        playerId = action.playerId,
                        prompt = "Choose X for cycling ${cardComponent.name} (0-$maxX)",
                        context = com.wingedsheep.engine.core.DecisionContext(
                            sourceId = action.cardId,
                            sourceName = cardComponent.name,
                            phase = com.wingedsheep.engine.core.DecisionPhase.CASTING
                        ),
                        minValue = 0,
                        maxValue = maxX
                    )
                },
                answer = continuation
            )
        }

        // X is settled from here on. Legacy engine callers may still substitute X into the
        // effective cost; the public ExplicitV2 slice is intentionally narrower and uses the
        // authoritative unsubstituted cost only when it is already fixed ordinary mana.
        val announcedX = action.xValue?.takeIf { cyclingAbility.cost.hasX }
        val cyclingCost = cyclingAbility.cost.withXAs(announcedX ?: 0)

        val explicitV3Payment = if (action.paymentStrategy is PaymentStrategy.ExplicitV3) {
            if (action.xValue != null || !cyclingAbility.cost.isFixedOrdinaryManaCost()) {
                return ExecutionResult.error(
                    state,
                    "PaymentStrategy.ExplicitV3 supports only fixed ordinary cycling costs",
                )
            }
            val plan = action.paymentStrategy.paymentPlan
                ?: return ExecutionResult.error(state, "PaymentStrategy.ExplicitV3 requires PaymentPlanV3")
            orderedPaymentProgramExecutor.executeV3(
                state = state,
                playerId = action.playerId,
                cost = cyclingAbility.cost.canonicalPaymentManaCost(),
                plan = plan,
                paymentContext = paymentContext,
                reason = "Cycle ${cardComponent.name}",
            )
        } else null

        val explicitV2Payment = if (action.paymentStrategy is PaymentStrategy.ExplicitV2) {
            if (action.xValue != null || !cyclingAbility.cost.isFixedOrdinaryManaCost()) {
                return ExecutionResult.error(
                    state,
                    "PaymentStrategy.ExplicitV2 supports only fixed ordinary cycling costs",
                )
            }
            val plan = action.paymentStrategy.paymentPlan
                ?: return ExecutionResult.error(state, "PaymentStrategy.ExplicitV2 requires PaymentPlanV2")
            explicitPaymentPlanExecutor.executeV2(
                state = state,
                playerId = action.playerId,
                cost = cyclingAbility.cost.canonicalPaymentManaCost(),
                plan = plan,
                paymentContext = paymentContext,
                reason = "Cycle ${cardComponent.name}",
            )
        } else null

        explicitV3Payment?.error?.let { error ->
            return ExecutionResult.error(state, error)
        }
        explicitV2Payment?.error?.let { error ->
            return ExecutionResult.error(state, error)
        }

        var currentState = explicitV3Payment?.state ?: explicitV2Payment?.state ?: state
        val events = (explicitV3Payment?.events ?: explicitV2Payment?.events ?: emptyList()).toMutableList()

        if (explicitV3Payment == null && explicitV2Payment == null) {
            // Pay the cycling cost - use floating mana first, then tap lands. The same activated
            // ability context must govern restricted floating mana and source discovery.
            val poolComponent = currentState.getEntity(action.playerId)?.get<ManaPoolComponent>()
                ?: ManaPoolComponent()
            val pool = poolComponent.toManaPool().withSpendingColors(currentState, action.playerId)

            val partialResult = pool.payPartial(cyclingCost, paymentContext)
            val poolAfterPayment = partialResult.newPool
            val remainingCost = partialResult.remainingCost
            val manaSpentFromPool = partialResult.manaSpent

            var whiteSpent = manaSpentFromPool.white
            var blueSpent = manaSpentFromPool.blue
            var blackSpent = manaSpentFromPool.black
            var redSpent = manaSpentFromPool.red
            var greenSpent = manaSpentFromPool.green
            var colorlessSpent = manaSpentFromPool.colorless

            currentState = currentState.updateEntity(action.playerId) { c ->
                c.with(
                    fromManaPool(poolAfterPayment)
                )
            }

            // Tap lands for remaining cost
            if (!remainingCost.isEmpty()) {
                if (action.paymentStrategy is PaymentStrategy.Explicit) {
                    // Tap specified sources explicitly
                    for (sourceId in action.paymentStrategy.manaAbilitiesToActivate) {
                        val (tappedState, tapEvents) = tapForMana(currentState, sourceId, action.playerId)
                        currentState = tappedState
                        events.addAll(tapEvents)
                    }
                } else {
                    val solution = manaSolver.solve(
                        state = currentState,
                        playerId = action.playerId,
                        cost = remainingCost,
                        xValue = 0,
                        spellContext = paymentContext,
                    ) ?: return ExecutionResult.error(state, "Not enough mana to cycle")

                    val tapResult = manaAbilitySideEffectExecutor
                        .tapSourcesWithSideEffects(currentState, solution, action.playerId)
                    if (!tapResult.success) return ExecutionResult.error(state, "Unable to pay mana ability side effect")
                    currentState = tapResult.state
                    events.addAll(tapResult.events)

                    for ((_, production) in solution.manaProduced) {
                        when (production.color) {
                            Color.WHITE -> whiteSpent++
                            Color.BLUE -> blueSpent++
                            Color.BLACK -> blackSpent++
                            Color.RED -> redSpent++
                            Color.GREEN -> greenSpent++
                            null -> colorlessSpent += production.colorless
                        }
                    }
                }
            }

            events.add(
                ManaSpentEvent(
                    playerId = action.playerId,
                    reason = "Cycle ${cardComponent.name}",
                    white = whiteSpent,
                    blue = blueSpent,
                    black = blackSpent,
                    red = redSpent,
                    green = greenSpent,
                    colorless = colorlessSpent
                )
            )
        }

        // Discard the card to pay the cycling cost (CR 702.29a: "Cycling [cost]" means
        // "[Cost], Discard this card: Draw a card."), through the shared discard path so that
        // "whenever you discard" payoffs see it (Magmakin Artillerist) *and* a card-intrinsic
        // discard replacement applies — cycling a madness card exiles it and offers the madness
        // cast (CR 702.35a), which is the classic Fiery Temper line. The discard event and the
        // zone change land before CardCycledEvent, so a card that triggers on both (CR 702.29d)
        // sees them in the order they happened.
        val discardResult = zones.discardCards(currentState, action.playerId, listOf(action.cardId), asCyclingCost = true)
        currentState = discardResult.state
        events.addAll(discardResult.events)

        // Emit cycling event (for cycling triggers like Astral Slide)
        events.add(CardCycledEvent(action.playerId, action.cardId, cardComponent.name, announcedX))

        currentState = currentState.tick()

        // Draw a card using DrawCardsExecutor (checks replacement shields).
        // Cycling is "Discard this card: Draw a card" (CR 702.29a). The announcement-site
        // modifier (CR 121.2a) fires via executeDraws → checkDrawAmount before the per-card loop.
        val drawExecutor = DrawCardsExecutor(
            cardRegistry = cardRegistry,
            effectExecutor = effectExecutor,
            replacementProcessor = replacementProcessor,
            amountEvaluator = amountEvaluator
        )
        val drawResult = drawExecutor.executeDraws(currentState, action.playerId, 1)
        if (drawResult.outcome is Outcome.Paused) {
            return ExecutionResult.propagatePause(
                drawResult.state,
                events + drawResult.events,
                drawResult.diagnostics,
            )
        }
        currentState = drawResult.newState
        events.addAll(drawResult.events)

        // Cycling doesn't change priority
        return ExecutionResult.success(currentState, events, drawResult.diagnostics)
    }

    private fun isCyclingPrevented(state: GameState): Boolean {
        for (entityId in state.getBattlefield()) {
            val card = state.getEntity(entityId)?.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(card.cardDefinitionId) ?: continue
            if (cardDef.script.staticAbilities.any { it is PreventCycling }) {
                return true
            }
        }
        return false
    }

    companion object {
        fun create(services: EngineServices): CycleCardHandler {
            return CycleCardHandler(
                services.zones,
                services.cardRegistry,
                services.manaSolver,
                services.manaAbilitySideEffectExecutor,
                services.effectExecutorRegistry::execute,
                services.replacementEffectProcessor,
                services.castPermissionUtils
            )
        }
    }
}
