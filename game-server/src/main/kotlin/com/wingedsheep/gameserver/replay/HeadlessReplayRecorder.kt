package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gym.GameEnvironment
import java.time.Instant

/**
 * Records a game driven headlessly through a [GameEnvironment] as a [CompactReplay] the ordinary
 * replay viewer can play back — the Gym counterpart of the live recording in
 * [com.wingedsheep.gameserver.session.GameSession].
 *
 * It does not care who chooses the actions: engine AI, a model, a scripted test. It listens to
 * [GameEnvironment.committedTransitionListener], which reports every rules transition the
 * environment commits, including the automatic priority passes and forced decision answers a
 * legacy [GameEnvironment.step] performs after the submitted action. Recording only the submitted
 * actions would not re-simulate: the replay must contain every [com.wingedsheep.engine.core.ActionProcessor]
 * input, exactly as the live server records every action it applies.
 *
 * Checkpoints follow the live cadence ([ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS]) plus the
 * zero-action and tail stamps, so [ReplayReconstructor] verifies the result as
 * [ReplayFidelity.EXACT] or tells exactly where it drifted.
 *
 * Play the game with the registries the server replays it with — create the environment with the
 * server's `printingRegistry` and `tokenArtRegistry` as well as its card registry. Token art is
 * part of the fingerprinted state, so a game played without it drifts on the server at its first
 * token even though every action still applies.
 *
 * ```kotlin
 * val environment = GameEnvironment.create(registry)
 * val recorder = HeadlessReplayRecorder.start(environment, registry, config, maxSteps = 2_000)
 * while (!environment.isTerminal && !environment.isTruncated) environment.step(chooseAction())
 * val replay = recorder.finish(gameId = "showcase-1")
 * ```
 */
class HeadlessReplayRecorder private constructor(
    private val environment: GameEnvironment,
    private val cardRegistry: CardRegistry,
    private val setup: ReplaySetup,
    initialState: GameState,
    private val startedAt: Instant,
) {
    private val actions = ArrayList<GameAction>()
    private val checkpoints = mutableListOf(
        ReplayCheckpoint(afterActionCount = 0, fingerprint = fingerprint(initialState)),
    )
    private var lastState: GameState = initialState
    private var finished = false

    /** Committed rules transitions so far — at least one per [GameEnvironment.step]. */
    val actionCount: Int get() = actions.size

    private fun onCommitted(action: GameAction, stateAfter: GameState) {
        check(!finished) { "Recorder already finished" }
        actions += action
        lastState = stateAfter
        if (actions.size % ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS == 0) {
            checkpoints += ReplayCheckpoint(actions.size, fingerprint(stateAfter))
        }
    }

    /**
     * Stop recording and build the replay. Detaches from the environment; may be called at any
     * point (a finished, truncated, or still-running game all produce a replay of what was played).
     *
     * Fails if the environment's state was changed by anything other than a reported transition —
     * a [GameEnvironment.reset] or [GameEnvironment.restore] mid-recording — since the input log
     * would then no longer lead to the position it claims to.
     */
    fun finish(
        gameId: String,
        endedAt: Instant = Instant.now(),
        engineVersion: String = CompactReplay.UNKNOWN_VERSION,
        tournamentName: String? = null,
        tournamentRound: Int? = null,
    ): CompactReplay {
        check(!finished) { "Recorder already finished" }
        finished = true
        environment.committedTransitionListener = null
        check(environment.state === lastState) {
            "Environment state changed outside the recorded transitions (reset or restore while recording)"
        }

        val winnerName = lastState.winnerId?.let { winner ->
            setup.players.firstOrNull { it.playerId == winner.value }?.name
        }
        return CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = gameId,
            players = setup.players.map { ReplayPlayerInfo(it.playerId, it.name) },
            startedAt = startedAt.toString(),
            endedAt = endedAt.toString(),
            winnerName = winnerName,
            tournamentName = tournamentName,
            tournamentRound = tournamentRound,
            setup = setup,
            actions = actions.toList(),
            engineVersion = engineVersion,
            pinnedCards = ReplayCardPin.capture(cardRegistry, setup),
            checkpoints = ReplayCheckpointPolicy.withV3Tail(
                checkpoints = checkpoints,
                actionCount = actions.size,
                fingerprint = fingerprint(lastState),
            ),
        )
    }

    companion object {
        /**
         * [GameEnvironment.reset] [environment] with [config] and start recording it.
         *
         * [config] must fix everything re-simulation depends on: a [GameConfig.seed] and an explicit
         * [com.wingedsheep.engine.core.PlayerConfig.playerId] for every seat. [cardRegistry] must be
         * the registry the environment was created with — its definitions are pinned into the replay.
         */
        fun start(
            environment: GameEnvironment,
            cardRegistry: CardRegistry,
            config: GameConfig,
            maxSteps: Int? = null,
            startedAt: Instant = Instant.now(),
        ): HeadlessReplayRecorder {
            val seed = requireNotNull(config.seed) { "A recorded game needs a fixed GameConfig.seed" }
            val playerIds = config.players.map {
                requireNotNull(it.playerId) { "A recorded game needs an explicit playerId for ${it.name}" }
            }
            check(environment.committedTransitionListener == null) {
                "Environment is already being recorded"
            }

            environment.reset(config, maxSteps)
            val initialState = environment.state
            val setup = ReplaySetup(
                seed = seed,
                format = config.format,
                attackMode = config.attackMode,
                startingHandSize = config.startingHandSize,
                skipMulligans = config.skipMulligans,
                useHandSmoother = config.useHandSmoother,
                handSmootherCandidates = config.handSmootherCandidates,
                startingPlayerIndex = config.startingPlayerIndex,
                teams = config.teams,
                players = config.players.mapIndexed { index, player ->
                    ReplayPlayerSetup(
                        playerId = playerIds[index].value,
                        name = player.name,
                        deck = player.deck,
                        startingLife = player.startingLife,
                        commanderCardName = player.commanderCardName,
                    )
                },
                // Same seating the live server records: seat index is the position in turn order.
                seatRoster = config.players.mapIndexed { index, player ->
                    ServerMessage.PlayerSeatInfo(
                        playerId = playerIds[index].value,
                        name = player.name,
                        seatIndex = initialState.turnOrder.indexOf(playerIds[index]).takeIf { it >= 0 } ?: index,
                        teamIndex = config.teams?.indexOfFirst { index in it }?.takeIf { it >= 0 },
                        teamSharedLife = config.format.sharesTeamLife,
                    )
                }.sortedBy { it.seatIndex },
            )
            val recorder = HeadlessReplayRecorder(
                environment = environment,
                cardRegistry = cardRegistry,
                setup = setup,
                initialState = initialState,
                startedAt = startedAt,
            )
            environment.committedTransitionListener = recorder::onCommitted
            return recorder
        }

        private fun fingerprint(state: GameState): String =
            ReplayFingerprint.of(state, CompactReplay.CURRENT_VERSION)
    }
}
