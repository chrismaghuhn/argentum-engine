package com.wingedsheep.rundiagnostics.supervisor

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a diagnostic child command with a hard wait and output bound. Destroying a timed-out child
 * here never targets the supervised process; the supervisor has no termination operation.
 */
public class JdkBoundedCommandRunner(
    private val processFactory: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
) : BoundedCommandRunner {
    override fun run(command: List<String>, timeoutMillis: Long, maxBytes: Int): BoundedCommandResult {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxBytes in 1..16 * 1024 * 1024) { "maxBytes is outside the bounded range" }

        val process = try {
            processFactory(command.toList())
        } catch (_: Exception) {
            return BoundedCommandResult(
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.COMMAND_FAILED,
            )
        }
        val output = AtomicReference<ByteArray?>(null)
        val readerDone = CountDownLatch(1)
        val reader = Thread({
            try {
                process.inputStream.use { input -> output.set(input.readNBytes(maxBytes + 1)) }
            } catch (_: Exception) {
                output.set(null)
            } finally {
                readerDone.countDown()
            }
        }, "run-diagnostics-command-reader")
        reader.isDaemon = true
        reader.start()

        return try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                stopChild(process)
                BoundedCommandResult(
                    availability = EvidenceAvailability.TIMED_OUT,
                    failureCode = SupervisorFailureCode.COMMAND_TIMED_OUT,
                )
            } else if (!readerDone.await(1, TimeUnit.SECONDS)) {
                BoundedCommandResult(
                    availability = EvidenceAvailability.FAILED,
                    failureCode = SupervisorFailureCode.COMMAND_FAILED,
                )
            } else {
                val bytes = output.get()
                when {
                    bytes == null -> BoundedCommandResult(
                        availability = EvidenceAvailability.FAILED,
                        failureCode = SupervisorFailureCode.COMMAND_FAILED,
                    )

                    bytes.size > maxBytes -> BoundedCommandResult(
                        availability = EvidenceAvailability.FAILED,
                        capturedBytes = maxBytes,
                        failureCode = SupervisorFailureCode.COMMAND_OUTPUT_TOO_LARGE,
                    )

                    process.exitValue() != 0 -> BoundedCommandResult(
                        availability = EvidenceAvailability.FAILED,
                        exitCode = process.exitValue(),
                        output = bytes.toString(UTF_8),
                        capturedBytes = bytes.size,
                        failureCode = SupervisorFailureCode.COMMAND_FAILED,
                    )

                    else -> BoundedCommandResult(
                        availability = EvidenceAvailability.AVAILABLE,
                        exitCode = 0,
                        output = bytes.toString(UTF_8),
                        capturedBytes = bytes.size,
                    )
                }
            }
        } catch (_: InterruptedException) {
            stopChild(process)
            Thread.currentThread().interrupt()
            BoundedCommandResult(
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.COMMAND_FAILED,
            )
        } catch (_: Exception) {
            stopChild(process)
            BoundedCommandResult(
                availability = EvidenceAvailability.FAILED,
                failureCode = SupervisorFailureCode.COMMAND_FAILED,
            )
        }
    }

    private fun stopChild(process: Process) {
        try {
            process.destroy()
            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        } catch (_: Exception) {
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
                // The diagnostic child is best effort; the supervised process is never touched here.
            }
        }
    }
}
