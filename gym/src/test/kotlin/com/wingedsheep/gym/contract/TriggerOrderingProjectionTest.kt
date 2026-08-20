package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** TO-12: the owner gets the structured ordering boundary; opponents get A4's generic view. */
class TriggerOrderingProjectionTest : FunSpec({

    test("same-controller trigger order is actor-only in the Gym pending-decision projection") {
        val registry = CardRegistry().also {
            it.register(PortalSet.cards)
            it.register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        val owner = environment.playerIds[0]
        val opponent = environment.playerIds[1]
        val first = EntityId("trigger-order-object-0")
        val second = EntityId("trigger-order-object-1")
        val pending = OrderObjectsDecision(
            id = "trigger-order-test",
            playerId = owner,
            prompt = "Choose the order for your simultaneous triggered abilities",
            context = DecisionContext(phase = DecisionPhase.TRIGGER),
            objects = listOf(first, second),
            objectLabels = mapOf(first to "first trigger", second to "second trigger")
        )
        val paused = environment.state.withPendingDecision(pending)
        val builder = ObservationBuilder(cardRegistry = registry)

        val ownerView = builder.build(paused, owner, emptyList()).observation as TrainingObservation
        val opponentView = builder.build(paused, opponent, emptyList()).observation as TrainingObservation

        ownerView.pendingDecision?.kind shouldBe PendingDecisionKind.ORDER_OBJECTS
        ownerView.pendingDecision?.requiresStructuredResponse shouldBe true
        ownerView.pendingDecision?.decisionId shouldBe pending.id
        opponentView.pendingDecision?.kind shouldBe PendingDecisionKind.GENERIC
        opponentView.pendingDecision?.decisionId shouldBe null
        opponentView.pendingDecision?.sourceName shouldBe null
        opponentView.pendingDecision?.shape shouldBe DecisionShape()
        opponentView.legalActions shouldBe emptyList()
    }
})
