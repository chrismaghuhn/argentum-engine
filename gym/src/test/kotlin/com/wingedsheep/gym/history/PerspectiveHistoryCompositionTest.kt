package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.StatsModifiedEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventV1
import com.wingedsheep.gym.contract.PerspectiveHistoryIdentityDisclosureV1
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.put
import io.kotest.matchers.types.shouldBeInstanceOf

class PerspectiveHistoryCompositionTest : FunSpec({

    fun registry(playerId: com.wingedsheep.sdk.model.EntityId) =
        PerspectiveAliasRegistryV1(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = playerId,
        )

    test("HISTD-03/HISTD-09 real multi-look composes only for the authorized perspective") {
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
        driver.replaceState(
            driver.state.copy(
                zones = driver.state.zones + (ZoneKey(owner, com.wingedsheep.sdk.core.Zone.HAND) to
                    listOf(lookedCardA, lookedCardB)),
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
                        events = result.events.filterIsInstance<HandLookedAtEvent>(),
                        sourceStepCount = 1,
                    )
                }
            }
        }
        val transition = checkNotNull(lookTransition)
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(driver.cardRegistry)
        source.capture(transition)
        val lifecycle = PerspectiveHistoryStateV1.start(
            semanticEpisodeId = "episode-history",
            playerIds = players,
        )

        val viewerProjection = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = viewer,
            registry = registry(viewer),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val viewerHistory = PerspectiveHistoryComposerV1.append(
            state = lifecycle,
            eventBatch = viewerProjection.evidence.eventBatch,
            evidence = viewerProjection.evidence,
            projection = viewerProjection.projection,
        )
        val viewerEntry = viewerHistory.histories.getValue(viewer).entries.single()
        viewerEntry.references shouldHaveSize 2
        viewerEntry.references.map { it.cardDefinitionId } shouldBe listOf("Forest", "Island")
        viewerEntry.references.map { it.semanticAlias } shouldBe listOf("o0", "o1")

        val reversedSource = com.wingedsheep.gym.CommittedPerspectiveEventSource(driver.cardRegistry)
        val reversedTransition = transition.copy(
            events = transition.events.map { event ->
                (event as HandLookedAtEvent).copy(cardIds = event.cardIds.reversed())
            },
        )
        reversedSource.capture(reversedTransition)
        val reversedProjection = reversedSource.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = viewer,
            registry = registry(viewer),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val reversedHistory = PerspectiveHistoryComposerV1.append(
            state = lifecycle,
            eventBatch = reversedProjection.evidence.eventBatch,
            evidence = reversedProjection.evidence,
            projection = reversedProjection.projection,
        )
        reversedHistory.histories.getValue(viewer).entries.single().references.map {
            it.cardDefinitionId to it.semanticAlias
        } shouldBe viewerEntry.references.map { it.cardDefinitionId to it.semanticAlias }

        val unrelatedProjection = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = unrelated,
            registry = registry(unrelated),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val unrelatedHistory = PerspectiveHistoryComposerV1.append(
            state = lifecycle,
            eventBatch = unrelatedProjection.evidence.eventBatch,
            evidence = unrelatedProjection.evidence,
            projection = unrelatedProjection.projection,
        )
        unrelatedHistory.histories.getValue(unrelated).entries.shouldBeEmpty()

        transition.afterState.getEntity(lookedCardA)
            ?.get<RevealedToComponent>()?.isRevealedTo(viewer) shouldBe true
        transition.afterState.getEntity(lookedCardA)
            ?.get<RevealedToComponent>()?.isRevealedTo(unrelated) shouldBe false
    }

    test("HISTD-05 incomplete History-A projection fails closed before composition") {
        val perspective = com.wingedsheep.sdk.model.EntityId("p1")
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(CardRegistry())
        source.capture(
            CommittedRulesTransition(
                beforeState = GameState(),
                afterState = GameState(),
                events = listOf(
                    StatsModifiedEvent(
                        targetId = com.wingedsheep.sdk.model.EntityId("card"),
                        targetName = "hidden",
                        powerChange = 1,
                        toughnessChange = 1,
                        sourceName = "effect",
                    ),
                ),
                sourceStepCount = 1,
            ),
        )

        val result = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = perspective,
            registry = registry(perspective),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Rejected>()
        result.failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
    }

    test("HISTD-07/HISTD-08 identity upgrades do not rewrite prefixes or link incarnations") {
        val perspective = com.wingedsheep.sdk.model.EntityId("p1")
        val firstWitness = HistoryCObjectWitness(com.wingedsheep.sdk.model.EntityId("card"), 1L)
        val secondWitness = HistoryCObjectWitness(com.wingedsheep.sdk.model.EntityId("card"), 2L)
        val state = PerspectiveHistoryStateV1.start("episode-history", listOf(perspective))
        fun evidence(witness: HistoryCObjectWitness, known: Boolean): HistoryCReferenceEvidenceV1 =
            HistoryCReferenceEvidenceV1(
                perspectivePlayerId = perspective,
                eventBatch = PerspectiveEventBatchV1(
                    perspectivePlayerId = perspective,
                    entries = listOf(
                        PerspectiveEventV1(
                            perspectiveEventOrdinal = 0,
                            eventFamily = PerspectiveEventFamily.ZONE_CHANGED,
                            semanticPayload = kotlinx.serialization.json.buildJsonObject {
                                put("type", "zone_changed")
                                put("ownerRole", "SELF")
                                put("fromZone", "HAND")
                                put("toZone", "BATTLEFIELD")
                            },
                        ),
                    ),
                ),
                candidates = listOf(
                    HistoryCReferenceCandidateV1(
                        slot = HistoryCReferenceSlot(
                            eventOrdinal = 0,
                            role = HistoryCReferenceSlotRole.MOVED_OBJECT,
                        ),
                        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
                        afterWitness = witness,
                        identityDisclosure = if (known) {
                            HistoryCIdentityDisclosure.DEFINITION_KNOWN
                        } else {
                            HistoryCIdentityDisclosure.OPAQUE
                        },
                        cardDefinitionId = "Forest".takeIf { known },
                        orderProof = HistoryCOrderProof(
                            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                            rank = 0,
                        ),
                        semanticDescriptor = kotlinx.serialization.json.buildJsonObject {
                            put("type", "object_reference")
                        },
                    ),
                ),
            )
        fun projection(
            alias: String,
            disclosure: HistoryCIdentityDisclosure,
            definition: String?,
        ) = PerspectiveReferenceProjectionV1(
            nextRegistry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "episode-history",
                perspectivePlayerId = perspective,
            ),
            referenceOccurrences = listOf(
                PerspectiveAliasAssignment(
                    candidateIndex = 0,
                    alias = PerspectiveSemanticAlias(alias.removePrefix("o").toLong()),
                    identityDisclosure = disclosure,
                    cardDefinitionId = definition,
                ),
            ),
            incarnationRelations = emptyList(),
        )

        val first = PerspectiveHistoryComposerV1.append(
            state = state,
            eventBatch = evidence(firstWitness, known = false).eventBatch,
            evidence = evidence(firstWitness, known = false),
            projection = projection("o0", HistoryCIdentityDisclosure.OPAQUE, null),
        )
        val second = PerspectiveHistoryComposerV1.append(
            state = first,
            eventBatch = evidence(firstWitness, known = true).eventBatch,
            evidence = evidence(firstWitness, known = true),
            projection = projection("o0", HistoryCIdentityDisclosure.DEFINITION_KNOWN, "Forest"),
        )
        val third = PerspectiveHistoryComposerV1.append(
            state = second,
            eventBatch = evidence(secondWitness, known = true).eventBatch,
            evidence = evidence(secondWitness, known = true),
            projection = projection("o1", HistoryCIdentityDisclosure.DEFINITION_KNOWN, "Forest"),
        )

        third.histories.getValue(perspective).entries.map { it.references.single().semanticAlias } shouldBe
            listOf("o0", "o0", "o1")
        third.histories.getValue(perspective).entries.first().references.single()
            .identityDisclosure shouldBe PerspectiveHistoryIdentityDisclosureV1.OPAQUE
        third.histories.getValue(perspective).entries[1].references.single()
            .identityDisclosure shouldBe PerspectiveHistoryIdentityDisclosureV1.DEFINITION_KNOWN
    }
})
