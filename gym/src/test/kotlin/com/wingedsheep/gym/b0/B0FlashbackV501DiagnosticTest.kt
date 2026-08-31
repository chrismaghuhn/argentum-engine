package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.actions.spell.CastZoneResolver
import com.wingedsheep.engine.handlers.actions.spell.resolveApplicableAdditionalCostsForCast
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.mechanics.FlashbackGrants
import com.wingedsheep.engine.mechanics.mana.CostCalculator
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidator
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCost
import com.wingedsheep.engine.mechanics.mana.isResolvedFixedAlternativeCastPayment
import com.wingedsheep.engine.mechanics.mana.spellPaymentContextFor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.paymentPlanV3FromPublic
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayReconstructor
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ChoiceSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Characterizes the first target-bearing fixed Flashback V5 gap in the B0 corpus. */
class B0FlashbackV501DiagnosticTest : FunSpec({
    test("fixed targeted Flashback is payment-independent but rejected by target qualification") {
        val spec = B0EpisodeSpec(
            baseSeed = 5L,
            engineSeed = 5L,
            policySeed = -7084831928418434212L,
            rosterOrientation = B0RosterOrientation.CHEVILL_SEAT_0,
            startingPlayer = B0Commander.CHEVILL,
        )
        val run = B0CommanderSoakHarness.create().run(
            spec = spec,
            control = B0RunControl(semanticActionBudget = 2_000, engineProgressBudget = 4_000),
            interruptionProbe = B0InterruptionProbe { progress ->
                if (progress.semanticExternalDecisionCount >= 858) {
                    B0InterruptionRequest.ADMINISTRATIVE_CANCEL
                } else {
                    null
                }
            },
        )

        run.result.engineCommit shouldBe "4b23b75223c0560c6d2b7d08c587952d8b7217f5"
        run.result.closureKind shouldBe B0ClosureKind.FAILED
        run.result.closureReason shouldBe B0ClosureReason.UNSUPPORTED
        run.result.failureClassification shouldBe B0FailureClassification.UNSUPPORTED
        run.result.semanticExternalDecisionCount shouldBe 858
        run.result.engineProgressCount shouldBe 858
        run.result.externalTransitionCount shouldBe 858
        run.failureBundle shouldNotBe null
        run.failureBundle!!.failureStage shouldBe "post-action-observation"
        run.failureBundle!!.restrictedDiagnosticsReference.orEmpty()
            .contains("PAYMENT_DOMAIN_UNSUPPORTED") shouldBe true
        run.failureBundle!!.restrictedDiagnosticsReference.orEmpty()
            .contains("Sevinne's Reclamation") shouldBe true

        val registry = completeRegistry()
        val replay = compactReplay(run, registry)
        val state = ReplayReconstructor(registry, null).reconstructStateAt(
            replay = replay,
            frame = run.acceptedActions.size,
        ) ?: error("Could not reconstruct the seed-5 pre-observation-failure state")
        val actor = requireNotNull(state.priorityPlayerId)
        val legalActions = LegalActionEnumerator.create(registry)
            .enumerate(state, actor, EnumerationMode.ACTIONS_ONLY)
            .filterNot(LegalAction::hasUnfillableTargetRequirement)
        val sevinne = legalActions.single { candidate ->
            candidate.actionType == "CastWithFlashback" &&
                (candidate.action as? CastSpell)?.let { cast ->
                    state.getEntity(cast.cardId)?.get<CardComponent>()?.name == "Sevinne's Reclamation"
                } == true
        }
        val cast = sevinne.action as CastSpell
        val card = requireNotNull(state.getEntity(cast.cardId)?.get<CardComponent>())
        val cardDef = requireNotNull(registry.getCard(card.cardDefinitionId))
        val flashback = requireNotNull(
            FlashbackGrants.effectiveFlashback(
                state = state,
                cardId = cast.cardId,
                cardDef = cardDef,
                controllerId = actor,
                cardRegistry = registry,
                predicateEvaluator = PredicateEvaluator(),
            ),
        )
        val costCalculator = CostCalculator(registry)
        val effectiveCost = costCalculator.calculateEffectiveCostWithAlternativeBase(
            state = state,
            cardDef = cardDef,
            alternativeCost = flashback.cost,
            casterId = actor,
        )
        val targetIds = sevinne.targetRequirements.single().validTargets
        val builder = ObservationBuilder(cardRegistry = registry)
        val observationResult = builder.build(
            state = state,
            perspectivePlayerId = actor,
            legalActions = listOf(sevinne),
        )
        val view = observationResult.observation
            .shouldBeInstanceOf<TrainingObservation>()
            .legalActions
            .single()
        val publicTargetIds = view.targetDomain!!.requirements.single().candidates

        sevinne.actionType shouldBe "CastWithFlashback"
        sevinne.manaCostString shouldBe "{4}{W}"
        sevinne.affordable shouldBe true
        view.manaCost shouldBe "{4}{W}"
        view.targetDomain!!.requirements.single().minTargets shouldBe 1
        view.targetDomain!!.requirements.single().maxTargets shouldBe 1
        publicTargetIds shouldBe targetIds
        observationResult.diagnostics.map { it.code } shouldBe listOf(DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED)
        view.paymentDomain shouldBe null
        view.targetPaymentDomain shouldBe null

        cast.useAlternativeCost shouldBe true
        cast.alternativeCostType shouldBe AlternativeCostType.FLASHBACK
        cast.targets shouldBe emptyList()
        cast.xValue shouldBe null
        cast.alternativePayment shouldBe null
        cast.additionalCostPayment shouldBe null
        cast.declaredCostSlot shouldBe null
        cast.wasWaterbendPaid shouldBe false
        cast.splicedCardIds shouldBe emptyList()
        cast.chosenModes shouldBe emptyList()
        cast.modeTargetsOrdered shouldBe emptyList()
        cast.useWithoutPayingManaCost shouldBe false
        cast.faceIndex shouldBe null
        sevinne.additionalCostInfo shouldBe null
        flashback.cost.toString() shouldBe "{4}{W}"
        flashback.additionalCost shouldBe null

        val applicableAdditionalCosts = resolveApplicableAdditionalCostsForCast(
            state = state,
            action = cast,
            cardDef = cardDef,
            cardRegistry = registry,
            predicateEvaluator = PredicateEvaluator(),
            zoneResolver = CastZoneResolver(registry, ConditionEvaluator()),
        )
        applicableAdditionalCosts shouldBe emptyList()

        val targetCostDependency = targetIds.associateWith { targetId ->
            costCalculator.hasTargetDependentCastCost(
                state = state,
                cardDef = cardDef,
                casterId = actor,
                advertisedCost = effectiveCost,
                legalTargets = listOf(targetId),
                targetCount = 1,
                minimumTargetCount = 1,
                fromZone = Zone.GRAVEYARD,
                declaredCostSlot = cast.declaredCostSlot,
            )
        }
        val effectiveCostsByTarget = targetIds.associateWith {
            costCalculator.calculateEffectiveCostWithAlternativeBase(
                state = state,
                cardDef = cardDef,
                alternativeCost = flashback.cost,
                casterId = actor,
            )
        }
        effectiveCost.toString() shouldBe "{4}{W}"
        targetCostDependency.values.toSet() shouldBe setOf(false)
        effectiveCostsByTarget.values.toSet() shouldBe setOf(effectiveCost)

        val actualTargetProbe = isResolvedFixedAlternativeCastPayment(
            action = cast,
            effectiveCost = effectiveCost,
            hasUnresolvedTargetChoice = true,
            hasApplicableAdditionalCost = false,
        )
        val boundTargetProbe = isResolvedFixedAlternativeCastPayment(
            action = cast.copy(targets = listOf(com.wingedsheep.engine.state.components.stack.ChosenTarget.Card(
                targetIds.first(),
                actor,
                Zone.GRAVEYARD,
            ))),
            effectiveCost = effectiveCost,
            hasUnresolvedTargetChoice = false,
            hasApplicableAdditionalCost = false,
        )
        val targetlessProbe = isResolvedFixedAlternativeCastPayment(
            action = cast.copy(targets = emptyList()),
            effectiveCost = effectiveCost,
            hasUnresolvedTargetChoice = false,
            hasApplicableAdditionalCost = false,
        )
        actualTargetProbe shouldBe false
        boundTargetProbe shouldBe false
        targetlessProbe shouldBe true

        val targetlessLegalAction = sevinne.copy(
            action = cast.copy(targets = emptyList()),
            validTargets = emptyList(),
            requiresTargets = false,
            targetCount = 0,
            minTargets = 0,
            targetDescription = null,
            targetRequirements = emptyList(),
        )
        val targetlessDomain = builder.paymentDomainV5For(state, targetlessLegalAction)
        targetlessDomain shouldNotBe null
        targetlessDomain!!.requiredCost shouldBe "{4}{W}"
        val plan = paymentPlanV3FromPublic(targetlessDomain)
            ?: error("Expected a complete V5/ExplicitV3 plan for the targetless control")
        val paymentContext = spellPaymentContextFor(
            cardComponent = card,
            isFromExile = false,
            isFromHand = false,
        )
        val paymentValidator = PaymentPlanValidator(
            com.wingedsheep.engine.mechanics.mana.ManaSolver(registry),
        )
        val accepted = paymentValidator.validateV3(
            state = state,
            playerId = actor,
            cost = effectiveCost.canonicalPaymentManaCost(),
            plan = plan,
            spellContext = paymentContext,
        )
        accepted.shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
        val wrongCost = paymentValidator.validateV3(
            state = state,
            playerId = actor,
            cost = ManaCost.parse("{3}{W}").canonicalPaymentManaCost(),
            plan = plan,
            spellContext = paymentContext,
        )
        wrongCost.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        println(
            "B0_FLASHBACK_V5_01 head=${run.result.engineCommit} " +
                "episode=${spec.episodeId} engineSeed=${spec.engineSeed} " +
                "policySeed=${spec.policySeed} decision=858 actor=$actor " +
                "card=Sevinne's Reclamation actionType=${sevinne.actionType} " +
                "cost={4}{W} affordable=${sevinne.affordable} " +
                "targetIds=$targetIds targetCostDependency=$targetCostDependency " +
                "applicableAdditionalCosts=$applicableAdditionalCosts " +
                "publicPaymentDomain=${view.paymentDomain} " +
                "targetlessPaymentDomain=$targetlessDomain " +
                "targetlessPlan=$plan acceptedPaymentPlan=$accepted " +
                "wrongCost=$wrongCost firstQualificationGate=target-choice " +
                "targetlessProbe=$targetlessProbe",
        )
    }
})

private fun completeRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun compactReplay(run: B0EpisodeRun, registry: CardRegistry): CompactReplay {
    val config = requireNotNull(run.replayConfig) { "B0 run has no replay config" }
    val deckResolver = com.wingedsheep.gym.service.DeckResolver(registry)
    val players = config.players.map { player ->
        ReplayPlayerSetup(
            playerId = requireNotNull(player.playerId).value,
            name = player.name,
            deck = deckResolver.resolve(player.deck),
            startingLife = player.startingLife,
            commanderCardName = player.commanderCardName,
        )
    }
    val roster = config.players.mapIndexed { index, player ->
        ServerMessage.PlayerSeatInfo(
            playerId = requireNotNull(player.playerId).value,
            name = player.name,
            seatIndex = index,
        )
    }
    val setup = ReplaySetup(
        seed = requireNotNull(config.seed),
        format = config.format,
        attackMode = AttackMode.MULTIPLE,
        startingHandSize = config.startingHandSize,
        skipMulligans = config.skipMulligans,
        useHandSmoother = config.useHandSmoother,
        startingPlayerIndex = config.startingPlayerIndex,
        players = players,
        seatRoster = roster,
    )
    return CompactReplay(
        gameId = run.result.episodeId,
        players = config.players.map { player ->
            ReplayPlayerInfo(
                playerId = requireNotNull(player.playerId).value,
                name = player.name,
            )
        },
        startedAt = "1970-01-01T00:00:00Z",
        endedAt = "1970-01-01T00:00:00Z",
        winnerName = run.result.winner,
        setup = setup,
        actions = run.acceptedActions,
        engineVersion = run.result.engineCommit,
    )
}
