package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.Suspension
import com.wingedsheep.engine.core.TriggeredAbilityContinuation
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
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
            triggeringEntityName = null,
            triggeringEntityNameKnown = true,
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
        // The answer frame is persisted inside the Suspension that associates it with its question.
        val continuation = TriggeredAbilityContinuation(
            sourceId = EntityId("source-1"),
            sourceName = "Guardian Project",
            controllerId = EntityId("player-1"),
            effect = Effects.DrawCards(1),
            description = "Draw a card",
            triggerContext = TriggerContext(
                triggeringEntityId = EntityId("creature-1"),
                triggeringEntityEntryTimestamp = 42L,
                triggeringEntityName = "Grizzly Bears",
                triggeringEntityNameKnown = true,
            ),
        )
        val suspension = Suspension(
            question = ChooseTargetsDecision(
                id = "decision-1",
                playerId = EntityId("player-1"),
                prompt = "Choose targets",
                context = DecisionContext(sourceId = EntityId("source-1")),
                targetRequirements = emptyList(),
                legalTargets = emptyMap(),
            ),
            answer = continuation,
        )

        val encoded = json.encodeToString(ContinuationFrame.serializer(), suspension)

        val decoded = json.decodeFromString(ContinuationFrame.serializer(), encoded)
        decoded shouldBe suspension
        (decoded as Suspension).answer shouldBe continuation
    }

    test("triggered ability stack component preserves the frozen occurrence name") {
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
        }
        val component = TriggeredAbilityOnStackComponent(
            sourceId = EntityId("source-1"),
            sourceName = "Guardian Project",
            controllerId = EntityId("player-1"),
            effect = Effects.DrawCards(1),
            description = "Draw a card",
            triggerContext = TriggerContext(
                triggeringEntityId = EntityId("creature-1"),
                triggeringEntityEntryTimestamp = 42L,
                triggeringEntityName = null,
                triggeringEntityNameKnown = true,
            ),
        )
        val original = ComponentContainer().with(component)

        val encoded = json.encodeToString(ComponentContainer.serializer(), original)

        json.decodeFromString(ComponentContainer.serializer(), encoded)
            .get<TriggeredAbilityOnStackComponent>() shouldBe component
    }
})
