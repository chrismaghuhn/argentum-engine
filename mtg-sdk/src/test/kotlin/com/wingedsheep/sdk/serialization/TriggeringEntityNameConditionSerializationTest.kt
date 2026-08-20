package com.wingedsheep.sdk.serialization

import com.wingedsheep.sdk.scripting.conditions.Condition
import com.wingedsheep.sdk.scripting.conditions.TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The new trigger-context condition must survive the deterministic SDK card wire format. */
class TriggeringEntityNameConditionSerializationTest : FunSpec({
    test("round trips through the polymorphic Condition serializer") {
        val condition = TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard
        val encoded = CardSerialization.json.encodeToString(Condition.serializer(), condition)

        CardSerialization.json.decodeFromString(Condition.serializer(), encoded) shouldBe condition
    }
})
