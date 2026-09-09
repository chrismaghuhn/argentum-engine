package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AbilityTriggeredSourceAuthorityHistoryCTest : FunSpec({
    val perspective = EntityId.of("p1")
    val opponent = EntityId.of("p2")
    val source = EntityId.of("source")
    val other = EntityId.of("other")

    fun state(
        sourceStamp: Long,
        includeSource: Boolean = true,
        includeOther: Boolean = false,
        otherStamp: Long = 3L,
    ): GameState = GameState(
        entities = buildMap {
            put(perspective, ComponentContainer.EMPTY)
            put(opponent, ComponentContainer.EMPTY)
            if (includeSource) put(source, ComponentContainer.EMPTY)
            if (includeOther) put(other, ComponentContainer.EMPTY)
        },
        turnOrder = listOf(perspective, opponent),
        objectIdentityStamps = buildMap {
            if (includeSource) put(source, sourceStamp)
            if (includeOther) put(other, otherStamp)
        },
    )

    fun event(
        authority: AbilityTriggeredSourceEndpointAuthority? =
            AbilityTriggeredSourceEndpointAuthority.AFTER_OBJECT,
        sourceObjectIncarnationStamp: Long? = null,
    ) = AbilityTriggeredEvent(
        sourceId = source,
        sourceName = "private source name",
        controllerId = perspective,
        description = "private description",
        sourceEndpointAuthority = authority,
        sourceObjectIncarnationStamp = sourceObjectIncarnationStamp,
    )

    fun transition(
        event: AbilityTriggeredEvent,
        before: GameState,
        after: GameState,
    ) = CommittedRulesTransition(
        beforeState = before,
        afterState = after,
        events = listOf(event),
        sourceStepCount = 1,
    )

    fun projection(
        transition: CommittedRulesTransition,
        perspectivePlayerId: EntityId = perspective,
    ) =
        PerspectiveEventProjector(CardRegistry()).project(
            events = transition.events,
            perspectivePlayerId = perspectivePlayerId,
            beforeState = transition.beforeState,
            afterState = transition.afterState,
        )

    fun candidate(
        endpointAuthority: HistoryCReferenceEndpointAuthority,
        beforeWitness: HistoryCObjectWitness? = null,
        afterWitness: HistoryCObjectWitness? = HistoryCObjectWitness(source, 2L),
        role: HistoryCReferenceSlotRole = HistoryCReferenceSlotRole.SOURCE,
        roleOrdinal: Int = 0,
        eventOrdinal: Int = 0,
        referenceKind: HistoryCReferenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
        witnessProvenance: HistoryCReferenceWitnessProvenance =
            HistoryCReferenceWitnessProvenance.TRANSITION_STATE,
    ) = HistoryCReferenceCandidateV1(
        slot = HistoryCReferenceSlot(eventOrdinal, role, roleOrdinal),
        referenceKind = referenceKind,
        beforeWitness = beforeWitness,
        afterWitness = afterWitness,
        orderProof = HistoryCOrderProof(
            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
            rank = 0,
        ),
        semanticDescriptor = buildJsonObject {
            put("type", "object_reference")
            put("visibility", "opaque")
        },
        endpointAuthority = endpointAuthority,
        witnessProvenance = witnessProvenance,
    )

    fun validate(
        transition: CommittedRulesTransition,
        candidate: HistoryCReferenceCandidateV1,
    ): HistoryCReferenceAuthorityResult {
        val source = CommittedPerspectiveEventSource(CardRegistry())
        source.capture(transition)
        return source.lastCommittedReferenceEvidence(
            HistoryCReferenceEnvelopeV1(
                perspectivePlayerId = perspective,
                candidates = listOf(candidate),
            ),
        )
    }

    test("event authority selects the exact BEFORE/AFTER witness through producer and validator") {
        val beforeTransition = transition(
            event(AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT),
            before = state(sourceStamp = 11L),
            after = state(sourceStamp = 11L, includeSource = false),
        )
        val beforeProduced = HistoryCReferenceEnvelopeProducerV1.produce(
            beforeTransition,
            projection(beforeTransition),
        ).shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val beforeCandidate = beforeProduced.envelope.candidates.single()
        beforeCandidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.BEFORE_OBJECT
        beforeCandidate.beforeWitness shouldBe HistoryCObjectWitness(source, 11L)
        beforeCandidate.afterWitness shouldBe null
        validate(beforeTransition, beforeCandidate)
            .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()

        val afterTransition = transition(
            event(AbilityTriggeredSourceEndpointAuthority.AFTER_OBJECT),
            before = state(sourceStamp = 11L, includeSource = false),
            after = state(sourceStamp = 12L),
        )
        val afterProduced = HistoryCReferenceEnvelopeProducerV1.produce(
            afterTransition,
            projection(afterTransition),
        ).shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val afterCandidate = afterProduced.envelope.candidates.single()
        afterCandidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.AFTER_OBJECT
        afterCandidate.beforeWitness shouldBe null
        afterCandidate.afterWitness shouldBe HistoryCObjectWitness(source, 12L)
        validate(afterTransition, afterCandidate)
            .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()
    }

    test("event-owned source witness survives when the resume transition has no source state") {
        val transition = transition(
            event(
                authority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
                sourceObjectIncarnationStamp = 61L,
            ),
            before = state(sourceStamp = 61L, includeSource = false),
            after = state(sourceStamp = 61L, includeSource = false),
        )
        val projected = projection(transition)
        val produced = HistoryCReferenceEnvelopeProducerV1.produce(transition, projected)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val candidate = produced.envelope.candidates.single()
        candidate.beforeWitness shouldBe HistoryCObjectWitness(source, 61L)
        candidate.afterWitness shouldBe null
        candidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.EVENT_OWNED
        validate(transition, candidate)
            .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()

        val automatic = CommittedPerspectiveEventSource(CardRegistry()).also { committedSource ->
            committedSource.capture(transition)
        }.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "event-owned-source-witness",
            perspectivePlayerId = perspective,
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "event-owned-source-witness",
                perspectivePlayerId = perspective,
            ),
        )
        automatic.shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
            .projection.referenceOccurrences.single().identityDisclosure shouldBe
            HistoryCIdentityDisclosure.OPAQUE
    }

    test("event-owned witness rejects a mismatched incarnation") {
        val transition = transition(
            event(
                authority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
                sourceObjectIncarnationStamp = 62L,
            ),
            before = state(sourceStamp = 62L, includeSource = false),
            after = state(sourceStamp = 62L, includeSource = false),
        )

        validate(
            transition,
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                beforeWitness = HistoryCObjectWitness(source, 61L),
                afterWitness = null,
                witnessProvenance = HistoryCReferenceWitnessProvenance.EVENT_OWNED,
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH
    }

    test("event metadata rejects a transition-state witness from another incarnation") {
        val transition = transition(
            event(
                authority = AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT,
                sourceObjectIncarnationStamp = 63L,
            ),
            before = state(sourceStamp = 64L),
            after = state(sourceStamp = 64L),
        )

        validate(
            transition,
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.BEFORE_OBJECT,
                beforeWitness = HistoryCObjectWitness(source, 64L),
                afterWitness = null,
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.RAW_EVENT_REFERENCE_MISMATCH
    }

    test("SAME_INCARNATION accepts equal witnesses and rejects a changed source incarnation") {
        val sameTransition = transition(
            event(AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION),
            before = state(sourceStamp = 21L),
            after = state(sourceStamp = 21L),
        )
        val produced = HistoryCReferenceEnvelopeProducerV1.produce(
            sameTransition,
            projection(sameTransition),
        ).shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val sameCandidate = produced.envelope.candidates.single()
        sameCandidate.endpointAuthority shouldBe HistoryCReferenceEndpointAuthority.SAME_INCARNATION
        sameCandidate.beforeWitness shouldBe HistoryCObjectWitness(source, 21L)
        sameCandidate.afterWitness shouldBe HistoryCObjectWitness(source, 21L)
        validate(sameTransition, sameCandidate)
            .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Accepted>()

        val changedTransition = transition(
            event(AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION),
            before = state(sourceStamp = 21L),
            after = state(sourceStamp = 22L),
        )
        HistoryCReferenceEnvelopeProducerV1.produce(
            changedTransition,
            projection(changedTransition),
        ).shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA

        validate(
            changedTransition,
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.SAME_INCARNATION,
                afterWitness = HistoryCObjectWitness(source, 22L),
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.CROSS_INCARNATION_REFERENCE_UNSUPPORTED
    }

    test("missing event authority is rejected instead of using an implicit AFTER endpoint") {
        val transition = transition(
            event(authority = null),
            before = state(sourceStamp = 31L, includeSource = false),
            after = state(sourceStamp = 31L),
        )

        HistoryCReferenceEnvelopeProducerV1.produce(
            transition,
            projection(transition),
        ).shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA

        validate(
            transition,
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                afterWitness = HistoryCObjectWitness(source, 31L),
            ),
        ).shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }

    test("raw authority rejects wrong object, role, kind, ordinal, and missing witness") {
        val transition = transition(
            event(),
            before = state(sourceStamp = 41L, includeOther = true),
            after = state(sourceStamp = 42L, includeOther = true),
        )
        val cases = listOf(
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                afterWitness = HistoryCObjectWitness(other, 3L),
            ),
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                role = HistoryCReferenceSlotRole.TARGET,
                afterWitness = HistoryCObjectWitness(source, 42L),
            ),
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                referenceKind = HistoryCReferenceKind.STACK_OBJECT,
                afterWitness = HistoryCObjectWitness(source, 42L),
            ),
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                eventOrdinal = 1,
                afterWitness = HistoryCObjectWitness(source, 42L),
            ),
            candidate(
                endpointAuthority = HistoryCReferenceEndpointAuthority.AFTER_OBJECT,
                afterWitness = null,
            ),
        )

        cases.forEach { malformed ->
            validate(transition, malformed)
                .shouldBeInstanceOf<HistoryCReferenceAuthorityResult.Rejected>()
        }
    }

    test("A remains perspective-safe while event metadata stays outside the semantic payload") {
        val transition = transition(
            event(),
            before = state(sourceStamp = 51L),
            after = state(sourceStamp = 51L),
        )
        val projected = projection(transition)
        val opponentProjection = projection(transition, opponent)

        projected.classifications.single().disposition shouldBe PerspectiveEventDisposition.EMITTED
        projected.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ABILITY_TRIGGERED
        projected.batch.entries.single().semanticPayload.toString() shouldBe
            "{\"type\":\"ability_triggered\",\"controllerRole\":\"SELF\",\"causedByAttack\":false}"
        opponentProjection.batch.entries.single().semanticPayload.toString() shouldBe
            "{\"type\":\"ability_triggered\",\"controllerRole\":\"OTHER\",\"causedByAttack\":false}"
    }
})
