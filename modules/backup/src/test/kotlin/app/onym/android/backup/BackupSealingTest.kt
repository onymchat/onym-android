package app.onym.android.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class BackupSealingTest {

    private fun tempFile(prefix: String) = File.createTempFile(prefix, ".bin").apply { deleteOnExit() }

    @Test
    fun seal_open_round_trip_preserves_payload_as_prefix_of_padded_plaintext() {
        val archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val payload = "hello onym backup".toByteArray(Charsets.UTF_8)
        val plainFile = tempFile("plain").apply { writeBytes(payload) }
        val sealedFile = tempFile("sealed")
        val openedFile = tempFile("opened")

        val reference = BackupSealer.seal(plainFile, sealedFile, archiveRoot)
        assertTrue(BackupOpener.verify(sealedFile, reference))

        BackupOpener.open(sealedFile, reference, archiveRoot, openedFile)
        val opened = openedFile.readBytes()
        assertTrue(opened.size >= payload.size)
        assertArrayEquals(payload, opened.copyOfRange(0, payload.size))
    }

    @Test
    fun non_convergent_keying_same_plaintext_twice_yields_different_digests() {
        val archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val payload = "same content, different snapshots".toByteArray(Charsets.UTF_8)
        val plainFile = tempFile("plain").apply { writeBytes(payload) }

        val ref1 = BackupSealer.seal(plainFile, tempFile("sealed1"), archiveRoot)
        val ref2 = BackupSealer.seal(plainFile, tempFile("sealed2"), archiveRoot)

        assertNotEquals(ref1.digest, ref2.digest)
    }

    @Test
    fun tampered_chunk_fails_before_producing_plaintext() {
        val archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val payload = ByteArray(200) { it.toByte() }
        val plainFile = tempFile("plain").apply { writeBytes(payload) }
        val sealedFile = tempFile("sealed")
        val reference = BackupSealer.seal(plainFile, sealedFile, archiveRoot)

        // Flip one bit inside the sealed file, after the header.
        val bytes = sealedFile.readBytes()
        bytes[BackupSealer.HEADER_BYTES] = (bytes[BackupSealer.HEADER_BYTES].toInt() xor 0x01).toByte()
        sealedFile.writeBytes(bytes)

        // The digest check catches it before decrypt is ever attempted.
        assertFalse(BackupOpener.verify(sealedFile, reference))
        try {
            BackupOpener.open(sealedFile, reference, archiveRoot, tempFile("opened"))
            org.junit.Assert.fail("expected IncompleteSnapshot")
        } catch (e: BackupError) {
            assertTrue(e is BackupError.IncompleteSnapshot)
        }
    }

    @Test
    fun access_proof_is_bound_to_the_request() {
        val signingKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(SecureRandom())
        val holderRef = BackupKeys.holderReference(signingKey.generatePublicKey().encoded)
        val body = "body".toByteArray()

        val h1 = BackupAccessProof.headers("POST", "/v1/preflight", body, signingKey, holderRef)
        val h2 = BackupAccessProof.headers("POST", "/v1/erasures", body, signingKey, holderRef, nonce = h1.nonce.let {
            // reuse same nonce hex decoded back to bytes for a same-nonce, different-path comparison
            ByteArray(16) { i -> ((Character.digit(it[2 * i], 16) shl 4) or Character.digit(it[2 * i + 1], 16)).toByte() }
        })

        assertNotEquals(h1.signature, h2.signature)
    }

    @Test
    fun padded_worked_example_seals_to_the_pinned_bucket() {
        val archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val payload = ByteArray(41_000_000)
        val plainFile = tempFile("plain").apply { writeBytes(payload) }
        val sealedFile = tempFile("sealed")

        val reference = BackupSealer.seal(plainFile, sealedFile, archiveRoot)
        val expectedPadded = 41_943_040L
        val expectedChunkCount = (expectedPadded + BackupSealer.CHUNK_PLAIN_BYTES - 1) / BackupSealer.CHUNK_PLAIN_BYTES
        val expectedSealedSize = BackupSealer.HEADER_BYTES + expectedChunkCount * 28L + expectedPadded
        assertEquals(expectedSealedSize, reference.sealedByteSize)
    }
}
