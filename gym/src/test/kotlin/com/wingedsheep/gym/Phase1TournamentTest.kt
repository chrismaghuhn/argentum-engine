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
            val registry = A9TrustedGenerationHarness.actorRegistry()
            val resolver = DeckResolver(registry)
            val decks = mapOf(
                "Akiri" to Phase1SelfPlayCollector.lockedDeck(repositoryRoot, "akiri-v0.1.txt"),
                "Chevill" to Phase1SelfPlayCollector.lockedDeck(repositoryRoot, "chevill-v0.1.txt"),
            )
            val checkpoints = listOf(seatA, seatB).filterIsInstance<Phase1Tournament.SeatSpec.Model>()
                .map { it.checkpoint }.distinct()
            val policyWorkers = checkpoints.associateWith {
                Phase1Tournament.PolicyWorker(it, python, repositoryRoot.resolve("ml"))
            }
            Files.createDirectories(results.parent)
            val pool = Executors.newFixedThreadPool(workers)
            try {
                val finished = (firstGame until firstGame + games).map { game ->
                    pool.submit(Callable {
                        val result = Phase1Tournament.playGame(
                            game = game,
                            config = Phase1SelfPlayCollector.gameConfig(game, TOURNAMENT_BASE_SEED, resolver, decks),
                            registry = registry,
                            seatA = seatA,
                            seatB = seatB,
                            workers = policyWorkers,
                            maxSteps = maxSteps,
                        )
                        val line = buildJsonObject {
                            put("schema", "argentum-p1-match-result@v1")
                            put("game", result.game)
                            put("seatA", result.seatA)
                            put("seatB", result.seatB)
                            put("deckA", result.deckA)
                            put("aStarts", result.aStarts)
                            put("terminal", result.terminal)
                            put("winner", result.winner)
                            put("turns", result.turns)
                            put("engineSteps", result.engineSteps)
                            put("modelChoices", result.modelChoices)
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
                }.map { it.get() }
                val aWins = finished.count { it.winner == "A" }
                val bWins = finished.count { it.winner == "B" }
                println(
                    "# ${seatA.label} vs ${seatB.label}: games=${finished.size} A=$aWins B=$bWins " +
                        "unfinished=${finished.count { !it.terminal }} fallbacks=${finished.sumOf { it.modelFallbacks }}" +
                        "/${finished.sumOf { it.modelChoices }} loopBreaks=${finished.sumOf { it.loopBreaks }}",
                )
            } finally {
                pool.shutdown()
                policyWorkers.values.forEach { it.close() }
            }
        }
})
