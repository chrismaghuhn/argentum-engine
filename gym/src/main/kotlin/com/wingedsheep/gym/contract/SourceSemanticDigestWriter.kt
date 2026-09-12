package com.wingedsheep.gym.contract

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Direct canonical UTF-8 digest writer for source observations only. */
internal object SourceSemanticDigestWriter {
    private const val DIGEST_BUFFER_SIZE = 16 * 1024
    private val hexChars = "0123456789abcdef".toCharArray()
    private val scratch = ThreadLocal.withInitial { Scratch() }

    internal fun digest(root: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DigestSink(digest, scratch.get().bytes)
        writeCanonical(root, sink)
        sink.finish()
        return hex(digest.digest())
    }

    /** Test-only byte sink seam; production source code uses [digest] only. */
    internal fun writeCanonical(root: JsonElement, sink: CanonicalUtf8Sink) {
        write(root, sink)
    }

    internal interface CanonicalUtf8Sink {
        fun appendAscii(value: String)

        fun appendJsonText(value: String)
    }

    private fun write(element: JsonElement, sink: CanonicalUtf8Sink, propertyName: String? = null) {
        when (element) {
            is JsonObject -> {
                sink.appendAscii("{")
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) sink.appendAscii(",")
                    sink.appendJsonText(JsonPrimitive(key).toString())
                    sink.appendAscii(":")
                    write(value, sink, key)
                }
                sink.appendAscii("}")
            }

            is JsonArray -> {
                sink.appendAscii("[")
                if (propertyName in ObservationCanonicalizer.unorderedArrayKeys) {
                    element
                        .map { child -> canonicalSortKey(child) to child }
                        .sortedBy { (sortKey, _) -> sortKey }
                        .forEachIndexed { index, (_, child) ->
                            if (index > 0) sink.appendAscii(",")
                            write(child, sink)
                        }
                } else {
                    element.forEachIndexed { index, child ->
                        if (index > 0) sink.appendAscii(",")
                        write(child, sink)
                    }
                }
                sink.appendAscii("]")
            }

            is JsonNull -> sink.appendAscii("null")
            is JsonPrimitive -> sink.appendJsonText(element.toString())
        }
    }

    private fun canonicalSortKey(element: JsonElement): String = buildString {
        val sink = StringSink(this)
        write(element, sink)
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append(hexChars[unsigned ushr 4])
            append(hexChars[unsigned and 0x0f])
        }
    }

    private class Scratch {
        val bytes = ByteArray(DIGEST_BUFFER_SIZE)
    }

    private class DigestSink(
        private val digest: MessageDigest,
        private val bytes: ByteArray,
    ) : CanonicalUtf8Sink {
        private var size = 0

        override fun appendAscii(value: String) {
            value.forEach { byte(it.code) }
        }

        override fun appendJsonText(value: String) {
            var index = 0
            while (index < value.length) {
                val first = value[index].code
                when {
                    first <= 0x7f -> {
                        byte(first)
                        index++
                    }

                    first <= 0x7ff -> {
                        byte(0xc0 or (first shr 6))
                        byte(0x80 or (first and 0x3f))
                        index++
                    }

                    first in 0xd800..0xdbff && index + 1 < value.length -> {
                        val second = value[index + 1].code
                        if (second in 0xdc00..0xdfff) {
                            val codePoint = 0x10000 + ((first - 0xd800) shl 10) + (second - 0xdc00)
                            byte(0xf0 or (codePoint shr 18))
                            byte(0x80 or ((codePoint shr 12) and 0x3f))
                            byte(0x80 or ((codePoint shr 6) and 0x3f))
                            byte(0x80 or (codePoint and 0x3f))
                            index += 2
                        } else {
                            byte(0x3f)
                            index++
                        }
                    }

                    first in 0xdc00..0xdfff -> {
                        byte(0x3f)
                        index++
                    }

                    else -> {
                        byte(0xe0 or (first shr 12))
                        byte(0x80 or ((first shr 6) and 0x3f))
                        byte(0x80 or (first and 0x3f))
                        index++
                    }
                }
            }
        }

        fun finish() {
            flush()
        }

        private fun byte(value: Int) {
            bytes[size++] = value.toByte()
            if (size == bytes.size) flush()
        }

        private fun flush() {
            if (size == 0) return
            digest.update(bytes, 0, size)
            size = 0
        }
    }

    private class StringSink(
        private val builder: StringBuilder,
    ) : CanonicalUtf8Sink {
        override fun appendAscii(value: String) {
            builder.append(value)
        }

        override fun appendJsonText(value: String) {
            builder.append(value)
        }
    }
}
