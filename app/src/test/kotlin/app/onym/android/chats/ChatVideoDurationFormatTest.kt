package app.onym.android.chats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [formatVideoDuration] lives in ChatBubble.kt (the chats UI half that
 * stayed in :app when the domain half moved to :chats-core), so its
 * coverage stays here too — split out of ChatVideoAttachmentTest, which
 * moved to modules/chats-core with the wire-format subjects.
 */
class ChatVideoDurationFormatTest {

    @Test
    fun formatVideoDuration_formatsAsMinutesSeconds() {
        assertEquals("0:00", formatVideoDuration(0.0))
        assertEquals("0:09", formatVideoDuration(9.4))
        assertEquals("1:07", formatVideoDuration(67.0))
        assertEquals("10:00", formatVideoDuration(600.0))
        assertEquals("0:00", formatVideoDuration(-3.0))
    }
}
