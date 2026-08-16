package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.DeclaredAttack
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.event.PendingTrigger
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Generic acceptance matrix for "whenever you attack a player with one or more
 * qualifying creatures".
 *
 * This is intentionally independent of Akiri. The qualifying filter is menace,
 * which is an ordinary reusable creature filter; the production primitive must
 * not know why a creature qualifies.
 */
class PerDefendingPlayerAttackTriggerScenarioTest : FunSpec({

    val qualifyingFilter = GameObjectFilter.Creature
        .withKeyword(Keyword.MENACE)
        .youControl()

    val qualifyingCreature = CardDefinition.creature(
        name = "Attack Group Menace",
        manaCost = ManaCost.parse("{1}{R}"),
        subtypes = setOf(Subtype("Warrior")),
        power = 2,
        toughness = 2,
        keywords = setOf(Keyword.MENACE),
    )

    val nonQualifyingCreature = CardDefinition.creature(
        name = "Attack Group Vanilla",
        manaCost = ManaCost.parse("{1}{G}"),
        subtypes = setOf(Subtype("Bear")),
        power = 2,
        toughness = 2,
    )

    val testWalker = card("Attack Group Walker") {
        manaCost = "{2}"
        typeLine = "Legendary Planeswalker — Tester"
        startingLoyalty = 3
        loyaltyAbility(1) {
            effect = Effects.GainLife(1)
        }
    }

    val perPlayerWatcher = card("Per Player Attack Watcher") {
        manaCost = "{2}"
        typeLine = "Enchantment"
        triggeredAbility {
            trigger = Triggers.YouAttackPlayerWithFilter(qualifyingFilter)
            effect = Effects.DrawCards(1)
        }
    }

    val perPlayerMinTwoWatcher = card("Per Player Attack Min Two Watcher") {
        manaCost = "{2}"
        typeLine = "Enchantment"
        triggeredAbility {
            trigger = TriggerSpec(
                event = EventPattern.YouAttackPlayerEvent(
                    minAttackers = 2,
                    attackerFilter = qualifyingFilter,
                ),
                binding = TriggerBinding.ANY,
            )
            effect = Effects.DrawCards(1)
        }
    }

    val declarationWatcher = card("Declaration Wide Attack Watcher") {
        manaCost = "{2}"
        typeLine = "Enchantment"
        triggeredAbility {
            trigger = Triggers.YouAttack
            effect = Effects.DrawCards(1)
        }
    }

    val existingFilteredDeclarationWatcher = card("Existing Filtered Attack Watcher") {
        manaCost = "{2}"
        typeLine = "Enchantment"
        triggeredAbility {
            trigger = Triggers.YouAttackWithFilter(qualifyingFilter)
            effect = Effects.DrawCards(1)
        }
    }

    val perAttackerWatcher = card("Per Attacker Attack Watcher") {
        manaCost = "{2}"
        typeLine = "Enchantment"
        triggeredAbility {
            trigger = TriggerSpec(
                event = EventPattern.AttackEvent(filter = qualifyingFilter),
                binding = TriggerBinding.ANY,
            )
            effect = Effects.DrawCards(1)
        }
    }

    data class Pod(
        val driver: GameTestDriver,
        val attackingPlayer: EntityId,
        val playerB: EntityId,
        val playerC: EntityId,
    )

    fun pod(): Pod {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + listOf(
                qualifyingCreature,
                nonQualifyingCreature,
                testWalker,
                perPlayerWatcher,
                perPlayerMinTwoWatcher,
                declarationWatcher,
                existingFilteredDeclarationWatcher,
                perAttackerWatcher,
            )
        )
        val players = driver.initMultiplayer(
            decks = listOf(
                Deck.of("Forest" to 40),
                Deck.of("Forest" to 40),
                Deck.of("Forest" to 40),
            ),
            skipMulligans = true,
            startingPlayer = 0,
        )
        return Pod(driver, players[0], players[1], players[2])
    }

    fun attackEvent(pod: Pod, vararg attacks: Pair<EntityId, EntityId>): AttackersDeclaredEvent {
        val entries = attacks.toList()
        return AttackersDeclaredEvent(
            attackers = entries.map { it.first },
            attackingPlayerId = pod.attackingPlayer,
            declaredAttacks = entries.map { (attackerId, defenderId) ->
                DeclaredAttack(attackerId = attackerId, defenderId = defenderId)
            },
        )
    }

    fun triggers(
        pod: Pod,
        watcherName: String,
        event: AttackersDeclaredEvent,
    ): List<PendingTrigger> {
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, watcherName)
        return TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(pod.driver.state, listOf(event))
            .filter { it.sourceName == watcherName }
    }

    test("ATTACK-GROUP-01: one qualifying attacker to one player produces one trigger") {
        val pod = pod()
        val attacker = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            qualifyingCreature.name,
        )

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod, attacker to pod.playerB),
        )

        pending shouldHaveSize 1
        pending.single().triggerContext.triggeringPlayerId shouldBe pod.playerB
    }

    test("ATTACK-GROUP-02: two qualifying attackers to the same player produce one trigger") {
        val pod = pod()
        val attackers = listOf(
            pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name),
            pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name),
        )

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod, attackers[0] to pod.playerB, attackers[1] to pod.playerB),
        )

        pending shouldHaveSize 1
        pending.single().triggerContext.triggeringPlayerId shouldBe pod.playerB
    }

    test("ATTACK-GROUP-03: qualifying attackers to distinct players produce two triggers") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        )

        pending shouldHaveSize 2
        pending.map { it.triggerContext.triggeringPlayerId }.toSet() shouldBe
            setOf(pod.playerB, pod.playerC)
    }

    test("ATTACK-GROUP-04: B/B/C grouping produces exactly one trigger for B and one for C") {
        val pod = pod()
        val attackers = List(3) {
            pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        }

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(
                pod,
                attackers[0] to pod.playerB,
                attackers[1] to pod.playerB,
                attackers[2] to pod.playerC,
            ),
        )

        pending shouldHaveSize 2
        pending.map { it.triggerContext.triggeringPlayerId }.toSet() shouldBe
            setOf(pod.playerB, pod.playerC)
    }

    test("ATTACK-GROUP-05: attacking a planeswalker does not count as attacking its controller") {
        val pod = pod()
        val walker = pod.driver.putPermanentOnBattlefield(pod.playerB, testWalker.name)
        val attacker = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            qualifyingCreature.name,
        )

        triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod, attacker to walker),
        ).shouldBeEmpty()
    }

    test("ATTACK-GROUP-06: player target counts while planeswalker target does not") {
        val pod = pod()
        val walker = pod.driver.putPermanentOnBattlefield(pod.playerC, testWalker.name)
        val playerAttacker = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            qualifyingCreature.name,
        )
        val walkerAttacker = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            qualifyingCreature.name,
        )

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(
                pod,
                playerAttacker to pod.playerB,
                walkerAttacker to walker,
            ),
        )

        pending shouldHaveSize 1
        pending.single().triggerContext.triggeringPlayerId shouldBe pod.playerB
    }

    test("ATTACK-GROUP-07: qualification happens before grouping") {
        val pod = pod()
        val qualifying = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            qualifyingCreature.name,
        )
        val nonQualifying = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            nonQualifyingCreature.name,
        )

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(
                pod,
                qualifying to pod.playerB,
                nonQualifying to pod.playerC,
            ),
        )

        pending shouldHaveSize 1
        pending.single().triggerContext.triggeringPlayerId shouldBe pod.playerB
    }

    test("ATTACK-GROUP-08: multiple non-qualifying attackers produce no triggers") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            nonQualifyingCreature.name,
        )
        val second = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            nonQualifyingCreature.name,
        )

        triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        ).shouldBeEmpty()
    }

    test("ATTACK-GROUP-09: no attackers produce no triggers") {
        val pod = pod()

        triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod),
        ).shouldBeEmpty()
    }

    test("ATTACK-GROUP-10: each trigger binds its attacked player in TriggerContext") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)

        val pending = triggers(
            pod,
            perPlayerWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        )

        pending.map { it.triggerContext.triggeringPlayerId }.toSet() shouldBe
            setOf(pod.playerB, pod.playerC)
    }

    test("minAttackers is applied per attacked player after qualification") {
        val pod = pod()
        val attackers = List(3) {
            pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        }

        val pending = triggers(
            pod,
            perPlayerMinTwoWatcher.name,
            attackEvent(
                pod,
                attackers[0] to pod.playerB,
                attackers[1] to pod.playerB,
                attackers[2] to pod.playerC,
            ),
        )

        pending shouldHaveSize 1
        pending.single().triggerContext.triggeringPlayerId shouldBe pod.playerB
    }

    test("minAttackers greater than one rejects one qualifying attacker per player") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)

        triggers(
            pod,
            perPlayerMinTwoWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        ).shouldBeEmpty()
    }

    test("reversed event declaration order has the same deterministic player-trigger order") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, perPlayerWatcher.name)
        val detector = TriggerDetector(pod.driver.cardRegistry)

        val forward = detector
            .detectTriggers(
                pod.driver.state,
                listOf(attackEvent(pod, first to pod.playerB, second to pod.playerC)),
            )
            .filter { it.sourceName == perPlayerWatcher.name }
            .map { it.triggerContext.triggeringPlayerId }

        val reverse = detector
            .detectTriggers(
                pod.driver.state,
                listOf(attackEvent(pod, second to pod.playerC, first to pod.playerB)),
            )
            .filter { it.sourceName == perPlayerWatcher.name }
            .map { it.triggerContext.triggeringPlayerId }

        reverse shouldBe forward
    }

    test("equivalent declaration map iteration orders produce identical serialized attack events") {
        val pod = pod()
        pod.driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        pod.driver.removeSummoningSickness(first)
        pod.driver.removeSummoningSickness(second)
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, perPlayerWatcher.name)
        pod.driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        val baseState = pod.driver.state
        val processor = ActionProcessor(pod.driver.cardRegistry)

        val forward = processor.process(
            baseState,
            DeclareAttackers(
                pod.attackingPlayer,
                linkedMapOf(first to pod.playerB, second to pod.playerC),
            ),
        ).result
        val reverse = processor.process(
            baseState,
            DeclareAttackers(
                pod.attackingPlayer,
                linkedMapOf(second to pod.playerC, first to pod.playerB),
            ),
        ).result
        val forwardEvent = forward.events.filterIsInstance<AttackersDeclaredEvent>().single()
        val reverseEvent = reverse.events.filterIsInstance<AttackersDeclaredEvent>().single()
        val json = Json { serializersModule = engineSerializersModule }

        json.encodeToString(GameEvent.serializer(), forwardEvent) shouldBe
            json.encodeToString(GameEvent.serializer(), reverseEvent)

        val detector = TriggerDetector(pod.driver.cardRegistry)
        val forwardOrder = detector.detectTriggers(baseState, listOf(forwardEvent))
            .filter { it.sourceName == perPlayerWatcher.name }
            .map { it.triggerContext.triggeringPlayerId }
        val reverseOrder = detector.detectTriggers(baseState, listOf(reverseEvent))
            .filter { it.sourceName == perPlayerWatcher.name }
            .map { it.triggerContext.triggeringPlayerId }
        reverseOrder shouldBe forwardOrder
    }

    test("equivalent immutable state forks preserve per-player grouping and bindings") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, perPlayerWatcher.name)
        val event = attackEvent(pod, first to pod.playerB, second to pod.playerC)
        val detector = TriggerDetector(pod.driver.cardRegistry)

        fun bindingOrder(state: com.wingedsheep.engine.state.GameState): List<EntityId> =
            detector.detectTriggers(state, listOf(event))
                .filter { it.sourceName == perPlayerWatcher.name }
                .map { requireNotNull(it.triggerContext.triggeringPlayerId) }

        val originalOrder = bindingOrder(pod.driver.state)
        val forkOrder = bindingOrder(pod.driver.state.copy())

        forkOrder shouldBe originalOrder
    }

    test("historical attack events without declared target data fail closed") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val legacyEvent = AttackersDeclaredEvent(
            attackers = listOf(first, second),
            attackingPlayerId = pod.attackingPlayer,
            attackersAgainstPlayer = setOf(first, second),
        )

        triggers(pod, perPlayerWatcher.name, legacyEvent).shouldBeEmpty()
    }

    test("ordinary declaration-wide attack trigger remains one trigger") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)

        triggers(
            pod,
            declarationWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        ) shouldHaveSize 1
    }

    test("existing filtered declaration-wide attack trigger remains one trigger") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)

        triggers(
            pod,
            existingFilteredDeclarationWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        ) shouldHaveSize 1
    }

    test("existing per-attacker attack trigger remains one trigger per qualifying attacker") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)

        triggers(
            pod,
            perAttackerWatcher.name,
            attackEvent(pod, first to pod.playerB, second to pod.playerC),
        ) shouldHaveSize 2
    }
})
