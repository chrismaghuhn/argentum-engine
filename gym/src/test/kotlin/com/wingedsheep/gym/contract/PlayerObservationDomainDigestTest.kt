package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class PlayerObservationDomainDigestTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val environment = GameEnvironment.create(registry())
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 70L,
            ),
        )
        return environment
    }

    fun sourceObservation(environment: GameEnvironment): TrainingObservation =
        ObservationBuilder(cardRegistry = registry()).build(
            state = environment.state,
            perspectivePlayerId = environment.playerIds.first(),
            legalActions = environment.legalActions(),
        ).observation as TrainingObservation

    fun foldedObservation(environment: GameEnvironment): TrainingObservation {
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val folded = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-folded-id",
                kind = PendingDecisionKind.YES_NO,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = false,
                shape = DecisionShape(),
            ),
            legalActions = listOf(
                LegalActionView(
                    actionId = 0,
                    kind = "DECISION",
                    description = "Yes",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", true)
                    },
                    isDecisionOption = true,
                ),
                LegalActionView(
                    actionId = 1,
                    kind = "DECISION",
                    description = "No",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", false)
                    },
                    isDecisionOption = true,
                ),
            ),
        )
        return folded.copy(stateDigest = StateDigest.compute(folded))
    }

    fun structuredObservation(environment: GameEnvironment): TrainingObservation {
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val target = EntityId("target-a")
        val structured = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-structured-id",
                kind = PendingDecisionKind.CHOOSE_TARGETS,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(),
                structuredDomain = TargetsDomain(
                    requirements = listOf(
                        TargetRequirementDomain(
                            index = 0,
                            description = "target",
                            minTargets = 1,
                            maxTargets = 1,
                            candidates = listOf(target),
                            targetZone = null,
                            mustDifferFromEarlier = false,
                            sameController = false,
                            sameOwner = false,
                            sameCreatureType = false,
                            sameCardType = false,
                            totalManaValueAtMost = null,
                            differentNames = false,
                            xConstrainsManaValue = false,
                            xConstrainsPower = false,
                            xConstrainsCount = false,
                            xConstrainsManaValueExactly = false,
                        ),
                    ),
                    canCancel = false,
                ),
            ),
            legalActions = emptyList(),
        )
        return structured.copy(stateDigest = StateDigest.compute(structured))
    }

    fun orderingObservation(environment: GameEnvironment): TrainingObservation {
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val first = EntityId("trigger-order-object-a")
        val second = EntityId("trigger-order-object-b")
        val ordering = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-ordering-id",
                kind = PendingDecisionKind.ORDER_OBJECTS,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(),
                structuredDomain = OrderingDomain(
                    objects = listOf(first, second),
                    cardInfo = mapOf(
                        first to StructuredCardInfo(
                            name = "First Trigger",
                            manaCost = "{1}",
                            typeLine = "Creature — Human",
                            colors = listOf("RED"),
                            power = 1,
                        ),
                        second to StructuredCardInfo(
                            name = "Second Trigger",
                            manaCost = "{2}",
                            typeLine = "Creature — Wizard",
                            colors = listOf("BLUE"),
                            power = 2,
                        ),
                    ),
                    objectLabels = mapOf(
                        first to "First Trigger #1",
                        second to "Second Trigger #1",
                    ),
                ),
            ),
            legalActions = emptyList(),
        )
        return ordering.copy(stateDigest = StateDigest.compute(ordering))
    }

    fun assertSourceParity(source: TrainingObservation, domain: CompleteLegalDomainV1) {
        val projection = PlayerObservationV1.from(source)

        ObservationCanonicalizer.semanticJson(source) shouldBe
            ObservationCanonicalizer.semanticJson(projection, domain)
        StateDigest.compute(source) shouldBe StateDigest.compute(projection, domain)
        source.stateDigest shouldBe StateDigest.compute(projection, domain)
    }

    test("action observation and complete action domain reassemble the exact source digest") {
        val source = sourceObservation(environment())

        assertSourceParity(source, CompleteLegalDomainV1.from(source))
    }

    test("folded decision observation and domain reassemble the exact source digest") {
        val source = foldedObservation(environment())

        assertSourceParity(source, CompleteLegalDomainV1.from(source))
    }

    test("structured decision observation and domain reassemble the exact source digest") {
        val source = structuredObservation(environment())

        assertSourceParity(source, CompleteLegalDomainV1.from(source))
        source.pendingDecision?.structuredDomain shouldNotBe null
    }

    test("ordering structured domains preserve source digest parity") {
        val source = orderingObservation(environment())
        val projection = PlayerObservationV1.from(source)
        val domain = CompleteLegalDomainV1.from(source)

        StateDigest.compute(source) shouldBe StateDigest.compute(projection, domain)
        source.stateDigest shouldBe StateDigest.compute(projection, domain)
    }

    test("ordering structured domains preserve both trigger semantics in canonical JSON") {
        val source = orderingObservation(environment())
        val projection = PlayerObservationV1.from(source)
        val domain = CompleteLegalDomainV1.from(source)

        ObservationCanonicalizer.semanticJson(source) shouldBe
            ObservationCanonicalizer.semanticJson(projection, domain)
        val semantic = Json.parseToJsonElement(
            ObservationCanonicalizer.semanticJson(projection, domain),
        ).jsonObject
        semantic.getValue("pendingDecision").jsonObject
            .getValue("structuredDomain").jsonObject
            .getValue("objectSemantics").jsonArray.size shouldBe 2
    }

    test("a durable observation mutation changes the reassembled source digest") {
        val source = sourceObservation(environment())
        val projection = PlayerObservationV1.from(source)
        val domain = CompleteLegalDomainV1.from(source)
        val changed = projection.copy(turnNumber = projection.turnNumber + 1)

        StateDigest.compute(changed, domain) shouldNotBe source.stateDigest
    }

    test("the stored observation digest is excluded from its own reassembly preimage") {
        val source = sourceObservation(environment())
        val projection = PlayerObservationV1.from(source).copy(observationDigest = "f".repeat(64))
        val domain = CompleteLegalDomainV1.from(source)

        StateDigest.compute(projection, domain) shouldBe source.stateDigest
    }

    test("the reassembly seam rejects an A1 observation paired with the wrong A2 shape") {
        val structured = structuredObservation(environment())
        val projection = PlayerObservationV1.from(structured)
        val actionDomain = CompleteLegalDomainV1.from(sourceObservation(environment()))

        shouldThrow<IllegalArgumentException> {
            StateDigest.compute(projection, actionDomain)
        }
    }
})
