package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.handlers.EffectContext
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
 * Characterizes the CR 509.1c requirement-instance gap in the current blocker validator.
 *
 * Requirements are counted as instances, not as a duplicate-free set of affected attackers.
 * The production validator currently calls `distinct()` while collecting
 * MustBeBlockedIfAble effects, so the two cases below deliberately expose the current RED
 * behavior before the Rules-owned certificate is implemented.
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
        // blockers can satisfy only two distinct attacker relations, but blocking second and
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
        // one blocker, either assignment satisfies the maximum possible count of one. The current
        // validator's separate validateProvokeRequirements pass incorrectly hard-pins the choice.
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
})
