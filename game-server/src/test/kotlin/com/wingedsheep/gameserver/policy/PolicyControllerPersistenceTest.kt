package com.wingedsheep.gameserver.policy

import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.gameserver.persistence.restoreGameSession
import com.wingedsheep.gameserver.persistence.toPersistent
import com.wingedsheep.gameserver.persistence.dto.PersistentGameSession
import com.wingedsheep.gameserver.persistence.dto.PersistentPlayerInfo
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.config.MlPolicyProperties
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gym.contract.LivePolicyDecisionResponseV1
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.web.socket.WebSocketSession

class PolicyControllerPersistenceTest : FunSpec({
    test("GameSession persistence exports its explicit ML authority and current RNG state") {
        val playerId = EntityId("persisted-policy-seat")
        val session = GameSession(cardRegistry = CardRegistry())
        session.addPlayer(
            PlayerSession(ws("policy"), playerId, "Policy"),
            mapOf("Forest" to 40),
        )
        session.setControllerAuthority(playerId, ControllerAuthorityV1.mlPolicy(0, 42L))
        session.setPlayerPersistenceInfo(playerId, "Policy", "policy-token")

        val playerInfo = session.toPersistent(null).playerInfos.single()

        playerInfo.controllerAuthority shouldBe ControllerAuthorityV1.mlPolicy(0, 42L)
        playerInfo.policySeatState shouldBe PolicySeatStateV1.fromAuthority(
            ControllerAuthorityV1.mlPolicy(0, 42L),
        )
    }

    test("controller authority and PolicyTieRng state survive session persistence and rehydration") {
        val playerId = EntityId("persisted-policy-seat")
        val authority = ControllerAuthorityV1.mlPolicy(seatIndex = 0, policySeed = 42L)
        val state = PolicySeatStateV1.fromAuthority(authority).copy(cursor = 9uL)
        val persistent = PersistentGameSession(
            sessionId = "session-1",
            gameState = null,
            deckLists = emptyMap(),
            lastProcessedMessageId = emptyMap(),
            gameLogs = emptyMap(),
            playerInfos = listOf(
                PersistentPlayerInfo(
                    playerId = playerId.value,
                    playerName = "Policy",
                    token = "policy-token",
                    controllerAuthority = authority,
                    policySeatState = state,
                ),
            ),
            lobbyId = null,
        )

        val encoded = persistenceJson.encodeToString(PersistentGameSession.serializer(), persistent)
        val restored = persistenceJson.decodeFromString(PersistentGameSession.serializer(), encoded)
        val (session, identities) = restoreGameSession(restored, CardRegistry())

        identities.single().playerId shouldBe playerId
        session.getControllerAuthority(playerId) shouldBe authority
        session.getPolicySeatState(playerId) shouldBe state
    }

    test("rehydrated policy authority can construct a fresh per-seat runtime") {
        val playerId = EntityId("rehydrated-policy-seat")
        val opponentId = EntityId("rehydrated-opponent")
        val authority = ControllerAuthorityV1.mlPolicy(seatIndex = 0, policySeed = 42L)
        val source = GameSession(sessionId = "session-runtime", cardRegistry = CardRegistry())
        source.addPlayer(PlayerSession(ws("policy"), playerId, "Policy"), mapOf("Forest" to 40))
        source.addPlayer(PlayerSession(ws("opponent"), opponentId, "Opponent"), mapOf("Island" to 40))
        source.setControllerAuthority(playerId, authority)
        source.setPlayerPersistenceInfo(playerId, "Policy", "policy-token")
        source.setPlayerPersistenceInfo(opponentId, "Opponent", "opponent-token")

        val (restored, _) = restoreGameSession(source.toPersistent(null), CardRegistry())
        val workerStarts = AtomicInteger(0)
        val manager = PolicySeatRuntimeManager(
            GameProperties(
                mlPolicy = MlPolicyProperties(
                    enabled = true,
                    checkpointDirectory = "C:/server-owned/checkpoint",
                ),
            ),
            PolicySeatWorkerFactory {
                workerStarts.incrementAndGet()
                object : PolicySeatWorker {
                    override fun decide(request: com.wingedsheep.gym.contract.LivePolicyDecisionRequestV1): LivePolicyDecisionResponseV1 =
                        error("rehydration construction test does not infer")

                    override fun close() = Unit
                }
            },
        )

        val runtime = manager.runtimeFor(restored, playerId)

        runtime.playerId shouldBe playerId
        restored.getControllerAuthority(playerId) shouldBe authority
        restored.getPolicySeatState(playerId) shouldBe PolicySeatStateV1.fromAuthority(authority)
        workerStarts.get() shouldBe 1
        manager.closeGame(restored.sessionId)
    }

    test("unknown future policy controller-state versions fail closed during session decoding") {
        val authority = ControllerAuthorityV1.mlPolicy(seatIndex = 0, policySeed = 42L)
        val state = PolicySeatStateV1.fromAuthority(authority)
        val persistent = PersistentGameSession(
            sessionId = "session-2",
            gameState = null,
            deckLists = emptyMap(),
            lastProcessedMessageId = emptyMap(),
            gameLogs = emptyMap(),
            playerInfos = listOf(
                PersistentPlayerInfo(
                    playerId = "policy-seat",
                    playerName = "Policy",
                    token = "policy-token",
                    controllerAuthority = authority,
                    policySeatState = state,
                ),
            ),
            lobbyId = null,
        )
        val root = persistenceJson
            .encodeToJsonElement(PersistentGameSession.serializer(), persistent)
            .jsonObject
            .toMutableMap()
        val player = root.getValue("playerInfos").jsonArray.single().jsonObject.toMutableMap()
        val controller = player.getValue("controllerAuthority").jsonObject.toMutableMap()
        controller["version"] = JsonPrimitive(2)
        player["controllerAuthority"] = JsonObject(controller)
        root["playerInfos"] = kotlinx.serialization.json.JsonArray(listOf(JsonObject(player)))

        shouldThrow<IllegalArgumentException> {
            persistenceJson.decodeFromJsonElement(
                PersistentGameSession.serializer(),
                JsonObject(root),
            )
        }
    }
})

private fun ws(id: String): WebSocketSession = mockk<WebSocketSession>(relaxed = true).also {
    every { it.id } returns id
}
