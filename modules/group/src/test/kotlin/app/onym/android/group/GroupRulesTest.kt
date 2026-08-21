package app.onym.android.group

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The statement, and the ways agreeing to it must fail.
 *
 * Mirrors `GroupRulesTests` in onym-ios; the vectors are the same on
 * purpose, because a signature made on either platform has to verify on
 * the other.
 */
class GroupRulesTest {

    private val groupId = ByteArray(32) { 0x11 }
    private val otherGroupId = ByteArray(32) { 0x22 }
    private val rules = "Be kind. No spam."

    @Test
    fun canonical_trimsTheEndsAndNothingElse() {
        // The founder wrote this and a joiner is agreeing to it, so
        // collapsing inner whitespace would mean signing something other
        // than what was displayed.
        assertEquals("a  b\n\nc", GroupRules.canonical("  \na  b\n\nc\n  "))
    }

    @Test
    fun normalized_treatsBlankAsNoRules() {
        // A group with no rules asks for no agreement, and an empty
        // string must not become a thing to sign.
        assertNull(GroupRules.normalized(null))
        assertNull(GroupRules.normalized(""))
        assertNull(GroupRules.normalized("   \n\t "))
        assertEquals("x", GroupRules.normalized("  x  "))
    }

    @Test
    fun hash_ignoresTheWhitespaceCanonicalizationRemoves() {
        assertTrue(GroupRules.hash(rules).contentEquals(GroupRules.hash("\n  $rules  \n")))
    }

    @Test
    fun hash_isSensitiveToTheTextItself() {
        // Changing a comma invalidates the agreement — that is the point
        // of hashing the text rather than naming it.
        assertFalse(GroupRules.hash(rules).contentEquals(GroupRules.hash("Be kind, No spam.")))
    }

    @Test
    fun statement_isDomainSeparated() {
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), ByteArray(32) { 0x33 })
        assertEquals(GroupRules.DOMAIN.length + 96, statement.size)
        assertTrue(
            statement.copyOfRange(0, GroupRules.DOMAIN.length)
                .contentEquals(GroupRules.DOMAIN.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun agreement_verifiesForTheJoinerWhoSignedTheseRules() {
        val joiner = Joiner()
        assertTrue(
            GroupRules.isAgreement(
                signature = joiner.sign(groupId, rules),
                rules = rules,
                groupId = groupId,
                joinerSendingPublicKey = joiner.publicKey,
            ),
        )
    }

    @Test
    fun agreement_survivesTheTrimmingThatCanonicalizationDoes() {
        // A trailing newline from a text field carries no meaning, and
        // agreement must not fail on an invisible character.
        val joiner = Joiner()
        assertTrue(
            GroupRules.isAgreement(
                signature = joiner.sign(groupId, "  $rules\n"),
                rules = rules,
                groupId = groupId,
                joinerSendingPublicKey = joiner.publicKey,
            ),
        )
    }

    @Test
    fun agreement_failsWhenTheRulesChanged() {
        val joiner = Joiner()
        assertFalse(
            GroupRules.isAgreement(
                signature = joiner.sign(groupId, rules),
                rules = "$rules And no links.",
                groupId = groupId,
                joinerSendingPublicKey = joiner.publicKey,
            ),
        )
    }

    @Test
    fun agreement_doesNotTransferToAnotherGroupWithTheSameRules() {
        // Why `group_id` is inside the signed bytes: an acceptance
        // collected for a permissive group must not be showable as
        // agreement to a stricter group whose text happens to match.
        val joiner = Joiner()
        assertFalse(
            GroupRules.isAgreement(
                signature = joiner.sign(groupId, rules),
                rules = rules,
                groupId = otherGroupId,
                joinerSendingPublicKey = joiner.publicKey,
            ),
        )
    }

    @Test
    fun agreement_cannotBeReattributedToAnotherJoiner() {
        // Why the signer names itself inside the statement: a signature
        // lifted from one request must not be presentable as another
        // person's.
        val signer = Joiner()
        val other = Joiner()
        assertFalse(
            GroupRules.isAgreement(
                signature = signer.sign(groupId, rules),
                rules = rules,
                groupId = groupId,
                joinerSendingPublicKey = other.publicKey,
            ),
        )
    }

    @Test
    fun agreement_refusesGarbageRatherThanThrowing() {
        // "We can't check it" is the same answer as "it doesn't check
        // out" to every caller, and a malformed key arriving on the wire
        // must not take the request down with it.
        assertFalse(
            GroupRules.isAgreement(
                signature = ByteArray(64),
                rules = rules,
                groupId = groupId,
                joinerSendingPublicKey = ByteArray(3),
            ),
        )
        assertFalse(
            GroupRules.isAgreement(
                signature = ByteArray(7),
                rules = rules,
                groupId = groupId,
                joinerSendingPublicKey = Joiner().publicKey,
            ),
        )
    }

    /** A joiner's Ed25519 identity, the same key the request announces
     *  as `joinerSendingPublicKey`. */
    private class Joiner {
        private val secret = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey: ByteArray = secret.generatePublicKey().encoded

        fun sign(groupId: ByteArray, rules: String): ByteArray {
            val statement = GroupRules.statement(groupId, GroupRules.hash(rules), publicKey)
            return Ed25519Signer().apply {
                init(true, secret)
                update(statement, 0, statement.size)
            }.generateSignature()
        }
    }
}
