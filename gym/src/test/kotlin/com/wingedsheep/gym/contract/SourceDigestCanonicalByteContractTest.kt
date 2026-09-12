package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SourceDigestCanonicalByteContractTest : FunSpec({
    test("locks current canonical JSON bytes and digests for adversarial fixtures") {
        sourceDigestCanonicalFixtures().forEach { (label, element, expected) ->
            val json = ObservationCanonicalizer.canonicalJson(element)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)

            json shouldBe expected.json
            bytes.size shouldBe expected.bytes
            sha256Hex(bytes) shouldBe expected.digest
        }
    }

    test("locks current UTF-8 rendering for malformed surrogate source text") {
        malformedSurrogateFixtures().forEach { fixture ->
            val actualJson = ObservationCanonicalizer.canonicalJson(
                buildJsonObject { put("value", fixture.raw) },
            )
            check(actualJson == fixture.expectedJson) {
                "rawCodeUnits=${fixture.raw.map { it.code }} " +
                    "expectedCodeUnits=${fixture.expectedJson.map { it.code }} " +
                    "actualCodeUnits=${actualJson.map { it.code }}"
            }
            actualJson.toByteArray(StandardCharsets.UTF_8).contentEquals(
                fixture.expectedUtf8.toByteArray(StandardCharsets.UTF_8),
            ) shouldBe true
        }
    }

    test("locks the legacy source digest oracle for a real perspective-safe observation") {
        val observation = realObservation()
        StateDigest.compute(observation) shouldBe legacySourceDigest(observation)
    }
})

internal fun legacySourceDigest(observation: TrainingObservation): String = sha256Hex(
    ObservationCanonicalizer.semanticJson(observation).toByteArray(StandardCharsets.UTF_8),
)

private data class CanonicalFixture(
    val json: String,
    val bytes: Int,
    val digest: String,
)

private fun sourceDigestCanonicalFixtures(): List<Triple<String, JsonElement, CanonicalFixture>> = listOf(
    Triple(
        "empty-object",
        buildJsonObject { },
        CanonicalFixture(
            "{}",
            2,
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
        ),
    ),
    Triple(
        "empty-array",
        buildJsonArray { },
        CanonicalFixture(
            "[]",
            2,
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
        ),
    ),
    Triple(
        "unsorted-nested-object",
        buildJsonObject {
            put("z", buildJsonObject {
                put("b", 2)
                put("a", 1)
            })
            put("a", buildJsonArray {
                add(buildJsonObject { put("z", true); put("a", false) })
                add(buildJsonObject { put("n", 3) })
            })
        },
        CanonicalFixture(
            "{\"a\":[{\"a\":false,\"z\":true},{\"n\":3}],\"z\":{\"a\":1,\"b\":2}}",
            54,
            "f235230a42fb044e49febfa8e89e112b702e4635ae85391237fab5b158aec28b",
        ),
    ),
    Triple(
        "ordered-and-unordered-arrays",
        buildJsonObject {
            put("types", buildJsonArray {
                add(JsonPrimitive("z"))
                add(JsonPrimitive("a"))
            })
            put("ordered", buildJsonArray {
                add(JsonPrimitive(2))
                add(JsonPrimitive(1))
            })
            put("targetEntityIds", buildJsonArray {
                add(JsonPrimitive("entity-9"))
                add(JsonPrimitive("entity-1"))
            })
        },
        CanonicalFixture(
            "{\"ordered\":[2,1],\"targetEntityIds\":[\"entity-1\",\"entity-9\"],\"types\":[\"a\",\"z\"]}",
            77,
            "89afc3ef820ac2cfb0bf14af737c99d33923f0ac09c57e316ad4fb371201b4ba",
        ),
    ),
    Triple(
        "escaped-and-unicode-strings",
        buildJsonObject {
            put("text", "quote\" slash\\ newline\n tab\t control\u0001 é Ω 😀")
            put("emoji😀", "supplementary😀")
        },
        CanonicalFixture(
            "{\"emoji😀\":\"supplementary😀\",\"text\":\"quote\\\" slash\\\\ newline\\n tab\\t control\\u0001 é Ω 😀\"}",
            99,
            "d70f61d751958fc2d4c47ee07e27ea45af6325f4984ec5f5a0038fd1a2409875",
        ),
    ),
    Triple(
        "primitive-rendering",
        Json.parseToJsonElement("{\"number\":1.00,\"boolean\":true,\"null\":null,\"negative\":-0.0}"),
        CanonicalFixture(
            "{\"boolean\":true,\"negative\":-0.0,\"null\":null,\"number\":1.00}",
            58,
            "e13d2f83f081ec7507afb5bd8519167b3471f5c0dc5a2fba33cffe0e3bc6c9cc",
        ),
    ),
    Triple(
        "adversarial-key-order",
        buildJsonObject {
            put("é", 1)
            put("A", 2)
            put("_", 3)
            put("0", 4)
            put("Ω", 5)
            put("a", 6)
            put("😀", 7)
            put("Z", 8)
            put("z", 9)
        },
        CanonicalFixture(
            "{\"0\":4,\"A\":2,\"Z\":8,\"_\":3,\"a\":6,\"z\":9,\"é\":1,\"Ω\":5,\"😀\":7}",
            60,
            "246f23b5a2ce11cb7d1bc3a5b0215b52d488c74d0ded030f9f8217454e16f1ec",
        ),
    ),
    Triple(
        "deep-mixed-structure",
        buildJsonObject {
            put("outer", buildJsonArray {
                add(buildJsonObject {
                    put("nested", buildJsonArray {
                        add(buildJsonObject { put("b", "two"); put("a", "one") })
                        add(JsonNull)
                    })
                    put("flag", false)
                })
            })
        },
        CanonicalFixture(
            "{\"outer\":[{\"flag\":false,\"nested\":[{\"a\":\"one\",\"b\":\"two\"},null]}]}",
            64,
            "e15a683a822aa6862b3796b915980b7b059796af63821171d2212910041f766d",
        ),
    ),
)

private data class MalformedSurrogateFixture(
    val raw: String,
    val expectedJson: String,
    val expectedUtf8: String,
)

private fun malformedSurrogateFixtures(): List<MalformedSurrogateFixture> = listOf(
    MalformedSurrogateFixture("\uD800", "{\"value\":\"\uD800\"}", "{\"value\":\"?\"}"),
    MalformedSurrogateFixture("\uDC00", "{\"value\":\"\uDC00\"}", "{\"value\":\"?\"}"),
    MalformedSurrogateFixture("\uD800x", "{\"value\":\"\uD800x\"}", "{\"value\":\"?x\"}"),
)

private fun realObservation(): TrainingObservation {
    val registry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }
    val environment = GameEnvironment.create(registry)
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                PlayerConfig("Bob", Deck.of("Mountain" to 20)),
            ),
            skipMulligans = true,
            startingPlayerIndex = 0,
        ),
    )
    return ObservationBuilder(cardRegistry = registry).build(
        environment.state,
        environment.playerIds[0],
        environment.legalActions(),
    ).observation as TrainingObservation
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
