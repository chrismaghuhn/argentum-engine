package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class B1RootMaterializationCharacterizationTest : FunSpec({
    test("reference writer matches canonical JSON bytes and digest for adversarial fixtures") {
        val cases = rootMaterializationFixtures()
        cases.forEach { (label, element) ->
            val current = currentCanonicalJson(element)
            val reference = B1CanonicalJsonReferenceWriter.canonicalJson(element)
            val currentBytes = current.toByteArray(StandardCharsets.UTF_8)
            val referenceBytes = reference.toByteArray(StandardCharsets.UTF_8)
            val expected = expectedFixture(label)

            current shouldBe expected.json
            current shouldBe reference
            currentBytes.size shouldBe expected.bytes
            currentBytes.contentEquals(referenceBytes) shouldBe true
            currentDigest(currentBytes) shouldBe expected.digest
            currentDigest(currentBytes) shouldBe B1CanonicalJsonReferenceWriter.canonicalDigest(element)
            println(
                "CHAR21_SYNTHETIC label=$label chars=${current.length} bytes=${currentBytes.size} " +
                    "digest=${currentDigest(currentBytes)} json=$current",
            )
        }
        println("CHAR21_SYNTHETIC_REFERENCE_CASES=${cases.size}")
    }

    test("reference writer performance is diagnostic only") {
        val cases = rootMaterializationFixtures()
        val warmupRounds = 20
        val measuredRounds = 100
        var checksum = 0L
        repeat(warmupRounds) {
            cases.forEach { (_, element) ->
                checksum += currentCanonicalJson(element).length.toLong()
                checksum += B1CanonicalJsonReferenceWriter.canonicalJson(element).length.toLong()
            }
        }

        val bean = (ManagementFactory.getThreadMXBean() as?
            com.sun.management.ThreadMXBean)?.takeIf {
            it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
        }
        val currentStart = System.nanoTime()
        val currentAllocatedStart = bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(measuredRounds) {
            cases.forEach { (_, element) -> checksum += currentCanonicalJson(element).length.toLong() }
        }
        val currentNanos = System.nanoTime() - currentStart
        val currentAllocated = currentAllocatedStart?.let {
            bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.minus(it)
        }

        val referenceStart = System.nanoTime()
        val referenceAllocatedStart = bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(measuredRounds) {
            cases.forEach { (_, element) ->
                checksum += B1CanonicalJsonReferenceWriter.canonicalJson(element).length.toLong()
            }
        }
        val referenceNanos = System.nanoTime() - referenceStart
        val referenceAllocated = referenceAllocatedStart?.let {
            bean?.getThreadAllocatedBytes(Thread.currentThread().threadId())?.minus(it)
        }

        val roots = (cases.size * measuredRounds).toDouble()
        println(
            "CHAR21_REFERENCE_PERF_DIAGNOSTIC current_ns_per_root=${currentNanos / roots} " +
                "reference_ns_per_root=${referenceNanos / roots} " +
                "current_alloc_per_root=${currentAllocated?.div(roots)} " +
                "reference_alloc_per_root=${referenceAllocated?.div(roots)} checksum=$checksum",
        )
        check(checksum > 0L)
    }
})

internal fun currentCanonicalJson(element: JsonElement): String =
    com.wingedsheep.gym.contract.ObservationCanonicalizer.canonicalJson(element)

internal fun currentDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal data class FixtureExpectation(
    val json: String,
    val bytes: Int,
    val digest: String,
)

internal fun expectedFixture(label: String): FixtureExpectation = when (label) {
    "empty-object" -> FixtureExpectation(
        json = "{}",
        bytes = 2,
        digest = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
    )
    "empty-array" -> FixtureExpectation(
        json = "[]",
        bytes = 2,
        digest = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
    )
    "unsorted-nested-object" -> FixtureExpectation(
        json = "{\"a\":[{\"a\":false,\"z\":true},{\"n\":3}],\"z\":{\"a\":1,\"b\":2}}",
        bytes = 54,
        digest = "f235230a42fb044e49febfa8e89e112b702e4635ae85391237fab5b158aec28b",
    )
    "ordered-and-unordered-arrays" -> FixtureExpectation(
        json = "{\"ordered\":[2,1],\"targetEntityIds\":[\"entity-1\",\"entity-9\"],\"types\":[\"a\",\"z\"]}",
        bytes = 77,
        digest = "89afc3ef820ac2cfb0bf14af737c99d33923f0ac09c57e316ad4fb371201b4ba",
    )
    "escaped-and-unicode-strings" -> FixtureExpectation(
        json = "{\"emoji😀\":\"supplementary😀\",\"text\":\"quote\\\" slash\\\\ newline\\n tab\\t control\\u0001 é Ω 😀\"}",
        bytes = 99,
        digest = "d70f61d751958fc2d4c47ee07e27ea45af6325f4984ec5f5a0038fd1a2409875",
    )
    "primitive-rendering" -> FixtureExpectation(
        json = "{\"boolean\":true,\"negative\":-0.0,\"null\":null,\"number\":1.00}",
        bytes = 58,
        digest = "e13d2f83f081ec7507afb5bd8519167b3471f5c0dc5a2fba33cffe0e3bc6c9cc",
    )
    "adversarial-key-order" -> FixtureExpectation(
        json = "{\"0\":4,\"A\":2,\"Z\":8,\"_\":3,\"a\":6,\"z\":9,\"é\":1,\"Ω\":5,\"😀\":7}",
        bytes = 60,
        digest = "246f23b5a2ce11cb7d1bc3a5b0215b52d488c74d0ded030f9f8217454e16f1ec",
    )
    "deep-mixed-structure" -> FixtureExpectation(
        json = "{\"outer\":[{\"flag\":false,\"nested\":[{\"a\":\"one\",\"b\":\"two\"},null]}]}",
        bytes = 64,
        digest = "e15a683a822aa6862b3796b915980b7b059796af63821171d2212910041f766d",
    )
    else -> error("Unknown Characterization-21 fixture: $label")
}

internal fun rootMaterializationFixtures(): List<Pair<String, JsonElement>> = listOf(
    "empty-object" to buildJsonObject { },
    "empty-array" to buildJsonArray { },
    "unsorted-nested-object" to buildJsonObject {
        put("z", buildJsonObject {
            put("b", 2)
            put("a", 1)
        })
        put("a", buildJsonArray {
            add(buildJsonObject { put("z", true); put("a", false) })
            add(buildJsonObject { put("n", 3) })
        })
    },
    "ordered-and-unordered-arrays" to buildJsonObject {
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
    "escaped-and-unicode-strings" to buildJsonObject {
        put("text", "quote\" slash\\ newline\n tab\t control\u0001 é Ω 😀")
        put("emoji😀", "supplementary😀")
    },
    "primitive-rendering" to Json.parseToJsonElement(
        "{\"number\":1.00,\"boolean\":true,\"null\":null,\"negative\":-0.0}",
    ),
    "adversarial-key-order" to buildJsonObject {
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
    "deep-mixed-structure" to buildJsonObject {
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
)
