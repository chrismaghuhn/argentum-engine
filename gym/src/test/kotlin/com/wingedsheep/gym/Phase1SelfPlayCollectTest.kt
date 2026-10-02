package com.wingedsheep.gym

import com.wingedsheep.gym.service.DeckResolver
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.hours

private val phase1CollectEnabled = System.getProperty("phase1.collect") == "true"

/**
 * Opt-in Phase 1 teacher-data run: `ka`-style Gradle entry for [Phase1SelfPlayCollector].
 *
 * `-Dphase1.collect=true -Dphase1.games=N -Dphase1.workers=W -Dphase1.outputDir=DIR`
 * (optional `phase1.firstGame`, `phase1.baseSeed`, `phase1.profile`, `phase1.maxSteps`).
 * Game `g` always uses seed `baseSeed + g` and the same seat orientation, so a run can be extended
 * by starting at `firstGame`. The seed fixes the deal; the engine AI's play also depends on its
 * wall-clock search budget (see docs/ml/p1-first-playing-model.md).
 */
class Phase1SelfPlayCollectTest : FunSpec({
    test("collects engine AI self-play behavior-cloning samples")
        .config(enabled = phase1CollectEnabled, timeout = 48.hours) {
            val games = System.getProperty("phase1.games")?.toInt() ?: 8
            val firstGame = System.getProperty("phase1.firstGame")?.toInt() ?: 0
            val workers = System.getProperty("phase1.workers")?.toInt() ?: 4
            val baseSeed = System.getProperty("phase1.baseSeed")?.toLong() ?: 20_260_930L
            val maxSteps = System.getProperty("phase1.maxSteps")?.toInt() ?: 20_000
            val profileId = System.getProperty("phase1.profile") ?: "production-candidate-expiring"
            val profile = Phase1SelfPlayCollector.profileFor(profileId)
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val outputDirectory = Path.of(
                System.getProperty("phase1.outputDir")
                    ?: repositoryRoot.resolve("gym/build/phase1-selfplay").toString(),
            )
            Files.createDirectories(outputDirectory)
            val registry = A9TrustedGenerationHarness.actorRegistry()
            val resolver = DeckResolver(registry)
            val matchups = Phase1SelfPlayCollector.Matchups(repositoryRoot, resolver)
            val sourceCommit = runCatching {
                ProcessBuilder("git", "rev-parse", "HEAD").directory(repositoryRoot.toFile())
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")

            Files.writeString(
                outputDirectory.resolve("manifest-%06d.json".format(firstGame)),
                buildJsonObject {
                    put("schema", Phase1SelfPlayCollector.SAMPLE_SCHEMA)
                    put("sourceCommit", sourceCommit)
                    put("teacherProfile", profile.id)
                    put("firstGame", firstGame)
                    put("games", games)
                    put("baseSeed", baseSeed)
                    put("maxSteps", maxSteps)
                    put("decks", matchups.description)
                }.toString() + "\n",
            )

            val started = System.nanoTime()
            val pool = Executors.newFixedThreadPool(workers)
            val summaries = try {
                (firstGame until firstGame + games).map { game ->
                    pool.submit(Callable {
                        Phase1SelfPlayCollector.playAndRecord(
                            game = game,
                            config = matchups.config(game, baseSeed),
                            registry = registry,
                            profile = profile,
                            maxSteps = maxSteps,
                            outputDirectory = outputDirectory,
                        ).also { summary ->
                            println(
                                "  game=${summary.game} terminal=${summary.terminal} winner=${summary.winner} " +
                                    "turns=${summary.turns} samples=${summary.samples} " +
                                    "unmatched=${summary.unmatchedChoices} seconds=${"%.1f".format(summary.seconds)}",
                            )
                        }
                    })
                }.map { it.get() }
            } finally {
                pool.shutdown()
            }
            val wallSeconds = (System.nanoTime() - started) / 1e9

            val header = "game\tseat0\tstartingPlayer\tterminal\twinner\tturns\tengineSteps\tsamples\tunmatchedChoices\tseconds"
            val rows = summaries.map {
                listOf(
                    it.game, it.seat0, it.startingPlayer, it.terminal, it.winner, it.turns, it.engineSteps,
                    it.samples, it.unmatchedChoices, "%.2f".format(it.seconds),
                ).joinToString("\t")
            }
            val footer = "# games=$games terminal=${summaries.count { it.terminal }} " +
                "akiriWins=${summaries.count { it.winner == "Akiri" }} chevillWins=${summaries.count { it.winner == "Chevill" }} " +
                "samples=${summaries.sumOf { it.samples }} unmatched=${summaries.sumOf { it.unmatchedChoices }} " +
                "workers=$workers wallSeconds=${"%.1f".format(wallSeconds)}"
            println(footer)
            Files.writeString(
                outputDirectory.resolve("summary-%06d.tsv".format(firstGame)),
                (listOf(header) + rows + footer).joinToString("\n", postfix = "\n"),
            )
        }
})
