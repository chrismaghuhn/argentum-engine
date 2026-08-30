package com.wingedsheep.gym

import com.wingedsheep.gym.contract.EntityFeatures
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.ZoneView
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ManaColorDomainPolicyTest : FunSpec({
    val player = EntityId("player-0")

    fun action(colors: List<Color>?): LegalActionView = LegalActionView(
        actionId = 42,
        kind = "ActivateAbility",
        description = "Add one mana of any color in your commander's color identity",
        affordable = true,
        isManaAbility = true,
        availableManaColors = colors,
        requiresStructuredAction = true,
        requiredPayloadFields = listOf("manaColorChoice"),
        actionSemantics = jsonObject(
            "type" to JsonPrimitive("ActivateAbility"),
            "abilityKey" to jsonObject(
                "ability" to jsonObject(
                    "effect" to jsonObject(
                        "colorSet" to jsonObject(
                            "type" to JsonPrimitive("ManaColorSet.CommanderIdentity"),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun observation(action: LegalActionView, commanderZone: Zone): TrainingObservation {
        val commander = EntityFeatures(
            entityId = EntityId("commander-0"),
            cardDefinitionId = "public-commander",
            name = "Public Commander",
            zone = commanderZone,
            ownerId = player,
            controllerId = null,
            types = setOf("CREATURE"),
            subtypes = emptySet(),
            colors = setOf("RED", "WHITE"),
            keywords = emptySet(),
            manaCost = "",
            manaValue = 0,
            power = null,
            toughness = null,
        )
        return TrainingObservation(
            schemaHash = "test-schema",
            perspectivePlayerId = player,
            agentToAct = player,
            turnNumber = 1,
            phase = Phase.PRECOMBAT_MAIN,
            step = Step.PRECOMBAT_MAIN,
            activePlayerId = player,
            priorityPlayerId = player,
            players = emptyList(),
            zones = listOf(
                ZoneView(
                    ownerId = player,
                    zoneType = commanderZone,
                    hidden = false,
                    size = 1,
                    cards = listOf(commander),
                ),
            ),
            stack = emptyList(),
            pendingDecision = null,
            legalActions = listOf(action),
            terminated = false,
            truncated = false,
            winnerId = null,
            stateDigest = "digest",
        )
    }

    test("policy selects from the published colors when the commander is outside command zone") {
        val choice = DeterministicExternalPolicy().choose(
            observation(action(listOf(Color.WHITE, Color.RED)), Zone.GRAVEYARD),
            DeterministicPolicyState(policySeed = 1L),
        )

        val selected = choice as SemanticChoice.Action
        selected.payload?.get("manaColorChoice") shouldBe JsonPrimitive("WHITE")
    }

    test("policy fails closed when no color domain is published") {
        val choice = DeterministicExternalPolicy().choose(
            observation(action(null), Zone.COMMAND),
            DeterministicPolicyState(policySeed = 1L),
        )

        val gap = choice as SemanticChoice.Gap
        gap.family shouldBe "MANA_COLOR"
        gap.code shouldBe "A5_DECISION_GAP"
    }

    test("policy fails closed when the published color domain contains duplicates") {
        val choice = DeterministicExternalPolicy().choose(
            observation(action(listOf(Color.WHITE, Color.WHITE)), Zone.GRAVEYARD),
            DeterministicPolicyState(policySeed = 1L),
        )

        val gap = choice as SemanticChoice.Gap
        gap.family shouldBe "MANA_COLOR"
        gap.code shouldBe "A5_DECISION_GAP"
    }
})

private fun jsonObject(vararg entries: Pair<String, JsonElement>): JsonObject =
    buildJsonObject {
        entries.forEach { (key, value) -> put(key, value) }
    }
