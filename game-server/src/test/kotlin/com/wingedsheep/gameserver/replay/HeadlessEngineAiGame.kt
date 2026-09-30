package com.wingedsheep.gameserver.replay

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.registry.TokenArtRegistry
import com.wingedsheep.gameserver.config.GameBeansConfig
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId

/**
 * Engine AI vs engine AI on the locked Commander curriculum matchup (Akiri vs Chevill), played
 * headlessly through the default LEGACY [GameEnvironment] and recorded with [HeadlessReplayRecorder].
 *
 * Shared by [HeadlessReplayRecorderTest] and the opt-in [HeadlessShowcaseReplays] generator.
 */
object HeadlessEngineAiGame {

    const val AKIRI_SOURCE = "docs/ml/curriculum/akiri-v0.1.txt"
    const val CHEVILL_SOURCE = "docs/ml/curriculum/chevill-v0.1.txt"

    private val beans by lazy { GameBeansConfig(GameProperties()) }

    /**
     * The live server's own registries, so a recorded game re-simulates there exactly as here. The
     * printing and token-art registries matter: token art is part of the fingerprinted state.
     */
    val registry: CardRegistry by lazy { beans.cardRegistry() }
    val printingRegistry: PrintingRegistry by lazy { beans.printingRegistry(registry) }
    val tokenArtRegistry: TokenArtRegistry by lazy { beans.tokenArtRegistry() }

    /** The reconstructor exactly as the server wires it. */
    fun serverReconstructor(): ReplayReconstructor =
        ReplayReconstructor(registry, printingRegistry, tokenArtRegistry)

    fun environment(): GameEnvironment = GameEnvironment.create(
        registry,
        printingRegistry = printingRegistry,
        tokenArtRegistry = tokenArtRegistry,
    )

    data class Recorded(
        val replay: CompactReplay,
        val closure: EpisodeClosureV1?,
        /** Actions the AIs submitted through [GameEnvironment.step]. */
        val submittedSteps: Int,
        val turns: Int,
    )

    fun config(seed: Long, startingPlayerIndex: Int = 0): GameConfig {
        val loader = CurriculumDeckSourceLoader()
        fun seat(index: Int, sourcePath: String): PlayerConfig {
            val source = loader.load(sourcePath)
            return PlayerConfig(
                name = "${source.commander.substringBefore(',')} (engine AI)",
                deck = Deck(cards = source.libraryDeckList().flatMap { (name, count) -> List(count) { name } }),
                startingLife = 40,
                playerId = EntityId("seat-$index"),
                commanderCardName = source.commander,
            )
        }
        return GameConfig(
            players = listOf(seat(0, AKIRI_SOURCE), seat(1, CHEVILL_SOURCE)),
            format = Format.Commander(),
            skipMulligans = true,
            startingPlayerIndex = startingPlayerIndex,
            seed = seed,
        )
    }

    /**
     * Play one game, engine AI in both seats, and record it. [maxSteps] bounds submitted actions;
     * a game that hits it is recorded as far as it got.
     */
    fun play(
        gameId: String,
        seed: Long,
        startingPlayerIndex: Int = 0,
        maxSteps: Int = 3_000,
        profile: AiProfile = AiProfile.PRODUCTION_CANDIDATE_EXPIRING,
        engineVersion: String = CompactReplay.UNKNOWN_VERSION,
    ): Recorded {
        val config = config(seed, startingPlayerIndex)
        val environment = environment()
        val recorder = HeadlessReplayRecorder.start(environment, registry, config, maxSteps)
        val ais = environment.playerIds.associateWith { AIPlayer.create(registry, it, profile) }

        var submitted = 0
        while (!environment.isTerminal && !environment.isTruncated) {
            val actor = environment.agentToAct ?: break
            val ai = ais.getValue(actor)
            val decision = environment.pendingDecision
            val action = if (decision != null) {
                SubmitDecision(actor, ai.respondToDecision(environment.state, decision))
            } else {
                ai.chooseAction(environment.state)
            }
            environment.step(action)
            submitted++
        }

        return Recorded(
            replay = recorder.finish(gameId = gameId, engineVersion = engineVersion),
            closure = environment.episodeClosure,
            submittedSteps = submitted,
            turns = environment.turnNumber,
        )
    }
}
