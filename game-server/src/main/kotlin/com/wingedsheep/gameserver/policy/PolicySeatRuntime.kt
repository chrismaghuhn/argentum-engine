package com.wingedsheep.gameserver.policy

import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.config.MlPolicyProperties
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1
import com.wingedsheep.gym.contract.LivePolicyDecisionResponseV1
import com.wingedsheep.sdk.model.EntityId
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Server-owned operational settings; none are read from a client payload. */
data class PolicySeatRuntimeConfiguration(
    val pythonExecutable: String,
    val checkpointDirectory: Path,
    val startupTimeout: Duration = Duration.ofSeconds(30),
    val inferenceTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(pythonExecutable.isNotBlank()) { "ML policy Python executable must not be blank" }
        require(!checkpointDirectory.toString().isBlank()) {
            "ML policy checkpoint directory must not be blank"
        }
        require(!startupTimeout.isNegative && !startupTimeout.isZero) {
            "ML policy startup timeout must be positive"
        }
        require(!inferenceTimeout.isNegative && !inferenceTimeout.isZero) {
            "ML policy inference timeout must be positive"
        }
    }

    companion object {
        fun from(properties: MlPolicyProperties): PolicySeatRuntimeConfiguration {
            if (properties.checkpointDirectory.isBlank()) {
                throw PolicySeatFailure(
                    PolicySeatFailureCode.CONFIGURATION_INVALID,
                    "server-owned ML policy checkpoint directory is not configured",
                )
            }
            val path = try {
                Path.of(properties.checkpointDirectory)
            } catch (failure: InvalidPathException) {
                throw PolicySeatFailure(
                    PolicySeatFailureCode.CONFIGURATION_INVALID,
                    "server-owned ML policy checkpoint directory is invalid",
                    failure,
                )
            }
            return PolicySeatRuntimeConfiguration(
                pythonExecutable = properties.pythonExecutable,
                checkpointDirectory = path,
                startupTimeout = Duration.ofMillis(properties.startupTimeoutMs),
                inferenceTimeout = Duration.ofMillis(properties.inferenceTimeoutMs),
            )
        }
    }
}

/** Transport/lifecycle adapter for one already-started C1_07B worker. */
interface PolicySeatWorker : AutoCloseable {
    fun decide(request: LivePolicyDecisionRequestV1): LivePolicyDecisionResponseV1
}

fun interface PolicySeatWorkerFactory {
    fun start(configuration: PolicySeatRuntimeConfiguration): PolicySeatWorker

    companion object {
        val SERVER_OWNED: PolicySeatWorkerFactory =
            PolicySeatWorkerFactory { configuration -> LocalPythonPolicyWorker.start(configuration) }
    }
}

/** A source snapshot plus the exact persisted PolicyTieRng input used for one request. */
data class PolicySeatInferenceCapture(
    val playerId: EntityId,
    val source: LivePolicySourceSnapshot,
    val policyState: PolicySeatStateV1,
) {
    fun toRequest(requestId: String): LivePolicyDecisionRequestV1 =
        source.snapshot.toRequest(requestId, policyState.toLiveState())
}

sealed interface PolicySeatDecisionResult {
    data class Accepted(
        val actionResult: GameSession.ActionResult,
        val selectedSourceBindingOrdinal: Int,
    ) : PolicySeatDecisionResult

    data class Rejected(val failure: PolicySeatFailure) : PolicySeatDecisionResult
}

/**
 * Ephemeral per-seat runtime. It owns one worker and one serialized inference lane; controller
 * authority and PolicyTieRng state remain in [GameSession] so they can be persisted/rehydrated.
 */
class PolicySeatRuntime(
    private val gameSession: GameSession,
    val playerId: EntityId,
    private val worker: PolicySeatWorker,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(DaemonThreadFactory)

    fun decide(): PolicySeatDecisionResult {
        if (closed.get()) {
            return PolicySeatDecisionResult.Rejected(
                PolicySeatFailure(PolicySeatFailureCode.RUNTIME_CLOSED, "policy seat runtime is closed"),
            )
        }
        if (!inFlight.compareAndSet(false, true)) {
            return PolicySeatDecisionResult.Rejected(
                PolicySeatFailure(
                    PolicySeatFailureCode.DUPLICATE_IN_FLIGHT,
                    "policy seat already has an inference in flight",
                ),
            )
        }
        return try {
            decideWhileClaimed()
        } finally {
            inFlight.set(false)
        }
    }

    /** Schedule one inference without holding GameSession's state lock during worker I/O. */
    fun decideAsync(onResult: (PolicySeatDecisionResult) -> Unit): Boolean {
        if (closed.get() || !inFlight.compareAndSet(false, true)) return false
        return try {
            executor.execute {
                val result = try {
                    decideWhileClaimed()
                } finally {
                    inFlight.set(false)
                }
                onResult(result)
            }
            true
        } catch (failure: RuntimeException) {
            inFlight.set(false)
            false
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { worker.close() }
        executor.shutdownNow()
    }

    internal fun isInFlight(): Boolean = inFlight.get()

    private fun decideWhileClaimed(): PolicySeatDecisionResult {
        val capture = try {
            gameSession.captureLivePolicyDecision(playerId)
        } catch (failure: PolicySeatFailure) {
            return PolicySeatDecisionResult.Rejected(failure)
        }
        try {
            val request = capture.toRequest(requestIdFactory())
            val result = worker.decide(request)
            if (closed.get()) {
                return PolicySeatDecisionResult.Rejected(
                    PolicySeatFailure(PolicySeatFailureCode.RUNTIME_CLOSED, "policy seat runtime was closed during inference"),
                )
            }
            return gameSession.acceptLivePolicyDecision(capture, request, result)
        } catch (failure: PolicySeatFailure) {
            if (failure.code.isWorkerFailure()) {
                closed.set(true)
                runCatching { worker.close() }
            }
            return PolicySeatDecisionResult.Rejected(failure)
        } catch (failure: Exception) {
            closed.set(true)
            runCatching { worker.close() }
            return PolicySeatDecisionResult.Rejected(
                PolicySeatFailure(
                    PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                    "policy worker operation failed closed",
                    failure,
                ),
            )
        }
    }

    private fun PolicySeatFailureCode.isWorkerFailure(): Boolean = when (this) {
        PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
        PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
        PolicySeatFailureCode.WORKER_TIMEOUT,
        PolicySeatFailureCode.WORKER_CRASH,
        -> true

        else -> false
    }

    private object DaemonThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(
            runnable,
            "argentum-policy-seat-runtime",
        ).apply { isDaemon = true }
    }
}

/** Ephemeral runtime registry; only the server's GamePlayHandler calls it. */
class PolicySeatRuntimeManager(
    private val gameProperties: GameProperties,
    private val workerFactory: PolicySeatWorkerFactory = PolicySeatWorkerFactory.SERVER_OWNED,
) {
    private val runtimes = ConcurrentHashMap<String, ConcurrentHashMap<EntityId, PolicySeatRuntime>>()

    fun runtimeFor(gameSession: GameSession, playerId: EntityId): PolicySeatRuntime {
        if (!gameProperties.mlPolicy.enabled) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.CONFIGURATION_INVALID,
                "ML policy controller is disabled by server configuration",
            )
        }
        val authority = gameSession.getControllerAuthority(playerId)
        if (authority?.isMlPolicy != true) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.CONTROLLER_AUTHORITY_INVALID,
                "requested runtime has no explicit ML_POLICY authority",
            )
        }
        val configuration = PolicySeatRuntimeConfiguration.from(gameProperties.mlPolicy)
        gameSession.installPolicyRuntimeCloseHook { closeGame(gameSession.sessionId) }
        val perGame = runtimes.computeIfAbsent(gameSession.sessionId) { ConcurrentHashMap() }
        return perGame.computeIfAbsent(playerId) {
            PolicySeatRuntime(
                gameSession = gameSession,
                playerId = playerId,
                worker = workerFactory.start(configuration),
            )
        }
    }

    fun dispatchIfNeeded(
        gameSession: GameSession,
        onResult: (PolicySeatDecisionResult) -> Unit,
    ): Boolean {
        val playerId = gameSession.policySeatToAct() ?: return false
        if (!gameProperties.mlPolicy.enabled) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.CONFIGURATION_INVALID,
                "ML policy controller is disabled by server configuration",
            )
        }
        return runtimeFor(gameSession, playerId).decideAsync(onResult)
    }

    fun closeGame(gameSessionId: String) {
        runtimes.remove(gameSessionId)?.values?.forEach { runtime -> runtime.close() }
    }
}
