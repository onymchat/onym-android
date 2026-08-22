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
 * The values below were derived from an independent RFC 8032
 * implementation, not read back out of BouncyCastle — a vector copied
 * from the code it checks pins nothing.
 *
 * This is the *second* vector in this module, and deliberately not a
 * replacement for the first. `GroupRulesVectorTest` pins the statement
 * and signature over group id `00..1f`, which is the pair iOS's own
 * vector test verifies against; this one pins the exported **document**
 * — its bytes, its key order, its `_readme` — over group id `1a`*32,
 * which that test says nothing about. Two vectors, two questions. The
 * overlap in seed and public key is the price of keeping each readable
 * on its own.
 */
class GroupRulesProofVectorTest {

    private val seed = ByteArray(32) { 0x07 }
    private val groupId = ByteArray(32) { 0x1a }
    private val rules = "Be kind. No links."

    /** 96 hex characters, as a BLS pubkey renders. The suffix in the
     *  pinned filename is a SHA-256 digest of the whole key, not a
     *  slice of it — two attacker-chosen keys sharing a prefix would
     *  otherwise share a filename. Regenerating this expects
     *  `7054d02d3974`, not `ab12ab12ab12`. */
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
        // The bytes, not a handful of fields. Key order, indentation
        // and the `_readme` wording are exactly what would drift
        // between two platforms writing "the same" document, and
        // nothing else pins them. Regenerate deliberately if the format
        // changes; a diff here is a wire-format change.
        assertEquals(GOLDEN_DOCUMENT, GroupRulesProof.of(group(), memberKey)!!.json())
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

/**
 * The exported document, byte for byte.
 *
 * Kept out of the test body so a diff on it reads as what it is: a
 * change to a format two platforms have to agree on.
 */
private val GOLDEN_DOCUMENT = """{
    "_readme": [
        "Proof of what this member agreed to in this group's rules.",
        "To verify a signature, with any Ed25519 implementation:",
        "  1. message = \"onym-group-rules-v1\" (ASCII, 19 bytes)",
        "             || group.id (32 bytes, hex above)",
        "             || SHA-256(rules.text as UTF-8) (32 bytes)",
        "             || member.sending_public_key (32 bytes, hex above)",
        "  2. check member.signature against that message and that key.",
        "",
        "How to read the fields, including where they disagree:",
        "  member.signed is this app's verdict, not evidence. A stored",
        "  signature is exported whether or not it verified here, so",
        "  you can repeat the check and disagree; member.note says what",
        "  this device concluded.",
        "  rules.text is the wording this member put their name to. For",
        "  the member who wrote the rules it is their own text and",
        "  signed is false — founders do not sign their own terms.",
        "  rules is absent entirely when this member signed nothing, or",
        "  when the wording their signature covers is not held by the",
        "  device that wrote this file.",
        "  group.current_rules is what the group asks today. Compare it",
        "  against rules.text when matches_current_rules is false. It is",
        "  absent only when the group has no rules at all any more.",
        "",
        "What the signature does NOT cover, and what you must not read",
        "out of it: alias and bls_public_key. Neither is inside the",
        "signed message. The alias is a name this member chose and can",
        "change, and the pairing of that name and that BLS key with this",
        "signature is an assertion by the app that wrote this file — not",
        "something the signature proves. What the signature proves is",
        "that the holder of sending_public_key agreed to these rules for",
        "this group. Tie that key to a person by some other means."
    ],
    "group": {
        "id": "1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a",
        "name": "Maple Garden",
        "current_rules": "Be kind. No links."
    },
    "member": {
        "alias": "Bob",
        "bls_public_key": "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12",
        "sending_public_key": "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c",
        "signature": "02ffd354bf379e72127a8b3893f1ae27d93dd5afeb4c38860fdeffd02dcffeb28f77c467506e80e99704db1780d074855ea9c30883f1bac7ab250246718d2001",
        "signed": true
    },
    "rules": {
        "text": "Be kind. No links.",
        "sha256": "440518f597c71a23fe7d99980df8c2156ac86dcc7f5b49493a4d403819b16473",
        "matches_current_rules": true
    }
}"""
