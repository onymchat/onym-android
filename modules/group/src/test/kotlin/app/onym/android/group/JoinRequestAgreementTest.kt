package app.onym.android.group

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

/**
 * The verdict a founder decides on, and the ways it must not be
 * flattered.
 *
 * The five outcomes exist because a boolean collapses cases whose
 * remedies differ, so each is pinned here — including the two ways a
 * dishonest request could try to read as agreement.
 *
 * Mirrors `JoinRequestAgreementTests` in onym-ios.
 */
class JoinRequestAgreementTest {

    private val groupId = ByteArray(32) { 0x11 }
    private val rules = "Be kind. No spam."

    @Test
    fun noRules_asksForNothing() {
        assertEquals(
            JoinRequestApprover.RulesAgreement.NOT_REQUIRED,
            rulesAgreementFor(payload(), rules = null),
        )
    }

    @Test
    fun blankRules_alsoAskForNothing() {
        assertEquals(
            JoinRequestApprover.RulesAgreement.NOT_REQUIRED,
            rulesAgreementFor(payload(), rules = "   \n "),
        )
    }

    @Test
    fun signedTheseRules_readsAsAgreed() {
        val joiner = Joiner()
        assertEquals(
            JoinRequestApprover.RulesAgreement.AGREED,
            rulesAgreementFor(joiner.request(groupId, rules), rules),
        )
    }

    @Test
    fun anOlderClient_readsAsNotSigned() {
        // Not the same as a signature that failed: this one is fixed by
        // asking again, and is every joiner on a build that predates
        // rules.
        assertEquals(
            JoinRequestApprover.RulesAgreement.NOT_SIGNED,
            rulesAgreementFor(payload(), rules),
        )
    }

    @Test
    fun signedSomeOtherText_readsAsUnknownRatherThanAsAgreement() {
        // A real signature over text this device doesn't hold. Nothing
        // here can check it, so the verdict says that instead of calling
        // it agreement to "another version".
        val joiner = Joiner()
        val request = joiner.request(groupId, "Be kind.")

        assertEquals(
            JoinRequestApprover.RulesAgreement.UNKNOWN_RULES,
            rulesAgreementFor(request, rules),
        )
    }

    @Test
    fun randomBytesOverAnUnknownHash_readTheSameAsARealOne() {
        // The point of the rename. 64 random bytes with any hash that
        // isn't ours land here too — so a forgery gains nothing by
        // avoiding our hash, and the verdict promises nothing it hasn't
        // checked.
        val forged = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerSendingPublicKey = ByteArray(32) { 0x03 },
            joinerDisplayLabel = "Mallory",
            groupId = groupId,
            rulesHash = ByteArray(32) { 0x7E },
            rulesSignature = ByteArray(64) { 0x7F },
        )

        assertEquals(
            JoinRequestApprover.RulesAgreement.UNKNOWN_RULES,
            rulesAgreementFor(forged, rules),
        )
    }

    @Test
    fun aSignatureThatDoesNotVerify_readsAsInvalid() {
        // The hash says "these rules", the signature doesn't back it —
        // the only one of the failures that should give a founder pause
        // about the request itself.
        val joiner = Joiner()
        val request = joiner.request(groupId, rules).let {
            JoinRequestPayload(
                joinerInboxPublicKey = it.joinerInboxPublicKey,
                joinerSendingPublicKey = it.joinerSendingPublicKey,
                joinerDisplayLabel = it.joinerDisplayLabel,
                groupId = it.groupId,
                rulesHash = it.rulesHash,
                rulesSignature = ByteArray(64) { 0x09 },
            )
        }

        assertEquals(
            JoinRequestApprover.RulesAgreement.INVALID,
            rulesAgreementFor(request, rules),
        )
    }

    @Test
    fun aStolenSignature_doesNotPassAsTheSendersOwn() {
        // Lifted from someone else's request and re-sent under this
        // joiner's keys: the signer names itself inside the statement,
        // so it can't be re-attributed.
        val signer = Joiner()
        val thief = Joiner()
        val stolen = signer.request(groupId, rules)
        val request = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerSendingPublicKey = thief.publicKey,
            joinerDisplayLabel = "Mallory",
            groupId = groupId,
            rulesHash = stolen.rulesHash,
            rulesSignature = stolen.rulesSignature,
        )

        assertEquals(
            JoinRequestApprover.RulesAgreement.INVALID,
            rulesAgreementFor(request, rules),
        )
    }

    @Test
    fun anHonestHashOverDishonestBytes_isNotEnough() {
        // Echoing back the right hash with a signature over something
        // else must not pass: verification runs against *our* rules, and
        // the hash they sent only ever explains a failure.
        val joiner = Joiner()
        val elsewhere = joiner.request(ByteArray(32) { 0x22 }, rules)
        val request = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerSendingPublicKey = joiner.publicKey,
            joinerDisplayLabel = "Bob",
            groupId = groupId,
            rulesHash = GroupRules.hash(rules),
            rulesSignature = elsewhere.rulesSignature,
        )

        assertEquals(
            JoinRequestApprover.RulesAgreement.INVALID,
            rulesAgreementFor(request, rules),
        )
    }

    @Test
    fun storedEvidence_saysWhichOfTheThreeItIs() {
        // The member-side counterpart of the verdict: "didn't agree" and
        // "agreed to something this device can't check" are different
        // facts about a person, and a boolean would lose the second one
        // — which is the only thing the retained bytes are good for.
        val joiner = Joiner()
        val request = joiner.request(groupId, rules)
        val agreed = MemberProfile(
            alias = "Bob",
            inboxPublicKey = ByteArray(32) { 0x01 },
            sendingPubkey = request.joinerSendingPublicKey,
            rulesHash = request.rulesHash,
            rulesSignature = request.rulesSignature,
            rulesText = rules,
        )

        assertEquals(MemberProfile.StoredAgreement.VERIFIED, agreed.storedAgreement(groupId))
        assertEquals(
            MemberProfile.StoredAgreement.UNCHECKABLE,
            agreed.copy(rulesText = null).storedAgreement(groupId),
        )
        assertEquals(
            MemberProfile.StoredAgreement.NONE,
            agreed.copy(rulesHash = null, rulesSignature = null, rulesText = null)
                .storedAgreement(groupId),
        )
        assertEquals(
            MemberProfile.StoredAgreement.NOT_VERIFIED,
            agreed.copy(rulesSignature = ByteArray(64) { 0x09 }).storedAgreement(groupId),
        )
    }

    private fun payload(
        sendingPublicKey: ByteArray = ByteArray(32) { 0x03 },
        rulesHash: ByteArray? = null,
        rulesSignature: ByteArray? = null,
    ) = JoinRequestPayload(
        joinerInboxPublicKey = ByteArray(32) { 0x01 },
        joinerSendingPublicKey = sendingPublicKey,
        joinerDisplayLabel = "Bob",
        groupId = groupId,
        rulesHash = rulesHash,
        rulesSignature = rulesSignature,
    )

    private inner class Joiner {
        private val secret = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey: ByteArray = secret.generatePublicKey().encoded

        fun request(groupId: ByteArray, rules: String): JoinRequestPayload {
            val hash = GroupRules.hash(rules)
            val statement = GroupRules.statement(groupId, hash, publicKey)
            val signature = Ed25519Signer().apply {
                init(true, secret)
                update(statement, 0, statement.size)
            }.generateSignature()
            return JoinRequestPayload(
                joinerInboxPublicKey = ByteArray(32) { 0x01 },
                joinerSendingPublicKey = publicKey,
                joinerDisplayLabel = "Bob",
                groupId = groupId,
                rulesHash = hash,
                rulesSignature = signature,
            )
        }
    }
}
