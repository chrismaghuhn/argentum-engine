package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString

class AttackDeclarationReplayWireAuditTest : FunSpec({
    test("CompactReplay v4 carries GameAction choices but no Gym observation contract") {
        CompactReplay.CURRENT_VERSION shouldBe 4
        val replay = replay()
        val json = persistenceJson.encodeToString(CompactReplay.serializer(), replay)

        listOf("setup", "actions", "yields", "pinnedCards", "checkpoints").forEach { field ->
            json shouldContain "\"$field\":"
        }
        json shouldContain "DeclareAttackers"
        json shouldContain "\"attackers\":"
        json shouldContain "\"bands\":"

        listOf(
            "LegalActionView",
            "AttackDeclarationDomainV1",
            "attackDeclarationDomain",
            "schemaHash",
            "targetDomain",
            "requiredPayloadFields",
            "paymentDomain",
        ).forEach { forbidden ->
            json shouldNotContain forbidden
        }

        ReplayCodec.decode(ReplayCodec.encode(replay)).actions.single() shouldBe replay.actions.single()
    }

    test("reconstruction uses setup and GameAction inputs without Gym observation data") {
        val registry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val replay = replay().copy(
            actions = emptyList(),
            pinnedCards = emptyList(),
            checkpoints = emptyList(),
        )

        val reconstructed = ReplayReconstructor(registry, null).reconstruct(replay)

        reconstructed.initialSnapshot shouldNotBe null
        reconstructed.deltas.shouldBeEmpty()
    }
})

private fun replay(): CompactReplay = CompactReplay(
    gameId = "attack-domain-audit",
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
    actions = listOf(
        DeclareAttackers(
            playerId = EntityId("p1"),
            attackers = mapOf(EntityId("attacker") to EntityId("p2")),
            bands = listOf(setOf(EntityId("attacker"), EntityId("attacker-two"))),
        )
    ),
    yields = listOf(
        ReplayYieldEntry(
            afterActionCount = 1,
            playerId = "p1",
            op = ReplayYieldOp.CLEAR_ALL,
        )
    ),
    pinnedCards = listOf("pinned-card-definition"),
    checkpoints = listOf(ReplayCheckpoint(afterActionCount = 0, fingerprint = "fingerprint")),
)
