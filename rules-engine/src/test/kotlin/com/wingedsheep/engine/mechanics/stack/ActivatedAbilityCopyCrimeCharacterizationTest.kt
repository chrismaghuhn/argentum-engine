package com.wingedsheep.engine.mechanics.stack

import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.core.CommitCrimeEvent
import com.wingedsheep.engine.handlers.effects.stack.CopyTargetSpellOrAbilityExecutor
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.targets.TargetPlayer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * RED characterization for CR 707.10: a copy of an activated ability is not activated and must not
 * commit a second crime. The test uses the real StackResolver and copy executor seams.
 */
class ActivatedAbilityCopyCrimeCharacterizationTest : FunSpec({
    test("genuine targeted activation commits crime but its activated-ability copy does not") {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all)
            initMirrorMatch(
                deck = Deck.of("Forest" to 40),
                skipMulligans = true,
                startingPlayer = 0,
            )
        }
        val genuineController = driver.player1
        val copyController = driver.player2
        val sourceId = driver.putCreatureOnBattlefield(genuineController, "Grizzly Bears")
        val resolver = StackResolver(driver.cardRegistry)
        val ability = ActivatedAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "Targeted activated ability",
            controllerId = genuineController,
            effect = Effects.GainLife(1),
        )

        val genuine = resolver.putActivatedAbility(
            state = driver.state,
            ability = ability,
            targets = listOf(ChosenTarget.Player(copyController)),
            targetRequirements = listOf(TargetPlayer()),
        )
        genuine.error shouldBe null
        genuine.events.filterIsInstance<AbilityActivatedEvent>().size shouldBe 1
        genuine.events.filterIsInstance<CommitCrimeEvent>().size shouldBe 1
        genuine.newState.playersWhoCommittedCrimeThisTurn.size shouldBe 1
        genuine.newState.playersWhoCommittedCrimeThisTurn.contains(genuineController) shouldBe true

        val copied = CopyTargetSpellOrAbilityExecutor.cloneAndPush(
            state = genuine.newState,
            stackResolver = resolver,
            abilityEntityId = genuine.newState.stack.last(),
            controllerId = copyController,
            targets = listOf(ChosenTarget.Player(genuineController)),
            targetRequirements = listOf(TargetPlayer()),
        )

        copied.error shouldBe null
        val copiedCrimeEvents = copied.events.filterIsInstance<CommitCrimeEvent>()
        println(
            "ACTIVATED_ABILITY_COPY_CRIME_RED " +
                "genuineActivationEvents=${genuine.events.count { it is AbilityActivatedEvent }} " +
                "genuineCrimeEvents=${genuine.events.count { it is CommitCrimeEvent }} " +
                "copiedActivationEvents=${copied.events.count { it is AbilityActivatedEvent }} " +
                "copiedCrimeEvents=${copiedCrimeEvents.size} " +
                "genuineCrimePlayers=${genuine.newState.playersWhoCommittedCrimeThisTurn.size} " +
                "copiedCrimePlayers=${copied.newState.playersWhoCommittedCrimeThisTurn.size}",
        )
        copied.events.filterIsInstance<AbilityActivatedEvent>().map { it::class.simpleName }
            .shouldBeEmpty()
        copiedCrimeEvents.map { it::class.simpleName }
            .shouldBeEmpty()
        (copied.newState.playersWhoCommittedCrimeThisTurn -
            genuine.newState.playersWhoCommittedCrimeThisTurn).isEmpty() shouldBe true
    }
})
