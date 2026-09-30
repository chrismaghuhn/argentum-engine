package com.wingedsheep.gym

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

private val phase1MeasureEnabled = System.getProperty("phase1.measure") == "true"

/**
 * Phase 1, step 0: can the built-in engine AI play the locked Akiri vs Chevill Commander matchup
 * to completion against itself, and how fast?
 *
 * Runs [GameEnvironment] in its default LEGACY mode (engine auto-pay and simulator-resolved
 * trivial decisions) with an [AIPlayer] on both seats — the same call the game server makes for
 * its AI seat. Records per game whether it reached a terminal state, the winner, turns, engine
 * steps, AI priority choices and decisions, and wall time, as TSV.
 */
class Phase1EngineAiCommanderSelfPlayMeasurementTest : FunSpec({
    test("engine AI vs engine AI on the locked Commander matchup")
        .config(enabled = phase1MeasureEnabled, timeout = 8.hours) {
            val games = System.getProperty("phase1.games")?.toInt() ?: 20
            val maxSteps = System.getProperty("phase1.maxSteps")?.toInt() ?: 20_000
            val profile = profileFor(System.getProperty("phase1.profile") ?: "production-candidate-expiring")
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val outputFile = Path.of(
                System.getProperty("phase1.outputFile")
                    ?: repositoryRoot.resolve("gym/build/phase1-selfplay-measure.tsv").toString(),
            )
            val registry = A9TrustedGenerationHarness.actorRegistry()
            val resolver = DeckResolver(registry)
            val decks = mapOf(
                "Akiri" to lockedDeck(repositoryRoot, "akiri-v0.1.txt"),
                "Chevill" to lockedDeck(repositoryRoot, "chevill-v0.1.txt"),
            )

            val lines = mutableListOf(
                listOf(
                    "game", "seat0", "startingPlayer", "terminal", "winner", "turns", "engineSteps",
                    "aiPriorityCalls", "aiNonPassActions", "aiDecisions", "seconds",
                ).joinToString("\t"),
            )
            var terminalGames = 0
            var totalSeconds = 0.0
            for (game in 0 until games) {
                // Alternate seat order and starting player so both decks see both roles.
                val seat0 = if (game % 2 == 0) "Akiri" else "Chevill"
                val seat1 = if (seat0 == "Akiri") "Chevill" else "Akiri"
                val startingPlayer = (game / 2) % 2
                val players = listOf(seat0, seat1).mapIndexed { index, name ->
                    val deck = decks.getValue(name)
                    PlayerConfig(
                        name = name,
                        deck = resolver.resolve(DeckSpec.Explicit(deck.drop(1).groupingBy { it }.eachCount())),
                        startingLife = 40,
                        playerId = EntityId("p1-measure-$game-seat-$index"),
                        commanderCardName = deck.first(),
                    )
                }
                val config = GameConfig(
                    players = players,
                    startingHandSize = 7,
                    skipMulligans = true,
                    useHandSmoother = false,
                    startingPlayerIndex = startingPlayer,
                    format = Format.Commander(),
                    attackMode = AttackMode.MULTIPLE,
                    seed = 20_260_930L + game,
                )
                val environment = GameEnvironment.create(cardRegistry = registry)
                val started = System.nanoTime()
                environment.reset(config, maxSteps = maxSteps)
                val ais = players.associate { player ->
                    val id = checkNotNull(player.playerId)
                    id to AIPlayer.create(registry, id, profile)
                }
                var priorityCalls = 0
                var nonPassActions = 0
                var decisions = 0
                while (!environment.isTerminal && !environment.isTruncated) {
                    val actor = environment.agentToAct ?: break
                    val ai = ais.getValue(actor)
                    val pending = environment.pendingDecision
                    val action = if (pending != null) {
                        decisions++
                        SubmitDecision(actor, ai.respondToDecision(environment.state, pending))
                    } else {
                        priorityCalls++
                        ai.chooseAction(environment.state).also { if (it !is PassPriority) nonPassActions++ }
                    }
                    val traceFrom = System.getProperty("phase1.traceFrom")?.toInt()
                    if (traceFrom != null && environment.stepCount in traceFrom until traceFrom + 12) {
                        val before = environment.state
                        val describe = { id: EntityId? ->
                            id?.let { before.getEntity(it)?.get<CardComponent>()?.name ?: it.value } ?: "-"
                        }
                        val detail = when (action) {
                            is ActivateAbility -> "ActivateAbility source=${describe(action.sourceId)} " +
                                "ability=${action.abilityId} targets=${action.targets} x=${action.xValue}"
                            else -> action.toString().take(200)
                        }
                        println("  [trace $game] step=${environment.stepCount} actor=${describe(actor)} $detail")
                    }
                    val traceBefore = if (traceFrom != null && environment.stepCount in traceFrom until traceFrom + 3) {
                        environment.state
                    } else {
                        null
                    }
                    environment.step(action)
                    if (traceBefore != null) {
                        val after = environment.state
                        val changes = (traceBefore.entities.keys + after.entities.keys).flatMap { id ->
                            val a = traceBefore.getEntity(id)?.all()?.associateBy { it::class.simpleName }.orEmpty()
                            val b = after.getEntity(id)?.all()?.associateBy { it::class.simpleName }.orEmpty()
                            (a.keys + b.keys).filter { a[it] != b[it] }.map { "${id.value}:$it" }
                        }
                        val fields = listOf(
                            "zones" to (traceBefore.zones == after.zones),
                            "stack" to (traceBefore.stack == after.stack),
                            "floatingEffects" to (traceBefore.floatingEffects == after.floatingEffects),
                            "turnNumber" to (traceBefore.turnNumber == after.turnNumber),
                        ).filterNot { it.second }.map { it.first }
                        val normalizedDiff = GameState::class.java.declaredFields.filter { f ->
                            f.isAccessible = true
                            f.name !in setOf("entities", "rng", "nextEntityId", "timestamp", "nextObjectIdentityStamp",
                                "objectIdentityStamps", "priorityPlayerId", "priorityPassedBy", "continuationStack",
                                "pendingDecision") && runCatching { f.get(traceBefore) != f.get(after) }.getOrDefault(false)
                        }.map { it.name }
                        println("  [diff $game] components=$changes fields=$fields stateFields=$normalizedDiff")
                    }
                    if (environment.stepCount % 250 == 0) {
                        val state = environment.state
                        println(
                            "  [game $game] step=${environment.stepCount} turn=${state.turnNumber} " +
                                "phase=${state.phase}/${state.step} active=${state.activePlayerId?.value} " +
                                "elapsed=${"%.1f".format((System.nanoTime() - started) / 1e9)}s " +
                                "last=${action::class.simpleName}",
                        )
                    }
                }
                val seconds = (System.nanoTime() - started) / 1e9
                totalSeconds += seconds
                if (environment.isTerminal) terminalGames++
                val winner = environment.winnerId?.let { id -> players.firstOrNull { it.playerId == id }?.name } ?: "-"
                lines += listOf(
                    game.toString(), seat0, startingPlayer.toString(), environment.isTerminal.toString(), winner,
                    environment.turnNumber.toString(), environment.stepCount.toString(),
                    priorityCalls.toString(), nonPassActions.toString(), decisions.toString(),
                    "%.2f".format(seconds),
                ).joinToString("\t")
                println(lines.last())
            }
            lines += "# games=$games terminal=$terminalGames profile=${profile.id} maxSteps=$maxSteps " +
                "totalSeconds=${"%.1f".format(totalSeconds)} secondsPerGame=${"%.2f".format(totalSeconds / games)}"
            println(lines.last())
            Files.createDirectories(outputFile.parent)
            Files.writeString(outputFile, lines.joinToString("\n", postfix = "\n"))
        }
})

private fun lockedDeck(repositoryRoot: Path, fileName: String): List<String> {
    val cards = Files.readAllLines(repositoryRoot.resolve("docs/ml/curriculum").resolve(fileName))
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }
    check(cards.size == 100) { "Locked deck $fileName has ${cards.size} cards" }
    return cards
}

private fun profileFor(id: String): AiProfile = when (id) {
    "production-candidate-expiring" -> AiProfile.PRODUCTION_CANDIDATE_EXPIRING
    "production" -> AiProfile.PRODUCTION
    "current" -> AiProfile.CURRENT
    else -> error("Unsupported phase1.profile '$id'")
}
