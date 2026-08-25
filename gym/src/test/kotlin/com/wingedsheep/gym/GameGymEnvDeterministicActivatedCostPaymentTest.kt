package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.`5dn`.cards.WayfarersBauble
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private data class PreparedDeterministicAbilityGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val playerId: EntityId,
    val sourceId: EntityId,
    val mountainIds: List<EntityId>,
)

/** RED characterization for public payment of deterministic activated-ability additional costs. */
class GameGymEnvDeterministicActivatedCostPaymentTest : FunSpec({

    fun registry(extraCards: List<CardDefinition> = emptyList()) = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(WayfarersBauble)
        register(MindStone)
        extraCards.forEach(::register)
    }

    fun preparedWayfarer(): PreparedDeterministicAbilityGym {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(WayfarersBauble.name to 1, "Mountain" to 6)),
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
        fun moveNamed(name: String, destination: Zone): EntityId {
            val cardId = state.entities.entries.first { (id, container) ->
                id in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> cardId in ids }.key
            state = state.moveToZone(cardId, sourceZone, ZoneKey(playerId, destination))
            return cardId
        }

        val sourceId = moveNamed(WayfarersBauble.name, Zone.BATTLEFIELD)
        val mountainIds = listOf(
            moveNamed("Mountain", Zone.BATTLEFIELD),
            moveNamed("Mountain", Zone.BATTLEFIELD),
        )
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedDeterministicAbilityGym(
            environment = environment,
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ),
            playerId = playerId,
            sourceId = sourceId,
            mountainIds = mountainIds,
        )
    }

    test("real Wayfarer's Bauble publishes a usable PaymentDomainV4") {
        val prepared = preparedWayfarer()
        val wayfarerAction = prepared.environment.legalActions().single { legalAction ->
            val action = legalAction.action as? ActivateAbility
            action?.sourceId == prepared.sourceId
        }

        val view = ObservationBuilder(cardRegistry = registry())
            .build(prepared.environment.state, prepared.playerId, listOf(wayfarerAction))
            .observation
            .let { it as TrainingObservation }
            .legalActions
            .single()

        view.requiredPayloadFields shouldBe listOf("paymentStrategy", "costPayment")
        view.sourceEntityId shouldBe prepared.sourceId
        view.validSacrificeTargets shouldBe listOf(prepared.sourceId)
        view.paymentDomain shouldNotBe null
        view.paymentDomain!!.version shouldBe 4
        view.paymentDomain!!.requiredCost shouldBe "{2}"
        view.paymentDomain!!.sourceActivations.any { it.sourceId == prepared.sourceId } shouldBe false
    }
})
