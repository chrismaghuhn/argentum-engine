package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.DeclaredAttack
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class AttackersDeclaredEventSerializationTest : FunSpec({

    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
    }

    val attackerA = EntityId.of("attacker-a")
    val attackerB = EntityId.of("attacker-b")
    val playerB = EntityId.of("player-b")
    val playerC = EntityId.of("player-c")

    test("declared attack snapshots round-trip through the polymorphic GameEvent serializer") {
        val event = AttackersDeclaredEvent(
            attackers = listOf(attackerA, attackerB),
            attackingPlayerId = EntityId.of("player-a"),
            declaredAttacks = listOf(
                DeclaredAttack(attackerA, playerB, playerB),
                DeclaredAttack(attackerB, playerC, playerC),
            ),
        )

        val encoded = json.encodeToString(GameEvent.serializer(), event)
        val decoded = json.decodeFromString(GameEvent.serializer(), encoded)

        decoded shouldBe event
    }

    test("historical payload without declaredAttacks decodes with no invented target data") {
        val event = AttackersDeclaredEvent(
            attackers = listOf(attackerA, attackerB),
            attackingPlayerId = EntityId.of("player-a"),
            attackersAgainstPlayer = setOf(attackerA, attackerB),
            declaredAttacks = listOf(
                DeclaredAttack(attackerA, playerB, playerB),
                DeclaredAttack(attackerB, playerC, playerC),
            ),
        )
        val encoded = json.encodeToString(GameEvent.serializer(), event)
        val legacyPayload = JsonObject(
            json.parseToJsonElement(encoded).jsonObject.filterKeys { it != "declaredAttacks" }
        )

        val decoded = json.decodeFromString(GameEvent.serializer(), legacyPayload.toString())
            as AttackersDeclaredEvent

        decoded.declaredAttacks shouldBe emptyList()
        decoded.attackersAgainstPlayer shouldBe setOf(attackerA, attackerB)
    }
})
