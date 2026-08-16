package com.wingedsheep.gameserver.replay

import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.engine.state.YieldKind
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/**
 * Replays have one home, and an interrupted recording resumes from it — or honestly refuses to.
 *
 * The recording used to ride along in the Redis session blob, which was written on every state
 * change and so was always current. The store is flushed on a timer instead, which is cheaper but
 * means the stored log can trail the live game. The dangerous failure isn't losing those actions —
 * it's appending the *rest* of the game onto the short prefix, producing a replay of a game nobody
 * played that looks entirely well-formed. These tests pin the fingerprint gate that prevents it.
 */
class ReplayStorageTest : ScenarioTestBase() {

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun replay(gameId: String, players: List<Pair<String, String>>, endedAt: String) =
        CompactReplay(
            gameId = gameId,
            players = players.map { (id, name) -> ReplayPlayerInfo(id, name) },
            startedAt = endedAt,
            endedAt = endedAt,
            winnerName = null,
            setup = ReplaySetup(
                seed = 1L,
                format = Format.Standard,
                attackMode = AttackMode.MULTIPLE,
                players = players.map { (id, name) ->
                    ReplayPlayerSetup(playerId = id, name = name, deck = Deck(cards = listOf("Forest")))
                },
                seatRoster = emptyList(),
            ),
            actions = emptyList(),
        )

    /** Play a real recorded game and stop after [actions] recorded actions. */
    private fun playPartialGame(): GameSession {
        val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
        val p1 = EntityId.of("resume-p1")
        val p2 = EntityId.of("resume-p2")
        session.addPlayer(PlayerSession(mockWs("resume-ws1"), p1, "Alice"), mapOf("Forest" to 40))
        session.addPlayer(PlayerSession(mockWs("resume-ws2"), p2, "Bob"), mapOf("Forest" to 40))
        session.startGame()
        session.keepHand(p1)
        session.keepHand(p2)
        repeat(30) {
            val state = session.getStateForTesting() ?: return@repeat
            if (state.gameOver) return@repeat
            state.priorityPlayerId?.let { session.executeAutoPass(it) }
        }
        return session
    }

    private fun playToActionCount(target: Int): GameSession {
        val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
        val p1 = EntityId.of("cadence-p1")
        val p2 = EntityId.of("cadence-p2")
        session.addPlayer(PlayerSession(mockWs("cadence-ws1"), p1, "Alice"), mapOf("Forest" to 40))
        session.addPlayer(PlayerSession(mockWs("cadence-ws2"), p2, "Bob"), mapOf("Forest" to 40))
        session.startGame()
        session.keepHand(p1)
        session.keepHand(p2)

        var attempts = 0
        while (session.getRecordedActions().size < target && attempts++ < 200) {
            val state = session.getStateForTesting() ?: break
            if (state.gameOver) break
            state.priorityPlayerId?.let { session.executeAutoPass(it) }
        }
        session.getRecordedActions().size shouldBe target
        return session
    }

    /** Exactly what the flusher stores: one atomic snapshot turned into a record + its gate value. */
    private fun GameSession.flushRecord(): Pair<CompactReplay, String> {
        val snapshot = replayRecordingSnapshot().shouldNotBeNull()
        return CompactReplay(
            version = snapshot.version,
            gameId = sessionId,
            players = getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
            startedAt = (snapshot.startedAt ?: Instant.now()).toString(),
            endedAt = "",
            winnerName = null,
            setup = snapshot.setup,
            actions = snapshot.actions,
            yields = snapshot.yields,
            checkpoints = if (ReplayCheckpointPolicy.requiresTailCheckpoint(snapshot.version)) {
                ReplayCheckpointPolicy.withV3Tail(
                    checkpoints = snapshot.checkpoints,
                    actionCount = snapshot.actions.size,
                    fingerprint = snapshot.fingerprint,
                )
            } else snapshot.checkpoints,
        ) to snapshot.fingerprint
    }

    init {
        test("the in-memory store lists a player's finished games, newest first") {
            val store = InMemoryReplayStore()
            store.save(StoredReplay(replay("g1", listOf("alice" to "Alice", "bob" to "Bob"), "2026-01-01T00:00:00Z"), ReplayStatus.FINISHED))
            store.save(StoredReplay(replay("g2", listOf("alice" to "Alice", "carol" to "Carol"), "2026-01-03T00:00:00Z"), ReplayStatus.FINISHED))
            store.save(StoredReplay(replay("g3", listOf("bob" to "Bob", "carol" to "Carol"), "2026-01-02T00:00:00Z"), ReplayStatus.FINISHED))

            store.findRecentForPlayer("alice", 10).map { it.gameId } shouldContainExactly listOf("g2", "g1")
            (store.find("g3").shouldNotBeNull() as ReplayRead.Decoded).stored.replay.gameId shouldBe "g3"
            store.find("nope") shouldBe null
        }

        test("an in-progress recording is listed for resume but never in a player's history") {
            val store = InMemoryReplayStore()
            store.save(
                StoredReplay(
                    replay("live", listOf("alice" to "Alice", "bob" to "Bob"), ""),
                    ReplayStatus.IN_PROGRESS,
                    resumeFingerprint = "abc",
                )
            )

            store.findRecentForPlayer("alice", 10) shouldBe emptyList()
            store.findInProgress().map { it.replay.gameId } shouldContainExactly listOf("live")
            store.findInProgress().single().resumeFingerprint shouldBe "abc"
        }

        test("an abandoned recording is promoted to a finished, watchable replay") {
            val store = InMemoryReplayStore()
            store.save(
                StoredReplay(
                    replay("g-abandoned", listOf("alice" to "Alice", "bob" to "Bob"), ""),
                    ReplayStatus.IN_PROGRESS,
                    resumeFingerprint = "abc",
                )
            )
            val service = ReplayService(store, mockk(relaxed = true), mockk(relaxed = true))

            service.finalizePartial("g-abandoned")

            (store.find("g-abandoned").shouldNotBeNull() as ReplayRead.Decoded).stored.status shouldBe ReplayStatus.FINISHED
            store.findRecentForPlayer("alice", 10).map { it.gameId } shouldContainExactly listOf("g-abandoned")
            // Finalizing twice is harmless — a finished record is left alone.
            service.finalizePartial("g-abandoned")
            (store.find("g-abandoned").shouldNotBeNull() as ReplayRead.Decoded).stored.status shouldBe ReplayStatus.FINISHED
        }

        test("a flush that lands after game over cannot downgrade the finished record") {
            val store = InMemoryReplayStore()
            val finished = replay("g-done", listOf("alice" to "Alice", "bob" to "Bob"), "2026-01-01T00:00:00Z")
                .copy(winnerName = "Alice")
            store.save(StoredReplay(finished, ReplayStatus.FINISHED, presentation = "FRAMES"))
            val service = ReplayService(store, mockk(relaxed = true), mockk(relaxed = true))

            // The sweep read this session as live before the game-over path stored the final record.
            service.saveInProgress(finished.copy(winnerName = null, endedAt = ""), resumeFingerprint = "abc")

            val stored = (store.find("g-done").shouldNotBeNull() as ReplayRead.Decoded).stored
            stored.status shouldBe ReplayStatus.FINISHED
            stored.presentation shouldBe "FRAMES"
            stored.replay.winnerName shouldBe "Alice"
            // ...and it is still the player's to watch, which an IN_PROGRESS row would not be.
            store.findRecentForPlayer("alice", 10).map { it.gameId } shouldContainExactly listOf("g-done")
        }

        test("an in-progress record stranded by a crash is finalized on the first sweep") {
            val store = InMemoryReplayStore()
            store.save(
                StoredReplay(
                    replay("g-stranded", listOf("alice" to "Alice", "bob" to "Bob"), ""),
                    ReplayStatus.IN_PROGRESS,
                    resumeFingerprint = "abc",
                )
            )
            val service = ReplayService(store, mockk(relaxed = true), mockk(relaxed = true))
            // No live session came back for it: recovery has already run, so nothing ever will.
            val games = mockk<GameRepository>(relaxed = true) { every { findAll() } returns emptyList() }

            ReplayCheckpointFlusher(games, service, EngineVersion("test")).flush()

            (store.find("g-stranded").shouldNotBeNull() as ReplayRead.Decoded).stored.status shouldBe ReplayStatus.FINISHED
            store.findRecentForPlayer("alice", 10).map { it.gameId } shouldContainExactly listOf("g-stranded")
            store.findInProgress() shouldBe emptyList()
        }

        test("a flush snapshot's fingerprint is the position its own action log produces") {
            val session = playPartialGame()
            val (record, fingerprint) = session.flushRecord()

            // The resume gate compares this fingerprint against the state recovered after a restart,
            // so it is only a valid gate if it describes the position the stored log itself
            // reproduces. Sampling the log and the fingerprint separately breaks exactly this: the
            // game thread advances between the two reads, and a crash at the position the fingerprint
            // names then passes the gate over a hole in the log.
            val replayed = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(record, record.actions.size).shouldNotBeNull()
            ReplayFingerprint.of(replayed) shouldBe fingerprint
        }

        test("a yield-only mutation marks an in-progress replay flush dirty") {
            val session = playPartialGame()
            val store = InMemoryReplayStore()
            val service = ReplayService(store, mockk(relaxed = true), mockk(relaxed = true))
            val games = mockk<GameRepository>(relaxed = true) {
                every { findAll() } returns listOf(session)
            }
            val flusher = ReplayCheckpointFlusher(games, service, EngineVersion("test"))

            flusher.flush()
            val before = (store.find(session.sessionId) as ReplayRead.Decoded).stored.replay

            session.setAbilityYield(
                EntityId.of("resume-p1"),
                AbilityIdentity("Yield Test#TST-1", AbilityId("yield-test")),
                YieldKind.ALWAYS_ANSWER_YES,
            )
            flusher.flush()

            val after = (store.find(session.sessionId) as ReplayRead.Decoded).stored.replay
            after.yields.size shouldBe before.yields.size + 1
            after.checkpoints.count { it.afterActionCount == after.actions.size } shouldBe 1
        }

        test("a yield at a cadence checkpoint refreshes the checkpoint before replay persistence") {
            val session = playToActionCount(20)
            val before = session.getReplayCheckpoints().single { it.afterActionCount == 20 }.fingerprint

            session.setAbilityYield(
                EntityId.of("cadence-p1"),
                AbilityIdentity("Cadence Yield#TST-1", AbilityId("cadence-yield")),
                YieldKind.ALWAYS_ANSWER_YES,
            )
            val refreshed = session.getReplayCheckpoints().single { it.afterActionCount == 20 }.fingerprint
            refreshed shouldNotBe before

            var attempts = 0
            while (session.getRecordedActions().size < 21 && attempts++ < 20) {
                val state = session.getStateForTesting().shouldNotBeNull()
                state.priorityPlayerId?.let { session.executeAutoPass(it) }
            }
            val (record, _) = session.flushRecord()

            ReplayReconstructor(cardRegistry, null).reconstruct(record).fidelity shouldBe ReplayFidelity.EXACT
        }

        test("per-flush v3 tails do not accumulate in the live cadence checkpoint list") {
            val session = playPartialGame()
            val cadenceCounts = session.getReplayCheckpoints().map { it.afterActionCount }
            val first = session.flushRecord().first
            val second = session.flushRecord().first

            first.version shouldBe CompactReplay.CURRENT_VERSION
            second.version shouldBe CompactReplay.CURRENT_VERSION
            session.getReplayCheckpoints().map { it.afterActionCount } shouldBe cadenceCounts
            first.checkpoints.count { it.afterActionCount == first.actions.size } shouldBe 1
            second.checkpoints.count { it.afterActionCount == second.actions.size } shouldBe 1
        }

        test("a recording whose flush captured the live position resumes") {
            val session = playPartialGame()
            val (flushed, fingerprint) = session.flushRecord()
            val actionCount = flushed.actions.size

            // A restart: same recovered state, recording handed back from the store.
            val resumed = session.restoreReplayRecording(flushed, fingerprint)

            resumed shouldBe true
            session.getReplaySetup().shouldNotBeNull()
            session.getRecordedActions().size shouldBe actionCount
        }

        test("a recording that trails the recovered state stops rather than splicing onto a stale prefix") {
            val session = playPartialGame()
            val (stale, _) = session.flushRecord()

            // The flush landed, then a few more actions were played before the crash — so the
            // fingerprint stored with the flush describes an earlier position than the recovered one.
            val resumed = session.restoreReplayRecording(stale, expectedFingerprint = "0000000000000000")

            resumed shouldBe false
            // Recording is off, so nothing further is appended and the stored prefix stays honest.
            session.getReplaySetup() shouldBe null
            session.getReplayFrameCount() shouldBe 0
        }
    }
}
