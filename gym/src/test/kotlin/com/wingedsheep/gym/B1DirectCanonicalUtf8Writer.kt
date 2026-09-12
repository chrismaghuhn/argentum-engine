package com.wingedsheep.gym

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Test-only candidate for the source semantic digest path.
 *
 * It writes the already-produced semantic JsonElement directly as UTF-8. It deliberately keeps
 * primitive JSON rendering delegated to JsonPrimitive.toString(); only encoding of that already
 * rendered JSON text is implemented here. This object is never used by production code.
 */
internal class B1DirectCanonicalUtf8Writer(
    initialBufferCapacity: Int = 64 * 1024,
) {
    private val bufferSink = ResizableUtf8BufferSink(initialBufferCapacity)
    private val digestSink = BufferedDigestUtf8Sink()

    internal fun writeToBuffer(element: JsonElement): EncodedBuffer {
        bufferSink.reset()
        write(element, bufferSink)
        return EncodedBuffer(bufferSink.bytes, bufferSink.size)
    }

    internal fun writeToDigest(element: JsonElement, digest: MessageDigest): Int {
        digestSink.reset(digest)
        write(element, digestSink)
        digestSink.finish()
        return digestSink.size
    }

    private fun write(element: JsonElement, sink: Utf8Sink, propertyName: String? = null) {
        when (element) {
            is JsonObject -> {
                sink.writeAscii("{")
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) sink.writeAscii(",")
                    sink.writeText(JsonPrimitive(key).toString())
                    sink.writeAscii(":")
                    write(value, sink, key)
                }
                sink.writeAscii("}")
            }

            is JsonArray -> {
                sink.writeAscii("[")
                if (propertyName in B1CanonicalJsonReferenceWriter.unorderedArrayKeys) {
                    element
                        .map { child ->
                            B1CanonicalJsonReferenceWriter.canonicalJson(child) to child
                        }
                        .sortedBy { (sortKey, _) -> sortKey }
                        .forEachIndexed { index, (_, child) ->
                            if (index > 0) sink.writeAscii(",")
                            write(child, sink)
                        }
                } else {
                    element.forEachIndexed { index, child ->
                        if (index > 0) sink.writeAscii(",")
                        write(child, sink)
                    }
                }
                sink.writeAscii("]")
            }

            is JsonNull -> sink.writeAscii("null")
            is JsonPrimitive -> sink.writeText(element.toString())
        }
    }

    internal data class EncodedBuffer(
        val bytes: ByteArray,
        val size: Int,
    )

    private interface Utf8Sink {
        val size: Int

        fun writeByte(value: Int)

        fun writeAscii(value: String) = writeText(value)

        fun writeText(value: String) {
            var index = 0
            while (index < value.length) {
                val first = value[index].code
                when {
                    first <= 0x7f -> {
                        writeByte(first)
                        index++
                    }

                    first <= 0x7ff -> {
                        writeByte(0xc0 or (first shr 6))
                        writeByte(0x80 or (first and 0x3f))
                        index++
                    }

                    first in 0xd800..0xdbff && index + 1 < value.length -> {
                        val second = value[index + 1].code
                        if (second in 0xdc00..0xdfff) {
                            val codePoint = 0x10000 + ((first - 0xd800) shl 10) + (second - 0xdc00)
                            writeByte(0xf0 or (codePoint shr 18))
                            writeByte(0x80 or ((codePoint shr 12) and 0x3f))
                            writeByte(0x80 or ((codePoint shr 6) and 0x3f))
                            writeByte(0x80 or (codePoint and 0x3f))
                            index += 2
                        } else {
                            writeByte(0x3f)
                            index++
                        }
                    }

                    first in 0xdc00..0xdfff -> {
                        writeByte(0x3f)
                        index++
                    }

                    else -> {
                        writeByte(0xe0 or (first shr 12))
                        writeByte(0x80 or ((first shr 6) and 0x3f))
                        writeByte(0x80 or (first and 0x3f))
                        index++
                    }
                }
            }
        }
    }

    private class ResizableUtf8BufferSink(initialCapacity: Int) : Utf8Sink {
        var bytes = ByteArray(initialCapacity)
            private set
        override var size: Int = 0
            private set

        fun reset() {
            size = 0
        }

        override fun writeByte(value: Int) {
            ensureCapacity(size + 1)
            bytes[size++] = value.toByte()
        }

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var capacity = bytes.size.coerceAtLeast(1)
            while (capacity < required) capacity = capacity shl 1
            bytes = bytes.copyOf(capacity)
        }
    }

    private class BufferedDigestUtf8Sink : Utf8Sink {
        private val buffer = ByteArray(16 * 1024)
        private var digest: MessageDigest? = null
        private var buffered = 0
        private var total = 0

        override val size: Int
            get() = total

        fun reset(nextDigest: MessageDigest) {
            digest = nextDigest
            buffered = 0
            total = 0
        }

        override fun writeByte(value: Int) {
            buffer[buffered++] = value.toByte()
            total++
            if (buffered == buffer.size) flush()
        }

        fun finish() {
            flush()
            digest = null
        }

        private fun flush() {
            if (buffered == 0) return
            checkNotNull(digest).update(buffer, 0, buffered)
            buffered = 0
        }
    }
}
