package app.onym.android.chats

/**
 * Lifecycle stage of a chat message on the local device:
 *  - [PENDING] — outgoing, queued, not yet acknowledged by any
 *    relay.
 *  - [SENT] — outgoing, at least one relay accepted. Single check.
 *  - [DELIVERED] — outgoing, a recipient's device received +
 *    decrypted the message and sent back a delivered receipt.
 *    Double check.
 *  - [READ] — outgoing, a recipient opened the thread and sent back
 *    a read receipt (gated by the symmetric read-receipt setting).
 *    Accent double check.
 *  - [FAILED] — outgoing, every relay rejected (or the seal step
 *    threw). Retry path lives outside this enum.
 *  - [RECEIVED] — incoming, decrypted and persisted locally.
 *
 * DELIVERED / READ are appended (not inserted) so the Room name-string
 * persistence ([RoomMessageStore] stores `status.name`) needs no
 * migration.
 *
 * Mirrors `MessageStatus.swift` from onym-ios PR #148.
 */
enum class MessageStatus {
    PENDING,
    SENT,
    FAILED,
    RECEIVED,
    DELIVERED,
    READ,
    ;

    /**
     * Position on the outgoing delivery ladder
     * (`PENDING → SENT → DELIVERED → READ`). Receipts only ever
     * *raise* the status — a late DELIVERED after READ must not move
     * it back — so callers compare ranks before applying. `null` for
     * statuses off the ladder ([FAILED], [RECEIVED]), which receipts
     * never transition to.
     */
    val deliveryRank: Int?
        get() = when (this) {
            PENDING -> 0
            SENT -> 1
            DELIVERED -> 2
            READ -> 3
            FAILED, RECEIVED -> null
        }
}
