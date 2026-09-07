package com.wingedsheep.rundiagnostics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import java.nio.charset.StandardCharsets.UTF_8

class RunStatusContractTest : FunSpec({
    test("rejects unsupported schema and stage versions") {
        shouldThrow<IllegalArgumentException> {
            sampleStatus().copy(schemaVersion = 2)
        }
        shouldThrow<IllegalArgumentException> {
            sampleStage().copy(schemaVersion = 2)
        }
        shouldThrow<IllegalArgumentException> {
            sampleStage().copy(stageFamilySchemaIdentity = "unversioned-stage-family")
        }
    }

    test("decodes only the declared status schema") {
        val encoded = RunStatusCodec.encode(sampleStatus()).toString(UTF_8)
        val withUnknownField = encoded.dropLast(1) + ",\"rawGameState\":{}}"

        shouldThrow<SerializationException> {
            RunStatusCodec.decode(withUnknownField.toByteArray(UTF_8))
        }
    }

    test("requires explicit schema identity fields when decoding") {
        val encoded = RunStatusCodec.encode(sampleStatus()).toString(UTF_8)
        val withoutSchemaVersion = encoded.replace("\"schemaVersion\":1,", "")

        shouldThrow<SerializationException> {
            RunStatusCodec.decode(withoutSchemaVersion.toByteArray(UTF_8))
        }
    }

    test("enforces a bounded serialized status") {
        val error = shouldThrow<StatusSerializationException> {
            RunStatusCodec.encode(sampleStatus(), maxBytes = 64)
        }

        error.code shouldBe StatusPublicationFailureCode.STATUS_SERIALIZATION_TOO_LARGE
    }

    test("keeps unavailable counters distinct from explicit zero") {
        sampleStatus().progress.engineProgressCount shouldBe null

        val recorder = newRecorder()
        recorder.recordUsefulProgress(engineProgressDelta = 0)

        recorder.snapshot()!!.progress.engineProgressCount shouldBe 0
        recorder.close()
    }

    test("normal status JSON contains no raw gameplay contract fields") {
        val json = RunStatusCodec.encode(sampleStatus()).toString(UTF_8).lowercase()

        listOf(
            "gamestate",
            "rawgamestate",
            "rawaction",
            "playerobservation",
            "completelegaldomain",
            "reward",
            "policyinput",
            "hiddenhand",
            "librarycontents",
        ).forEach { forbiddenField ->
            json.contains("\"$forbiddenField\"") shouldBe false
        }
    }
})
