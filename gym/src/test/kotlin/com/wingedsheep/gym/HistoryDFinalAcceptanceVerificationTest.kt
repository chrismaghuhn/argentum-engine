package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PermanentsSacrificedEvent
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveHistoryV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.history.HistoryDOperationException
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

private const val HISTORY_D_MAX_STEPS = 4_000
private const val HISTORY_D_POLICY_SEED = 0x41L
private const val HISTORY_D_EPISODE_ID = "history-d-final-acceptance-v1"

/** Executes the ratified bounded History-D final-acceptance matrix. */
class HistoryDFinalAcceptanceVerificationTest : FunSpec({
    test("ratified two-run two-perspective 4000-step matrix passes") {
        val run1 = executeFreshRun("run-1")
        val run2 = executeFreshRun("run-2")

        run1.step3522Crossed shouldBe true
        run2.step3522Crossed shouldBe true
        run1.successfulChoices shouldBe HISTORY_D_MAX_STEPS
        run2.successfulChoices shouldBe HISTORY_D_MAX_STEPS
        run1.committedSteps shouldBe HISTORY_D_MAX_STEPS
        run2.committedSteps shouldBe HISTORY_D_MAX_STEPS
        run1.terminated shouldBe false
        run2.terminated shouldBe false
        run1.truncated shouldBe true
        run2.truncated shouldBe true
        run1.closure shouldBe expectedHorizonClosure()
        run2.closure shouldBe expectedHorizonClosure()

        run1.p1.perspectivePlayerId shouldBe run2.p1.perspectivePlayerId
        run1.p2.perspectivePlayerId shouldBe run2.p2.perspectivePlayerId
        run1.p1.canonicalJson() shouldBe run2.p1.canonicalJson()
        run1.p2.canonicalJson() shouldBe run2.p2.canonicalJson()
        run1.p1.semanticDigest() shouldBe run2.p1.semanticDigest()
        run1.p2.semanticDigest() shouldBe run2.p2.semanticDigest()

        println(
            "HISTORY_D_FINAL_ACCEPTANCE " +
                "run1Choices=${run1.successfulChoices} " +
                "run1Steps=${run1.committedSteps} " +
                "run1Step3522=${run1.step3522Crossed} " +
                "run1P1Digest=${run1.p1.semanticDigest()} " +
                "run1P2Digest=${run1.p2.semanticDigest()} " +
                "run2Choices=${run2.successfulChoices} " +
                "run2Steps=${run2.committedSteps} " +
                "run2Step3522=${run2.step3522Crossed} " +
                "run2P1Digest=${run2.p1.semanticDigest()} " +
                "run2P2Digest=${run2.p2.semanticDigest()} " +
                "perspectiveIdsEqual=${
                    run1.p1.perspectivePlayerId == run2.p1.perspectivePlayerId &&
                        run1.p2.perspectivePlayerId == run2.p2.perspectivePlayerId
                } " +
                "closureEqual=${run1.closure == run2.closure}",
        )
    }
})

private data class AcceptanceRun(
    val successfulChoices: Int,
    val committedSteps: Int,
    val step3522Crossed: Boolean,
    val terminated: Boolean,
    val truncated: Boolean,
    val closure: EpisodeClosureV1?,
    val p1: PerspectiveHistoryV1,
    val p2: PerspectiveHistoryV1,
)

private fun executeFreshRun(label: String): AcceptanceRun {
    val registry = CardRegistry().apply {
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
        }
    }
    val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }

    fun lockedDeck(fileName: String): List<String> = Files.readAllLines(
        repositoryRoot.resolve("docs").resolve("ml").resolve("curriculum").resolve(fileName),
    )
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }

    val akiri = lockedDeck("akiri-v0.1.txt")
    val chevill = lockedDeck("chevill-v0.1.txt")
    val config = GameConfig(
        players = listOf(
            com.wingedsheep.engine.core.PlayerConfig(
                name = "Akiri",
                deck = Deck(akiri.drop(1)),
                startingLife = 40,
                commanderCardName = akiri.first(),
            ),
            com.wingedsheep.engine.core.PlayerConfig(
                name = "Chevill",
                deck = Deck(chevill.drop(1)),
                startingLife = 40,
                commanderCardName = chevill.first(),
            ),
        ),
        startingHandSize = 7,
        skipMulligans = true,
        startingPlayerIndex = 0,
        format = Format.Commander(),
        seed = 0L,
    )
    val environment = GameEnvironment.create(
        cardRegistry = registry,
        executionMode = GameEnvironmentMode.TRUSTED,
    )
    val gym = GameGymEnv(
        environment = environment,
        perspectivePlayerIndex = 0,
        observationBuilder = ObservationBuilder(cardRegistry = registry),
    )
    var observation = gym.reset(
        gameConfig = config,
        maxSteps = HISTORY_D_MAX_STEPS,
        semanticEpisodeId = HISTORY_D_EPISODE_ID,
    ).observation as TrainingObservation
    val policy = DeterministicExternalPolicy()
    var policyState = DeterministicPolicyState(policySeed = HISTORY_D_POLICY_SEED)
    var successfulChoices = 0
    var step3522Crossed = false

    while (!observation.terminated && !observation.truncated) {
        val choice = policy.choose(observation, policyState)
        policyState = policyState.afterChoice()
        try {
            observation = when (choice) {
                is SemanticChoice.Action -> {
                    if (choice.payload == null) {
                        gym.step(choice.actionId).observation
                    } else {
                        gym.step(choice.actionId, choice.payload).observation
                    }
                }

                is SemanticChoice.Structured -> {
                    val pending = checkNotNull(observation.pendingDecision) {
                        "$label reached a structured choice without a pending decision"
                    }
                    val decisionId = checkNotNull(pending.decisionId) {
                        "$label reached a structured choice without a decision ID"
                    }
                    gym.submitDecision(
                        response = historyAcceptanceDecisionResponse(decisionId, choice.selection),
                        actorId = observation.agentToAct,
                    ).observation
                }

                is SemanticChoice.Gap -> error("$label reached a policy gap at choice=$successfulChoices")
            } as TrainingObservation
        } catch (exception: HistoryDOperationException) {
            error(
                "$label first History-D failure at committedStep=${environment.stepCount}: " +
                    exception.failure.code,
            )
        }
        successfulChoices++
        if (
            environment.stepCount == 3_522 &&
            environment.lastStepEvents.any { it is PermanentsSacrificedEvent }
        ) {
            step3522Crossed = true
        }
    }

    val playerIds = environment.playerIds
    playerIds.size shouldBe 2
    val p1 = gym.perspectiveHistory(playerIds[0])
    val p2 = gym.perspectiveHistory(playerIds[1])
    return AcceptanceRun(
        successfulChoices = successfulChoices,
        committedSteps = environment.stepCount,
        step3522Crossed = step3522Crossed,
        terminated = observation.terminated,
        truncated = observation.truncated,
        closure = gym.episodeClosure,
        p1 = p1,
        p2 = p2,
    )
}

private fun expectedHorizonClosure(): EpisodeClosureV1 = EpisodeClosureV1.Interrupted(
    stepCount = HISTORY_D_MAX_STEPS,
    reason = EpisodeInterruptionReason.HORIZON_REACHED,
)

private fun historyAcceptanceDecisionResponse(
    decisionId: String,
    selection: SemanticDecision,
): DecisionResponse = when (selection) {
    is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
    is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
    is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Distribution -> DistributionResponse(decisionId, selection.selected)
    is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
    is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
    is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
    is SemanticDecision.Replacement -> ReplacementChosenResponse(decisionId, selection.from, selection.to)
    is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
    is SemanticDecision.Damage -> CombatResolutionResponse(
        decisionId = decisionId,
        edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
    )
    is SemanticDecision.Payment -> selection.toDecisionResponse(decisionId)
}
