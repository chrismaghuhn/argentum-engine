package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StateDigestTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    fun observation(env: GameEnvironment, perspectiveIndex: Int = 0): TrainingObservation =
        ObservationBuilder(cardRegistry = registry()).build(
            env.state,
            env.playerIds[perspectiveIndex],
            env.legalActions()
        ).observation as TrainingObservation

    test("schema identity is digest-relevant") {
        val base = observation(environment())
        val otherSchema = base.copy(schemaHash = "argentum-gym-contract@test-schema")

        StateDigest.compute(base) shouldNotBe StateDigest.compute(otherSchema)
    }

    test("action and decision transport handles are digest-irrelevant") {
        val env = environment()
        val base = observation(env)
        val actionVariant = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(actionId = index + 1000, description = "different text")
            }
        )

        StateDigest.compute(base) shouldBe StateDigest.compute(actionVariant)

        val owner = env.playerIds[0]
        val sourceId = env.state.getHand(owner).first()
        val pendingState = env.state.copy(
            pendingDecision = YesNoDecision(
                id = "decision-original",
                playerId = owner,
                prompt = "private prompt",
                context = DecisionContext(
                    sourceId = sourceId,
                    sourceName = "Mountain",
                    triggeringEntityId = sourceId,
                    effectHint = "private hint"
                )
            )
        )
        val pending = ObservationBuilder(cardRegistry = registry()).build(pendingState, owner, emptyList())
            .observation as TrainingObservation
        val pendingVariant = pending.copy(
            pendingDecision = pending.pendingDecision!!.copy(decisionId = "decision-next")
        )

        StateDigest.compute(pending) shouldBe StateDigest.compute(pendingVariant)
    }

    test("structured legal-action semantics are digest-relevant") {
        val base = observation(environment())
        val first = base.legalActions.first()
        val changed = base.copy(
            legalActions = listOf(first.copy(manaCost = "{9}")) + base.legalActions.drop(1)
        )

        StateDigest.compute(base) shouldNotBe StateDigest.compute(changed)
    }

    test("perspective is part of the information-set digest") {
        val env = environment()
        val firstPerspective = observation(env, perspectiveIndex = 0)
        val secondPerspective = observation(env, perspectiveIndex = 1)

        StateDigest.compute(firstPerspective) shouldNotBe StateDigest.compute(secondPerspective)
    }
})
