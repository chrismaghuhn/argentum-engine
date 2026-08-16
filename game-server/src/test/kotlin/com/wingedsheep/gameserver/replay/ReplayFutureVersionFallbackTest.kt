package com.wingedsheep.gameserver.replay

import com.wingedsheep.gameserver.persistence.GameReplayPlayerRow
import com.wingedsheep.gameserver.persistence.GameReplayRepository
import com.wingedsheep.gameserver.persistence.GameReplayRow
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant

class ReplayFutureVersionFallbackTest : FunSpec({

    val gameId = "future-version"
    val archive = "{\"initialSnapshot\":{\"gameId\":\"$gameId\"},\"deltas\":[]}"

    fun futureReplay() = CompactReplay(
        version = CompactReplay.CURRENT_VERSION + 1,
        gameId = gameId,
        players = listOf(
            ReplayPlayerInfo("p1", "Alice"),
            ReplayPlayerInfo("p2", "Bob"),
        ),
        startedAt = "2026-01-01T00:00:00Z",
        endedAt = "2026-01-01T00:01:00Z",
        winnerName = "Alice",
        setup = ReplaySetup(
            seed = 1L,
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

    fun row(presentation: String? = ReplayCodec.encodeText(archive)) =
        GameReplayRow(
            gameId = gameId,
            winnerName = "Alice",
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            endedAt = Instant.parse("2026-01-01T00:01:00Z"),
            frameCount = 1,
            playerNames = "Alice, Bob",
            status = ReplayStatus.FINISHED.name,
            engineVersion = "future-build",
            data = ReplayCodec.encode(futureReplay()),
            presentation = presentation,
            players = setOf(
                GameReplayPlayerRow(seat = 0, playerId = "p1", playerName = "Alice"),
                GameReplayPlayerRow(seat = 1, playerId = "p2", playerName = "Bob"),
            ),
        )

    test("unsupported persisted versions become a marker with decoded presentation JSON") {
            val repository = mockk<GameReplayRepository> {
                every { findByGameId(gameId) } returns row()
            }
            val store = JdbcReplayStore(repository)

            val read = store.find(gameId).shouldNotBeNull()
            val unsupported = (read as ReplayRead.UnsupportedVersion)
            unsupported.version shouldBe CompactReplay.CURRENT_VERSION + 1
            unsupported.playerIds shouldBe listOf("p1", "p2")
            unsupported.playerNames shouldBe listOf("Alice", "Bob")
            unsupported.presentation shouldBe archive

            val reconstructor = mockk<ReplayReconstructor>(relaxed = true)
            val service = ReplayService(store, reconstructor, mockk(relaxed = true))
            val payload = service.viewerPayload(gameId).shouldNotBeNull()

            payload.body shouldBe archive
            payload.frameCount shouldBe 1
            payload.fidelity shouldBe ReplayFidelity.DIVERGED
            payload.degradedReason shouldBe
                "Unsupported CompactReplay version; showing the archived presentation."
            payload.stateReproducible shouldBe false
            verify(exactly = 0) { reconstructor.reconstruct(any()) }
    }

    test("unsupported version without a valid archive is controlled unavailable") {
            val repository = mockk<GameReplayRepository> {
                every { findByGameId(gameId) } returns row(presentation = "not-gzip")
            }
            val store = JdbcReplayStore(repository)
            val unsupported = store.find(gameId).shouldNotBeNull() as ReplayRead.UnsupportedVersion
            unsupported.presentation.shouldBeNull()

            val service = ReplayService(store, mockk(relaxed = true), mockk(relaxed = true))
            service.viewerPayload(gameId).shouldBeNull()
    }
})
