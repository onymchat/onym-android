package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.MemberProfile
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Eligibility + verification for the Report menu. The invariant that
 * matters most: [ReportableMessageFactory.make] accepts exactly the
 * messages whose proof verifies against the sender's stored key over
 * the preimage REBUILT from the stored row — anything else must be
 * refused before a form is shown. Mirrors iOS
 * `ReportableMessageFactoryTests`.
 */
class ReportableMessageFactoryTest {

    private val senderKeys = Ed25519KeyPairGenerator().let { generator ->
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        generator.generateKeyPair()
    }
    private val senderPublic = (senderKeys.public as Ed25519PublicKeyParameters).encoded
    private val senderBlsHex = "44".repeat(48)
    private val groupSecret = ByteArray(32) { 0x07 }
    private val groupIdHex = "aa".repeat(32)

    private val group = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = groupSecret,
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = mapOf(
            senderBlsHex to MemberProfile(
                alias = "Peer",
                inboxPublicKey = ByteArray(32) { 0x55 },
                sendingPubkey = senderPublic,
            ),
        ),
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = true,
        ownerIdentityId = "alice",
    )

    private fun sign(preimage: String): String {
        val message = preimage.toByteArray(Charsets.UTF_8)
        val signature = Ed25519Signer().apply {
            init(true, senderKeys.private as Ed25519PrivateKeyParameters)
            update(message, 0, message.size)
        }.generateSignature()
        return Base64.getEncoder().encodeToString(signature)
    }

    private fun message(
        body: String = "hello",
        proof: String? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        imageAttachment: ChatImageAttachment? = null,
    ): ChatMessage {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val sentAt = 1_785_580_800_123L
        return ChatMessage(
            id = id,
            groupId = groupIdHex,
            ownerIdentityId = "alice",
            senderBlsPubkeyHex = senderBlsHex,
            body = body,
            sentAtMillis = sentAt,
            direction = direction,
            status = MessageStatus.RECEIVED,
            groupType = SepGroupType.TYRANNY,
            imageAttachment = imageAttachment,
            moderationAuthenticityProof = proof,
        )
    }

    private fun validTextProof(body: String = "hello"): String = sign(
        ChatModerationProof.signedContent(
            messageId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            groupId = groupIdHex,
            groupSecret = groupSecret,
            sentAtMillis = 1_785_580_800_123L,
            body = body,
        ),
    )

    @Test
    fun validTextMessage_producesDisclosableEvidence() {
        val msg = message(proof = validTextProof())
        assertTrue(ReportableMessageFactory.isReportable(msg, group))
        val evidence = ReportableMessageFactory.make(msg, group)
        assertNotNull(evidence)
        assertEquals("00000000-0000-0000-0000-000000000001", evidence!!.sourceMessageId)
        assertEquals(
            "onym:key:" + senderPublic.joinToString("") { "%02x".format(it) },
            evidence.accused,
        )
        assertTrue(evidence.disclosedContent.contains("\"proof_version\":1"))
        assertTrue(evidence.images.isEmpty())
    }

    @Test
    fun proofOverTheBareBody_isNotReportable() {
        // A transferable signature (bare body, no message binding) must
        // be refused even though it is a valid signature by the sender.
        val msg = message(proof = sign("hello"))
        assertNull(ReportableMessageFactory.make(msg, group))
    }

    @Test
    fun wrongGroupSecret_isNotReportable() {
        val msg = message(proof = validTextProof())
        val otherGroup = group.copy(groupSecret = ByteArray(32) { 0x08 })
        assertNull(ReportableMessageFactory.make(msg, otherGroup))
    }

    @Test
    fun outgoingSystemVoiceVideoAlbumAndProofless_areNeverOffered() {
        assertFalse(
            ReportableMessageFactory.isReportable(
                message(proof = validTextProof(), direction = MessageDirection.OUTGOING),
                group,
            ),
        )
        assertFalse(ReportableMessageFactory.isReportable(message(proof = null), group))
        assertFalse(
            ReportableMessageFactory.isReportable(
                message(proof = validTextProof()).copy(senderBlsPubkeyHex = "ee".repeat(48)),
                group,
            ),
        )
    }

    @Test
    fun photoMessage_verifiesTheV2PreimageOverTheExactBytes() {
        val bytes = ByteArray(64) { it.toByte() }
        val plaintextSha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val attachment = ChatImageAttachment(
            sha256 = "0f".repeat(32),
            mimeType = "image/jpeg",
            byteSize = 128,
            width = 1200,
            height = 1600,
            encKey = ByteArray(32),
            blurhash = "LEHV6nWB2yk8",
            server = "https://blossom.test",
        )
        val proof = sign(
            ChatModerationProof.signedContent(
                messageId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                groupId = groupIdHex,
                groupSecret = groupSecret,
                sentAtMillis = 1_785_580_800_123L,
                body = "look",
                media = listOf(
                    ChatModerationProof.MediaCommitment(
                        blobSha256 = attachment.sha256,
                        mimeType = "image/jpeg",
                        plaintextSha256 = plaintextSha,
                        plaintextByteLength = bytes.size,
                        width = 1200,
                        height = 1600,
                    ),
                ),
            ),
        )
        val msg = message(body = "look", proof = proof, imageAttachment = attachment)

        val evidence = ReportableMessageFactory.make(msg, group, attachmentBytes = bytes)
        assertNotNull(evidence)
        assertEquals(plaintextSha, evidence!!.images.single().sha256)
        assertTrue(evidence.disclosedContent.contains("\"proof_version\":2"))

        // Bytes that don't match the signed commitment must refuse.
        assertNull(
            ReportableMessageFactory.make(msg, group, attachmentBytes = ByteArray(64) { 9 }),
        )
        // A photo without bytes in hand can't be disclosed.
        assertNull(ReportableMessageFactory.make(msg, group, attachmentBytes = null))
    }
}
