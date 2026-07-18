package app.onym.android.chats

import app.onym.android.group.MemberProfile
import app.onym.android.group.OnymAccent
import java.util.UUID

/**
 * Resolved quote shown at the top of a bubble that replies to an
 * earlier message — the inset preview. Built by the chat thread
 * screen by looking the reply target up in the loaded
 * message list; [ChatBubble] just renders it.
 *
 * The target is resolved *live* (not snapshotted into the payload,
 * per onym-ios PR #173), so when it isn't on this device the screen
 * hands over [Unavailable] and the bubble renders a muted placeholder
 * instead of a real sender + snippet.
 *
 * Mirrors `ChatReplyQuote` from onym-ios PR #174.
 */
data class ChatReplyQuote(
    /** Quoted sender's display name (alias or BLS fingerprint). */
    val name: String,
    /** Quoted message body, rendered on a single truncated line. */
    val snippet: String,
    /** Quoted sender's accent — colors the leading bar and the name,
     *  so the quote reads as "from that person" at a glance. */
    val accent: OnymAccent,
    /** The reply target isn't in the local store (never delivered, or
     *  deleted). Renders a muted "Message unavailable" placeholder and
     *  the quote isn't tappable. */
    val isUnavailable: Boolean,
) {
    companion object {
        /** Placeholder for a reply whose target this device doesn't
         *  have. */
        val Unavailable = ChatReplyQuote(
            name = "",
            snippet = "",
            accent = OnymAccent.Blue,
            isUnavailable = true,
        )
    }
}

/**
 * Resolve the reply quote for one [message], if it replies to another.
 * The target is looked up *live* in [messagesById] (the current
 * thread snapshot, keyed by [ChatMessage.id]):
 *   - found → quote carries the target's sender name + accent + the
 *     target body as a one-line snippet;
 *   - not on this device (never delivered / deleted) →
 *     [ChatReplyQuote.Unavailable] placeholder.
 * Returns `null` for a non-reply message.
 *
 * Pure function — the screen calls it inside a `remember(...)` and
 * feeds the result to each bubble, so a unit test exercises the
 * resolution policy without standing up Compose. Mirrors
 * `ChatThreadViewController.replyQuote(for:)` from onym-ios PR #174;
 * on iOS the controller owns this, on Android the screen does.
 */
internal fun resolveReplyQuote(
    message: ChatMessage,
    messagesById: Map<UUID, ChatMessage>,
    memberProfiles: Map<String, MemberProfile>,
): ChatReplyQuote? {
    val targetId = message.replyToMessageId ?: return null
    val target = messagesById[targetId] ?: return ChatReplyQuote.Unavailable
    return ChatReplyQuote(
        name = senderName(target.senderBlsPubkeyHex, memberProfiles),
        snippet = target.body,
        accent = OnymAccent.forSender(target.senderBlsPubkeyHex),
        isUnavailable = false,
    )
}

/**
 * Build the per-message reply quote map over [messages] + the group's
 * [memberProfiles]. Only reply messages get an entry; non-replies are
 * absent (the bubble shows no quote). Pure function so the screen can
 * `remember(messages, memberProfiles)` it alongside the sender
 * displays. Mirrors [buildSenderDisplays].
 */
internal fun buildReplyQuotes(
    messages: List<ChatMessage>,
    memberProfiles: Map<String, MemberProfile>,
): Map<UUID, ChatReplyQuote> {
    val byId = messages.associateBy { it.id }
    val out = HashMap<UUID, ChatReplyQuote>()
    for (message in messages) {
        val quote = resolveReplyQuote(message, byId, memberProfiles) ?: continue
        out[message.id] = quote
    }
    return out
}
