package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReplayVersionCompatibilityTest : FunSpec({

    fun replay(version: Int = CompactReplay.CURRENT_VERSION) = CompactReplay(
        version = version,
        gameId = "version-test",
        players = listOf(
            ReplayPlayerInfo("p1", "Alice"),
            ReplayPlayerInfo("p2", "Bob"),
        ),
        startedAt = "2026-01-01T00:00:00Z",
        endedAt = "2026-01-01T00:01:00Z",
        winnerName = null,
        setup = ReplaySetup(
            seed = 7L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            players = listOf(
                ReplayPlayerSetup("p1", "Alice", Deck(cards = listOf("Forest"))),
                ReplayPlayerSetup("p2", "Bob", Deck(cards = listOf("Forest"))),
            ),
            seatRoster = emptyList(),
        ),
        actions = emptyList(),
    )

    fun legacyWithoutVersion(): String {
        val root = persistenceJson
            .encodeToString(CompactReplay.serializer(), replay(version = 1))
            .let { persistenceJson.parseToJsonElement(it).jsonObject }
        val withoutVersion = JsonObject(root.filterKeys { it != "version" })
        return ReplayCodec.encodeText(
            persistenceJson.encodeToString(JsonElement.serializer(), withoutVersion)
        )
    }

    test("VERSION-01-MISSING materializes omitted wire version as v1") {
        val decoded = ReplayCodec.decode(legacyWithoutVersion())

        decoded.version shouldBe 1

        val reencoded = persistenceJson
            .parseToJsonElement(ReplayCodec.decodeText(ReplayCodec.encode(decoded)).trim())
            .jsonObject
        reencoded["version"]?.jsonPrimitive?.int shouldBe 1
        ReplayFingerprint.of(GameState(), decoded.version).length shouldBe 16
    }
})
