package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.event.PendingTrigger
import com.wingedsheep.engine.event.TriggerContext
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggeredAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Regression coverage for the semantic identity of the CR 603.3b ordering boundary. */
class TriggerOrderingReplayFingerprintTest : FunSpec({

    test("v3 replay fingerprint ignores detector-order permutations of one trigger domain") {
        val driver = GameTestDriver().also {
            it.registerCards(TestCards.all)
            it.initMirrorMatch(Deck.of("Forest" to 40))
        }
        val first = syntheticTrigger(driver, "first")
        val second = syntheticTrigger(driver, "second")
        val initial = driver.state
        val processor = EngineServices(driver.cardRegistry).triggerProcessor

        val forward = processor.processTriggers(initial, listOf(first, second))
        val reverse = processor.processTriggers(initial, listOf(second, first))

        TransitionSemanticGameStateCanonicalizer.canonicalJson(forward.state) shouldBe
            TransitionSemanticGameStateCanonicalizer.canonicalJson(reverse.state)
        ReplayFingerprint.of(forward.state, 3) shouldBe ReplayFingerprint.of(reverse.state, 3)
    }
})

private fun syntheticTrigger(driver: GameTestDriver, label: String): PendingTrigger = PendingTrigger(
    ability = TriggeredAbility.create(
        trigger = EventPattern.StepEvent(Step.UPKEEP, com.wingedsheep.sdk.scripting.references.Player.You),
        effect = Effects.DrawCards(1),
        descriptionOverride = label,
    ).copy(id = AbilityId("replay-$label")),
    sourceId = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring"),
    sourceName = label,
    controllerId = driver.player1,
    triggerContext = TriggerContext(),
)
