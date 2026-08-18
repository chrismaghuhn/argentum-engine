package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.TriggeredAbilityContinuation
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class TriggerContextSerializationTest : FunSpec({
    test("triggering battlefield incarnation survives serialization") {
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
        }
        val context = TriggerContext(
            triggeringEntityId = EntityId("creature-1"),
            triggeringEntityEntryTimestamp = 42L,
            triggeringPlayerId = EntityId("player-1"),
        )

        val encoded = json.encodeToString(TriggerContext.serializer(), context)

        json.decodeFromString(TriggerContext.serializer(), encoded) shouldBe context
    }

    test("triggered ability continuation preserves battlefield incarnation") {
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
        }
        val continuation = TriggeredAbilityContinuation(
            decisionId = "decision-1",
            sourceId = EntityId("source-1"),
            sourceName = "Guardian Project",
            controllerId = EntityId("player-1"),
            effect = Effects.DrawCards(1),
            description = "Draw a card",
            triggeringEntityId = EntityId("creature-1"),
            triggeringEntityEntryTimestamp = 42L,
        )

        val encoded = json.encodeToString(ContinuationFrame.serializer(), continuation)

        json.decodeFromString(ContinuationFrame.serializer(), encoded) shouldBe continuation
    }
})
