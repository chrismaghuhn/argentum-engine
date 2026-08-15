package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import com.wingedsheep.sdk.serialization.CardSerialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Characterization tests for the existing generic named-counter LKI path.
 *
 * The test deliberately uses BOUNTY, the counter vocabulary needed by Chevill,
 * but does not implement Chevill. The named counter itself is the mark: the
 * trigger reads the dying object's frozen snapshot rather than maintaining a
 * source-relative cross-zone tracker.
 */
class NamedCounterLkiTest : FunSpec({

    fun watcher(filter: GameObjectFilter): CardDefinition = card("Named Counter LKI Watcher") {
        manaCost = "{2}"
        colorIdentity = ""
        typeLine = "Enchantment"
        oracleText = "Whenever a matching permanent dies, draw a card."

        spell {}

        triggeredAbility {
            trigger = TriggerSpec(
                event = EventPattern.ZoneChangeEvent(
                    filter = filter,
                    from = Zone.BATTLEFIELD,
                    to = Zone.GRAVEYARD,
                ),
                binding = TriggerBinding.OTHER,
            )
            effect = Effects.DrawCards(1)
        }
    }

    fun createDriver(filter: GameObjectFilter? = null): GameTestDriver {
        val driver = GameTestDriver()
        val cards = buildList {
            addAll(TestCards.all)
            if (filter != null) add(watcher(filter))
        }
        driver.registerCards(cards)
        driver.initMirrorMatch(deck = Deck.of("Island" to 40))
        if (filter != null) {
            driver.putPermanentOnBattlefield(driver.player1, "Named Counter LKI Watcher")
        }
        return driver
    }

    fun bountyFilter() = GameObjectFilter.Permanent
        .opponentControls()
        .withCounter(Counters.BOUNTY)

    fun event(
        driver: GameTestDriver,
        counters: Map<String, Int>,
        controllerId: EntityId = driver.player2,
        ownerId: EntityId = driver.player2,
        entityId: EntityId = EntityId.generate(),
        typeLine: TypeLine = TypeLine.parse("Creature"),
    ): ZoneChangeEvent = ZoneChangeEvent(
        entityId = entityId,
        entityName = "Marked Creature",
        fromZone = Zone.BATTLEFIELD,
        toZone = Zone.GRAVEYARD,
        ownerId = ownerId,
        lastKnown = EntitySnapshot(
            entityId = entityId,
            controllerId = controllerId,
            typeLine = typeLine,
            counters = counters,
        ),
    )

    fun zoneChangeTriggers(driver: GameTestDriver, event: GameEvent): List<*> =
        TriggerDetector(driver.cardRegistry)
            .detectTriggers(driver.state, listOf(event))
            .filter { it.ability.trigger is EventPattern.ZoneChangeEvent }

    fun destroyWithDoomBlade(driver: GameTestDriver, target: EntityId) {
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val doomBlade = driver.putCardInHand(driver.player1, "Doom Blade")
        driver.giveMana(driver.player1, com.wingedsheep.sdk.core.Color.BLACK, 2)
        driver.castSpell(driver.player1, doomBlade, targets = listOf(target))
        driver.bothPass()
    }

    test("LKI-01: BOUNTY and +1/+1 survive into the actual death snapshot") {
        val driver = createDriver(bountyFilter())
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        driver.addComponent(
            target,
            CountersComponent()
                .withAdded(CounterType.BOUNTY, 1)
                .withAdded(CounterType.PLUS_ONE_PLUS_ONE, 3),
        )

        destroyWithDoomBlade(driver, target)

        val death = driver.events
            .filterIsInstance<ZoneChangeEvent>()
            .last { it.entityId == target && it.fromZone == Zone.BATTLEFIELD }
        death.lastKnown.shouldNotBeNull().counters[Counters.BOUNTY] shouldBe 1
        death.lastKnown.counters[Counters.PLUS_ONE_PLUS_ONE] shouldBe 3
        zoneChangeTriggers(driver, death) shouldHaveSize 1
    }

    test("LKI-02: a different counter does not match BOUNTY") {
        val driver = createDriver(bountyFilter())

        zoneChangeTriggers(
            driver,
            event(driver, mapOf(Counters.PLUS_ONE_PLUS_ONE to 3)),
        ).shouldBeEmpty()
    }

    test("LKI-03: a battlefield-exit query ignores current-zone counters") {
        val driver = createDriver(bountyFilter())
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        driver.addComponent(
            target,
            CountersComponent().withAdded(CounterType.BOUNTY, 1),
        )

        // The live object still has BOUNTY, but the frozen pre-exit snapshot does not.
        val exit = event(driver, emptyMap(), entityId = target)
        zoneChangeTriggers(driver, exit).shouldBeEmpty()
    }

    test("LKI-04: removing BOUNTY before death makes the death query false") {
        val driver = createDriver(bountyFilter())
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        driver.addComponent(
            target,
            CountersComponent().withAdded(CounterType.BOUNTY, 1),
        )
        driver.addComponent(
            target,
            CountersComponent(mapOf(CounterType.PLUS_ONE_PLUS_ONE to 3)),
        )

        destroyWithDoomBlade(driver, target)

        val death = driver.events
            .filterIsInstance<ZoneChangeEvent>()
            .last { it.entityId == target && it.fromZone == Zone.BATTLEFIELD }
        death.lastKnown.shouldNotBeNull().counters[Counters.BOUNTY] shouldBe null
        zoneChangeTriggers(driver, death).shouldBeEmpty()
    }

    test("LKI-05: BOUNTY matches while FINALITY does not in a multi-counter snapshot") {
        val counters = mapOf(
            Counters.BOUNTY to 1,
            Counters.PLUS_ONE_PLUS_ONE to 3,
        )
        val bountyDriver = createDriver(bountyFilter())
        zoneChangeTriggers(bountyDriver, event(bountyDriver, counters)) shouldHaveSize 1

        val finalityDriver = createDriver(
            GameObjectFilter.Permanent
                .opponentControls()
                .withCounter(Counters.FINALITY),
        )
        zoneChangeTriggers(finalityDriver, event(finalityDriver, counters)).shouldBeEmpty()
    }

    test("LKI-06: simultaneous deaths retain independent counter answers") {
        val driver = createDriver(bountyFilter())
        val bountyDeath = event(driver, mapOf(Counters.BOUNTY to 1))
        val unmarkedDeath = event(driver, mapOf(Counters.PLUS_ONE_PLUS_ONE to 3))

        TriggerDetector(driver.cardRegistry)
            .detectTriggers(driver.state, listOf(bountyDeath, unmarkedDeath))
            .filter { it.ability.trigger is EventPattern.ZoneChangeEvent } shouldHaveSize 1
    }

    test("LKI-07: opponentControls uses the last-known controller") {
        val driver = createDriver(bountyFilter())

        zoneChangeTriggers(
            driver,
            event(driver, mapOf(Counters.BOUNTY to 1), controllerId = driver.player2),
        ) shouldHaveSize 1
        zoneChangeTriggers(
            driver,
            event(driver, mapOf(Counters.BOUNTY to 1), controllerId = driver.player1),
        ).shouldBeEmpty()
    }

    test("LKI-08: a source leaving simultaneously still sees the marked death") {
        val driver = GameTestDriver()
        val wipe = CardDefinition.sorcery(
            name = "LKI Named Counter Wipe",
            manaCost = ManaCost.parse("{3}{W}{W}"),
            oracleText = "Destroy all nonland permanents.",
            script = CardScript.spell(
                effect = Effects.DestroyAll(
                    filter = GameObjectFilter.NonlandPermanent,
                    noRegenerate = true,
                ),
            ),
        )
        val filter = bountyFilter()
        driver.registerCards(TestCards.all + listOf(watcher(filter), wipe))
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        driver.putPermanentOnBattlefield(driver.player1, "Named Counter LKI Watcher")
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        driver.addComponent(
            target,
            CountersComponent().withAdded(CounterType.BOUNTY, 1),
        )
        val boardWipe = driver.putCardInHand(driver.player1, wipe.name)
        driver.giveMana(driver.player1, com.wingedsheep.sdk.core.Color.WHITE, 5)
        driver.castSpell(driver.player1, boardWipe)
        driver.bothPass()

        // The watcher and the marked creature left in the same batch. Trigger
        // detection must use the source's LKI and the target's own LKI snapshot.
        driver.stackSize shouldBe 1
    }

    test("LKI-09: the frozen arbitrary counter map round-trips") {
        val snapshot = EntitySnapshot(
            entityId = EntityId("marked-permanent"),
            counters = mapOf(
                Counters.BOUNTY to 1,
                Counters.PLUS_ONE_PLUS_ONE to 3,
            ),
        )
        val encoded = CardSerialization.json.encodeToString(EntitySnapshot.serializer(), snapshot)
        CardSerialization.json.decodeFromString(EntitySnapshot.serializer(), encoded) shouldBe snapshot
    }

    test("LKI-10: existing +1/+1 and any-counter paths remain independent") {
        val plusOneDriver = createDriver(
            GameObjectFilter.Permanent
                .opponentControls()
                .withCounter(Counters.PLUS_ONE_PLUS_ONE),
        )
        val plusOneEvent = event(plusOneDriver, mapOf(Counters.PLUS_ONE_PLUS_ONE to 3))
        zoneChangeTriggers(plusOneDriver, plusOneEvent) shouldHaveSize 1

        val anyCounterDriver = createDriver(
            GameObjectFilter.Permanent
                .opponentControls()
                .withAnyCounter(),
        )
        zoneChangeTriggers(
            anyCounterDriver,
            event(anyCounterDriver, mapOf(Counters.FINALITY to 1)),
        ) shouldHaveSize 1
    }
})
