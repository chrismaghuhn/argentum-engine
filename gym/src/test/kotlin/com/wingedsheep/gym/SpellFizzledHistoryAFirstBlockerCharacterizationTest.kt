package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SpellFizzledEvent
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveEventClassification
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventUnsupportedReason
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

private data class SpellFizzledProjectionFact(
    val rawEventType: String,
    val family: PerspectiveEventFamily?,
    val disposition: PerspectiveEventDisposition,
    val reason: PerspectiveEventUnsupportedReason?,
)

/** Regression proving the former SpellFizzledEvent History-A blocker remains closed. */
class SpellFizzledHistoryAFirstBlockerCharacterizationTest : FunSpec({
    test("crosses the former SpellFizzledEvent History-A blocker") {
        val registry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }
        val root = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }

        fun lockedDeck(fileName: String): List<String> = Files.readAllLines(
            root.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName),
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
            useHandSmoother = false,
            startingPlayerIndex = 0,
            format = Format.Commander(),
            seed = 1L,
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
            maxSteps = 2_000,
            semanticEpisodeId = "spell-fizzled-history-a-first-blocker-seed-1-start-0",
        ).observation as TrainingObservation
        val policy = DeterministicExternalPolicy()
        // This is the existing B1 per-cell derivation: seed * 1_000_003 + start * 97_409 +
        // roster (Akiri in seat 0) * 65_537.
        var policyState = DeterministicPolicyState(
            policySeed = 1L * 1_000_003L + 0L * 97_409L + 0x41L * 65_537L,
        )
        var successfulChoices = 0
        var failure: HistoryDOperationException? = null
        var beforeStateAtFizzle: GameState? = null
        var afterStateAtFizzle: GameState? = null
        var eventsAtFizzle: List<GameEvent> = emptyList()

        while (!observation.terminated && !observation.truncated && failure == null) {
            val beforeState = environment.state
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            try {
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
                            response = spellFizzledDecisionResponse(decisionId, choice.selection),
                            actorId = observation.agentToAct,
                        ).observation
                    }

                    is SemanticChoice.Gap -> error(
                        "Policy gap at choice=$successfulChoices " +
                            "family=${choice.family} code=${choice.code}",
                    )
                } as TrainingObservation
                if (eventsAtFizzle.isEmpty() && environment.lastStepEvents.any { it is SpellFizzledEvent }) {
                    beforeStateAtFizzle = beforeState
                    afterStateAtFizzle = environment.state
                    eventsAtFizzle = environment.lastStepEvents.toList()
                }
                successfulChoices++
            } catch (exception: HistoryDOperationException) {
                failure = exception
            }
        }

        successfulChoices shouldBe 2000
        environment.stepCount shouldBe 2000
        failure shouldBe null
        observation.terminated shouldBe false
        observation.truncated shouldBe true
        eventsAtFizzle.map { it::class.simpleName ?: "UnknownGameEvent" } shouldBe listOf(
            "SpellFizzledEvent",
            "ZoneChangeEvent",
        )

        val spellFizzled = eventsAtFizzle.filterIsInstance<SpellFizzledEvent>().single()
        val zoneChange = eventsAtFizzle.filterIsInstance<ZoneChangeEvent>().single()
        spellFizzled.spellEntityId.value.isNotBlank() shouldBe true
        spellFizzled.cardName.isNotBlank() shouldBe true
        spellFizzled.reason shouldBe "All targets are invalid"
        zoneChange.entityId shouldBe spellFizzled.spellEntityId
        zoneChange.fromZone shouldBe null
        zoneChange.toZone shouldBe Zone.GRAVEYARD

        val beforeState = checkNotNull(beforeStateAtFizzle)
        val afterState = checkNotNull(afterStateAtFizzle)
        val beforeStamp = beforeState.objectIdentityStamps[spellFizzled.spellEntityId]
        val afterStamp = afterState.objectIdentityStamps[spellFizzled.spellEntityId]
        beforeState.hasEntity(spellFizzled.spellEntityId) shouldBe true
        afterState.hasEntity(spellFizzled.spellEntityId) shouldBe true
        (beforeStamp != null) shouldBe true
        (afterStamp != null) shouldBe true
        (beforeStamp != afterStamp) shouldBe true

        val rawFieldNames = (0 until SpellFizzledEvent.serializer().descriptor.elementsCount)
            .map { index -> SpellFizzledEvent.serializer().descriptor.getElementName(index) }
        rawFieldNames shouldBe listOf("spellEntityId", "cardName", "reason")

        val projector = com.wingedsheep.gym.contract.PerspectiveEventProjector(registry)
        val perspectiveFacts = environment.playerIds.map { perspectivePlayerId ->
            val projection = projector.project(
                events = eventsAtFizzle,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = beforeState,
                afterState = afterState,
            )
            projection.isComplete shouldBe true
            projectionFacts(projection)
        }
        val expectedFacts = listOf(
            SpellFizzledProjectionFact(
                rawEventType = "SpellFizzledEvent",
                family = PerspectiveEventFamily.SPELL_FIZZLED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
            SpellFizzledProjectionFact(
                rawEventType = "ZoneChangeEvent",
                family = PerspectiveEventFamily.ZONE_CHANGED,
                disposition = PerspectiveEventDisposition.EMITTED,
                reason = null,
            ),
        )
        perspectiveFacts shouldBe listOf(expectedFacts, expectedFacts)

        println(
            "SPELL_FIZZLED_HISTORY_A_CLOSURE " +
                "successfulChoices=$successfulChoices " +
                "committedStep=${environment.stepCount} " +
                "failure=${failure?.failure?.code} " +
                "rawEvents=${eventsAtFizzle.map { it::class.simpleName ?: "UnknownGameEvent" }} " +
                "perspectives=${perspectiveFacts.size} " +
                "historyA=SPELL_FIZZLED/EMITTED " +
                "cDependency=RESOLVED " +
                "rulesMetadataDependency=NONE_NEW " +
                "beforeWitness=true " +
                "afterWitness=true " +
                "sameIncarnation=false " +
                "candidateRole=EVENT_SUBJECT " +
                "referenceKind=STACK_OBJECT " +
                "endpointAuthority=BEFORE_OBJECT",
        )
    }
})

private fun projectionFacts(
    projection: PerspectiveEventProjectionResult,
): List<SpellFizzledProjectionFact> {
    var emittedOrdinal = 0
    return projection.classifications.map { classification: PerspectiveEventClassification ->
        val family = if (classification.disposition == PerspectiveEventDisposition.EMITTED) {
            projection.batch.entries[emittedOrdinal++].eventFamily
        } else {
            null
        }
        SpellFizzledProjectionFact(
            rawEventType = classification.rawEventType,
            family = family,
            disposition = classification.disposition,
            reason = classification.reason,
        )
    }
}

private fun spellFizzledDecisionResponse(
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
