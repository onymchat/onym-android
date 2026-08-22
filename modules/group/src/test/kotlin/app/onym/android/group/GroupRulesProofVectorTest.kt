package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * A fixed proof document, pinned byte for byte.
 *
 * This test exists on Android and cannot exist on iOS, and the reason
 * is worth writing down: BouncyCastle's Ed25519 is deterministic per
 * RFC 8032, while CryptoKit's signing is randomized — the same key over
 * the same bytes gives a different signature each time, all valid. So
 * the iOS side can only assert that a foreign signature *verifies*,
 * where this side can assert the exact bytes it produces.
 *
 * That makes this the anchor for the pair. The values below were
 * derived from an independent RFC 8032 implementation, not read back
 * out of BouncyCastle — a vector copied from the code it checks pins
 * nothing — and the signature here is the one `GroupRulesVectorTest` on
 * iOS feeds to its verifier. If either platform's `statement()` moves
 * by a byte, one of the two tests fails.
 */
class GroupRulesProofVectorTest {

    private val seed = ByteArray(32) { 0x07 }
    private val groupId = ByteArray(32) { 0x1a }
    private val rules = "Be kind. No links."

    /** 96 hex characters, as a BLS pubkey renders. The suffix in the
     *  pinned filename is its first twelve. */
    private val memberKey = "ab12".repeat(24)

    @Test
    fun `the fixed seed derives the expected public key`() {
        assertEquals(
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c",
            publicKey().toHex(),
        )
    }

    @Test
    fun `signing the statement produces the expected bytes`() {
        assertEquals(
            "02ffd354bf379e72127a8b3893f1ae27d93dd5afeb4c38860fdeffd02dcffeb2" +
                "8f77c467506e80e99704db1780d074855ea9c30883f1bac7ab250246718d2001",
            signature().toHex(),
        )
    }

    @Test
    fun `the exported document is byte-identical to the pinned form`() {
        val proof = GroupRulesProof.of(group(), memberKey)!!
        val json = Json.parseToJsonElement(proof.json()).jsonObject
        val member = json["member"]!!.jsonObject
        val carried = json["rules"]!!.jsonObject

        assertEquals(groupId.toHex(), json["group"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(publicKey().toHex(), member["sending_public_key"]!!.jsonPrimitive.content)
        assertEquals(signature().toHex(), member["signature"]!!.jsonPrimitive.content)
        assertEquals(
            "440518f597c71a23fe7d99980df8c2156ac86dcc7f5b49493a4d403819b16473",
            carried["sha256"]!!.jsonPrimitive.content,
        )
        assertEquals("onym-rules-proof-maple-garden-bob-ab12ab12ab12.json", proof.suggestedFileName)
    }

    @Test
    fun `the statement is the domain then three fixed-length fields`() {
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), publicKey())
        assertEquals(19 + 32 + 32 + 32, statement.size)
        assertEquals("onym-group-rules-v1", String(statement.copyOfRange(0, 19)))
        assertEquals(groupId.toHex(), statement.copyOfRange(19, 51).toHex())
        assertEquals(GroupRules.hash(rules).toHex(), statement.copyOfRange(51, 83).toHex())
        assertEquals(publicKey().toHex(), statement.copyOfRange(83, 115).toHex())
    }

    private fun publicKey(): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    private fun signature(): ByteArray {
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), publicKey())
        return Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(seed, 0))
            update(statement, 0, statement.size)
        }.generateSignature()
    }

    private fun group() = ChatGroup(
        id = groupId.toHex(),
        ownerIdentityId = UUID.randomUUID().toString(),
        name = "Maple Garden",
        groupSecret = ByteArray(32) { 1 },
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = mapOf(
            memberKey to MemberProfile(
                alias = "Bob",
                inboxPublicKey = ByteArray(32) { 0xAA.toByte() },
                sendingPubkey = publicKey(),
                rulesHash = GroupRules.hash(rules),
                rulesSignature = signature(),
                rulesText = rules,
            ),
        ),
        epoch = 0UL,
        salt = ByteArray(32) { 2 },
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = "ff",
        invitationMessage = rules,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
