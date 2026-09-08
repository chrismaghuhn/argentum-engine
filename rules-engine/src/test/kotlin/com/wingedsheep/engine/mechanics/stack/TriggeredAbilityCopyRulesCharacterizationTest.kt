package com.wingedsheep.engine.mechanics.stack

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.BecomesTargetEvent
import com.wingedsheep.engine.core.CommitCrimeEvent
import com.wingedsheep.engine.core.TargetsChosenEvent
import com.wingedsheep.engine.handlers.effects.stack.CopyTargetSpellOrAbilityExecutor
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.targets.TargetPlayer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Characterization for CR 700.13 and CR 707.10: a triggered-ability copy is put on the stack and
 * can therefore be the action that commits a crime, but it does not trigger again merely because
 * it was copied. The test intentionally asserts the rule-correct result and is RED until the
 * production stack path distinguishes a real trigger from a copy.
 */
class TriggeredAbilityCopyRulesCharacterizationTest : FunSpec({
    fun newDriver() = GameTestDriver().apply {
        registerCards(TestCards.all)
        initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
    }

    test("triggered ability copies do not retrigger but preserve crime and targeting semantics") {
        val driver = newDriver()
        val genuineController = driver.player1
        val copyController = driver.player2
        val sourceId = driver.putCreatureOnBattlefield(genuineController, "Grizzly Bears")
        val resolver = StackResolver(driver.cardRegistry)
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "Targeted triggered ability",
            controllerId = genuineController,
            effect = Effects.GainLife(1),
            description = "Targeted triggered ability",
        )
        val targetRequirement = listOf(TargetPlayer())

        val genuine = resolver.putTriggeredAbility(
            state = driver.state,
            ability = ability,
            targets = listOf(ChosenTarget.Player(copyController)),
            targetRequirements = targetRequirement,
        )
        genuine.error shouldBe null
        val genuineTriggeredEvents = genuine.events.filterIsInstance<AbilityTriggeredEvent>()
        val genuineCrimeEvents = genuine.events.filterIsInstance<CommitCrimeEvent>()
        genuineTriggeredEvents.size shouldBe 1
        genuineCrimeEvents.size shouldBe 1
        genuine.newState.playersWhoCommittedCrimeThisTurn.size shouldBe 1
        genuine.newState.playersWhoCommittedCrimeThisTurn.contains(genuineController) shouldBe true
        genuine.events.filterIsInstance<TargetsChosenEvent>().size shouldBe 1
        genuine.events.filterIsInstance<BecomesTargetEvent>().size shouldBe 1

        val copied = CopyTargetSpellOrAbilityExecutor.cloneAndPush(
            state = genuine.newState,
            stackResolver = resolver,
            abilityEntityId = genuine.newState.stack.last(),
            controllerId = copyController,
            targets = listOf(ChosenTarget.Player(genuineController)),
            targetRequirements = targetRequirement,
        )
        copied.error shouldBe null

        val copiedTriggeredEvents = copied.events.filterIsInstance<AbilityTriggeredEvent>()
        val copiedCrimeEvents = copied.events.filterIsInstance<CommitCrimeEvent>()
        val copiedCrimeStateDelta = copied.newState.playersWhoCommittedCrimeThisTurn -
            genuine.newState.playersWhoCommittedCrimeThisTurn
        val copiedTargets = copied.newState.getEntity(copied.newState.stack.last())
            ?.get<TargetsComponent>()?.targets.orEmpty()

        println(
            "TRIGGERED_ABILITY_COPY_RULES_RED " +
                "genuineTriggeredEvents=${genuineTriggeredEvents.size} " +
                "genuineCrimeEvents=${genuineCrimeEvents.size} " +
                "genuineCrimePlayers=${genuine.newState.playersWhoCommittedCrimeThisTurn.size} " +
                "copiedTriggeredEvents=${copiedTriggeredEvents.size} " +
                "copiedCrimeEvents=${copiedCrimeEvents.size} " +
                "copiedCrimeStateDelta=${copiedCrimeStateDelta.size} " +
                "copiedCrimeStateIncludesController=${copiedCrimeStateDelta.contains(copyController)} " +
                "copiedTargetsChosenEvents=${copied.events.count { it is TargetsChosenEvent }} " +
                "copiedBecomesTargetEvents=${copied.events.count { it is BecomesTargetEvent }}",
        )

        // A copy is not a second trigger (CR 707.10), so this is the intentional RED assertion.
        copiedTriggeredEvents.size shouldBe 0

        // CR 700.13 counts putting the triggered-ability copy onto the stack as a crime when its
        // initial copied target is an opponent. This must not be implemented as crime deduplication.
        copiedCrimeEvents.size shouldBe 1
        copiedCrimeStateDelta.size shouldBe 1
        copiedCrimeStateDelta.contains(copyController) shouldBe true

        // Copy target selection remains independent of the trigger-event distinction (CR 707.10c).
        copied.events.filterIsInstance<TargetsChosenEvent>().size shouldBe 1
        copied.events.filterIsInstance<BecomesTargetEvent>().size shouldBe 1
        copiedTargets.size shouldBe 1
        ((copiedTargets.single() as? ChosenTarget.Player)?.playerId == genuineController) shouldBe true
    }
})
