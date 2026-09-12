package com.wingedsheep.gym

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test-only reference implementation of the current ObservationCanonicalizer tree semantics.
 * It deliberately delegates primitive rendering, including string escaping and number spelling,
 * to kotlinx.serialization's existing JsonPrimitive authority.
 */
internal object B1CanonicalJsonReferenceWriter {
    internal val unorderedArrayKeys = setOf(
        "types",
        "subtypes",
        "colors",
        "keywords",
        "availableColors",
        "attachments",
        "targetEntityIds",
        "validSacrificeTargets",
        "candidates",
        "nonSelectableOptions",
        "matchingOptions",
        "availableSources",
        "waterbendPermanents",
        "producesColors",
        "sourceSubtypes",
        "sourceBuckets",
        "sourceColorBuckets",
        "certifiedFloatingBuckets",
        "blockedByIds",
        "blockedAttackerIds",
    )

    internal fun canonicalJson(element: JsonElement): String = buildString {
        write(element, this)
    }

    /** Test-only direct digest experiment; production does not call this writer. */
    internal fun canonicalDigest(element: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
        write(element, DigestingAppendable(digest))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun write(element: JsonElement, out: Appendable, propertyName: String? = null) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    out.append(JsonPrimitive(key).toString())
                    out.append(':')
                    write(value, out, key)
                }
                out.append('}')
            }

            is JsonArray -> {
                val values = element.map { child ->
                    if (propertyName in unorderedArrayKeys) {
                        canonicalJson(child)
                    } else {
                        null
                    }
                }
                out.append('[')
                if (propertyName in unorderedArrayKeys) {
                    values.filterNotNull().sorted().forEachIndexed { index, value ->
                        if (index > 0) out.append(',')
                        out.append(value)
                    }
                } else {
                    element.forEachIndexed { index, child ->
                        if (index > 0) out.append(',')
                        write(child, out)
                    }
                }
                out.append(']')
            }

            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(element.toString())
        }
    }

    private class DigestingAppendable(
        private val digest: MessageDigest,
    ) : Appendable {
        override fun append(c: Char): Appendable {
            append(c.toString())
            return this
        }

        override fun append(csq: CharSequence?): Appendable {
            val text = csq?.toString() ?: "null"
            digest.update(text.toByteArray(StandardCharsets.UTF_8))
            return this
        }

        override fun append(csq: CharSequence?, start: Int, end: Int): Appendable {
            val text = csq?.subSequence(start, end)?.toString() ?: "null"
            digest.update(text.toByteArray(StandardCharsets.UTF_8))
            return this
        }
    }
}
