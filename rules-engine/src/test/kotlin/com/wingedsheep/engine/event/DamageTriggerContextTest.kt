package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class DamageTriggerContextTest : FunSpec({
    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
    }

    val sourceId = EntityId("damage-source")
    val recipientId = EntityId("damage-recipient")

    val damageEvent = DamageDealtEvent(
        sourceId = sourceId,
        targetId = recipientId,
        amount = 3,
        isCombatDamage = true
    )

    test("damage trigger context preserves source and recipient as separate roles") {
        val context = TriggerContext.fromEvent(damageEvent)

        context.triggeringEntityId shouldBe recipientId
        context.damageSourceEntityId shouldBe sourceId
        context.damageRecipientEntityId shouldBe recipientId
    }

    test("damage source and recipient targets resolve independently of triggering entity") {
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context) shouldBe sourceId
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context) shouldBe recipientId
    }

    test("source-filtered observers keep source TriggeringEntity without losing the recipient") {
        val context = TriggerContext.fromDamageEvent(
            damageEvent,
            triggeringEntityId = sourceId,
            triggeringPlayerId = EntityId("recipient-player")
        )

        context.triggeringEntityId shouldBe sourceId
        context.triggeringPlayerId shouldBe EntityId("recipient-player")
        context.damageSourceEntityId shouldBe sourceId
        context.damageRecipientEntityId shouldBe recipientId
    }

    test("simultaneous damage events retain each source-recipient pairing") {
        val secondSourceId = EntityId("second-source")
        val secondRecipientId = EntityId("second-recipient")
        val secondEvent = damageEvent.copy(
            sourceId = secondSourceId,
            targetId = secondRecipientId
        )

        val firstContext = TriggerContext.fromEvent(damageEvent)
        val secondContext = TriggerContext.fromEvent(secondEvent)

        firstContext.damageSourceEntityId shouldBe sourceId
        firstContext.damageRecipientEntityId shouldBe recipientId
        secondContext.damageSourceEntityId shouldBe secondSourceId
        secondContext.damageRecipientEntityId shouldBe secondRecipientId
    }

    test("a player damage recipient resolves through the player-target path") {
        val playerId = EntityId("damage-player")
        val playerEvent = damageEvent.copy(targetId = playerId, targetIsPlayer = true)
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = playerId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = playerId
        )

        TriggerContext.fromEvent(playerEvent).damageRecipientEntityId shouldBe playerId
        TargetResolutionUtils.resolvePlayerTarget(EffectTarget.DamageRecipient, context) shouldBe playerId
    }

    test("damage role targets retain IDs when source and recipient leave before resolution") {
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context) shouldBe sourceId
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context) shouldBe recipientId
    }

    test("damage source and recipient roles survive event and trigger-context serialization") {
        val eventRoundTrip = json.decodeFromString<GameEvent>(
            json.encodeToString(GameEvent.serializer(), damageEvent)
        )
        eventRoundTrip shouldBe damageEvent

        val context = TriggerContext.fromEvent(damageEvent)
        val encoded = json.encodeToString(TriggerContext.serializer(), context)
        val decoded = json.decodeFromString(TriggerContext.serializer(), encoded)

        decoded shouldBe context
        json.encodeToString(TriggerContext.serializer(), decoded) shouldBe encoded
    }

    test("damage roles survive serialization of the triggered ability stack component") {
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        val encoded = json.encodeToString(TriggeredAbilityOnStackComponent.serializer(), ability)
        val decoded = json.decodeFromString(TriggeredAbilityOnStackComponent.serializer(), encoded)

        decoded shouldBe ability
    }
})
