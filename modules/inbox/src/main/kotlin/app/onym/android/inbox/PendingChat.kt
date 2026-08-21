package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import java.time.Instant

/**
 * A chat this device has asked to be let into, or been offered a seat
 * in, but is not yet a member of.
 *
 * It is deliberately **not** a [app.onym.android.group.ChatGroup]. There
 * is no group secret, no roster and no epoch here — nothing that could
 * decrypt or send — and it must never reach
 * [app.onym.android.group.GroupRepository]: `currentGroups()` is the
 * "do I already hold this group?" oracle for both
 * [app.onym.android.group.JoinRequestApprover] and
 * [IncomingMessageDispatcher]'s materialize path, so a placeholder there
 * would suppress the real materialization and the "You joined" notice
 * with it. The chats list merges the two kinds of row at the view-model
 * layer instead ([PendingChatsViewModel]).
 *
 * One row per `(group, owning identity)` rather than per inbound event:
 * an offer replayed by a relay and a link tapped for the same group are
 * the same waiting room, and the person should see one row for it.
 *
 * Mirrors `PendingChat` from onym-ios.
 */
data class PendingChat(
    val groupId: ByteArray,
    /** Identity that was invited / did the asking. The join request is
     *  sealed and sent *as* this identity. */
    val ownerIdentityId: IdentityId,
    /** The founder's per-invite intro pubkey — the reply channel a join
     *  request is sealed to. */
    val introPublicKey: ByteArray,
    val groupName: String?,
    /** Self-asserted, like every alias. Empty for a link/QR join, where
     *  nobody introduced themselves. */
    val inviterAlias: String,
    /** The founder's free-text invitation, when the offer carried one. */
    val invitationMessage: String?,
    /** When the offer was sent, or the link was tapped — the row's sort
     *  key in the chats list. */
    val receivedAt: Instant,
    val status: Status,
    /**
     * Authenticated sender timestamp used only to order pushed offers.
     * Null for link/QR joins, whose [receivedAt] comes from this device's
     * clock and must never be compared with a Nostr `created_at` value.
     */
    val offerReceivedAt: Instant? = null,
    /**
     * The name this device asked to be let in under, as typed on the
     * confirmation screen.
     *
     * Kept because a re-send has to introduce the same person. Falling
     * back to the identity's current alias would quietly change the name
     * the founder is looking at between the first request and the
     * second — and they are deciding partly on that name.
     *
     * Null until the person has confirmed a join, which is also the
     * marker that nothing has been sent for this row yet.
     */
    val joinerLabel: String? = null,
) {
    /** Lowercase hex of [groupId] — how [PendingVerificationStore] names
     *  a group, and how the materialized sweep matches rows. */
    val groupIdHex: String get() = groupId.joinToString("") { "%02x".format(it) }

    /** `<group id hex>:<owner uuid>` — the dedupe key, and stable across
     *  relaunches (unlike a Nostr event id, which changes per delivery). */
    val id: String get() = "$groupIdHex:${ownerIdentityId.value}"

    /**
     * What this row is waiting on. The verification statuses
     * ([PendingGroupVerification.Status]) are *not* mirrored here — they
     * live in [PendingVerificationStore] and are overlaid at read time
     * by [PendingChatsViewModel], so there is one owner of each fact.
     */
    sealed interface Status {
        /** A pushed offer nobody has answered yet. Accept ships the join
         *  request; until then nothing has left this device. */
        data object Offered : Status

        /** The join request is out. Waiting on the founder. */
        data object Requested : Status

        /**
         * The request couldn't be sent. Carries a *code*, not a
         * sentence: this row is written to disk and outlives the
         * language it was written in, the same rule
         * `ChatSystemEvent` follows. The wording is assembled at render
         * time, in the screen.
         */
        data class Failed(val failure: SendFailure) : Status
    }

    /**
     * Why a join request didn't leave the device. Coarse on purpose —
     * these are the two things a person can act on differently, and a
     * transport's own error text is neither localizable nor useful to
     * the reader.
     *
     * [raw] is a persistence format: stable forever.
     */
    enum class SendFailure(val raw: String) {
        /** No identity was loaded when the send was attempted. */
        NO_IDENTITY("noIdentity"),

        /** The request never reached a relay. */
        TRANSPORT("transport"),
        ;

        companion object {
            fun fromRaw(raw: String?): SendFailure? = entries.firstOrNull { it.raw == raw }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingChat) return false
        return groupId.contentEquals(other.groupId) &&
            ownerIdentityId == other.ownerIdentityId &&
            introPublicKey.contentEquals(other.introPublicKey) &&
            groupName == other.groupName &&
            inviterAlias == other.inviterAlias &&
            invitationMessage == other.invitationMessage &&
            receivedAt == other.receivedAt &&
            joinerLabel == other.joinerLabel &&
            status == other.status &&
            offerReceivedAt == other.offerReceivedAt
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + introPublicKey.contentHashCode()
        h = 31 * h + (groupName?.hashCode() ?: 0)
        h = 31 * h + inviterAlias.hashCode()
        h = 31 * h + (invitationMessage?.hashCode() ?: 0)
        h = 31 * h + receivedAt.hashCode()
        h = 31 * h + (joinerLabel?.hashCode() ?: 0)
        h = 31 * h + status.hashCode()
        h = 31 * h + (offerReceivedAt?.hashCode() ?: 0)
        return h
    }

    companion object {
        /** Bytes from the hex a row stores. Same shape as
         *  `ChatGroup.groupIdBytes`, which reads its own id back the
         *  same way. */
        fun bytesFromHex(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
    }
}

/**
 * Receive-side seam the dispatcher writes decoded offers into. Kept to
 * one method so the dispatcher depends on a narrow interface rather than
 * the concrete repository.
 */
interface PendingChatRecording {
    /**
     * Idempotent on [PendingChat.id]. A re-delivered offer keeps the row
     * it already has — in particular it must not knock a `Requested` row
     * back to `Offered` and ask the person to accept an invitation they
     * already accepted.
     */
    suspend fun record(chat: PendingChat): PendingChatWriteOutcome
}

/**
 * What a write actually did.
 *
 * Four cases rather than a boolean because the deeplink caller acts
 * differently on each: a fresh row is asked for on the person's behalf,
 * an existing one is picked up where it stands, and a write that never
 * landed has to be said out loud rather than leaving someone waiting on
 * a request their device has no record of.
 *
 * The dispatcher's offer path ignores it on purpose — see `recordOffer`.
 * A pushed offer is a retained event the relays replay on every
 * reconnect, so the next delivery re-attempts the write; there is
 * nothing for that caller to do with a failure that waiting doesn't
 * already do, and there is nothing it may log (an invitation is exactly
 * the kind of activity this app does not record).
 */
enum class PendingChatWriteOutcome {
    /** A new waiting room appeared. */
    INSERTED,

    /** A row for this `(group, owner)` was already there. Its status is
     *  left exactly as it was; only the reply channel is refreshed. */
    ALREADY_PRESENT,

    /** The row could not be written (encryption or store failure). The
     *  person is waiting on something this device has no record of. */
    FAILED,

    /** Nobody was listening — the recorder is a no-op. Distinct from
     *  [FAILED] so a test double is never mistaken for a disk that gave
     *  out. */
    NOT_RECORDED,

    /**
     * This particular row could not be encrypted, so it was not written.
     *
     * Distinct from [FAILED] because the remedy is different and so is
     * the blast radius: the store is fine, this row is not. Reporting it
     * as a store failure demoted the durable store to memory for the
     * rest of the process — losing exactly the durability the row was
     * being written for — on the strength of one bad row.
     */
    NOT_ENCRYPTABLE,
}

/**
 * Default [PendingChatRecording] for the many dispatcher constructions
 * that never exercise an offer. Production wiring passes the shared
 * [PendingChatRepository] explicitly.
 */
class NoopPendingChatRecorder : PendingChatRecording {
    override suspend fun record(chat: PendingChat): PendingChatWriteOutcome =
        PendingChatWriteOutcome.NOT_RECORDED
}
