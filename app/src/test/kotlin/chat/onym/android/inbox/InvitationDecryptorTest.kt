package chat.onym.android.inbox

import chat.onym.android.identity.IdentityId
import chat.onym.android.identity.InvitationDecryptError
import chat.onym.android.support.FakeInvitationEnvelopeDecrypter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pump shape for [InvitationDecryptor] — verifies the interactor
 * forwards bytes to the seam unchanged and parses the returned
 * plaintext into [DecryptedInvitation], plus that errors on either
 * side propagate without translation.
 *
 * Uses [FakeInvitationEnvelopeDecrypter] so the production X25519+AES
 * path stays out of scope; that's covered by
 * `IdentityRepositoryInvitationDecryptTest` against a real
 * EncryptedSharedPreferences-backed identity.
 *
 * Mirrors `InvitationDecryptorTests.swift` from onym-ios PR #17.
 */
class InvitationDecryptorTest {

    private val testIdentity = IdentityId("test-identity")

    @Test
    fun decrypt_passesEnvelopeBytesThroughToDecrypter() = runTest {
        val plaintext = decryptedInvitationJson("aGVsbG8=", "Group A", 7uL, "abcd")
        val fake = FakeInvitationEnvelopeDecrypter(
            FakeInvitationEnvelopeDecrypter.Mode.Fixed(plaintext)
        )
        val decryptor = InvitationDecryptor(fake)

        val envelope = "envelope-bytes".toByteArray()
        val result = decryptor.decrypt(envelope, asIdentity = testIdentity)

        assertEquals(1, fake.decryptCalls.size)
        assertArrayEquals(envelope, fake.decryptCalls.single())
        assertEquals("Group A", result.name)
        assertEquals(7uL, result.epoch)
        assertEquals("abcd", result.senderNostrPubkey)
    }

    @Test
    fun decrypt_passesPerRecordAsIdentityThroughToEnvelopeDecrypter() = runTest {
        val plaintext = decryptedInvitationJson("AAA=", "X", 1uL, "pk")
        val fake = FakeInvitationEnvelopeDecrypter(
            FakeInvitationEnvelopeDecrypter.Mode.Fixed(plaintext)
        )
        val decryptor = InvitationDecryptor(fake)
        val alice = IdentityId("alice-id")
        val bob = IdentityId("bob-id")

        decryptor.decrypt("env-A".toByteArray(), asIdentity = alice)
        decryptor.decrypt("env-B".toByteArray(), asIdentity = bob)

        // The decryptor's only job for PR-6 (per-identity decryption
        // routing) is to pass the per-record ownerIdentityId through
        // to the envelope decrypter unchanged. This assertion is
        // load-bearing: a regression here means envelopes addressed
        // to identity B silently fail to decrypt while the user is
        // on identity A — the exact bug PR-6 fixes.
        val calls = fake.decryptCallsWithIdentity
        assertEquals(2, calls.size)
        assertEquals(alice, calls[0].asIdentity)
        assertEquals(bob, calls[1].asIdentity)
    }

    @Test
    fun decrypt_scriptedMode_decodesEachInputCorrectly() = runTest {
        val envA = "env-A".toByteArray()
        val envB = "env-B".toByteArray()
        val ptA = decryptedInvitationJson("AAA=", "Alpha", 1uL, "pkA")
        val ptB = decryptedInvitationJson("AAEC", "Beta",  2uL, "pkB")
        val fake = FakeInvitationEnvelopeDecrypter(
            FakeInvitationEnvelopeDecrypter.Mode.Scripted(
                mapOf(envA.toList() to ptA, envB.toList() to ptB)
            )
        )
        val decryptor = InvitationDecryptor(fake)

        val resA = decryptor.decrypt(envA, asIdentity = testIdentity)
        val resB = decryptor.decrypt(envB, asIdentity = testIdentity)

        assertEquals("Alpha", resA.name); assertEquals(1uL, resA.epoch)
        assertEquals("Beta",  resB.name); assertEquals(2uL, resB.epoch)
        assertEquals(2, fake.decryptCalls.size)
    }

    @Test
    fun decrypt_fixedMode_returnsSamePlaintextForAnyInput() = runTest {
        val pt = decryptedInvitationJson("AAA=", "Constant", 99uL, "pk")
        val fake = FakeInvitationEnvelopeDecrypter(
            FakeInvitationEnvelopeDecrypter.Mode.Fixed(pt)
        )
        val decryptor = InvitationDecryptor(fake)

        val r1 = decryptor.decrypt("anything-1".toByteArray(), asIdentity = testIdentity)
        val r2 = decryptor.decrypt("anything-2".toByteArray(), asIdentity = testIdentity)

        assertEquals(r1, r2)
        assertEquals("Constant", r1.name)
    }

    @Test
    fun decrypt_propagatesDecrypterException() = runTest {
        val cause = InvitationDecryptError.UnsupportedScheme("aes-128-cbc-v0")
        val fake = FakeInvitationEnvelopeDecrypter(
            FakeInvitationEnvelopeDecrypter.Mode.Failing(cause)
        )
        val decryptor = InvitationDecryptor(fake)

        val thrown = assertThrows(InvitationDecryptError.UnsupportedScheme::class.java) {
            kotlinx.coroutines.runBlocking { decryptor.decrypt("env".toByteArray(), asIdentity = testIdentity) }
        }
        // Same instance — interactor MUST NOT wrap or rewrap the seam's error.
        assertSame(cause, thrown)
    }

    @Test
    fun decrypt_throwsOnMalformedPlaintextJson() = runTest {
        val garbage = "not valid json {{{".toByteArray(Charsets.UTF_8)
        val fake = FakeInvitationEnvelopeDecrypter(
            FakeInvitationEnvelopeDecrypter.Mode.Fixed(garbage)
        )
        val decryptor = InvitationDecryptor(fake)

        // SerializationException not InvitationDecryptError — the
        // contract is: seam errors are decryption failures; parser
        // errors are payload-shape failures, surfaced separately.
        val thrown = assertThrows(SerializationException::class.java) {
            kotlinx.coroutines.runBlocking { decryptor.decrypt("env".toByteArray(), asIdentity = testIdentity) }
        }
        assertTrue("expected SerializationException, got ${thrown::class.simpleName}: ${thrown.message}", true)
    }

    /** Build a JSON byte string for [DecryptedInvitation] without
     *  going through kotlinx.serialization (so the test asserts
     *  decoding rather than encoding-then-decoding). */
    private fun decryptedInvitationJson(
        groupIdBase64: String,
        name: String,
        epoch: ULong,
        senderNostrPubkey: String,
    ): ByteArray = """
        {
          "groupID": "$groupIdBase64",
          "name": "$name",
          "epoch": $epoch,
          "senderNostrPubkey": "$senderNostrPubkey"
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    // Touch Base64 to avoid the unused-import warning if the helper
    // ever drops it; intentionally kept for ergonomics in future tests.
    @Suppress("unused")
    private val b64 = Base64.getEncoder()
}
