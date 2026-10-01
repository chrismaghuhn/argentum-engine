package com.wingedsheep.gym.service

import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameEnvironmentMode
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.GymEnv
import com.wingedsheep.gym.EpisodeDiagnostics
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PerspectiveHistoryV1
import com.wingedsheep.gym.deckbuild.DeckbuildEnvironment
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.rundiagnostics.DiagnosticsRecorder
import com.wingedsheep.sdk.model.EntityId
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

/**
 * In-JVM registry for many concurrent gym environments. Transport-agnostic:
 * exposes create / reset / observe / step / fork / snapshot / dispose methods
 * that HTTP, gRPC, or an in-process training loop can call directly.
 *
 * Environments are polymorphic ([GymEnv]): game envs ([GameGymEnv]) and deckbuild
 * envs ([DeckbuildEnvironment]) share the observe/step/fork surface. Operations that
 * only make sense for a game (decision submission, snapshot/restore, reset) require a
 * [GameGymEnv] and reject other env kinds.
 *
 * ## Threading
 *
 * Each env is single-threaded. [MultiEnvService] serializes operations that name the
 * same [EnvId], including singular calls racing a batch item, while different envs run
 * independently in parallel via [stepBatch]. This keeps mutable per-env state and action
 * registries race-free without imposing a global lock.
 *
 * ## Registry regeneration
 *
 * Every `reset` / `step` rebuilds the env's action mapping. Action IDs from a
 * previous step are invalidated.
 */
class MultiEnvService(
    val cardRegistry: CardRegistry,
    boosterGenerator: BoosterGenerator? = null,
    val workerPool: EnvWorkerPool = EnvWorkerPool(),
    val snapshotCodec: SnapshotCodec = SnapshotCodec(),
    /** Creates one recorder owned by each game environment; null keeps the hot path disabled. */
    private val diagnosticsRecorderFactory: ((EnvId) -> DiagnosticsRecorder?)? = null,
) {
    private val envs = ConcurrentHashMap<EnvId, GymEnv>()
    val deckResolver: DeckResolver = DeckResolver(cardRegistry, boosterGenerator)

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Create a new game env and run `reset` immediately. The returned observation
     * is the opening state (post-mulligan if [EnvConfig.skipMulligans]).
     */
    fun create(config: EnvConfig): CreatedEnv {
        val gameConfig = config.toGameConfig()
        val env = GameEnvironment.create(
            cardRegistry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        val envId = EnvId.generate()
        val gymEnv = GameGymEnv(
            environment = env,
            perspectivePlayerIndex = config.perspectivePlayerIndex,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            diagnosticsRecorder = diagnosticsRecorderFactory?.let { factory ->
                runCatching { factory(envId) }.getOrNull()
            },
        )
        try {
            gymEnv.reset(
                gameConfig,
                maxSteps = config.maxSteps,
                semanticEpisodeId = config.semanticEpisodeId,
            )
        } catch (failure: RuntimeException) {
            gymEnv.closeDiagnostics()
            throw failure
        }
        envs[envId] = gymEnv
        return CreatedEnv(envId, gymEnv.observe())
    }

    /**
     * Create a new **deckbuild** env: open a sealed pool from [DeckbuildConfig.setCode]
     * and hand the agent the build interface. The opening observation lists the pool.
     */
    fun createDeckbuild(config: DeckbuildConfig): CreatedEnv {
        val sealed = deckResolver.openSealedPool(config.setCode, config.boosterCount)
        val env = DeckbuildEnvironment(
            pool = sealed.pool,
            basics = sealed.basics,
            targetSize = config.targetSize
        )
        val observation = env.observe()
        val envId = EnvId.generate()
        envs[envId] = env
        return CreatedEnv(envId, observation)
    }

    /** Reset an existing game env while keeping the same [EnvId]. */
    fun reset(envId: EnvId, config: EnvConfig): ObservationResult =
        withGameEnv(envId) {
            it.reset(
                config.toGameConfig(),
                perspectivePlayerIndex = config.perspectivePlayerIndex,
                maxSteps = config.maxSteps,
                semanticEpisodeId = config.semanticEpisodeId,
            )
        }

    /** Drop envs from the registry. Idempotent and ordered after in-flight operations. */
    fun dispose(envIds: Collection<EnvId>) {
        envIds.forEach { envId ->
            val env = envs[envId] ?: return@forEach
            synchronized(env) {
                if (envs.remove(envId, env)) (env as? GameGymEnv)?.closeDiagnostics()
            }
        }
    }

    fun listEnvs(): Set<EnvId> = envs.keys.toSet()

    /** Authoritative episode diagnostics for the JVM-side A9 harness. */
    fun diagnostics(envId: EnvId): EpisodeDiagnostics = requireGameEnv(envId).diagnostics

    /** Authoritative lifecycle closure for a game environment, when the episode has closed. */
    fun episodeClosure(envId: EnvId): EpisodeClosureV1? = requireGameEnv(envId).episodeClosure

    /** Return the explicit trusted History-D contract for one perspective. */
    fun perspectiveHistory(envId: EnvId, perspectivePlayerId: EntityId): PerspectiveHistoryV1 =
        requireGameEnv(envId).perspectiveHistory(perspectivePlayerId)

    // =========================================================================
    // Observations / stepping
    // =========================================================================

    /** Get the current observation without advancing state. */
    fun observe(
        envId: EnvId,
        perspectivePlayerId: EntityId? = null
    ): ObservationResult = withEnv(envId) { env ->
        if (perspectivePlayerId == null) {
            env.observe()
        } else {
            (env as? GameGymEnv
                ?: throw IllegalStateException(
                    "Env $envId is not a game env; player perspective is not supported"
                )).observeForPlayer(perspectivePlayerId)
        }
    }

    /**
     * Advance a single env by the given [StepRequest.actionId]. The ID must
     * come from the most-recent observation for that env.
     */
    fun step(request: StepRequest): ObservationResult =
        withEnv(request.envId) { env ->
            val action = request.action
            if (action == null) {
                env.step(request.actionId, request.params)
            } else {
                (env as? GameGymEnv
                    ?: throw IllegalStateException(
                        "Env ${request.envId} is not a game env; structured actions are not supported"
                    )).step(request.actionId, action)
            }
        }

    /** Advance N envs in parallel; calls naming the same env are serialized. */
    fun stepBatch(requests: List<StepRequest>): List<Pair<EnvId, ObservationResult>> {
        if (requests.isEmpty()) return emptyList()
        val tasks = requests.map { req -> Callable { req.envId to step(req) } }
        return workerPool.invokeAll(tasks)
    }

    /**
     * Submit a raw `DecisionResponse` for a game env paused on a complex pending
     * decision. Simple decisions are driven via [step] with a folded action ID.
     */
    fun submitDecision(
        envId: EnvId,
        response: DecisionResponse,
        actorId: EntityId? = null
    ): ObservationResult =
        withGameEnv(envId) { it.submitDecision(response, actorId) }

    // =========================================================================
    // Fork / snapshot / restore
    // =========================================================================

    /** Fork an env N times. Children diverge independently from the next step on. */
    fun fork(srcEnvId: EnvId, count: Int = 1): List<EnvId> {
        require(count > 0) { "fork count must be positive" }
        return withEnv(srcEnvId) { src ->
            List(count) {
                val newId = EnvId.generate()
                envs[newId] = src.fork()
                newId
            }
        }
    }

    fun snapshot(envId: EnvId): SnapshotHandle =
        withGameEnv(envId) { it.snapshot(snapshotCodec) }

    /** Restore a game env to a previously-snapshotted state. */
    fun restore(envId: EnvId, handle: SnapshotHandle): ObservationResult =
        withGameEnv(envId) { it.restore(snapshotCodec, handle) }

    /** Release a snapshot slot so long-lived trainers do not retain old game states indefinitely. */
    fun disposeSnapshot(handle: SnapshotHandle) {
        snapshotCodec.dispose(handle)
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private fun EnvConfig.toGameConfig(): GameConfig = GameConfig(
        players = players.map { spec ->
            PlayerConfig(
                name = spec.name,
                deck = deckResolver.resolve(spec.deck),
                startingLife = spec.startingLife,
                playerId = spec.playerId,
                commanderCardName = spec.commanderCardName
            )
        },
        startingHandSize = startingHandSize,
        skipMulligans = skipMulligans,
        useHandSmoother = useHandSmoother,
        startingPlayerIndex = startingPlayerIndex,
        format = format,
        seed = seed
    )

    /**
     * Run one operation under the environment's own monitor. Re-check the registry after taking the
     * monitor so a request that fetched an env just before [dispose] cannot operate on it afterward.
     */
    private inline fun <T> withEnv(envId: EnvId, block: (GymEnv) -> T): T {
        val env = requireEnv(envId)
        return synchronized(env) {
            if (envs[envId] !== env) {
                throw NoSuchElementException("Unknown envId: $envId")
            }
            block(env)
        }
    }

    private inline fun <T> withGameEnv(envId: EnvId, block: (GameGymEnv) -> T): T =
        withEnv(envId) { env ->
            block(
                env as? GameGymEnv
                    ?: throw IllegalStateException("Env $envId is not a game env; operation not supported")
            )
        }

    private fun requireEnv(envId: EnvId): GymEnv =
        envs[envId] ?: throw NoSuchElementException("Unknown envId: $envId")

    private fun requireGameEnv(envId: EnvId): GameGymEnv =
        requireEnv(envId) as? GameGymEnv
            ?: throw IllegalStateException("Env $envId is not a game env; operation not supported")
}

/** Result of [MultiEnvService.create] — the new env's ID plus its opening observation. */
data class CreatedEnv(
    val envId: EnvId,
    val observation: ObservationResult
)
