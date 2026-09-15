package com.wingedsheep.gameserver.controller

import com.wingedsheep.gameserver.handler.LobbyHandler
import com.wingedsheep.gameserver.curriculum.CurriculumAiTournamentPreset
import com.wingedsheep.gameserver.curriculum.CurriculumSourceProvenanceV1
import com.wingedsheep.gameserver.lobby.LobbyState
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.engine.limited.BoosterGenerator
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Development-only endpoint to create AI-only tournaments, including the server-owned locked
 * Commander curriculum preset and the legacy sealed/fixed-deck modes.
 *
 * After creation, open /tournament/{lobbyId} in the browser to spectate, or poll
 * `GET /api/dev/ai-tournament/{lobbyId}` for the live game id and jump straight to
 * `/?spectate={gameSessionId}` — that is what the client's AI Sandbox page does.
 * AI players build decks, start matches, and play autonomously.
 *
 * Enable with: game.dev-endpoints.enabled=true
 */
@RestController
@RequestMapping("/api/dev/ai-tournament")
@ConditionalOnProperty(name = ["game.dev-endpoints.enabled"], havingValue = "true")
class AiTournamentController(
    private val lobbyHandler: LobbyHandler,
    private val boosterGenerator: BoosterGenerator,
    private val lobbyRepository: LobbyRepository,
    private val gameRepository: GameRepository
) {
    private val logger = LoggerFactory.getLogger(AiTournamentController::class.java)

    data class AiTournamentRequest(
        /** Optional server-owned fixed matchup identity. Mutually exclusive with legacy options. */
        val preset: String? = null,
        val setCodes: List<String>? = null,
        val playerCount: Int? = null,
        /** Optional per-player model overrides. Index 0 = player 1, index 1 = player 2, etc.
         *  Falls back to the server's configured model for any unspecified slots. */
        val models: List<String>? = null,
        /** Skip LLM deck building and use the fast heuristic builder instead. */
        val heuristicDeckbuilding: Boolean? = null,
        /**
         * Games played per pairing. Defaults to the lobby default (3) — a two-AI sandbox that
         * plays a single game is over almost as soon as you have finished opening the tab.
         */
        val gamesPerMatch: Int? = null,
        /**
         * Optional pre-built decks (one per player, indexed by slot) as cardName→count maps.
         * When provided, the lobby is created in PREMADE_DECKS format and AI deckbuilding is
         * skipped entirely — boosters are not generated and `setCodes` is ignored.
         */
        val decks: List<Map<String, Int>>? = null
    )

    data class AiTournamentResponse(
        val lobbyId: String,
        val spectateUrl: String,
        val message: String,
        val presetIdentity: String? = null,
        val curriculumSources: List<CurriculumSourceProvenanceV1> = emptyList(),
    )

    @PostMapping
    fun createAiTournament(
        @RequestBody request: AiTournamentRequest?
    ): ResponseEntity<AiTournamentResponse> {
        val requestedPreset = if (request?.preset == null) {
            null
        } else {
            try {
                CurriculumAiTournamentPreset.fromRequest(request.preset)
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest().body(AiTournamentResponse(
                    lobbyId = "",
                    spectateUrl = "",
                    message = e.message ?: "Unknown AI tournament preset",
                ))
            }
        }

        if (requestedPreset != null) {
            val hasCallerOverrides = request?.let {
                it.decks != null ||
                    it.setCodes != null ||
                    it.models != null ||
                    it.heuristicDeckbuilding != null ||
                    it.gamesPerMatch != null ||
                    (it.playerCount != null && it.playerCount != 2)
            } == true
            if (hasCallerOverrides) {
                return ResponseEntity.badRequest().body(AiTournamentResponse(
                    lobbyId = "",
                    spectateUrl = "",
                    message = "The locked curriculum preset cannot be combined with caller overrides",
                ))
            }

            return try {
                val lobbyId = lobbyHandler.createAiTournamentFromCurriculumPreset(requestedPreset)
                val provenance = lobbyRepository.findLobbyById(lobbyId)?.curriculumProvenance
                logger.info(
                    "AI tournament created via REST: lobbyId={}, mode=curriculum-preset, preset={}",
                    lobbyId,
                    requestedPreset.identity,
                )
                ResponseEntity.ok(AiTournamentResponse(
                    lobbyId = lobbyId,
                    spectateUrl = "/tournament/$lobbyId",
                    message = "Locked Commander curriculum match created. Open /tournament/$lobbyId to spectate.",
                    presetIdentity = provenance?.presetIdentity ?: requestedPreset.identity,
                    curriculumSources = provenance?.sources.orEmpty(),
                ))
            } catch (e: Exception) {
                logger.error("Failed to create curriculum AI tournament: ${e.message}", e)
                ResponseEntity.badRequest().body(AiTournamentResponse(
                    lobbyId = "",
                    spectateUrl = "",
                    message = "Failed to create AI tournament: ${e.message}",
                ))
            }
        }

        val decks = request?.decks?.takeIf { it.isNotEmpty() }
        val playerCount = decks?.size
            ?: request?.playerCount?.coerceIn(2, 8) ?: 2

        return try {
            val lobbyId = if (decks != null) {
                if (decks.size < 2) {
                    return ResponseEntity.badRequest().body(AiTournamentResponse(
                        lobbyId = "", spectateUrl = "",
                        message = "At least 2 decks are required for a fixed-deck AI tournament"
                    ))
                }
                lobbyHandler.createAiTournamentWithFixedDecks(decks, request.models)
            } else {
                // Auto-pick a random *fully implemented* set (partial sets aren't reliable enough
                // for an unattended AI tournament); fall back to any set if none qualify.
                val setCodes = request?.setCodes?.ifEmpty { null }
                    ?: boosterGenerator.availableSets.values
                        .filter { it.fullyImplemented }
                        .map { it.setCode }
                        .ifEmpty { boosterGenerator.availableSets.keys.toList() }
                        .let { listOf(it.random()) }
                lobbyHandler.createAiTournament(
                    setCodes = setCodes,
                    playerCount = playerCount,
                    models = request?.models,
                    heuristicDeckbuilding = request?.heuristicDeckbuilding,
                    gamesPerMatch = request?.gamesPerMatch?.coerceIn(1, 9)
                )
            }

            val mode = if (decks != null) "fixed-decks" else "sealed (sets=${request?.setCodes ?: "auto"})"
            logger.info("AI tournament created via REST: lobbyId=$lobbyId, mode=$mode, players=$playerCount")

            ResponseEntity.ok(AiTournamentResponse(
                lobbyId = lobbyId,
                spectateUrl = "/tournament/$lobbyId",
                message = "AI tournament created. Open /tournament/$lobbyId to spectate. " +
                    if (decks != null) "Players will start playing shortly with the supplied decks."
                    else "AI players are building decks and will start playing shortly."
            ))
        } catch (e: Exception) {
            logger.error("Failed to create AI tournament: ${e.message}", e)
            ResponseEntity.badRequest().body(AiTournamentResponse(
                lobbyId = "",
                spectateUrl = "",
                message = "Failed to create AI tournament: ${e.message}"
            ))
        }
    }

    // ---- Status ---------------------------------------------------------------

    data class AiLiveGame(
        val gameSessionId: String,
        val player1Name: String,
        val player2Name: String,
        val player1Life: Int,
        val player2Life: Int,
        val turnNumber: Int
    )

    data class AiTournamentStatus(
        val lobbyId: String,
        val state: String,
        val playerNames: List<String>,
        /** How many AI seats have handed in a deck — the progress bar while decks are being built. */
        val decksSubmitted: Int,
        val round: Int,
        val totalRounds: Int,
        val complete: Boolean,
        val liveGames: List<AiLiveGame>,
        val presetIdentity: String? = null,
        val curriculumSources: List<CurriculumSourceProvenanceV1> = emptyList(),
    )

    /**
     * Poll target for the AI Sandbox page: where the lobby is in its lifecycle and which game
     * sessions are running right now. Unlike `/api/tournaments/live` this works for the existing
     * private AI Sandbox lobbies too; the locked curriculum preset is the deliberate exception and
     * marks its own lobby public-spectatable so a normal spectator can attach through the hardened
     * admission gate.
     */
    @GetMapping("/{lobbyId}")
    fun status(@PathVariable lobbyId: String): ResponseEntity<AiTournamentStatus> {
        val lobby = lobbyRepository.findLobbyById(lobbyId) ?: return ResponseEntity.notFound().build()
        val tournament = lobbyRepository.findTournamentById(lobbyId)

        val liveGames = tournament?.getAllInProgressMatches().orEmpty().mapNotNull { match ->
            val gameSessionId = match.gameSessionId ?: return@mapNotNull null
            val session = gameRepository.findById(gameSessionId) ?: return@mapNotNull null
            if (session.isGameOver()) return@mapNotNull null
            val names = session.getPlayerNames()
            val life = session.getLifeTotals()
            if (names.size < 2 || life.size < 2) return@mapNotNull null
            AiLiveGame(
                gameSessionId = gameSessionId,
                player1Name = names[0],
                player2Name = names[1],
                player1Life = life[0],
                player2Life = life[1],
                turnNumber = session.getStateSnapshot()?.turnNumber ?: 0
            )
        }.sortedBy { it.gameSessionId }

        return ResponseEntity.ok(AiTournamentStatus(
            lobbyId = lobby.lobbyId,
            state = lobby.state.name,
            playerNames = lobby.players.values.map { it.identity.playerName }.sorted(),
            decksSubmitted = lobby.players.values.count { it.hasSubmittedDeck },
            round = tournament?.currentRound?.roundNumber ?: 0,
            totalRounds = tournament?.totalRounds ?: 0,
            complete = lobby.state == LobbyState.TOURNAMENT_COMPLETE,
            liveGames = liveGames,
            presetIdentity = lobby.curriculumProvenance?.presetIdentity,
            curriculumSources = lobby.curriculumProvenance?.sources.orEmpty(),
        ))
    }

    @GetMapping("/sets")
    fun listAvailableSets(): ResponseEntity<List<SetInfo>> {
        val sets = boosterGenerator.availableSets.values.map { config ->
            SetInfo(
                code = config.setCode,
                name = config.setName,
                partial = !config.fullyImplemented,
                extensionSet = config.extensionSet,
                block = config.block,
                implementedCount = config.distinctCardCount,
                releaseDate = config.releaseDate
            )
        }.sortedBy { it.name }
        return ResponseEntity.ok(sets)
    }

    /** Mirrors the client's `AvailableSet`, so the sandbox can reuse the shared set picker. */
    data class SetInfo(
        val code: String,
        val name: String,
        val partial: Boolean,
        val extensionSet: Boolean,
        val block: String?,
        val implementedCount: Int,
        val releaseDate: String?
    )
}
