package io.github.jqssun.airplay.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parses DMAP (iTunes metadata) binary TLV format into a tag->value map. */
object DmapParser {

    private const val MAX_METADATA_BYTES = 1024 * 1024
    private const val MAX_CONTAINER_DEPTH = 32

    // tags whose payload is an integer (1/2/4/8 bytes)
    private val INT_TAGS = setOf(
        "astm", // duration ms
        "astn", // track number
        "asdk", // data kind
        "asts", // ???
        "miid", // item id
        "mcti", // container item id
        "mper", // persistent id
        "asai", // album id
        "asri", // artist id
        "asci", // composer id
        "asgi", // genre id
    )

    // tags that are containers (contain nested dmap items)
    private val CONTAINER_TAGS = setOf("mlit", "mcon", "mlcl")

    fun parse(data: ByteArray): Map<String, Any> {
        if (data.size > MAX_METADATA_BYTES) return emptyMap()
        return parse(ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN), depth = 0)
    }

    private fun parse(buf: ByteBuffer, depth: Int): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        items@ while (buf.remaining() >= 8) {
            val tagBytes = ByteArray(4)
            buf.get(tagBytes)
            val tag = String(tagBytes, Charsets.US_ASCII)
            val len = buf.int
            if (len < 0 || len > buf.remaining()) break
            // Share the bounded payload instead of copying it at every nesting level.
            val payload = buf.slice().order(ByteOrder.BIG_ENDIAN).apply { limit(len) }
            buf.position(buf.position() + len)
            result[tag] = when {
                tag in CONTAINER_TAGS -> {
                    if (depth >= MAX_CONTAINER_DEPTH) continue@items
                    parse(payload, depth + 1)
                }
                tag in INT_TAGS -> {
                    if (len != 1 && len != 2 && len != 4 && len != 8) continue@items
                    _readInt(payload)
                }
                else -> Charsets.UTF_8.decode(payload).toString()
            }
        }
        return result
    }

    private fun _readInt(b: ByteBuffer): Long {
        var v = 0L
        while (b.hasRemaining()) v = (v shl 8) or (b.get().toLong() and 0xFF)
        return v
    }
}
