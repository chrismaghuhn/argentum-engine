package com.wingedsheep.sdk.serialization

import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Serialization coverage for the generic named-counter authoring path used by future Chevill. */
class NamedCounterLkiSerializationTest : FunSpec({
    test("LKI-09: BOUNTY counter vocabulary round-trips") {
        val encoded = CardSerialization.json.encodeToString(CounterType.serializer(), CounterType.BOUNTY)
        CardSerialization.json.decodeFromString(CounterType.serializer(), encoded) shouldBe CounterType.BOUNTY
        CounterType.fromName(Counters.BOUNTY) shouldBe CounterType.BOUNTY
    }

    test("LKI-09: BOUNTY named-counter filter round-trips") {
        val filter = GameObjectFilter.Permanent
            .opponentControls()
            .withCounter(Counters.BOUNTY)
        val encoded = CardSerialization.json.encodeToString(GameObjectFilter.serializer(), filter)
        CardSerialization.json.decodeFromString(GameObjectFilter.serializer(), encoded) shouldBe filter
    }
})
