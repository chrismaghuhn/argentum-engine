package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PaymentCostKind
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.ulg.UrzasLegacySet
import com.wingedsheep.mtg.sets.definitions.ulg.cards.Unearth
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * RED characterization for the real plain Cycling {2} action reached by the exact-pair corpus.
 * The production change must make this test green without adding an Unearth-specific branch.
 */
class GameGymEnvCycleCardPaymentDomainTest : FunSpec({

    test("real Unearth fixed Cycling {2} publishes PaymentDomainV4") {
        val cardRegistry = CardRegistry().apply {
            register(UrzasLegacySet.cards)
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(Unearth.name to 1, "Mountain" to 3)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 2)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )

        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        val playerId = environment.playerIds.first()

        fun moveNamed(name: String, destination: Zone): com.wingedsheep.sdk.model.EntityId {
            val cardId = state.entities.entries.first { (id, container) ->
                id in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> cardId in ids }.key
            state = state.moveToZone(cardId, sourceZone, ZoneKey(playerId, destination))
            return cardId
        }

        moveNamed(Unearth.name, Zone.HAND)
        moveNamed("Mountain", Zone.BATTLEFIELD)
        moveNamed("Mountain", Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        val observed = gym.observe().observation as TrainingObservation
        val cycle = observed.legalActions.single { it.kind == "CycleCard" && it.description.contains(Unearth.name) }

        cycle.manaCost shouldBe "{2}"
        cycle.paymentDomain shouldNotBe null
        cycle.paymentDomain!!.version shouldBe 4
        cycle.paymentDomain!!.requiredCost shouldBe "{2}"
        cycle.paymentDomain!!.costUnits.single().kind shouldBe PaymentCostKind.GENERIC
        cycle.paymentDomain!!.costUnits.single().amount shouldBe 2
    }
})
