package com.wingedsheep.gym.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SourceDigestDirectWriterThreadSafetyTest : FunSpec({
    test("concurrent source digests preserve bytes and digests per root") {
        val roots = listOf(
            buildJsonObject {
                put("z", "Ω")
                put("a", buildJsonArray {
                    add(JsonPrimitive("😀"))
                    add(JsonPrimitive("alpha"))
                })
            },
            buildJsonObject {
                put("types", buildJsonArray {
                    add(JsonPrimitive("z"))
                    add(JsonPrimitive("a"))
                })
                put("value", "\uD800x")
            },
            buildJsonObject {
                put("nested", buildJsonObject {
                    put("number", JsonPrimitive(1))
                    put("null", kotlinx.serialization.json.JsonNull)
                })
            },
        )
        val expected = roots.map { root ->
            val bytes = ObservationCanonicalizer.canonicalJson(root).toByteArray(StandardCharsets.UTF_8)
            ExpectedRoot(bytes, sha256Hex(bytes))
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 128).map { index ->
                Callable {
                    val rootIndex = index % roots.size
                    val root = roots[rootIndex]
                    val bytes = directCanonicalBytes(root)
                    val digest = SourceSemanticDigestWriter.digest(root)
                    rootIndex to ExpectedRoot(bytes, digest)
                }
            }
            executor.invokeAll(tasks).forEach { future ->
                val (rootIndex, actual) = future.get()
                actual.bytes.contentEquals(expected[rootIndex].bytes) shouldBe true
                actual.digest shouldBe expected[rootIndex].digest
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
        }
    }
})

private data class ExpectedRoot(
    val bytes: ByteArray,
    val digest: String,
)

private fun directCanonicalBytes(element: JsonElement): ByteArray = ByteArrayOutputStream().also { output ->
    SourceSemanticDigestWriter.writeCanonical(
        element,
        object : SourceSemanticDigestWriter.CanonicalUtf8Sink {
            override fun appendAscii(value: String) {
                output.write(value.toByteArray(StandardCharsets.UTF_8))
            }

            override fun appendJsonText(value: String) {
                output.write(value.toByteArray(StandardCharsets.UTF_8))
            }
        },
    )
}.toByteArray()

private fun sha256Hex(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
