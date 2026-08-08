package app.onym.android.chats

/**
 * Local-view direction of a chat message. Persisted as the enum
 * name on disk (`INCOMING` / `OUTGOING`); not on the wire.
 *
 * Mirrors `MessageDirection.swift` from onym-ios PR #148.
 */
enum class MessageDirection { INCOMING, OUTGOING }
