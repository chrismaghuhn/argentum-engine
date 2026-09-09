package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.AbilityTriggeredEvent
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
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import com.wingedsheep.sdk.scripting.references.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val emissionEtbSource = card("Emission Authority ETB Source") {
    manaCost = "{0}"
    colorIdentity = ""
    typeLine = "Creature"
    power = 1
    toughness = 1
    oracleText = "When this creature enters the battlefield, you gain 1 life."

    spell {}

    triggeredAbility {
        trigger = TriggerSpec(
            event = EventPattern.ZoneChangeEvent(to = Zone.BATTLEFIELD),
            binding = TriggerBinding.SELF,
        )
        effect = Effects.GainLife(1)
    }
}

private val emissionLtbSource = card("Emission Authority LTB Source") {
    manaCost = "{0}"
    colorIdentity = ""
    typeLine = "Creature"
    power = 1
    toughness = 1
    oracleText = "When this creature dies, you gain 1 life."

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

private val emissionPersistentSource = card("Emission Authority Persistent Source") {
    manaCost = "{0}"
    colorIdentity = ""
    typeLine = "Creature"
    power = 1
    toughness = 1
    oracleText = "At the beginning of your upkeep, you gain 1 life."

    spell {}

    triggeredAbility {
        trigger = TriggerSpec(
            event = EventPattern.StepEvent(Step.UPKEEP, Player.You),
            binding = TriggerBinding.SELF,
        )
        effect = Effects.GainLife(1)
    }
}

private data class TriggerEmission(
    val before: GameState,
    val after: GameState,
    val pending: PendingTrigger,
    val result: ExecutionResult,
    val event: AbilityTriggeredEvent,
)

/** Test-only characterization of Rules data available when a triggered ability is emitted. */
class TriggeredAbilityEmissionAuthorityCharacterizationTest : FunSpec({
    fun newDriver(): GameTestDriver = GameTestDriver().apply {
        registerCards(
            TestCards.all + emissionEtbSource + emissionLtbSource + emissionPersistentSource,
        )
        initMirrorMatch(deck = Deck.of("Island" to 40))
    }

    fun processOne(
        driver: GameTestDriver,
        before: GameState,
        after: GameState,
        events: List<GameEvent>,
        pending: PendingTrigger,
    ): TriggerEmission {
        val result = TriggerProcessor(
            cardRegistry = driver.cardRegistry,
            stackResolver = StackResolver(driver.cardRegistry),
        ).processTriggers(after, listOf(pending))
        val event = result.events.filterIsInstance<AbilityTriggeredEvent>().single()
        return TriggerEmission(before, after, pending, result, event)
    }

    fun abilityOnStack(emission: TriggerEmission): TriggeredAbilityOnStackComponent =
        emission.result.newState
            .getEntity(checkNotNull(emission.event.abilityEntityId))
            ?.get<TriggeredAbilityOnStackComponent>()
            .shouldBeInstanceOf<TriggeredAbilityOnStackComponent>()

    fun eventFacts(emission: TriggerEmission): Map<String, Any?> {
        val ability = abilityOnStack(emission)
        val sourceId = emission.event.sourceId
        val sourceBeforeStamp = emission.before.objectIdentityStamps[sourceId]
        val sourceAfterStamp = emission.after.objectIdentityStamps[sourceId]
        val sourceBeforeWitness = emission.before.hasEntity(sourceId) && sourceBeforeStamp != null
        val sourceAfterWitness = emission.after.hasEntity(sourceId) && sourceAfterStamp != null
        return mapOf(
            "sourceBeforeWitness" to sourceBeforeWitness,
            "sourceAfterWitness" to sourceAfterWitness,
            "sourceSameIncarnation" to (
                sourceBeforeWitness && sourceAfterWitness && sourceBeforeStamp == sourceAfterStamp
                ),
            "sourceChangedIncarnation" to (
                sourceBeforeWitness && sourceAfterWitness && sourceBeforeStamp != sourceAfterStamp
                ),
            "eventSourceMatchesStackAbilitySource" to (ability.sourceId == sourceId),
            "eventHasAbilityEntityId" to (emission.event.abilityEntityId != null),
            "abilityBeforeWitness" to (
                emission.event.abilityEntityId?.let { id ->
                    emission.before.hasEntity(id) && emission.before.objectIdentityStamps[id] != null
                } == true
                ),
            "abilityAfterWitness" to (
                emission.event.abilityEntityId?.let { id ->
                    emission.result.newState.hasEntity(id) &&
                        emission.result.newState.objectIdentityStamps[id] != null
                } == true
                ),
            "abilityAfterOnStack" to (
                emission.event.abilityEntityId?.let { id -> id in emission.result.newState.stack } == true
                ),
            "pendingSourceMatchesEventSource" to (emission.pending.sourceId == sourceId),
            "pendingTriggeringEntityEntryTimestampPresent" to
                (emission.pending.triggerContext.triggeringEntityEntryTimestamp != null),
            "stackTriggeringEntityEntryTimestampPresent" to
                (ability.triggeringEntityEntryTimestamp != null),
        )
    }

    fun etbEmission(): TriggerEmission {
        val driver = newDriver()
        val sourceId = driver.putCardInHand(driver.player1, emissionEtbSource.name)
        val before = driver.state
        val after = before.moveToZone(
            entityId = sourceId,
            from = ZoneKey(driver.player1, Zone.HAND),
            to = ZoneKey(driver.player1, Zone.BATTLEFIELD),
        )
        val event = ZoneChangeEvent(
            entityId = sourceId,
            entityName = emissionEtbSource.name,
            fromZone = Zone.HAND,
            toZone = Zone.BATTLEFIELD,
            ownerId = driver.player1,
        )
        val pending = TriggerDetector(driver.cardRegistry)
            .detectTriggers(after, listOf(event))
            .single { it.sourceId == sourceId }
        return processOne(driver, before, after, listOf(event), pending)
    }

    fun ltbEmission(): TriggerEmission {
        val driver = newDriver()
        val sourceId = driver.putCreatureOnBattlefield(driver.player1, emissionLtbSource.name)
        val before = driver.state
        val sourceStamp = checkNotNull(before.objectIdentityStamps[sourceId])
        val after = before
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), sourceId)
            .withoutEntity(sourceId)
        val event = ZoneChangeEvent(
            entityId = sourceId,
            entityName = emissionLtbSource.name,
            fromZone = Zone.BATTLEFIELD,
            toZone = Zone.GRAVEYARD,
            ownerId = driver.player1,
            lastKnown = EntitySnapshot(
                entityId = sourceId,
                controllerId = driver.player1,
                typeLine = TypeLine.parse("Creature"),
                cardDefinitionId = emissionLtbSource.name,
                objectIncarnationStamp = sourceStamp,
            ),
        )
        val lastKnown = checkNotNull(event.lastKnown)
        lastKnown.entityId shouldBe sourceId
        lastKnown.objectIncarnationStamp shouldBe sourceStamp
        val pending = TriggerDetector(driver.cardRegistry)
            .detectTriggers(after, listOf(event))
            .single { it.sourceId == sourceId }
        return processOne(driver, before, after, listOf(event), pending)
    }

    fun persistentEmission(): TriggerEmission {
        val driver = newDriver()
        val sourceId = driver.putCreatureOnBattlefield(driver.player1, emissionPersistentSource.name)
        val before = driver.state
        val after = before
        val pending = TriggerDetector(driver.cardRegistry)
            .detectPhaseStepTriggers(after, Step.UPKEEP, driver.player1)
            .single { it.sourceId == sourceId }
        return processOne(driver, before, after, emptyList(), pending)
    }

    test("ETB trigger emission retains source as a new after object but carries no source authority") {
        val emission = etbEmission()
        val facts = eventFacts(emission)
        val source = emission.event.sourceId

        emission.event.sourceId shouldBe source
        emission.event.abilityEntityId shouldBe emission.result.newState.stack.single()
        facts["sourceBeforeWitness"] shouldBe true
        facts["sourceAfterWitness"] shouldBe true
        facts["sourceSameIncarnation"] shouldBe false
        facts["sourceChangedIncarnation"] shouldBe true
        facts["eventSourceMatchesStackAbilitySource"] shouldBe true
        facts["eventHasAbilityEntityId"] shouldBe true
        facts["abilityBeforeWitness"] shouldBe false
        facts["abilityAfterWitness"] shouldBe true
        facts["abilityAfterOnStack"] shouldBe true
        facts["pendingSourceMatchesEventSource"] shouldBe true
        facts["pendingTriggeringEntityEntryTimestampPresent"] shouldBe false
        facts["stackTriggeringEntityEntryTimestampPresent"] shouldBe false

        println(
            "TRIGGER_EMISSION_AUTHORITY ETB " +
                "sourceBeforeWitness=${facts["sourceBeforeWitness"]} " +
                "sourceAfterWitness=${facts["sourceAfterWitness"]} " +
                "sameIncarnation=${facts["sourceSameIncarnation"]} " +
                "changedIncarnation=${facts["sourceChangedIncarnation"]} " +
                "abilityEntityIdPresent=${facts["eventHasAbilityEntityId"]} " +
                "abilityAfterOnStack=${facts["abilityAfterOnStack"]} " +
                "sourceAuthorityMetadata=ABSENT",
        )
    }

    test("LTB/dies trigger emission uses source last-known context but no source incarnation field") {
        val emission = ltbEmission()
        val facts = eventFacts(emission)
        val source = emission.event.sourceId

        emission.event.sourceId shouldBe source
        emission.event.abilityEntityId shouldBe emission.result.newState.stack.single()
        facts["sourceBeforeWitness"] shouldBe true
        facts["sourceAfterWitness"] shouldBe false
        facts["sourceSameIncarnation"] shouldBe false
        facts["sourceChangedIncarnation"] shouldBe false
        facts["eventSourceMatchesStackAbilitySource"] shouldBe true
        facts["eventHasAbilityEntityId"] shouldBe true
        facts["abilityBeforeWitness"] shouldBe false
        facts["abilityAfterWitness"] shouldBe true
        facts["abilityAfterOnStack"] shouldBe true
        facts["pendingSourceMatchesEventSource"] shouldBe true
        facts["pendingTriggeringEntityEntryTimestampPresent"] shouldBe false
        facts["stackTriggeringEntityEntryTimestampPresent"] shouldBe false

        println(
            "TRIGGER_EMISSION_AUTHORITY LTB " +
                "sourceBeforeWitness=${facts["sourceBeforeWitness"]} " +
                "sourceAfterWitness=${facts["sourceAfterWitness"]} " +
                "sameIncarnation=${facts["sourceSameIncarnation"]} " +
                "changedIncarnation=${facts["sourceChangedIncarnation"]} " +
                "abilityEntityIdPresent=${facts["eventHasAbilityEntityId"]} " +
                "abilityAfterOnStack=${facts["abilityAfterOnStack"]} " +
                "sourceAuthorityMetadata=ABSENT",
        )
    }

    test("a source that remains in play emits a distinct stack object without source lifecycle authority") {
        val emission = persistentEmission()
        val facts = eventFacts(emission)
        val source = emission.event.sourceId

        emission.event.sourceId shouldBe source
        emission.event.abilityEntityId shouldBe emission.result.newState.stack.single()
        facts["sourceBeforeWitness"] shouldBe true
        facts["sourceAfterWitness"] shouldBe true
        facts["sourceSameIncarnation"] shouldBe true
        facts["sourceChangedIncarnation"] shouldBe false
        facts["eventSourceMatchesStackAbilitySource"] shouldBe true
        facts["eventHasAbilityEntityId"] shouldBe true
        facts["abilityBeforeWitness"] shouldBe false
        facts["abilityAfterWitness"] shouldBe true
        facts["abilityAfterOnStack"] shouldBe true
        facts["pendingSourceMatchesEventSource"] shouldBe true
        facts["pendingTriggeringEntityEntryTimestampPresent"] shouldBe false
        facts["stackTriggeringEntityEntryTimestampPresent"] shouldBe false

        println(
            "TRIGGER_EMISSION_AUTHORITY PERSISTENT " +
                "sourceBeforeWitness=${facts["sourceBeforeWitness"]} " +
                "sourceAfterWitness=${facts["sourceAfterWitness"]} " +
                "sameIncarnation=${facts["sourceSameIncarnation"]} " +
                "changedIncarnation=${facts["sourceChangedIncarnation"]} " +
                "abilityEntityIdPresent=${facts["eventHasAbilityEntityId"]} " +
                "abilityAfterOnStack=${facts["abilityAfterOnStack"]} " +
                "sourceAuthorityMetadata=ABSENT",
        )
    }
})
