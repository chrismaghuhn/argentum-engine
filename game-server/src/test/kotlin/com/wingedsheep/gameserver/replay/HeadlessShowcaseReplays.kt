package com.wingedsheep.gameserver.replay

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Opt-in generator of showcase replay files: engine AI vs engine AI on Akiri vs Chevill, played
 * headlessly through the Gym environment, recorded, verified, and written to disk for import into
 * the web replay viewer (see `docs/ml/headless-replay-showcase.md`).
 *
 * Skipped unless `-DshowcaseReplays=true`. Run it through `just showcase-replays`, which also sets
 * `-Dbenchmark=true` so the test hang guard allows whole games.
 *
 * - `showcaseReplaysGames` — how many games (default 2)
 * - `showcaseReplaysSeed` — seed of the first game; game i uses seed + i and alternates who starts (default 1)
 * - `showcaseReplaysOutputDir` — where the files go (default `build/showcase-replays` in the repo root)
 * - `showcaseReplaysEngineVersion` — build id stamped into each replay (the recipe passes the git sha)
 */
class HeadlessShowcaseReplays : FunSpec({

    test("record showcase replays of engine AI vs engine AI")
        .config(enabled = System.getProperty("showcaseReplays") == "true") {
            val games = System.getProperty("showcaseReplaysGames")?.toInt() ?: 2
            val baseSeed = System.getProperty("showcaseReplaysSeed")?.toLong() ?: 1L
            val engineVersion = System.getProperty("showcaseReplaysEngineVersion") ?: CompactReplay.UNKNOWN_VERSION
            val outputDir = (System.getProperty("showcaseReplaysOutputDir")?.let(Path::of)
                ?: repositoryRoot().resolve("build").resolve("showcase-replays"))
                .toAbsolutePath().normalize()
            Files.createDirectories(outputDir)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val reconstructor = HeadlessEngineAiGame.serverReconstructor()

            val rows = mutableListOf<String>()
            val failures = mutableListOf<String>()
            repeat(games) { i ->
                val seed = baseSeed + i
                val startingPlayerIndex = i % 2
                val gameId = "showcase-$stamp-akiri-chevill-s$seed-p$startingPlayerIndex"
                val started = System.nanoTime()
                val recorded = HeadlessEngineAiGame.play(
                    gameId = gameId,
                    seed = seed,
                    startingPlayerIndex = startingPlayerIndex,
                    engineVersion = engineVersion,
                )
                val replay = recorded.replay
                val fidelity = reconstructor.reconstruct(ReplayCodec.decode(ReplayCodec.encode(replay)))
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0

                val file = outputDir.resolve("$gameId.replay")
                Files.writeString(file, ReplayCodec.encode(replay))
                val outcome = when (val closure = recorded.closure) {
                    null -> "open"
                    else -> closure.kind.name
                }
                rows += "| `${file.fileName}` | $seed | ${replay.players[startingPlayerIndex].name} | " +
                    "${replay.winnerName ?: "—"} | ${recorded.turns} | $outcome | ${recorded.submittedSteps} | " +
                    "${replay.actions.size} | ${fidelity.fidelity} | ${"%.0f".format(seconds)} s |"
                println(
                    "Showcase ${i + 1}/$games: $file — winner ${replay.winnerName ?: "none"}, " +
                        "${recorded.turns} turns, $outcome, ${fidelity.fidelity} (${"%.0f".format(seconds)} s)",
                )
                if (fidelity.fidelity != ReplayFidelity.EXACT) {
                    failures += "$gameId: ${fidelity.fidelity} — ${fidelity.divergenceReason}"
                }
            }

            val index = outputDir.resolve("showcase-$stamp.md")
            Files.writeString(
                index,
                buildString {
                    appendLine("# Showcase replays $stamp")
                    appendLine()
                    appendLine("Engine AI vs engine AI, Akiri vs Chevill (Commander), engine `$engineVersion`.")
                    appendLine("Import with `just replay-import <file>`, then open the printed `/replay/…` path.")
                    appendLine()
                    appendLine("| File | Seed | Starts | Winner | Turns | Closure | AI steps | Replay actions | Fidelity | Time |")
                    appendLine("|---|---|---|---|---|---|---|---|---|---|")
                    rows.forEach(::appendLine)
                },
            )
            println("Showcase index: $index")
            check(failures.isEmpty()) { "Showcase replays that do not re-simulate EXACT:\n" + failures.joinToString("\n") }
        }
})

private fun repositoryRoot(): Path =
    generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
