package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.CardCycledEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveHistoryReferenceRoleV1
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class CardCycledEventHistoryClosureTest : FunSpec({
    val perspective = EntityId.of("perspective")
    val cyclingPlayer = EntityId.of("cycling-player")
    val cardDefinition = "Barren Moor"
    val objectStamp = 17L

    fun state(
        cardId: EntityId,
        zone: Zone,
        cardName: String = cardDefinition,
        stamp: Long = objectStamp,
    ): GameState = GameState(
        entities = mapOf(
            perspective to ComponentContainer.EMPTY,
            cyclingPlayer to ComponentContainer.EMPTY,
            cardId to ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = cardDefinition,
                    name = cardName,
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                    ownerId = cyclingPlayer,
                ),
            ),
        ),
        zones = mapOf(ZoneKey(cyclingPlayer, zone) to listOf(cardId)),
        turnOrder = listOf(perspective, cyclingPlayer),
        objectIdentityStamps = mapOf(cardId to stamp),
    )

    fun project(
        cardId: EntityId,
        eventCardName: String,
        beforeCardName: String,
        beforeStamp: Long = objectStamp - 1L,
        afterStamp: Long = objectStamp,
    ): AutomaticHistoryCReferenceProjectionResult.Accepted {
        val source = CommittedPerspectiveEventSource(CardRegistry())
        source.capture(
            CommittedRulesTransition(
                beforeState = state(cardId, Zone.HAND, beforeCardName, stamp = beforeStamp),
                afterState = state(cardId, Zone.GRAVEYARD, stamp = afterStamp),
                events = listOf(
                    CardCycledEvent(
                        playerId = cyclingPlayer,
                        cardId = cardId,
                        cardName = eventCardName,
                        xValue = 2,
                    ),
                ),
                sourceStepCount = 93,
            ),
        )
        return source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "card-cycled-history-closure",
            perspectivePlayerId = perspective,
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "card-cycled-history-closure",
                perspectivePlayerId = perspective,
            ),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
    }

    fun history(result: AutomaticHistoryCReferenceProjectionResult.Accepted) =
        PerspectiveHistoryComposerV1.append(
            state = PerspectiveHistoryStateV1.start(
                semanticEpisodeId = "card-cycled-history-closure",
                playerIds = listOf(perspective, cyclingPlayer),
            ),
            eventBatch = result.evidence.eventBatch,
            evidence = result.evidence,
            projection = result.projection,
        ).histories.getValue(perspective)

    test("CardCycledEvent crosses A/C with a safe payload and exact after witness") {
        val result = project(
            cardId = EntityId.of("cycled-runtime-id"),
            eventCardName = "Private event name",
            beforeCardName = "Private state name",
        )
        val eventBatch = result.evidence.eventBatch
        val entry = eventBatch.entries.single()

        entry.eventFamily.name shouldBe "CARD_CYCLED"
        entry.semanticPayload.toString() shouldBe
            "{\"type\":\"card_cycled\",\"playerRole\":\"OTHER\",\"xValue\":2}"
        eventBatch.canonicalJson() shouldNotContain "cycled-runtime-id"
        eventBatch.canonicalJson() shouldNotContain "Private event name"
        eventBatch.canonicalJson() shouldNotContain objectStamp.toString()

        val candidate = result.evidence.candidates.single()
        candidate.slot shouldBe HistoryCReferenceSlot(
            eventOrdinal = 0,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = 0,
        )
        candidate.beforeWitness shouldBe null
        candidate.afterWitness shouldBe HistoryCObjectWitness(
            EntityId.of("cycled-runtime-id"),
            objectStamp,
        )
        candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
        candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
        candidate.orderProof shouldBe HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = 0,
        )

        val reference = result.projection.referenceOccurrences.single()
        reference.alias.canonical() shouldBe "o0"
        reference.identityDisclosure shouldBe HistoryCIdentityDisclosure.DEFINITION_KNOWN
        reference.cardDefinitionId shouldBe cardDefinition

        val projectedHistory = history(result)
        val historyReference = projectedHistory.entries.single().references.single()
        historyReference.semanticRole shouldBe PerspectiveHistoryReferenceRoleV1.EVENT_SUBJECT
        historyReference.semanticAlias shouldBe "o0"
        historyReference.cardDefinitionId shouldBe cardDefinition
        projectedHistory.canonicalJson() shouldNotContain "cycled-runtime-id"
        projectedHistory.canonicalJson() shouldNotContain objectStamp.toString()
        projectedHistory.canonicalJson() shouldNotContain "objectIdentityStamp"
        projectedHistory.canonicalJson() shouldNotContain "rawEventOrdinal"
        projectedHistory.canonicalJson() shouldNotContain "committedActionIndex"
    }

    test("CardCycledEvent history is deterministic and ignores hidden runtime identity") {
        val first = project(
            cardId = EntityId.of("hidden-runtime-a"),
            eventCardName = "Hidden event A",
            beforeCardName = "Hidden state A",
        )
        val repeated = project(
            cardId = EntityId.of("hidden-runtime-a"),
            eventCardName = "Hidden event A",
            beforeCardName = "Hidden state A",
        )
        val alternateHiddenObject = project(
            cardId = EntityId.of("hidden-runtime-b"),
            eventCardName = "Hidden event B",
            beforeCardName = "Hidden state B",
        )

        history(first).canonicalJson() shouldBe history(repeated).canonicalJson()
        history(first).semanticDigest() shouldBe history(repeated).semanticDigest()
        history(first).canonicalJson() shouldBe history(alternateHiddenObject).canonicalJson()
        first.evidence.eventBatch.canonicalJson() shouldBe
            alternateHiddenObject.evidence.eventBatch.canonicalJson()
    }
})
