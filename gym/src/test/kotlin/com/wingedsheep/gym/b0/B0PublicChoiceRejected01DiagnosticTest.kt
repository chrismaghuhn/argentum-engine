package com.wingedsheep.gym.b0

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.PublicActionSubmission
import com.wingedsheep.gym.SemanticChoice
import com.wingedsheep.gym.contract.ActionPayloadRequirements
import com.wingedsheep.gym.contract.AttackDeclarationDomainSubmission
import com.wingedsheep.gym.contract.BlockerDeclarationDomainSubmission
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ManaColorDomainSubmission
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ResolvedAction
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
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Characterizes the first public-choice rejection after the accepted B0 payment-policy fixes.
 * The harness is stopped immediately before the failing choice so the exact observation and the
 * public policy payload can be replayed against a freshly restored state without changing the B0
 * overlay, policy, decks, or seeds.
 */
class B0PublicChoiceRejected01DiagnosticTest : FunSpec({
    test("Decision 1032 reports the exact public-choice rejection gate") {
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
            interruptionProbe = B0InterruptionProbe { progress ->
                if (progress.semanticExternalDecisionCount >= 1_032) {
                    B0InterruptionRequest.ADMINISTRATIVE_CANCEL
                } else {
                    null
                }
            },
        )

        run.result.closureKind shouldBe B0ClosureKind.INTERRUPTED
        run.result.closureReason shouldBe B0ClosureReason.ADMINISTRATIVE_CANCEL
        run.result.semanticExternalDecisionCount shouldBe 1_032
        run.result.engineProgressCount shouldBe 1_032
        run.result.externalTransitionCount shouldBe 1_032
        run.failureBundle shouldBe null

        val registry = completeRegistry()
        val replay = compactReplay(run, registry)
        val state = ReplayReconstructor(registry, null).reconstructStateAt(
            replay = replay,
            frame = run.acceptedActions.size,
        ) ?: error("Could not reconstruct the exact pre-decision-1032 state")
        val actor = requireNotNull(state.priorityPlayerId) { "Decision 1032 has no priority actor" }
        val builder = ObservationBuilder(cardRegistry = registry)
        val legalActions = LegalActionEnumerator.create(registry)
            .enumerate(state, actor, EnumerationMode.ACTIONS_ONLY)
            .filterNot(LegalAction::hasUnfillableTargetRequirement)

        fun freshEnvironment(): GameEnvironment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        ).also {
            it.restore(
                state = state,
                playerIds = state.turnOrder,
                stepCount = run.acceptedActions.size,
            )
        }

        val environment = freshEnvironment()
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = state.turnOrder.indexOf(actor),
            observationBuilder = builder,
        )
        val gymObservation = gym.observe()
        gymObservation.diagnostics shouldBe emptyList()
        val training = gymObservation.observation as? TrainingObservation
            ?: error("Expected TrainingObservation")

        val policy = B0SeededPublicPolicy()
        val choice = policy.choose(
            observation = training,
            state = B0PolicyState(
                policySeed = spec.policySeed,
                choiceOrdinal = run.result.semanticExternalDecisionCount.toLong(),
            ),
        )
        val actionChoice = choice as? SemanticChoice.Action
            ?: error("Expected the seeded policy to choose an action, got $choice")
        actionChoice.family shouldBe "ActivateAbility"
        val selectedIndex = training.legalActions.indexOfFirst { it.actionId == actionChoice.actionId }
        require(selectedIndex >= 0) { "Policy selected an action absent from its observation" }
        val selectedView = training.legalActions[selectedIndex]
        val payload = actionChoice.payload
            ?: error("Decision 1032 selected a structured action without a payload")
        selectedView.kind shouldBe "ActivateAbility"
        selectedView.description shouldBe "Equip {0}"
        selectedView.sourceEntityId shouldBe EntityId("e173")
        selectedView.manaCost shouldBe "{0}"
        selectedView.affordable shouldBe true
        selectedView.requiredPayloadFields shouldBe listOf("targets", "paymentStrategy", "alternativePayment")
        selectedView.targetPaymentDomain shouldBe null
        selectedView.paymentDomain shouldNotBe null
        selectedView.paymentDomain!!.requiredCost shouldBe "{0}"
        selectedView.targetDomain!!.requirements.single().candidates shouldBe listOf(
            EntityId("e141"),
            EntityId("e157"),
            EntityId("e159"),
        )

        val directObservation = builder.build(
            state = state,
            perspectivePlayerId = actor,
            legalActions = legalActions,
        )
        directObservation.diagnostics shouldBe emptyList()
        val directTraining = directObservation.observation as? TrainingObservation
            ?: error("Expected direct TrainingObservation")
        directTraining.legalActions.map(::publicViewKey) shouldBe training.legalActions.map(::publicViewKey)
        val registeredView = directTraining.legalActions[selectedIndex]
        val registered = directObservation.registry.resolve(registeredView.actionId)
            as? ResolvedAction.Legal
            ?: error("Selected public view did not resolve to a LegalAction")
        val registeredAction = registered.legalAction
        val activate = registeredAction.action as? ActivateAbility
            ?: error("Expected the failing candidate to be ActivateAbility")
        activate.abilityId.value shouldBe "ability_3100"
        activate.alternativePayment?.equipPayment shouldBe EquipPaymentChoice.FREE_FIRST_EQUIP
        val sourceId = activate.sourceId
        val sourceName = state.getEntity(sourceId)?.get<CardComponent>()?.name
        sourceName shouldBe "Embercleave"
        val abilityKey = selectedView.actionSemantics?.get("abilityKey")
        val siblings = training.legalActions.filter { view ->
            view.kind == "ActivateAbility" &&
                view.sourceEntityId == selectedView.sourceEntityId &&
                view.actionSemantics?.get("abilityKey") == abilityKey
        }

        val currentActions = freshEnvironment().legalActions()
        val currentMatches = currentActions.filter { candidate ->
            environment.isCurrentActionCandidate(candidate.action, registeredAction.action)
        }
        val currentAction = currentMatches.firstOrNull()
        currentMatches.size shouldBe 1
        currentAction!!.action shouldBe registeredAction.action
        val currentView = currentAction?.let { candidate ->
            builder.build(state, actor, listOf(candidate)).observation
        } as? TrainingObservation
        val materialized = PublicActionSubmission.materialize(registeredAction.action, payload)
        val materializedActivate = materialized as? ActivateAbility
            ?: error("Expected an ActivateAbility payload")
        materializedActivate.targets shouldBe listOf(ChosenTarget.Permanent(EntityId("e159")))
        materializedActivate.alternativePayment?.equipPayment shouldBe EquipPaymentChoice.FREE_FIRST_EQUIP

        val gateResults = linkedMapOf<String, String>()
        fun probe(name: String, body: () -> Unit) {
            gateResults[name] = try {
                body()
                "PASS"
            } catch (failure: Exception) {
                "${failure::class.qualifiedName}: ${failure.message}"
            }
        }
        probe("1-required-fields") {
            builder.missingRequiredFieldsFor(state, registeredAction, payload) shouldBe emptyList()
        }
        probe("2-materialize-decode") { materialized }
        probe("3-target-payload-partition") {
            ActionPayloadRequirements.requireTargetDomainSupported(registeredAction)
            ActionPayloadRequirements.requireTargetPayloadPartition(registeredAction, materialized)
        }
        probe("3b-combat-and-mana-domains") {
            AttackDeclarationDomainSubmission.requireWithinRegisteredDomain(registeredAction, materialized)
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(registeredAction, materialized)
            ManaColorDomainSubmission.requireWithinRegisteredDomain(registeredAction, materialized)
        }
        probe("4-payment-domain") {
            require(builder.paymentDomainV5For(state, registeredAction) != null) {
                "No action-level PaymentDomainV5 was available"
            }
        }
        probe("5-current-candidate") {
            require(currentMatches.isNotEmpty()) { "No current candidate matched the registered action" }
        }
        probe("6-registered-current-identity") {
            require(currentAction != null) { "No current ActivateAbility candidate" }
            require(environment.isCurrentActionCandidate(currentAction.action, materialized)) {
                "Materialized action is not a current candidate"
            }
        }
        gateResults.values.all { it == "PASS" } shouldBe true

        val directExecutionEnvironment = freshEnvironment()
        val directBeforeState = directExecutionEnvironment.state
        val directBeforeStepCount = directExecutionEnvironment.stepCount
        val directBeforeEvents = directExecutionEnvironment.events
        val directBeforeLastStepEvents = directExecutionEnvironment.lastStepEvents
        val directExecutionFailure = runCatching {
            directExecutionEnvironment.stepFromCandidateStrict(registeredAction, materialized)
        }.exceptionOrNull()
        val directExecutionOutcome = directExecutionFailure?.let {
            "REJECTED ${it::class.qualifiedName}: ${it.message}"
        } ?: "ACCEPTED"
        if (directExecutionFailure != null) {
            directExecutionEnvironment.state shouldBe directBeforeState
            directExecutionEnvironment.stepCount shouldBe directBeforeStepCount
            directExecutionEnvironment.events shouldBe directBeforeEvents
            directExecutionEnvironment.lastStepEvents shouldBe directBeforeLastStepEvents
        }

        val beforeState = environment.state
        val beforeStepCount = environment.stepCount
        val beforeEvents = environment.events
        val beforeLastStepEvents = environment.lastStepEvents
        val strictFailure = runCatching { gym.step(actionChoice.actionId, payload) }.exceptionOrNull()
        strictFailure shouldNotBe null
        (strictFailure is IllegalArgumentException) shouldBe true
        strictFailure?.message.orEmpty().isNotBlank() shouldBe true
        strictFailure?.message shouldBe "Loran of the Third Path has protection from red"
        directExecutionFailure?.message shouldBe "Loran of the Third Path has protection from red"
        environment.state shouldBe beforeState
        environment.stepCount shouldBe beforeStepCount
        environment.events shouldBe beforeEvents
        environment.lastStepEvents shouldBe beforeLastStepEvents

        println(
            buildString {
                appendLine("B0_PUBLIC_CHOICE_REJECTED_01")
                appendLine("head=${run.result.engineCommit}")
                appendLine("episode=${spec.episodeId} engineSeed=${spec.engineSeed} policySeed=${spec.policySeed}")
                appendLine("decision=1032 actor=$actor")
                appendLine("selected.semanticKey=${actionChoice.semanticKey}")
                appendLine("selected.actionId=${actionChoice.actionId} kind=${selectedView.kind}")
                appendLine("selected.description=${selectedView.description}")
                appendLine("selected.sourceEntityId=${selectedView.sourceEntityId} sourceName=$sourceName")
                appendLine("selected.abilityKey=$abilityKey abilityId=${activate.abilityId}")
                appendLine("selected.requiredPayloadFields=${selectedView.requiredPayloadFields}")
                appendLine("selected.actionSemantics=${selectedView.actionSemantics}")
                appendLine("selected.targetDomain=${selectedView.targetDomain}")
                appendLine("selected.paymentDomain=${selectedView.paymentDomain}")
                appendLine("selected.targetPaymentDomain=${selectedView.targetPaymentDomain}")
                appendLine("selected.availableManaColors=${selectedView.availableManaColors}")
                appendLine("selected.affordable=${selectedView.affordable}")
                appendLine("registered.activate=$activate")
                appendLine("registered.alternativePayment=${activate.alternativePayment}")
                appendLine("registered.additionalCostInfo=${registeredAction.additionalCostInfo}")
                appendLine("registered.targetRequirements=${registeredAction.targetRequirements}")
                appendLine("registered.manaCostString=${registeredAction.manaCostString}")
                appendLine("registered.affordable=${registeredAction.affordable}")
                appendLine("current.matchCount=${currentMatches.size}")
                appendLine("current.action=${currentAction?.action}")
                appendLine("current.publicView=${currentView?.legalActions?.singleOrNull()}")
                appendLine("registeredCurrentPublicEqual=${currentView?.legalActions?.singleOrNull()?.let(::publicViewKey) == publicViewKey(registeredView)}")
                appendLine("payload=$payload")
                appendLine("payloadVsSemantics=${jsonDelta(selectedView.actionSemantics, payload)}")
                appendLine("materialized=$materialized")
                appendLine("siblings.count=${siblings.size}")
                siblings.forEachIndexed { index, sibling ->
                    appendLine("sibling[$index]=${siblingPublicRecord(sibling)}")
                }
                appendLine("gateResults=$gateResults")
                appendLine("directExecution=$directExecutionOutcome")
                appendLine("strictGym=REJECTED ${strictFailure!!::class.qualifiedName}: ${strictFailure.message}")
                appendLine("atomicity=state/events/lastStepEvents/stepCount-unchanged")
            },
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

private fun publicViewKey(view: LegalActionView): String = buildString {
    append(view.kind).append('|')
    append(view.description).append('|')
    append(view.sourceEntityId?.value).append('|')
    append(view.manaCost).append('|')
    append(view.affordable).append('|')
    append(view.requiredPayloadFields).append('|')
    append(view.actionSemantics).append('|')
    append(view.targetDomain).append('|')
    append(view.paymentDomain).append('|')
    append(view.targetPaymentDomain).append('|')
    append(view.availableManaColors).append('|')
    append(view.targetEntityIds)
}

private fun jsonDelta(expected: JsonObject?, actual: JsonObject): String {
    val keys = (expected?.keys.orEmpty() + actual.keys).toSortedSet()
    return keys.joinToString(prefix = "{", postfix = "}", separator = ", ") { key ->
        "$key=${expected?.get(key)} -> ${actual[key]}"
    }
}

private fun siblingPublicRecord(view: LegalActionView): String = buildString {
    append("actionId=").append(view.actionId)
    append(" description=").append(view.description)
    append(" sourceEntityId=").append(view.sourceEntityId)
    append(" manaCost=").append(view.manaCost)
    append(" affordable=").append(view.affordable)
    append(" requiredPayloadFields=").append(view.requiredPayloadFields)
    append(" actionSemantics=").append(view.actionSemantics)
    append(" targetDomain=").append(view.targetDomain)
    append(" paymentDomain=").append(view.paymentDomain)
    append(" targetPaymentDomain=").append(view.targetPaymentDomain)
    append(" availableManaColors=").append(view.availableManaColors)
}
