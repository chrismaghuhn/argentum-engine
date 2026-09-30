package com.wingedsheep.gameserver.controller

import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayRead
import com.wingedsheep.gameserver.replay.ReplayReconstructor
import com.wingedsheep.gameserver.replay.ReplayService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Development-only import of a replay recorded outside this server — typically a headless Gym game
 * recorded by [com.wingedsheep.gameserver.replay.HeadlessReplayRecorder] — into the replay store,
 * so the ordinary viewer at `/replay/{gameId}` can play it back.
 *
 * Nothing about serving changes: the imported record is re-simulated and spectator-masked by the
 * same [ReplayService] path every live game uses. Only the way in is new, and it is dev-gated.
 *
 * **WARNING:** This endpoint should NEVER be enabled in production.
 * Enable with: game.dev-endpoints.enabled=true
 */
@RestController
@RequestMapping("/api/dev/replays")
@ConditionalOnProperty(name = ["game.dev-endpoints.enabled"], havingValue = "true")
class DevReplayImportController(
    private val replayService: ReplayService,
    private val reconstructor: ReplayReconstructor,
) {
    private val logger = LoggerFactory.getLogger(DevReplayImportController::class.java)

    data class ImportResponse(
        val gameId: String,
        /** Web-client route that plays this replay. */
        val viewerPath: String,
        val playerNames: List<String>,
        val winnerName: String?,
        val frameCount: Int,
        /** How this server re-simulates it: EXACT, UNVERIFIED, or DIVERGED. */
        val fidelity: String,
        val detail: String?,
    )

    /**
     * POST /api/dev/replays/import
     *
     * Body: a replay file — either the [ReplayCodec.encode] text (gzip + base64, what the headless
     * recorder writes) or the plain `CompactReplay` JSON. Re-importing the identical replay is a
     * no-op; a different replay under an existing game id is refused rather than overwritten.
     */
    @PostMapping("/import")
    fun importReplay(@RequestBody body: String): ResponseEntity<Any> {
        val replay = try {
            decode(body.trim())
        } catch (failure: Exception) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Not a replay file: ${failure.message}"))
        }

        when (val existing = replayService.findStored(replay.gameId)) {
            is ReplayRead.Decoded -> if (existing.stored.replay != replay) {
                return ResponseEntity.status(409)
                    .body(mapOf("error" to "A different replay is already stored as ${replay.gameId}"))
            }
            is ReplayRead.UnsupportedVersion -> return ResponseEntity.status(409)
                .body(mapOf("error" to "A different replay is already stored as ${replay.gameId}"))
            null -> Unit
        }

        val reconstructed = reconstructor.reconstruct(replay)
        replayService.save(replay, archive = false)
        logger.info(
            "Imported replay {} ({} actions, fidelity {})",
            replay.gameId, replay.actions.size, reconstructed.fidelity,
        )
        return ResponseEntity.ok(
            ImportResponse(
                gameId = replay.gameId,
                viewerPath = "/replay/${replay.gameId}",
                playerNames = replay.players.map { it.name },
                winnerName = replay.winnerName,
                frameCount = replay.frameCount,
                fidelity = reconstructed.fidelity.name,
                detail = reconstructed.divergenceReason,
            )
        )
    }

    /** Both forms go through [ReplayCodec.decode], so version checks and migrations still apply. */
    private fun decode(body: String): CompactReplay {
        require(body.isNotEmpty()) { "empty body" }
        val encoded = if (body.startsWith("{")) ReplayCodec.encodeText(body) else body
        return ReplayCodec.decode(encoded)
    }
}
