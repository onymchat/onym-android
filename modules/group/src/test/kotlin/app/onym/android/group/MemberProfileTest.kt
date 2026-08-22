package app.onym.android.group

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Round-trip vectors for [MemberProfile]. Cross-checks the snake_case
 * JSON shape + base64 byte encoding so onym-ios receivers decode
 * Android-emitted profiles bit-for-bit.
 *
 * Mirrors `MemberProfileTests.swift` from onym-ios.
 */
@OptIn(ExperimentalSerializationApi::class)
class MemberProfileTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun encode_emitsSnakeCaseAndBase64() {
        val inboxPub = ByteArray(32) { 0xAB.toByte() }
        val sendingPub = ByteArray(32) { 0xCD.toByte() }
        val profile = MemberProfile(
            alias = "Alice",
            inboxPublicKey = inboxPub,
            sendingPubkey = sendingPub,
        )

        val text = json.encodeToString(MemberProfile.serializer(), profile)

        assertTrue("alias literal", text.contains("\"alias\":\"Alice\""))
        assertTrue(
            "snake_case key",
            text.contains("\"inbox_public_key\":\"${Base64.getEncoder().encodeToString(inboxPub)}\""),
        )
        assertTrue(
            "sending_pubkey snake_case key",
            text.contains("\"sending_pubkey\":\"${Base64.getEncoder().encodeToString(sendingPub)}\""),
        )
    }

    @Test
    fun decode_roundTripsRawBytes() {
        val inboxPub = ByteArray(32) { (it * 7 and 0xFF).toByte() }
        val sendingPub = ByteArray(32) { (it * 13 and 0xFF).toByte() }
        val original = MemberProfile(
            alias = "Bob",
            inboxPublicKey = inboxPub,
            sendingPubkey = sendingPub,
        )

        val text = json.encodeToString(MemberProfile.serializer(), original)
        val decoded = json.decodeFromString(MemberProfile.serializer(), text)

        assertEquals("Bob", decoded.alias)
        assertArrayEquals(inboxPub, decoded.inboxPublicKey)
        assertArrayEquals(sendingPub, decoded.sendingPubkey)
        assertEquals(original, decoded)
    }

    @Test
    fun emptyAlias_roundTrips() {
        val profile = MemberProfile(
            alias = "",
            inboxPublicKey = ByteArray(32),
            sendingPubkey = ByteArray(32),
        )
        val text = json.encodeToString(MemberProfile.serializer(), profile)
        val decoded = json.decodeFromString(MemberProfile.serializer(), text)
        assertEquals("", decoded.alias)
        assertArrayEquals(ByteArray(32), decoded.inboxPublicKey)
        assertArrayEquals(ByteArray(32), decoded.sendingPubkey)
    }

    // ─── length validation (PR A3) ────────────────────────────────

    @Test
    fun constructor_rejectsWrongSizedSendingPubkey() {
        assertThrows(IllegalArgumentException::class.java) {
            MemberProfile(
                alias = "x",
                inboxPublicKey = ByteArray(32),
                sendingPubkey = ByteArray(31),
            )
        }
    }

    @Test
    fun constructor_rejectsWrongSizedInboxPublicKey() {
        // Bonus length check landed alongside the sending_pubkey
        // addition — the pre-PR-A3 type had no validation at all and
        // would silently accept a 31-byte X25519 key.
        assertThrows(IllegalArgumentException::class.java) {
            MemberProfile(
                alias = "x",
                inboxPublicKey = ByteArray(31),
                sendingPubkey = ByteArray(32),
            )
        }
    }

    @Test
    fun decode_rejectsWrongSizedSendingPubkey() {
        val text = """
            {
              "alias": "x",
              "inbox_public_key": "${b64(ByteArray(32))}",
              "sending_pubkey": "${b64(ByteArray(31))}"
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(MemberProfile.serializer(), text)
        }
    }

    @Test
    fun decode_rejectsMissingSendingPubkey() {
        val text = """
            {
              "alias": "x",
              "inbox_public_key": "${b64(ByteArray(32))}"
            }
        """.trimIndent()
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString(MemberProfile.serializer(), text)
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    // ---- the agreement kept on the member ----

    @Test
    fun aProfileThatGainedAnAgreement_isNotTheProfileWithoutIt() {
        // Left out of equals, a group whose only change is the evidence
        // a founder decided on would be conflated by the snapshots flow
        // and never re-emitted — the DB would have it and the screen
        // would not.
        val bare = profile()
        val agreed = profile(
            rulesHash = GroupRules.hash("Be kind."),
            rulesSignature = ByteArray(64) { 0x05 },
            rulesText = "Be kind.",
        )

        assertNotEquals(bare, agreed)
        assertNotEquals(bare.hashCode(), agreed.hashCode())
        // And the text is part of it, not decoration beside the bytes.
        assertNotEquals(
            agreed,
            agreed.copy(
                rulesHash = GroupRules.hash("Be cruel."),
                rulesText = "Be cruel.",
            ),
        )
    }

    @Test
    fun aPeersBadAgreement_dropsTheFieldAndNotTheMessage() {
        // `MemberProfile` is decoded straight off the wire inside
        // `GroupInvitationPayload.memberProfiles`, and the dispatcher
        // turns any throw into a dropped message — so a strict decode
        // would cost a joiner the whole group over one roster entry.
        val decoded = json.decodeFromString(
            MemberProfile.serializer(),
            wireProfile(
                rulesHash = GroupRules.hash("What they signed."),
                rulesSignature = ByteArray(64) { 0x05 },
                rulesText = "What the founder holds.",
            ),
        )

        assertEquals("Bob", decoded.alias)
        assertNull("the text isn't the one the hash names", decoded.rulesText)
        assertArrayEquals(GroupRules.hash("What they signed."), decoded.rulesHash)
    }

    @Test
    fun aPeersWrongSizedAgreementBytes_dropTheWholeTriple() {
        val decoded = json.decodeFromString(
            MemberProfile.serializer(),
            wireProfile(
                rulesHash = ByteArray(32) { 0x04 },
                rulesSignature = ByteArray(65) { 0x05 },
                rulesText = null,
            ),
        )

        assertNull(decoded.rulesHash)
        assertNull(decoded.rulesSignature)
    }

    @Test
    fun aPeersPaddedRulesText_isNotStored() {
        // `fits` and `hash` both canonicalize, so a megabyte of leading
        // whitespace in front of the real rules would pass every check
        // while the raw string got persisted.
        val padded = " ".repeat(GroupRules.MAX_BYTES + 10) + "Be kind."
        val decoded = json.decodeFromString(
            MemberProfile.serializer(),
            wireProfile(
                rulesHash = GroupRules.hash("Be kind."),
                rulesSignature = ByteArray(64) { 0x05 },
                rulesText = padded,
            ),
        )

        assertNull(decoded.rulesText)
    }

    @Test
    fun aGoodAgreement_survivesTheRoundTrip() {
        val profile = profile(
            rulesHash = GroupRules.hash("Be kind."),
            rulesSignature = ByteArray(64) { 0x05 },
            rulesText = "Be kind.",
        )

        val decoded = json.decodeFromString(
            MemberProfile.serializer(),
            json.encodeToString(MemberProfile.serializer(), profile),
        )

        assertEquals(profile, decoded)
        assertEquals("Be kind.", decoded.rulesText)
    }

    private fun wireProfile(
        rulesHash: ByteArray?,
        rulesSignature: ByteArray?,
        rulesText: String?,
    ): String = buildString {
        append("{\"alias\":\"Bob\",")
        append("\"inbox_public_key\":\"${Base64.getEncoder().encodeToString(ByteArray(32))}\",")
        append("\"sending_pubkey\":\"${Base64.getEncoder().encodeToString(ByteArray(32))}\"")
        rulesHash?.let {
            append(",\"rules_hash\":\"${Base64.getEncoder().encodeToString(it)}\"")
        }
        rulesSignature?.let {
            append(",\"rules_signature\":\"${Base64.getEncoder().encodeToString(it)}\"")
        }
        rulesText?.let { append(",\"rules_text\":${'"'}$it${'"'}") }
        append("}")
    }

    @Test
    fun ourWordsCannotBeStoredBesideAHashOfTheirs() {
        // The pairing that made the retained proof unreadable: a text
        // the hash doesn't name means every later reader verifies a
        // signature against bytes it was never made over.
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                rulesHash = GroupRules.hash("What they signed."),
                rulesSignature = ByteArray(64) { 0x05 },
                rulesText = "What we hold.",
            )
        }
        // A hash with no text is allowed, and is exactly the
        // signed-something-we-can't-check case.
        profile(rulesHash = GroupRules.hash("Theirs."), rulesSignature = ByteArray(64) { 0x05 })
    }

    @Test
    fun anUncappedRulesText_isRefused() {
        val tooLong = "x".repeat(GroupRules.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            profile(
                rulesHash = GroupRules.hash(tooLong),
                rulesSignature = ByteArray(64) { 0x05 },
                rulesText = tooLong,
            )
        }
    }

    @Test
    fun malformedAgreementBytes_areRefusedAtTheBoundary() {
        // Profiles arrive inside peer-announced snapshots. A wrong-sized
        // signature that got as far as verification would read as "they
        // didn't agree", which is a different claim than "this is not a
        // profile".
        assertThrows(IllegalArgumentException::class.java) {
            profile(rulesHash = ByteArray(31), rulesSignature = ByteArray(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(rulesHash = ByteArray(32), rulesSignature = ByteArray(63))
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(rulesHash = ByteArray(32), rulesSignature = null)
        }
    }

    @Test
    fun agreedToRules_checksTheRetainedTextRatherThanTheGroupsCurrentOne() {
        // The whole reason the text is stored: a founder editing the
        // rules afterwards must not turn every member who agreed into
        // one who apparently didn't.
        val groupId = ByteArray(32) { 0x11 }
        val key = Ed25519PrivateKeyParameters(ByteArray(32) { 0x07 }, 0)
        val sendingPub = key.generatePublicKey().encoded
        val statement = GroupRules.statement(groupId, GroupRules.hash("Be kind."), sendingPub)
        val signature = Ed25519Signer().apply {
            init(true, key)
            update(statement, 0, statement.size)
        }.generateSignature()

        val member = MemberProfile(
            alias = "Bob",
            inboxPublicKey = ByteArray(32) { 0xAB.toByte() },
            sendingPubkey = sendingPub,
            rulesHash = GroupRules.hash("Be kind."),
            rulesSignature = signature,
            rulesText = "Be kind.",
        )

        assertTrue(member.agreedToRules(groupId))
        assertFalse(member.agreedToRules(ByteArray(32) { 0x22 }))
        assertFalse(
            member.copy(
                rulesHash = GroupRules.hash("Be cruel."),
                rulesText = "Be cruel.",
            ).agreedToRules(groupId),
        )
        assertFalse(profile().agreedToRules(groupId))
    }

    private fun profile(
        rulesHash: ByteArray? = null,
        rulesSignature: ByteArray? = null,
        rulesText: String? = null,
    ) = MemberProfile(
        alias = "Alice",
        inboxPublicKey = ByteArray(32) { 0xAB.toByte() },
        sendingPubkey = ByteArray(32) { 0xCD.toByte() },
        rulesHash = rulesHash,
        rulesSignature = rulesSignature,
        rulesText = rulesText,
    )
}
