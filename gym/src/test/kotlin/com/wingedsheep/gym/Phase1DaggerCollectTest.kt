package com.wingedsheep.gym

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.ai.engine.budget.RolloutBudgetPolicy
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.hours

private val phase1DaggerEnabled = System.getProperty("phase1.dagger") == "true"

/** DAgger deals live apart from collection (20260930 + g) and tournament (910000000 + g) seeds. */
private const val DAGGER_BASE_SEED = 500_000_000L

/**
 * Opt-in P1 DAgger round: a model checkpoint plays (against itself, the engine AI, or another
 * checkpoint) and, at a sampled share of its decisions, the engine AI teacher labels the position
 * the *model* reached with the action it would have taken. The model's own action is what executes.
 *
 * The teacher runs on a work-bounded rollout budget (`-Dphase1.teacherPlayouts`, no wall clock), so
 * labels do not depend on machine load and can be stronger than the engine AI's live play.
 *
 * Output uses the collector's sample schema plus `played` (the model's index), `source` = "dagger",
 * `teacherPlayouts` and `checkpoint`; `chosen` is the teacher's index and `outcome` the game result
 * from the acting player's perspective. Train on it together with the engine-AI data.
 *
 * `-Dphase1.dagger=true -Dphase1.checkpoint=DIR -Dphase1.opponent=self|engine|DIR -Dphase1.games=N
 *  -Dphase1.workers=W -Dphase1.labelRate=0.3 -Dphase1.teacherPlayouts=16 -Dphase1.outputDir=DIR`
 */
class Phase1DaggerCollectTest : FunSpec({
    test("collects DAgger teacher labels on model-reached positions")
        .config(enabled = phase1DaggerEnabled, timeout = 48.hours) {
            val checkpoint = Path.of(checkNotNull(System.getProperty("phase1.checkpoint")) { "phase1.checkpoint" })
            val opponentSpec = System.getProperty("phase1.opponent") ?: "self"
            val games = System.getProperty("phase1.games")?.toInt() ?: 20
            val firstGame = System.getProperty("phase1.firstGame")?.toInt() ?: 0
            val workers = System.getProperty("phase1.workers")?.toInt() ?: 8
            val labelRate = System.getProperty("phase1.labelRate")?.toDouble() ?: 0.3
            val teacherPlayouts = System.getProperty("phase1.teacherPlayouts")?.toInt() ?: 16
            val maxSteps = System.getProperty("phase1.maxSteps")?.toInt() ?: 5_000
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val python = Path.of(System.getProperty("phase1.python") ?: repositoryRoot.resolve("ml/.venv/Scripts/python.exe").toString())
            val outputDirectory = Path.of(
                System.getProperty("phase1.outputDir") ?: repositoryRoot.resolve("gym/build/phase1-dagger").toString(),
            )
            Files.createDirectories(outputDirectory)

            val seatA = Phase1Tournament.SeatSpec.Model(checkpoint)
            val seatB = if (opponentSpec == "self") seatA else Phase1Tournament.SeatSpec.parse(opponentSpec)
            val base = AiProfile.PRODUCTION_CANDIDATE_EXPIRING
            val teacherProfile = base.copy(budgetPolicy = WorkBoundedBudgetPolicy(RolloutBudgetPolicy(teacherPlayouts)))
            val resolver = DeckResolver(Phase1Tournament.Registries.card)
            val decks = mapOf(
                "Akiri" to Phase1SelfPlayCollector.lockedDeck(repositoryRoot, "akiri-v0.1.txt"),
                "Chevill" to Phase1SelfPlayCollector.lockedDeck(repositoryRoot, "chevill-v0.1.txt"),
            )
            val checkpoints = listOf(seatA, seatB).filterIsInstance<Phase1Tournament.SeatSpec.Model>()
                .map { it.checkpoint }.distinct()
            val policyWorkers = checkpoints.associateWith {
                Phase1Tournament.PolicyWorker(it, python, repositoryRoot.resolve("ml"))
            }
            val sourceCommit = runCatching {
                ProcessBuilder("git", "rev-parse", "HEAD").directory(repositoryRoot.toFile())
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")
            Files.writeString(
                outputDirectory.resolve("manifest-%06d.json".format(firstGame)),
                buildJsonObject {
                    put("schema", Phase1SelfPlayCollector.SAMPLE_SCHEMA)
                    put("source", "dagger")
                    put("sourceCommit", sourceCommit)
                    put("checkpoint", seatA.label)
                    put("opponent", seatB.label)
                    put("teacherProfile", base.id)
                    put("teacherPlayouts", teacherPlayouts)
                    put("labelRate", labelRate)
                    put("firstGame", firstGame)
                    put("games", games)
                    put("baseSeed", DAGGER_BASE_SEED)
                }.toString() + "\n",
            )

            val labelled = AtomicInteger()
            val unmatched = AtomicInteger()
            val failed = AtomicInteger()
            val started = System.nanoTime()
            val pool = Executors.newFixedThreadPool(workers)
            val summaries = try {
                (firstGame until firstGame + games).map { game ->
                    pool.submit(Callable {
                        val config = Phase1SelfPlayCollector.gameConfig(game, DAGGER_BASE_SEED, resolver, decks)
                        val rng = Random(DAGGER_BASE_SEED * 31 + game)
                        val teachers = HashMap<EntityId, AIPlayer>()
                        val samples = mutableListOf<JsonObject>()
                        val observer = Phase1Tournament.ModelDecisionObserver { decision ->
                            if (rng.nextDouble() >= labelRate) return@ModelDecisionObserver
                            val teacher = teachers.getOrPut(decision.actor) {
                                AIPlayer.create(Phase1Tournament.Registries.card, decision.actor, teacherProfile)
                            }
                            val choice = teacher.chooseFrom(decision.state, decision.legal)
                            val teacherIndex = decision.candidates.indexOfFirst { view ->
                                val template = decision.registry.legalActions.firstOrNull { it.first == view.actionId }?.second
                                template != null && decision.environment.isCurrentActionCandidate(template.action, choice.action)
                            }
                            if (teacherIndex < 0) {
                                unmatched.incrementAndGet()
                                return@ModelDecisionObserver
                            }
                            samples += JsonObject(
                                decision.sample + mapOf(
                                    "chosen" to JsonPrimitive(teacherIndex),
                                    "played" to JsonPrimitive(decision.modelIndex ?: -1),
                                    "source" to JsonPrimitive("dagger"),
                                    "teacherPlayouts" to JsonPrimitive(teacherPlayouts),
                                    "checkpoint" to JsonPrimitive(seatA.label),
                                ),
                            )
                        }
                        val result = try {
                            Phase1Tournament.playGame(
                                game = game,
                                config = config,
                                seatA = seatA,
                                seatB = seatB,
                                workers = policyWorkers,
                                maxSteps = maxSteps,
                                observer = observer,
                            )
                        } catch (failure: Exception) {
                            failed.incrementAndGet()
                            println("  game=$game failed: ${failure::class.simpleName}: ${failure.cause?.message ?: failure.message}")
                            return@Callable null
                        }
                        val deckA = config.players[0].name
                        val deckB = config.players[1].name
                        val winnerDeck = when (result.winner) {
                            "A" -> deckA
                            "B" -> deckB
                            else -> null
                        }
                        val file = outputDirectory.resolve("game-%06d.jsonl.gz".format(game))
                        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter(Charsets.UTF_8).use { writer ->
                            for (sample in samples) {
                                val seat = sample["seat"]?.jsonPrimitive?.content
                                val outcome = when {
                                    !result.terminal || winnerDeck == null -> 0
                                    seat == winnerDeck -> 1
                                    else -> -1
                                }
                                writer.write(JsonObject(sample + ("outcome" to JsonPrimitive(outcome))).toString())
                                writer.write("\n")
                            }
                        }
                        labelled.addAndGet(samples.size)
                        val line = "game=$game terminal=${result.terminal} winner=${winnerDeck ?: "-"} turns=${result.turns} " +
                            "modelChoices=${result.modelChoices} labels=${samples.size} seconds=${"%.1f".format(result.seconds)}"
                        println("  $line")
                        line
                    })
                }.mapNotNull { it.get() }
            } finally {
                pool.shutdown()
                policyWorkers.values.forEach { it.close() }
            }
            val footer = "# games=${summaries.size}/$games failed=${failed.get()} labels=${labelled.get()} " +
                "unmatched=${unmatched.get()} teacherPlayouts=$teacherPlayouts labelRate=$labelRate " +
                "wallSeconds=${"%.1f".format((System.nanoTime() - started) / 1e9)}"
            println(footer)
            Files.writeString(
                outputDirectory.resolve("summary-%06d.txt".format(firstGame)),
                (summaries + footer).joinToString("\n", postfix = "\n"),
            )
        }
})
