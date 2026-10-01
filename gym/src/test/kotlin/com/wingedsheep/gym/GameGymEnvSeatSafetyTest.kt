package com.wingedsheep.gym

import com.wingedsheep.engine.core.MayAbilityContinuation
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.core.ActionParams
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class GameGymEnvSeatSafetyTest : FunSpec({
    fun registry(): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    test("non-acting seat cannot observe or submit the acting seat's decision surface") {
        val environment = GameEnvironment.create(registry())
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 20260920L
            )
        )

        val alice = environment.playerIds[0]
        val bob = environment.playerIds[1]
        val suspended = environment.state.suspendForDecision(
            question = { id ->
                YesNoDecision(
                    id = id,
                    playerId = bob,
                    prompt = "Secret decision for Bob",
                    context = DecisionContext(sourceId = EntityId("hidden-source"))
                )
            },
            answer = MayAbilityContinuation(
                playerId = bob,
                sourceName = null,
                effectIfYes = null,
                effectIfNo = null,
                effectContext = EffectContext(sourceId = null, controllerId = bob)
            )
        )
        environment.restore(suspended.state, environment.playerIds)

        // The merged (fork) GameGymEnv has no revealAll switch: observations never reveal hidden
        // information, so upstream's `defaultRevealAll = false` is the only behaviour.
        val gymEnv = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
        )

        // The fork's unnamed observe() follows the seat that has to act (Bob here), so the
        // non-acting seat's view is requested by name.
        val aliceView = gymEnv.observeForPlayer(alice).observation as TrainingObservation
        aliceView.perspectivePlayerId shouldBe alice
        aliceView.agentToAct shouldBe bob
        // Fork contract: a non-owner gets a redacted GENERIC placeholder that names only the
        // deciding player (upstream published null). None of the decision surface is exposed.
        val hiddenDecision = aliceView.pendingDecision.shouldNotBeNull()
        hiddenDecision.playerId shouldBe bob
        hiddenDecision.kind shouldBe PendingDecisionKind.GENERIC
        hiddenDecision.decisionId shouldBe null
        hiddenDecision.prompt shouldBe ""
        hiddenDecision.sourceEntityId shouldBe null
        hiddenDecision.structuredDomain shouldBe null
        aliceView.legalActions.shouldBeEmpty()
        shouldThrow<IllegalArgumentException> {
            gymEnv.step(0, ActionParams())
        }

        // A raw decision submission is guarded by its explicit actor in the fork's Gym (upstream
        // guarded it by the last observed decision instead; the merge kept the fork's guard):
        // Alice cannot answer Bob's question, and the rejection leaves the state untouched.
        val beforeRejectedDecision = environment.state
        shouldThrow<IllegalArgumentException> {
            gymEnv.submitDecision(YesNoResponse(suspended.state.pendingDecision!!.id, false), actorId = alice)
        }
        environment.state shouldBe beforeRejectedDecision

        val bobView = gymEnv.observeForPlayer(bob).observation as TrainingObservation
        bobView.perspectivePlayerId shouldBe bob
        bobView.agentToAct shouldBe bob
        bobView.pendingDecision.shouldNotBeNull()
        bobView.legalActions.shouldNotBeEmpty()

        shouldThrow<IllegalArgumentException> {
            gymEnv.observeForPlayer(EntityId("not-seated"))
        }

        // Observing the other seat revokes the folded decision actions (the action registry is
        // emptied); a raw submission on Alice's behalf is still refused by the actor guard.
        gymEnv.observeForPlayer(alice)
        shouldThrow<IllegalArgumentException> {
            gymEnv.submitDecision(YesNoResponse(suspended.state.pendingDecision!!.id, false), actorId = alice)
        }
        shouldThrow<IllegalArgumentException> { gymEnv.step(0, ActionParams()) }

        // Upstream checked a revealAll debug view here; the merged Gym has no revealAll (dropped
        // upstream feature). Its unnamed observe() is the acting seat's (Bob's) own view, which is
        // the one view that carries the decision surface.
        val actingView = gymEnv.observe().observation as TrainingObservation
        actingView.perspectivePlayerId shouldBe bob
        actingView.pendingDecision.shouldNotBeNull()
        actingView.legalActions.shouldNotBeEmpty()

        gymEnv.observeForPlayer(bob)
        gymEnv.submitDecision(YesNoResponse(suspended.state.pendingDecision!!.id, false))
        environment.state.pendingDecision shouldBe null
    }
})
