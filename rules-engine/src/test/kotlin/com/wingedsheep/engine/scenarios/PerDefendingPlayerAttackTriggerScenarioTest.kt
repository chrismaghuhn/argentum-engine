package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.DeclaredAttack
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.event.DelayedTriggeredAbility
import com.wingedsheep.engine.event.GlobalGrantedTriggeredAbility
import com.wingedsheep.engine.event.PendingTrigger
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalAttackTriggers
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.TriggeredAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
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

    val otherQualifyingCreature = CardDefinition.creature(
        name = "Attack Group Menace Bear",
        manaCost = ManaCost.parse("{1}{G}"),
        subtypes = setOf(Subtype("Bear")),
        power = 2,
        toughness = 2,
        keywords = setOf(Keyword.MENACE),
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

    val commandZoneWatcher = card("Command Zone Per Player Attack Watcher") {
        manaCost = "{2}"
        typeLine = "Legendary Enchantment"
        triggeredAbility {
            trigger = Triggers.YouAttackPlayerWithFilter(qualifyingFilter)
            triggerZones = setOf(Zone.COMMAND)
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

    val delayedWatcher = card("Per Player Delayed Attack Watcher") {
        manaCost = "{2}"
        typeLine = "Enchantment"
    }

    val attackDoubler = card("Attack Group Doubler") {
        manaCost = "{2}"
        typeLine = "Enchantment"
        staticAbility {
            ability = AdditionalAttackTriggers(
                attackerFilter = GameObjectFilter.Creature.withSubtype(Subtype("Warrior")),
            )
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
                otherQualifyingCreature,
                testWalker,
                perPlayerWatcher,
                commandZoneWatcher,
                perPlayerMinTwoWatcher,
                declarationWatcher,
                existingFilteredDeclarationWatcher,
                perAttackerWatcher,
                delayedWatcher,
                attackDoubler,
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

    test("command-zone per-player attack triggers fan out with player bindings") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val sourceId = pod.driver.putCardInCommandZone(pod.attackingPlayer, commandZoneWatcher.name)

        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(
                pod.driver.state,
                listOf(attackEvent(pod, first to pod.playerB, second to pod.playerC)),
            )
            .filter { it.sourceId == sourceId }

        pending shouldHaveSize 2
        pending.map { it.triggerContext.triggeringPlayerId }.toSet() shouldBe
            setOf(pod.playerB, pod.playerC)
    }

    test("global-granted per-player attack triggers fan out with player bindings") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val sourceId = EntityId.generate()
        val global = GlobalGrantedTriggeredAbility(
            ability = TriggeredAbility.create(
                trigger = EventPattern.YouAttackPlayerEvent(attackerFilter = qualifyingFilter),
                binding = TriggerBinding.ANY,
                effect = Effects.DrawCards(1),
            ),
            controllerId = pod.attackingPlayer,
            sourceId = sourceId,
            sourceName = "Global Per Player Attack Watcher",
            duration = Duration.Permanent,
        )

        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(
                pod.driver.state.copy(globalGrantedTriggeredAbilities = listOf(global)),
                listOf(attackEvent(pod, first to pod.playerB, second to pod.playerC)),
            )
            .filter { it.sourceId == sourceId }

        pending shouldHaveSize 2
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

    test("serialized game-state fork preserves per-player grouping and bindings") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, perPlayerWatcher.name)
        val event = attackEvent(pod, first to pod.playerB, second to pod.playerC)
        val detector = TriggerDetector(pod.driver.cardRegistry)
        val json = Json {
            serializersModule = engineSerializersModule
            allowStructuredMapKeys = true
        }

        fun bindingOrder(state: GameState): List<EntityId> =
            detector.detectTriggers(state, listOf(event))
                .filter { it.sourceName == perPlayerWatcher.name }
                .map { requireNotNull(it.triggerContext.triggeringPlayerId) }

        val originalOrder = bindingOrder(pod.driver.state)
        val restoredState = json.decodeFromString<GameState>(
            json.encodeToString(GameState.serializer(), pod.driver.state)
        )

        bindingOrder(restoredState) shouldBe originalOrder
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

    test("additional attack trigger doubling remains scoped to the bound attacked player") {
        val pod = pod()
        val warrior = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            qualifyingCreature.name,
        )
        val bear = pod.driver.putCreatureOnBattlefield(
            pod.attackingPlayer,
            otherQualifyingCreature.name,
        )
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, perPlayerWatcher.name)
        pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, attackDoubler.name)

        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(
                pod.driver.state,
                listOf(attackEvent(pod, warrior to pod.playerB, bear to pod.playerC)),
            )
            .filter { it.sourceName == perPlayerWatcher.name }

        pending shouldHaveSize 3
        pending.count { it.triggerContext.triggeringPlayerId == pod.playerB } shouldBe 2
        pending.count { it.triggerContext.triggeringPlayerId == pod.playerC } shouldBe 1
    }

    test("event-based per-player delayed attack trigger fans out with player bindings") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val sourceId = pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, delayedWatcher.name)
        val delayedId = "per-player-delayed"
        val delayed = DelayedTriggeredAbility(
            id = delayedId,
            effect = Effects.DrawCards(1),
            sourceId = sourceId,
            sourceName = delayedWatcher.name,
            controllerId = pod.attackingPlayer,
            trigger = TriggerSpec(
                event = EventPattern.YouAttackPlayerEvent(
                    attackerFilter = qualifyingFilter,
                ),
                binding = TriggerBinding.ANY,
            ),
        )

        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(
                pod.driver.state.copy(delayedTriggers = listOf(delayed)),
                listOf(attackEvent(pod, first to pod.playerB, second to pod.playerC)),
            )
            .filter { it.sourceName == delayedWatcher.name }

        pending shouldHaveSize 2
        pending.map { it.triggerContext.triggeringPlayerId }.toSet() shouldBe
            setOf(pod.playerB, pod.playerC)
    }

    test("ATTACK-GROUP-DELAYED-01: ambiguous fire-once player matches externalize occurrence choice") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val sourceId = pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, delayedWatcher.name)
        val delayedId = "per-player-delayed-fire-once"
        val delayed = DelayedTriggeredAbility(
            id = delayedId,
            effect = Effects.DrawCards(1),
            sourceId = sourceId,
            sourceName = delayedWatcher.name,
            controllerId = pod.attackingPlayer,
            trigger = TriggerSpec(
                event = EventPattern.YouAttackPlayerEvent(
                    attackerFilter = qualifyingFilter,
                ),
                binding = TriggerBinding.ANY,
            ),
            fireOnce = true,
        )

        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(
                pod.driver.state.copy(delayedTriggers = listOf(delayed)),
                listOf(attackEvent(pod, first to pod.playerB, second to pod.playerC)),
            )
            .filter { it.sourceName == delayedWatcher.name }

        // CR 603.7b is now an explicit controller choice. The detector emits one transient marker
        // carrying every per-occurrence context; TriggerProcessor turns it into a pending choice.
        pending shouldHaveSize 1
        pending.single().occurrenceChoice.map { it.triggerContext.triggeringPlayerId }.toSet() shouldBe
            setOf(pod.playerB, pod.playerC)

        // The marker itself may be queued below another continuation before TriggerProcessor
        // sees it, so its candidate payload must survive ordinary engine-state serialization.
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val encoded = json.encodeToString(PendingTrigger.serializer(), pending.single())
        json.decodeFromString(PendingTrigger.serializer(), encoded) shouldBe pending.single()
    }

    test("fire-once delayed trigger does not combine separate events into one occurrence choice") {
        val pod = pod()
        val attacker = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val sourceId = pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, delayedWatcher.name)
        val delayedId = "per-player-delayed-first-event"
        val delayed = DelayedTriggeredAbility(
            id = delayedId,
            effect = Effects.DrawCards(1),
            sourceId = sourceId,
            sourceName = delayedWatcher.name,
            controllerId = pod.attackingPlayer,
            trigger = TriggerSpec(
                event = EventPattern.YouAttackPlayerEvent(attackerFilter = qualifyingFilter),
                binding = TriggerBinding.ANY,
            ),
            fireOnce = true,
        )

        val pending = TriggerDetector(pod.driver.cardRegistry).detectTriggers(
            pod.driver.state.copy(delayedTriggers = listOf(delayed)),
            listOf(
                attackEvent(pod, attacker to pod.playerB),
                attackEvent(pod, attacker to pod.playerC),
            ),
        ).filter { it.sourceName == delayedWatcher.name }

        pending shouldHaveSize 1
        pending.single().occurrenceChoice shouldBe emptyList()
        pending.single().triggerContext.triggeringPlayerId shouldBe pod.playerB
        pending.single().consumesDelayedTriggerId shouldBe delayedId
    }

    test("ATTACK-GROUP-DELAYED-02: selected occurrence keeps context and consumes fire-once exactly once") {
        val pod = pod()
        val first = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val second = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val sourceId = pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, delayedWatcher.name)
        val delayedId = "per-player-delayed-choice"
        val delayed = DelayedTriggeredAbility(
            id = delayedId,
            effect = Effects.DrawCards(1),
            sourceId = sourceId,
            sourceName = delayedWatcher.name,
            controllerId = pod.attackingPlayer,
            trigger = TriggerSpec(
                event = EventPattern.YouAttackPlayerEvent(attackerFilter = qualifyingFilter),
                binding = TriggerBinding.ANY,
            ),
            fireOnce = true,
        )
        val state = pod.driver.state.copy(delayedTriggers = listOf(delayed))
        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(state, listOf(attackEvent(pod, first to pod.playerB, second to pod.playerC)))
            .filter { it.sourceName == delayedWatcher.name }
        val services = EngineServices(pod.driver.cardRegistry)
        val paused = services.triggerProcessor.processTriggers(state, pending)

        paused.isPaused shouldBe true
        val decision = paused.pendingDecision as ChooseOptionDecision
        decision.options shouldHaveSize 2
        decision.options shouldBe listOf(
            "Trigger for player ${pod.playerB.value}",
            "Trigger for player ${pod.playerC.value}"
        )
        decision.optionMetadata.map { it.triggeringPlayerId } shouldBe listOf(pod.playerB, pod.playerC)
        decision.optionMetadata.map { it.id } shouldBe listOf(pod.playerB.value, pod.playerC.value)
        decision.optionMetadata.map { it.description } shouldBe listOf(
            "Trigger for player ${pod.playerB.value}",
            "Trigger for player ${pod.playerC.value}"
        )
        val continuation = paused.state.peekContinuation()
            ?: error("Delayed-trigger occurrence choice did not leave a continuation")

        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }

        val wrongOwner = ActionProcessor(pod.driver.cardRegistry).process(
            paused.state,
            SubmitDecision(
                pod.playerB,
                OptionChosenResponse(decision.id, optionIndex = 0)
            )
        ).result
        wrongOwner.error.shouldNotBeNull()
        wrongOwner.state shouldBe paused.state

        val restoredPausedState = json.decodeFromString(
            GameState.serializer(),
            json.encodeToString(GameState.serializer(), paused.state)
        )
        restoredPausedState shouldBe paused.state
        (restoredPausedState.pendingDecision as ChooseOptionDecision).optionMetadata shouldBe
            decision.optionMetadata

        val processor = ActionProcessor(pod.driver.cardRegistry)
        val invalid = processor.process(
            paused.state,
            SubmitDecision(
                pod.attackingPlayer,
                OptionChosenResponse(decision.id, optionIndex = 99)
            )
        ).result
        invalid.error shouldBe "Invalid option index: 99"
        invalid.state shouldBe paused.state

        val resumed = processor.process(
            paused.state,
            SubmitDecision(
                pod.attackingPlayer,
                OptionChosenResponse(decision.id, optionIndex = 1)
            )
        ).result
        resumed.error shouldBe null
        resumed.state.delayedTriggers.any { it.id == delayedId } shouldBe false
        val stackTop = resumed.state.getTopOfStack()
        val stackTrigger = stackTop?.let {
            resumed.state.getEntity(it)?.get<com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent>()
        }
        stackTrigger?.triggeringPlayerId shouldBe pod.playerC

        val encoded = json.encodeToString(ContinuationFrame.serializer(), continuation)
        val decoded = json.decodeFromString(ContinuationFrame.serializer(), encoded)
        decoded shouldBe continuation

        val replayed = processor.process(
            restoredPausedState,
            SubmitDecision(
                pod.attackingPlayer,
                OptionChosenResponse(decision.id, optionIndex = 0)
            )
        ).result
        replayed.error shouldBe null
        replayed.state.delayedTriggers.any { it.id == delayedId } shouldBe false
        replayed.state.getTopOfStack()?.let {
            replayed.state.getEntity(it)
                ?.get<com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent>()
                ?.triggeringPlayerId
        } shouldBe pod.playerB

        val forkB = processor.process(
            paused.state.copy(),
            SubmitDecision(
                pod.attackingPlayer,
                OptionChosenResponse(decision.id, optionIndex = 0)
            )
        ).result
        val forkC = processor.process(
            paused.state.copy(),
            SubmitDecision(
                pod.attackingPlayer,
                OptionChosenResponse(decision.id, optionIndex = 1)
            )
        ).result
        forkB.state.getTopOfStack()?.let {
            forkB.state.getEntity(it)
                ?.get<com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent>()
                ?.triggeringPlayerId
        } shouldBe pod.playerB
        forkC.state.getTopOfStack()?.let {
            forkC.state.getEntity(it)
                ?.get<com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent>()
                ?.triggeringPlayerId
        } shouldBe pod.playerC
    }

    test("watched-target scope fails closed for per-player delayed attack triggers") {
        val pod = pod()
        val attacker = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, qualifyingCreature.name)
        val watchedEntity = pod.driver.putCreatureOnBattlefield(pod.attackingPlayer, otherQualifyingCreature.name)
        val sourceId = pod.driver.putPermanentOnBattlefield(pod.attackingPlayer, delayedWatcher.name)
        val delayedId = "per-player-delayed-watched"
        val delayed = DelayedTriggeredAbility(
            id = delayedId,
            effect = Effects.DrawCards(1),
            sourceId = sourceId,
            sourceName = delayedWatcher.name,
            controllerId = pod.attackingPlayer,
            trigger = TriggerSpec(
                event = EventPattern.YouAttackPlayerEvent(
                    attackerFilter = qualifyingFilter,
                ),
                binding = TriggerBinding.ANY,
            ),
            watchedEntityId = watchedEntity,
            fireOnce = true,
        )

        val pending = TriggerDetector(pod.driver.cardRegistry)
            .detectTriggers(
                pod.driver.state.copy(delayedTriggers = listOf(delayed)),
                listOf(attackEvent(pod, attacker to pod.playerB)),
            )
            .filter { it.consumesDelayedTriggerId == delayedId }

        pending.shouldBeEmpty()
    }
})
