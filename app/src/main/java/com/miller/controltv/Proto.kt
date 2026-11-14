package com.miller.controltv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf encoder/decoder. The Android TV Remote v2 protocol only
 * needs varints and length-delimited fields, so we avoid a full protobuf library.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        writeRawVarint(out, (field.toLong() shl 3) or 0L)
        writeRawVarint(out, value)
        return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeRawVarint(out, (field.toLong() shl 3) or 2L)
        writeRawVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, msg: ProtoWriter): ProtoWriter = bytes(field, msg.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun writeRawVarint(out: OutputStream, v: Long) {
            var value = v
            while (true) {
                if ((value and 0x7FL.inv()) == 0L) {
                    out.write(value.toInt())
                    return
                }
                out.write(((value and 0x7F) or 0x80).toInt())
                value = value ushr 7
            }
        }
    }
}

/** Parsed message: field number -> list of values (Long for varints, ByteArray for length-delimited). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int) = fields.containsKey(field)

    fun long(field: Int, default: Long = 0): Long =
        (fields[field]?.firstOrNull() as? Long) ?: default

    fun int(field: Int, default: Int = 0): Int = long(field, default.toLong()).toInt()

    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray

    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    fun messages(field: Int): List<ProtoMessage> =
        fields[field]?.mapNotNull { (it as? ByteArray)?.let { b -> parse(b) } } ?: emptyList()

    fun fieldNumbers(): Set<Int> = fields.keys

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val map = HashMap<Int, MutableList<Any>>()
            var pos = 0
            fun readVarint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    if (pos >= data.size) throw EOFException("varint truncated")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IllegalStateException("varint too long")
                }
            }
            while (pos < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> map.getOrPut(field) { mutableListOf() }.add(readVarint())
                    1 -> pos += 8
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || pos + len > data.size) throw EOFException("bytes truncated")
                        map.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    5 -> pos += 4
                    else -> throw IllegalStateException("unsupported wire type")
                }
            }
            return ProtoMessage(map)
        }
    }
}

/** Messages on both ports are framed with a varint length prefix. */
object Framing {
    fun write(out: OutputStream, payload: ByteArray) {
        val buf = ByteArrayOutputStream()
        ProtoWriter.writeRawVarint(buf, payload.size.toLong())
        buf.write(payload)
        synchronized(out) {
            out.write(buf.toByteArray())
            out.flush()
        }
    }

    fun read(input: InputStream): ByteArray {
        var shift = 0
        var len = 0L
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed")
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (len > 1_000_000) throw IllegalStateException("message too large")
        val data = ByteArray(len.toInt())
        var off = 0
        while (off < data.size) {
            val r = input.read(data, off, data.size - off)
            if (r < 0) throw EOFException("connection closed")
            off += r
        }
        return data
    }
}
