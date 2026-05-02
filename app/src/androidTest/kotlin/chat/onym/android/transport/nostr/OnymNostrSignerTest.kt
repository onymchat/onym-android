package chat.onym.android.transport.nostr

import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.identity.OnymNostrSigner
import chat.onym.sdk.Common
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hits OnymSDK's BIP340 / secp256k1 FFI through [OnymNostrSigner] —
 * no mocks. The roundtrip uses
 * [chat.onym.sdk.Common.nostrVerifyEventSignature] so a regression
 * in either signing or verification surfaces here.
 *
 * Lives in `androidTest/` (not `test/`) because the OnymSDK FFI
 * lives in jni/abi/lib*.so inside the AAR — only loadable on
 * emulator/device, not on host JVM. Same pattern the existing
 * `IdentityRepositoryTest` uses for `Common.publicKey` /
 * `Common.nostrDerivePublicKey` calls.
 *
 * Mirrors `OnymNostrSignerTests.swift` from onym-ios PR #13.
 */
@RunWith(AndroidJUnit4::class)
class OnymNostrSignerTest {

    // ─── constructor ──────────────────────────────────────────────

    @Test
    fun init_acceptsValid32ByteSecret() {
        // Should not throw.
        OnymNostrSigner(ByteArray(32) { 0x01 })
    }

    @Test
    fun init_rejectsShortSecret() {
        assertThrows(IllegalArgumentException::class.java) {
            OnymNostrSigner(ByteArray(31) { 0x01 })
        }
    }

    @Test
    fun init_rejectsLongSecret() {
        assertThrows(IllegalArgumentException::class.java) {
            OnymNostrSigner(ByteArray(33) { 0x01 })
        }
    }

    @Test
    fun init_rejectsEmptySecret() {
        assertThrows(IllegalArgumentException::class.java) {
            OnymNostrSigner(ByteArray(0))
        }
    }

    // ─── publicKey ────────────────────────────────────────────────

    @Test
    fun publicKey_is32Bytes() {
        val signer = OnymNostrSigner(ByteArray(32) { 0x42 })
        assertEquals("BIP340 x-only pubkey is 32 bytes", 32, signer.publicKey().size)
    }

    @Test
    fun publicKey_isDeterministic() {
        val secret = ByteArray(32) { 0x42 }
        val pubA = OnymNostrSigner(secret).publicKey()
        val pubB = OnymNostrSigner(secret).publicKey()
        assertArrayEquals(pubA, pubB)
    }

    // ─── signEventId ──────────────────────────────────────────────

    @Test
    fun signEventId_rejectsShortEventId() {
        val signer = OnymNostrSigner(ByteArray(32) { 0x01 })
        assertThrows(IllegalArgumentException::class.java) {
            signer.signEventId(ByteArray(31) { 0xAA.toByte() })
        }
    }

    @Test
    fun signEventId_returns64Bytes() {
        val signer = OnymNostrSigner(ByteArray(32) { 0x01 })
        val sig = signer.signEventId(ByteArray(32) { 0xAA.toByte() })
        assertEquals("BIP340 schnorr sig is 64 bytes", 64, sig.size)
    }

    @Test
    fun signEventId_verifiesAgainstOnymSDK() {
        val signer = OnymNostrSigner(ByteArray(32) { 0x01 })
        val pub = signer.publicKey()
        val eventId = ByteArray(32) { 0xAA.toByte() }
        val sig = signer.signEventId(eventId)
        // Android's Common.nostrVerifyEventSignature returns Boolean
        // — the underlying FFI conflates "verify failed" with "input
        // malformed" into a single `bool` (see the wrapper KDoc in
        // onym-sdk-kotlin). iOS's binding throws on failure instead.
        // Inputs here are well-formed and the sig is freshly produced,
        // so the result must be true.
        assertTrue(
            "schnorr verify of own signature must return true",
            Common.nostrVerifyEventSignature(pub, eventId, sig),
        )
    }

    @Test
    fun signEventId_verificationFailsForWrongMessage() {
        val signer = OnymNostrSigner(ByteArray(32) { 0x01 })
        val pub = signer.publicKey()
        val signedId = ByteArray(32) { 0xAA.toByte() }
        val otherId = ByteArray(32) { 0xBB.toByte() }
        val sig = signer.signEventId(signedId)
        assertFalse(
            "verify with a different eventId must return false",
            Common.nostrVerifyEventSignature(pub, otherId, sig),
        )
    }

    // ─── ephemeral ────────────────────────────────────────────────

    @Test
    fun ephemeral_producesDistinctKeysPerCall() {
        val a = OnymNostrSigner.ephemeral()
        val b = OnymNostrSigner.ephemeral()
        assertFalse("CSPRNG produced identical secrets — astronomically unlikely",
            a.secretKey.contentEquals(b.secretKey))
        assertFalse("distinct secrets must produce distinct pubkeys",
            a.publicKey().contentEquals(b.publicKey()))
    }

    @Test
    fun ephemeral_secretIs32Bytes() {
        val signer = OnymNostrSigner.ephemeral()
        assertEquals(32, signer.secretKey.size)
    }

    @Test
    fun ephemeral_canSignAndVerify() {
        val signer = OnymNostrSigner.ephemeral()
        val pub = signer.publicKey()
        val eventId = ByteArray(32) { 0x55 }
        val sig = signer.signEventId(eventId)
        assertTrue(
            "ephemeral signer roundtrip must verify",
            Common.nostrVerifyEventSignature(pub, eventId, sig),
        )
    }
}
