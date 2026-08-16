package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Combat damage assignment flow, driven through the combat resolution board
 * ([CombatResolutionDecision]). When an attacker has trample or multiple blockers with excess
 * power, the chooser gets a board of [com.wingedsheep.engine.core.DamageEdge]s pre-filled with
 * a complete deterministic split; they confirm or re-divide. Damage assignment is part of the
 * board, not a separate order pre-step.
 */
class CombatDamageAssignmentTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        return driver
    }

    test("trample creature with single blocker presents a damage board with a drain edge") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // 5/5 trample attacking into 2/2 blocker
        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Trample Beast")
        val blocker = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(blocker to listOf(trampler))).isSuccess shouldBe true

        // Advance to combat damage — the board pauses here (passPriorityUntil stops at the step
        // boundary before it would auto-resolve the decision).
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        val decision = driver.pendingDecision
        decision.shouldBeInstanceOf<CombatResolutionDecision>()
        val blockerEdge = decision.edges.single { it.sourceId == trampler && it.targetId == blocker }
        val drainEdge = decision.edges.single { it.sourceId == trampler && it.isTrampleDrain }
        blockerEdge.maximum shouldBe 5
        blockerEdge.lethal shouldBe 2
        drainEdge.targetId shouldBe opponent
        // The deterministic default satisfies the trample-specific lethal
        // requirement, then assigns the excess to the defending player.
        blockerEdge.amount shouldBe 2
        drainEdge.amount shouldBe 3

        driver.confirmCombatDamage()
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.findPermanent(opponent, "Grizzly Bears").shouldBeNull()
        driver.getGraveyardCardNames(opponent) shouldContain "Grizzly Bears"
        driver.assertLifeTotal(opponent, 17)
    }

    test("trample creature with custom damage assignment - all to blocker") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Trample Beast")
        val blocker = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(blocker to listOf(trampler))).isSuccess shouldBe true

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        // Override: 4 to blocker, 1 to player.
        driver.submitCombatDamage(mapOf((trampler to blocker) to 4, (trampler to opponent) to 1))

        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.findPermanent(opponent, "Grizzly Bears").shouldBeNull()
        driver.assertLifeTotal(opponent, 19)
    }

    test("multiple blockers: board defaults to a complete legal trample split") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 20, "Mountain" to 20), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // 5/5 trample attacking into two blockers
        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Trample Beast")
        val blocker1 = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")  // 2/2
        val blocker2 = driver.putCreatureOnBattlefield(opponent, "Goblin Guide")    // 2/1
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        // The complete assignment board has no separate blocker-order step.
        driver.declareBlockers(opponent, mapOf(
            blocker1 to listOf(trampler),
            blocker2 to listOf(trampler)
        ))

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        val decision = driver.pendingDecision
        decision.shouldBeInstanceOf<CombatResolutionDecision>()
        val toB1 = decision.edges.single { it.sourceId == trampler && it.targetId == blocker1 }
        val toB2 = decision.edges.single { it.sourceId == trampler && it.targetId == blocker2 }
        val drain = decision.edges.single { it.sourceId == trampler && it.isTrampleDrain }
        // The default assigns trample-specific lethal to each blocker (2 to the
        // 2/2 and 1 to the 2/1), then sends the excess to the defending player.
        toB1.amount shouldBe 2
        toB2.amount shouldBe 1
        drain.amount shouldBe 2

        driver.confirmCombatDamage()
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.findPermanent(opponent, "Grizzly Bears").shouldBeNull()
        driver.findPermanent(opponent, "Goblin Guide").shouldBeNull()
        driver.assertLifeTotal(opponent, 18)
    }

    test("deathtouch + trample assigns 1 damage per blocker, rest to player") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 20, "Swamp" to 20), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // 3/3 deathtouch trample attacking into 2/2 blocker
        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Deathtouch Trampler")
        val blocker = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears") // 2/2
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(blocker to listOf(trampler))).isSuccess shouldBe true

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        val decision = driver.pendingDecision
        decision.shouldBeInstanceOf<CombatResolutionDecision>()
        val blockerEdge = decision.edges.single { it.sourceId == trampler && it.targetId == blocker }
        val drainEdge = decision.edges.single { it.sourceId == trampler && it.isTrampleDrain }
        // With deathtouch, 1 damage is lethal; the remaining two trample over.
        blockerEdge.lethal shouldBe 1
        blockerEdge.amount shouldBe 1
        drainEdge.amount shouldBe 2

        driver.confirmCombatDamage()
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.assertLifeTotal(opponent, 18)
        // 704.5h: blocker received 1 deathtouch damage, so it dies
        driver.findPermanent(opponent, "Grizzly Bears").shouldBeNull()
    }

    test("deathtouch 1/1 kills larger creature via 704.5h") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // 1/1 deathtouch attacking into 5/5 blocker
        val rat = driver.putCreatureOnBattlefield(activePlayer, "Deathtouch Rat")
        val bigCreature = driver.putCreatureOnBattlefield(opponent, "Force of Nature") // 5/5
        driver.removeSummoningSickness(rat)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(rat), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(bigCreature to listOf(rat))).isSuccess shouldBe true

        // Single blocker, no trample: no board, auto-resolves.
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        // 704.5h: 5/5 received 1 deathtouch damage — it dies
        driver.findPermanent(opponent, "Force of Nature").shouldBeNull()
        // 1/1 rat also dies from 5 damage
        driver.findPermanent(activePlayer, "Deathtouch Rat").shouldBeNull()
    }

    test("single blocker without trample - no decision needed, auto-assigned") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // 5/5 without trample attacking into 2/2 blocker - no choice needed
        val attacker = driver.putCreatureOnBattlefield(activePlayer, "Force of Nature")
        val blocker = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(attacker), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(blocker to listOf(attacker))).isSuccess shouldBe true

        // Should pass straight through combat damage with no decision
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.findPermanent(opponent, "Grizzly Bears").shouldBeNull()
        driver.assertLifeTotal(opponent, 20)
        driver.findPermanent(activePlayer, "Force of Nature").shouldNotBeNull()
    }

    test("auto-resolve uses default assignments for trample") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Trample Beast")
        val blocker = driver.putCreatureOnBattlefield(opponent, "Grizzly Bears")
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(blocker to listOf(trampler))).isSuccess shouldBe true

        // passPriorityUntil(POSTCOMBAT_MAIN) auto-resolves the board with its
        // complete deterministic trample plan.
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        // Default assignment: lethal to the blocker, with the excess trampling over.
        driver.findPermanent(opponent, "Grizzly Bears").shouldBeNull()
        driver.assertLifeTotal(opponent, 17)
    }

    test("Daunting Defender - neutral default remains a complete legal assignment") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 20, "Plains" to 20), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        // 5/5 trample attacks, 2/2 Cleric blocks, Daunting Defender prevents 1 damage to Clerics.
        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Trample Beast")
        val clericBlocker = driver.putCreatureOnBattlefield(opponent, "Test Cleric")
        driver.putCreatureOnBattlefield(opponent, "Daunting Defender")
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(clericBlocker to listOf(trampler))).isSuccess shouldBe true

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        val decision = driver.pendingDecision
        decision.shouldBeInstanceOf<CombatResolutionDecision>()
        val blockerEdge = decision.edges.single { it.sourceId == trampler && it.targetId == clericBlocker }
        val drainEdge = decision.edges.single { it.sourceId == trampler && it.isTrampleDrain }
        // Lethal is metadata for the trample-specific rule, not a generic
        // assignment-order gate.
        blockerEdge.lethal shouldBe 2
        blockerEdge.amount shouldBe 2
        drainEdge.amount shouldBe 3

        driver.confirmCombatDamage()
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        // Two assigned - one prevented leaves one damage on the 2/2 Cleric;
        // the three excess damage still reaches the defending player.
        driver.findPermanent(opponent, "Test Cleric").shouldNotBeNull()
        driver.assertLifeTotal(opponent, 17)
        driver.findPermanent(opponent, "Daunting Defender").shouldNotBeNull()
        driver.findPermanent(activePlayer, "Trample Beast").shouldNotBeNull()
    }

    test("Daunting Defender - player assigns minimum (toughness only) and Cleric survives") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 20, "Plains" to 20), startingLife = 20)

        val activePlayer = driver.activePlayer!!
        val opponent = driver.getOpponent(activePlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val trampler = driver.putCreatureOnBattlefield(activePlayer, "Trample Beast")
        val clericBlocker = driver.putCreatureOnBattlefield(opponent, "Test Cleric")
        driver.putCreatureOnBattlefield(opponent, "Daunting Defender")
        driver.removeSummoningSickness(trampler)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(activePlayer, listOf(trampler), opponent).isSuccess shouldBe true

        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(clericBlocker to listOf(trampler))).isSuccess shouldBe true

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        // The player assigns only the order-threshold (toughness 2) to the Cleric to maximize
        // trample. That satisfies CR 510.1c / 702.19b — prevention isn't part of the threshold —
        // but the 1 prevented means only 1 actual damage lands, so the Cleric survives.
        driver.submitCombatDamage(mapOf((trampler to clericBlocker) to 2, (trampler to opponent) to 3))

        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.findPermanent(opponent, "Test Cleric").shouldNotBeNull()
        driver.assertLifeTotal(opponent, 17)
    }
})
