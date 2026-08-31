package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.paymentPlanV3FromPublic
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayReconstructor
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EquipPaymentChoice
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * B0_ALTERNATIVE_PAYMENT_01 — diagnostic-only characterization of the first A5 gap after the
 * Decision-564 payment-domain fix. It proves whether engine-issued alternative-payment choices
 * are complete public candidate bindings and whether the strict Gym boundary rejects payload
 * omission, tampering, and mixed resource/equip choices. No production code, policy, deck, seed,
 * or B0 overlay is changed by this test.
 */
class B0AlternativePayment01DiagnosticTest : FunSpec({
    test("characterizes engine-bound alternativePayment at decision 571") {
        val spec = B0EpisodeSpec(
            baseSeed = 2L,
            engineSeed = 2L,
            policySeed = 3160949986217547037L,
            rosterOrientation = B0RosterOrientation.CHEVILL_SEAT_0,
            startingPlayer = B0Commander.CHEVILL,
        )
        val run = B0CommanderSoakHarness.create().run(
            spec = spec,
            control = B0RunControl(semanticActionBudget = 2_000, engineProgressBudget = 4_000),
        )

        run.result.closureKind shouldBe B0ClosureKind.FAILED
        run.result.closureReason shouldBe B0ClosureReason.MALFORMED_OR_INCOMPLETE_PUBLIC_DOMAIN
        run.result.semanticExternalDecisionCount shouldBe 571
        run.result.engineProgressCount shouldBe 571
        run.result.externalTransitionCount shouldBe 571
        run.failureBundle?.failureStage shouldBe "public-domain:ActivateAbility:A5_DECISION_GAP"
        run.failureBundle?.restrictedDiagnosticsReference shouldNotBe null
        run.failureBundle?.restrictedDiagnosticsReference
            ?.contains("unsupported=[alternativePayment]") shouldBe true

        val registry = completeRegistry()
        val state = ReplayReconstructor(registry, null).reconstructStateAt(
            replay = compactReplay(run, registry),
            frame = run.acceptedActions.size,
        ) ?: error("Could not reconstruct the exact post-decision-571 state")
        val actor = requireNotNull(state.priorityPlayerId) { "Decision 571 has no priority actor" }
        val legalActions = LegalActionEnumerator.create(registry)
            .enumerate(state, actor, EnumerationMode.ACTIONS_ONLY)
            .filterNot(LegalAction::hasUnfillableTargetRequirement)
        val builder = ObservationBuilder(cardRegistry = registry)
        val observed = builder.build(
            state = state,
            perspectivePlayerId = actor,
            legalActions = legalActions,
        )
        observed.diagnostics shouldBe emptyList()
        val training = observed.observation as? TrainingObservation
            ?: error("Expected TrainingObservation")

        val alternativeViews = training.legalActions.filter { view ->
            view.kind == "ActivateAbility" && "alternativePayment" in view.requiredPayloadFields
        }
        alternativeViews.shouldNotBeEmpty()
        val records = alternativeViews.map { view ->
            val action = legalActions.singleOrNull { candidate ->
                val activate = candidate.action as? ActivateAbility
                candidate.actionType == view.kind &&
                    candidate.description == view.description &&
                    candidate.manaCostString == view.manaCost &&
                    activate?.sourceId == view.sourceEntityId &&
                    activate?.alternativePayment != null
            }?.action as? ActivateAbility
                ?: error("Could not bind ${view.description} to its Rules ActivateAbility")
            AlternativePaymentRecord(
                view = view,
                action = action,
                sourceCard = state.getEntity(action.sourceId)?.get<CardComponent>(),
            )
        }

        val firstRecord = records.first()
        val siblingRecords = training.legalActions
            .filter { it.kind == "ActivateAbility" }
            .filter { view ->
                view.sourceEntityId == firstRecord.view.sourceEntityId &&
                    view.actionSemantics?.get("abilityKey") == firstRecord.view.actionSemantics?.get("abilityKey")
            }
            .map { view ->
                val action = legalActions.singleOrNull { candidate ->
                    val activate = candidate.action as? ActivateAbility
                    candidate.actionType == view.kind &&
                        candidate.description == view.description &&
                        candidate.manaCostString == view.manaCost &&
                        activate?.sourceId == view.sourceEntityId
                }?.action as? ActivateAbility
                    ?: error("Could not bind sibling ${view.description}")
                AlternativePaymentRecord(
                    view = view,
                    action = action,
                    sourceCard = state.getEntity(action.sourceId)?.get<CardComponent>(),
                )
        }
        siblingRecords.shouldNotBeEmpty()
        siblingRecords.size shouldBe 2
        siblingRecords.map { it.action.sourceId }.toSet() shouldBe setOf(EntityId("e161"))
        siblingRecords.map { it.sourceCard?.name }.toSet() shouldBe setOf("Bonesplitter")
        siblingRecords.map { it.view.actionSemantics?.get("abilityKey") }.distinct().size shouldBe 1
        siblingRecords.associate {
            it.view.description to it.action.alternativePayment?.equipPayment
        } shouldBe mapOf(
            "Equip {1}" to EquipPaymentChoice.NORMAL,
            "Equip {0}" to EquipPaymentChoice.FREE_FIRST_EQUIP,
        )
        siblingRecords.forEach { record ->
            paymentSelection(record.view) shouldNotBe null
            val semanticAlternative = record.view.actionSemantics
                ?.get("alternativePayment") as? JsonObject
                ?: error("${record.view.description} did not publish alternativePayment semantics")
            semanticAlternative["equipPayment"]?.jsonPrimitive?.content shouldBe
                record.action.alternativePayment?.equipPayment?.name
        }

        records.forEach { record ->
            val semanticAlternative = record.view.actionSemantics
                ?.get("alternativePayment") as? JsonObject
                ?: error("${record.view.description} did not publish alternativePayment semantics")
            record.view.requiredPayloadFields.contains("alternativePayment") shouldBe true
            semanticAlternative["equipPayment"]?.jsonPrimitive?.content shouldBe
                record.action.alternativePayment?.equipPayment?.name
            record.action.alternativePayment?.hasResourcePayment shouldBe false
            record.action.alternativePayment?.equipPayment shouldNotBe null
        }

        val strictCandidate = records.firstOrNull { record ->
            record.view.affordable && paymentSelection(record.view) != null
        } ?: error("No affordable alternative-payment candidate has a public payment domain")
        val strictResults = linkedMapOf(
            "EXACT" to strictSubmission(
                state = state,
                actor = actor,
                registry = registry,
                record = strictCandidate,
                mutation = PayloadMutation.EXACT,
            ),
            "MISSING_ALTERNATIVE_PAYMENT" to strictSubmission(
                state = state,
                actor = actor,
                registry = registry,
                record = strictCandidate,
                mutation = PayloadMutation.MISSING_ALTERNATIVE_PAYMENT,
            ),
            "TAMPERED_ALTERNATIVE_PAYMENT" to strictSubmission(
                state = state,
                actor = actor,
                registry = registry,
                record = strictCandidate,
                mutation = PayloadMutation.TAMPERED_ALTERNATIVE_PAYMENT,
            ),
            "MIXED_RESOURCE_AND_EQUIP_PAYMENT" to strictSubmission(
                state = state,
                actor = actor,
                registry = registry,
                record = strictCandidate,
                mutation = PayloadMutation.MIXED_RESOURCE_AND_EQUIP_PAYMENT,
            ),
        )
        strictResults.getValue("EXACT").accepted shouldBe true
        strictResults.getValue("MISSING_ALTERNATIVE_PAYMENT").accepted shouldBe false
        strictResults.getValue("TAMPERED_ALTERNATIVE_PAYMENT").accepted shouldBe false
        strictResults.getValue("MIXED_RESOURCE_AND_EQUIP_PAYMENT").accepted shouldBe false

        val report = buildReport(
            spec = spec,
            run = run,
            state = state,
            actor = actor,
            observationDiagnostics = observed.diagnostics.toString(),
            records = records,
            siblingRecords = siblingRecords,
            strictCandidate = strictCandidate,
            strictResults = strictResults,
        )
        val output = Path.of(
            System.getProperty(
                "b0.diagnosticOutput",
                "C:/Users/chris/.config/superpowers/worktrees/argentum-engine/b0-alternative-payment-01/decision-571-diagnostic.txt",
            ),
        )
        Files.createDirectories(output.parent)
        Files.writeString(output, report)
        println("DIAGNOSTIC_REPORT_PATH=$output")
        println(report)

        println(
            "STRICT_MATRIX candidate=${strictCandidate.view.description} " +
                "source=${strictCandidate.action.sourceId} " +
                strictResults.entries.joinToString(" ") { (name, result) -> "$name=${result.detail}" },
        )
    }
})

private data class AlternativePaymentRecord(
    val view: LegalActionView,
    val action: ActivateAbility,
    val sourceCard: CardComponent?,
)

private enum class PayloadMutation {
    EXACT,
    MISSING_ALTERNATIVE_PAYMENT,
    TAMPERED_ALTERNATIVE_PAYMENT,
    MIXED_RESOURCE_AND_EQUIP_PAYMENT,
}

private data class PaymentSelection(
    val plan: PaymentPlanV3,
    val targetId: EntityId?,
)

private data class StrictSubmissionResult(
    val accepted: Boolean,
    val detail: String,
)

private fun paymentSelection(view: LegalActionView): PaymentSelection? {
    val relation = view.targetPaymentDomain
    if (relation != null) {
        val binding = relation.targetBindings.firstOrNull { it.affordable } ?: return null
        val plan = paymentPlanV3FromPublic(binding.paymentDomain) ?: return null
        return PaymentSelection(plan = plan, targetId = binding.target)
    }
    val domain = view.paymentDomain ?: return null
    val plan = paymentPlanV3FromPublic(domain) ?: return null
    val targetId = if ("targets" in view.requiredPayloadFields) {
        view.targetDomain?.requirements?.singleOrNull()?.candidates?.firstOrNull()
    } else {
        null
    }
    return PaymentSelection(plan = plan, targetId = targetId)
}

private fun strictSubmission(
    state: GameState,
    actor: EntityId,
    registry: CardRegistry,
    record: AlternativePaymentRecord,
    mutation: PayloadMutation,
): StrictSubmissionResult {
    val environment = GameEnvironment.create(
        cardRegistry = registry,
        executionMode = GameEnvironmentMode.TRUSTED,
    )
    environment.restore(state = state, playerIds = state.turnOrder, stepCount = 571)
    val gym = GameGymEnv(
        environment = environment,
        perspectivePlayerIndex = state.turnOrder.indexOf(actor),
        observationBuilder = ObservationBuilder(cardRegistry = registry),
    )
    val observed = gym.observe()
    val view = observed.observation.legalActions.singleOrNull { candidate ->
        candidate.kind == record.view.kind &&
            candidate.description == record.view.description &&
            candidate.sourceEntityId == record.view.sourceEntityId &&
            candidate.manaCost == record.view.manaCost &&
            candidate.actionSemantics?.get("alternativePayment") ==
                record.view.actionSemantics?.get("alternativePayment")
    } ?: return StrictSubmissionResult(false, "candidate-not-present: ${observed.observation.legalActions}")
    val selection = paymentSelection(view)
        ?: return StrictSubmissionResult(false, "public-payment-domain-unusable")
    val payload = payloadFor(view, selection, mutation)
    val beforeState = environment.state
    val beforeStep = environment.stepCount
    val result = runCatching { gym.step(view.actionId, payload) }
    val accepted = result.isSuccess
    if (!accepted) {
        environment.state shouldBe beforeState
        environment.stepCount shouldBe beforeStep
    }
    return StrictSubmissionResult(
        accepted = accepted,
        detail = result.fold(
            onSuccess = { "ACCEPTED step=${environment.stepCount}" },
            onFailure = { "REJECTED ${it.javaClass.simpleName}: ${it.message}" },
        ),
    )
}

private fun payloadFor(
    view: LegalActionView,
    selection: PaymentSelection,
    mutation: PayloadMutation,
): JsonObject = buildJsonObject {
    if (mutation != PayloadMutation.MISSING_ALTERNATIVE_PAYMENT) {
        view.actionSemantics?.forEach { (key, value) -> put(key, value) }
    } else {
        view.actionSemantics
            ?.filterKeys { it != "alternativePayment" }
            ?.forEach { (key, value) -> put(key, value) }
    }
    when (mutation) {
        PayloadMutation.EXACT,
        PayloadMutation.MISSING_ALTERNATIVE_PAYMENT -> Unit
        PayloadMutation.TAMPERED_ALTERNATIVE_PAYMENT -> {
            val original = view.actionSemantics?.get("alternativePayment") as? JsonObject
                ?: error("Candidate has no public alternativePayment to tamper")
            val current = original["equipPayment"]?.jsonPrimitive?.content
            val replacement = if (current == EquipPaymentChoice.NORMAL.name) {
                EquipPaymentChoice.FREE_FIRST_EQUIP.name
            } else {
                EquipPaymentChoice.NORMAL.name
            }
            put("alternativePayment", original.withEquipPayment(replacement))
        }
        PayloadMutation.MIXED_RESOURCE_AND_EQUIP_PAYMENT -> {
            val original = view.actionSemantics?.get("alternativePayment") as? JsonObject
                ?: error("Candidate has no public alternativePayment to mix")
            val target = selection.targetId ?: EntityId("b0-mixed-payment-target")
            put(
                "alternativePayment",
                buildJsonObject {
                    original.forEach { (key, value) -> put(key, value) }
                    put("harmonizeCreature", target.value)
                },
            )
        }
    }
    put(
        "paymentStrategy",
        Json.encodeToJsonElement(
            PaymentStrategy.serializer(),
            PaymentStrategy.ExplicitV3(paymentPlan = selection.plan),
        ),
    )
    selection.targetId?.let { targetId ->
        put(
            "targets",
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "Permanent")
                    put("entityId", targetId.value)
                })
            },
        )
    }
}

private fun JsonObject.withEquipPayment(value: String): JsonObject = buildJsonObject {
    forEach { (key, element) ->
        if (key == "equipPayment") put(key, JsonPrimitive(value)) else put(key, element)
    }
}

private fun buildReport(
    spec: B0EpisodeSpec,
    run: B0EpisodeRun,
    state: GameState,
    actor: EntityId,
    observationDiagnostics: String,
    records: List<AlternativePaymentRecord>,
    siblingRecords: List<AlternativePaymentRecord>,
    strictCandidate: AlternativePaymentRecord,
    strictResults: Map<String, StrictSubmissionResult>,
): String = buildString {
    appendLine("B0-ALTERNATIVE-PAYMENT-01 DECISION-571 DIAGNOSTIC")
    appendLine("MODE=CHARACTERIZATION_ONLY")
    appendLine("CHECKOUT_HEAD=${gitHead()}")
    appendLine("EPISODE_ID=${spec.episodeId}")
    appendLine("ENGINE_SEED=${spec.engineSeed}")
    appendLine("POLICY_SEED=${spec.policySeed}")
    appendLine("DECISION=${run.result.semanticExternalDecisionCount}")
    appendLine("ACTOR=$actor")
    appendLine("PHASE=${state.phase}")
    appendLine("TURN=${state.turnNumber}")
    appendLine("STEP=${state.step}")
    appendLine("RUN_CLOSURE=${run.result.closureKind}/${run.result.closureReason}")
    appendLine("RUN_FAILURE_STAGE=${run.failureBundle?.failureStage}")
    appendLine("RUN_FAILURE_REFERENCE=${run.failureBundle?.restrictedDiagnosticsReference}")
    appendLine("OBSERVATION_DIAGNOSTICS=$observationDiagnostics")
    appendLine("ALTERNATIVE_PAYMENT_CANDIDATE_COUNT=${records.size}")
    appendLine("SIBLING_ACTIVATE_CANDIDATE_COUNT=${siblingRecords.size}")
    siblingRecords.forEachIndexed { index, record ->
        appendLine("SIBLING[$index]")
        appendRecord(record)
    }
    appendLine("ALL_ALTERNATIVE_PAYMENT_CANDIDATES")
    records.forEachIndexed { index, record ->
        appendLine("CANDIDATE[$index]")
        appendRecord(record)
    }
    appendLine("STRICT_CANDIDATE=${strictCandidate.view.description} source=${strictCandidate.action.sourceId}")
    strictResults.forEach { (name, result) ->
        appendLine("STRICT_${name}=${result.detail}")
    }
    appendLine("DISCOVERED_STATE_PRIORITY_ACTOR=$actor")
}

private fun StringBuilder.appendRecord(record: AlternativePaymentRecord) {
    val action = record.action
    val view = record.view
    appendLine("sourceId=${action.sourceId.value}")
    appendLine("sourceCardName=${record.sourceCard?.name}")
    appendLine("sourceCardDefinitionId=${record.sourceCard?.cardDefinitionId}")
    appendLine("actionType=${view.kind}")
    appendLine("abilityId=${action.abilityId.value}")
    appendLine("abilityKey=${view.actionSemantics?.get("abilityKey")}")
    appendLine("description=${view.description}")
    appendLine("alternativePayment=${action.alternativePayment}")
    appendLine("equipPayment=${action.alternativePayment?.equipPayment}")
    appendLine("manaCost=${view.manaCost}")
    appendLine("affordable=${view.affordable}")
    appendLine("requiredPayloadFields=${view.requiredPayloadFields}")
    appendLine("actionSemantics=${view.actionSemantics}")
    appendLine("actionSemanticsAlternativePayment=${view.actionSemantics?.get("alternativePayment")}")
    appendLine("targetDomain=${view.targetDomain}")
    appendLine("paymentDomain=${view.paymentDomain}")
    appendLine("targetPaymentDomain=${view.targetPaymentDomain}")
    appendLine("paymentSelectionAvailable=${paymentSelection(view) != null}")
}

private fun completeRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun compactReplay(run: B0EpisodeRun, registry: CardRegistry): CompactReplay {
    val config = requireNotNull(run.replayConfig) { "B0 run has no replay config" }
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

private fun gitHead(): String = ProcessBuilder("git", "rev-parse", "HEAD")
    .redirectErrorStream(true)
    .start()
    .let { process ->
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { "Could not resolve checkout HEAD: $value" }
        value
    }
