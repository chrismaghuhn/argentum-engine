package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticSignal
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TargetPaymentBindingV1
import com.wingedsheep.gym.contract.TargetPaymentDomainV1

/**
 * Shared strict public-payment preflight for live Gym submissions and verified replay cursors.
 *
 * The Rules handlers retain their broader legacy payment vocabulary for non-Gym callers. This
 * boundary deliberately admits only the complete V3 public program, so a replay cannot become
 * exact by letting the engine choose an implicit payment line that was never in the public domain.
 */
object ActionPaymentPlanValidator {

    /** Validate an action-level or target-bound payment against the current public boundary. */
    fun require(
        state: GameState,
        legalAction: LegalAction,
        submitted: GameAction,
        observationBuilder: ObservationBuilder,
        publicView: LegalActionView? = null,
    ) {
        if (legalAction.manaCostString == null) return

        val targetPaymentDomain = publicView?.targetPaymentDomain
        if (targetPaymentDomain != null) {
            requireTargetPaymentPlan(
                state = state,
                template = legalAction,
                submitted = submitted,
                domain = targetPaymentDomain,
                observationBuilder = observationBuilder,
            )
            return
        }

        requireOrdinaryPaymentPlan(
            state = state,
            legalAction = legalAction,
            submitted = submitted,
            observationBuilder = observationBuilder,
        )
    }

    /** Validate a payment whose public action view has no target-to-payment relation. */
    fun requireOrdinary(
        state: GameState,
        legalAction: LegalAction,
        submitted: GameAction,
        observationBuilder: ObservationBuilder,
    ) {
        if (legalAction.manaCostString == null) return
        requireOrdinaryPaymentPlan(state, legalAction, submitted, observationBuilder)
    }

    private fun requireOrdinaryPaymentPlan(
        state: GameState,
        legalAction: LegalAction,
        submitted: GameAction,
        observationBuilder: ObservationBuilder,
    ) {
        if (observationBuilder.paymentDomainV5For(state, legalAction) == null) {
            throw UnsupportedPathFailure(
                listOf(DiagnosticSignal(DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED)),
            )
        }

        val strategy = when (submitted) {
            is ActivateAbility -> submitted.paymentStrategy
            is CastSpell -> submitted.paymentStrategy
            is CycleCard -> submitted.paymentStrategy
            else -> throw IllegalArgumentException("Structured action changed its action type")
        }

        if (submitted is CycleCard) {
            val explicitV3 = strategy as? PaymentStrategy.ExplicitV3
                ?: throw IllegalArgumentException(
                    "CycleCard payment must submit PaymentStrategy.ExplicitV3; " +
                        "automatic, pool, and legacy payments are not allowed",
                )
            require(explicitV3.paymentPlan != null) {
                "CycleCard payment must submit a complete PaymentPlanV3; " +
                    "source IDs alone are not sufficient"
            }
            return
        }

        when (strategy) {
            is PaymentStrategy.ExplicitV3 -> require(strategy.paymentPlan != null) {
                "${legalAction.actionType} payment must submit a complete PaymentPlanV3; " +
                    "source IDs alone are not sufficient"
            }

            else -> throw IllegalArgumentException(
                "${legalAction.actionType} payment must submit a complete PaymentPlanV3; " +
                    "automatic, pool, and legacy payments are not allowed",
            )
        }
    }

    /** Validate the common target-bound plan after a caller has checked any snapshot freshness. */
    internal fun requireTargetPaymentPlan(
        state: GameState,
        template: LegalAction,
        submitted: GameAction,
        domain: TargetPaymentDomainV1,
        observationBuilder: ObservationBuilder,
    ) {
        val activate = submitted as? ActivateAbility
            ?: throw IllegalArgumentException("Target-bound payment requires ActivateAbility")
        val binding = targetPaymentBindingFor(domain, activate)
        require(binding.affordable) { "Selected target-payment binding is unaffordable" }

        val explicitV3 = activate.paymentStrategy as? PaymentStrategy.ExplicitV3
            ?: throw IllegalArgumentException(
                "Target-bound payment must submit PaymentStrategy.ExplicitV3",
            )
        val plan = explicitV3.paymentPlan
            ?: throw IllegalArgumentException("Target-bound payment must submit a complete PaymentPlanV3")
        when (val validation = observationBuilder.validateTargetPaymentPlanV3(
            state = state,
            template = template,
            submitted = activate,
            plan = plan,
            expectedRequiredCost = binding.paymentDomain.requiredCost,
        )) {
            is PaymentPlanValidation.AcceptedV3 -> Unit
            is PaymentPlanValidation.Rejected -> throw IllegalArgumentException(
                "Target-bound PaymentPlanV3 rejected: ${validation.reason}",
            )

            else -> throw IllegalStateException("Unexpected target-bound payment validation result")
        }
    }

    /** Resolve one submitted permanent target through the producer-owned target-payment relation. */
    internal fun targetPaymentBindingFor(
        domain: TargetPaymentDomainV1,
        submitted: ActivateAbility,
    ): TargetPaymentBindingV1 {
        val selectedTarget = submitted.targets.singleOrNull() as? ChosenTarget.Permanent
            ?: throw IllegalArgumentException(
                "Target-bound payment requires exactly one permanent target",
            )
        return domain.targetBindings.singleOrNull {
            it.target == selectedTarget.entityId
        } ?: throw IllegalArgumentException("Submitted target is outside the payment domain")
    }
}
