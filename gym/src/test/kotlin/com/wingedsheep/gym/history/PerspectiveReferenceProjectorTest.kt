package com.wingedsheep.gym.history

/**
 * Rules provenance verified during HISTC-C implementation on 2026-09-07:
 * https://magic.wizards.com/en/rules links to
 * https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt.
 * The linked TXT states that the rules are effective as of August 7, 2026. The older
 * `MagicCompRules 20260807.txt` URL is not currently published (HTTP 404).
 */

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.player.KnownInformationAudience
import com.wingedsheep.engine.state.components.player.KnownInformationAcquisitionReason
import com.wingedsheep.engine.state.components.player.KnownInformationFactKind
import com.wingedsheep.engine.state.components.player.KnownInformationFactV1
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerComponentV1
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PerspectiveReferenceProjectorTest : FunSpec({

    val p1 = EntityId.of("p1")
    val p2 = EntityId.of("p2")
    val p3 = EntityId.of("p3")
    val card = EntityId.of("card")
    val otherCard = EntityId.of("other-card")

    fun registry(perspective: EntityId = p1) = PerspectiveAliasRegistryV1(
        semanticEpisodeId = "episode-0",
        perspectivePlayerId = perspective,
    )

    fun cardContainer(
        owner: EntityId,
        definition: String = "mtn",
        faceDown: Boolean = false,
        revealedTo: Set<EntityId> = emptySet(),
    ): ComponentContainer = ComponentContainer.of(
        CardComponent(
            cardDefinitionId = definition,
            name = if (definition == "mtn") "Mountain" else "Forest",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
            ownerId = owner,
        ),
    ).let { container ->
        var result = container
        if (faceDown) result = result.with(FaceDownComponent)
        if (revealedTo.isNotEmpty()) result = result.with(RevealedToComponent(revealedTo))
        result
    }

    fun fact(
        subject: EntityId = card,
        stamp: Long = 1L,
        kind: KnownInformationFactKind,
        definition: String? = null,
        knownZone: Zone? = null,
    ) = KnownInformationFactV1(
        subjectEntityId = subject,
        objectIdentityStamp = stamp,
        factKind = kind,
        cardDefinitionId = definition,
        knownZone = knownZone,
        knownPosition = null,
        audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
        acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_CARD_LOOK,
        acquiredAtEpoch = 1L,
    )

    fun ledger(facts: List<KnownInformationFactV1>) = KnownInformationLedgerComponentV1(
        knowledgeEpoch = if (facts.isEmpty()) 0L else 1L,
        activeFacts = facts.sortedWith(
            compareBy(
                { it.subjectEntityId.value },
                { it.objectIdentityStamp },
                { it.factKind.ordinal },
                { it.knownZone?.ordinal ?: -1 },
                { it.knownPosition ?: -1 },
                { it.cardDefinitionId ?: "" },
                { it.audience.ordinal },
                { it.acquisitionReason.ordinal },
                { it.acquiredAtEpoch },
            ),
        ),
    )

    fun state(
        zone: Zone = Zone.BATTLEFIELD,
        owner: EntityId = p2,
        stamp: Long = 1L,
        definition: String = "mtn",
        faceDown: Boolean = false,
        revealedTo: Set<EntityId> = emptySet(),
        ledgerFacts: Map<EntityId, List<KnownInformationFactV1>> = emptyMap(),
        players: List<EntityId> = listOf(p1, p2),
        entityId: EntityId = card,
    ): GameState {
        val entities = buildMap {
            players.forEach { player ->
                val facts = ledgerFacts[player].orEmpty()
                put(
                    player,
                    if (facts.isEmpty()) ComponentContainer.EMPTY else ComponentContainer.of(ledger(facts)),
                )
            }
            put(entityId, cardContainer(owner, definition, faceDown, revealedTo))
        }
        val zones = if (zone == Zone.STACK) {
            emptyMap()
        } else {
            mapOf(ZoneKey(owner, zone) to listOf(entityId))
        }
        return GameState(
            entities = entities,
            zones = zones,
            stack = if (zone == Zone.STACK) listOf(entityId) else emptyList(),
            turnOrder = players,
            objectIdentityStamps = mapOf(entityId to stamp),
        )
    }

    fun twoCardState(cardStamp: Long, otherStamp: Long): GameState {
        val base = state(stamp = cardStamp)
        return base.copy(
            entities = base.entities + (otherCard to cardContainer(p2, definition = "forest")),
            zones = mapOf(ZoneKey(p2, Zone.BATTLEFIELD) to listOf(card, otherCard)),
            objectIdentityStamps = mapOf(card to cardStamp, otherCard to otherStamp),
        )
    }

    fun candidate(
        before: HistoryCObjectWitness? = null,
        after: HistoryCObjectWitness? = HistoryCObjectWitness(card, 1L),
        disclosure: HistoryCIdentityDisclosure = HistoryCIdentityDisclosure.OPAQUE,
        definition: String? = null,
        role: HistoryCReferenceSlotRole = HistoryCReferenceSlotRole.MOVED_OBJECT,
        eventOrdinal: Int = 0,
    ) = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(
            eventOrdinal = eventOrdinal,
            role = role,
            roleOrdinal = 0,
        ),
        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        beforeWitness = before,
        afterWitness = after,
        identityDisclosure = disclosure,
        cardDefinitionId = definition,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = 0,
        ),
        semanticDescriptor = buildJsonObject {
            put("type", "object_reference")
            put("visibility", "opaque")
        },
    )

    fun evidence(
        perspective: EntityId = p1,
        candidates: List<HistoryCReferenceCandidateV1>,
    ) = HistoryCReferenceEvidenceV1(
        perspectivePlayerId = perspective,
        eventBatch = PerspectiveEventBatchV1(perspectivePlayerId = perspective, entries = emptyList()),
        candidates = candidates,
    )

    fun project(
        before: GameState,
        after: GameState,
        candidates: List<HistoryCReferenceCandidateV1>,
        registry: PerspectiveAliasRegistryV1 = registry(),
        perspective: EntityId = p1,
        events: List<GameEvent> = emptyList(),
    ): PerspectiveReferenceProjectionResult = PerspectiveReferenceProjectorV1(
        CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        },
    ).project(
        semanticEpisodeId = registry.semanticEpisodeId,
        perspectivePlayerId = perspective,
        transition = CommittedRulesTransition(
            beforeState = before,
            afterState = after,
            events = events,
            sourceStepCount = 1,
        ),
        evidence = evidence(perspective, candidates),
        registry = registry,
    )

    fun accepted(result: PerspectiveReferenceProjectionResult) =
        result.shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Accepted>().projection

    fun rejected(result: PerspectiveReferenceProjectionResult) =
        result.shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Rejected>()

    test("HISTC-07 public face-down object receives only an opaque alias") {
        val result = accepted(
            project(
                before = state(faceDown = true),
                after = state(faceDown = true),
                candidates = listOf(candidate()),
            ),
        )

        result.referenceOccurrences.single().alias.canonical() shouldBe "o0"
        result.referenceOccurrences.single().identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
        result.referenceOccurrences.single().cardDefinitionId shouldBe null
    }

    test("HISTC-08 authorized face-down look upgrades the same alias without rewriting the prefix") {
        val first = accepted(
            project(
                before = state(faceDown = true),
                after = state(faceDown = true),
                candidates = listOf(candidate()),
            ),
        )
        val laterState = state(
            faceDown = true,
            revealedTo = setOf(p1),
            ledgerFacts = mapOf(
                p1 to listOf(fact(kind = KnownInformationFactKind.IDENTITY, definition = "mtn")),
            ),
        )
        val second = accepted(
            project(
                before = laterState,
                after = laterState,
                candidates = listOf(candidate()),
                registry = first.nextRegistry,
            ),
        )

        first.referenceOccurrences.single().alias.canonical() shouldBe "o0"
        first.referenceOccurrences.single().identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
        second.referenceOccurrences.single().alias.canonical() shouldBe "o0"
        second.referenceOccurrences.single().identityDisclosure shouldBe
            HistoryCIdentityDisclosure.DEFINITION_KNOWN
        second.referenceOccurrences.single().cardDefinitionId shouldBe "mtn"
    }

    test("HISTC-C-REVIEW-10 hidden full-state definition cannot upgrade opaque") {
        val result = accepted(
            project(
                before = state(faceDown = true),
                after = state(faceDown = true),
                candidates = listOf(candidate()),
            ),
        )

        result.referenceOccurrences.single().cardDefinitionId shouldBe null
        result.referenceOccurrences.single().identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
    }

    test("HISTC-05_PRIVATE_LOOK_PERSPECTIVE_ISOLATION") {
        val after = state(
            zone = Zone.HAND,
            owner = p2,
            faceDown = true,
            revealedTo = setOf(p2),
            ledgerFacts = mapOf(
                p2 to listOf(
                    fact(subject = card, kind = KnownInformationFactKind.IDENTITY, definition = "mtn"),
                    fact(subject = card, kind = KnownInformationFactKind.ZONE_MEMBERSHIP, knownZone = Zone.HAND),
                ),
            ),
        )
        val p1Result = accepted(
            project(
                before = after,
                after = after,
                perspective = p1,
                candidates = emptyList(),
            ),
        )
        val p2Result = accepted(
            project(
                before = after,
                after = after,
                perspective = p2,
                registry = registry(p2),
                candidates = listOf(
                    candidate(
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "mtn",
                    ),
                ),
            ),
        )

        p1Result.referenceOccurrences.shouldBeEmpty()
        p1Result.nextRegistry.nextAliasOrdinal shouldBe 0L
        p2Result.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0")
    }

    test("HISTC-C production adapter consumes only committed accepted A evidence") {
        val event = CardsRevealedEvent(
            revealingPlayerId = p1,
            cardIds = listOf(card),
            cardNames = listOf("Mountain"),
        )
        val before = state()
        val after = state()
        val source = CommittedPerspectiveEventSource(
            CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
            },
        )
        source.capture(
            CommittedRulesTransition(
                beforeState = before,
                afterState = after,
                events = listOf(event),
                sourceStepCount = 1,
            ),
        )
        val result = source.lastCommittedReferenceProjection(
            semanticEpisodeId = "episode-0",
            registry = registry(),
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = p1,
                candidates = listOf(
                    candidate(
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "mtn",
                    ),
                ),
            ),
        )

        val accepted = accepted(result)
        accepted.referenceOccurrences.single().alias.canonical() shouldBe "o0"
        accepted.referenceOccurrences.single().cardDefinitionId shouldBe "mtn"
    }

    test("HISTC-05 real Rules private-look producer preserves only the viewer's knowledge") {
        val driver = GameTestDriver().apply {
            registerCards(PortalSet.cards)
            registerCards(PortalSet.basicLands)
        }
        val deck = Deck.of("Island" to 20, "Forest" to 20)
        val players = driver.initMultiplayer(
            decks = listOf(deck, deck, deck),
            skipMulligans = true,
            startingPlayer = 0,
        )
        val viewer = players[0]
        val owner = players[1]
        val unrelated = players[2]
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val lookedCard = driver.putCardInHand(owner, "Forest")
        val ownerHand = ZoneKey(owner, Zone.HAND)
        driver.replaceState(
            driver.state.copy(
                zones = driver.state.zones + (ownerHand to listOf(lookedCard)),
            ),
        )

        val thief = driver.putCardInHand(viewer, "Ingenious Thief")
        driver.giveMana(viewer, Color.BLUE, 2)
        driver.castSpell(viewer, thief).isSuccess shouldBe true

        var lookTransition: CommittedRulesTransition? = null
        repeat(40) {
            if (lookTransition != null) return@repeat
            if (driver.state.pendingDecision != null) {
                driver.submitTargetSelection(viewer, listOf(owner))
            } else {
                val priority = driver.state.priorityPlayerId ?: return@repeat
                val before = driver.state
                val result = driver.passPriority(priority)
                if (result.events.any { it is HandLookedAtEvent }) {
                    lookTransition = CommittedRulesTransition(
                        beforeState = before,
                        afterState = driver.state,
                        events = result.events,
                        sourceStepCount = 1,
                    )
                }
            }
        }
        val transition = checkNotNull(lookTransition) { "Real look producer did not emit HandLookedAtEvent" }
        val lookEvent = transition.events.filterIsInstance<HandLookedAtEvent>().single()
        lookEvent.viewingPlayerId shouldBe viewer
        lookEvent.targetPlayerId shouldBe owner
        lookEvent.cardIds shouldBe listOf(lookedCard)
        transition.afterState.getEntity(lookedCard)
            ?.get<RevealedToComponent>()
            ?.isRevealedTo(viewer) shouldBe true
        transition.afterState.getEntity(lookedCard)
            ?.get<RevealedToComponent>()
            ?.isRevealedTo(unrelated) shouldBe false

        // The strict Rules step also emits unrelated stack/priority bookkeeping events. Keep the
        // exact producer-emitted look event and its event-time states as the bounded C authority
        // characterization; do not make A silently accept those unrelated families.
        val lookOnlyTransition = transition.copy(events = listOf(lookEvent))
        val perspectiveProjector = com.wingedsheep.gym.contract.PerspectiveEventProjector(driver.cardRegistry)
        val projection = perspectiveProjector.project(
            events = lookOnlyTransition.events,
            perspectivePlayerId = viewer,
            beforeState = lookOnlyTransition.beforeState,
            afterState = lookOnlyTransition.afterState,
        )
        val rawIndex = lookOnlyTransition.events.indexOfFirst { it is HandLookedAtEvent }
        val eventOrdinal = projection.classifications
            .take(rawIndex)
            .count { it.disposition == com.wingedsheep.gym.contract.PerspectiveEventDisposition.EMITTED }
        val stamp = transition.afterState.objectIdentityStamps.getValue(lookedCard)
        val definition = transition.afterState.getEntity(lookedCard)
            ?.get<CardComponent>()
            ?.cardDefinitionId
            ?: error("Looked card lost its definition")
        val source = CommittedPerspectiveEventSource(driver.cardRegistry)
        source.capture(lookOnlyTransition)

        val viewerResult = source.lastCommittedReferenceProjection(
            semanticEpisodeId = "episode-live",
            registry = registry(viewer).copy(semanticEpisodeId = "episode-live"),
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = viewer,
                candidates = listOf(
                    candidate(
                        eventOrdinal = eventOrdinal,
                        after = HistoryCObjectWitness(lookedCard, stamp),
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = definition,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    ),
                ),
            ),
        )
        val viewerProjection = accepted(viewerResult)
        viewerProjection.referenceOccurrences.single().identityDisclosure shouldBe
            HistoryCIdentityDisclosure.DEFINITION_KNOWN

        val unrelatedResult = source.lastCommittedReferenceProjection(
            semanticEpisodeId = "episode-live",
            registry = registry(unrelated).copy(semanticEpisodeId = "episode-live"),
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = unrelated,
                candidates = emptyList(),
            ),
        )
        val unrelatedProjection = accepted(unrelatedResult)
        unrelatedProjection.referenceOccurrences.shouldBeEmpty()
        unrelatedProjection.nextRegistry.nextAliasOrdinal shouldBe 0L
    }

    test("HISTC-05 real Rules producer characterizes the multi-card look shape") {
        val driver = GameTestDriver().apply {
            registerCards(PortalSet.cards)
            registerCards(PortalSet.basicLands)
        }
        val deck = Deck.of("Island" to 20, "Forest" to 20)
        val players = driver.initMultiplayer(
            decks = listOf(deck, deck, deck),
            skipMulligans = true,
            startingPlayer = 0,
        )
        val viewer = players[0]
        val owner = players[1]
        val unrelated = players[2]
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val lookedCardA = driver.putCardInHand(owner, "Forest")
        val lookedCardB = driver.putCardInHand(owner, "Island")
        val ownerHand = ZoneKey(owner, Zone.HAND)
        driver.replaceState(
            driver.state.copy(
                zones = driver.state.zones + (ownerHand to listOf(lookedCardA, lookedCardB)),
            ),
        )

        val thief = driver.putCardInHand(viewer, "Ingenious Thief")
        driver.giveMana(viewer, Color.BLUE, 2)
        driver.castSpell(viewer, thief).isSuccess shouldBe true

        var lookTransition: CommittedRulesTransition? = null
        repeat(40) {
            if (lookTransition != null) return@repeat
            if (driver.state.pendingDecision != null) {
                driver.submitTargetSelection(viewer, listOf(owner))
            } else {
                val priority = driver.state.priorityPlayerId ?: return@repeat
                val before = driver.state
                val result = driver.passPriority(priority)
                if (result.events.any { it is HandLookedAtEvent }) {
                    lookTransition = CommittedRulesTransition(
                        beforeState = before,
                        afterState = driver.state,
                        events = result.events,
                        sourceStepCount = 1,
                    )
                }
            }
        }

        val transition = checkNotNull(lookTransition) { "Real multi-card look did not emit an event" }
        val lookEvents = transition.events.filterIsInstance<HandLookedAtEvent>()
        lookEvents.size shouldBe 1
        lookEvents.single().cardIds shouldBe listOf(lookedCardA, lookedCardB)
        listOf(lookedCardA, lookedCardB).forEach { cardId ->
            transition.afterState.getEntity(cardId)
                ?.get<RevealedToComponent>()
                ?.isRevealedTo(viewer) shouldBe true
            transition.afterState.getEntity(cardId)
                ?.get<RevealedToComponent>()
                ?.isRevealedTo(unrelated) shouldBe false
        }
    }

    test("HISTC-10_VISIBLE_ZONE_CHANGE_RELATIONSHIP_POLICY") {
        val result = accepted(
            project(
                before = state(zone = Zone.BATTLEFIELD, stamp = 1L),
                after = state(zone = Zone.GRAVEYARD, stamp = 2L),
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
        )

        result.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
        result.incarnationRelations.shouldBeEmpty()
    }

    test("HISTC-C-REVIEW-15_MULTI_MOVEMENT_OCCURRENCE_ORDER_PRESERVED") {
        val result = accepted(
            project(
                before = twoCardState(cardStamp = 1L, otherStamp = 3L),
                after = twoCardState(cardStamp = 2L, otherStamp = 4L),
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "mtn",
                    ),
                    candidate(
                        before = HistoryCObjectWitness(otherCard, 3L),
                        after = HistoryCObjectWitness(otherCard, 4L),
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "forest",
                    ),
                ),
            ),
        )

        result.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0", "o1", "o2", "o3")
        result.referenceOccurrences.map { it.candidateIndex } shouldBe listOf(0, 0, 1, 1)
    }

    test("HISTC-11_BLINK_NEW_ALIAS") {
        val first = accepted(
            project(
                before = state(stamp = 1L),
                after = state(stamp = 1L),
                candidates = listOf(candidate(after = HistoryCObjectWitness(card, 1L))),
            ),
        )
        val blink = accepted(
            project(
                before = state(stamp = 1L),
                after = state(zone = Zone.BATTLEFIELD, stamp = 2L),
                registry = first.nextRegistry,
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
        )

        blink.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
        blink.nextRegistry.retiredAliases.map { it.canonical() } shouldContain "o0"
    }

    test("HISTC-12_BOUNCE_RECAST_NEW_ALIASES") {
        val battlefield = state(zone = Zone.BATTLEFIELD, owner = p2, stamp = 1L)
        val hand = state(zone = Zone.HAND, owner = p2, stamp = 2L)
        val recast = state(zone = Zone.BATTLEFIELD, owner = p2, stamp = 3L)
        val first = accepted(
            project(
                before = battlefield,
                after = battlefield,
                perspective = p2,
                registry = registry(p2),
                candidates = listOf(candidate(after = HistoryCObjectWitness(card, 1L))),
            ),
        )
        val bounced = accepted(
            project(
                before = battlefield,
                after = hand,
                perspective = p2,
                registry = first.nextRegistry,
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
        )
        val recastResult = accepted(
            project(
                before = hand,
                after = recast,
                perspective = p2,
                registry = bounced.nextRegistry,
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 2L),
                        after = HistoryCObjectWitness(card, 3L),
                    ),
                ),
            ),
        )

        bounced.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
        recastResult.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o1", "o2")
    }

    test("HISTC-13_SHUFFLE_KNOWLEDGE_INVALIDATION") {
        val initial = accepted(
            project(
                before = state(),
                after = state(),
                candidates = listOf(candidate()),
            ),
        )
        val afterShuffle = accepted(
            project(
                before = state(),
                after = state(),
                registry = initial.nextRegistry,
                events = listOf(LibraryShuffledEvent(p2)),
                candidates = emptyList(),
            ),
        )

        afterShuffle.nextRegistry.activeBindings shouldBe initial.nextRegistry.activeBindings
        afterShuffle.nextRegistry.retiredAliases shouldBe emptySet()
    }

    test("HISTC-14_SAME_NAME_HAND_AMBIGUITY_BREAKS_CONTINUITY") {
        val initial = accepted(
            project(
                before = state(entityId = card),
                after = state(entityId = card),
                candidates = listOf(candidate()),
            ),
        )
        val next = accepted(
            project(
                before = state(entityId = card),
                after = state(entityId = otherCard, definition = "mtn"),
                registry = initial.nextRegistry,
                candidates = listOf(candidate(after = HistoryCObjectWitness(otherCard, 1L))),
            ),
        )

        next.referenceOccurrences.single().alias.canonical() shouldBe "o1"
    }

    test("HISTC-C-REVIEW-01 wrong-stamp identity fact is rejected") {
        val result = rejected(
            project(
                before = state(faceDown = true),
                after = state(
                    faceDown = true,
                    ledgerFacts = mapOf(
                        p1 to listOf(
                            fact(
                                stamp = 2L,
                                kind = KnownInformationFactKind.IDENTITY,
                                definition = "mtn",
                            ),
                        ),
                    ),
                ),
                candidates = listOf(
                    candidate(
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "mtn",
                    ),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH
    }

    test("HISTC-C-REVIEW-02 another perspective ledger cannot authorize identity") {
        val result = accepted(
            project(
                before = state(faceDown = true),
                after = state(
                    faceDown = true,
                    ledgerFacts = mapOf(
                        p2 to listOf(
                            fact(
                                kind = KnownInformationFactKind.IDENTITY,
                                definition = "mtn",
                            ),
                        ),
                    ),
                ),
                candidates = listOf(candidate()),
            ),
        )

        result.referenceOccurrences.single().identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
        result.referenceOccurrences.single().cardDefinitionId shouldBe null
    }

    test("HISTC-C-REVIEW-03 identity fact without hidden membership fails closed") {
        val result = rejected(
            project(
                before = state(zone = Zone.HAND, faceDown = true, revealedTo = setOf(p1)),
                after = state(
                    zone = Zone.HAND,
                    faceDown = true,
                    revealedTo = setOf(p1),
                    ledgerFacts = mapOf(
                        p1 to listOf(
                            fact(kind = KnownInformationFactKind.IDENTITY, definition = "mtn"),
                        ),
                    ),
                ),
                candidates = listOf(
                    candidate(
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "mtn",
                    ),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.MISSING_HISTORY_B_CONTINUITY_EVIDENCE
    }

    test("HISTC-C-REVIEW-04 zone membership does not imply identity") {
        val result = accepted(
            project(
                before = state(zone = Zone.HAND),
                after = state(
                    zone = Zone.HAND,
                    ledgerFacts = mapOf(
                        p1 to listOf(fact(kind = KnownInformationFactKind.ZONE_MEMBERSHIP, knownZone = Zone.HAND)),
                    ),
                ),
                candidates = listOf(candidate()),
            ),
        )

        result.referenceOccurrences.shouldBeEmpty()
        result.nextRegistry.nextAliasOrdinal shouldBe 0L
    }

    test("HISTC-C-REVIEW-05 raw EntityId equality does not create a relation") {
        val before = state(zone = Zone.BATTLEFIELD, stamp = 1L)
        val after = state(zone = Zone.GRAVEYARD, stamp = 2L)
        val result = accepted(
            project(
                before = before,
                after = after,
                events = listOf(ZoneChangeEvent(card, "Mountain", Zone.BATTLEFIELD, Zone.GRAVEYARD, p2)),
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                        role = HistoryCReferenceSlotRole.MOVED_OBJECT,
                    ),
                ),
            ),
        )

        result.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
        result.incarnationRelations.shouldBeEmpty()
    }

    test("HISTC-C-REVIEW-06 missing relation authority returns no link") {
        val result = accepted(
            project(
                before = state(stamp = 1L),
                after = state(stamp = 2L),
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
        )

        result.incarnationRelations.shouldBeEmpty()
    }

    test("HISTC-C-REVIEW-07 identity rejection is atomic") {
        val initial = registry()
        val result = rejected(
            project(
                before = state(faceDown = true),
                after = state(faceDown = true),
                registry = initial,
                candidates = listOf(
                    candidate(
                        disclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        definition = "mtn",
                    ),
                ),
            ),
        )

        result.failure.code shouldBe HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH
        initial.nextAliasOrdinal shouldBe 0L
        initial.activeBindings shouldBe emptyMap()
        initial.retiredAliases shouldBe emptySet()
    }

    test("HISTC-C-REVIEW-08 producer reveal preservation does not add a viewer") {
        val after = state(
            zone = Zone.EXILE,
            owner = p3,
            faceDown = true,
            revealedTo = setOf(p1),
            players = listOf(p1, p2, p3),
        )
        val p1Result = accepted(
            project(
                before = after,
                after = after,
                perspective = p1,
                candidates = listOf(candidate()),
            ),
        )
        val p2Result = accepted(
            project(
                before = after,
                after = after,
                perspective = p2,
                registry = registry(p2),
                candidates = listOf(candidate()),
            ),
        )

        p1Result.referenceOccurrences shouldHaveSize 1
        p2Result.referenceOccurrences.shouldBeEmpty()
    }

    test("HISTC-C-REVIEW-09 reveal preservation does not auto-link incarnations") {
        val result = accepted(
            project(
                before = state(zone = Zone.EXILE, stamp = 1L, faceDown = true, revealedTo = setOf(p1)),
                after = state(zone = Zone.EXILE, stamp = 2L, faceDown = true, revealedTo = setOf(p1)),
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
        )

        result.incarnationRelations.shouldBeEmpty()
    }

    test("HISTC-C-REVIEW-11 unrelated shuffle does not retire an addressable alias") {
        val initial = accepted(
            project(
                before = state(),
                after = state(),
                candidates = listOf(candidate()),
            ),
        )
        val later = accepted(
            project(
                before = state(),
                after = state(),
                registry = initial.nextRegistry,
                events = listOf(LibraryShuffledEvent(p2)),
                candidates = emptyList(),
            ),
        )

        later.nextRegistry.activeBindings shouldBe initial.nextRegistry.activeBindings
        later.nextRegistry.retiredAliases shouldBe emptySet()
    }

    test("HISTC-C-REVIEW-12 same-name continuity never rebinds by name") {
        val initial = accepted(
            project(
                before = state(entityId = card, definition = "mtn"),
                after = state(entityId = card, definition = "mtn"),
                candidates = listOf(candidate()),
            ),
        )
        val replacement = accepted(
            project(
                before = state(entityId = card, definition = "mtn"),
                after = state(entityId = otherCard, definition = "mtn", stamp = 1L),
                registry = initial.nextRegistry,
                candidates = listOf(candidate(after = HistoryCObjectWitness(otherCard, 1L))),
            ),
        )

        replacement.referenceOccurrences.single().alias.canonical() shouldBe "o1"
    }

    test("HISTC-C-REVIEW-13 terminal token retires without a destination alias") {
        val initial = accepted(
            project(
                before = state(),
                after = state(),
                candidates = listOf(candidate()),
            ),
        )
        val terminal = accepted(
            project(
                before = state(),
                after = state(zone = Zone.BATTLEFIELD).copy(
                    entities = state(zone = Zone.BATTLEFIELD).entities - card,
                    zones = emptyMap(),
                    objectIdentityStamps = emptyMap(),
                ),
                registry = initial.nextRegistry,
                candidates = emptyList(),
            ),
        )

        terminal.referenceOccurrences.shouldBeEmpty()
        terminal.nextRegistry.activeBindings shouldBe emptyMap()
        terminal.nextRegistry.retiredAliases.map { it.canonical() } shouldContain "o0"
    }

    test("HISTC-C-REVIEW-14 commander designation does not reuse an alias") {
        val result = accepted(
            project(
                before = state(zone = Zone.BATTLEFIELD, stamp = 1L),
                after = state(zone = Zone.COMMAND, stamp = 2L),
                candidates = listOf(
                    candidate(
                        before = HistoryCObjectWitness(card, 1L),
                        after = HistoryCObjectWitness(card, 2L),
                    ),
                ),
            ),
        )

        result.referenceOccurrences.map { it.alias.canonical() } shouldBe listOf("o0", "o1")
    }

    test("HISTC-C-17 projector determinism is independent of repeated invocation") {
        val first = accepted(
            project(
                before = state(),
                after = state(),
                candidates = listOf(candidate()),
            ),
        )
        val second = accepted(
            project(
                before = state(),
                after = state(),
                candidates = listOf(candidate()),
            ),
        )

        first.referenceOccurrences shouldBe second.referenceOccurrences
        first.nextRegistry shouldBe second.nextRegistry
        first.incarnationRelations shouldBe second.incarnationRelations
    }
})
