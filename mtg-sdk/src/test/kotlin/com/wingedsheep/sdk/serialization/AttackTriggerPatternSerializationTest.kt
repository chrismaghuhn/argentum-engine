package com.wingedsheep.sdk.serialization

import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.serialization.CardSerialization
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AttackTriggerPatternSerializationTest : FunSpec({

    test("YouAttackPlayerEvent round-trips with its per-player minimum and filter") {
        val pattern = EventPattern.YouAttackPlayerEvent(
            minAttackers = 2,
            attackerFilter = GameObjectFilter.Creature.equipped().youControl(),
        )

        val encoded = CardSerialization.json.encodeToString(EventPattern.serializer(), pattern)
        val decoded = CardSerialization.json.decodeFromString(EventPattern.serializer(), encoded)

        decoded shouldBe pattern
    }

    test("the generic filtered facade constructs the opt-in per-player event") {
        val filter = GameObjectFilter.Creature.equipped().youControl()

        val spec = Triggers.YouAttackPlayerWithFilter(filter)

        spec.event shouldBe EventPattern.YouAttackPlayerEvent(
            minAttackers = 1,
            attackerFilter = filter,
        )
    }

    test("YouAttackPlayerEvent rejects a non-positive minimum") {
        shouldThrow<IllegalArgumentException> {
            EventPattern.YouAttackPlayerEvent(minAttackers = 0)
        }
        shouldThrow<IllegalArgumentException> {
            EventPattern.YouAttackPlayerEvent(minAttackers = -1)
        }
    }
})
