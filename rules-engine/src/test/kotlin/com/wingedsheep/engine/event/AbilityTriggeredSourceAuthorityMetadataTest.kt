package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import com.wingedsheep.sdk.scripting.references.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.wingedsheep.engine.core.engineSerializersModule
import kotlinx.serialization.json.Json

private val metadataEtbSource = card("Source Authority Metadata ETB") {
    manaCost = "{0}"
    colorIdentity = ""
    typeLine = "Creature"
    power = 1
    toughness = 1

    spell {}

    triggeredAbility {
        trigger = TriggerSpec(
            event = EventPattern.ZoneChangeEvent(to = Zone.BATTLEFIELD),
            binding = TriggerBinding.SELF,
        )
        effect = Effects.GainLife(1)
    }
}

private val metadataLtbSource = card("Source Authority Metadata LTB") {
    manaCost = "{0}"
    colorIdentity = ""
    typeLine = "Creature"
    power = 1
    toughness = 1

    spell {}

    triggeredAbility {
        trigger = TriggerSpec(
            event = EventPattern.ZoneChangeEvent(
                from = Zone.BATTLEFIELD,
                to = Zone.GRAVEYARD,
            ),
            binding = TriggerBinding.SELF,
        )
        effect = Effects.GainLife(1)
    }
}

private val metadataPersistentSource = card("Source Authority Metadata Persistent") {
    manaCost = "{0}"
    colorIdentity = ""
    typeLine = "Creature"
    power = 1
    toughness = 1

    spell {}

    triggeredAbility {
        trigger = TriggerSpec(
            event = EventPattern.StepEvent(Step.UPKEEP, Player.You),
            binding = TriggerBinding.SELF,
        )
        effect = Effects.GainLife(1)
    }
}

private data class Emission(
    val before: GameState,
    val after: GameState,
    val result: ExecutionResult,
    val event: AbilityTriggeredEvent,
)

class AbilityTriggeredSourceAuthorityMetadataTest : FunSpec({
    fun driver(): GameTestDriver = GameTestDriver().apply {
        registerCards(TestCards.all + metadataEtbSource + metadataLtbSource + metadataPersistentSource)
        initMirrorMatch(deck = Deck.of("Island" to 40))
    }

    fun process(
        driver: GameTestDriver,
        before: GameState,
        after: GameState,
        pending: PendingTrigger,
    ): Emission {
        val result = TriggerProcessor(
            cardRegistry = driver.cardRegistry,
            stackResolver = StackResolver(driver.cardRegistry),
        ).processTriggers(after, listOf(pending))
        val event = result.events.filterIsInstance<AbilityTriggeredEvent>().single()
        return Emission(before, after, result, event)
    }

    fun stackAbility(emission: Emission): TriggeredAbilityOnStackComponent = emission.result.newState
        .getEntity(checkNotNull(emission.event.abilityEntityId))
        ?.get<TriggeredAbilityOnStackComponent>()
        .shouldBeInstanceOf<TriggeredAbilityOnStackComponent>()

    fun etb(): Emission {
        val driver = driver()
        val sourceId = driver.putCardInHand(driver.player1, metadataEtbSource.name)
        val before = driver.state
        val after = before.moveToZone(
            entityId = sourceId,
            from = ZoneKey(driver.player1, Zone.HAND),
            to = ZoneKey(driver.player1, Zone.BATTLEFIELD),
        )
        val event = ZoneChangeEvent(
            entityId = sourceId,
            entityName = metadataEtbSource.name,
            fromZone = Zone.HAND,
            toZone = Zone.BATTLEFIELD,
            ownerId = driver.player1,
        )
        val pending = TriggerDetector(driver.cardRegistry)
            .detectTriggers(after, listOf(event))
            .single { it.sourceId == sourceId }
        return process(driver, before, after, pending)
    }

    fun ltb(): Emission {
        val driver = driver()
        val sourceId = driver.putCreatureOnBattlefield(driver.player1, metadataLtbSource.name)
        val before = driver.state
        val after = before
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), sourceId)
            .withoutEntity(sourceId)
        val sourceStamp = checkNotNull(before.objectIdentityStamps[sourceId])
        val event = ZoneChangeEvent(
            entityId = sourceId,
            entityName = metadataLtbSource.name,
            fromZone = Zone.BATTLEFIELD,
            toZone = Zone.GRAVEYARD,
            ownerId = driver.player1,
            lastKnown = EntitySnapshot(
                entityId = sourceId,
                controllerId = driver.player1,
                typeLine = TypeLine.parse("Creature"),
                cardDefinitionId = metadataLtbSource.name,
                objectIncarnationStamp = sourceStamp,
            ),
        )
        val lastKnown = checkNotNull(event.lastKnown)
        lastKnown.entityId shouldBe sourceId
        lastKnown.objectIncarnationStamp shouldBe sourceStamp
        val pending = TriggerDetector(driver.cardRegistry)
            .detectTriggers(after, listOf(event))
            .single { it.sourceId == sourceId }
        return process(driver, before, after, pending)
    }

    fun persistent(): Emission {
        val driver = driver()
        val sourceId = driver.putCreatureOnBattlefield(driver.player1, metadataPersistentSource.name)
        val before = driver.state
        val pending = TriggerDetector(driver.cardRegistry)
            .detectPhaseStepTriggers(before, Step.UPKEEP, driver.player1)
            .single { it.sourceId == sourceId }
        return process(driver, before, before, pending)
    }

    test("ETB emits AFTER source endpoint authority") {
        val emission = etb()

        stackAbility(emission).sourceId shouldBe emission.event.sourceId
        emission.event.abilityEntityId shouldBe emission.result.newState.stack.single()
        emission.event.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.AFTER_OBJECT
    }

    test("LTB emits BEFORE source endpoint authority") {
        val emission = ltb()

        stackAbility(emission).sourceId shouldBe emission.event.sourceId
        emission.event.abilityEntityId shouldBe emission.result.newState.stack.single()
        emission.before.hasEntity(emission.event.sourceId) shouldBe true
        emission.after.hasEntity(emission.event.sourceId) shouldBe false
        emission.event.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
    }

    test("persistent trigger emits SAME_INCARNATION source authority") {
        val emission = persistent()

        stackAbility(emission).sourceId shouldBe emission.event.sourceId
        emission.event.abilityEntityId shouldBe emission.result.newState.stack.single()
        emission.before.objectIdentityStamps[emission.event.sourceId] shouldBe
            emission.after.objectIdentityStamps[emission.event.sourceId]
        emission.event.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
    }

    test("source endpoint authority survives deterministic event serialization") {
        val event = etb().event
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
        }

        val encoded = json.encodeToString(AbilityTriggeredEvent.serializer(), event)
        json.decodeFromString(AbilityTriggeredEvent.serializer(), encoded) shouldBe event
        json.encodeToString(AbilityTriggeredEvent.serializer(), event) shouldBe encoded
    }
})
