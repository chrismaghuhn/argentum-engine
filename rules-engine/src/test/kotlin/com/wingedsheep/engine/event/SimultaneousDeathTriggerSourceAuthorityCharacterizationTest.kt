package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val simultaneousDeathSource = CardDefinition.creature(
    name = "Simultaneous Death Source",
    manaCost = ManaCost.ZERO,
    subtypes = setOf(Subtype("Test")),
    power = 1,
    toughness = 1,
    oracleText = "Whenever another creature dies, you gain 1 life.",
    script = CardScript.creature(
        TriggeredAbility.create(
            trigger = EventPattern.ZoneChangeEvent(
                from = Zone.BATTLEFIELD,
                to = Zone.GRAVEYARD,
            ),
            binding = TriggerBinding.OTHER,
            effect = Effects.GainLife(1),
        ),
    ),
)

/**
 * Characterizes the simultaneous-death trigger path before its source-authority fix.
 *
 * A dies at the same time as B. The detector already carries A's event-time source stamp from A's
 * last-known information, but the trigger context is built from B's death event. Consequently
 * the current generic source-authority fallback sees different source/triggering IDs and labels
 * A as SAME_INCARNATION instead of the required BEFORE_OBJECT.
 */
class SimultaneousDeathTriggerSourceAuthorityCharacterizationTest : FunSpec({
    fun driver(): GameTestDriver = GameTestDriver().apply {
        registerCards(TestCards.all + simultaneousDeathSource)
        initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            startingLife = 20,
        )
        passPriorityUntil(Step.PRECOMBAT_MAIN)
    }

    fun deathEvent(
        state: GameState,
        entityId: com.wingedsheep.sdk.model.EntityId,
        entityName: String,
        ownerId: com.wingedsheep.sdk.model.EntityId,
    ): ZoneChangeEvent = ZoneChangeEvent(
        entityId = entityId,
        entityName = entityName,
        fromZone = Zone.BATTLEFIELD,
        toZone = Zone.GRAVEYARD,
        ownerId = ownerId,
        lastKnown = EntitySnapshot(
            entityId = entityId,
            controllerId = ownerId,
            typeLine = TypeLine.parse("Creature"),
            cardDefinitionId = entityName,
            objectIncarnationStamp = checkNotNull(state.objectIdentityStamps[entityId]),
        ),
    )

    test("simultaneous self-death trigger currently emits SAME_INCARNATION") {
        val driver = driver()
        val sourceA = driver.putCreatureOnBattlefield(driver.player1, simultaneousDeathSource.name)
        val otherB = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val beforeDeaths = driver.state
        val sourceStamp = checkNotNull(beforeDeaths.objectIdentityStamps[sourceA])

        val afterDeaths = beforeDeaths
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), sourceA)
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), otherB)
            .withoutEntity(sourceA)
            .withoutEntity(otherB)
        val events = listOf(
            deathEvent(beforeDeaths, sourceA, simultaneousDeathSource.name, driver.player1),
            deathEvent(beforeDeaths, otherB, "Grizzly Bears", driver.player1),
        )

        val pending = TriggerDetector(driver.cardRegistry)
            .detectTriggers(afterDeaths, events)
            .single { it.sourceId == sourceA }
        pending.triggerContext.triggeringEntityId shouldBe otherB
        pending.sourceObjectIncarnationStamp shouldBe sourceStamp
        pending.effectiveSourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION

        val result = TriggerProcessor(
            cardRegistry = driver.cardRegistry,
            stackResolver = StackResolver(driver.cardRegistry),
        ).processTriggers(afterDeaths, listOf(pending))
        result.isSuccess shouldBe true
        val emitted = result.events.filterIsInstance<AbilityTriggeredEvent>().single()
        emitted.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.SAME_INCARNATION
        emitted.sourceObjectIncarnationStamp shouldBe sourceStamp
        result.newState.hasEntity(sourceA) shouldBe false
    }
})
