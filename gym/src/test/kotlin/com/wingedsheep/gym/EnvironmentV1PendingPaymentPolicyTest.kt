package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.PaymentActivationSupportKindV1
import com.wingedsheep.gym.contract.PaymentDeterministicNonManaCostKindV1
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.PaymentSourceActivationDomainV2
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Public-only regression coverage for pending PaymentDomainV5 consumption in the exact-pair policy. */
class EnvironmentV1PendingPaymentPolicyTest : FunSpec({

    test("pending V3 payment domain is selected as a structured external choice") {
        val choice = DeterministicExternalPolicy().choose(
            pendingPaymentObservation(publicTwoProductionDomain()),
            DeterministicPolicyState(policySeed = 1L),
        )

        check(choice is SemanticChoice.Structured) {
            "Expected a structured pending-payment choice from the public V3 domain, got $choice"
        }
        choice.family shouldBe PendingDecisionKind.SELECT_MANA_SOURCES.name
        val payment = choice.selection.shouldBeInstanceOf<SemanticDecision.Payment>()
        payment.paymentPlan.activations.single().apply {
            sourceId shouldBe EntityId("two-production-source")
            productionChoice.producedColor shouldBe PaymentManaColor.GREEN
        }
        payment.paymentPlan.outerAllocation.size shouldBe 1

        val first = payment.toDecisionResponse("live-nonce-a")
        val second = payment.toDecisionResponse("live-nonce-b")
        first.shouldBeInstanceOf<ManaSourcesSelectedResponse>().apply {
            decisionId shouldBe "live-nonce-a"
            paymentPlan shouldBe payment.paymentPlan
            selectedSources shouldBe emptyList()
            autoPay shouldBe false
        }
        second.decisionId shouldBe "live-nonce-b"
        second.paymentPlan shouldBe payment.paymentPlan
    }

    test("uncompletable public pending payment remains PAYMENT_DOMAIN_UNSUPPORTED") {
        val choice = DeterministicExternalPolicy().choose(
            pendingPaymentObservation(publicTwoProductionDomain().copy(sourceActivationOptions = emptyList())),
            DeterministicPolicyState(policySeed = 2L),
        )

        val gap = choice.shouldBeInstanceOf<SemanticChoice.Gap>()
        gap.code shouldBe "PAYMENT_DOMAIN_UNSUPPORTED"
    }
})

private fun pendingPaymentObservation(domain: PaymentDomainV5): TrainingObservation {
    val player = EntityId("pending-payment-player")
    return TrainingObservation(
        schemaHash = "pending-payment-policy-test",
        perspectivePlayerId = player,
        agentToAct = player,
        turnNumber = 1,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = player,
        priorityPlayerId = player,
        players = emptyList(),
        zones = emptyList(),
        stack = emptyList(),
        pendingDecision = PendingDecisionView(
            decisionId = "live-pending-payment-nonce",
            kind = PendingDecisionKind.SELECT_MANA_SOURCES,
            playerId = player,
            prompt = "Pay {G}",
            requiresStructuredResponse = true,
            structuredDomain = ManaSourcesDomain(
                paymentDomain = domain,
                canDecline = true,
            ),
        ),
        legalActions = emptyList(),
        terminated = false,
        truncated = false,
        winnerId = null,
        stateDigest = "pending-payment-policy-test-digest",
    )
}

private fun publicTwoProductionDomain(): PaymentDomainV5 = PaymentDomainV5(
    requiredCost = "{G}",
    outerAtomicCostUnits = listOf(
        AtomicManaCostUnitV1(
            symbolIndex = 0,
            unitIndexWithinSymbol = 0,
            kind = PaymentCostKindV1.COLORED,
            allowedColors = setOf(PaymentManaColor.GREEN),
        ),
    ),
    initialPoolBuckets = emptyList(),
    sourceActivationOptions = listOf(
        PaymentSourceActivationDomainV2(
            sourceId = EntityId("two-production-source"),
            sourceName = "Public two-production source",
            manaAbilityKey = "public-two-production-ability",
            productionChoices = listOf(
                ProductionChoice(PaymentManaColor.BLACK),
                ProductionChoice(PaymentManaColor.GREEN),
            ),
            atomicActivationManaCostUnits = emptyList(),
            activationSupportKind = PaymentActivationSupportKindV1.FixedManaAndTapSelf,
            deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TapSelf),
            activationCostOrderOptions = listOf(
                listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
            ),
        ),
    ),
)
