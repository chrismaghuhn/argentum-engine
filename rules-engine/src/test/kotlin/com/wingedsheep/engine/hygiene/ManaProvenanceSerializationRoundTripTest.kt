package com.wingedsheep.engine.hygiene

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.mechanics.mana.fromManaPool
import com.wingedsheep.engine.mechanics.mana.toManaPool
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ManaProvenanceSerializationRoundTripTest : FunSpec({

    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
        allowStructuredMapKeys = true
    }

    fun pool(): ManaPoolComponent {
        val blackSource = EntityId("e108")
        val greenSource = EntityId("e117")
        return ManaPoolComponent(
            black = 1,
            green = 3,
            manaBySubtype = mapOf(Subtype.FOREST to 3),
            manaBySource = mapOf(blackSource to 1, greenSource to 3),
            manaBySourceAndColor = mapOf(
                blackSource to mapOf(PaymentManaColor.BLACK to 1),
                greenSource to mapOf(PaymentManaColor.GREEN to 3),
            ),
            manaByFloatingBucket = mapOf(
                FloatingManaBucketKeyV1(blackSource, PaymentManaColor.BLACK, emptySet()) to 1,
                FloatingManaBucketKeyV1(greenSource, PaymentManaColor.GREEN, setOf(Subtype.FOREST)) to 3,
            ),
            manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
        )
    }

    test("authoritative source-color provenance survives component and transient-pool seams") {
        val component = pool()
        val encoded = json.encodeToString(ManaPoolComponent.serializer(), component)
        val decoded = json.decodeFromString(ManaPoolComponent.serializer(), encoded)

        decoded shouldBe component
        fromManaPool(component.toManaPool()) shouldBe component
    }

    test("a serialized GameState checkpoint and immutable fork retain source-color provenance") {
        val playerId = EntityId("player")
        val state = GameState().withEntity(playerId, ComponentContainer.of(pool()))
        val fork = state.copy()

        fork.getEntity(playerId)?.get<ManaPoolComponent>() shouldBe pool()

        val checkpoint = json.encodeToString(GameState.serializer(), state)
        val restored = json.decodeFromString(GameState.serializer(), checkpoint)

        restored shouldBe state
        restored.getEntity(playerId)?.get<ManaPoolComponent>() shouldBe pool()
    }
})
