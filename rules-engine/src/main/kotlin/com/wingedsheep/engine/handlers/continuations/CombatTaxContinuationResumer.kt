package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.AttackTaxManaSelectionContinuation
import com.wingedsheep.engine.core.BlockTaxManaSelectionContinuation
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.tap
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.PendingManaPaymentPlanExecutor
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.fromManaPool
import com.wingedsheep.engine.mechanics.mana.toManaPool
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId

/**
 * Resumes attack / block declarations that paused for the player to pick mana sources
 * for a generic mana tax (Propaganda, Ghostly Prison, Windborn Muse, Collective
 * Restraint, Whipgrass Entangler, etc.).
 *
 * The prompt is a [com.wingedsheep.engine.core.SelectManaSourcesDecision] with the
 * auto-pay suggestion pre-selected, so the default response taps the same lands the
 * old auto-tap path used to — the player can swap selections or cancel before any
 * mana is spent.
 *
 * Branches:
 *  - `autoPay = true` → run the solver and tap its suggested sources, commit declaration.
 *  - manual non-empty selection → tap the chosen sources, commit declaration.
 *  - empty manual selection (`autoPay = false`) → clean no-op, declaration cancelled.
 *
 * Sources requiring a sub-cost (e.g. Springleaf Drum's "tap another creature") aren't
 * supported as combat-tax payment yet; selecting one returns an error.
 */
class CombatTaxContinuationResumer(
    private val services: com.wingedsheep.engine.core.EngineServices
) : ContinuationResumerModule {

    override fun resumers(): List<ContinuationResumer<*>> = listOf(
        resumer(AttackTaxManaSelectionContinuation::class) { state, continuation, response, _ ->
            resumeAttackTaxSelection(state, continuation, response)
        },
        resumer(BlockTaxManaSelectionContinuation::class) { state, continuation, response, _ ->
            resumeBlockTaxSelection(state, continuation, response)
        },
    )

    private fun resumeAttackTaxSelection(
        state: GameState,
        continuation: AttackTaxManaSelectionContinuation,
        response: DecisionResponse,
    ): ExecutionResult {
        if (response !is ManaSourcesSelectedResponse) {
            return ExecutionResult.error(state, "Expected mana sources selected response for attack tax")
        }
        if (response.isDecline(floatingCovers(state, continuation.attackingPlayer, continuation.manaCost))) {
            // Decline: no mana tapped, no AttackingComponent applied. Drop back into
            // DECLARE_ATTACKERS as a clean no-op (no error banner).
            return ExecutionResult.success(state)
        }

        response.paymentPlan?.let {
            val explicit = PendingManaPaymentPlanExecutor.executeIfPresent(
                state = state,
                playerId = continuation.attackingPlayer,
                cost = continuation.manaCost,
                response = response,
                services = services,
                reason = "attack tax",
            ) ?: error("Expected explicit pending payment result")
            if (explicit.error != null) return ExecutionResult.error(state, explicit.error)
            return services.combatManager.attackPhase.commitAttackDeclaration(
                state = explicit.state,
                attackingPlayer = continuation.attackingPlayer,
                attackers = continuation.attackers,
                projected = explicit.state.projectedState,
                taxEvents = explicit.events,
                bands = continuation.bands,
            )
        }

        val paid = payTax(state, continuation.attackingPlayer, continuation.manaCost, continuation.availableSources, response)
            ?: return ExecutionResult.error(state, "Cannot pay attack tax of ${continuation.manaCost}")

        return services.combatManager.attackPhase.commitAttackDeclaration(
            state = paid.state,
            attackingPlayer = continuation.attackingPlayer,
            attackers = continuation.attackers,
            projected = paid.state.projectedState,
            taxEvents = paid.events,
            bands = continuation.bands,
        )
    }

    private fun resumeBlockTaxSelection(
        state: GameState,
        continuation: BlockTaxManaSelectionContinuation,
        response: DecisionResponse,
    ): ExecutionResult {
        if (response !is ManaSourcesSelectedResponse) {
            return ExecutionResult.error(state, "Expected mana sources selected response for block tax")
        }
        if (response.isDecline(floatingCovers(state, continuation.blockingPlayer, continuation.manaCost))) {
            return ExecutionResult.success(state)
        }

        response.paymentPlan?.let {
            val explicit = PendingManaPaymentPlanExecutor.executeIfPresent(
                state = state,
                playerId = continuation.blockingPlayer,
                cost = continuation.manaCost,
                response = response,
                services = services,
                reason = "block tax",
            ) ?: error("Expected explicit pending payment result")
            if (explicit.error != null) return ExecutionResult.error(state, explicit.error)
            return services.combatManager.blockPhase.commitBlockDeclaration(
                state = explicit.state,
                blockingPlayer = continuation.blockingPlayer,
                blockers = continuation.blockers,
                taxEvents = explicit.events,
            )
        }

        val paid = payTax(state, continuation.blockingPlayer, continuation.manaCost, continuation.availableSources, response)
            ?: return ExecutionResult.error(state, "Cannot pay block tax of ${continuation.manaCost}")

        return services.combatManager.blockPhase.commitBlockDeclaration(
            state = paid.state,
            blockingPlayer = continuation.blockingPlayer,
            blockers = continuation.blockers,
            taxEvents = paid.events,
        )
    }

    private data class TaxPayment(val state: GameState, val events: List<GameEvent>)

    private fun payTax(
        state: GameState,
        playerId: EntityId,
        manaCost: ManaCost,
        availableSources: List<ManaSourceOption>,
        response: ManaSourcesSelectedResponse,
    ): TaxPayment? {
        val playerEntity = state.getEntity(playerId) ?: return null
        val poolComponent = playerEntity.get<ManaPoolComponent>() ?: return null
        var pool = poolComponent.toManaPool()

        val partial = pool.payPartial(manaCost)
        var remainingCost = partial.remainingCost
        var currentState = state
        val events = mutableListOf<GameEvent>()

        if (!remainingCost.isEmpty()) {
            if (response.autoPay) {
                val solver = ManaSolver(services.cardRegistry)
                val solution = solver.solve(currentState, playerId, remainingCost) ?: return null
                val tapResult = services.manaAbilitySideEffectExecutor
                    .tapSourcesWithSideEffects(currentState, solution, playerId)
                if (!tapResult.success) return null
                currentState = tapResult.state
                events.addAll(tapResult.events)
                for ((sourceId, production) in solution.manaProduced) {
                    val subtypes = state.getEntity(sourceId)
                        ?.get<CardComponent>()?.typeLine?.subtypes.orEmpty()
                    pool = if (production.color != null) {
                        pool.addTracked(
                            color = PaymentManaColor.fromEngine(production.color),
                            sourceId = sourceId,
                            subtypes = subtypes,
                            amount = production.amount,
                        )
                    } else {
                        pool.addTracked(
                            color = PaymentManaColor.COLORLESS,
                            sourceId = sourceId,
                            subtypes = subtypes,
                            amount = production.colorless,
                        )
                    }
                }
            } else {
                val sourceMap = availableSources.associateBy { it.entityId }
                for (sourceId in response.selectedSources) {
                    val source = sourceMap[sourceId] ?: return null
                    if (source.requiresSacrifice || source.requiresTappingAnotherPermanent) {
                        // Combat-tax payment doesn't support sac / sub-cost sources yet — fall back
                        // to returning null so the caller errors with a clear message.
                        return null
                    }
                    val (tappedState, tapEvent) = tap(currentState, sourceId)
                    currentState = tappedState
                    tapEvent?.let(events::add)
                    val subtypes = currentState.getEntity(sourceId)
                        ?.get<CardComponent>()?.typeLine?.subtypes.orEmpty()
                    pool = when {
                        source.producesColors.isNotEmpty() -> pool.addTracked(
                            color = PaymentManaColor.fromEngine(source.producesColors.first()),
                            sourceId = sourceId,
                            subtypes = subtypes,
                        )
                        source.producesColorless -> pool.addTracked(
                            color = PaymentManaColor.COLORLESS,
                            sourceId = sourceId,
                            subtypes = subtypes,
                        )
                        else -> pool
                    }
                }
            }
        }

        val newPool = pool.pay(manaCost) ?: return null
        currentState = currentState.updateEntity(playerId) { container ->
            container.with(
                fromManaPool(newPool)
            )
        }
        return TaxPayment(currentState, events)
    }

    /**
     * Whether [playerId]'s floating mana already covers [cost] — see
     * [ManaSourcesSelectedResponse.isDecline]. A player who taps their own sources during the
     * payment window (CR 605.3a) confirms with an empty selection, which must not read as a refusal.
     */
    private fun floatingCovers(state: GameState, playerId: EntityId, cost: ManaCost): Boolean =
        com.wingedsheep.engine.mechanics.mana.ManaPaymentWindow.floatingManaCovers(state, playerId, cost)
}
