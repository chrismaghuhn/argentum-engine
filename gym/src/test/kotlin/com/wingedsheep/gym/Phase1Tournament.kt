package com.wingedsheep.gym

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.ai.engine.StateProgress
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.legalactions.MeaningfulActionFilter
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Path

/**
 * Phase 1 tournament: headless games between seats that are either the engine AI or a P1 model
 * checkpoint, for checkpoint-vs-checkpoint and checkpoint-vs-engine ladders.
 *
 * A model seat picks the top-level priority candidate from exactly the sample format the collector
 * trains on ([Phase1SelfPlayCollector.observationSample]); its own engine AI then completes that
 * one candidate (targets, X, combat assignments, payment) and answers every pending decision — the
 * P1 "engine handles sub-decisions" rule from docs/ml/p1-first-playing-model.md. A model choice the
 * engine cannot execute falls back to the engine AI's own action for that step and is counted.
 */
internal object Phase1Tournament {

    sealed interface SeatSpec {
        val label: String

        data class Engine(val profile: AiProfile) : SeatSpec {
            override val label: String get() = "engine:${profile.id}"
        }

        data class Model(val checkpoint: Path) : SeatSpec {
            override val label: String get() = checkpoint.fileName.toString()
        }

        companion object {
            /** `engine`, `engine:<profile>`, or a checkpoint directory path. */
            fun parse(value: String): SeatSpec = when {
                value == "engine" -> Engine(AiProfile.PRODUCTION_CANDIDATE_EXPIRING)
                value.startsWith("engine:") -> Engine(Phase1SelfPlayCollector.profileFor(value.removePrefix("engine:")))
                else -> Model(Path.of(value))
            }
        }
    }

    /** One `python -m argentum_ml.p1.serve` process; calls are serialized, inference is milliseconds. */
    class PolicyWorker(checkpoint: Path, python: Path, mlRoot: Path) : AutoCloseable {
        private val process: Process = ProcessBuilder(
            python.toString(), "-m", "argentum_ml.p1.serve", "--checkpoint", checkpoint.toString(),
        ).directory(mlRoot.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
        private val output: BufferedReader = process.inputStream.bufferedReader(Charsets.UTF_8)

        init {
            val ready = output.readLine() ?: error("P1 policy worker for $checkpoint exited before ready")
            check(Json.parseToJsonElement(ready).jsonObject.containsKey("ready")) { "Unexpected worker hello: $ready" }
        }

        /** Returns the chosen candidate index, or null when the worker reports an error. */
        @Synchronized
        fun choose(sample: kotlinx.serialization.json.JsonObject, allowed: List<Boolean>): Int? {
            input.write(
                buildJsonObject {
                    put("sample", sample)
                    put("allowed", kotlinx.serialization.json.JsonArray(allowed.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }.toString(),
            )
            input.write("\n")
            input.flush()
            val reply = Json.parseToJsonElement(output.readLine() ?: return null).jsonObject
            return reply["chosen"]?.jsonPrimitive?.int
        }

        override fun close() {
            runCatching { input.close() }
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    data class GameResult(
        val game: Int,
        val seatA: String,
        val seatB: String,
        val deckA: String,
        val aStarts: Boolean,
        val terminal: Boolean,
        val winner: String, // "A", "B" or "-"
        val turns: Int,
        val engineSteps: Int,
        val modelChoices: Int,
        val modelFallbacks: Int,
        val loopBreaks: Int,
        val seconds: Double,
    )

    fun playGame(
        game: Int,
        config: GameConfig,
        registry: CardRegistry,
        seatA: SeatSpec,
        seatB: SeatSpec,
        workers: Map<Path, PolicyWorker>,
        maxSteps: Int,
    ): GameResult {
        val environment = GameEnvironment.create(cardRegistry = registry)
        val observationBuilder = ObservationBuilder(cardRegistry = registry)
        val started = System.nanoTime()
        environment.reset(config, maxSteps = maxSteps)
        val ids = config.players.map { checkNotNull(it.playerId) }
        val names = config.players.associate { checkNotNull(it.playerId) to it.name }
        // Seat A always sits in the seat whose deck the config gave it; see [configFor].
        val seatOf = mapOf(ids[0] to seatA, ids[1] to seatB)
        val ais = ids.associateWith { id ->
            val spec = seatOf.getValue(id)
            val profile = (spec as? SeatSpec.Engine)?.profile ?: AiProfile.PRODUCTION_CANDIDATE_EXPIRING
            AIPlayer.create(registry, id, profile)
        }
        var modelChoices = 0
        var modelFallbacks = 0
        var loopBreaks = 0
        // The engine AI refuses to act again from a position it already acted from (StateProgress);
        // a model seat gets the same guard, or a free inert action (re-equip, self-untap) repeats forever.
        val actedFrom = ids.associateWith { mutableSetOf<Long>() }

        while (!environment.isTerminal && !environment.isTruncated) {
            val actor = environment.agentToAct ?: break
            val ai = ais.getValue(actor)
            val decision = environment.pendingDecision
            if (decision != null) {
                environment.step(SubmitDecision(actor, ai.respondToDecision(environment.state, decision)))
                continue
            }
            val spec = seatOf.getValue(actor)
            val state = environment.state
            if (spec is SeatSpec.Engine) {
                environment.step(ai.chooseAction(state))
                continue
            }
            spec as SeatSpec.Model
            if (MeaningfulActionFilter.canAutoPassWithoutEnumerating(state, actor)) {
                environment.step(PassPriority(actor))
                continue
            }
            val legal = environment.legalActions()
            if (legal.isEmpty()) break
            if (!actedFrom.getValue(actor).add(StateProgress.digest(state))) {
                loopBreaks++
                val pass = legal.firstOrNull { it.action is PassPriority }?.action
                environment.step(pass ?: ai.chooseAction(state))
                continue
            }
            val built = observationBuilder.build(state, actor, legal)
            val observation = built.observation as? TrainingObservation
            val candidates = observation?.let(Phase1SelfPlayCollector::modelCandidates).orEmpty()
            if (observation == null || candidates.size < 2) {
                environment.step(ai.chooseFrom(state, legal).action)
                continue
            }
            val sample = Phase1SelfPlayCollector.observationSample(game, environment.stepCount, names, observation, candidates)
            // The teacher never picks an unaffordable candidate and the sample carries no affordability
            // feature, so the seat restricts the model to candidates it can currently pay for.
            val index = workers.getValue(spec.checkpoint).choose(sample, candidates.map { it.affordable })
            val template = index?.let { i ->
                candidates.getOrNull(i)?.let { view ->
                    built.registry.legalActions.firstOrNull { it.first == view.actionId }?.second
                }
            }
            modelChoices++
            val completed: GameAction? = template?.let { ai.chooseFrom(state, listOf(it)).action }
            val ok = completed != null && runCatching { environment.step(completed) }.isSuccess
            if (!ok) {
                modelFallbacks++
                environment.step(ai.chooseAction(environment.state))
            }
        }

        val winnerId = environment.winnerId
        return GameResult(
            game = game,
            seatA = seatA.label,
            seatB = seatB.label,
            deckA = names.getValue(ids[0]),
            aStarts = config.startingPlayerIndex == 0,
            terminal = environment.isTerminal,
            winner = when (winnerId) {
                null -> "-"
                ids[0] -> "A"
                else -> "B"
            },
            turns = environment.turnNumber,
            engineSteps = environment.stepCount,
            modelChoices = modelChoices,
            modelFallbacks = modelFallbacks,
            loopBreaks = loopBreaks,
            seconds = (System.nanoTime() - started) / 1e9,
        )
    }
}
