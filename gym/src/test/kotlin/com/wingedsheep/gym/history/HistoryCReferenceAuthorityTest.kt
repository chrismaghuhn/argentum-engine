package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.LookedAtCardsEvent
import com.wingedsheep.engine.core.TurnedFaceDownEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HistoryCReferenceAuthorityTest : FunSpec({

    val perspective = EntityId.of("p1")
    val card = EntityId.of("card-1")
    val otherCard = EntityId.of("card-2")

    fun registry(): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun state(
        stamp: Long,
        includeOtherCard: Boolean = false,
        faceDown: Boolean = false,
    ): GameState = GameState(
        entities = buildMap {
            put(perspective, ComponentContainer.EMPTY)
            put(
                card,
                ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "mtn",
                        name = "Mountain",
                        manaCost = ManaCost.ZERO,
                        typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                        ownerId = perspective,
                    ),
                ).let { container ->
                    if (faceDown) container.with(FaceDownComponent) else container
                },
            )
            if (includeOtherCard) {
                put(
                    otherCard,
                    ComponentContainer.of(
                        CardComponent(
                            cardDefinitionId = "other",
                            name = "Other",
                            manaCost = ManaCost.ZERO,
                            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                            ownerId = perspective,
                        ),
                    ),
                )
            }
        },
        zones = mapOf(ZoneKey(perspective, Zone.BATTLEFIELD) to listOf(card)),
        turnOrder = listOf(perspective),
        objectIdentityStamps = buildMap {
            put(card, stamp)
            if (includeOtherCard) put(otherCard, 3L)
        },
    )

    fun candidate(
        eventOrdinal: Int = 0,
        roleOrdinal: Int = 0,
        role: HistoryCReferenceSlotRole = HistoryCReferenceSlotRole.EVENT_SUBJECT,
        referenceKind: HistoryCReferenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        orderAuthority: HistoryCOrderAuthority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
        beforeWitness: HistoryCObjectWitness? = null,
        afterWitness: HistoryCObjectWitness? = HistoryCObjectWitness(card, 2L),
        identityDisclosure: HistoryCIdentityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
        cardDefinitionId: String? = null,
        semanticDescriptor: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("type", "object_reference")
            put("visibility", "opaque")
        },
    ) = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(
            eventOrdinal = eventOrdinal,
            role = role,
            roleOrdinal = roleOrdinal,
        ),
        referenceKind = referenceKind,
        beforeWitness = beforeWitness,
        afterWitness = afterWitness,
        identityDisclosure = identityDisclosure,
        cardDefinitionId = cardDefinitionId,
        orderProof = HistoryCOrderProof(
            authority = orderAuthority,
            rank = roleOrdinal,
        ),
        semanticDescriptor = semanticDescriptor,
    )

    fun envelope(
        version: Int = HISTORY_C_REFERENCE_EVIDENCE_V1_VERSION,
        candidates: List<HistoryCReferenceCandidateV1> = listOf(candidate()),
    ) = HistoryCReferenceEnvelopeV1(
        version = version,
        schemaIdentity = HISTORY_C_REFERENCE_EVIDENCE_V1_SCHEMA_IDENTITY,
        perspectivePlayerId = perspective,
        candidates = candidates,
    )

    fun resultFor(
        events: List<GameEvent>,
        envelope: HistoryCReferenceEnvelopeV1,
        before: GameState = state(1L),
        after: GameState = state(2L),
    ): HistoryCReferenceAuthorityResult {
        val source = CommittedPerspectiveEventSource(registry())
        source.capture(
            CommittedRulesTransition(
                beforeState = before,
                afterState = after,
                events = events,
                sourceStepCount = 1,
            ),
        )
        return source.lastCommittedReferenceEvidence(envelope)
    }

    test("HISTC-01 raw runtime identity is rejected at the semantic seam") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        semanticDescriptor = buildJsonObject {
                            put("type", "object_reference")
                            put("entityId", "runtime-card-id")
                        },
                    ),
                ),
            ),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_RUNTIME_ID_AT_SEMANTIC_SEAM
    }

    test("HISTC-02 repeated references retain the same internal incarnation witness without allocation") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event, event),
            envelope = envelope(
                candidates = listOf(
                    candidate(eventOrdinal = 0, roleOrdinal = 0),
                    candidate(eventOrdinal = 1, roleOrdinal = 0),
                ),
            ),
        )

        val accepted = result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        accepted.evidence.candidates shouldHaveSize 2
        accepted.evidence.candidates.map { it.afterWitness } shouldBe
            listOf(HistoryCObjectWitness(card, 2L), HistoryCObjectWitness(card, 2L))
    }

    test("HISTC-06 public reveal candidate is accepted with definition knowledge") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "mtn",
                        semanticDescriptor = buildJsonObject {
                            put("type", "object_reference")
                            put("visibility", "public")
                        },
                    ),
                ),
            ),
        )

        val accepted = result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        accepted.evidence.candidates.single().identityDisclosure shouldBe
            HistoryCIdentityDisclosure.DEFINITION_KNOWN
        accepted.evidence.candidates.single().cardDefinitionId shouldBe "mtn"
    }

    test("HISTC-C-05 single-object private look binds the exact viewed witness") {
        val event = LookedAtCardsEvent(
            playerId = perspective,
            cardIds = listOf(card),
            source = "private look",
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "mtn",
                    ),
                ),
            ),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
            .evidence.candidates.single().afterWitness shouldBe HistoryCObjectWitness(card, 2L)
    }

    test("HISTC-C-05 multi-object private look binds each exact witness") {
        val event = LookedAtCardsEvent(
            playerId = perspective,
            cardIds = listOf(card, otherCard),
            source = "private look",
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        roleOrdinal = 0,
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "mtn",
                    ),
                    candidate(
                        roleOrdinal = 1,
                        afterWitness = HistoryCObjectWitness(otherCard, 3L),
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "other",
                    ),
                ),
            ),
            after = state(2L, includeOtherCard = true),
        )

        val accepted = result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        accepted.evidence.candidates shouldHaveSize 2
        accepted.evidence.candidates.map { it.slot.roleOrdinal } shouldBe listOf(0, 1)
    }

    test("HISTC-07 face-down object is accepted as opaque without printed identity") {
        val event = TurnedFaceDownEvent(entityId = card, controllerId = perspective)
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        beforeWitness = HistoryCObjectWitness(card, 2L),
                        afterWitness = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
            before = state(2L),
            after = state(2L),
        )

        val accepted = result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
        accepted.evidence.candidates.single().identityDisclosure shouldBe
            HistoryCIdentityDisclosure.OPAQUE
        accepted.evidence.candidates.single().cardDefinitionId shouldBe null
    }

    test("HISTC-24 unknown reference schema version fails closed") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(version = HISTORY_C_REFERENCE_EVIDENCE_V1_VERSION + 1),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.UNKNOWN_REFERENCE_SCHEMA_VERSION
    }

    test("HISTC-A-REVIEW-01 public reveal rejects a witness from another raw event object") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(afterWitness = HistoryCObjectWitness(otherCard, 3L)),
                ),
            ),
            after = state(2L, includeOtherCard = true),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH
    }

    test("HISTC-A-REVIEW-02 public reveal rejects forged definition identity") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "forged",
                    ),
                ),
            ),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_DEFINITION_MISMATCH
    }

    test("HISTC-A-REVIEW-03 public reveal rejects incompatible kind and slot role") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        role = HistoryCReferenceSlotRole.TARGET,
                        referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                    ),
                ),
            ),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH
    }

    test("HISTC-A-REVIEW-04 visible zone change does not authorize hidden printed identity") {
        val event = com.wingedsheep.engine.core.ZoneChangeEvent(
            entityId = card,
            entityName = "Mountain",
            fromZone = Zone.BATTLEFIELD,
            toZone = Zone.GRAVEYARD,
            ownerId = perspective,
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(
                        role = HistoryCReferenceSlotRole.MOVED_OBJECT,
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "mtn",
                    ),
                ),
            ),
            before = state(2L, faceDown = true),
            after = state(2L, faceDown = true),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH
    }

    test("HISTC-A-REVIEW-05 reveal rejects a non-producer order authority") {
        val event = CardsRevealedEvent(
            revealingPlayerId = perspective,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val result = resultFor(
            events = listOf(event),
            envelope = envelope(
                candidates = listOf(
                    candidate(orderAuthority = HistoryCOrderAuthority.EXPLICIT_PLAYER_ORDER),
                ),
            ),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_ORDER_AUTHORITY_MISMATCH
    }

    test("HISTC-A missing committed transition fails closed with a typed diagnostic") {
        val source = CommittedPerspectiveEventSource(registry())

        val result = source.lastCommittedReferenceEvidence(
            envelope = envelope(),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.UNCOMMITTED_TRANSITION
    }

    test("HISTC-A speculative source fails closed with a typed diagnostic") {
        val source = CommittedPerspectiveEventSource(registry(), captureEnabled = false)

        val result = source.lastCommittedReferenceEvidence(
            envelope = envelope(),
        )

        result.shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.FORK_OR_SPECULATIVE_SOURCE
    }
})
