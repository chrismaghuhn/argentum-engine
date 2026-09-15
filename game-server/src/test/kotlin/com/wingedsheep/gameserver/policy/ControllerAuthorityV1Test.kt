package com.wingedsheep.gameserver.policy

import com.wingedsheep.gameserver.persistence.persistenceJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalStdlibApi::class)
class ControllerAuthorityV1Test : FunSpec({
    test("V1 controller kinds are explicit and preserve their server-owned shape") {
        ControllerKindV1.entries.map { it.name } shouldBe listOf("HUMAN", "ENGINE_AI", "ML_POLICY", "LEGACY_AI")

        ControllerAuthorityV1.human(0).controllerKind shouldBe ControllerKindV1.HUMAN
        ControllerAuthorityV1.engineAi(1).controllerKind shouldBe ControllerKindV1.ENGINE_AI
        ControllerAuthorityV1.legacyAi(2).controllerKind shouldBe ControllerKindV1.LEGACY_AI

        val authority = ControllerAuthorityV1.mlPolicy(seatIndex = 0, policySeed = 4_259_905L)
        authority.controllerKind shouldBe ControllerKindV1.ML_POLICY
        authority.policyProfileIdentity shouldBe C1_07B_POLICY_PROFILE_IDENTITY
        authority.checkpointId shouldBe C1_07B_CHECKPOINT_IDENTITY
        authority.policySeed shouldBe 4_259_905L
        persistenceJson.encodeToString(ControllerAuthorityV1.serializer(), authority) shouldNotContain "socket"
        persistenceJson.encodeToString(ControllerAuthorityV1.serializer(), authority) shouldNotContain "worker"
        persistenceJson.encodeToString(ControllerAuthorityV1.serializer(), authority) shouldNotContain "path"
    }

    test("unknown controller authority versions fail closed during persistence decoding") {
        val encoded = persistenceJson
            .encodeToJsonElement(ControllerAuthorityV1.serializer(), ControllerAuthorityV1.human(0))
            .jsonObject
            .toMutableMap()
        encoded["version"] = JsonPrimitive(2)

        shouldThrow<IllegalArgumentException> {
            persistenceJson.decodeFromJsonElement(ControllerAuthorityV1.serializer(), JsonObject(encoded))
        }
    }

    test("ML authority rejects caller-defined profile and checkpoint identities") {
        shouldThrow<IllegalArgumentException> {
            ControllerAuthorityV1(
                seatIndex = 0,
                controllerKind = ControllerKindV1.ML_POLICY,
                policyProfileIdentity = "caller-profile@v1",
                checkpointId = C1_07B_CHECKPOINT_IDENTITY,
                inferenceContractIdentity = C1_07B_INFERENCE_CONTRACT_IDENTITY,
                selectionContractIdentity = C1_07B_SELECTION_CONTRACT_IDENTITY,
                policyRngContractIdentity = C1_07B_POLICY_RNG_CONTRACT_IDENTITY,
                policySeed = 1L,
            )
        }
    }

    test("PolicySeatStateV1 uses the accepted PolicyTieRng stream derivation") {
        val authority = ControllerAuthorityV1.mlPolicy(seatIndex = 0, policySeed = 4_259_905L)
        val state = PolicySeatStateV1.fromAuthority(authority)

        state.streamKeyHex shouldBe "e524b34e031fbdc4f616f6725de4335c95207edadbcbfbd09bdb91952152e8d1"
        state.cursor shouldBe 0uL
        state.toLiveState().streamKeyHex shouldBe state.streamKeyHex
        state.toLiveState().cursor shouldBe 0uL
    }

    test("persisted policy state contains only stream and cursor and restores by value") {
        val authority = ControllerAuthorityV1.mlPolicy(seatIndex = 1, policySeed = -1L)
        val original = PolicySeatStateV1.fromAuthority(authority).copy(cursor = 7uL)
        val encoded = persistenceJson.encodeToString(PolicySeatStateV1.serializer(), original)
        val restored = persistenceJson.decodeFromString(PolicySeatStateV1.serializer(), encoded)

        restored shouldBe original
        encoded shouldNotContain "pid"
        encoded shouldNotContain "request"
        encoded shouldNotContain "path"
        encoded shouldNotContain "socket"
    }
})
