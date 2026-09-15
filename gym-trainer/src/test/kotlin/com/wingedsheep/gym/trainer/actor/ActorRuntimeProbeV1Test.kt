package com.wingedsheep.gym.trainer.actor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ActorRuntimeProbeV1Test : FunSpec({
    test("runtime probe reports bounded provider-neutral facts without private paths") {
        val root = Files.createTempDirectory("actor-runtime-probe-")
        val snapshot = ActorRuntimeProbeV1(
            sourceRepositoryRoot = root,
            workingRoot = root,
            scratchRoot = root,
        ).probe()

        snapshot.osName shouldBe System.getProperty("os.name")
        snapshot.architecture shouldBe System.getProperty("os.arch")
        snapshot.availableProcessors shouldBe Runtime.getRuntime().availableProcessors()
        snapshot.renderSafeText().contains(root.toAbsolutePath().toString()) shouldBe false
        snapshot.renderSafeText().contains("Authorization") shouldBe false
        snapshot.renderSafeText().contains("token") shouldBe false
    }
})
