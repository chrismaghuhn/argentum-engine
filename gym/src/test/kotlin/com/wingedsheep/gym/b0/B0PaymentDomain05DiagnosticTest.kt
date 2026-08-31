package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.legalactions.utils.CastPermissionUtils
import com.wingedsheep.engine.mechanics.combat.CombatObjectOrder
import com.wingedsheep.engine.mechanics.cost.ActivatedAbilityCostCalculator
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gym.contract.ActionTargetDomainV1
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.TargetPaymentDomainV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.paymentPlanV3FromPublic
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayReconstructor
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.sdk.scripting.ActivatedAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * B0_PAYMENT_DOMAIN_05 — diagnostic-only characterization of the first new payment-domain
 * trust gap at decision 564. The test records both Equip actions independently and does not
 * change production code, policy, decks, seeds, or the B0 overlay.
 */
class B0PaymentDomain05DiagnosticTest : FunSpec({
    test("characterizes both Equip PAYMENT_DOMAIN_UNSUPPORTED actions at decision 564") {
        val exactSpec = B0EpisodeSpec(
            baseSeed = 2L,
            engineSeed = 2L,
            policySeed = 3160949986217547037L,
            rosterOrientation = B0RosterOrientation.CHEVILL_SEAT_0,
            startingPlayer = B0Commander.CHEVILL,
        )
        val run = B0CommanderSoakHarness.create().run(
            spec = exactSpec,
            control = B0RunControl(semanticActionBudget = 2_000, engineProgressBudget = 4_000),
        )

        run.result.closureKind shouldBe B0ClosureKind.FAILED
        run.result.closureReason shouldBe B0ClosureReason.UNSUPPORTED
        run.result.semanticExternalDecisionCount shouldBe 564
        run.result.engineProgressCount shouldBe 564
        run.result.externalTransitionCount shouldBe 564
        run.failureBundle?.failureStage shouldBe "post-action-observation"
        run.failureBundle?.restrictedDiagnosticsReference shouldNotBe null
        run.failureBundle?.restrictedDiagnosticsReference
            ?.contains("PAYMENT_DOMAIN_UNSUPPORTED") shouldBe true

        val registry = completeRegistry()
        val replay = compactReplay(run, registry)
        val state = ReplayReconstructor(registry, null).reconstructStateAt(
            replay = replay,
            frame = run.acceptedActions.size,
        ) ?: error("Could not reconstruct the exact post-transition state")
        state.pendingDecision shouldBe null
        val actor = requireNotNull(state.priorityPlayerId) { "Decision 564 has no priority player" }

        val legalActions = LegalActionEnumerator.create(registry)
            .enumerate(state, actor, EnumerationMode.ACTIONS_ONLY)
            .filterNot(LegalAction::hasUnfillableTargetRequirement)
        val builder = ObservationBuilder(cardRegistry = registry)
        val observation = builder.build(
            state = state,
            perspectivePlayerId = actor,
            legalActions = legalActions,
        )
        val view = observation.observation as? TrainingObservation
            ?: error("Expected TrainingObservation")
        val equipViews = view.legalActions.filter { publicView ->
            publicView.kind == "ActivateAbility" && publicView.description.startsWith("Equip ")
        }
        equipViews.size shouldBe 2
        equipViews.map { it.description }.toSet() shouldBe setOf("Equip {1}", "Equip {0}")
        println("EQUIP_VIEWS=$equipViews")
        observation.diagnostics.map { it.code.name } shouldBe listOf("PAYMENT_DOMAIN_UNSUPPORTED")

        val records = equipViews.map { publicView ->
            val legalAction = legalActions.singleOrNull { candidate ->
                candidate.actionType == publicView.kind &&
                    candidate.description == publicView.description &&
                    actionSourceId(candidate) == publicView.sourceEntityId
            } ?: error("Could not bind public ${publicView.description} to a Rules LegalAction")
            diagnoseEquip(
                state = state,
                actor = actor,
                legalAction = legalAction,
                publicView = publicView,
                builder = builder,
                cardRegistry = registry,
            )
        }

        val report = buildReport(
            exactSpec = exactSpec,
            run = run,
            state = state,
            actor = actor,
            observation = observation,
            records = records,
        )
        val output = Path.of(
            System.getProperty(
                "b0.diagnosticOutput",
                "C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b0-payment-domain-05/decision-564-diagnostic.txt",
            ),
        )
        Files.createDirectories(output.parent)
        Files.writeString(output, report)
        println("DIAGNOSTIC_REPORT_PATH=$output")
        println(report)

        records.single { it.publicView.description == "Equip {1}" }.publicView.manaCost shouldBe "{1}"
        records.single { it.publicView.description == "Equip {0}" }.publicView.manaCost shouldBe "{0}"
        records.map { it.legalAction.action.let { action -> (action as ActivateAbility).sourceId } }
            .toSet() shouldBe setOf(EntityId("e161"))
        records.map { it.ability?.id?.value }.toSet() shouldBe setOf("ability_1585")
        records.map { it.ability?.isEquipAbility }.toSet() shouldBe setOf(true)

        val normalEquip = records.single { it.publicView.description == "Equip {1}" }
        normalEquip.publicView.affordable shouldBe false
        normalEquip.publicView.targetPaymentDomain shouldBe null
        normalEquip.publicView.paymentDomain shouldBe null
        normalEquip.targetDependency.toString() shouldBe "INDEPENDENT"
        normalEquip.targetQualification.toString() shouldBe "NotApplicable"
        normalEquip.targetPaymentDomain shouldBe null
        normalEquip.supportedEquipPayment shouldBe false
        normalEquip.paymentRequest shouldBe null
        normalEquip.paymentDomainV5 shouldBe null
        firstPaymentRequestNullGate(normalEquip) shouldBe "ALTERNATIVE_PAYMENT_PRESENT"
        (normalEquip.legalAction.action as ActivateAbility).alternativePayment?.equipPayment
            .toString() shouldBe "NORMAL"
        normalEquip.legalAction.targetRequirements shouldBe emptyList()

        val freeFirstEquip = records.single { it.publicView.description == "Equip {0}" }
        freeFirstEquip.publicView.affordable shouldBe true
        freeFirstEquip.publicView.targetPaymentDomain shouldBe null
        freeFirstEquip.publicView.paymentDomain shouldBe null
        freeFirstEquip.targetDependency.toString() shouldBe "INDEPENDENT"
        freeFirstEquip.targetQualification.toString() shouldBe "NotApplicable"
        freeFirstEquip.supportedEquipPayment shouldBe true
        freeFirstEquip.paymentRequest shouldBe null
        freeFirstEquip.paymentDomainV5 shouldBe null
        firstPaymentRequestNullGate(freeFirstEquip) shouldBe "ALTERNATIVE_PAYMENT_PRESENT"
        (freeFirstEquip.legalAction.action as ActivateAbility).alternativePayment?.equipPayment
            .toString() shouldBe "FREE_FIRST_EQUIP"
        freeFirstEquip.legalAction.targetRequirements.size shouldBe 1
        freeFirstEquip.targetDomain?.requirements?.singleOrNull()?.candidates shouldBe listOf(
            EntityId("e141"),
            EntityId("e157"),
            EntityId("e159"),
        )
        val directRelation = requireNotNull(freeFirstEquip.targetPaymentDomain)
        directRelation.targetBindings.map { it.target } shouldBe listOf(
            EntityId("e141"),
            EntityId("e157"),
            EntityId("e159"),
        )
        directRelation.targetBindings.forEach { binding ->
            binding.affordable shouldBe true
            binding.paymentDomain.requiredCost shouldBe "{0}"
            paymentPlanV3FromPublic(binding.paymentDomain) shouldNotBe null
        }

        val downstreamBinding = directRelation.targetBindings.first()
        val downstreamAction = (freeFirstEquip.legalAction.action as ActivateAbility).copy(
            targets = listOf(ChosenTarget.Permanent(downstreamBinding.target)),
            paymentStrategy = PaymentStrategy.ExplicitV3(
                paymentPlan = requireNotNull(paymentPlanV3FromPublic(downstreamBinding.paymentDomain)),
            ),
        )
        val downstreamEnvironment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        downstreamEnvironment.restore(
            state = state,
            playerIds = state.turnOrder,
            stepCount = run.acceptedActions.size,
        )
        val downstreamResult = runCatching { downstreamEnvironment.stepStrict(downstreamAction) }
        println(
            "DOWNSTREAM_EXPLICIT_V3 target=${downstreamBinding.target} " +
                "outcome=${downstreamResult.fold({ "ACCEPTED" }, { "REJECTED ${it.javaClass.simpleName}: ${it.message}" })}",
        )
        downstreamResult.isSuccess shouldBe true
    }
})

private data class EquipDiagnostic(
    val legalAction: LegalAction,
    val publicView: LegalActionView,
    val sourceCard: CardComponent?,
    val ability: ActivatedAbility?,
    val targetResult: Any?,
    val targetDomain: ActionTargetDomainV1?,
    val targetDependency: Any?,
    val targetQualification: Any?,
    val targetPaymentDomain: TargetPaymentDomainV1?,
    val unboundEffectiveCost: Any?,
    val boundEffectiveCosts: List<Pair<EntityId, Any?>>,
    val supportedEquipPayment: Boolean?,
    val paymentRequest: Any?,
    val reservedOuterLifePayment: Any?,
    val paymentDomainV5: PaymentDomainV5?,
    val paymentPlanAvailable: Boolean,
    val deterministicAdditionalCostPayment: Any?,
    val discoveredSources: List<Any>,
    val orderedSourceIds: List<EntityId>,
)

private fun diagnoseEquip(
    state: GameState,
    actor: EntityId,
    legalAction: LegalAction,
    publicView: LegalActionView,
    builder: ObservationBuilder,
    cardRegistry: CardRegistry,
): EquipDiagnostic {
    val action = legalAction.action as? ActivateAbility
        ?: error("Expected ActivateAbility for ${publicView.description}")
    val sourceCard = state.getEntity(action.sourceId)?.get<CardComponent>()
    val ability = invokeDeclared(builder, "resolveActivatedAbility", state, action) as? ActivatedAbility
    val calculator = activatedAbilityCostCalculator(cardRegistry)
    val unboundEffectiveCost = ability?.let {
        calculator.calculate(
            state = state,
            sourceId = action.sourceId,
            controllerId = action.playerId,
            ability = it,
            equipPayment = action.alternativePayment?.equipPayment,
        )
    }
    val targetResult = invokeDeclared(
        builder,
        "mapPublicTargetDomain",
        state,
        legalAction,
        actor.value,
    )
    val targetDomain = propertyOrNull(targetResult, "domain") as? ActionTargetDomainV1
    val targetDependency = if (ability != null) {
        invokeDeclared(builder, "targetCostDependencyFor", state, action, legalAction, targetResult)
    } else {
        null
    }
    val targetQualification = invokeDeclared(
        builder,
        "targetPaymentQualificationFor",
        state,
        legalAction,
        targetResult,
    )
    val targetPaymentDomain = if (targetDomain != null) {
        invokeDeclared(builder, "targetPaymentDomainV1For", state, legalAction, targetDomain)
            as? TargetPaymentDomainV1
    } else {
        null
    }
    val candidateIds = targetDomain?.requirements?.singleOrNull()?.candidates
        ?: legalAction.targetRequirements.singleOrNull()?.validTargets.orEmpty()
    val boundEffectiveCosts = if (ability != null) {
        candidateIds.map { targetId ->
            targetId to calculator.calculate(
                state = state,
                sourceId = action.sourceId,
                controllerId = action.playerId,
                ability = ability,
                targets = listOf(ChosenTarget.Permanent(targetId)),
                equipPayment = action.alternativePayment?.equipPayment,
            )
        }
    } else {
        emptyList()
    }
    val supportedEquipPayment = if (ability != null) {
        invokeDeclared(
            builder,
            "isSupportedEquipPayment",
            state,
            legalAction,
            action,
            ability,
            legalAction.manaCostString,
        ) as? Boolean
    } else {
        null
    }
    val paymentRequest = invokeDeclared(
        builder,
        "paymentDomainRequestFor",
        state,
        legalAction,
        false,
    )
    val reservedOuterLifePayment = invokeDeclared(
        builder,
        "reservedOuterLifePaymentForV5",
        state,
        legalAction,
    )
    val paymentDomainV5 = builder.paymentDomainV5For(state, legalAction)
    val deterministicAdditionalCostPayment = invokeDeclared(
        builder,
        "deterministicAdditionalCostPaymentFor",
        state,
        legalAction,
    )
    val discoveredSources = ManaSolver(cardRegistry).findAvailableManaSources(
        state = state,
        playerId = action.playerId,
        spellContext = null,
        paymentOrderRequired = true,
    )
    val sourcesById = discoveredSources.associateBy { it.entityId }
    val orderedSourceIds = CombatObjectOrder.order(state, discoveredSources.map { it.entityId }).orEmpty()

    return EquipDiagnostic(
        legalAction = legalAction,
        publicView = publicView,
        sourceCard = sourceCard,
        ability = ability,
        targetResult = targetResult,
        targetDomain = targetDomain,
        targetDependency = targetDependency,
        targetQualification = targetQualification,
        targetPaymentDomain = targetPaymentDomain,
        unboundEffectiveCost = unboundEffectiveCost,
        boundEffectiveCosts = boundEffectiveCosts,
        supportedEquipPayment = supportedEquipPayment,
        paymentRequest = paymentRequest,
        reservedOuterLifePayment = reservedOuterLifePayment,
        paymentDomainV5 = paymentDomainV5,
        paymentPlanAvailable = paymentDomainV5?.let { paymentPlanV3FromPublic(it) != null } == true,
        deterministicAdditionalCostPayment = deterministicAdditionalCostPayment,
        discoveredSources = orderedSourceIds.mapNotNull { sourcesById[it] },
        orderedSourceIds = orderedSourceIds,
    )
}

private fun buildReport(
    exactSpec: B0EpisodeSpec,
    run: B0EpisodeRun,
    state: GameState,
    actor: EntityId,
    observation: ObservationResult,
    records: List<EquipDiagnostic>,
): String = buildString {
    appendLine("B0-PAYMENT-DOMAIN-05 DECISION-564 PAYMENT DIAGNOSTIC")
    appendLine("MODE=CHARACTERIZATION_ONLY")
    appendLine("CHECKOUT_HEAD=${gitHead()}")
    appendLine("EPISODE_ID=${exactSpec.episodeId}")
    appendLine("ENGINE_SEED=${exactSpec.engineSeed}")
    appendLine("POLICY_SEED=${exactSpec.policySeed}")
    appendLine("DECISION=${run.result.semanticExternalDecisionCount}")
    appendLine("ACTOR=$actor")
    appendLine("PHASE=${state.phase}")
    appendLine("TURN=${state.turnNumber}")
    appendLine("STEP=${state.step}")
    appendLine("RUN_CLOSURE=${run.result.closureKind}/${run.result.closureReason}")
    appendLine("RUN_FAILURE_STAGE=${run.failureBundle?.failureStage}")
    appendLine("RUN_FAILURE_REFERENCE=${run.failureBundle?.restrictedDiagnosticsReference}")
    appendLine("OBSERVATION_DIAGNOSTICS=${observation.diagnostics}")
    appendLine("PUBLIC_EQUIP_COUNT=${records.size}")
    appendLine("PLAYER_MANA_POOL=${state.getEntity(actor)?.get<ManaPoolComponent>()}")
    appendLine("REQUIREMENT_ACTION_ORDER=${records.map { it.publicView.description }}")
    records.forEachIndexed { index, record ->
        val action = record.legalAction.action as ActivateAbility
        appendLine("ACTION[$index]")
        appendLine("sourceEntityId=${action.sourceId.value}")
        appendLine("sourceCardName=${record.sourceCard?.name}")
        appendLine("sourceCardDefinitionId=${record.sourceCard?.cardDefinitionId}")
        appendLine("actionType=${record.legalAction.actionType}")
        appendLine("description=${record.legalAction.description}")
        appendLine("abilityId=${action.abilityId.value}")
        appendLine("resolvedAbilityId=${record.ability?.id?.value}")
        appendLine("abilityIsEquipAbility=${record.ability?.isEquipAbility}")
        appendLine("abilityProvenance=${record.ability}")
        appendLine("effectiveAbilityCostUnbound=${record.unboundEffectiveCost}")
        appendLine("manaCostString=${record.legalAction.manaCostString}")
        appendLine("alternativePayment=${action.alternativePayment}")
        appendLine("equipPayment=${action.alternativePayment?.equipPayment}")
        appendLine("genericCostReduction=${record.ability?.genericCostReduction}")
        appendLine("additionalCostInfo=${record.legalAction.additionalCostInfo}")
        appendLine("targetRequirements=${record.legalAction.targetRequirements}")
        appendLine("targetDomain=${record.publicView.targetDomain}")
        appendLine("targetCandidates=${record.targetDomain?.requirements?.singleOrNull()?.candidates}")
        appendLine("requiredPayloadFields=${record.publicView.requiredPayloadFields}")
        appendLine("affordableRules=${record.legalAction.affordable}")
        appendLine("affordablePublic=${record.publicView.affordable}")
        appendLine("paymentDomainV5Public=${record.publicView.paymentDomain}")
        appendLine("targetPaymentDomainPublic=${record.publicView.targetPaymentDomain}")
        appendLine("targetResult=${record.targetResult}")
        appendLine("targetCostDependency=${record.targetDependency}")
        appendLine("targetPaymentQualification=${record.targetQualification}")
        appendLine("targetPaymentDomainV1=${record.targetPaymentDomain}")
        record.targetPaymentDomain?.targetBindings?.forEach { binding ->
            appendLine(
                "targetBinding target=${binding.target} affordable=${binding.affordable} " +
                    "requiredCost=${binding.paymentDomain.requiredCost} " +
                    "paymentDomain=${binding.paymentDomain}",
            )
        }
        appendLine("unboundEffectiveCost=${record.unboundEffectiveCost}")
        record.boundEffectiveCosts.forEach { (targetId, cost) ->
            appendLine("boundEffectiveCost target=$targetId cost=$cost")
        }
        appendLine("isSupportedEquipPayment=${record.supportedEquipPayment}")
        appendLine("paymentDomainRequest=${record.paymentRequest}")
        appendLine("paymentDomainRequestNullGate=${firstPaymentRequestNullGate(record)}")
        appendLine("reservedOuterLifePayment=${record.reservedOuterLifePayment}")
        appendLine("paymentDomainV5Result=${record.paymentDomainV5}")
        appendLine("paymentPlanFromV5Available=${record.paymentPlanAvailable}")
        appendLine("deterministicAdditionalCostPayment=${record.deterministicAdditionalCostPayment}")
        appendLine("discoveredManaSourceOrder=${record.orderedSourceIds}")
        record.discoveredSources.forEachIndexed { sourceIndex, source ->
            appendLine("manaSource[$sourceIndex]=$source")
        }
    }
    appendLine("SHARED_ROOT=ACTION_LEVEL_PAYMENT_REQUEST_REJECTS_SELECTED_EQUIP_PAYMENT")
    appendLine("PRODUCTION_OFFENDER=Equip {0} (affordable, target-independent, direct V1 relation buildable)")
    appendLine("NON_CAUSAL_DIAGNOSTIC_NOISE=Equip {1} (unaffordable targetless placeholder)")
}

private fun firstPaymentRequestNullGate(record: EquipDiagnostic): String {
    if (record.paymentRequest != null) return "NONE_REQUEST_NON_NULL"
    val legalAction = record.legalAction
    val action = legalAction.action as? ActivateAbility ?: return "ACTION_KIND_UNSUPPORTED"
    if (legalAction.manaCostString == null) return "MANA_COST_MISSING"
    if (record.deterministicAdditionalCostPayment == null) return "DETERMINISTIC_ADDITIONAL_COST_UNRESOLVED"
    if (legalAction.hasXCost) return "ACTIVATE_HAS_X_COST"
    if (legalAction.hasConvoke) return "ACTIVATE_HAS_CONVOKE"
    if (legalAction.hasTapForGeneric) return "ACTIVATE_HAS_TAP_FOR_GENERIC"
    if (action.alternativePayment != null) return "ALTERNATIVE_PAYMENT_PRESENT"
    if (record.ability?.hasConvoke == true) return "ABILITY_HAS_CONVOKE"
    if (record.ability?.hasWaterbend == true) return "ABILITY_HAS_WATERBEND"
    if (record.ability?.isEquipAbility == true && record.supportedEquipPayment != true) {
        return "EQUIP_PAYMENT_UNSUPPORTED"
    }
    if (record.targetDependency?.toString() != "INDEPENDENT") {
        return "TARGET_COST_DEPENDENCY_NOT_INDEPENDENT"
    }
    return "REQUEST_NULL_UNEXPLAINED"
}

private fun completeRegistry(): CardRegistry = CardRegistry().apply {
    com.wingedsheep.mtg.sets.MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun activatedAbilityCostCalculator(cardRegistry: CardRegistry): ActivatedAbilityCostCalculator =
    ActivatedAbilityCostCalculator(
        CastPermissionUtils(cardRegistry, PredicateEvaluator(), ConditionEvaluator()),
    )

private fun actionSourceId(legalAction: LegalAction): EntityId? =
    (legalAction.action as? ActivateAbility)?.sourceId

private fun compactReplay(run: B0EpisodeRun, registry: CardRegistry): CompactReplay {
    val config = requireNotNull(run.replayConfig) { "B0 failed run has no replay config" }
    val deckResolver = DeckResolver(registry)
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

private fun invokeDeclared(target: Any, name: String, vararg args: Any?): Any? {
    val method = target.javaClass.declaredMethods.firstOrNull {
        it.name.startsWith(name) && it.parameterCount == args.size
    } ?: error("Could not find $name with ${args.size} arguments on ${target.javaClass.name}")
    method.isAccessible = true
    return method.invoke(target, *args)
}

private fun propertyOrNull(target: Any?, name: String): Any? {
    if (target == null) return null
    val getterName = "get" + name.replaceFirstChar { it.uppercase() }
    val getter = target.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        ?: target.javaClass.declaredMethods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
    if (getter != null) {
        getter.isAccessible = true
        return getter.invoke(target)
    }
    val field = target.javaClass.declaredFields.firstOrNull { it.name == name } ?: return null
    field.isAccessible = true
    return field.get(target)
}

private fun gitHead(): String = ProcessBuilder("git", "rev-parse", "HEAD")
    .redirectErrorStream(true)
    .start()
    .let { process ->
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { "Could not resolve checkout HEAD: $value" }
        value
    }
