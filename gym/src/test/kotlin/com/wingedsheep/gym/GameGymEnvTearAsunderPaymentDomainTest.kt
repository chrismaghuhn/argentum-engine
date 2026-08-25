package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.mtg.sets.definitions.dmu.cards.TearAsunder
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ChoiceSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class PreparedTearAsunderGym(
    val environment: GameEnvironment,
    val playerId: EntityId,
    val tearAsunderId: EntityId,
)

/** RED coverage for real normal and kicked Tear Asunder PaymentDomainV4 publication. */
class GameGymEnvTearAsunderPaymentDomainTest : FunSpec({

    val targetArtifact = card("Gym Tear Asunder Target Artifact") {
        typeLine = "Artifact"
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(LlanowarWastes)
        register(TearAsunder)
        register(targetArtifact)
    }

    fun prepared(): PreparedTearAsunderGym {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            TearAsunder.name to 1,
                            LlanowarWastes.name to 1,
                            targetArtifact.name to 1,
                            "Forest" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 93502L,
            ),
        )

        val playerId = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.actionType == "PassPriority" }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val entityId = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> entityId in ids }.key
            state = state.moveToZone(entityId, sourceZone, ZoneKey(playerId, destination))
            return entityId
        }

        val tearAsunderId = moveNamed(TearAsunder.name, Zone.HAND)
        moveNamed(LlanowarWastes.name, Zone.BATTLEFIELD)
        moveNamed(targetArtifact.name, Zone.BATTLEFIELD)
        repeat(4) { moveNamed("Forest", Zone.BATTLEFIELD) }
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedTearAsunderGym(environment, playerId, tearAsunderId)
    }

    fun viewFor(
        prepared: PreparedTearAsunderGym,
        kicked: Boolean,
    ): LegalActionView {
        val legalAction = prepared.environment.legalActions().single { legal ->
            val cast = legal.action as? CastSpell
            cast?.cardId == prepared.tearAsunderId &&
                (cast.declaredCostSlot == ChoiceSlot.KICKED) == kicked
        }
        return (ObservationBuilder(cardRegistry = registry())
            .build(
                prepared.environment.state,
                prepared.playerId,
                listOf(legalAction),
            ).observation as TrainingObservation).legalActions.single()
    }

    test("real Tear Asunder normal {1}{G} publishes PaymentDomainV4") {
        val prepared = prepared()
        val view = viewFor(prepared, kicked = false)

        view.manaCost shouldBe "{1}{G}"
        view.paymentDomain?.version shouldBe 4
        view.paymentDomain?.requiredCost shouldBe "{1}{G}"
    }

    test("real Tear Asunder kicked {2}{G}{B} publishes PaymentDomainV4") {
        val prepared = prepared()
        val view = viewFor(prepared, kicked = true)

        view.manaCost shouldBe "{2}{G}{B}"
        view.paymentDomain?.version shouldBe 4
        view.paymentDomain?.requiredCost shouldBe "{2}{G}{B}"
    }
})
