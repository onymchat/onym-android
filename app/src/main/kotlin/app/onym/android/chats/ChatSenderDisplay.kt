package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.group.MemberProfile
import app.onym.android.group.OnymAccent
import java.util.UUID

/**
 * Resolved sender presentation for one bubble — computed by the chat
 * thread screen (which alone knows the group's member profiles + the
 * message ordering) and handed to [ChatBubble]. The bubble stays a
 * dumb renderer: it doesn't look up aliases or hash pubkeys itself.
 *
 * [accent] is derived from the sender's BLS pubkey via
 * [OnymAccent.forSender], so it's a stable per-person color rather
 * than anything tied to the (spoofable) alias.
 *
 * Mirrors `ChatSenderDisplay` from onym-ios PR #162.
 */
data class ChatSenderDisplay(
    /** Alias if the member set one, else a short BLS-fingerprint
     *  fallback. Only rendered when [showNameHeader] is true. */
    val name: String,
    /** Per-sender color. Tints the incoming bubble; fills the
     *  outgoing bubble; colors the name header. */
    val accent: OnymAccent,
    /** Show the name header above this bubble. The screen sets this
     *  only at the start of a run of consecutive same-sender incoming
     *  messages, and never in 1-on-1 groups (one other person — naming
     *  them on every run is noise). */
    val showNameHeader: Boolean,
) {
    companion object {
        /** Neutral default used by the bubble when no sender info is
         *  supplied (older call sites / a missing display). Blue, no
         *  header — matches the pre-sender-differentiation look. */
        val Unknown = ChatSenderDisplay(
            name = "",
            accent = OnymAccent.Blue,
            showNameHeader = false,
        )
    }
}

/**
 * Recompute the per-message sender presentation from [messages] (in
 * display order) + [memberProfiles]. A name header shows only at the
 * *start* of a run of consecutive same-sender messages, only for
 * incoming messages (own messages are obvious from alignment + color),
 * and never in 1-on-1 groups (a single other person doesn't need to be
 * named on every run). Color is hashed from the BLS pubkey, so it's
 * stable per-person and independent of the alias.
 *
 * Pure function — the production composable calls it inside a
 * `remember(messages, memberProfiles)` and feeds the result to each
 * bubble, so a unit test exercises the run-grouping policy without
 * standing up Compose. Mirrors
 * `ChatThreadViewController.rebuildSenderDisplays` from onym-ios
 * PR #162; on iOS the controller owns this, on Android the screen does.
 */
internal fun buildSenderDisplays(
    messages: List<ChatMessage>,
    memberProfiles: Map<String, MemberProfile>,
): Map<UUID, ChatSenderDisplay> {
    val displays = HashMap<UUID, ChatSenderDisplay>(messages.size)
    var previousSender: String? = null
    for (message in messages) {
        val isRunStart = message.senderBlsPubkeyHex != previousSender
        val showHeader = isRunStart &&
            message.direction == MessageDirection.INCOMING &&
            message.groupType != SepGroupType.ONE_ON_ONE
        displays[message.id] = ChatSenderDisplay(
            name = senderName(message.senderBlsPubkeyHex, memberProfiles),
            accent = OnymAccent.forSender(message.senderBlsPubkeyHex),
            showNameHeader = showHeader,
        )
        previousSender = message.senderBlsPubkeyHex
    }
    return displays
}

/**
 * The sender's display name: their self-asserted alias when set, else
 * a short BLS-pubkey fingerprint (`BLS ` + the first 8 hex chars).
 * Mirrors the `ChatMembersScreen` fallback so an unnamed member reads
 * consistently in both places.
 */
internal fun senderName(
    blsPubkeyHex: String,
    memberProfiles: Map<String, MemberProfile>,
): String {
    val alias = memberProfiles[blsPubkeyHex]?.alias ?: ""
    if (alias.isNotEmpty()) return alias
    return "BLS " + blsPubkeyHex.take(8)
}
