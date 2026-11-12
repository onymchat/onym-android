package app.onym.android.mesh

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The mesh's addressing, built so nobody listening on the radio can
 * tell who a broadcast is for (docs/mesh-ble-v1.md §2).
 *
 * A frame never names its recipient. It carries a short tag only the
 * sender and the recipient can compute — from a key the two of them
 * share and nobody else can derive, not even someone holding the
 * recipient's public inbox key, which is in every share-my-key QR — and
 * the tag changes every epoch, so today's frames can't be linked to
 * tomorrow's.
 */
object MeshEpoch {
    /** 15 minutes. */
    const val LENGTH_SECONDS = 15L * 60L

    /** UInt32 on the wire; carried as a Long so it never goes negative. */
    fun number(at: Instant): Long = (at.epochSecond.coerceAtLeast(0) / LENGTH_SECONDS) and 0xFFFF_FFFFL
}

/**
 * The secret one pair of inbox keys shares for the mesh:
 * `HKDF-SHA256(X25519(own, peer), salt = "onym-mesh-pair-v1", info = "")`.
 *
 * X25519 is symmetric, so either side derives the same key from its own
 * private key and the other's public key. HKDF separates it from the
 * inbox key's other use (sealing envelopes).
 */
object MeshPairKey {
    private val SALT = "onym-mesh-pair-v1".toByteArray(Charsets.UTF_8)

    fun derive(privateKey: X25519PrivateKeyParameters, peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == 32) { "peer inbox key must be 32 bytes" }
        val shared = ByteArray(32)
        X25519Agreement().apply { init(privateKey) }
            .calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(shared, SALT, ByteArray(0)))
        }.generateBytes(out, 0, out.size)
        shared.fill(0)
        return out
    }
}

/**
 * A frame's recipient tag for one epoch: the first three bytes of
 * `HMAC-SHA256(pairKey, "onym-mesh-tag-v1" ‖ epoch as UInt32 big-endian)`,
 * held as an Int in `0..0xFFFFFF`.
 *
 * Three bytes on purpose: unrelated frames collide with a listener's tags
 * often enough that a match says little, and a collision costs one failed
 * decrypt — the envelope authenticates its sender.
 */
object MeshTag {
    const val BYTE_COUNT = 3
    private val DOMAIN = "onym-mesh-tag-v1".toByteArray(Charsets.UTF_8)

    fun derive(pairKey: ByteArray, epoch: Long): Int {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pairKey, "HmacSHA256")) }
        mac.update(DOMAIN)
        mac.update(byteArrayOf((epoch ushr 24).toByte(), (epoch ushr 16).toByte(), (epoch ushr 8).toByte(), epoch.toByte()))
        val digest = mac.doFinal()
        return (digest[0].toInt() and 0xFF shl 16) or (digest[1].toInt() and 0xFF shl 8) or (digest[2].toInt() and 0xFF)
    }

    fun toBytes(tag: Int): ByteArray = byteArrayOf((tag ushr 16).toByte(), (tag ushr 8).toByte(), tag.toByte())

    fun fromBytes(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 16) or (bytes[offset + 1].toInt() and 0xFF shl 8) or (bytes[offset + 2].toInt() and 0xFF)
}
