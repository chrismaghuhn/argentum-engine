package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.KeywordGrantedEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KeywordGrantedEventHistoryClosureTest : FunSpec({
    val self = EntityId("p1")
    val opponent = EntityId("p2")
    val target = EntityId("keyword-target-runtime-id")
    val wrongTarget = EntityId("wrong-keyword-target-runtime-id")
    val targetName = "Hidden Keyword Target Name"
    val sourceName = "Hidden Keyword Source Name"

    fun targetContainer(definition: String = "Mountain") = ComponentContainer.of(
        CardComponent(
            cardDefinitionId = definition,
            name = definition,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
            ownerId = opponent,
        ),
    )

    fun state() = GameState(
        entities = mapOf(
            self to ComponentContainer.EMPTY,
            opponent to ComponentContainer.EMPTY,
            target to targetContainer(),
            wrongTarget to targetContainer("Forest"),
        ),
        zones = mapOf(
            ZoneKey(opponent, Zone.BATTLEFIELD) to listOf(target, wrongTarget),
        ),
        turnOrder = listOf(self, opponent),
        objectIdentityStamps = mapOf(target to 9L, wrongTarget to 10L),
    )

    fun event() = KeywordGrantedEvent(
        targetId = target,
        targetName = targetName,
        keyword = "flying",
        sourceName = sourceName,
    )

    fun transition() = CommittedRulesTransition(
        beforeState = state(),
        afterState = state(),
        events = listOf(event()),
        sourceStepCount = 457,
    )

    fun aProjection(perspectivePlayerId: EntityId) = PerspectiveEventProjector(CardRegistry()).project(
        events = transition().events,
        perspectivePlayerId = perspectivePlayerId,
        beforeState = state(),
        afterState = state(),
    )

    fun automaticProjection(perspectivePlayerId: EntityId) =
        CommittedPerspectiveEventSource(CardRegistry()).also { source ->
            source.capture(transition())
        }.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "keyword-granted-history-closure",
            perspectivePlayerId = perspectivePlayerId,
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "keyword-granted-history-closure",
                perspectivePlayerId = perspectivePlayerId,
            ),
        )

    test("KeywordGrantedEvent projects only its stable keyword for both perspectives") {
        val projector = PerspectiveEventProjector(CardRegistry())
        val projections = listOf(self, opponent).map { perspectivePlayerId ->
            projector.project(
                events = transition().events,
                perspectivePlayerId = perspectivePlayerId,
                beforeState = state(),
                afterState = state(),
            )
        }

        projections.forEach { projection ->
            projection.isComplete shouldBe true
            projection.classifications.single().rawEventType shouldBe "KeywordGrantedEvent"
            projection.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
            projection.batch.entries.size shouldBe 1
            projection.batch.entries.single().eventFamily.name shouldBe "KEYWORD_GRANTED"
            projection.batch.entries.single().semanticPayload shouldBe buildJsonObject {
                put("type", "keyword_granted")
                put("keyword", "flying")
            }
            projection.batch.canonicalJson() shouldNotContain target.value
            projection.batch.canonicalJson() shouldNotContain targetName
            projection.batch.canonicalJson() shouldNotContain sourceName
        }
        projections.map { it.batch.entries.single().semanticPayload }.distinct().size shouldBe 1
        val repeated = projector.project(
            events = transition().events,
            perspectivePlayerId = self,
            beforeState = state(),
            afterState = state(),
        )
        projections.first().batch.canonicalJson() shouldBe repeated.batch.canonicalJson()
    }

    test("KeywordGrantedEvent binds its target through the after-object witness") {
        val results = listOf(self, opponent).map(::automaticProjection)
        results.forEach { result ->
            val accepted = result.shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
            val candidate = accepted.evidence.candidates.single()
            candidate.slot shouldBe HistoryCReferenceSlot(
                eventOrdinal = 0,
                role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                roleOrdinal = 0,
            )
            candidate.referenceKind shouldBe HistoryCReferenceKind.CARD_OR_RULES_OBJECT
            candidate.beforeWitness shouldBe null
            candidate.afterWitness shouldBe HistoryCObjectWitness(target, 9L)
            candidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
            candidate.identityDisclosure shouldBe HistoryCIdentityDisclosure.OPAQUE

            val occurrence = accepted.projection.referenceOccurrences.single()
            occurrence.identityDisclosure shouldBe HistoryCIdentityDisclosure.DEFINITION_KNOWN
            occurrence.cardDefinitionId shouldBe "Mountain"

            val history = PerspectiveHistoryComposerV1.append(
                state = PerspectiveHistoryStateV1.start(
                    semanticEpisodeId = "keyword-granted-history-closure",
                    playerIds = listOf(self, opponent),
                ),
                eventBatch = accepted.evidence.eventBatch,
                evidence = accepted.evidence,
                projection = accepted.projection,
            ).histories.getValue(accepted.evidence.perspectivePlayerId)
            history.canonicalJson() shouldNotContain target.value
            history.canonicalJson() shouldNotContain targetName
            history.canonicalJson() shouldNotContain sourceName
            history.canonicalJson() shouldNotContain "objectIdentityStamp"
        }
    }

    test("KeywordGrantedEvent raw authority rejects malformed target bindings") {
        val perspectivePlayerId = self
        val committedTransition = transition()
        val projection = aProjection(perspectivePlayerId)
        val accepted = automaticProjection(perspectivePlayerId)
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
            candidate.copy(afterWitness = HistoryCObjectWitness(wrongTarget, 10L)),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH

        validate(
            candidate.copy(slot = candidate.slot.copy(role = HistoryCReferenceSlotRole.TARGET)),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH

        validate(
            candidate.copy(referenceKind = HistoryCReferenceKind.STACK_OBJECT),
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

        val source = CommittedPerspectiveEventSource(CardRegistry()).also { it.capture(committedTransition) }
        source.lastCommittedReferenceProjection(
            semanticEpisodeId = "keyword-granted-history-closure",
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "keyword-granted-history-closure",
                perspectivePlayerId = perspectivePlayerId,
            ),
            envelope = HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = perspectivePlayerId,
                candidates = listOf(
                    candidate.copy(
                        identityDisclosure = HistoryCIdentityDisclosure.DEFINITION_KNOWN,
                        cardDefinitionId = "Forest",
                    ),
                ),
            ),
        ).shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_DEFINITION_MISMATCH
    }
})
