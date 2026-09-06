package com.wingedsheep.rundiagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AtomicStatusPublicationTest : FunSpec({
    test("characterizes initial and existing-target publication on the current provider") {
        val directory = Files.createTempDirectory("run-diagnostics-provider-")
        try {
            val target = directory.resolve("run-status.json")
            val first = statusBytes(1)
            val second = statusBytes(2)
            val publisher = AtomicStatusFile(target)

            val initial = publisher.publish(first)
            when (initial) {
                is StatusPublicationResult.Published -> {
                    Files.readAllBytes(target) shouldBe first

                    val replacement = publisher.publish(second)
                    when (replacement) {
                        is StatusPublicationResult.Published -> {
                            Files.readAllBytes(target) shouldBe second
                            println("ATOMIC_PROVIDER_RESULT=ATOMIC_REPLACEMENT")
                        }

                        is StatusPublicationResult.Failed -> {
                            replacement.code shouldBe StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE
                            Files.readAllBytes(target) shouldBe first
                            println("ATOMIC_PROVIDER_RESULT=PROVIDER_UNSUPPORTED")
                        }
                    }
                }

                is StatusPublicationResult.Failed -> {
                    initial.code shouldBe StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE
                    println("ATOMIC_PROVIDER_RESULT=PROVIDER_UNSUPPORTED")
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("preserves the old valid status when atomic replacement fails") {
        val directory = Files.createTempDirectory("run-diagnostics-failure-")
        try {
            val target = directory.resolve("run-status.json")
            val old = statusBytes(1)
            Files.write(target, old)

            val failingOps = DelegatingAtomicStatusFileOps(
                move = { _, _ -> throw AtomicMoveNotSupportedException("temp", "target", "injected") },
            )
            val result = AtomicStatusFile(target, ops = failingOps).publish(statusBytes(2))

            result.shouldBeInstanceOf<StatusPublicationResult.Failed>().code shouldBe
                StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_UNAVAILABLE
            Files.readAllBytes(target) shouldBe old
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("preserves the old valid status on a generic atomic move failure") {
        val directory = Files.createTempDirectory("run-diagnostics-move-failure-")
        try {
            val target = directory.resolve("run-status.json")
            val old = statusBytes(1)
            Files.write(target, old)

            val failingOps = DelegatingAtomicStatusFileOps(
                move = { _, _ -> throw IOException("injected atomic move failure") },
            )
            val result = AtomicStatusFile(target, ops = failingOps).publish(statusBytes(2))

            result.shouldBeInstanceOf<StatusPublicationResult.Failed>().code shouldBe
                StatusPublicationFailureCode.STATUS_ATOMIC_REPLACE_FAILED
            Files.readAllBytes(target) shouldBe old
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("does not accept or leave a partial temp status after a write failure") {
        val directory = Files.createTempDirectory("run-diagnostics-partial-")
        try {
            val target = directory.resolve("run-status.json")
            val old = statusBytes(1)
            Files.write(target, old)

            val partialOps = DelegatingAtomicStatusFileOps(
                write = { path, bytes ->
                    Files.write(
                        path,
                        bytes.copyOf(3),
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                    throw IOException("injected partial write")
                },
            )
            val result = AtomicStatusFile(target, ops = partialOps).publish(statusBytes(2))

            result.shouldBeInstanceOf<StatusPublicationResult.Failed>().code shouldBe
                StatusPublicationFailureCode.STATUS_WRITE_FAILED
            Files.readAllBytes(target) shouldBe old
            Files.list(directory).use { stream -> stream.toList() } shouldHaveSize 1
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("reports a missing status directory without throwing") {
        val directory = Files.createTempDirectory("run-diagnostics-missing-")
        val missingDirectory = directory.resolve("removed")
        val target = missingDirectory.resolve("run-status.json")
        try {
            val result = AtomicStatusFile(target).publish("{}".toByteArray(UTF_8))

            result.shouldBeInstanceOf<StatusPublicationResult.Failed>().code shouldBe
                StatusPublicationFailureCode.STATUS_DIRECTORY_UNAVAILABLE
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
})

private fun statusBytes(heartbeatSequence: Long): ByteArray =
    RunStatusCodec.encode(sampleStatus().copy(heartbeatSequence = heartbeatSequence))

private class DelegatingAtomicStatusFileOps(
    private val write: ((Path, ByteArray) -> Unit)? = null,
    private val move: ((Path, Path) -> Unit)? = null,
) : AtomicStatusFileOps {
    private val delegate = AtomicStatusFileOps.system()

    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
        delegate.createTempFile(directory, prefix, suffix)

    override fun writeAndForce(path: Path, bytes: ByteArray) {
        write?.invoke(path, bytes) ?: delegate.writeAndForce(path, bytes)
    }

    override fun atomicReplace(source: Path, target: Path) {
        move?.invoke(source, target) ?: delegate.atomicReplace(source, target)
    }

    override fun deleteIfExists(path: Path) {
        delegate.deleteIfExists(path)
    }
}
