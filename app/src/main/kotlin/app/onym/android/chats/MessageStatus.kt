package app.onym.android.chats

/**
 * Lifecycle stage of a chat message on the local device:
 *  - [PENDING] — outgoing, queued, not yet acknowledged by any
 *    relay.
 *  - [SENT] — outgoing, at least one relay accepted.
 *  - [FAILED] — outgoing, every relay rejected (or the seal step
 *    threw). Retry path lives outside this enum.
 *  - [RECEIVED] — incoming, decrypted and persisted locally.
 *
 * Mirrors `MessageStatus.swift` from onym-ios PR #148.
 */
enum class MessageStatus { PENDING, SENT, FAILED, RECEIVED }
