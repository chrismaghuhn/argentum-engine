package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.legalactions.RulesBlockRequirement
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression coverage for the CR 509.1c requirement-instance semantics in the blocker validator.
 *
 * Requirements are counted as instances, not as a duplicate-free set of affected attackers.
 * These cases retain duplicate instances and verify that competing requirements are evaluated
 * through the Rules-owned maximum-satisfaction calculation.
 */
class BlockerRequirementMultiplicityCharacterizationTest : FunSpec({

    fun combatWith(
        attackerCount: Int,
        blockerCount: Int,
    ): Triple<GameTestDriver, List<com.wingedsheep.sdk.model.EntityId>, List<com.wingedsheep.sdk.model.EntityId>> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(Deck.of("Forest" to 40))

        val attackerPlayer = driver.activePlayer!!
        val defendingPlayer = driver.getOpponent(attackerPlayer)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val attackers = List(attackerCount) {
            driver.putCreatureOnBattlefield(attackerPlayer, "Grizzly Bears")
        }
        val blockers = List(blockerCount) {
            driver.putCreatureOnBattlefield(defendingPlayer, "Grizzly Bears")
        }
        attackers.forEach(driver::removeSummoningSickness)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, attackers, defendingPlayer).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)

        return Triple(driver, attackers, blockers)
    }

    fun addRequirement(
        driver: GameTestDriver,
        affectedAttacker: com.wingedsheep.sdk.model.EntityId,
        modification: SerializableModification,
    ) {
        val player = driver.activePlayer!!
        driver.replaceState(
            driver.state.addFloatingEffect(
                layer = Layer.ABILITY,
                modification = modification,
                affectedEntities = setOf(affectedAttacker),
                duration = Duration.EndOfTurn,
                context = EffectContext(sourceId = player, controllerId = player),
            )
        )
    }

    test("duplicate requirement instances must not collapse into one attacker relation") {
        val (driver, attackers, blockers) = combatWith(attackerCount = 3, blockerCount = 2)
        val defendingPlayer = driver.getOpponent(driver.activePlayer!!)
        val first = attackers[0]
        val second = attackers[1]
        val third = attackers[2]

        // Two independent requirements for first, and one each for second and third. Two
        // blockers can satisfy only two attacker relations, but blocking second and
        // third leaves the duplicate first requirement instances unsatisfied. CR 509.1c therefore
        // rejects this declaration because three requirement instances were simultaneously
        // satisfiable (first plus either second or third, with first counted twice).
        addRequirement(driver, first, SerializableModification.MustBeBlockedIfAble)
        addRequirement(driver, first, SerializableModification.MustBeBlockedIfAble)
        addRequirement(driver, second, SerializableModification.MustBeBlockedIfAble)
        addRequirement(driver, third, SerializableModification.MustBeBlockedIfAble)

        driver.declareBlockers(
            defendingPlayer,
            mapOf(blockers[0] to listOf(second), blockers[1] to listOf(third)),
        ).isSuccess shouldBe false
    }

    test("a specific Provoke requirement competes with another requirement instead of hard-pinning") {
        val (driver, attackers, blockers) = combatWith(attackerCount = 2, blockerCount = 1)
        val defendingPlayer = driver.getOpponent(driver.activePlayer!!)
        val provokedAttacker = attackers[0]
        val otherRequiredAttacker = attackers[1]
        val blocker = blockers.single()

        // The blocker can block either attacker. Provoke contributes one requirement for
        // blocker -> provokedAttacker; the other attacker contributes a second requirement. With
        // one blocker, either assignment satisfies the maximum possible count of one. Provoke is
        // therefore a competing requirement instance, not an unconditional hard pin.
        addRequirement(
            driver,
            blocker,
            SerializableModification.MustBlockSpecificAttacker(provokedAttacker),
        )
        addRequirement(
            driver,
            otherRequiredAttacker,
            SerializableModification.MustBeBlockedIfAble,
        )

        driver.declareBlockers(
            defendingPlayer,
            mapOf(blocker to listOf(otherRequiredAttacker)),
        ).isSuccess shouldBe true
    }

    test("Lure-style requirements remain blocker-scoped and preserve duplicate instances") {
        val (driver, attackers, blockers) = combatWith(attackerCount = 2, blockerCount = 2)
        val defendingPlayer = driver.getOpponent(driver.activePlayer!!)

        // Each effect creates a separate all-able-blocker requirement. The first attacker has two
        // identical source instances, so each of the two eligible blockers receives two equal
        // blocker-scoped requirements. These are not collapsed into one attacker-level relation.
        addRequirement(driver, attackers[0], SerializableModification.MustBeBlockedByAll)
        addRequirement(driver, attackers[0], SerializableModification.MustBeBlockedByAll)
        addRequirement(driver, attackers[1], SerializableModification.MustBeBlockedByAll)

        val declareBlockers = driver.legalActions(defendingPlayer)
            .single { it.action is com.wingedsheep.engine.core.DeclareBlockers }
        val domain = declareBlockers.blockerDeclarationDomain!!
        val lureRequirements = domain.requirements.filterIsInstance<RulesBlockRequirement.BlockOneOf>()

        // Two blockers × (two requirements for the first attacker + one for the second).
        lureRequirements.size shouldBe 6
        lureRequirements.count { it.attackerIds == listOf(attackers[0]) } shouldBe 4
        lureRequirements.count { it.attackerIds == listOf(attackers[1]) } shouldBe 2
        // Blocking both blockers on the first attacker satisfies four duplicate requirement
        // instances, which is the exact maximum. Blocking one on each attacker satisfies only
        // three and is therefore illegal.
        domain.minimumSatisfiedRequirementCount shouldBe 4

        driver.declareBlockers(
            defendingPlayer,
            mapOf(blockers[0] to listOf(attackers[0]), blockers[1] to listOf(attackers[1])),
        ).isSuccess shouldBe false

        // Both normal blockers can satisfy all four duplicate instances for the first attacker;
        // the declaration reaches the exact Rules-owned maximum without a hard pin.
        driver.declareBlockers(
            defendingPlayer,
            mapOf(blockers[0] to listOf(attackers[0]), blockers[1] to listOf(attackers[0])),
        ).isSuccess shouldBe true
    }
})
