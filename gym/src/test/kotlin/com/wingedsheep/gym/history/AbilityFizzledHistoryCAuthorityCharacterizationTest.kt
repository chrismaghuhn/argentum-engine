package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.AbilityResolvedEvent
import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.DeterministicExternalPolicy
import com.wingedsheep.gym.DeterministicPolicyState
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.SemanticChoice
import com.wingedsheep.gym.SemanticDecision
import com.wingedsheep.gym.toDecisionResponse
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

private data class AbilityFizzledSourceWitnessMatrix(
    val beforeWitnessPresent: Boolean,
    val afterWitnessPresent: Boolean,
    val beforeStampPresent: Boolean,
    val afterStampPresent: Boolean,
    val sameIncarnation: Boolean,
)

/** Test-only characterization of the missing AbilityFizzledEvent History-C source contract. */
class AbilityFizzledHistoryCAuthorityCharacterizationTest : FunSpec({
    test("compares AbilityFizzledEvent source authority with AbilityResolvedEvent") {
        val registry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }

        fun lockedDeck(fileName: String): List<String> = Files.readAllLines(
            repositoryRoot.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName),
        )
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }

        val akiri = lockedDeck("akiri-v0.1.txt")
        val chevill = lockedDeck("chevill-v0.1.txt")
        val config = GameConfig(
            players = listOf(
                com.wingedsheep.engine.core.PlayerConfig(
                    name = "Akiri",
                    deck = Deck(akiri.drop(1)),
                    startingLife = 40,
                    commanderCardName = akiri.first(),
                ),
                com.wingedsheep.engine.core.PlayerConfig(
                    name = "Chevill",
                    deck = Deck(chevill.drop(1)),
                    startingLife = 40,
                    commanderCardName = chevill.first(),
                ),
            ),
            startingHandSize = 7,
            skipMulligans = true,
            startingPlayerIndex = 0,
            format = Format.Commander(),
            seed = 0L,
        )
        val environment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        var observation = gym.reset(
            gameConfig = config,
            maxSteps = 4_000,
            semanticEpisodeId = "ability-fizzled-history-c-authority-seed-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed = 0x41L)
        var successfulChoices = 0
        var capturedTransition: CommittedRulesTransition? = null

        while (environment.stepCount < 2_767) {
            val beforeState = environment.state
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            observation = when (choice) {
                is SemanticChoice.Action -> {
                    if (choice.payload == null) {
                        gym.step(choice.actionId).observation
                    } else {
                        gym.step(choice.actionId, choice.payload).observation
                    }
                }

                is SemanticChoice.Structured -> {
                    val pending = checkNotNull(observation.pendingDecision)
                    val decisionId = checkNotNull(pending.decisionId)
                    gym.submitDecision(
                        response = abilityFizzledDecisionResponse(decisionId, choice.selection),
                        actorId = observation.agentToAct,
                    ).observation
                }

                is SemanticChoice.Gap -> error("Policy gap at choice=$successfulChoices: $choice")
            } as TrainingObservation
            successfulChoices++
            if (environment.stepCount == 2_767) {
                capturedTransition = CommittedRulesTransition(
                    beforeState = beforeState,
                    afterState = environment.state,
                    events = environment.lastStepEvents.toList(),
                    sourceStepCount = environment.stepCount,
                )
            }
        }

        val transition = checkNotNull(capturedTransition)
        successfulChoices shouldBe 2_767
        transition.sourceStepCount shouldBe 2_767
        transition.events.map { it::class.simpleName ?: "UnknownGameEvent" } shouldBe listOf(
            "ResolvedEvent",
            "ZoneChangeEvent",
            "AbilityFizzledEvent",
        )

        val fizzled = transition.events.filterIsInstance<AbilityFizzledEvent>().single()
        val zoneChange = transition.events.filterIsInstance<ZoneChangeEvent>().single()
        fizzled.sourceId.value.isNotBlank() shouldBe true
        fizzled.description.isNotBlank() shouldBe true
        fizzled.reason.isNotBlank() shouldBe true

        val beforeStamp = transition.beforeState.objectIdentityStamps[fizzled.sourceId]
        val afterStamp = transition.afterState.objectIdentityStamps[fizzled.sourceId]
        val witnessMatrix = AbilityFizzledSourceWitnessMatrix(
            beforeWitnessPresent = transition.beforeState.hasEntity(fizzled.sourceId) && beforeStamp != null,
            afterWitnessPresent = transition.afterState.hasEntity(fizzled.sourceId) && afterStamp != null,
            beforeStampPresent = beforeStamp != null,
            afterStampPresent = afterStamp != null,
            sameIncarnation = beforeStamp != null && beforeStamp == afterStamp,
        )
        witnessMatrix.beforeWitnessPresent shouldBe true
        witnessMatrix.afterWitnessPresent shouldBe true
        witnessMatrix.beforeStampPresent shouldBe true
        witnessMatrix.afterStampPresent shouldBe true
        witnessMatrix.sameIncarnation shouldBe false
        (zoneChange.entityId == fizzled.sourceId) shouldBe true
        zoneChange.fromZone shouldBe null
        zoneChange.toZone shouldBe Zone.BATTLEFIELD
        val observedStep2767Endpoint = if (
            zoneChange.entityId == fizzled.sourceId &&
            witnessMatrix.afterWitnessPresent &&
            !witnessMatrix.sameIncarnation
        ) {
            "AFTER_OBJECT"
        } else {
            "MISSING_AUTHORITATIVE_METADATA"
        }
        observedStep2767Endpoint shouldBe "AFTER_OBJECT"

        val projector = com.wingedsheep.gym.contract.PerspectiveEventProjector(registry)
        val fizzledProjections = environment.playerIds.map { perspectivePlayerId ->
            projector.project(
                events = transition.events,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = transition.beforeState,
                afterState = transition.afterState,
            )
        }
        fizzledProjections.forEach { projection ->
            projection.isComplete shouldBe true
            projection.classifications.last().rawEventType shouldBe "AbilityFizzledEvent"
            projection.classifications.last().disposition shouldBe PerspectiveEventDisposition.EMITTED
            projection.batch.entries.last().eventFamily shouldBe PerspectiveEventFamily.ABILITY_FIZZLED

            val produced = HistoryCReferenceEnvelopeProducerV1.produce(transition, projection)
                .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
            val fizzledEventOrdinal = projection.classifications.indexOfLast {
                it.rawEventType == "AbilityFizzledEvent"
            }
            produced.envelope.candidates.filter {
                it.slot.eventOrdinal == fizzledEventOrdinal
            } shouldBe emptyList()
        }

        val resolved = AbilityResolvedEvent(
            sourceId = fizzled.sourceId,
            description = fizzled.description,
        )
        val resolvedTransition = transition.copy(events = listOf<GameEvent>(resolved))
        val resolvedProjections = environment.playerIds.map { perspectivePlayerId ->
            projector.project(
                events = resolvedTransition.events,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = resolvedTransition.beforeState,
                afterState = resolvedTransition.afterState,
            )
        }
        resolvedProjections.forEach { projection ->
            val produced = HistoryCReferenceEnvelopeProducerV1.produce(resolvedTransition, projection)
                .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
            val candidate = produced.envelope.candidates.single()
            candidate.slot.role shouldBe HistoryCReferenceSlotRole.SOURCE
            candidate.referenceKind shouldBe HistoryCReferenceKind.CARD_OR_RULES_OBJECT
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
            (candidate.afterWitness != null) shouldBe true
        }

        println(
            "ABILITY_FIZZLED_HISTORY_C_CHARACTERIZATION " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${transition.sourceStepCount} " +
                "rawEvents=${transition.events.map { it::class.simpleName ?: "UnknownGameEvent" }} " +
                "sourceBeforeWitness=${witnessMatrix.beforeWitnessPresent} " +
                "sourceAfterWitness=${witnessMatrix.afterWitnessPresent} " +
                "sourceBeforeStampPresent=${witnessMatrix.beforeStampPresent} " +
                "sourceAfterStampPresent=${witnessMatrix.afterStampPresent} " +
                "sameIncarnation=${witnessMatrix.sameIncarnation} " +
                "sourceMatchesZoneChange=true " +
                "zoneChange=${zoneChange.fromZone?.name ?: "UNKNOWN"}->${zoneChange.toZone.name} " +
                "observedStep2767Endpoint=$observedStep2767Endpoint " +
                "genericEndpointMetadataPresent=false " +
                "rawFields=[sourceId, description, reason] " +
                "fizzledCandidateCount=0 " +
                "resolvedCandidateAuthority=AFTER_OBJECT",
        )
    }
})

private fun abilityFizzledDecisionResponse(
    decisionId: String,
    selection: SemanticDecision,
): DecisionResponse = when (selection) {
    is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
    is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
    is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Distribution -> DistributionResponse(decisionId, selection.selected)
    is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
    is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
    is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Replacement -> ReplacementChosenResponse(decisionId, selection.from, selection.to)
    is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
    is SemanticDecision.Damage -> CombatResolutionResponse(
        decisionId = decisionId,
        edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
    )
    is SemanticDecision.Payment -> selection.toDecisionResponse(decisionId)
}
