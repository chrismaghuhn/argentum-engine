package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.CommitCrimeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.Component
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class CommitCrimeEventHistoryClosureTest : FunSpec({
    val self = EntityId.of("p1")
    val controller = EntityId.of("p2")
    val sourceId = EntityId.of("crime-source-runtime-id")
    val cardDefinition = "Mountain"
    val sourceName = "Private crime source name"

    fun cardComponent() = CardComponent(
        cardDefinitionId = cardDefinition,
        name = cardDefinition,
        manaCost = ManaCost.ZERO,
        typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
        ownerId = controller,
    )

    fun triggeredMarker() = TriggeredAbilityOnStackComponent(
        sourceId = controller,
        sourceName = sourceName,
        controllerId = controller,
        effect = Effects.DrawCards(1),
        description = sourceName,
    )

    fun activatedMarker() = ActivatedAbilityOnStackComponent(
        sourceId = controller,
        sourceName = sourceName,
        controllerId = controller,
        effect = Effects.DrawCards(1),
    )

    fun state(components: List<Component>): GameState = GameState(
        entities = mapOf(
            self to ComponentContainer.EMPTY,
            controller to ComponentContainer.EMPTY,
            sourceId to ComponentContainer.of(*components.toTypedArray()),
        ),
        stack = listOf(sourceId),
        turnOrder = listOf(self, controller),
        objectIdentityStamps = mapOf(sourceId to 1L),
    )

    fun transition(components: List<Component>) = CommittedRulesTransition(
        beforeState = state(components),
        afterState = state(components),
        events = listOf(
            CommitCrimeEvent(
                playerId = controller,
                sourceEntityId = sourceId,
                sourceName = sourceName,
            ),
        ),
        sourceStepCount = 128,
    )

    fun aProjection(
        components: List<Component>,
        perspectivePlayerId: EntityId,
    ) = PerspectiveEventProjector(CardRegistry()).project(
        events = transition(components).events,
        perspectivePlayerId = perspectivePlayerId,
        beforeState = state(components),
        afterState = state(components),
    )

    fun automaticProjection(
        components: List<Component>,
        perspectivePlayerId: EntityId,
    ): AutomaticHistoryCReferenceProjectionResult =
        CommittedPerspectiveEventSource(CardRegistry()).also { source ->
            source.capture(transition(components))
        }.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "commit-crime-history-closure",
            perspectivePlayerId = perspectivePlayerId,
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "commit-crime-history-closure",
                perspectivePlayerId = perspectivePlayerId,
            ),
        )

    fun history(
        result: AutomaticHistoryCReferenceProjectionResult.Accepted,
        perspectivePlayerId: EntityId,
    ) = PerspectiveHistoryComposerV1.append(
        state = PerspectiveHistoryStateV1.start(
            semanticEpisodeId = "commit-crime-history-closure",
            playerIds = listOf(self, controller),
        ),
        eventBatch = result.evidence.eventBatch,
        evidence = result.evidence,
        projection = result.projection,
    ).histories.getValue(perspectivePlayerId)

    test("CommitCrimeEvent binds spell and ability sources through opaque semantic references") {
        val sources = listOf(
            listOf(cardComponent(), SpellOnStackComponent(casterId = controller)) to
                HistoryCIdentityDisclosure.DEFINITION_KNOWN,
            listOf<Component>(triggeredMarker()) to HistoryCIdentityDisclosure.OPAQUE,
            listOf<Component>(activatedMarker()) to HistoryCIdentityDisclosure.OPAQUE,
        )

        listOf(self, controller).forEach { perspectivePlayerId ->
            sources.forEach { (components, expectedDisclosure) ->
                val result = automaticProjection(components, perspectivePlayerId)
                    .shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
                val candidate = result.evidence.candidates.single()
                val occurrence = result.projection.referenceOccurrences.single()

                result.evidence.eventBatch.entries.single().eventFamily.name shouldBe "COMMIT_CRIME"
                candidate.slot shouldBe HistoryCReferenceSlot(
                    eventOrdinal = 0,
                    role = HistoryCReferenceSlotRole.SOURCE,
                    roleOrdinal = 0,
                )
                candidate.referenceKind shouldBe HistoryCReferenceKind.STACK_OBJECT
                candidate.beforeWitness shouldBe null
                candidate.afterWitness shouldBe HistoryCObjectWitness(sourceId, 1L)
                candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
                candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE
                candidate.orderProof shouldBe HistoryCOrderProof(
                    authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                    rank = 0,
                )
                occurrence.identityDisclosure shouldBe expectedDisclosure
                occurrence.cardDefinitionId shouldBe
                    if (expectedDisclosure == HistoryCIdentityDisclosure.DEFINITION_KNOWN) {
                        cardDefinition
                    } else {
                        null
                    }

                result.evidence.eventBatch.canonicalJson() shouldNotContain sourceId.value
                result.evidence.eventBatch.canonicalJson() shouldNotContain sourceName
                history(result, perspectivePlayerId).canonicalJson() shouldNotContain sourceId.value
                history(result, perspectivePlayerId).canonicalJson() shouldNotContain sourceName
                history(result, perspectivePlayerId).canonicalJson() shouldNotContain "objectIdentityStamp"
                val repeated = automaticProjection(components, perspectivePlayerId)
                    .shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
                history(result, perspectivePlayerId).canonicalJson() shouldBe
                    history(repeated, perspectivePlayerId).canonicalJson()
            }
        }
    }

    test("CommitCrimeEvent raw authority rejects malformed candidates") {
        val components = listOf<Component>(cardComponent(), SpellOnStackComponent(casterId = controller))
        val perspectivePlayerId = self
        val committedTransition = transition(components)
        val projection = aProjection(components, perspectivePlayerId)
        val accepted = automaticProjection(components, perspectivePlayerId)
            .shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val candidate = accepted.evidence.candidates.single()

        fun validate(candidate: HistoryCReferenceCandidateV1) = HistoryCReferenceAuthority.validate(
            transition = committedTransition,
            projection = projection,
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = perspectivePlayerId,
                candidates = listOf(candidate),
            ),
        )

        validate(
            candidate.copy(
                afterWitness = HistoryCObjectWitness(EntityId.of("wrong-source-runtime-id"), 1L),
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH

        validate(
            candidate.copy(
                slot = candidate.slot.copy(role = HistoryCReferenceSlotRole.EVENT_SUBJECT),
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH

        validate(
            candidate.copy(referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH

        validate(
            candidate.copy(slot = candidate.slot.copy(eventOrdinal = 1)),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.INVALID_REFERENCE_SLOT

        validate(
            candidate.copy(afterWitness = null),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.MISSING_EVENT_TIME_WITNESS
    }

    test("CommitCrimeEvent malformed stack sources remain fail-closed") {
        val malformedSources = listOf(
            emptyList<Component>(),
            listOf<Component>(SpellOnStackComponent(casterId = controller)),
            listOf<Component>(triggeredMarker(), activatedMarker()),
        )

        listOf(self, controller).forEach { perspectivePlayerId ->
            malformedSources.forEach { components ->
                automaticProjection(components, perspectivePlayerId)
                    .shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Rejected>()
                    .failure.code shouldBe HistoryCFailureCode.IDENTITY_AUTHORITY_MISMATCH
            }
        }
    }
})
