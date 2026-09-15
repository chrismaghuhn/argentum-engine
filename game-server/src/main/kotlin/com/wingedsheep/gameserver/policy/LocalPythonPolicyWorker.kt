package com.wingedsheep.gameserver.policy

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1
import com.wingedsheep.gym.contract.LivePolicyDecisionResponseV1
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val WORKER_MODULE = "argentum_ml.live_policy.worker"
private const val PROTOCOL_VERSION = 1
private const val MESSAGE_HEALTH = "HEALTH"
private const val MESSAGE_REQUEST = "REQUEST"
private const val MESSAGE_RESPONSE = "RESPONSE"
private const val MESSAGE_ERROR = "ERROR"
private const val MESSAGE_SHUTDOWN = "SHUTDOWN"
private const val MESSAGE_SHUTDOWN_ACK = "SHUTDOWN_ACK"
private const val FRAME_HEADER_BYTES = 4
private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
private const val SELECTION_ADDRESS_IDENTITY = "argentum-ml-live-selection-address@v1"
private const val NUMERIC_PROFILE_CLASS = "C1_REFERENCE_NUMERIC_PROFILE"

/** JVM transport adapter for the fixed C1_07B Python worker. */
class LocalPythonPolicyWorker private constructor(
    private val process: Process,
    private val configuration: PolicySeatRuntimeConfiguration,
) : PolicySeatWorker {
    private val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream
    private val events = LinkedBlockingQueue<WorkerEvent>()
    private val lock = Any()
    @Volatile
    private var closed = false
    private val reader = Thread(::readLoop, "argentum-policy-worker-reader").apply {
        isDaemon = true
    }

    init {
        reader.start()
        try {
            awaitHealth()
        } catch (failure: PolicySeatFailure) {
            abort()
            throw failure
        }
    }

    override fun decide(request: LivePolicyDecisionRequestV1): LivePolicyDecisionResponseV1 =
        synchronized(lock) {
            requireOpen()
            try {
                FramedWorkerProtocol.writeFrame(output, FramedWorkerProtocol.requestFrame(request))
            } catch (failure: PolicySeatFailure) {
                abort()
                throw failure
            }
            val frame = try {
                nextFrame(configuration.inferenceTimeout)
            } catch (failure: PolicySeatFailure) {
                abort()
                throw failure
            } catch (error: Exception) {
                abort()
                throw failure(
                    PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                    "worker response could not be read",
                    error,
                )
            }
            try {
                FramedWorkerProtocol.response(frame, request)
            } catch (failure: PolicySeatFailure) {
                abort()
                throw failure
            } catch (error: Exception) {
                abort()
                throw failure(
                    PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                    "worker response could not be validated",
                    error,
                )
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (process.isAlive) {
                runCatching {
                    FramedWorkerProtocol.writeFrame(output, FramedWorkerProtocol.shutdownFrame())
                    val event = events.poll(2, TimeUnit.SECONDS)
                    if (event is WorkerEvent.Frame) {
                        FramedWorkerProtocol.shutdownAck(event.value)
                    }
                }
                terminateProcess()
            }
            runCatching { output.close() }
            runCatching { input.close() }
            reader.join(1_000)
        }
    }

    private fun awaitHealth() {
        val event = nextEvent(configuration.startupTimeout)
        when (event) {
            is WorkerEvent.EndOfStream -> throw failure(
                PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
                "worker exited before READY",
            )

            is WorkerEvent.ReaderFailure -> throw failure(
                PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
                "worker health frame could not be read",
                event.error,
            )

            is WorkerEvent.Frame -> {
                try {
                    FramedWorkerProtocol.health(event.value)
                } catch (handshakeFailure: PolicySeatFailure) {
                    throw failure(
                        PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
                        "worker health handshake was rejected",
                        handshakeFailure,
                    )
                }
            }
        }
    }

    private fun nextFrame(timeout: java.time.Duration): JsonObject {
        return when (val event = nextEvent(timeout)) {
            is WorkerEvent.Frame -> event.value
            is WorkerEvent.EndOfStream -> throw failure(
                PolicySeatFailureCode.WORKER_CRASH,
                "worker closed stdout during inference",
            )

            is WorkerEvent.ReaderFailure -> throw failure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker frame reader failed",
                event.error,
            )
        }
    }

    private fun nextEvent(timeout: java.time.Duration): WorkerEvent =
        events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
            ?: throw failure(
                PolicySeatFailureCode.WORKER_TIMEOUT,
                "worker exceeded its bounded operation timeout",
            )

    private fun readLoop() {
        try {
            while (true) {
                val frame = FramedWorkerProtocol.readFrame(input) ?: run {
                    events.put(WorkerEvent.EndOfStream)
                    return
                }
                events.put(WorkerEvent.Frame(frame))
            }
        } catch (failure: Throwable) {
            events.put(WorkerEvent.ReaderFailure(failure))
        }
    }

    private fun requireOpen() {
        if (closed || !process.isAlive) {
            throw failure(PolicySeatFailureCode.WORKER_CRASH, "policy worker is not alive")
        }
    }

    private fun abort() {
        closed = true
        terminateProcess()
        runCatching { output.close() }
        runCatching { input.close() }
        reader.join(1_000)
    }

    private fun terminateProcess() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun failure(
        code: PolicySeatFailureCode,
        message: String,
        cause: Throwable? = null,
    ): PolicySeatFailure = PolicySeatFailure(code, message, cause)

    companion object {
        fun start(configuration: PolicySeatRuntimeConfiguration): PolicySeatWorker {
            val command = listOf(
                configuration.pythonExecutable,
                "-m",
                WORKER_MODULE,
                "--checkpoint-dir",
                configuration.checkpointDirectory.toString(),
            )
            val process = try {
                ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (failure: Exception) {
                throw PolicySeatFailure(
                    PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
                    "fixed C1_07B Python worker could not be started",
                    failure,
                )
            }
            return try {
                LocalPythonPolicyWorker(process, configuration)
            } catch (failure: PolicySeatFailure) {
                runCatching { process.destroyForcibly() }
                throw failure
            } catch (failure: Exception) {
                runCatching { process.destroyForcibly() }
                throw PolicySeatFailure(
                    PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
                    "fixed C1_07B Python worker startup failed",
                    failure,
                )
            }
        }
    }
}

private sealed interface WorkerEvent {
    data class Frame(val value: JsonObject) : WorkerEvent
    data object EndOfStream : WorkerEvent
    data class ReaderFailure(val error: Throwable) : WorkerEvent
}

private object FramedWorkerProtocol {
    private val profileFields = mapOf(
        "profileIdentity" to C1_07B_POLICY_PROFILE_IDENTITY,
        "checkpointId" to C1_07B_CHECKPOINT_IDENTITY,
        "modelArchitectureIdentity" to
            "argentum-ml-c1-06-feed-forward-candidate-scorer@v1",
        "modelConfigDigest" to
            "542b74694061b07c8adc397e27ea3a57b71edaaa33af24b05b99099d95d3c966",
        "inferenceContractIdentity" to C1_07B_INFERENCE_CONTRACT_IDENTITY,
        "selectionContractIdentity" to C1_07B_SELECTION_CONTRACT_IDENTITY,
        "selectionAddressContractIdentity" to SELECTION_ADDRESS_IDENTITY,
        "policyRngContractIdentity" to C1_07B_POLICY_RNG_CONTRACT_IDENTITY,
        "numericProfileClass" to NUMERIC_PROFILE_CLASS,
    )

    fun requestFrame(request: LivePolicyDecisionRequestV1): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("messageType", MESSAGE_REQUEST)
        profileFields.forEach { (key, value) -> put(key, value) }
        put("requestId", request.requestId)
        put("request", A3SemanticJson.strictJson.encodeToJsonElement(
            LivePolicyDecisionRequestV1.serializer(),
            request,
        ))
    }

    fun shutdownFrame(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("messageType", MESSAGE_SHUTDOWN)
        profileFields.forEach { (key, value) -> put(key, value) }
    }

    fun writeFrame(output: OutputStream, value: JsonObject) {
        val bytes = A3SemanticJson.canonicalJson(value).toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker frame exceeds the bounded protocol size",
            )
        }
        try {
            output.write(ByteBuffer.allocate(FRAME_HEADER_BYTES).putInt(bytes.size).array())
            output.write(bytes)
            output.flush()
        } catch (failure: Exception) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_CRASH,
                "worker request could not be written",
                failure,
            )
        }
    }

    fun readFrame(input: InputStream): JsonObject? {
        val header = readExact(input, FRAME_HEADER_BYTES, allowCleanEof = true) ?: return null
        val size = ByteBuffer.wrap(header).int
        if (size <= 0 || size > MAX_FRAME_BYTES) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker frame has an invalid bounded size",
            )
        }
        val payload = readExact(input, size, allowCleanEof = false)
            ?: throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker frame payload is truncated",
            )
        val text = String(payload, StandardCharsets.UTF_8)
        val element = try {
            A3SemanticJson.strictJson.parseToJsonElement(text)
        } catch (failure: Exception) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker frame is not valid JSON",
                failure,
            )
        }
        if (A3SemanticJson.canonicalJson(element) != text) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker frame is not canonical JSON",
            )
        }
        return element as? JsonObject ?: throw PolicySeatFailure(
            PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
            "worker frame is not a JSON object",
        )
    }

    fun health(frame: JsonObject) {
        requireEnvelope(
            frame,
            MESSAGE_HEALTH,
            setOf("protocolVersion", "messageType", "status") + profileFields.keys,
        )
        require(frame["status"]?.jsonPrimitive?.content == "READY") {
            "worker did not report READY"
        }
    }

    fun response(
        frame: JsonObject,
        request: LivePolicyDecisionRequestV1,
    ): LivePolicyDecisionResponseV1 {
        if (frame["messageType"]?.jsonPrimitive?.content == MESSAGE_ERROR) {
            throw parseError(frame, request.requestId)
        }
        requireEnvelope(
            frame,
            MESSAGE_RESPONSE,
            setOf("protocolVersion", "messageType", "requestId", "response", "scoredCandidateCount") + profileFields.keys,
        )
        require(frame["requestId"]?.jsonPrimitive?.content == request.requestId) {
            "worker response requestId does not match the request"
        }
        require(frame["scoredCandidateCount"]?.jsonPrimitive?.intOrNull ==
            request.selectionBindingChannel.presentMask.count { it }
        ) {
            "worker response score count does not match the request"
        }
        val responseElement = frame["response"] as? JsonObject
            ?: throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker response payload is not an object",
            )
        val response = try {
            A3SemanticJson.strictJson.decodeFromJsonElement(
                LivePolicyDecisionResponseV1.serializer(),
                responseElement,
            )
        } catch (failure: Exception) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker response payload is malformed",
                failure,
            )
        }
        try {
            response.requireCompatible(request)
        } catch (failure: IllegalArgumentException) {
            throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker response is not compatible with the request",
                failure,
            )
        }
        return response
    }

    fun shutdownAck(frame: JsonObject) {
        requireEnvelope(
            frame,
            MESSAGE_SHUTDOWN_ACK,
            setOf("protocolVersion", "messageType") + profileFields.keys,
        )
    }

    private fun parseError(frame: JsonObject, requestId: String?): PolicySeatFailure {
        val phasePrimitive = frame["phase"] as? JsonPrimitive
            ?: throw PolicySeatFailure(
                PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                "worker error has no valid phase",
            )
        val phase = phasePrimitive.content
        val base = setOf("protocolVersion", "messageType", "errorCode", "phase", "message") + profileFields.keys
        val allowed = if (phase == "inference") base + "requestId" else base
        requireEnvelope(frame, MESSAGE_ERROR, allowed)
        require(phasePrimitive.isString) { "worker error phase is not a string" }
        require(phase in setOf("startup", "protocol", "inference")) {
            "worker error phase is unsupported"
        }
        require(
            (frame["errorCode"] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true,
        ) {
            "worker error code is invalid"
        }
        require(
            (frame["message"] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true,
        ) {
            "worker error message is invalid"
        }
        val responseRequestId = (frame["requestId"] as? JsonPrimitive)?.content
        if (phase == "inference") {
            require(responseRequestId != null && responseRequestId == requestId) {
                "worker inference error requestId does not match the request"
            }
        }
        return PolicySeatFailure(
            if (phase == "inference") PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE
            else PolicySeatFailureCode.WORKER_STARTUP_FAILURE,
            "worker returned a typed $phase error",
        )
    }

    private fun requireEnvelope(
        frame: JsonObject,
        messageType: String,
        keys: Set<String>,
    ) {
        require(frame.keys == keys) { "worker envelope fields are not exact" }
        require(frame["protocolVersion"]?.jsonPrimitive?.intOrNull == PROTOCOL_VERSION) {
            "worker protocol version is unsupported"
        }
        require(frame["messageType"]?.jsonPrimitive?.content == messageType) {
            "worker message type is unexpected"
        }
        profileFields.forEach { (key, expected) ->
            require(frame[key]?.jsonPrimitive?.content == expected) {
                "worker profile field $key differs from the server-owned profile"
            }
        }
    }

    private fun readExact(
        input: InputStream,
        size: Int,
        allowCleanEof: Boolean,
    ): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) {
                if (allowCleanEof && offset == 0) return null
                throw PolicySeatFailure(
                    PolicySeatFailureCode.WORKER_PROTOCOL_FAILURE,
                    "worker frame ended before all bytes were received",
                )
            }
            if (read == 0) continue
            offset += min(read, size - offset)
        }
        return bytes
    }
}
