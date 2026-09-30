package com.wingedsheep.gym

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.legalactions.MeaningfulActionFilter
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.EntityFeatures
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Phase 1 teacher data: the built-in engine AI plays the locked Akiri vs Chevill Commander matchup
 * against itself, and every non-trivial top-level choice it makes is recorded as a behavior-cloning
 * sample.
 *
 * Deliberate Phase 1 scope (docs/ml/p1-first-playing-model.md):
 * - The teacher is the engine AI, which reads the full GameState. The *sample* only carries the
 *   acting player's public observation, built by [ObservationBuilder], so the student never sees
 *   hidden information.
 * - Only priority choices are labelled. Pending decisions (targets, modes, payment, combat maps)
 *   are answered by the engine AI and not recorded; mana abilities are never candidates, the engine
 *   auto-pays.
 * - Entity ids never leave this file: cards are referenced by their index in the sample's `cards`.
 *
 * One gzip JSONL file per game (`game-NNNNNN.jsonl.gz`), one sample per line, plus a per-game row
 * returned to the caller.
 */
internal object Phase1SelfPlayCollector {

    const val SAMPLE_SCHEMA = "argentum-p1-selfplay-sample@v1"

    data class GameSummary(
        val game: Int,
        val seat0: String,
        val startingPlayer: Int,
        val terminal: Boolean,
        val winner: String,
        val turns: Int,
        val engineSteps: Int,
        val samples: Int,
        val unmatchedChoices: Int,
        val seconds: Double,
    )

    fun lockedDeck(repositoryRoot: Path, fileName: String): List<String> {
        val cards = Files.readAllLines(repositoryRoot.resolve("docs/ml/curriculum").resolve(fileName))
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }
        check(cards.size == 100) { "Locked deck $fileName has ${cards.size} cards" }
        return cards
    }

    fun profileFor(id: String): AiProfile = when (id) {
        "production-candidate-expiring" -> AiProfile.PRODUCTION_CANDIDATE_EXPIRING
        "production" -> AiProfile.PRODUCTION
        "current" -> AiProfile.CURRENT
        else -> error("Unsupported Phase 1 AI profile '$id'")
    }

    /** Seat order alternates every game and the starting player every two, so all four orientations recur. */
    fun gameConfig(
        game: Int,
        baseSeed: Long,
        resolver: DeckResolver,
        decks: Map<String, List<String>>,
    ): GameConfig {
        val seat0 = if (game % 2 == 0) "Akiri" else "Chevill"
        val seat1 = if (seat0 == "Akiri") "Chevill" else "Akiri"
        val players = listOf(seat0, seat1).mapIndexed { index, name ->
            val deck = decks.getValue(name)
            PlayerConfig(
                name = name,
                deck = resolver.resolve(DeckSpec.Explicit(deck.drop(1).groupingBy { it }.eachCount())),
                startingLife = 40,
                playerId = EntityId("p1-game-$game-seat-$index"),
                commanderCardName = deck.first(),
            )
        }
        return GameConfig(
            players = players,
            startingHandSize = 7,
            skipMulligans = true,
            useHandSmoother = false,
            startingPlayerIndex = (game / 2) % 2,
            format = Format.Commander(),
            attackMode = AttackMode.MULTIPLE,
            seed = baseSeed + game,
        )
    }

    fun playAndRecord(
        game: Int,
        config: GameConfig,
        registry: CardRegistry,
        profile: AiProfile,
        maxSteps: Int,
        outputDirectory: Path,
    ): GameSummary {
        val environment = GameEnvironment.create(cardRegistry = registry)
        val observationBuilder = ObservationBuilder(cardRegistry = registry)
        val started = System.nanoTime()
        environment.reset(config, maxSteps = maxSteps)
        val names = config.players.associate { checkNotNull(it.playerId) to it.name }
        val ais = names.keys.associateWith { id -> AIPlayer.create(registry, id, profile) }
        // Samples wait for the result so each can carry the outcome from its own perspective.
        val pending = mutableListOf<Pair<EntityId, JsonObject>>()
        var unmatched = 0

        while (!environment.isTerminal && !environment.isTruncated) {
            val actor = environment.agentToAct ?: break
            val ai = ais.getValue(actor)
            val decision = environment.pendingDecision
            if (decision != null) {
                environment.step(SubmitDecision(actor, ai.respondToDecision(environment.state, decision)))
                continue
            }
            val state = environment.state
            if (profile.useMeaningfulFilter && MeaningfulActionFilter.canAutoPassWithoutEnumerating(state, actor)) {
                environment.step(PassPriority(actor))
                continue
            }
            val legal = environment.legalActions()
            if (legal.isEmpty()) break
            val chosen = ai.chooseFrom(state, legal)

            val built = observationBuilder.build(state, actor, legal)
            val observation = built.observation as? TrainingObservation
            if (observation != null && built.diagnostics.isEmpty()) {
                val candidates = observation.legalActions.filterNot { it.isManaAbility }
                if (candidates.size >= 2) {
                    val chosenIndex = candidates.indexOfFirst { view ->
                        val template = built.registry.legalActions.firstOrNull { it.first == view.actionId }?.second
                        template != null && environment.isCurrentActionCandidate(template.action, chosen.action)
                    }
                    if (chosenIndex < 0) {
                        unmatched++
                    } else {
                        pending += actor to sample(game, environment.stepCount, names, observation, candidates, chosenIndex)
                    }
                }
            }
            environment.step(chosen.action)
        }

        val winnerId = environment.winnerId
        val file = outputDirectory.resolve("game-%06d.jsonl.gz".format(game))
        GZIPOutputStream(Files.newOutputStream(file)).bufferedWriter(Charsets.UTF_8).use { writer ->
            for ((perspective, record) in pending) {
                val outcome = when {
                    !environment.isTerminal || winnerId == null -> 0
                    winnerId == perspective -> 1
                    else -> -1
                }
                val withOutcome = JsonObject(record + ("outcome" to JsonPrimitive(outcome)))
                writer.write(withOutcome.toString())
                writer.write("\n")
            }
        }
        return GameSummary(
            game = game,
            seat0 = config.players[0].name,
            startingPlayer = config.startingPlayerIndex ?: -1,
            terminal = environment.isTerminal,
            winner = winnerId?.let { names[it] } ?: "-",
            turns = environment.turnNumber,
            engineSteps = environment.stepCount,
            samples = pending.size,
            unmatchedChoices = unmatched,
            seconds = (System.nanoTime() - started) / 1e9,
        )
    }

    /**
     * One behavior-cloning sample. Everything is relative to the acting player ("self"/"opponent"),
     * and every entity reference is an index into `cards`; no engine ids are written.
     */
    private fun sample(
        game: Int,
        engineStep: Int,
        names: Map<EntityId, String>,
        observation: TrainingObservation,
        candidates: List<com.wingedsheep.gym.contract.LegalActionView>,
        chosenIndex: Int,
    ): JsonObject {
        val self = observation.perspectivePlayerId
        val visible = observation.zones.flatMap { it.cards }
        val refs = visible.withIndex().associate { (index, card) -> card.entityId to index }
        fun ref(id: EntityId?): Int = id?.let { refs[it] } ?: -1
        fun side(id: EntityId?): String = when (id) {
            null -> "none"
            self -> "self"
            else -> "opponent"
        }

        return buildJsonObject {
            put("schema", SAMPLE_SCHEMA)
            put("game", game)
            put("engineStep", engineStep)
            put("seat", names[self] ?: "?")
            put("turn", observation.turnNumber)
            put("phase", observation.phase.name)
            put("step", observation.step.name)
            put("activeIsSelf", observation.activePlayerId == self)
            put("players", buildJsonArray {
                observation.players.sortedByDescending { it.isPerspective }.forEach { player ->
                    add(buildJsonObject {
                        put("side", if (player.isPerspective) "self" else "opponent")
                        put("life", player.lifeTotal)
                        put("hand", player.handSize)
                        put("library", player.librarySize)
                        put("graveyard", player.graveyardSize)
                        put("exile", player.exileSize)
                        put("mana", buildJsonArray {
                            with(player.manaPool) { listOf(white, blue, black, red, green, colorless) }
                                .forEach { add(JsonPrimitive(it)) }
                        })
                        put("active", player.isActive)
                    })
                }
            })
            put("cards", JsonArray(visible.map { card -> cardJson(card, side(card.controllerId ?: card.ownerId), ::ref) }))
            put("stack", buildJsonArray {
                observation.stack.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("kind", item.kind.name)
                        put("controller", side(item.controllerId))
                        put("source", ref(item.sourceEntityId))
                        put("targets", JsonArray(item.targets.map { JsonPrimitive(ref(it)) }))
                    })
                }
            })
            put("candidates", buildJsonArray {
                candidates.forEach { view ->
                    add(buildJsonObject {
                        put("kind", view.kind)
                        put("source", ref(view.sourceEntityId))
                        put("targets", JsonArray(view.targetEntityIds.map { JsonPrimitive(ref(it)) }))
                        put("manaCost", view.manaCost ?: "")
                        put("hasX", view.hasXCost)
                    })
                }
            })
            put("chosen", chosenIndex)
        }
    }

    private fun cardJson(card: EntityFeatures, side: String, ref: (EntityId?) -> Int): JsonObject = buildJsonObject {
        put("name", if (card.faceDown) "" else card.name)
        put("zone", card.zone.name)
        put("side", side)
        put("types", JsonArray(card.types.sorted().map(::JsonPrimitive)))
        put("colors", JsonArray(card.colors.sorted().map(::JsonPrimitive)))
        put("keywords", JsonArray(card.keywords.sorted().map(::JsonPrimitive)))
        put("manaValue", card.manaValue)
        card.power?.let { put("power", it) }
        card.toughness?.let { put("toughness", it) }
        put("tapped", card.tapped)
        put("summoningSick", card.summoningSick)
        put("faceDown", card.faceDown)
        put("damage", card.damageMarked)
        put("counters", JsonObject(card.counters.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("attachedTo", ref(card.attachedTo))
    }
}
