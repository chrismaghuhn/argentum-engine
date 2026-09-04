package com.wingedsheep.gym.contract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReplayVerificationBindingV1Test : FunSpec({

    fun identity(
        version: Int = REPLAY_CONTENT_IDENTITY_V1_VERSION,
        schemaIdentity: String = REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY,
        replayVersion: Int = 5,
        value: String = "a".repeat(64),
    ) = ReplayContentIdentityV1(
        version = version,
        schemaIdentity = schemaIdentity,
        replayVersion = replayVersion,
        value = value,
    )

    fun verification(replayVersion: Int = 5) = VerifiedReplayVerification(
        replayVersion = replayVersion,
        replayActionCount = 0,
        verifiedActionCount = 0,
        fidelity = ReplayFidelity.UNVERIFIED,
    )

    test("replay content identity is a versioned lowercase SHA-256 value") {
        val value = identity()

        value.version shouldBe REPLAY_CONTENT_IDENTITY_V1_VERSION
        value.schemaIdentity shouldBe REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY
        value.replayVersion shouldBe 5
        value.value shouldBe "a".repeat(64)
    }

    test("unknown identity versions, schemas, replay versions, and digest shapes fail closed") {
        shouldThrow<IllegalArgumentException> { identity(version = 2) }
        shouldThrow<IllegalArgumentException> { identity(schemaIdentity = "future-replay-content@v2") }
        shouldThrow<IllegalArgumentException> { identity(replayVersion = 0) }
        shouldThrow<IllegalArgumentException> { identity(value = "A".repeat(64)) }
        shouldThrow<IllegalArgumentException> { identity(value = "a".repeat(63)) }
    }

    test("verification binding requires identity and verification replay-version parity") {
        val binding = ReplayVerificationBindingV1(
            replayContentIdentity = identity(),
            verification = verification(),
        )

        binding.replayContentIdentity.replayVersion shouldBe binding.verification.replayVersion

        shouldThrow<IllegalArgumentException> {
            ReplayVerificationBindingV1(
                replayContentIdentity = identity(replayVersion = 4),
                verification = verification(replayVersion = 5),
            )
        }
        shouldThrow<IllegalArgumentException> { binding.copy(version = 2) }
        shouldThrow<IllegalArgumentException> { binding.copy(schemaIdentity = "future-binding@v2") }
    }

    test("binding and existing verification evidence round-trip through strict serialization") {
        val binding = ReplayVerificationBindingV1(
            replayContentIdentity = identity(),
            verification = verification(),
        )

        val encoded = Json.encodeToString(ReplayVerificationBindingV1.serializer(), binding)
        val decoded = Json.decodeFromString(ReplayVerificationBindingV1.serializer(), encoded)

        decoded shouldBe binding
    }
})
