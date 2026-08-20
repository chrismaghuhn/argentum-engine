package com.wingedsheep.sdk.serialization

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/** Serialization contract for the generic cast-cost → linked-trigger SDK linkage. */
class CostPaidLinkedTriggerSerializationTest : FunSpec({

    test("a cost-paid linked trigger survives the CardScript JSON round-trip") {
        val trigger = Triggers.costPaidLinkedTrigger(
            effect = Effects.DrawCards(1, EffectTarget.Controller),
        )
        val script = CardScript(
            spellEffect = Effects.DrawCards(1, EffectTarget.Controller),
            costPaidLinkedTriggers = listOf(trigger),
        )

        val encoded = CardSerialization.json.encodeToString(CardScript.serializer(), script)
        val decoded = CardSerialization.json.decodeFromString(CardScript.serializer(), encoded)

        decoded shouldBe script
        encoded shouldContain "costPaidLinkedTriggers"

        val explicitDefaultsJson = Json {
            serializersModule = CardSerialization.module
            classDiscriminator = "type"
            encodeDefaults = true
        }
        explicitDefaultsJson.encodeToString(CardScript.serializer(), script)
            .shouldContain("VariablePermanentsSacrifice")
    }
})
