package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.EpisodeInterruptionReason
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

/** Adapter-level coverage for the replay branch that binds a recorded decision response. */
class ReplayChosenInputBindingIntegrationTest : ScenarioTestBase() {

    private val choiceCard = card("A6 Chosen Input Integration") {
        manaCost = "{0}"
        typeLine = "Instant"
        oracleText = "Choose a number from 1 to 2. You gain 1 life."
        spell {
            effect = Effects.ChooseNumberThen(
                then = Effects.GainLife(1),
                minValue = 1,
                maxValue = 2,
                prompt = "Choose a number from 1 to 2",
            )
        }
    }

    private data class ResponseReplay(
        val replay: CompactReplay,
        val responseIndex: Int,
    )

    private fun responseReplay(responseNonce: String): ResponseReplay {
        val playerOne = EntityId.of("chosen-input-p1")
        val playerTwo = EntityId.of("chosen-input-p2")
        val deck = Deck(cards = List(40) { choiceCard.name })
        val config = GameConfig(
            players = listOf(
                PlayerConfig("Alice", deck, playerId = playerOne),
                PlayerConfig("Bob", deck, playerId = playerTwo),
            ),
            startingHandSize = 1,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 91L,
        )
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(config)
        val actions = mutableListOf<GameAction>()

        fun record(action: GameAction) {
            actions += action
            environment.step(action)
        }

        val cast = environment.legalActions()
            .firstOrNull { it.action is CastSpell }
            ?.let { it.action }
            ?: error("Choice replay did not publish a cast candidate")
        val typedCast = cast as CastSpell
        record(typedCast.copy(paymentStrategy = PaymentStrategy.ExplicitV3(PaymentPlanV3())))

        while (environment.pendingDecision == null) {
            val pass = environment.legalActions()
                .firstOrNull { it.action is PassPriority }
                ?.action
                ?: error("Choice replay did not reach its pending decision")
            record(pass)
        }

        val decision = environment.pendingDecision as? ChooseNumberDecision
            ?: error("Choice replay reached an unexpected pending decision")
        val recordedResponseIndex = actions.size
        record(
            SubmitDecision(
                playerId = decision.playerId,
                response = NumberChosenResponse(decision.id, number = 1),
            ),
        )
        // The legacy Gym simulator auto-resolves passive priority while this fixture is being
        // assembled. ReplayReconstructor consumes the same inputs one transition at a time, so
        // retain those resume passes explicitly in the replay fixture before the response.
        val replayPrefix = actions.take(recordedResponseIndex).let { prefix ->
            if (prefix.lastOrNull() is PassPriority) prefix
            else prefix + listOf(PassPriority(playerOne), PassPriority(playerTwo))
        }
        val responseIndex = replayPrefix.size
        val replayActions = replayPrefix + SubmitDecision(
            playerId = decision.playerId,
            response = NumberChosenResponse(responseNonce, number = 1),
        )

        val draft = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "chosen-input-integration",
            players = listOf(
                ReplayPlayerInfo(playerOne.value, "Alice"),
                ReplayPlayerInfo(playerTwo.value, "Bob"),
            ),
            startedAt = Instant.parse("2026-01-01T00:00:00Z").toString(),
            endedAt = Instant.parse("2026-01-01T00:01:00Z").toString(),
            winnerName = null,
            setup = ReplaySetup(
                seed = config.seed ?: error("Fixture seed is required"),
                format = config.format,
                attackMode = config.attackMode,
                startingHandSize = config.startingHandSize,
                skipMulligans = config.skipMulligans,
                useHandSmoother = config.useHandSmoother,
                handSmootherCandidates = config.handSmootherCandidates,
                startingPlayerIndex = config.startingPlayerIndex,
                teams = config.teams,
                players = listOf(
                    ReplayPlayerSetup(playerOne.value, "Alice", deck),
                    ReplayPlayerSetup(playerTwo.value, "Bob", deck),
                ),
                seatRoster = emptyList(),
            ),
            actions = replayActions,
        )
        val reconstructor = ReplayReconstructor(cardRegistry, null)
        val finalState = reconstructor.reconstructStateAt(draft, replayActions.size)
            ?: error(
                "Choice replay did not reconstruct: " +
                    reconstructor.reconstructDiagnostics(draft)
            )
        return ResponseReplay(
            replay = draft.copy(
                checkpoints = listOf(
                    ReplayCheckpoint(
                        afterActionCount = replayActions.size,
                        fingerprint = ReplayFingerprint.of(finalState, draft.version),
                    ),
                ),
            ),
            responseIndex = responseIndex,
        )
    }

    private fun source(replay: CompactReplay): GymReplayFrameSource = GymReplayFrameSource(
        replay = replay,
        cardRegistry = cardRegistry,
        tailClosure = EpisodeClosureV1.Interrupted(
            stepCount = replay.actions.size,
            reason = EpisodeInterruptionReason.HORIZON_REACHED,
        ),
    )

    init {
        test("combined replay binding projects SubmitDecision through the A4 public boundary") {
            cardRegistry.register(choiceCard)
            val first = responseReplay("nonce-a")
            val second = responseReplay("nonce-b")

            val firstBinding = source(first.replay).verifyTrajectoryBinding()
            val secondBinding = source(second.replay).verifyTrajectoryBinding()
            val chosen = firstBinding.chosenInputBinding.chosenInputs[first.responseIndex]

            chosen.replayActionIndex shouldBe first.responseIndex
            chosen.perspectivePlayerId shouldBe EntityId.of("chosen-input-p1")
            chosen.chosenSemanticAction shouldBe null
            chosen.chosenSemanticResponse.shouldNotBeNull()
            chosen.chosenSemanticResponse shouldBe
                secondBinding.chosenInputBinding.chosenInputs[second.responseIndex]
                    .chosenSemanticResponse
            firstBinding.verificationBinding.verification.completeRangeVerified shouldBe true
            firstBinding.chosenInputBinding.replayContentIdentity shouldBe
                firstBinding.verificationBinding.replayContentIdentity
            firstBinding.chosenInputBinding shouldBe secondBinding.chosenInputBinding
            firstBinding.verificationBinding.replayContentIdentity shouldBe
                secondBinding.verificationBinding.replayContentIdentity
        }
    }
}
