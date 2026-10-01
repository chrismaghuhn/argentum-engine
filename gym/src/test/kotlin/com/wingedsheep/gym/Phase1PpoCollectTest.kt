package com.wingedsheep.gym

import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.SplittableRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.hours

private val phase1PpoEnabled = System.getProperty("phase1.ppo") == "true"

/** PPO rollouts deal apart from collection (20260930 + g), DAgger (500000000 + g) and tournaments (910000000 + g). */
private const val PPO_BASE_SEED = 600_000_000L

/**
 * Opt-in P1 PPO rollouts: the learner checkpoint *samples* its moves (`serve --sample`) against a
 * league of opponents, and every learner decision is written with the probability it had
 * (`logprob`), the value estimate (`value`), the affordability mask the draw used (`allowed`),
 * whether the engine could execute it (`executed`) and the final game result (`outcome`).
 * `argentum_ml.p1.ppo` turns one such batch into the next checkpoint.
 *
 * League: `-Dphase1.league=self@6,<ckpt dir>@1,engine@1` — opponent and relative weight, drawn per
 * game from a seeded RNG. `self` plays the learner against itself and records both seats; checkpoint
 * opponents play argmax; `engine` is the engine AI (slow: one engine game costs several model games).
 * The learner always sits in seat A; [Phase1SelfPlayCollector.gameConfig] alternates decks and the
 * starting player by game number, so the learner plays both decks from both positions.
 *
 * `-Dphase1.ppo=true -Dphase1.checkpoint=DIR -Dphase1.league=... -Dphase1.games=N -Dphase1.firstGame=G
 *  -Dphase1.workers=W -Dphase1.policyProcesses=P -Dphase1.gameTimeoutSeconds=1200 -Dphase1.outputDir=DIR`
 */
class Phase1PpoCollectTest : FunSpec({
    test("collects PPO rollouts of a sampling learner against a league")
        .config(enabled = phase1PpoEnabled, timeout = 48.hours) {
            val checkpoint = Path.of(checkNotNull(System.getProperty("phase1.checkpoint")) { "phase1.checkpoint" })
            val games = System.getProperty("phase1.games")?.toInt() ?: 20
            val firstGame = System.getProperty("phase1.firstGame")?.toInt() ?: 0
            val workers = System.getProperty("phase1.workers")?.toInt() ?: 8
            val policyProcesses = System.getProperty("phase1.policyProcesses")?.toInt() ?: workers
            val maxSteps = System.getProperty("phase1.maxSteps")?.toInt() ?: 5_000
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val mlRoot = repositoryRoot.resolve("ml")
            val python = Path.of(System.getProperty("phase1.python") ?: mlRoot.resolve(".venv/Scripts/python.exe").toString())
            val outputDirectory = Path.of(
                System.getProperty("phase1.outputDir") ?: repositoryRoot.resolve("gym/build/phase1-ppo").toString(),
            )
            Files.createDirectories(outputDirectory)

            val learner = Phase1Tournament.SeatSpec.Model(checkpoint)
            // (opponent seat or null for self-play, weight)
            val league: List<Pair<Phase1Tournament.SeatSpec?, Double>> =
                (System.getProperty("phase1.league") ?: "self").split(',').filter { it.isNotBlank() }.map { entry ->
                    val at = entry.lastIndexOf('@')
                    val spec = if (at >= 0) entry.substring(0, at) else entry
                    val weight = if (at >= 0) entry.substring(at + 1).toDouble() else 1.0
                    val seat = Phase1Tournament.SeatSpec.parse(spec.trim())
                    // The learner's own directory as an opponent is self-play.
                    val opponent = if (spec.trim() == "self" || seat == learner) null else seat
                    opponent to weight
                }
            check(league.isNotEmpty() && league.all { it.second > 0 }) { "phase1.league needs positive weights" }
            val totalWeight = league.sumOf { it.second }
            fun label(opponent: Phase1Tournament.SeatSpec?) = opponent?.label ?: "self"

            val learnerWorker = Phase1Tournament.PolicyWorker(checkpoint, python, mlRoot, processes = policyProcesses, sample = true)
            val opponentWorkers = league.mapNotNull { (it.first as? Phase1Tournament.SeatSpec.Model)?.checkpoint }.distinct()
                .associateWith { Phase1Tournament.PolicyWorker(it, python, mlRoot, processes = (policyProcesses / 2).coerceAtLeast(1)) }
            val policyWorkers = opponentWorkers + (checkpoint to learnerWorker)

            val resolver = DeckResolver(Phase1Tournament.Registries.card)
            val decks = mapOf(
                "Akiri" to Phase1SelfPlayCollector.lockedDeck(repositoryRoot, "akiri-v0.1.txt"),
                "Chevill" to Phase1SelfPlayCollector.lockedDeck(repositoryRoot, "chevill-v0.1.txt"),
            )
            val sourceCommit = runCatching {
                ProcessBuilder("git", "rev-parse", "HEAD").directory(repositoryRoot.toFile())
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")
            Files.writeString(
                outputDirectory.resolve("manifest-%06d.json".format(firstGame)),
                buildJsonObject {
                    put("schema", Phase1SelfPlayCollector.SAMPLE_SCHEMA)
                    put("source", "ppo")
                    put("sourceCommit", sourceCommit)
                    put("checkpoint", learner.label)
                    put("league", JsonArray(league.map { (opponent, weight) -> JsonPrimitive("${label(opponent)}@$weight") }))
                    put("firstGame", firstGame)
                    put("games", games)
                    put("baseSeed", PPO_BASE_SEED)
                }.toString() + "\n",
            )
            val results = outputDirectory.resolve("results-%06d.jsonl".format(firstGame))

            val decisions = AtomicInteger()
            val failed = AtomicInteger()
            val timedOut = AtomicInteger()
            // A model can steer the engine AI into positions where one of its searches runs for an
            // hour; such a game is abandoned after this long so it cannot hold up the whole batch.
            val gameTimeoutMillis = (System.getProperty("phase1.gameTimeoutSeconds")?.toLong() ?: 1_200L) * 1_000L
            val gameStarted = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            val started = System.nanoTime()
            // Daemon threads: an abandoned game must not keep the test JVM alive.
            val pool = Executors.newFixedThreadPool(workers) { task -> Thread(task).apply { isDaemon = true } }
            val summaries = try {
                (firstGame until firstGame + games).map { game ->
                    game to pool.submit(Callable {
                        gameStarted[game] = System.currentTimeMillis()
                        val config = Phase1SelfPlayCollector.gameConfig(game, PPO_BASE_SEED, resolver, decks)
                        // SplittableRandom mixes its seed; java.util.Random's first draw for consecutive
                        // seeds is nearly identical and put every game against the same opponent.
                        var roll = SplittableRandom(PPO_BASE_SEED * 31 + game).nextDouble() * totalWeight
                        val opponent = (league.firstOrNull { (_, weight) -> roll -= weight; roll < 0 } ?: league.last()).first
                        val ids = config.players.map { checkNotNull(it.playerId) }
                        val learnerIds: Set<EntityId> = if (opponent == null) ids.toSet() else setOf(ids[0])
                        val recorded = mutableListOf<Phase1Tournament.ModelDecision>()
                        val observer = Phase1Tournament.ModelDecisionObserver { decision ->
                            if (decision.actor in learnerIds && decision.reply != null) recorded += decision
                        }
                        val result = try {
                            Phase1Tournament.playGame(
                                game = game,
                                config = config,
                                seatA = learner,
                                seatB = opponent ?: learner,
                                workers = policyWorkers,
                                maxSteps = maxSteps,
                                observer = observer,
                            )
                        } catch (failure: Exception) {
                            failed.incrementAndGet()
                            println("  game=$game failed: ${failure::class.simpleName}: ${failure.cause?.message ?: failure.message}")
                            return@Callable null
                        }
                        val winnerId = when (result.winner) {
                            "A" -> ids[0]
                            "B" -> ids[1]
                            else -> null
                        }
                        val file = outputDirectory.resolve("game-%06d.jsonl.gz".format(game))
                        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter(Charsets.UTF_8).use { writer ->
                            for (decision in recorded) {
                                val reply = checkNotNull(decision.reply)
                                val outcome = when {
                                    !result.terminal || winnerId == null -> 0
                                    decision.actor == winnerId -> 1
                                    else -> -1
                                }
                                val row = JsonObject(
                                    decision.sample + mapOf(
                                        "chosen" to JsonPrimitive(reply.index),
                                        "played" to JsonPrimitive(reply.index),
                                        "logprob" to JsonPrimitive(reply.logprob),
                                        "value" to JsonPrimitive(reply.value),
                                        "allowed" to JsonArray(decision.candidates.map { JsonPrimitive(it.affordable) }),
                                        "executed" to JsonPrimitive(decision.executed),
                                        "outcome" to JsonPrimitive(outcome),
                                        "source" to JsonPrimitive("ppo"),
                                        "checkpoint" to JsonPrimitive(learner.label),
                                        "opponent" to JsonPrimitive(label(opponent)),
                                    ),
                                )
                                writer.write(row.toString())
                                writer.write("\n")
                            }
                        }
                        decisions.addAndGet(recorded.size)
                        val line = buildJsonObject {
                            put("schema", "argentum-p1-ppo-rollout-result@v1")
                            put("game", game)
                            put("learner", learner.label)
                            put("opponent", label(opponent))
                            put("learnerDeck", config.players[0].name)
                            put("aStarts", result.aStarts)
                            put("terminal", result.terminal)
                            put("winner", result.winner)
                            put("turns", result.turns)
                            put("decisions", recorded.size)
                            put("modelFallbacks", result.modelFallbacks)
                            put("loopBreaks", result.loopBreaks)
                            put("seconds", result.seconds)
                        }.toString()
                        synchronized(results) {
                            Files.writeString(results, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        }
                        println("  $line")
                        result
                    })
                }.mapNotNull { (game, future) ->
                    var summary: Phase1Tournament.GameResult? = null
                    while (true) {
                        try {
                            summary = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                            break
                        } catch (_: java.util.concurrent.TimeoutException) {
                            val since = gameStarted[game] ?: continue
                            if (System.currentTimeMillis() - since > gameTimeoutMillis) {
                                future.cancel(true)
                                timedOut.incrementAndGet()
                                println("  game=$game abandoned after ${gameTimeoutMillis / 1000}s")
                                break
                            }
                        }
                    }
                    summary
                }
            } finally {
                pool.shutdown()
                policyWorkers.values.forEach { it.close() }
            }
            val footer = "# games=${summaries.size}/$games failed=${failed.get()} timedOut=${timedOut.get()} decisions=${decisions.get()} " +
                "learnerWinsSeatA=${summaries.count { it.winner == "A" }} unfinished=${summaries.count { !it.terminal }} " +
                "wallSeconds=${"%.1f".format((System.nanoTime() - started) / 1e9)}"
            println(footer)
            Files.writeString(outputDirectory.resolve("summary-%06d.txt".format(firstGame)), footer + "\n")
        }
})
