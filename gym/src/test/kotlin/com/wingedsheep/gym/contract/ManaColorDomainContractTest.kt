package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ManaColorDomainContractTest : FunSpec({
    val player = EntityId("player-0")
    val source = EntityId("source-0")

    fun state(): GameState = GameState(
        activePlayerId = player,
        priorityPlayerId = player,
        turnOrder = listOf(player),
    )

    fun action(
        requiresManaColorChoice: Boolean = true,
        availableManaColors: List<Color>? = listOf(Color.RED, Color.WHITE),
    ): LegalAction = LegalAction(
        action = ActivateAbility(player, source, AbilityId("mana-choice")),
        actionType = "ActivateAbility",
        description = "Choose a mana color",
        isManaAbility = true,
        requiresManaColorChoice = requiresManaColorChoice,
        availableManaColors = availableManaColors,
    )

    test("ObservationBuilder publishes a canonical exact mana-color domain") {
        val result = ObservationBuilder(cardRegistry = com.wingedsheep.engine.registry.CardRegistry())
            .build(state(), player, listOf(action()))

        val view = (result.observation as TrainingObservation).legalActions.single()
        view.availableManaColors shouldBe listOf(Color.WHITE, Color.RED)
    }

    test("actions without a mana-color choice do not publish a color domain") {
        val result = ObservationBuilder(cardRegistry = com.wingedsheep.engine.registry.CardRegistry())
            .build(
                state(),
                player,
                listOf(action(requiresManaColorChoice = false, availableManaColors = listOf(Color.RED))),
            )

        val view = (result.observation as TrainingObservation).legalActions.single()
        view.availableManaColors shouldBe null
    }

    test("published mana-color domains survive the observation wire round trip") {
        val observation = ObservationBuilder(cardRegistry = com.wingedsheep.engine.registry.CardRegistry())
            .build(state(), player, listOf(action()))
            .observation as TrainingObservation
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        val roundTripped = json.decodeFromString(
            TrainingObservation.serializer(),
            json.encodeToString(TrainingObservation.serializer(), observation),
        )

        roundTripped.legalActions.single().availableManaColors shouldBe listOf(Color.WHITE, Color.RED)
    }
})
