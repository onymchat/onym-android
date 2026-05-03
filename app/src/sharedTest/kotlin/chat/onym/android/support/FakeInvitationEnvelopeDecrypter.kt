package chat.onym.android.support

import chat.onym.android.identity.IdentityId
import chat.onym.android.identity.InvitationDecryptError
import chat.onym.android.identity.InvitationEnvelopeDecrypter

/**
 * Reusable test fake for [InvitationEnvelopeDecrypter]. Three modes
 * cover the three shapes of test:
 *
 *  - [Mode.Fixed] — happy-path pump tests that don't care about the
 *    input bytes, only that the decrypter returns *some* plaintext.
 *  - [Mode.Scripted] — multi-input tests that need different
 *    plaintexts depending on the envelope. Lookup is by content
 *    equality; an unmapped envelope throws.
 *  - [Mode.Failing] — error-path coverage; throws the supplied
 *    error verbatim.
 *
 * Tracks every envelope it's been called with in [decryptCalls] so
 * tests can assert on pump fan-out, ordering, dedup behaviour, etc.
 *
 * Mirrors `FakeInvitationEnvelopeDecrypter.swift` from onym-ios PR #17
 * (an actor there; not a coroutine actor here — just a plain class
 * with a `suspend fun` that switches on [Mode]).
 */
class FakeInvitationEnvelopeDecrypter(var mode: Mode) : InvitationEnvelopeDecrypter {

    sealed class Mode {
        /** Always returns [plaintext], regardless of input. */
        data class Fixed(val plaintext: ByteArray) : Mode() {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Fixed && plaintext.contentEquals(other.plaintext))
            override fun hashCode(): Int = plaintext.contentHashCode()
        }

        /** Looks up plaintext by exact-content envelope match. Tests
         *  build the script with `mapOf(env1.toList() to plaintext1, …)`
         *  — keying on `List<Byte>` because raw `ByteArray` uses
         *  reference equality. */
        data class Scripted(val script: Map<List<Byte>, ByteArray>) : Mode()

        /** Throws [error] on every call. */
        data class Failing(val error: Throwable) : Mode()
    }

    /** Every envelope this fake has been asked to decrypt, in call
     *  order. Tests assert with `decryptCalls.last().contentEquals(...)`
     *  or by counting size. */
    val decryptCalls: List<ByteArray> get() = _decryptCalls.map { it.envelope }
    /** Per-call (envelope, asIdentity) tuples — lets the per-identity-
     *  decryption tests assert that each record was decrypted with
     *  the right identity, not just that something was decrypted. */
    val decryptCallsWithIdentity: List<DecryptCall> get() = _decryptCalls.toList()
    private val _decryptCalls = mutableListOf<DecryptCall>()

    data class DecryptCall(val envelope: ByteArray, val asIdentity: IdentityId) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is DecryptCall && envelope.contentEquals(other.envelope) && asIdentity == other.asIdentity)
        override fun hashCode(): Int = 31 * envelope.contentHashCode() + asIdentity.hashCode()
    }

    override suspend fun decryptInvitation(envelopeBytes: ByteArray, asIdentity: IdentityId): ByteArray {
        _decryptCalls.add(DecryptCall(envelopeBytes.copyOf(), asIdentity))
        return when (val m = mode) {
            is Mode.Fixed -> m.plaintext.copyOf()
            is Mode.Scripted -> m.script[envelopeBytes.toList()]
                ?: throw InvitationDecryptError.MalformedEnvelope(
                    "FakeInvitationEnvelopeDecrypter: no script entry for envelope (${envelopeBytes.size} bytes)"
                )
            is Mode.Failing -> throw m.error
        }
    }
}
