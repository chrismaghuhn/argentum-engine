package com.wingedsheep.gameserver.controller

import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.HeadlessEngineAiGame
import com.wingedsheep.gameserver.replay.InMemoryReplayStore
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk

class DevReplayImportControllerTest : FunSpec({

    val replay = HeadlessEngineAiGame.play(gameId = "dev-import-test", seed = 11L, maxSteps = 8).replay

    fun controller(): Pair<DevReplayImportController, ReplayService> {
        val reconstructor = HeadlessEngineAiGame.serverReconstructor()
        val service = ReplayService(InMemoryReplayStore(), reconstructor, mockk(relaxed = true))
        return DevReplayImportController(service, reconstructor) to service
    }

    test("an encoded replay file is stored and reported EXACT with its viewer path") {
        val (controller, service) = controller()

        val response = controller.importReplay(ReplayCodec.encode(replay))

        response.statusCode.value() shouldBe 200
        val body = response.body.shouldBeInstanceOf<DevReplayImportController.ImportResponse>()
        body.gameId shouldBe "dev-import-test"
        body.viewerPath shouldBe "/replay/dev-import-test"
        body.fidelity shouldBe "EXACT"
        service.find("dev-import-test").shouldNotBeNull() shouldBe replay
    }

    test("plain CompactReplay JSON is accepted too, and re-importing it is a no-op") {
        val (controller, _) = controller()
        val json = persistenceJson.encodeToString(CompactReplay.serializer(), replay)

        controller.importReplay(json).statusCode.value() shouldBe 200
        controller.importReplay(json).statusCode.value() shouldBe 200
    }

    test("a different replay under an existing game id is refused, not overwritten") {
        val (controller, service) = controller()
        controller.importReplay(ReplayCodec.encode(replay))

        val other = replay.copy(winnerName = "someone else")
        controller.importReplay(ReplayCodec.encode(other)).statusCode.value() shouldBe 409
        service.find("dev-import-test") shouldBe replay
    }

    test("garbage is a bad request") {
        val (controller, _) = controller()
        controller.importReplay("not a replay").statusCode.value() shouldBe 400
    }
})
