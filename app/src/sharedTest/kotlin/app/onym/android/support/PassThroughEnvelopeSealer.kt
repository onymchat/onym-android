package app.onym.android.support

import app.onym.android.identity.InvitationEnvelopeSealer

/**
 * Test-only [InvitationEnvelopeSealer] that hands the payload back
 * unchanged, recording who each seal was addressed to.
 *
 * Real sealing needs the device's Ed25519 identity key, which a JVM
 * unit test can't reach. Passing the plaintext through keeps whatever
 * lands on the fake transport decodable, so a test can assert on the
 * *contents* of a shipped `GroupInvitationPayload` — the epoch it
 * carries, say — rather than only on the fact that bytes were sent.
 */
class PassThroughEnvelopeSealer : InvitationEnvelopeSealer {

    private val _recipients = mutableListOf<ByteArray>()

    /** Every `recipientInboxPublicKey` this sealer was handed, in order. */
    val recipients: List<ByteArray> get() = _recipients.toList()

    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray {
        _recipients += recipientInboxPublicKey
        return payload
    }
}
