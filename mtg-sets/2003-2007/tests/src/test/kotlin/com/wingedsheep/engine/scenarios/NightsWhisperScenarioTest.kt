package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.DrawFailedEvent
import com.wingedsheep.engine.core.LifeChangedEvent
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

/** Night's Whisper (5DN #55): draw two cards and lose 2 life. */
class NightsWhisperScenarioTest : ScenarioTestBase() {

    init {
        test("draws exactly two cards before the caster loses exactly two life") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Night's Whisper")
                .withCardInLibrary(1, "Forest")
                .withCardInLibrary(1, "Island")
                .withCardInLibrary(1, "Swamp")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .withLifeTotal(1, 20)
                .withLifeTotal(2, 20)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val handBeforeCast = game.handSize(1)
            val opponentHandBefore = game.handSize(2)
            game.castSpell(1, "Night's Whisper").error shouldBe null
            val handAfterCast = game.handSize(1)

            val events = game.resolveStack().flatMap { it.events }

            handAfterCast shouldBe handBeforeCast - 1
            game.handSize(1) shouldBe handAfterCast + 2
            game.getLifeTotal(1) shouldBe 18
            game.handSize(2) shouldBe opponentHandBefore
            game.getLifeTotal(2) shouldBe 20
            game.hasPendingDecision() shouldBe false

            val drawEvent = events.filterIsInstance<CardsDrawnEvent>()
                .single { it.playerId == game.player1Id }
            drawEvent.count shouldBe 2
            val lifeEvent = events.filterIsInstance<LifeChangedEvent>()
                .single { it.playerId == game.player1Id && it.reason == LifeChangeReason.LIFE_LOSS }
            (events.indexOf(drawEvent) < events.indexOf(lifeEvent)) shouldBe true
        }

        test("draws the available card before the second draw fails") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withCardInHand(1, "Night's Whisper")
                .withCardInLibrary(1, "Forest")
                .withLandsOnBattlefield(1, "Swamp", 2)
                .withLifeTotal(1, 20)
                .withLifeTotal(2, 20)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Night's Whisper").error shouldBe null
            val events = game.resolveStack().flatMap { it.events }

            game.state.gameOver shouldBe true
            game.state.winnerId shouldBe game.player2Id
            game.librarySize(1) shouldBe 0
            game.handSize(1) shouldBe 1
            game.getLifeTotal(1) shouldBe 18

            val drawEvent = events.filterIsInstance<CardsDrawnEvent>()
                .single { it.playerId == game.player1Id }
            drawEvent.count shouldBe 1
            events.filterIsInstance<DrawFailedEvent>()
                .any { it.playerId == game.player1Id } shouldBe true
        }
    }
}
