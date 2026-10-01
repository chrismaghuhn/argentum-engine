package com.wingedsheep.gym

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.ai.engine.StateProgress
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CycleCard
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.legalactions.MeaningfulActionFilter
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.gameserver.config.ServerRegistries
import com.wingedsheep.gameserver.replay.HeadlessReplayRecorder
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayReconstructor
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

    /**
     * The server's own registries, so a recorded showcase game re-simulates EXACT when the web replay
     * viewer plays it back (printing and token art are part of the fingerprinted state).
     */
    object Registries {
        val card: CardRegistry by lazy { ServerRegistries.cardRegistry() }
        val printing: PrintingRegistry by lazy { ServerRegistries.printingRegistry(card) }
        val tokenArt: TokenArtRegistry by lazy { ServerRegistries.tokenArtRegistry() }
    }

    data class Showcase(val directory: Path, val gameId: String, val engineVersion: String)

    /**
     * One model-seat choice among >= 2 candidates, reported before the model's action executes;
     * [executed] turns true once the engine has applied the model's choice (false = fallback).
     */
    class ModelDecision(
        val environment: GameEnvironment,
        val actor: EntityId,
        val state: GameState,
        val legal: List<LegalAction>,
        val registry: com.wingedsheep.gym.contract.ActionRegistry,
        val candidates: List<com.wingedsheep.gym.contract.LegalActionView>,
        val sample: kotlinx.serialization.json.JsonObject,
        val modelIndex: Int?,
        val reply: PolicyReply? = null,
    ) {
        var executed: Boolean = false
    }

    /** Observes model decisions without influencing them (DAgger labels them with the teacher). */
    fun interface ModelDecisionObserver {
        fun onModelDecision(decision: ModelDecision)
    }

    /**
     * Which cards a seat actually uses, split by what it did with them. `offered` counts priority
     * decisions in which the card gave the seat at least one affordable, non-mana action of that kind
     * (once per decision and kind); `played` counts submitted actions of that kind sourced from the
     * card. The same rule applies to engine and model seats, so their usage rates are comparable.
     * Kinds: Cast (any spell cast, incl. kicker/flashback/modes), Ability, Land, Cycle, TurnFaceUp.
     */
    class CardUsage {
        class Stats(val types: String) {
            val kinds = sortedMapOf<String, IntArray>()
        }

        val cards = sortedMapOf<String, Stats>()

        fun offered(state: GameState, legal: List<LegalAction>) {
            legal.asSequence()
                .filter { it.affordable && !it.isManaAbility }
                .mapNotNull { source(state, it.action) }
                .toSet()
                .forEach { (card, kind) -> stats(state, card).kinds.getOrPut(kind) { IntArray(2) }[0]++ }
        }

        fun played(state: GameState, action: GameAction) {
            val (card, kind) = source(state, action) ?: return
            stats(state, card).kinds.getOrPut(kind) { IntArray(2) }[1]++
        }

        private fun stats(state: GameState, card: EntityId): Stats {
            val component = state.getEntity(card)?.get<CardComponent>()
            val name = component?.name ?: card.value
            return cards.getOrPut(name) {
                Stats(component?.typeLine?.cardTypes?.map { it.name }?.sorted()?.joinToString("/") ?: "")
            }
        }

        private fun source(state: GameState, action: GameAction): Pair<EntityId, String>? {
            val (card, kind) = when (action) {
                is CastSpell -> action.cardId to "Cast"
                is ActivateAbility -> action.sourceId to "Ability"
                is CycleCard -> action.cardId to "Cycle"
                is PlayLand -> action.cardId to "Land"
                is TurnFaceUp -> action.sourceId to "TurnFaceUp"
                else -> return null
            }
            return if (state.getEntity(card)?.get<CardComponent>() != null) card to kind else null
        }
    }

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

    /** A worker's answer: the candidate index, its log-probability under the policy, and the value estimate. */
    data class PolicyReply(val index: Int, val logprob: Double, val value: Double)

    /**
     * `python -m argentum_ml.p1.serve` processes for one checkpoint. Each process answers one request
     * at a time; with [processes] > 1 concurrent games borrow whichever process is free, so model
     * inference stops being the bottleneck of a many-game run. With [sample] the processes draw from
     * the policy instead of taking the argmax (PPO rollouts); each request carries a seed so a draw
     * does not depend on which process served it. The processes import `argentum_ml` from this
     * checkout's `ml/src`, not from wherever the venv's editable install points.
     */
    class PolicyWorker(
        checkpoint: Path,
        python: Path,
        mlRoot: Path,
        processes: Int = 1,
        sample: Boolean = false,
    ) : AutoCloseable {
        private class Endpoint(val process: Process) {
            val input: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val output: BufferedReader = process.inputStream.bufferedReader(Charsets.UTF_8)
        }

        private val endpoints: List<Endpoint> = (0 until processes.coerceAtLeast(1)).map {
            val command = mutableListOf(python.toString(), "-m", "argentum_ml.p1.serve", "--checkpoint", checkpoint.toString())
            if (sample) command += "--sample"
            val builder = ProcessBuilder(command).directory(mlRoot.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment()["PYTHONPATH"] = mlRoot.resolve("src").toString()
            Endpoint(builder.start())
        }
        private val free = java.util.concurrent.LinkedBlockingQueue(endpoints)

        init {
            for (endpoint in endpoints) {
                val ready = endpoint.output.readLine() ?: error("P1 policy worker for $checkpoint exited before ready")
                check(Json.parseToJsonElement(ready).jsonObject.containsKey("ready")) { "Unexpected worker hello: $ready" }
            }
        }

        /** Returns the chosen candidate index, or null when the worker reports an error. */
        fun choose(sample: kotlinx.serialization.json.JsonObject, allowed: List<Boolean>): Int? =
            decide(sample, allowed, seed = null)?.index

        /** The full reply, or null when the worker reports an error. */
        fun decide(sample: kotlinx.serialization.json.JsonObject, allowed: List<Boolean>, seed: Long?): PolicyReply? {
            val endpoint = free.take()
            try {
                endpoint.input.write(
                    buildJsonObject {
                        put("sample", sample)
                        put("allowed", kotlinx.serialization.json.JsonArray(allowed.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        if (seed != null) put("seed", kotlinx.serialization.json.JsonPrimitive(seed))
                    }.toString(),
                )
                endpoint.input.write("\n")
                endpoint.input.flush()
                val reply = Json.parseToJsonElement(endpoint.output.readLine() ?: return null).jsonObject
                val index = reply["chosen"]?.jsonPrimitive?.int ?: return null
                return PolicyReply(
                    index = index,
                    logprob = reply["logprob"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: Double.NaN,
                    value = reply["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            } finally {
                free.put(endpoint)
            }
        }

        override fun close() {
            for (endpoint in endpoints) {
                runCatching { endpoint.input.close() }
                if (!endpoint.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) endpoint.process.destroyForcibly()
            }
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
        val replayFile: String? = null,
        val replayFidelity: String? = null,
        val cardsA: Map<String, CardUsage.Stats> = emptyMap(),
        val cardsB: Map<String, CardUsage.Stats> = emptyMap(),
    )

    fun playGame(
        game: Int,
        config: GameConfig,
        seatA: SeatSpec,
        seatB: SeatSpec,
        workers: Map<Path, PolicyWorker>,
        maxSteps: Int,
        showcase: Showcase? = null,
        observer: ModelDecisionObserver? = null,
    ): GameResult {
        val registry = Registries.card
        val environment = GameEnvironment.create(
            registry,
            printingRegistry = Registries.printing,
            tokenArtRegistry = Registries.tokenArt,
        )
        val observationBuilder = ObservationBuilder(cardRegistry = registry)
        val started = System.nanoTime()
        val recorder = if (showcase != null) {
            HeadlessReplayRecorder.start(environment, registry, config, maxSteps)
        } else {
            environment.reset(config, maxSteps = maxSteps)
            null
        }
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
        val usage = ids.associateWith { CardUsage() }

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
                if (!MeaningfulActionFilter.canAutoPassWithoutEnumerating(state, actor)) {
                    usage.getValue(actor).offered(state, environment.legalActions())
                }
                val action = ai.chooseAction(state)
                usage.getValue(actor).played(state, action)
                environment.step(action)
                continue
            }
            spec as SeatSpec.Model
            if (MeaningfulActionFilter.canAutoPassWithoutEnumerating(state, actor)) {
                environment.step(PassPriority(actor))
                continue
            }
            val legal = environment.legalActions()
            if (legal.isEmpty()) break
            usage.getValue(actor).offered(state, legal)
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
                val action = ai.chooseFrom(state, legal).action
                usage.getValue(actor).played(state, action)
                environment.step(action)
                continue
            }
            val sample = Phase1SelfPlayCollector.observationSample(game, environment.stepCount, names, observation, candidates)
            // The teacher never picks an unaffordable candidate and the sample carries no affordability
            // feature, so the seat restricts the model to candidates it can currently pay for.
            val seatIndex = ids.indexOf(actor)
            val reply = workers.getValue(spec.checkpoint).decide(
                sample,
                candidates.map { it.affordable },
                seed = (game.toLong() * 1_000_003L + environment.stepCount) * 2 + seatIndex,
            )
            val index = reply?.index
            val modelDecision = ModelDecision(environment, actor, state, legal, built.registry, candidates, sample, index, reply)
            observer?.onModelDecision(modelDecision)
            val template = index?.let { i ->
                candidates.getOrNull(i)?.let { view ->
                    built.registry.legalActions.firstOrNull { it.first == view.actionId }?.second
                }
            }
            modelChoices++
            val completed: GameAction? = template?.let { ai.chooseFrom(state, listOf(it)).action }
            val ok = completed != null && runCatching { environment.step(completed) }.isSuccess
            if (ok) {
                modelDecision.executed = true
                usage.getValue(actor).played(state, checkNotNull(completed))
            } else {
                modelFallbacks++
                val fallback = ai.chooseAction(environment.state)
                usage.getValue(actor).played(environment.state, fallback)
                environment.step(fallback)
            }
        }

        var replayFile: String? = null
        var replayFidelity: String? = null
        if (recorder != null && showcase != null) {
            val replay = recorder.finish(gameId = showcase.gameId, engineVersion = showcase.engineVersion)
            replayFidelity = ReplayReconstructor(registry, Registries.printing, Registries.tokenArt)
                .reconstruct(replay).fidelity.name
            val file = showcase.directory.resolve("${showcase.gameId}.replay")
            java.nio.file.Files.createDirectories(showcase.directory)
            java.nio.file.Files.writeString(file, ReplayCodec.encode(replay))
            replayFile = file.toString()
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
            replayFile = replayFile,
            replayFidelity = replayFidelity,
            cardsA = usage.getValue(ids[0]).cards,
            cardsB = usage.getValue(ids[1]).cards,
        )
    }
}
