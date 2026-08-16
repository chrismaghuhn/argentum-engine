package com.wingedsheep.gameserver.replay

import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class ReplayTailCheckpointTest : ScenarioTestBase() {

    private fun replay(checkpoints: List<ReplayCheckpoint> = emptyList()) = CompactReplay(
        version = 3,
        gameId = "tail-test",
        players = listOf(
            ReplayPlayerInfo("p1", "Alice"),
            ReplayPlayerInfo("p2", "Bob"),
        ),
        startedAt = Instant.parse("2026-01-01T00:00:00Z").toString(),
        endedAt = Instant.parse("2026-01-01T00:01:00Z").toString(),
        winnerName = null,
        setup = ReplaySetup(
            seed = 7L,
            format = Format.Standard,
            attackMode = AttackMode.MULTIPLE,
            startingHandSize = 0,
            skipMulligans = true,
            players = listOf(
                ReplayPlayerSetup("p1", "Alice", Deck(cards = listOf("Forest"))),
                ReplayPlayerSetup("p2", "Bob", Deck(cards = listOf("Forest"))),
            ),
            seatRoster = emptyList(),
        ),
        actions = emptyList(),
        checkpoints = checkpoints,
    )

    init {
        test("v3 tail policy materializes 20, 40, 43 without mutating cadence input") {
            val cadence = listOf(
                ReplayCheckpoint(20, "f20"),
                ReplayCheckpoint(40, "f40"),
            )

            val persisted = ReplayCheckpointPolicy.withV3Tail(cadence, 43, "f43")

            cadence.map { it.afterActionCount } shouldContainExactly listOf(20, 40)
            persisted.map { it.afterActionCount } shouldContainExactly listOf(20, 40, 43)
            ReplayCheckpointPolicy.withV3Tail(persisted, 43, "f43-new")
                .map { it.afterActionCount } shouldContainExactly listOf(20, 40, 43)
        }

        test("a zero-action v3 replay is exact only with a matching tail at zero") {
            val reconstructor = ReplayReconstructor(cardRegistry, null)
            val base = replay()
            val initial = reconstructor.reconstructStateAt(base, 0).shouldNotBeNull()
            val exact = base.copy(
                checkpoints = listOf(ReplayCheckpoint(0, ReplayFingerprint.of(initial, 3)))
            )

            reconstructor.reconstruct(exact).fidelity shouldBe ReplayFidelity.EXACT
            reconstructor.reconstruct(base).fidelity shouldBe ReplayFidelity.UNVERIFIED
            reconstructor.reconstruct(base).divergenceReason.shouldNotBeNull()
                .shouldContain("tail")
        }

        test("a mismatching v3 tail is divergence, not unverified success") {
            val reconstructor = ReplayReconstructor(cardRegistry, null)
            val base = replay()
            val mismatch = base.copy(checkpoints = listOf(ReplayCheckpoint(0, "0".repeat(64))))

            reconstructor.reconstruct(mismatch).fidelity shouldBe ReplayFidelity.DIVERGED
        }
    }
}
