package com.wingedsheep.gym

import com.wingedsheep.gym.service.DeckResolver
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.hours

private val phase1TournamentEnabled = System.getProperty("phase1.tournament") == "true"

/** Evaluation seeds live far away from collection seeds (20260930 + g) so no evaluated deal was trained on. */
private const val TOURNAMENT_BASE_SEED = 910_000_000L

/**
 * Opt-in Phase 1 match between two seats:
 * `-Dphase1.tournament=true -Dphase1.seatA=<ckpt dir|engine> -Dphase1.seatB=<ckpt dir|engine>
 *  -Dphase1.games=N -Dphase1.workers=W -Dphase1.results=<file.jsonl>`.
 * Game g gives seat A Akiri on even games and Chevill on odd ones, and alternates the starting player
 * every two games, so each seat plays both decks from both positions. Results are appended as one JSON
 * line per game, which the devlog/Elo tooling reads.
 */
class Phase1TournamentTest : FunSpec({
    test("plays a Phase 1 match between two seats")
        .config(enabled = phase1TournamentEnabled, timeout = 24.hours) {
            val seatA = Phase1Tournament.SeatSpec.parse(checkNotNull(System.getProperty("phase1.seatA")) { "phase1.seatA" })
            val seatB = Phase1Tournament.SeatSpec.parse(checkNotNull(System.getProperty("phase1.seatB")) { "phase1.seatB" })
            val games = System.getProperty("phase1.games")?.toInt() ?: 20
            val firstGame = System.getProperty("phase1.firstGame")?.toInt() ?: 0
            val workers = System.getProperty("phase1.workers")?.toInt() ?: 8
            val maxSteps = System.getProperty("phase1.maxSteps")?.toInt() ?: 5_000
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val python = Path.of(System.getProperty("phase1.python") ?: repositoryRoot.resolve("ml/.venv/Scripts/python.exe").toString())
            val results = Path.of(
                System.getProperty("phase1.results") ?: repositoryRoot.resolve("gym/build/phase1-tournament.jsonl").toString(),
            )
            val showcaseGames = System.getProperty("phase1.showcase")?.toInt() ?: 0
            val showcaseDir = Path.of(System.getProperty("phase1.showcaseDir") ?: results.parent.resolve("showcase").toString())
            val sourceCommit = runCatching {
                ProcessBuilder("git", "rev-parse", "HEAD").directory(repositoryRoot.toFile())
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")
            val resolver = DeckResolver(Phase1Tournament.Registries.card)
            val matchups = Phase1SelfPlayCollector.Matchups(repositoryRoot, resolver)
            val checkpoints = listOf(seatA, seatB).filterIsInstance<Phase1Tournament.SeatSpec.Model>()
                .map { it.checkpoint }.distinct()
            val policyWorkers = checkpoints.associateWith {
                Phase1Tournament.PolicyWorker(
                    it, python, repositoryRoot.resolve("ml"),
                    processes = System.getProperty("phase1.policyProcesses")?.toInt() ?: 1,
                )
            }
            Files.createDirectories(results.parent)
            // Per-game limit (default 10 min; 0 disables): an abandoned game is left out of the results
            // and counted, so one runaway engine-AI search cannot hold a whole match.
            val gameTimeoutMillis = (System.getProperty("phase1.gameTimeoutSeconds")?.toLong() ?: 600L) * 1_000L
            val gameStarted = java.util.concurrent.ConcurrentHashMap<Int, Long>()
            var timedOut = 0
            // Daemon threads: an abandoned game must not keep the test JVM alive.
            val pool = Executors.newFixedThreadPool(workers) { task -> Thread(task).apply { isDaemon = true } }
            try {
                val finished = (firstGame until firstGame + games).map { game ->
                    game to pool.submit(Callable {
                        gameStarted[game] = System.currentTimeMillis()
                        val config = matchups.config(game, TOURNAMENT_BASE_SEED)
                        // An engine failure inside one game (a model can reach positions the engine AI
                        // never does) is recorded with its seed for reproduction instead of aborting the
                        // whole match; error lines are excluded from ratings.
                        val result = try { Phase1Tournament.playGame(
                            game = game,
                            config = config,
                            seatA = seatA,
                            seatB = seatB,
                            workers = policyWorkers,
                            maxSteps = maxSteps,
                            showcase = if (game - firstGame < showcaseGames) {
                                Phase1Tournament.Showcase(
                                    directory = showcaseDir,
                                    gameId = "p1-${seatA.label}-vs-${seatB.label}-g$game".replace(Regex("[^A-Za-z0-9._-]"), "-"),
                                    engineVersion = sourceCommit,
                                )
                            } else {
                                null
                            },
                        ) } catch (failure: Exception) {
                            val errorLine = buildJsonObject {
                                put("schema", "argentum-p1-match-result@v1")
                                put("game", game)
                                put("seatA", seatA.label)
                                put("seatB", seatB.label)
                                put("deckA", config.players[0].name)
                                put("deckB", config.players[1].name)
                                put("aStarts", config.startingPlayerIndex == 0)
                                put("terminal", false)
                                put("winner", "-")
                                put("turns", 0)
                                put("engineSteps", 0)
                                put("modelChoices", 0)
                                put("modelFallbacks", 0)
                                put("loopBreaks", 0)
                                put("seconds", 0.0)
                                put("seed", config.seed ?: -1L)
                                put("error", "${failure::class.simpleName}: ${failure.cause?.message ?: failure.message}")
                            }.toString()
                            synchronized(results) {
                                Files.writeString(results, errorLine + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                            }
                            println("  $errorLine")
                            return@Callable null
                        }
                        val line = buildJsonObject {
                            put("schema", "argentum-p1-match-result@v1")
                            put("game", result.game)
                            put("seatA", result.seatA)
                            put("seatB", result.seatB)
                            put("deckA", result.deckA)
                            put("deckB", config.players[1].name)
                            put("aStarts", result.aStarts)
                            put("terminal", result.terminal)
                            put("winner", result.winner)
                            put("turns", result.turns)
                            put("engineSteps", result.engineSteps)
                            put("modelChoices", result.modelChoices)
                            put("modelFallbacks", result.modelFallbacks)
                            put("loopBreaks", result.loopBreaks)
                            put("seconds", result.seconds)
                            result.replayFile?.let { put("replayFile", it) }
                            result.replayFidelity?.let { put("replayFidelity", it) }
                            // {"card": {"types": "CREATURE", "Cast": [offered, played], ...}} per seat
                            fun usageJson(cards: Map<String, Phase1Tournament.CardUsage.Stats>) =
                                kotlinx.serialization.json.JsonObject(
                                    cards.mapValues { (_, stats) ->
                                        buildJsonObject {
                                            put("types", stats.types)
                                            stats.kinds.forEach { (kind, c) ->
                                                put(kind, kotlinx.serialization.json.JsonArray(c.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                                            }
                                        }
                                    },
                                )
                            put("cardsA", usageJson(result.cardsA))
                            put("cardsB", usageJson(result.cardsB))
                        }.toString()
                        synchronized(results) {
                            Files.writeString(results, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        }
                        println("  $line")
                        result
                    })
                }.mapNotNull { (game, future) ->
                    if (gameTimeoutMillis <= 0) return@mapNotNull future.get()
                    var result: Phase1Tournament.GameResult? = null
                    while (true) {
                        try {
                            result = future.get(5, java.util.concurrent.TimeUnit.SECONDS)
                            break
                        } catch (_: java.util.concurrent.TimeoutException) {
                            val since = gameStarted[game] ?: continue
                            if (System.currentTimeMillis() - since > gameTimeoutMillis) {
                                future.cancel(true)
                                timedOut++
                                println("  game=$game abandoned after ${gameTimeoutMillis / 1000}s")
                                break
                            }
                        }
                    }
                    result
                }
                val aWins = finished.count { it.winner == "A" }
                val bWins = finished.count { it.winner == "B" }
                println(
                    "# ${seatA.label} vs ${seatB.label}: games=${finished.size} A=$aWins B=$bWins " +
                        "unfinished=${finished.count { !it.terminal }} fallbacks=${finished.sumOf { it.modelFallbacks }}" +
                        "/${finished.sumOf { it.modelChoices }} loopBreaks=${finished.sumOf { it.loopBreaks }} timedOut=$timedOut",
                )
            } finally {
                pool.shutdown()
                policyWorkers.values.forEach { it.close() }
            }
        }
})
