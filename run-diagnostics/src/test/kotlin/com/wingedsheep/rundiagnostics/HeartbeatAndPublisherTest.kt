package com.wingedsheep.rundiagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HeartbeatAndPublisherTest : FunSpec({
    test("scheduled heartbeats run independently of useful progress and shut down cleanly") {
        val recorder = newRecorder()
        val ticks = CountDownLatch(3)
        val scheduler = HeartbeatScheduler(
            recorder = recorder,
            interval = Duration.ofMillis(5),
            onHeartbeat = { ticks.countDown() },
        )

        try {
            scheduler.start()
            ticks.await(2, TimeUnit.SECONDS) shouldBe true
            (recorder.snapshot()!!.heartbeatSequence >= 3) shouldBe true
            recorder.snapshot()!!.progress.authoritativeTransitionCount shouldBe null
        } finally {
            scheduler.close()
            recorder.close()
        }

        scheduler.isClosed shouldBe true
    }

    test("coalesces publication requests while one snapshot is in flight") {
        val directory = Files.createTempDirectory("run-diagnostics-coalescing-")
        val recorder = newRecorder()
        val supplierCalls = AtomicInteger(0)
        val firstSnapshotStarted = CountDownLatch(1)
        val releaseFirstSnapshot = CountDownLatch(1)
        val publisher = CoalescingStatusPublisher(
            target = directory.resolve("run-status.json"),
            statusSupplier = {
                if (supplierCalls.incrementAndGet() == 1) {
                    firstSnapshotStarted.countDown()
                    releaseFirstSnapshot.await(2, TimeUnit.SECONDS)
                }
                recorder.snapshot()!!
            },
        )

        try {
            publisher.requestPublish() shouldBe true
            firstSnapshotStarted.await(2, TimeUnit.SECONDS) shouldBe true
            repeat(20) { publisher.requestPublish() }
            releaseFirstSnapshot.countDown()

            publisher.awaitIdle(Duration.ofSeconds(2)) shouldBe true
            supplierCalls.get() shouldBe 2
            val publishedStatus = RunStatusCodec.decode(
                Files.readAllBytes(directory.resolve("run-status.json")),
            )
            publishedStatus.statusPublication.successfulPublicationSequence shouldBe 2
        } finally {
            publisher.close()
            recorder.close()
            directory.toFile().deleteRecursively()
        }
    }

    test("publication and snapshot failures are non-fatal and use stable codes") {
        val directory = Files.createTempDirectory("run-diagnostics-nonfatal-")
        val publisher = CoalescingStatusPublisher(
            target = directory.resolve("run-status.json"),
            statusSupplier = { error("injected snapshot failure") },
        )

        try {
            val result = publisher.publishNow()

            result.shouldBeInstanceOf<StatusPublicationResult.Failed>().code shouldBe
                StatusPublicationFailureCode.STATUS_SNAPSHOT_FAILED
        } finally {
            publisher.close()
            directory.toFile().deleteRecursively()
        }
    }

    test("closed publisher rejects new publication work without throwing") {
        val directory = Files.createTempDirectory("run-diagnostics-closed-")
        val publisher = CoalescingStatusPublisher(
            target = directory.resolve("run-status.json"),
            statusSupplier = { sampleStatus() },
        )
        publisher.close()

        publisher.requestPublish() shouldBe false
        publisher.publishNow().shouldBeInstanceOf<StatusPublicationResult.Failed>().code shouldBe
            StatusPublicationFailureCode.PUBLISHER_CLOSED

        directory.toFile().deleteRecursively()
    }
})
