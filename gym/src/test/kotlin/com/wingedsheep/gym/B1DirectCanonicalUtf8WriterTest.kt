package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class B1DirectCanonicalUtf8WriterTest : FunSpec({
    test("direct writer matches every Characterization-21 synthetic byte and digest fixture") {
        val writer = B1DirectCanonicalUtf8Writer()
        rootMaterializationFixtures().forEach { (label, element) ->
            val current = currentCanonicalJson(element).toByteArray(StandardCharsets.UTF_8)
            val candidate = writer.writeToBuffer(element)
            val candidateBytes = candidate.bytes.copyOf(candidate.size)

            candidate.size shouldBe current.size
            candidateBytes.contentEquals(current) shouldBe true
            currentDigest(candidateBytes) shouldBe currentDigest(current)

            val directDigest = MessageDigest.getInstance("SHA-256")
            writer.writeToDigest(element, directDigest) shouldBe current.size
            digestHex(directDigest.digest()) shouldBe currentDigest(current)
            expectedFixture(label).digest shouldBe currentDigest(current)
        }
    }

    test("direct writer output and digest repeat for identical roots") {
        val writer = B1DirectCanonicalUtf8Writer()
        rootMaterializationFixtures().forEach { (_, element) ->
            val first = writer.writeToBuffer(element)
            val firstBytes = first.bytes.copyOf(first.size)
            val firstDigest = digestHex(MessageDigest.getInstance("SHA-256").digest(firstBytes))
            repeat(3) {
                val repeated = writer.writeToBuffer(element)
                repeated.size shouldBe first.size
                repeated.bytes.copyOf(repeated.size).contentEquals(firstBytes) shouldBe true
                digestHex(MessageDigest.getInstance("SHA-256").digest(repeated.bytes.copyOf(repeated.size))) shouldBe
                    firstDigest
            }
        }
    }
})

private fun digestHex(bytes: ByteArray): String = bytes.joinToString("") { value ->
    "%02x".format(value)
}
