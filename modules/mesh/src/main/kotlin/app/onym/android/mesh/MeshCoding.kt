package app.onym.android.mesh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** GF(2⁸), polynomial 0x11D, generator 2 — onym-ios `GF256` parity. */
internal object GF256 {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun inv(a: Int): Int = exp[255 - log[a]]
}

/**
 * The erasure code behind coded frames (onym-ios docs/mesh-ble-v1.md §4c):
 * K symbols of [SYMBOL_SIZE] bytes; frames 0…K−1 carry them as they are,
 * every later index a GF(256) combination of all K. Any K independent
 * frames rebuild the envelope, whichever ones a listener happened to catch.
 */
object MeshFountain {
    const val SYMBOL_SIZE = 17
    const val MAX_ENVELOPE = 255 * SYMBOL_SIZE - 2

    /** xorshift32 seeded from envelope ID and index; zero mapped to one. */
    fun coefficients(envelopeId: Int, index: Int, k: Int): IntArray {
        var x = (((envelopeId.toLong() shl 16) or index.toLong()) * 0x9E3779B1L).toInt() xor 0x6D2B79F5
        if (x == 0) x = 1
        return IntArray(k) {
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            val c = x and 0xFF
            if (c == 0) 1 else c
        }
    }

    class Encoder private constructor(val k: Int, val envelopeId: Int, private val symbols: Array<ByteArray>) {
        fun symbol(index: Int): ByteArray {
            if (index < k) return symbols[index].copyOf()
            val c = coefficients(envelopeId, index, k)
            val out = ByteArray(SYMBOL_SIZE)
            for (m in 0 until k) {
                val s = symbols[m]
                for (j in 0 until SYMBOL_SIZE) {
                    out[j] = (out[j].toInt() xor GF256.mul(c[m], s[j].toInt() and 0xFF)).toByte()
                }
            }
            return out
        }

        companion object {
            /** Null for an empty envelope or one too large for 255 symbols. */
            fun of(envelope: ByteArray, envelopeId: Int): Encoder? {
                if (envelope.isEmpty() || envelope.size > MAX_ENVELOPE) return null
                val prefixed = byteArrayOf((envelope.size ushr 8).toByte(), envelope.size.toByte()) + envelope
                val k = (prefixed.size + SYMBOL_SIZE - 1) / SYMBOL_SIZE
                val buffer = prefixed.copyOf(k * SYMBOL_SIZE)
                return Encoder(k, envelopeId, Array(k) { buffer.copyOfRange(it * SYMBOL_SIZE, (it + 1) * SYMBOL_SIZE) })
            }
        }
    }

    /** Online Gaussian elimination in reduced row-echelon form. */
    class Decoder(val k: Int, val envelopeId: Int) {
        private val rows = HashMap<Int, Pair<IntArray, IntArray>>()

        val rank: Int get() = rows.size

        fun add(index: Int, symbol: ByteArray): ByteArray? {
            if (symbol.size != SYMBOL_SIZE || rows.size >= k) return null
            val v = if (index < k) IntArray(k).also { it[index] = 1 } else coefficients(envelopeId, index, k)
            val d = IntArray(SYMBOL_SIZE) { symbol[it].toInt() and 0xFF }
            for ((pivot, row) in rows) {
                val f = v[pivot]
                if (f == 0) continue
                for (m in 0 until k) v[m] = v[m] xor GF256.mul(f, row.first[m])
                for (j in 0 until SYMBOL_SIZE) d[j] = d[j] xor GF256.mul(f, row.second[j])
            }
            val column = v.indexOfFirst { it != 0 }
            if (column < 0) return null
            val scale = GF256.inv(v[column])
            for (m in 0 until k) v[m] = GF256.mul(scale, v[m])
            for (j in 0 until SYMBOL_SIZE) d[j] = GF256.mul(scale, d[j])
            for ((_, row) in rows) {
                val f = row.first[column]
                if (f == 0) continue
                for (m in 0 until k) row.first[m] = row.first[m] xor GF256.mul(f, v[m])
                for (j in 0 until SYMBOL_SIZE) row.second[j] = row.second[j] xor GF256.mul(f, d[j])
            }
            rows[column] = v to d
            if (rows.size < k) return null

            val buffer = ByteArray(k * SYMBOL_SIZE)
            for (i in 0 until k) {
                val data = rows.getValue(i).second
                for (j in 0 until SYMBOL_SIZE) buffer[i * SYMBOL_SIZE + j] = data[j].toByte()
            }
            val length = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            if (length <= 0 || length > buffer.size - 2) return null
            return buffer.copyOfRange(2, 2 + length)
        }
    }
}

/** A coded frame (kind 0xC2, §4c): 10-byte header + 17-byte symbol. */
class MeshCodedFrame(
    val epochLow: Int,
    val tag: Int,
    val envelopeId: Int,
    val k: Int,
    val index: Int,
    val symbol: ByteArray,
) {
    fun encode(): ByteArray =
        byteArrayOf(KIND, epochLow.toByte()) + MeshTag.toBytes(tag) +
            byteArrayOf(
                (envelopeId ushr 8).toByte(), envelopeId.toByte(), k.toByte(),
                (index ushr 8).toByte(), index.toByte(),
            ) + symbol

    /** What scopes a 16-bit envelope ID: its epoch and tag. */
    fun key(epoch: Long): Long = ((epoch and 0xFF_FFFF) shl 40) or (tag.toLong() shl 16) or envelopeId.toLong()

    companion object {
        const val KIND: Byte = 0xC2.toByte()
        const val HEADER = 10
        const val SIZE = HEADER + MeshFountain.SYMBOL_SIZE

        fun decode(b: ByteArray): MeshCodedFrame? {
            if (b.size != SIZE || b[0] != KIND) return null
            val k = b[7].toInt() and 0xFF
            if (k < 1) return null
            return MeshCodedFrame(
                epochLow = b[1].toInt() and 0xFF,
                tag = MeshTag.fromBytes(b, 2),
                envelopeId = ((b[5].toInt() and 0xFF) shl 8) or (b[6].toInt() and 0xFF),
                k = k,
                index = ((b[8].toInt() and 0xFF) shl 8) or (b[9].toInt() and 0xFF),
                symbol = b.copyOfRange(HEADER, b.size),
            )
        }
    }
}

/**
 * The sealed envelope in binary for the air (§4c): format 0x01, the fixed
 * fields raw, then the ciphertext. A receiver rebuilds exactly the JSON
 * envelope the inbox already opens.
 */
object MeshEnvelope {
    private const val FORMAT: Byte = 0x01
    private const val SCHEME = "x25519-aes-256-gcm-v1"
    private val FIELDS = listOf(
        "ephemeral_public_key" to 32,
        "ephemeral_key_signature" to 64,
        "sender_ed25519_public_key" to 32,
        "nonce" to 12,
        "authentication_tag" to 16,
    )
    private const val HEADER = 1 + 32 + 64 + 32 + 12 + 16

    fun binaryFromJson(json: ByteArray): ByteArray? {
        val obj = runCatching { Json.parseToJsonElement(json.decodeToString()).jsonObject }.getOrNull() ?: return null
        if (obj["version"]?.jsonPrimitive?.intOrNull != 1) return null
        if (obj["scheme"]?.jsonPrimitive?.content != SCHEME) return null
        val out = ByteArrayOutputStream()
        out.write(FORMAT.toInt())
        for ((key, size) in FIELDS) {
            val value = obj.base64(key) ?: return null
            if (value.size != size) return null
            out.write(value)
        }
        val ciphertext = obj.base64("ciphertext") ?: return null
        if (ciphertext.isEmpty()) return null
        out.write(ciphertext)
        return out.toByteArray()
    }

    fun jsonFromBinary(binary: ByteArray): ByteArray? {
        if (binary.size <= HEADER || binary[0] != FORMAT) return null
        val encoder = Base64.getEncoder()
        var offset = 1
        val obj = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("scheme", JsonPrimitive(SCHEME))
            for ((key, size) in FIELDS) {
                put(key, JsonPrimitive(encoder.encodeToString(binary.copyOfRange(offset, offset + size))))
                offset += size
            }
            put("ciphertext", JsonPrimitive(encoder.encodeToString(binary.copyOfRange(offset, binary.size))))
        }
        return obj.toString().encodeToByteArray()
    }

    private fun JsonObject.base64(key: String): ByteArray? =
        (this[key] as? JsonPrimitive)?.content?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

/**
 * The plaintext a mesh sender seals: `0x01 ‖ raw DEFLATE(payload)`. JSON
 * always starts with `{`, so the marker can't be mistaken for a payload;
 * unmarked plaintext passes through [expand] unchanged.
 */
object MeshPayload {
    private const val MARKER: Byte = 0x01
    private const val MAX_EXPANDED = 64 * 1024

    fun compress(payload: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(payload)
        deflater.finish()
        val out = ByteArrayOutputStream()
        out.write(MARKER.toInt())
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    fun expand(plaintext: ByteArray): ByteArray? {
        if (plaintext.firstOrNull() != MARKER) return plaintext
        val inflater = Inflater(true)
        return try {
            inflater.setInput(plaintext, 1, plaintext.size - 1)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
                if (out.size() > MAX_EXPANDED) return null
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
