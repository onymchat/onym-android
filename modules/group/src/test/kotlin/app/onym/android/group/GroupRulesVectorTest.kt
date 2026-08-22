package app.onym.android.group

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Golden vectors for the bytes iOS has to reproduce, plus the two
 * boundary rules that decide whether a link is usable at all.
 *
 * These live in the same PR as the format rather than with the rest of
 * the tests, because a stack can land partially and these bytes are a
 * cross-platform contract: [GroupRules.statement] is what the other
 * implementation is written against, and a fixture is a cheaper
 * specification than prose.
 *
 * Every constant below is the same constant `GroupRulesVectorTests`
 * asserts in onym-ios. If one of them has to change, the wire format
 * changed, and the domain string carries a version for exactly that.
 */
class GroupRulesVectorTest {

    /** A 32-byte Ed25519 seed, fixed. Not a real identity's. */
    private val seed = ByteArray(32) { 0x07 }
    private val groupId = ByteArray(32) { it.toByte() }
    private val rules = "Be kind. No links."

    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
    private val publicKey: ByteArray = privateKey.generatePublicKey().encoded

    @Test
    fun vector_rulesHash() {
        // SHA-256 of the canonical text's UTF-8, and nothing else — no
        // length prefix, no domain, no normalisation beyond trimming.
        assertEquals(
            "440518f597c71a23fe7d99980df8c2156ac86dcc7f5b49493a4d403819b16473",
            GroupRules.hash(rules).hex(),
        )
    }

    @Test
    fun vector_publicKeyFromTheFixedSeed() {
        assertEquals(
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c",
            publicKey.hex(),
        )
    }

    @Test
    fun vector_statementBytes() {
        // The preimage, byte for byte: 19-byte domain, then three
        // 32-byte fields in this order.
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), publicKey)
        assertEquals(
            "domain string, unversioned changes to which must break this",
            "onym-group-rules-v1",
            statement.copyOfRange(0, 19).toString(Charsets.UTF_8),
        )
        assertEquals(groupId.hex(), statement.copyOfRange(19, 51).hex())
        assertEquals(GroupRules.hash(rules).hex(), statement.copyOfRange(51, 83).hex())
        assertEquals(publicKey.hex(), statement.copyOfRange(83, 115).hex())
        assertEquals(115, statement.size)
    }

    /**
     * The signature iOS pins as "produced by an independent RFC 8032
     * implementation" is this one. Ed25519 signing is deterministic, so
     * on this side the vector holds in both directions: the bytes we
     * produce, and that they verify. CryptoKit's signing is randomized,
     * which is why iOS can only assert the second half.
     *
     * If [GroupRules.statement] ever disagrees with its counterpart by
     * one byte, this fails and the wire format has changed.
     */
    @Test
    fun vector_theSignatureOverTheseBytes() {
        val expected =
            "d1b32ae2d65faad6f3867c7a47dec90a1c040ccbcb14e70cfbf5c4ce29229eb9" +
                "c39055e12bacbfb46c3c83940fd5fed61a9747a5c2ef8fc6468db5fc9421810e"
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), publicKey)
        val signature = Ed25519Signer().apply {
            init(true, privateKey)
            update(statement, 0, statement.size)
        }.generateSignature()
        assertEquals(expected, signature.hex())
        assertTrue(GroupRules.isAgreement(signature, rules, groupId, publicKey))
    }

    @Test
    fun aSignatureOverOtherRules_doesNotVerify() {
        val statement = GroupRules.statement(groupId, GroupRules.hash("Other rules."), publicKey)
        val signature = Ed25519Signer().apply {
            init(true, privateKey)
            update(statement, 0, statement.size)
        }.generateSignature()
        assertFalse(GroupRules.isAgreement(signature, rules, groupId, publicKey))
    }

    @Test
    fun aSignatureForAnotherGroup_doesNotVerifyHere() {
        // `group_id` is in the statement so an acceptance collected for
        // a permissive group can't be shown as agreement to a stricter
        // one whose rules happen to read the same. Dropping the field
        // would leave every positive vector green.
        val other = ByteArray(32) { (it + 1).toByte() }
        val statement = GroupRules.statement(other, GroupRules.hash(rules), publicKey)
        val signature = Ed25519Signer().apply {
            init(true, privateKey)
            update(statement, 0, statement.size)
        }.generateSignature()

        assertTrue(GroupRules.isAgreement(signature, rules, other, publicKey))
        assertFalse(GroupRules.isAgreement(signature, rules, groupId, publicKey))
    }

    @Test
    fun aSignatureCannotBeReAttributedToAnotherJoiner() {
        // `joiner_sending_pub` names the signer inside the signed bytes.
        // Without it, this signature would verify for whoever presented
        // it — the lift-and-reuse the doc says the field prevents.
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), publicKey)
        val signature = Ed25519Signer().apply {
            init(true, privateKey)
            update(statement, 0, statement.size)
        }.generateSignature()
        val someoneElse = Ed25519PrivateKeyParameters(ByteArray(32) { 0x09 }, 0)
            .generatePublicKey().encoded

        assertFalse(GroupRules.isAgreement(signature, rules, groupId, someoneElse))
    }

    // ---- The budget the rules share with the group name ----

    @Test
    fun theRulesBudget_isSharedWithTheGroupName() {
        // The doc's "1500 bytes of rules alongside a 30-character CJK
        // name overruns" — the reason `MAX_BYTES` can't be raised
        // without re-measuring the pair. Asserted against a real
        // encoder, at the level the budget was measured for.
        val atCap = "則".repeat(GroupRules.MAX_BYTES / 3)
        val latinName = IntroCapability(
            introPublicKey = ByteArray(32) { 0x44 },
            groupId = groupId,
            groupName = "x".repeat(30),
            rules = atCap,
        ).toAppLink()
        val cjkName = IntroCapability(
            introPublicKey = ByteArray(32) { 0x44 },
            groupId = groupId,
            groupName = "則".repeat(30),
            rules = atCap,
        ).toAppLink()

        assertTrue("the budget holds for a Latin name", encodesAtLevelM(latinName))
        assertFalse("and is spent by a CJK one", encodesAtLevelM(cjkName))
    }

    /** Level M, which is the level `MAX_BYTES` was measured against. */
    private fun encodesAtLevelM(value: String): Boolean = runCatching {
        QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            1,
            1,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }.isSuccess

    // ---- Canonicalization across platforms ----

    @Test
    fun canonical_trimsExactlyTheSetIosTrims() {
        // Every codepoint of `CharacterSet.whitespacesAndNewlines`, one
        // at a time. NBSP rides in on text pasted from a web page, so a
        // platform that kept one of these would hash different bytes and
        // the founder would read a genuine agreement as a signature that
        // doesn't check out.
        for (c in GroupRules.TRIMMED_CODEPOINTS) {
            assertEquals(
                "U+%04X must be trimmed on both platforms".format(c.code),
                rules,
                GroupRules.canonical("$c$rules$c"),
            )
        }
    }

    @Test
    fun canonical_doesNotDelegateToKotlinsOwnTrim() {
        // The two codepoints where `String.trim()` and Foundation
        // disagree, and the reason the set is written out rather than
        // named. U+0085 (NEL) is trimmed by Foundation and kept by
        // `Char.isWhitespace`; U+001C-U+001F are the inverse. Both
        // directions are a hash mismatch on a link that crossed
        // platforms, which is why neither side may use its default.
        val withNel = "\u0085$rules\u0085"
        assertEquals(rules, GroupRules.canonical(withNel))
        assertNotEquals(rules, withNel.trim())

        val withSeparator = "\u001C$rules\u001C"
        assertEquals(withSeparator, GroupRules.canonical(withSeparator))
        assertNotEquals(withSeparator, withSeparator.trim())
    }

    @Test
    fun canonical_leavesInnerTextExactlyAsWritten() {
        // Only the ends. Inner shape is the founder's, and it is what
        // the joiner reads and signs.
        val shaped = "One.  Two.\n\n  Three."
        assertEquals(shaped, GroupRules.canonical("  $shaped  "))
    }

    // ---- The cap, in the unit the wire spends ----

    @Test
    fun theCap_isBytesNotCharacters() {
        // A character cap would admit this: 500 grapheme clusters and
        // ~12.5 KB, many times the QR ceiling it exists to defend.
        val family = "👨‍👩‍👧‍👦"
        val emoji = family.repeat(500)
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size > 12_000)
        assertThrows(IllegalArgumentException::class.java) {
            IntroCapability(
                introPublicKey = ByteArray(32) { 0x44 },
                groupId = groupId,
                rules = emoji,
            )
        }
    }

    @Test
    fun rulesAtTheByteCap_areAccepted() {
        val capability = IntroCapability(
            introPublicKey = ByteArray(32) { 0x44 },
            groupId = groupId,
            rules = "則".repeat(GroupRules.MAX_BYTES / 3),
        )
        assertEquals(GroupRules.MAX_BYTES, capability.rules!!.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun overLongRulesOnTheWire_areRejectedNotTruncated() {
        // The mint-time cap is not a defence: a hostile link never
        // passes through it. Truncating would have someone sign rules
        // that end mid-sentence.
        val json = "{\"intro_pub\":\"${b64(ByteArray(32) { 0x44 })}\"," +
            "\"group_id\":\"${b64(groupId)}\"," +
            "\"rules\":\"${"x".repeat(GroupRules.MAX_BYTES + 1)}\"}"
        assertThrows(InvalidIntroCapability::class.java) {
            IntroCapability.decode(urlSafe(json.toByteArray(Charsets.UTF_8)))
        }
    }

    // ---- Pairing ----

    @Test
    fun halfAnAgreement_isRejected() {
        // A signature with nothing naming what it covers can't be
        // checked; a hash with no signature is a claim, not a proof.
        assertThrows(IllegalArgumentException::class.java) {
            request(hash = GroupRules.hash(rules), signature = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(hash = null, signature = ByteArray(64) { 0x55 })
        }
    }

    // ---- Helpers ----

    private fun request(hash: ByteArray?, signature: ByteArray?) = JoinRequestPayload(
        joinerInboxPublicKey = ByteArray(32) { 0xAA.toByte() },
        joinerBlsPublicKey = null,
        joinerLeafHash = null,
        joinerSendingPublicKey = publicKey,
        joinerDisplayLabel = "Bob",
        groupId = groupId,
        rulesHash = hash,
        rulesSignature = signature,
    )

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun urlSafe(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
