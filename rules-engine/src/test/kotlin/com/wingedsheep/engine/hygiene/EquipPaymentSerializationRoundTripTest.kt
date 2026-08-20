package com.wingedsheep.engine.hygiene

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AlternativePaymentChoice
import com.wingedsheep.sdk.scripting.EquipPaymentChoice
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** The selected equip payment mode is part of the replayable action, not transient UI state. */
class EquipPaymentSerializationRoundTripTest : FunSpec({

    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
    }

    test("explicit equip payment mode survives GameAction JSON round-trip") {
        val original: GameAction = ActivateAbility(
            playerId = EntityId.of("player"),
            sourceId = EntityId.of("equipment"),
            abilityId = AbilityId("equip"),
            alternativePayment = AlternativePaymentChoice(
                equipPayment = EquipPaymentChoice.FREE_FIRST_EQUIP
            )
        )

        val encoded = json.encodeToString(GameAction.serializer(), original)
        val decoded = json.decodeFromString(GameAction.serializer(), encoded) as ActivateAbility

        decoded shouldBe original
        decoded.alternativePayment?.equipPayment shouldBe EquipPaymentChoice.FREE_FIRST_EQUIP
    }
})
