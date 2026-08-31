package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.actions.spell.CastZoneResolver
import com.wingedsheep.engine.handlers.actions.spell.resolveApplicableAdditionalCostsForCast
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.mechanics.combat.CombatObjectOrder
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayReconstructor
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.paymentPlanV3FromPublic
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * B0_PAYMENT_DOMAIN_04 — diagnostic-only characterization of Plumb the Forbidden at decision
 * 1112. This test deliberately uses the accepted production head plus the existing disposable
 * B0 overlay. It records the Rules-owned cost/source facts and does not change production code,
 * policy, decks, seeds, or the B0 overlay.
 */
class B0PaymentDomain04DiagnosticTest : FunSpec({

    val exactSpec = B0EpisodeSpec(
        baseSeed = 2L,
        engineSeed = 2L,
        policySeed = -3487907050897048331L,
        rosterOrientation = B0RosterOrientation.AKIRI_SEAT_0,
        startingPlayer = B0Commander.CHEVILL,
    )

    test("characterize Plumb sacrifice/payment ordering and V5 null chain") {
        val run = B0CommanderSoakHarness.create().run(
            spec = exactSpec,
            control = B0RunControl(semanticActionBudget = 2_000, engineProgressBudget = 4_000),
        )

        run.result.closureKind shouldBe B0ClosureKind.FAILED
        run.result.closureReason shouldBe B0ClosureReason.UNSUPPORTED
        run.result.semanticExternalDecisionCount shouldBe 1_112
        run.failureBundle?.failureStage shouldBe "post-structured-decision-observation"
        run.failureBundle?.restrictedDiagnosticsReference shouldNotBe null

        val registry = completeRegistry()
        val replay = compactReplay(run, registry)
        val state = ReplayReconstructor(registry, null).reconstructStateAt(
            replay = replay,
            frame = run.acceptedActions.size,
        ) ?: error("Could not reconstruct the exact post-transition state")

        state.pendingDecision shouldBe null
        val actor = requireNotNull(state.priorityPlayerId) { "Expected priority at decision 1112" }
        val enumerator = LegalActionEnumerator.create(registry)
        val legalActions = enumerator.enumerate(state, actor, EnumerationMode.ACTIONS_ONLY)
            .filterNot(LegalAction::hasUnfillableTargetRequirement)
        val plumb = legalActions.singleOrNull { candidate ->
            val cast = candidate.action as? CastSpell ?: return@singleOrNull false
            state.getEntity(cast.cardId)?.get<CardComponent>()?.name == "Plumb the Forbidden"
        } ?: error("Expected exactly one Plumb the Forbidden legal action")
        val cast = plumb.action as CastSpell
        val card = state.getEntity(cast.cardId)?.get<CardComponent>()
            ?: error("Plumb card entity is missing")
        val cardDef = registry.getCard(card.cardDefinitionId)
            ?: error("Plumb card definition is missing")
        val info = plumb.additionalCostInfo
            ?: error("Plumb must publish AdditionalCostData")

        val builder = ObservationBuilder(cardRegistry = registry)
        val observation = builder.build(
            state = state,
            perspectivePlayerId = actor,
            legalActions = listOf(plumb),
        )
        val view = observation.observation as? TrainingObservation
            ?: error("Expected TrainingObservation")

        val additionalCosts = resolveApplicableAdditionalCostsForCast(
            state = state,
            action = cast,
            cardDef = cardDef,
            cardRegistry = registry,
            predicateEvaluator = PredicateEvaluator(),
            zoneResolver = CastZoneResolver(registry, ConditionEvaluator()),
        )

        val solver = ManaSolver(registry)
        val discovered = solver.findAvailableManaSources(
            state = state,
            playerId = cast.playerId,
            spellContext = null,
            paymentOrderRequired = true,
        )
        val sourceOrder = CombatObjectOrder.order(state, discovered.map { it.entityId })
            ?: error("Rules-owned source ordering was unavailable")
        val sourcesById = discovered.associateBy { it.entityId }
        val orderedSources = sourceOrder.map { sourcesById.getValue(it) }
        val sourceIds = orderedSources.map { it.entityId }.toSet()
        val sacrificeCandidates = info.validSacrificeTargets
        val nonManaSourceCandidates = sacrificeCandidates.filterNot(sourceIds::contains)
        val manaSourceCandidates = sacrificeCandidates.filter(sourceIds::contains)
        val overlap = sacrificeCandidates.intersect(sourceIds)
        val overlappingSource = sourcesById[EntityId("e139")]

        println("B0_PAYMENT_DOMAIN_04")
        println("PRODUCTION_HEAD=90248d20173a51b78d6cdb437f562b3fcf33fceb")
        println("EPISODE=${exactSpec.episodeId}")
        println("ENGINE_SEED=${exactSpec.engineSeed}")
        println("POLICY_SEED=${exactSpec.policySeed}")
        println("DECISION=1112")
        println("ACTOR=$actor")
        println("PHASE=${state.phase}")
        println("STEP=${state.step}")
        println("CARD_ZONE=${cardZone(state, cast.cardId)}")
        println("MANA_POOL=${state.getEntity(cast.playerId)?.get<ManaPoolComponent>()}")
        println("LEGAL_ACTION_TYPE=${plumb.actionType}")
        println("LEGAL_ACTION_DESCRIPTION=${plumb.description}")
        println("LEGAL_ACTION=${plumb.action}")
        println("AFFORDABLE=${plumb.affordable}")
        println("MANA_COST_STRING=${plumb.manaCostString}")
        println("ALTERNATIVE_PAYMENT=${cast.alternativePayment}")
        println("ADDITIONAL_COST_PAYMENT=${cast.additionalCostPayment}")
        println("TARGETS=${cast.targets}")
        println("TARGET_REQUIREMENTS=${plumb.targetRequirements}")
        println("REQUIRED_PAYLOAD_FIELDS=${view.legalActions.single().requiredPayloadFields}")
        println("ADDITIONAL_COST_INFO=$info")
        println("RULES_APPLICABLE_ADDITIONAL_COSTS=$additionalCosts")
        println("CARD_SCRIPT_ADDITIONAL_COSTS=${cardDef.script.additionalCosts}")
        println("SACRIFICE_CANDIDATES=$sacrificeCandidates")
        println("NON_MANA_SOURCE_CANDIDATES=$nonManaSourceCandidates")
        println("MANA_SOURCE_CANDIDATES=$manaSourceCandidates")
        println("MANA_SOURCE_OVERLAP=$overlap")
        println("MANA_SOURCE_OVERLAP_DETAILS=$overlappingSource")
        sacrificeCandidates.forEach { candidateId ->
            val candidateCard = state.getEntity(candidateId)?.get<CardComponent>()
            val candidateDefinition = candidateCard?.let { registry.getCard(it.cardDefinitionId) }
            println(
                "SACRIFICE_CANDIDATE id=$candidateId name=${candidateCard?.name} " +
                    "isManaSource=${candidateId in sourceIds} " +
                    "manaSource=${sourcesById[candidateId]} " +
                    "manaAbilities=${candidateDefinition?.script?.activatedAbilities}",
            )
        }
        println(
            "RULES_PAYMENT_ORDERING=CR_601_2f_total_cost_locked; " +
                "CR_601_2g_mana_abilities_before_costs; " +
                "CR_601_2h_pay_total_cost",
        )
        orderedSources.forEachIndexed { index, source ->
            val sourceCard = state.getEntity(source.entityId)?.get<CardComponent>()
            val sourceDef = sourceCard?.let { registry.getCard(it.cardDefinitionId) }
            println(
                "SOURCE[$index] id=${source.entityId} name=${source.name} " +
                    "cardDefinitionId=${sourceCard?.cardDefinitionId} " +
                    "isCreature=${source.isCreature} producesColors=${source.producesColors} " +
                    "producesColorless=${source.producesColorless} " +
                    "activationManaCosts=${source.colorActivationManaCost} " +
                    "colorlessActivationManaCost=${source.colorlessActivationManaCost} " +
                    "paymentOrderCertified=${source.paymentManaAbilityOrderCertified} " +
                    "restrictionsCertified=${source.paymentManaSpendingRestrictionsCertified} " +
                    "stabilityCertified=${source.paymentManaExecutionStabilityCertified} " +
                    "productionProfiles=${source.paymentManaProductionProfiles} " +
                    "sideEffectCertificates=${source.paymentManaSideEffectCertificates} " +
                    "abilities=${sourceDef?.script?.activatedAbilities}",
            )
        }

        val request = invokePrivate(
            target = builder,
            name = "paymentDomainRequestFor",
            args = arrayOf(state, plumb, true),
        )
        val requestRequiredCost = request?.let { readProperty(it, "requiredCost") }
        val requestPlayerId = request?.let { readProperty(it, "playerId") }
        val requestSpellContext = request?.let { readProperty(it, "spellContext") }
        val requestExcludedSources = request?.let { readProperty(it, "excludeSources") }
        val reservedOuterLife = invokePrivate(
            target = builder,
            name = "reservedOuterLifePaymentForV5",
            args = arrayOf(state, plumb),
        )
        val rawV5Domain = if (request != null) {
            val paymentBuilder = lazyProperty(builder, "paymentDomainBuilder")
            invokePublic(
                target = paymentBuilder,
                name = "buildV5",
                args = arrayOf(
                    state,
                    requestPlayerId,
                    requestRequiredCost,
                    requestSpellContext,
                    requestExcludedSources,
                    reservedOuterLife,
                ),
            )
        } else {
            null
        }
        val additionalGate = invokePrivate(
            target = builder,
            name = "hasUnrepresentableAdditionalPayment",
            args = arrayOf(plumb, sourceIds),
        )
        val publishedDomain = builder.paymentDomainV5For(state, plumb)

        println("NULL_CHAIN_PAYMENT_DOMAIN_REQUEST=${request?.javaClass?.name ?: "null"}")
        println("NULL_CHAIN_REQUEST_REQUIRED_COST=$requestRequiredCost")
        println("NULL_CHAIN_REQUEST_PLAYER=$requestPlayerId")
        println("NULL_CHAIN_REQUEST_CONTEXT=$requestSpellContext")
        println("NULL_CHAIN_REQUEST_EXCLUDED_SOURCES=$requestExcludedSources")
        println("NULL_CHAIN_RESERVED_OUTER_LIFE=$reservedOuterLife")
        println("NULL_CHAIN_BUILD_V5=${rawV5Domain?.javaClass?.name ?: "null"}")
        println("NULL_CHAIN_BUILD_V5_VALUE=$rawV5Domain")
        println("NULL_CHAIN_HAS_UNREPRESENTABLE_ADDITIONAL_PAYMENT=$additionalGate")
        println("NULL_CHAIN_PUBLIC_PAYMENT_DOMAIN=${publishedDomain?.javaClass?.name ?: "null"}")
        println("OBSERVATION_DIAGNOSTICS=${observation.diagnostics}")
        println("PUBLIC_VIEW_PAYMENT_DOMAIN=${view.legalActions.single().paymentDomain}")

        plumb.manaCostString shouldBe "{1}{B}"
        plumb.affordable shouldBe true
        cast.alternativePayment shouldBe null
        cast.additionalCostPayment shouldBe null
        info.sacrificeMinCount shouldBe 0
        info.sacrificeMaxCount shouldBe sacrificeCandidates.size
        sacrificeCandidates shouldBe listOf(EntityId("e100"), EntityId("e138"), EntityId("e139"))
        overlap shouldBe setOf(EntityId("e139"))
        overlappingSource?.name shouldBe "Leyline Prowler"
        overlappingSource?.isCreature shouldBe true
        observation.diagnostics.map { it.code.name } shouldBe listOf("PAYMENT_DOMAIN_UNSUPPORTED")
        request shouldNotBe null
        requestRequiredCost shouldBe "{1}{B}"
        reservedOuterLife shouldBe 0
        rawV5Domain shouldNotBe null
        additionalGate shouldBe true
        publishedDomain shouldBe null

        println("CHOICE_MATRIX_REQUIRED_MANA_COST=${plumb.manaCostString}")
        choiceVariants(sacrificeCandidates, nonManaSourceCandidates, manaSourceCandidates).forEach { variant ->
            println(
                "CHOICE name=${variant.name} ids=${variant.ids} " +
                    "requiredManaCost=${plumb.manaCostString} " +
                    "candidateClasses=${variant.ids.map { id -> if (id in sourceIds) "MANA_SOURCE" else "NON_MANA_SOURCE" }}",
            )
        }

        val rawDomain = rawV5Domain as? com.wingedsheep.gym.contract.PaymentDomainV5
            ?: error("Expected raw V5 domain for composition characterization")
        val completePlan = paymentPlanV3FromPublic(rawDomain)
            ?: error("Expected a complete public V3 plan for Plumb's {1}{B}")
        println("PUBLIC_V3_PLAN=$completePlan")
        println("PUBLIC_V3_PLAN_ACTIVATIONS=${completePlan.activations.map { it.sourceId }}")

        choiceVariants(sacrificeCandidates, nonManaSourceCandidates, manaSourceCandidates).forEach { variant ->
            val environment = GameEnvironment.create(
                cardRegistry = registry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.restore(
                state = state,
                playerIds = state.turnOrder,
                stepCount = run.acceptedActions.size,
            )
            val submitted = cast.copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = completePlan),
                additionalCostPayment = com.wingedsheep.sdk.scripting.AdditionalCostPayment(
                    variableCostPermanents = variant.ids,
                ),
            )
            val beforeState = environment.state
            val beforeStep = environment.stepCount
            val result = runCatching { environment.stepStrict(submitted) }
            val stepResult = result.getOrNull()
            val outcome = result.fold(
                onSuccess = { "ACCEPTED stateChanged=${environment.state != beforeState}" },
                onFailure = { "REJECTED ${it.javaClass.simpleName}: ${it.message}" },
            )
            println(
                "EXECUTION_MATRIX name=${variant.name} ids=${variant.ids} " +
                    "stepBefore=$beforeStep stepAfter=${environment.stepCount} outcome=$outcome",
            )
            result.isSuccess shouldBe true
            environment.stepCount shouldBe beforeStep + 1
        }

        if (manaSourceCandidates.isNotEmpty()) {
            val sacrificeSourceId = manaSourceCandidates.first()
            val sacrificeSourceOption = rawDomain.sourceActivationOptions.firstOrNull {
                it.sourceId == sacrificeSourceId &&
                    it.productionChoices.any { choice -> choice.producedColor == PaymentManaColor.BLACK }
            } ?: error("Expected a published black-producing option for $sacrificeSourceId")
            val seedOption = rawDomain.sourceActivationOptions.firstOrNull {
                it.sourceId != sacrificeSourceId &&
                    it.productionChoices.any { choice -> choice.producedColor == PaymentManaColor.GREEN }
            } ?: error("Expected a second source to provide Plumb's generic mana")
            val forcedPlan = PaymentPlanV3(
                activations = listOf(
                    SourceActivationV2(
                        sourceId = seedOption.sourceId,
                        manaAbilityKey = seedOption.manaAbilityKey,
                        productionChoice = seedOption.productionChoices.first {
                            it.producedColor == PaymentManaColor.GREEN
                        },
                        activationCostOrder = seedOption.activationCostOrderOptions.first(),
                    ),
                    SourceActivationV2(
                        sourceId = sacrificeSourceOption.sourceId,
                        manaAbilityKey = sacrificeSourceOption.manaAbilityKey,
                        productionChoice = sacrificeSourceOption.productionChoices.first {
                            it.producedColor == PaymentManaColor.BLACK
                        },
                        activationCostOrder = sacrificeSourceOption.activationCostOrderOptions.first(),
                    ),
                ),
                outerAllocation = listOf(
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(symbolIndex = 0, unitIndexWithinSymbol = 0),
                        resource = ManaResourceRefV1.ActivationOutputUnit(activationIndex = 0, outputIndex = 0),
                    ),
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(symbolIndex = 1, unitIndexWithinSymbol = 0),
                        resource = ManaResourceRefV1.ActivationOutputUnit(activationIndex = 1, outputIndex = 0),
                    ),
                ),
            )
            println("FORCED_PLAN_USING_SACRIFICED_MANA_SOURCE=$forcedPlan")
            val baselineEnvironment = GameEnvironment.create(
                cardRegistry = registry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            baselineEnvironment.restore(
                state = state,
                playerIds = state.turnOrder,
                stepCount = run.acceptedActions.size,
            )
            val baselineSubmitted = cast.copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = forcedPlan),
                additionalCostPayment = com.wingedsheep.sdk.scripting.AdditionalCostPayment(),
            )
            val baselineResult = runCatching { baselineEnvironment.stepStrict(baselineSubmitted) }
            println(
                "EXECUTION_ORDER_BASELINE_NO_SACRIFICE " +
                    "stepAfter=${baselineEnvironment.stepCount} " +
                    "sourceZone=${cardZone(baselineEnvironment.state, sacrificeSourceId)} " +
                    "events=${baselineResult.getOrNull()?.events?.map { it::class.simpleName }} " +
                    "outcome=${baselineResult.fold({ "ACCEPTED" }, { "REJECTED ${it.message}" })}",
            )
            baselineResult.isSuccess shouldBe true
            baselineEnvironment.stepCount shouldBe run.acceptedActions.size + 1
            cardZone(baselineEnvironment.state, sacrificeSourceId) shouldBe Zone.BATTLEFIELD

            val environment = GameEnvironment.create(
                cardRegistry = registry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.restore(
                state = state,
                playerIds = state.turnOrder,
                stepCount = run.acceptedActions.size,
            )
            val submitted = cast.copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = forcedPlan),
                additionalCostPayment = com.wingedsheep.sdk.scripting.AdditionalCostPayment(
                    variableCostPermanents = listOf(sacrificeSourceId),
                ),
            )
            val beforeState = environment.state
            val beforeStep = environment.stepCount
            val beforeEvents = environment.lastStepEvents
            val result = runCatching { environment.stepStrict(submitted) }
            val stepResult = result.getOrNull()
            val outcome = result.fold(
                onSuccess = { "ACCEPTED stateChanged=${environment.state != beforeState}" },
                onFailure = { "REJECTED ${it.javaClass.simpleName}: ${it.message}" },
            )
            println(
                "EXECUTION_ORDER_PROBE sacrifice-before-or-after-mana=" +
                    "source=$sacrificeSourceId stepBefore=$beforeStep " +
                    "stepAfter=${environment.stepCount} outcome=$outcome",
            )
            println("EXECUTION_ORDER_PROBE_EVENTS=${stepResult?.events?.map { it::class.simpleName }}")
            println("EXECUTION_ORDER_PROBE_SACRIFICED_SOURCE_ZONE=${cardZone(environment.state, sacrificeSourceId)}")
            println("EXECUTION_ORDER_PROBE_RESULT_POOL=${environment.state.getEntity(cast.playerId)?.get<ManaPoolComponent>()}")
            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Payment source is not currently available: $sacrificeSourceId"
            environment.state shouldBe beforeState
            environment.stepCount shouldBe beforeStep
            environment.lastStepEvents shouldBe beforeEvents
        }
    }
})

private data class ChoiceVariant(
    val name: String,
    val ids: List<EntityId>,
)

private fun choiceVariants(
    allCandidates: List<EntityId>,
    nonManaSourceCandidates: List<EntityId>,
    manaSourceCandidates: List<EntityId>,
): List<ChoiceVariant> = buildList {
    add(ChoiceVariant("sacrifice-0", emptyList()))
    nonManaSourceCandidates.firstOrNull()?.let { add(ChoiceVariant("sacrifice-1-non-mana-source", listOf(it))) }
    manaSourceCandidates.firstOrNull()?.let { add(ChoiceVariant("sacrifice-1-mana-source", listOf(it))) }
    if (nonManaSourceCandidates.isNotEmpty() && manaSourceCandidates.isNotEmpty()) {
        add(ChoiceVariant("sacrifice-multiple-mixed", listOf(nonManaSourceCandidates.first(), manaSourceCandidates.first())))
    } else if (allCandidates.size >= 2) {
        add(ChoiceVariant("sacrifice-multiple", allCandidates.take(2)))
    }
}

private fun completeRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

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

private fun cardZone(state: GameState, entityId: EntityId): Zone? =
    state.zones.entries.firstOrNull { (_, ids) -> entityId in ids }?.key?.zoneType

private fun invokePrivate(target: Any, name: String, args: Array<Any?>): Any? {
    val method = target.javaClass.declaredMethods.firstOrNull {
        it.name == name && it.parameterCount == args.size
    } ?: error("Could not find private method $name(${args.size} args)")
    method.isAccessible = true
    return method.invoke(target, *args)
}

private fun invokePublic(target: Any, name: String, args: Array<Any?>): Any? {
    val method = target.javaClass.methods.firstOrNull {
        (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == args.size
    } ?: error("Could not find public method $name(${args.size} args)")
    method.isAccessible = true
    return method.invoke(target, *args)
}

private fun lazyProperty(target: Any, propertyName: String): Any {
    val field = target.javaClass.declaredFields.firstOrNull {
        it.name == "${propertyName}\$delegate"
    } ?: error("Could not find lazy delegate for $propertyName")
    field.isAccessible = true
    val lazy = field.get(target) as Lazy<*>
    return requireNotNull(lazy.value) { "Lazy property $propertyName was null" }
}

private fun readProperty(target: Any, propertyName: String): Any? {
    val getterName = "get" + propertyName.replaceFirstChar { it.uppercase() }
    val getter = target.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        ?: target.javaClass.declaredMethods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
    if (getter != null) {
        getter.isAccessible = true
        return getter.invoke(target)
    }
    val field = target.javaClass.declaredFields.firstOrNull { it.name == propertyName }
        ?: error("Could not read property $propertyName from ${target.javaClass.name}")
    field.isAccessible = true
    return field.get(target)
}
