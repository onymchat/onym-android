package app.onym.android.group

import app.onym.android.chats.ChatMessagePayload
import app.onym.android.chats.ChatReceiptPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Round-trip vectors for [MemberRemovalPayload] plus trial-decode
 * disjointness against every other inbox payload — the dispatcher's
 * routing depends on no payload decoding as another under
 * `ignoreUnknownKeys`.
 *
 * Wire format authored here (Android-first); onym-ios mirrors it.
 */
class MemberRemovalPayloadTest {

    /** Same settings the sender uses ([GroupMemberRemover]). */
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val victimHex = "bb".repeat(48)
    private val commitment = ByteArray(32) { 0xDD.toByte() }
    private val secretNew = ByteArray(32) { 0x11 }
    private val saltNew = ByteArray(32) { 0x22 }

    private fun memberVariant() = MemberRemovalPayload(
        version = 1,
        groupId = groupId,
        removedBlsHex = victimHex,
        commitment = commitment,
        epoch = 3uL,
        sentAtMillis = 1_700_000_000_000L,
        groupSecretNew = secretNew,
        saltNew = saltNew,
    )

    private fun victimVariant() =
        memberVariant().copy(groupSecretNew = null, saltNew = null)

    @Test
    fun roundTrip_memberVariant() {
        val encoded = json.encodeToString(MemberRemovalPayload.serializer(), memberVariant())
        val decoded = json.decodeFromString(MemberRemovalPayload.serializer(), encoded)
        assertEquals(memberVariant(), decoded)
        assertArrayEquals(secretNew, decoded.groupSecretNew)
        assertArrayEquals(saltNew, decoded.saltNew)
    }

    @Test
    fun roundTrip_victimVariant_omitsSecretKeysEntirely() {
        val encoded = json.encodeToString(MemberRemovalPayload.serializer(), victimVariant())
        // Not just null-valued — the keys must be absent from the wire.
        assertFalse(encoded.contains("group_secret_new"))
        assertFalse(encoded.contains("salt_new"))
        val decoded = json.decodeFromString(MemberRemovalPayload.serializer(), encoded)
        assertNull(decoded.groupSecretNew)
        assertNull(decoded.saltNew)
        assertEquals(victimVariant(), decoded)
    }

    @Test
    fun decode_wireVector() {
        // Frozen wire shape — iOS decodes this exact text.
        val text = """
            {
              "removal_version": 1,
              "removal_group_id": "${b64(groupId)}",
              "removal_member_bls_hex": "$victimHex",
              "removal_commitment": "${b64(commitment)}",
              "removal_epoch": 3,
              "removal_sent_at_millis": 1700000000000,
              "group_secret_new": "${b64(secretNew)}",
              "salt_new": "${b64(saltNew)}"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(MemberRemovalPayload.serializer(), text)
        assertEquals(memberVariant(), decoded)
    }

    // ─── trial-decode disjointness (both directions) ───────────────

    @Test
    fun removal_doesNotDecodeAsAnyOtherInboxPayload() {
        val wire = json.encodeToString(MemberRemovalPayload.serializer(), memberVariant())
        assertNull(tryDecode(GroupInviteOfferPayload.serializer(), wire))
        assertNull(tryDecode(GroupStateRefreshRequest.serializer(), wire))
        assertNull(tryDecode(MemberAnnouncementPayload.serializer(), wire))
        assertNull(tryDecode(GroupInvitationPayload.serializer(), wire))
        assertNull(tryDecode(GroupAvatarPayload.serializer(), wire))
        assertNull(tryDecode(GroupNamePayload.serializer(), wire))
        assertNull(tryDecode(ChatReceiptPayload.serializer(), wire))
        assertNull(tryDecode(ChatMessagePayload.serializer(), wire))
    }

    @Test
    fun otherInboxPayloads_doNotDecodeAsRemoval() {
        val announcement = json.encodeToString(
            MemberAnnouncementPayload.serializer(),
            MemberAnnouncementPayload(
                version = 1,
                groupId = groupId,
                newMember = MemberAnnouncementPayload.AnnouncedMember(
                    blsPub = ByteArray(48) { 0xBB.toByte() },
                    inboxPub = ByteArray(32) { 0xCC.toByte() },
                    alias = "Bob",
                    sendingPub = ByteArray(32) { 0xDE.toByte() },
                ),
                adminAlias = "Alice",
                commitment = commitment,
                epoch = 3uL,
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), announcement))

        val invitation = json.encodeToString(
            GroupInvitationPayload.serializer(),
            GroupInvitationPayload(
                version = 1,
                groupId = groupId,
                groupSecret = secretNew,
                name = "Family",
                members = emptyList(),
                epoch = 3uL,
                salt = saltNew,
                commitment = commitment,
                tierRaw = app.onym.android.chain.SepTier.SMALL.rawValue,
                groupTypeRaw = app.onym.android.chain.SepGroupType.TYRANNY.wireValue,
                adminPubkeyHex = victimHex,
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), invitation))

        // The types tried AFTER removal in the dispatcher's chain are
        // the ones a removal false-positive would steal, so they matter
        // most in this direction.
        val avatar = json.encodeToString(
            GroupAvatarPayload.serializer(),
            GroupAvatarPayload(
                version = 1,
                groupId = groupId,
                senderBlsHex = victimHex,
                sentAtMillis = 1L,
                avatar = ByteArray(8) { 0x7 },
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), avatar))

        val name = json.encodeToString(
            GroupNamePayload.serializer(),
            GroupNamePayload(
                version = 1,
                groupId = groupId,
                senderBlsHex = victimHex,
                sentAtMillis = 1L,
                name = "Family",
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), name))

        val receipt = json.encodeToString(
            ChatReceiptPayload.serializer(),
            ChatReceiptPayload(
                version = 1,
                groupId = groupId,
                kind = ChatReceiptPayload.Kind.DELIVERED,
                senderBlsPubkeyHex = victimHex,
                messageIds = listOf(java.util.UUID.randomUUID()),
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), receipt))

        val chat = json.encodeToString(
            ChatMessagePayload.serializer(),
            ChatMessagePayload(
                version = 1,
                messageId = java.util.UUID.randomUUID(),
                groupId = groupId,
                senderBlsPubkeyHex = victimHex,
                sentAtMillis = 1L,
                variant = app.onym.android.chats.ChatMessageVariant.Tyranny(body = "hi"),
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), chat))

        val offer = json.encodeToString(
            GroupInviteOfferPayload.serializer(),
            GroupInviteOfferPayload(
                groupId = groupId,
                groupName = "Family",
                introPublicKey = ByteArray(32) { 0x11 },
                inviterAlias = "Admin",
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), offer))

        val refresh = json.encodeToString(
            GroupStateRefreshRequest.serializer(),
            GroupStateRefreshRequest(
                groupId = groupId,
                requesterInboxPublicKey = ByteArray(32) { 0x12 },
                requesterBlsPublicKey = ByteArray(48) { 0x13 },
            ),
        )
        assertNull(tryDecode(MemberRemovalPayload.serializer(), refresh))
    }

    @Test
    fun constructor_rejectsWrongSizes() {
        assertTrue(
            runCatching { memberVariant().copy(groupId = ByteArray(31)) }.isFailure,
        )
        assertTrue(
            runCatching { memberVariant().copy(removedBlsHex = "ab") }.isFailure,
        )
        assertTrue(
            runCatching { memberVariant().copy(commitment = ByteArray(16)) }.isFailure,
        )
        assertTrue(
            runCatching { memberVariant().copy(groupSecretNew = ByteArray(16)) }.isFailure,
        )
        assertTrue(
            runCatching { memberVariant().copy(saltNew = ByteArray(16)) }.isFailure,
        )
    }

    private fun <T> tryDecode(
        serializer: kotlinx.serialization.KSerializer<T>,
        text: String,
    ): T? = try {
        json.decodeFromString(serializer, text)
    } catch (_: Throwable) {
        null
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
