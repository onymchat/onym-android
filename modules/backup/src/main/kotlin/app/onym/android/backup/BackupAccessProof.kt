package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Per-request proof of possession (onym-system
 * `backup/UI-Backup-Object-HTTP.md` §8). Every `/v1/` request carries
 * the four headers this builds. Length-prefixing each signed field is
 * not decoration — without it, a boundary between two
 * attacker-influenced adjacent fields could be shifted and the
 * signature reinterpreted.
 *
 * Mirrors `BackupAccessProof` in onym-ios OnymBackup.
 */
object BackupAccessProof {
    const val CONTEXT = "onym-backup-v1"

    data class Headers(
        val holder: String,
        val timestamp: String,
        val nonce: String,
        val signature: String,
    )

    /** Builds the four `X-Onym-*` headers for one request. [nonce] is
     *  internal-only in the public sense — always fresh CSPRNG bytes
     *  in production, overridable only for tests. */
    fun headers(
        method: String,
        path: String,
        body: ByteArray,
        signingKey: Ed25519PrivateKeyParameters,
        holderReference: String,
        now: Instant = Instant.now(),
        nonce: ByteArray = randomNonce(),
    ): Headers {
        val timestamp = BackupFormat.rfc3339(now)
        val nonceHex = nonce.toLowercaseHex()
        val bodyDigest = MessageDigest.getInstance("SHA-256").digest(body)

        val signedBytes = lengthPrefixed(
            CONTEXT.toByteArray(Charsets.UTF_8),
            method.uppercase().toByteArray(Charsets.UTF_8),
            path.toByteArray(Charsets.UTF_8),
            holderReference.toByteArray(Charsets.UTF_8),
            timestamp.toByteArray(Charsets.UTF_8),
            nonceHex.toByteArray(Charsets.UTF_8),
            bodyDigest,
        )

        val signature = Ed25519Signer().apply {
            init(true, signingKey)
            update(signedBytes, 0, signedBytes.size)
        }.generateSignature()

        return Headers(
            holder = holderReference,
            timestamp = timestamp,
            nonce = nonceHex,
            signature = Base64.getEncoder().encodeToString(signature),
        )
    }

    private fun lengthPrefixed(vararg fields: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            out.write(ByteBuffer.allocate(4).putInt(field.size).array())
            out.write(field)
        }
        return out.toByteArray()
    }

    private fun randomNonce(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
}
