package app.onym.android.inbox

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.Serializable

/**
 * Parsed subset of the stellar-mls `BootstrapPayload` JSON — just
 * the fields a UI layer would show while the user decides whether
 * to accept the invitation. The remaining fields (members,
 * groupSecret, relayHints, salt, commitment, senderTransportBundle,
 * etc.) only matter once a flow actually joins the group; they
 * land in a future PR alongside the join interactor.
 *
 * Wire format is camelCase (matches the writer in
 * `stellar-mls/clients/android/StellarChat/.../model/BootstrapPayload.kt`).
 * `groupID` ships as a base64 string. `epoch` is permissive on
 * whether the wire value is signed or unsigned — JSON sees an
 * integer.
 *
 * `Json { ignoreUnknownKeys = true }` is used by the decoder
 * (configured in [InvitationDecryptor]), so adding fields here is
 * non-breaking and adding fields to the wire format upstream is
 * non-breaking.
 *
 * Mirrors `DecryptedInvitation.swift` from onym-ios PR #17.
 */
@Serializable
data class DecryptedInvitation(
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupID: ByteArray,
    val name: String,
    val epoch: ULong,
    val senderNostrPubkey: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedInvitation) return false
        return groupID.contentEquals(other.groupID) &&
            name == other.name &&
            epoch == other.epoch &&
            senderNostrPubkey == other.senderNostrPubkey
    }

    override fun hashCode(): Int {
        var result = groupID.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + senderNostrPubkey.hashCode()
        return result
    }
}
