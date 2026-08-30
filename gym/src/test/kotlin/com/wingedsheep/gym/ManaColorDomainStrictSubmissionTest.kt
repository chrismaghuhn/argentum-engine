package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ManaColorDomainStrictSubmissionTest : FunSpec({
    test("strict Gym rejects an unpublished mana color without mutating the game") {
        val prepared = preparedCommanderSphere()
        val environment = prepared.environment
        val gym = prepared.gym
        val view = prepared.manaAction()
        val before = environment.state
        val beforeStepCount = environment.stepCount
        val payload = view.actionSemantics!!.toMutableMap().toMutableMap().let { values ->
            buildJsonObject {
                values.forEach { (key, value) -> put(key, value) }
                put("manaColorChoice", JsonPrimitive("BLUE"))
            }
        }

        shouldThrow<IllegalArgumentException> {
            gym.step(view.actionId, payload)
        }

        environment.state shouldBe before
        environment.stepCount shouldBe beforeStepCount
    }

    test("strict Gym accepts a color from the published CommanderIdentity domain") {
        val prepared = preparedCommanderSphere()
        val environment = prepared.environment
        val gym = prepared.gym
        val view = prepared.manaAction()
        val payload = view.actionSemantics!!.toMutableMap().let { values ->
            buildJsonObject {
                values.forEach { (key, value) -> put(key, value) }
                put("manaColorChoice", JsonPrimitive("WHITE"))
            }
        }

        gym.step(view.actionId, payload)

        environment.stepCount shouldBe prepared.initialStepCount + 1
        environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe true
        environment.state.getEntity(prepared.playerId)?.get<ManaPoolComponent>()?.white shouldBe 1
    }
})

private data class PreparedCommanderSphere(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val playerId: EntityId,
    val sourceId: EntityId,
    val commanderId: EntityId,
    val initialStepCount: Int,
) {
    fun manaAction() = (gym.observe().observation as TrainingObservation).legalActions.single {
        it.kind == "ActivateAbility" &&
            it.sourceEntityId == sourceId &&
            it.requiredPayloadFields == listOf("manaColorChoice")
    }.also { action ->
        action.availableManaColors shouldBe listOf(Color.WHITE, Color.RED)
        environment.state.getZone(playerId, Zone.GRAVEYARD) shouldBe listOf(commanderId)
    }
}

private fun preparedCommanderSphere(): PreparedCommanderSphere {
    val cardRegistry = fullCardRegistry()
    val environment = GameEnvironment.create(cardRegistry)
    environment.reset(
        GameConfig(
            format = Format.Commander(),
            players = listOf(
                PlayerConfig(
                    "Alice",
                    Deck.of("Commander's Sphere" to 1, "Mountain" to 98),
                    commanderCardName = "Akiri, Fearless Voyager",
                ),
                PlayerConfig(
                    "Bob",
                    Deck.of("Mountain" to 99),
                    commanderCardName = "Akiri, Fearless Voyager",
                ),
            ),
            startingHandSize = 8,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 106L,
        ),
    )

    while (environment.state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
        val pass = environment.legalActions().first { it.action is PassPriority }
        environment.step(pass.action)
    }

    val playerId = environment.playerIds.first()
    var state = environment.state
    val sphereId = state.entities.entries.first { (id, container) ->
        id in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
            container.get<CardComponent>()?.name == "Commander's Sphere"
    }.key
    val sphereFrom = state.zones.entries.first { (_, ids) -> sphereId in ids }.key
    state = state.moveToZone(sphereId, sphereFrom, ZoneKey(playerId, Zone.BATTLEFIELD))

    val commanderId = state.getZone(playerId, Zone.COMMAND).single()
    state = state.moveToZone(
        commanderId,
        ZoneKey(playerId, Zone.COMMAND),
        ZoneKey(playerId, Zone.GRAVEYARD),
    )
    environment.restore(state, environment.playerIds, environment.stepCount)

    val gym = GameGymEnv(
        environment = environment,
        perspectivePlayerIndex = 0,
        observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
    )
    return PreparedCommanderSphere(
        environment = environment,
        gym = gym,
        playerId = playerId,
        sourceId = sphereId,
        commanderId = commanderId,
        initialStepCount = environment.stepCount,
    )
}

private fun fullCardRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards.map { it.withSetCodeIfMissing(set.code) })
        register(set.basicLands.map { it.withSetCodeIfMissing(set.code) })
    }
}

private fun CardDefinition.withSetCodeIfMissing(code: String): CardDefinition =
    if (setCode == null) copy(setCode = code) else this
