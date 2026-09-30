package com.wingedsheep.gym

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.ai.engine.budget.BudgetPolicy
import com.wingedsheep.ai.engine.budget.DecisionBudget
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.gameserver.replay.ReplayFingerprint
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.hours

private val determinismTraceEnabled = System.getProperty("perf.trace") == "true"

/**
 * The same tier and search allowances as [inner], without any wall-clock stop. The production
 * tiered budget cuts search at a deadline, so the engine AI plays differently under CPU load and a
 * behavior-parity trace cannot be reproduced; bounded by work alone it can.
 */
internal class WorkBoundedBudgetPolicy(private val inner: BudgetPolicy) : BudgetPolicy {
    override fun budgetFor(state: GameState, playerId: EntityId, meaningfulActions: List<LegalAction>): DecisionBudget =
        unbounded(inner.budgetFor(state, playerId, meaningfulActions))

    override fun budgetForDecision(state: GameState, playerId: EntityId): DecisionBudget =
        unbounded(inner.budgetForDecision(state, playerId))

    private fun unbounded(budget: DecisionBudget) = DecisionBudget(
        budget.tier,
        budget.allowances.copy(combatSearchMillis = DecisionBudget.UNBOUNDED_MILLIS),
        DecisionBudget.UNBOUNDED_MILLIS,
    )
}

/**
 * Opt-in behavior-parity trace for engine/AI performance work: plays seeded engine AI vs engine AI
 * games on the locked Akiri vs Chevill Commander matchup and records, per game, a digest of every
 * submitted action, the winner, turns, steps and the final state fingerprint. A pure performance
 * change must reproduce the file byte for byte (timings are kept in a separate column-free file).
 *
 * `-Dperf.trace=true -Dperf.games=N -Dperf.workers=W -Dperf.out=<file.tsv>`
 */
class EngineAiDeterminismTraceTest : FunSpec({
    test("records a seeded engine AI self-play trace")
        .config(enabled = determinismTraceEnabled, timeout = 8.hours) {
            val games = System.getProperty("perf.games")?.toInt() ?: 6
            val workers = System.getProperty("perf.workers")?.toInt() ?: 3
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val out = Path.of(System.getProperty("perf.out") ?: repositoryRoot.resolve("gym/build/perf-trace.tsv").toString())
            val registry = A9TrustedGenerationHarness.actorRegistry()
            val resolver = DeckResolver(registry)
            fun deck(file: String) = Files.readAllLines(repositoryRoot.resolve("docs/ml/curriculum").resolve(file))
                .filter { it.matches(Regex("^\\d{3}\\t.*")) }
                .map { it.substringAfterLast('\t') }
            val decks = mapOf("Akiri" to deck("akiri-v0.1.txt"), "Chevill" to deck("chevill-v0.1.txt"))

            fun config(game: Int): GameConfig {
                val names = if (game % 2 == 0) listOf("Akiri", "Chevill") else listOf("Chevill", "Akiri")
                return GameConfig(
                    players = names.mapIndexed { index, name ->
                        val cards = decks.getValue(name)
                        PlayerConfig(
                            name = name,
                            deck = resolver.resolve(DeckSpec.Explicit(cards.drop(1).groupingBy { it }.eachCount())),
                            startingLife = 40,
                            playerId = EntityId("perf-$game-seat-$index"),
                            commanderCardName = cards.first(),
                        )
                    },
                    startingHandSize = 7,
                    skipMulligans = true,
                    useHandSmoother = false,
                    startingPlayerIndex = (game / 2) % 2,
                    format = Format.Commander(),
                    attackMode = AttackMode.MULTIPLE,
                    seed = 77_000_000L + game,
                )
            }

            val pool = Executors.newFixedThreadPool(workers)
            val rows = try {
                (0 until games).map { game ->
                    pool.submit(Callable {
                        val cfg = config(game)
                        val environment = GameEnvironment.create(cardRegistry = registry)
                        environment.reset(cfg, maxSteps = 5_000)
                        val ais = cfg.players.associate { p ->
                            val id = checkNotNull(p.playerId)
                            val base = AiProfile.PRODUCTION_CANDIDATE_EXPIRING
                            id to AIPlayer.create(registry, id, base.copy(budgetPolicy = WorkBoundedBudgetPolicy(base.budgetPolicy)))
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var submitted = 0
                        val started = System.nanoTime()
                        while (!environment.isTerminal && !environment.isTruncated) {
                            val actor = environment.agentToAct ?: break
                            val ai = ais.getValue(actor)
                            val pending = environment.pendingDecision
                            val action = if (pending != null) {
                                SubmitDecision(actor, ai.respondToDecision(environment.state, pending))
                            } else {
                                ai.chooseAction(environment.state)
                            }
                            submitted++
                            environment.step(action)
                            // Action text carries runtime decision nonces; the semantic fingerprint
                            // aliases them, so it is the stable per-step evidence of identical play.
                            if (environment.stepCount % 25 == 0) {
                                digest.update((ReplayFingerprint.of(environment.state) + "\n").toByteArray(Charsets.UTF_8))
                            }
                        }
                        val seconds = (System.nanoTime() - started) / 1e9
                        val winner = environment.winnerId?.let { w -> cfg.players.first { it.playerId == w }.name } ?: "-"
                        val row = listOf(
                            game, winner, environment.turnNumber, environment.stepCount, submitted,
                            digest.digest().joinToString("") { "%02x".format(it) },
                            ReplayFingerprint.of(environment.state),
                        ).joinToString("\t")
                        println("  $row\t${"%.1f".format(seconds)}s")
                        row to seconds
                    })
                }.map { it.get() }
            } finally {
                pool.shutdown()
            }
            Files.createDirectories(out.parent)
            Files.writeString(out, rows.joinToString("\n", postfix = "\n") { it.first })
            Files.writeString(
                out.resolveSibling(out.fileName.toString() + ".seconds"),
                rows.joinToString("\n", postfix = "\n") { "%.2f".format(it.second) } +
                    "# total ${"%.1f".format(rows.sumOf { it.second })}\n",
            )
        }
})
