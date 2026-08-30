package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.mechanics.combat.CombatObjectOrder
import com.wingedsheep.engine.mechanics.cost.ActivatedAbilityCostCalculator
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.PaidManaSourceTimingCertifier
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PaymentDomainBuilder
import com.wingedsheep.gym.paymentPlanV3FromPublic
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.EnvId
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.engine.mechanics.mana.canonicalPaymentManaCost
import com.wingedsheep.engine.mechanics.mana.isFixedOrdinaryManaCost
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path

/**
 * RED-only characterization for B0-PAYMENT-DOMAIN-01. The test deliberately reproduces the
 * exact decision-1027 state and records the complete action/request/builder boundary without
 * changing the B0 policy, locked decks, PR #107, or production semantics.
 */
class B0PaymentDomain1027DiagnosticTest : FunSpec({
    test("characterizes all four PAYMENT_DOMAIN_UNSUPPORTED offenders at decision 1027") {
        val harness = B0CommanderSoakHarness.create()
        val service = privateField<MultiEnvService>(harness, "service")
        val cardRegistry = privateField<CardRegistry>(harness, "cardRegistry")
        val spec = B0EpisodeSpec.fourForBaseSeed(0L)[3]
        val config = privateMethod(harness, "toEnvConfig").invoke(harness, spec) as EnvConfig
        val preCreated = service.create(config)
        val failingCreated = service.create(config)

        try {
            val drive = privateDriveMethod(harness)
            val preRun = drive.invoke(
                harness,
                spec,
                B0RunControl(semanticActionBudget = 2_000, engineProgressBudget = 4_000),
                config,
                preCreated.envId.value,
                preCreated.observation,
                B0InterruptionProbe { progress ->
                    if (progress.semanticExternalDecisionCount >= 1026) {
                        B0InterruptionRequest.ADMINISTRATIVE_CANCEL
                    } else {
                        null
                    }
                },
            ) as B0EpisodeRun
            val failingRun = drive.invoke(
                harness,
                spec,
                B0RunControl(semanticActionBudget = 2_000, engineProgressBudget = 4_000),
                config,
                failingCreated.envId.value,
                failingCreated.observation,
                B0InterruptionProbe { null },
            ) as B0EpisodeRun

            val preEnvironment = liveGameEnv(service, preCreated.envId).environment
            val postEnvironment = liveGameEnv(service, failingCreated.envId).environment
            val preState = preEnvironment.state
            val postState = postEnvironment.state
            val preActor = preState.priorityPlayerId ?: error("Decision-1026 has no priority player")
            val actor = postState.priorityPlayerId ?: error("Decision-1027 has no priority player")
            val transition = failingRun.acceptedActions.lastOrNull()
                ?: error("B0 run has no transition action")
            val observation = service.diagnosticTrainingObservation(failingCreated.envId)
            val postLegalActions = postEnvironment.legalActions()
            val payableViews = observation.legalActions.filter { it.manaCost != null }
            val offenderViews = payableViews.filter { it.paymentDomain == null }
            val offenders = offenderViews.map { view ->
                postLegalActions.single { legalAction ->
                    legalAction.actionType == view.kind &&
                        legalAction.description == view.description &&
                        legalAction.manaCostString == view.manaCost &&
                        legalActionSourceId(legalAction) == view.sourceEntityId
                }
            }

            val report = buildString {
                appendLine("B0-PAYMENT-DOMAIN-01 DECISION-1027 PAYMENT DIAGNOSTIC")
                appendLine("MODE=CHARACTERIZATION_RED_ONLY")
                appendLine("CHECKOUT_HEAD=${gitHead()}")
                appendLine("EPISODE_ID=${spec.episodeId}")
                appendLine("ENGINE_SEED=${spec.engineSeed}")
                appendLine("POLICY_SEED=${spec.policySeed}")
                appendLine("PRE_DECISION_COUNT=${preRun.result.semanticExternalDecisionCount}")
                appendLine("POST_DECISION_COUNT=${failingRun.result.semanticExternalDecisionCount}")
                appendLine("TRANSITION=decision-1026 -> decision-1027")
                appendLine("TRANSITION_ACTION=$transition")
                appendLine("TRANSITION_ACTION_TYPE=${transition.javaClass.simpleName}")
                appendLine("PRE_ACTOR=${preActor.value}")
                appendLine("POST_ACTOR=${actor.value}")
                appendLine("PHASE=${postState.phase}")
                appendLine("STEP=${postState.step}")
                appendLine("TURN=${postState.turnNumber}")
                appendLine("RUN_CLOSURE=${failingRun.result.closureKind}/${failingRun.result.closureReason}")
                appendLine("RUN_FAILURE_STAGE=${failingRun.failureBundle?.failureStage}")
                appendLine("RUN_FAILURE_REFERENCE=${failingRun.failureBundle?.restrictedDiagnosticsReference}")
                appendLine("ROOT_CLASSIFICATION=MIXED_SHARED_AND_ACTION_SPECIFIC_ROOT_CAUSES")
                appendLine("SHARED_EQUIP_ROOT_CAUSE=Fervent Champion makes one public equip target cheaper")
                appendLine("STRONGHOLD_ROOT_CAUSE=unaffordable action is omitted from public payment domain")
                appendLine()

                appendLine("PUBLIC_PAYABLE_COUNT=${payableViews.size}")
                appendLine("PUBLIC_UNPUBLISHED_COUNT=${offenderViews.size}")
                offenderViews.forEachIndexed { index, view ->
                    appendLine(
                        "PUBLIC_OFFENDER[$index]=" +
                            "sourceEntityId=${view.sourceEntityId}," +
                            "kind=${view.kind},description=${view.description}," +
                            "manaCost=${view.manaCost},alternativePayment=<from LegalAction>," +
                            "requiredPayloadFields=${view.requiredPayloadFields}," +
                            "affordable=${view.affordable},paymentDomainPresent=${view.paymentDomain != null}," +
                            "targetDomain=${view.targetDomain}",
                    )
                }

                appendLine("POST_PLAYER_MANA_POOL")
                append(manaPoolReport(postState, actor))
                appendLine("DISCOVERED_MANA_SOURCES")
                append(sourceInventory(postState, actor, cardRegistry))
                appendLine("ACTION_RECORDS")
                offenders.forEachIndexed { index, legalAction ->
                    appendLine("ACTION[$index]")
                    append(actionReport(postState, actor, legalAction, cardRegistry, offenderViews[index]))
                }
            }

            val output = Path.of(
                System.getProperty(
                    "b0.diagnosticOutput",
                    "C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b0-payment-domain-1027/gym/build/reports/b0/task-b0-payment-domain-01/decision-1027-diagnostic.txt",
                ),
            )
            Files.createDirectories(output.parent)
            Files.writeString(output, report)
            println("DIAGNOSTIC_REPORT_PATH=$output")

            preRun.result.semanticExternalDecisionCount shouldBe 1026
            preRun.result.closureReason shouldBe B0ClosureReason.ADMINISTRATIVE_CANCEL
            failingRun.result.semanticExternalDecisionCount shouldBe 1027
            failingRun.result.engineProgressCount shouldBe 1027
            failingRun.result.externalTransitionCount shouldBe 1027
            failingRun.result.failureClassification shouldBe B0FailureClassification.UNSUPPORTED
            failingRun.failureBundle?.failureStage shouldBe "post-action-observation"
            failingRun.failureBundle?.restrictedDiagnosticsReference
                ?.contains("PAYMENT_DOMAIN_UNSUPPORTED") shouldBe true
            transition.javaClass.simpleName shouldBe "PassPriority"
            preEnvironment.stepCount shouldBe 1026
            postEnvironment.stepCount shouldBe 1027

            offenderViews.size shouldBe 4
            offenders.size shouldBe 4
            offenders.mapNotNull(::legalActionSourceId).map(EntityId::value) shouldBe
                listOf("e164", "e136", "e162", "e165")
            offenderViews.map { it.requiredPayloadFields } shouldBe listOf(
                listOf("targets", "paymentStrategy"),
                listOf("paymentStrategy", "costPayment"),
                listOf("targets", "paymentStrategy"),
                listOf("targets", "paymentStrategy"),
            )
            offenderViews.map { it.affordable } shouldBe listOf(true, false, true, true)
            offenders.map { it.actionType } shouldBe List(4) { "ActivateAbility" }
            offenders.map { it.manaCostString } shouldBe listOf("{0}", "{R}{W}", "{0}", "{0}")
            offenders.map { legalAction ->
                ObservationBuilder(cardRegistry = cardRegistry).paymentDomainV5For(postState, legalAction) != null
            } shouldBe listOf(false, true, false, false)
            offenders.map { legalAction ->
                firstNullStage(postState, legalAction, offenderViewFor(legalAction, offenderViews), cardRegistry)
            } shouldBe listOf(
                "PAYMENT_REQUEST:EQUIP_PAYMENT_UNSUPPORTED:{e147=Atom(atom=Mana(cost={2}))}",
                "PUBLIC_VIEW_OMITS_UNAFFORDABLE_ACTION",
                "PAYMENT_REQUEST:EQUIP_PAYMENT_UNSUPPORTED:{e147=Atom(atom=Mana(cost={1}))}",
                "PAYMENT_REQUEST:EQUIP_PAYMENT_UNSUPPORTED:{e147=Atom(atom=Mana(cost={1}))}",
            )

            val equipActions = offenders.filter { it.description == "Equip {0}" }
            equipActions.size shouldBe 3
            equipActions.forEach { legalAction ->
                val action = legalAction.action as ActivateAbility
                action.alternativePayment shouldBe null
                legalAction.additionalCostInfo shouldBe null
                val ability = invokeDeclared(
                    ObservationBuilder(cardRegistry = cardRegistry),
                    "resolveActivatedAbility",
                    postState,
                    action,
                ) as ActivatedAbility
                invokeDeclared(
                    ObservationBuilder(cardRegistry = cardRegistry),
                    "isSupportedEquipPayment",
                    postState,
                    legalAction,
                    action,
                    ability,
                    legalAction.manaCostString,
                ) shouldBe false
                equipTargetCostMismatches(postState, legalAction, ability, cardRegistry)
                    .isNotEmpty() shouldBe true
            }

            val stronghold = offenders.single { legalActionSourceId(it)?.value == "e136" }
            val strongholdAction = stronghold.action as ActivateAbility
            strongholdAction.alternativePayment shouldBe null
            stronghold.additionalCostInfo shouldBe null
            stronghold.manaCostString shouldBe "{R}{W}"
            stronghold.targetRequirements shouldBe emptyList()
            val strongholdAbility = invokeDeclared(
                ObservationBuilder(cardRegistry = cardRegistry),
                "resolveActivatedAbility",
                postState,
                strongholdAction,
            ) as ActivatedAbility
            strongholdAbility.id.value shouldBe "ability_2228"
            val strongholdEffectiveCost = effectiveAbilityCost(postState, strongholdAction, strongholdAbility, cardRegistry)
            strongholdEffectiveCost.toString() shouldBe
                "Composite(costs=[Atom(atom=Mana(cost={R}{W})), Tap])"
            val strongholdRequest = invokeDeclared(
                ObservationBuilder(cardRegistry = cardRegistry),
                "paymentDomainRequestFor",
                postState,
                stronghold,
            ) ?: error("Stronghold payment request must resolve")
            field<String>(strongholdRequest, "requiredCost") shouldBe "{R}{W}"
            field<Set<EntityId>>(strongholdRequest, "excludeSources") shouldBe setOf(EntityId("e136"))
            invokeDeclared(
                ObservationBuilder(cardRegistry = cardRegistry),
                "deterministicAdditionalCostPaymentFor",
                postState,
                stronghold,
            ).toString() shouldBe
                "AdditionalCostPayment(sacrificedPermanents=[], discardedCards=[], lifePaid=0, exiledCards=[], variableCostPermanents=[], beheldCards=[], tappedPermanents=[e136], bouncedPermanents=[], blightTargets=[], blightAmount=0, payXLifeAmount=0, distributedCounterRemovals=[])"
            ObservationBuilder(cardRegistry = cardRegistry)
                .paymentDomainV5For(postState, stronghold) shouldNotBe null
            offenderViewFor(stronghold, offenderViews).affordable shouldBe false
            offenderViewFor(stronghold, offenderViews).paymentDomain shouldBe null

            val discovered = ManaSolver(cardRegistry).findAvailableManaSources(
                state = postState,
                playerId = actor,
                spellContext = null,
                paymentOrderRequired = true,
            )
            discovered shouldBe emptyList()
            CombatObjectOrder.order(postState, discovered.map { it.entityId }) shouldBe emptyList()
        } finally {
            service.dispose(listOf(preCreated.envId, failingCreated.envId))
        }
    }
})

private fun actionReport(
    state: GameState,
    playerId: EntityId,
    legalAction: LegalAction,
    cardRegistry: CardRegistry,
    publicView: LegalActionView,
): String = buildString {
    val action = legalAction.action as ActivateAbility
    val observationBuilder = ObservationBuilder(cardRegistry = cardRegistry)
    val source = state.getEntity(action.sourceId)?.get<CardComponent>()
    val ability = invokeDeclared(observationBuilder, "resolveActivatedAbility", state, action) as? ActivatedAbility
    val effective = ability?.let { effectiveAbilityCost(state, action, it, cardRegistry) }
    val request = invokeDeclared(observationBuilder, "paymentDomainRequestFor", state, legalAction)
    val directDomain = if (request != null) {
        val requestPlayer = EntityId(field<String>(request, "playerId"))
        val requiredCost = field<String>(request, "requiredCost")
        val spellContext = field<Any>(request, "spellContext")
        val excludedSources = field<Set<EntityId>>(request, "excludeSources")
        val reserved = invokeDeclared(observationBuilder, "reservedOuterLifePaymentForV5", state, legalAction) as? Int
        if (reserved != null) {
            paymentDomainBuilder(cardRegistry).buildV5(
                state = state,
                playerId = requestPlayer,
                requiredCost = requiredCost,
                spellContext = spellContext as com.wingedsheep.engine.mechanics.mana.SpellPaymentContext,
                excludeSources = excludedSources,
                reservedOuterLifePayment = reserved,
            )
        } else {
            null
        }
    } else {
        null
    }
    appendLine("sourceEntityId=${action.sourceId.value}")
    appendLine("sourceCardName=${source?.name}")
    appendLine("sourceCardDefinitionId=${source?.cardDefinitionId}")
    appendLine("actionType=${legalAction.actionType}")
    appendLine("abilityIdentity=${action.abilityId.value}")
    appendLine("resolvedAbilityIdentity=${ability?.id?.value}")
    appendLine("abilityProvenance=${ability}")
    appendLine("effectiveAbilityCost=$effective")
    appendLine("manaCostString=${legalAction.manaCostString}")
    appendLine("alternativePayment=${action.alternativePayment}")
    appendLine("additionalCostInfo=${legalAction.additionalCostInfo}")
    appendLine("targetDomainSupport=${legalAction.targetDomainSupport}")
    appendLine("targetRequirements=${legalAction.targetRequirements}")
    appendLine("requiredPayloadFields=${publicView.requiredPayloadFields}")
    appendLine("affordable=${legalAction.affordable}")
    appendLine("publicViewAffordable=${publicView.affordable}")
    appendLine("publicViewPaymentDomainPresent=${publicView.paymentDomain != null}")
    appendLine("directPaymentDomainV5Produced=${directDomain != null}")
    appendLine("publicPaymentPlanFromDirectDomain=${directDomain?.let(::paymentPlanV3FromPublic) != null}")
    appendLine("firstNullStage=${firstNullStage(state, legalAction, publicView, cardRegistry)}")
    if (ability != null && ability.isEquipAbility) {
        appendLine("isSupportedEquipPayment=${invokeDeclared(
            observationBuilder,
            "isSupportedEquipPayment",
            state,
            legalAction,
            action,
            ability,
            legalAction.manaCostString,
        )}")
        appendLine("equipTargetCostMismatches=${equipTargetCostMismatches(state, legalAction, ability, cardRegistry)}")
    }
    if (request != null) {
        appendLine("paymentDomainRequest=$request")
        appendLine("requestPlayer=${field<String>(request, "playerId")}")
        appendLine("requiredCost=${field<String>(request, "requiredCost")}")
        appendLine("spellContext=${field<Any>(request, "spellContext")}")
        appendLine("excludeSources=${field<Set<EntityId>>(request, "excludeSources")}")
        appendLine("reservedOuterLifePayment=${invokeDeclared(
            observationBuilder,
            "reservedOuterLifePaymentForV5",
            state,
            legalAction,
        )}")
        appendLine("deterministicAdditionalCostPayment=${invokeDeclared(
            observationBuilder,
            "deterministicAdditionalCostPaymentFor",
            state,
            legalAction,
        )}")
        appendLine("paymentDomainBuilderNullGate=${builderNullGate(state, request, cardRegistry)}")
    } else {
        appendLine("paymentDomainRequest=null")
        appendLine("paymentDomainRequestNullGate=${requestNullGate(state, legalAction, cardRegistry)}")
        appendLine("reservedOuterLifePayment=${invokeDeclared(
            observationBuilder,
            "reservedOuterLifePaymentForV5",
            state,
            legalAction,
        )}")
    }
}

private fun firstNullStage(
    state: GameState,
    legalAction: LegalAction,
    publicView: LegalActionView,
    cardRegistry: CardRegistry,
): String {
    val builder = ObservationBuilder(cardRegistry = cardRegistry)
    val request = invokeDeclared(builder, "paymentDomainRequestFor", state, legalAction)
        ?: return "PAYMENT_REQUEST:${requestNullGate(state, legalAction, cardRegistry)}"
    val reserved = invokeDeclared(builder, "reservedOuterLifePaymentForV5", state, legalAction) as? Int
        ?: return "RESERVED_OUTER_LIFE_NULL"
    val requestPlayer = EntityId(field<String>(request, "playerId"))
    val requiredCost = field<String>(request, "requiredCost")
    val spellContext = field<com.wingedsheep.engine.mechanics.mana.SpellPaymentContext>(request, "spellContext")
    val excludedSources = field<Set<EntityId>>(request, "excludeSources")
    val domain = paymentDomainBuilder(cardRegistry).buildV5(
        state = state,
        playerId = requestPlayer,
        requiredCost = requiredCost,
        spellContext = spellContext,
        excludeSources = excludedSources,
        reservedOuterLifePayment = reserved,
    ) ?: return "PAYMENT_DOMAIN_BUILDER_NULL"
    if (!publicView.affordable) return "PUBLIC_VIEW_OMITS_UNAFFORDABLE_ACTION"
    if (publicView.paymentDomain == null) return "PUBLIC_VIEW_PAYMENT_DOMAIN_NULL"
    if (paymentPlanV3FromPublic(domain) == null) return "PUBLIC_PLAN_UNAVAILABLE"
    return "NONE"
}

private fun requestNullGate(
    state: GameState,
    legalAction: LegalAction,
    cardRegistry: CardRegistry,
): String {
    val action = legalAction.action as? ActivateAbility ?: return "ACTION_KIND_UNSUPPORTED"
    val builder = ObservationBuilder(cardRegistry = cardRegistry)
    val ability = invokeDeclared(builder, "resolveActivatedAbility", state, action) as? ActivatedAbility
        ?: return "ACTIVATED_ABILITY_UNRESOLVED"
    if (invokeDeclared(builder, "deterministicAdditionalCostPaymentFor", state, legalAction) == null) {
        return "DETERMINISTIC_ADDITIONAL_COST_UNRESOLVED"
    }
    if (legalAction.hasXCost) return "ACTIVATE_HAS_X_COST"
    if (legalAction.hasConvoke) return "ACTIVATE_HAS_CONVOKE"
    if (legalAction.hasTapForGeneric) return "ACTIVATE_HAS_TAP_FOR_GENERIC"
    if (action.alternativePayment != null) return "ACTIVATE_HAS_ALTERNATIVE_PAYMENT"
    if (ability.hasConvoke) return "ABILITY_HAS_CONVOKE"
    if (ability.hasWaterbend) return "ABILITY_HAS_WATERBEND"
    if (ability.isEquipAbility) {
        val supported = invokeDeclared(
            builder,
            "isSupportedEquipPayment",
            state,
            legalAction,
            action,
            ability,
            legalAction.manaCostString,
        ) as? Boolean
        if (supported != true) {
            return "EQUIP_PAYMENT_UNSUPPORTED:${equipTargetCostMismatches(state, legalAction, ability, cardRegistry)}"
        }
    }
    if (ability.genericCostReduction != null && ability.targetRequirements.isNotEmpty()) {
        return "TARGET_DEPENDENT_ABILITY_COST"
    }
    return "ACTIVATE_REQUEST_NULL_UNEXPLAINED"
}

private fun builderNullGate(
    state: GameState,
    request: Any,
    cardRegistry: CardRegistry,
): String {
    val requiredCost = field<String>(request, "requiredCost")
    val playerId = EntityId(field<String>(request, "playerId"))
    val spellContext = field<com.wingedsheep.engine.mechanics.mana.SpellPaymentContext>(request, "spellContext")
    val excludedSources = field<Set<EntityId>>(request, "excludeSources")
    val reserved = 0
    val parsed = runCatching { ManaCost.parse(requiredCost) }.getOrNull()
        ?: return "COST_PARSE"
    if (!parsed.isFixedOrdinaryManaCost()) return "COST_NOT_FIXED_ORDINARY"
    val discovered = ManaSolver(cardRegistry).findAvailableManaSources(
        state = state,
        playerId = playerId,
        spellContext = null,
        paymentOrderRequired = true,
    ).filter { it.entityId !in excludedSources }
    if (CombatObjectOrder.order(state, discovered.map { it.entityId }) == null) {
        return "SOURCE_ORDER_UNAVAILABLE"
    }
    return "NONE(discovered=${discovered.map { it.entityId.value }},reserved=$reserved,spellContext=$spellContext)"
}

private fun equipTargetCostMismatches(
    state: GameState,
    legalAction: LegalAction,
    ability: ActivatedAbility,
    cardRegistry: CardRegistry,
): Map<EntityId, AbilityCost> {
    val advertisedCost = ManaCost.parse(legalAction.manaCostString ?: error("Equip has no mana cost"))
        .canonicalPaymentManaCost()
        .let { AbilityCost.Atom(CostAtom.Mana(it)) }
    val action = legalAction.action as ActivateAbility
    val calculator = activatedAbilityCostCalculator(cardRegistry)
    return legalAction.targetRequirements.single().validTargets.associateWith { targetId ->
        calculator.calculate(
            state = state,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            ability = ability,
            targets = listOf(ChosenTarget.Permanent(targetId)),
        )
    }.filterValues { it != advertisedCost }
}

private fun effectiveAbilityCost(
    state: GameState,
    action: ActivateAbility,
    ability: ActivatedAbility,
    cardRegistry: CardRegistry,
): AbilityCost = activatedAbilityCostCalculator(cardRegistry).calculate(
    state = state,
    sourceId = action.sourceId,
    controllerId = action.playerId,
    ability = ability,
    targets = action.targets,
    equipPayment = action.alternativePayment?.equipPayment,
)

private fun paymentDomainBuilder(cardRegistry: CardRegistry): PaymentDomainBuilder = PaymentDomainBuilder(
    manaSolver = ManaSolver(cardRegistry),
    visibility = Visibility(cardRegistry),
    activatedAbilityCostCalculator = activatedAbilityCostCalculator(cardRegistry),
    paidManaSourceTimingCertifier = PaidManaSourceTimingCertifier.fixedFirstSlice(cardRegistry),
)

private fun activatedAbilityCostCalculator(cardRegistry: CardRegistry): ActivatedAbilityCostCalculator =
    ActivatedAbilityCostCalculator(
        CastPermissionUtils(cardRegistry, PredicateEvaluator(), ConditionEvaluator()),
    )

private fun manaPoolReport(state: GameState, playerId: EntityId): String {
    val pool = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
    return buildString {
        appendLine("white=${pool.white},blue=${pool.blue},black=${pool.black},red=${pool.red},green=${pool.green},colorless=${pool.colorless}")
        appendLine("restrictedMana=${pool.restrictedMana}")
        appendLine("manaProvenanceKnownTo=${pool.manaProvenanceKnownTo.map(EntityId::value).sorted()}")
        appendLine("manaBySource=${pool.manaBySource.mapKeys { it.key.value }}")
        appendLine("manaBySourceAndColor=${pool.manaBySourceAndColor.mapKeys { it.key.value }}")
        appendLine("manaBySubtype=${pool.manaBySubtype}")
        appendLine("manaByFloatingBucket=${pool.manaByFloatingBucket}")
    }
}

private fun sourceInventory(state: GameState, playerId: EntityId, cardRegistry: CardRegistry): String {
    val sources = ManaSolver(cardRegistry).findAvailableManaSources(
        state = state,
        playerId = playerId,
        spellContext = null,
        paymentOrderRequired = true,
    )
    val ordered = CombatObjectOrder.order(state, sources.map { it.entityId })
    return buildString {
        appendLine("count=${sources.size}")
        appendLine("rulesOrder=${ordered?.map(EntityId::value)}")
        val byId = sources.associateBy { it.entityId }
        ordered.orEmpty().forEach { sourceId ->
            val source = byId.getValue(sourceId)
            appendLine(
                "sourceId=${source.entityId.value},name=${source.name}," +
                    "abilityOrder=${source.paymentManaAbilityOrder}," +
                    "orderCertified=${source.paymentManaAbilityOrderCertified}," +
                    "restrictionsCertified=${source.paymentManaSpendingRestrictionsCertified}," +
                    "stabilityCertified=${source.paymentManaExecutionStabilityCertified}," +
                    "profiles=${source.paymentManaProductionProfiles}," +
                    "sideEffects=${source.paymentManaSideEffectCertificates}",
            )
        }
    }
}

private fun legalActionSourceId(legalAction: LegalAction): EntityId? = when (val action = legalAction.action) {
    is ActivateAbility -> action.sourceId
    is CastSpell -> action.cardId
    else -> null
}

private fun offenderViewFor(legalAction: LegalAction, views: List<LegalActionView>): LegalActionView = views.single {
    it.sourceEntityId == legalActionSourceId(legalAction) &&
        it.kind == legalAction.actionType &&
        it.description == legalAction.description &&
        it.manaCost == legalAction.manaCostString
}

private fun liveGameEnv(service: MultiEnvService, envId: EnvId): com.wingedsheep.gym.GameGymEnv {
    val envs = privateField<Any>(service, "envs")
    @Suppress("UNCHECKED_CAST")
    return (envs as Map<Any?, Any?>).entries.first { it.key.toString() == envId.value }.value as com.wingedsheep.gym.GameGymEnv
}

private fun <T> privateField(target: Any, name: String): T {
    val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return field.get(target) as T
}

private fun privateMethod(target: Any, name: String): Method = target.javaClass.declaredMethods
    .first { it.name == name }
    .apply { isAccessible = true }

private fun privateDriveMethod(target: Any): Method = target.javaClass.declaredMethods
    .first { it.name.startsWith("drive-") && it.parameterTypes.size == 6 }
    .apply { isAccessible = true }

private fun invokeDeclared(target: Any, name: String, vararg args: Any?): Any? = target.javaClass.declaredMethods
    .first { it.name == name && it.parameterTypes.size == args.size }
    .apply { isAccessible = true }
    .invoke(target, *args)

private fun <T> field(target: Any, name: String): T {
    val value = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    @Suppress("UNCHECKED_CAST")
    return value as T
}

private fun gitHead(): String = ProcessBuilder("git", "rev-parse", "HEAD")
    .redirectErrorStream(true)
    .start()
    .let { process ->
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { "Could not resolve checkout HEAD: $value" }
        value
    }
