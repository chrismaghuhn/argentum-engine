package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.DeclaredAttack
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AttackersDeclaredEmptyHistoryCClosureTest : FunSpec({
    val attackingPlayer = EntityId("attacking-player")
    val defendingPlayer = EntityId("defending-player")
    val attacker = EntityId("attacker-runtime-id")
    val otherAttacker = EntityId("other-attacker-runtime-id")
    val cardDefinition = "Grizzly Bears"

    fun attackerContainer(owner: EntityId = attackingPlayer) = ComponentContainer.of(
        CardComponent(
            cardDefinitionId = cardDefinition,
            name = cardDefinition,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            ownerId = owner,
        ),
    )

    fun state(
        includeAttacker: Boolean = false,
        includeOtherAttacker: Boolean = false,
    ) = GameState(
        entities = buildMap {
            put(attackingPlayer, ComponentContainer.EMPTY)
            put(defendingPlayer, ComponentContainer.EMPTY)
            if (includeAttacker) put(attacker, attackerContainer())
            if (includeOtherAttacker) put(otherAttacker, attackerContainer())
        },
        zones = buildMap {
            if (includeAttacker || includeOtherAttacker) {
                put(
                    ZoneKey(attackingPlayer, Zone.BATTLEFIELD),
                    buildList {
                        if (includeAttacker) add(attacker)
                        if (includeOtherAttacker) add(otherAttacker)
                    },
                )
            }
        },
        turnOrder = listOf(attackingPlayer, defendingPlayer),
        objectIdentityStamps = buildMap {
            if (includeAttacker) put(attacker, 1L)
            if (includeOtherAttacker) put(otherAttacker, 2L)
        },
    )

    fun event(
        attackers: List<EntityId> = emptyList(),
        declaredAttacks: List<DeclaredAttack> = emptyList(),
    ) = AttackersDeclaredEvent(
        attackers = attackers,
        attackerNames = attackers.map { "redacted" },
        attackingPlayerId = attackingPlayer,
        declaredAttacks = declaredAttacks,
    )

    fun transition(
        before: GameState,
        after: GameState = before,
        event: AttackersDeclaredEvent,
    ) = CommittedRulesTransition(
        beforeState = before,
        afterState = after,
        events = listOf(event),
        sourceStepCount = 466,
    )

    fun projection(
        committedTransition: CommittedRulesTransition,
        perspectivePlayerId: EntityId = attackingPlayer,
    ) = PerspectiveEventProjector(CardRegistry()).project(
        events = committedTransition.events,
        perspectivePlayerId = perspectivePlayerId,
        beforeState = committedTransition.beforeState,
        afterState = committedTransition.afterState,
    )

    fun produce(
        committedTransition: CommittedRulesTransition,
        perspectivePlayerId: EntityId = attackingPlayer,
    ) = HistoryCReferenceEnvelopeProducerV1.produce(
        transition = committedTransition,
        projection = projection(committedTransition, perspectivePlayerId),
    )

    test("an empty AttackersDeclaredEvent is accepted without C evidence") {
        val committedTransition = transition(state(), event = event())
        listOf(attackingPlayer, defendingPlayer).forEach { perspectivePlayerId ->
            val aProjection = projection(committedTransition, perspectivePlayerId)
            aProjection.isComplete shouldBe true
            aProjection.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ATTACKERS_DECLARED

            val accepted = produce(committedTransition, perspectivePlayerId)
                .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
            accepted.envelope.candidates.shouldBeEmpty()
            accepted.envelope.relations.shouldBeEmpty()
        }
    }

    test("a non-empty witness-backed AttackersDeclaredEvent retains its candidate authority") {
        val committedTransition = transition(
            state(includeAttacker = true),
            event = event(attackers = listOf(attacker)),
        )
        val accepted = produce(committedTransition)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Accepted>()
        val candidate = accepted.envelope.candidates.single()
        candidate.slot shouldBe HistoryCReferenceSlot(
            eventOrdinal = 0,
            role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
            roleOrdinal = 0,
        )
        candidate.afterWitness shouldBe HistoryCObjectWitness(attacker, 1L)
        accepted.envelope.relations.shouldBeEmpty()
    }

    test("a non-empty AttackersDeclaredEvent without an attacker witness fails closed") {
        val committedTransition = transition(
            state(),
            event = event(attackers = listOf(attacker)),
        )
        produce(committedTransition)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }

    test("a declared attack with an unrelated attacker fails closed") {
        val committedTransition = transition(
            state(includeAttacker = true, includeOtherAttacker = true),
            event = event(
                attackers = listOf(attacker),
                declaredAttacks = listOf(
                    DeclaredAttack(
                        attackerId = otherAttacker,
                        defenderId = defendingPlayer,
                        defendingPlayerId = defendingPlayer,
                    ),
                ),
            ),
        )
        produce(committedTransition)
            .shouldBeInstanceOf<HistoryCReferenceEnvelopeProducerResult.Rejected>()
            .failure.code shouldBe HistoryCFailureCode.BLOCKED_ON_AUTHORITATIVE_METADATA
    }
})
