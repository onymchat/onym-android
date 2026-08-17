package app.onym.android.support

import app.onym.android.identity.InvitationEnvelopeSealer

/**
 * Test-only sealer that passes the payload through, so a test can
 * assert on shipped contents rather than just on bytes being sent.
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
