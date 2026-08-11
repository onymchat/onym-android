package app.onym.android.chats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A locally-minted, non-conversational notice rendered in the thread —
 * the membership events every member should see without anyone having
 * to type them.
 *
 * Cases carry **structured data, not prose**. The stored row holds an
 * alias and a group name, never a rendered sentence, so re-wording the
 * copy needs no migration and a device that changes language re-reads
 * history in the new one. The sentence itself is assembled in the UI
 * (`stringResource`), because this module has no `:strings` dependency
 * and text built here would be hardcoded English with no `values-ru`
 * twin — the exact gap `ChatMessage.chatListPreview` already has.
 *
 * ## Wire/disk format
 *
 * kotlinx writes the sealed hierarchy with its **default** class
 * discriminator, so a stored row looks like
 * `{"type":"member_joined","alias":"Bob"}`. Both the `type` key and the
 * [SerialName] values are a persistence format and are stable forever —
 * renaming either, or setting `classDiscriminator` to something that
 * merely reads better, orphans every row already on disk.
 *
 * Mirrors `ChatSystemEvent` in onym-ios.
 */
@Serializable
sealed interface ChatSystemEvent {

    /**
     * Someone else was admitted. Rendered to the admin (who approved)
     * and to every existing member (via `MemberAnnouncementPayload`).
     * [alias] is self-asserted by the joiner — the same trust caveat as
     * everywhere else an alias is shown.
     */
    @Serializable
    @SerialName("member_joined")
    data class MemberJoined(val alias: String) : ChatSystemEvent

    /**
     * This device's own identity was admitted — the first row in a
     * freshly materialized thread, so a brand-new chat explains itself
     * instead of rendering blank.
     */
    @Serializable
    @SerialName("you_joined")
    data class YouJoined(
        @SerialName("group_name") val groupName: String,
    ) : ChatSystemEvent
}
