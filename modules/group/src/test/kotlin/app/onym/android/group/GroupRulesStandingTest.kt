package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

/**
 * Where a member stands, and what an export of that standing may say.
 *
 * The negative cases carry the weight. A signature that verifies is one
 * property; one that keeps verifying after the group, the signer, or a
 * comma has changed would make the whole thing decorative.
 */
class GroupRulesStandingTest {

    private val groupId = ByteArray(32) { 0x1a }
    private val rules = "Be kind. No links."
    private val adminHex = "ff".repeat(48)

    // MARK: standings

    @Test
    fun `a group with no rules has no standing to report`() {
        assertEquals(GroupRulesStanding.NO_RULES, standingOf(unsigned(), rules = null))
    }

    @Test
    fun `blank rules are no rules`() {
        assertEquals(GroupRulesStanding.NO_RULES, standingOf(unsigned(), rules = "   \n "))
    }

    @Test
    fun `the founder wrote them rather than failing to sign`() {
        assertEquals(
            GroupRulesStanding.AUTHOR,
            standingOf(unsigned(), key = adminHex, rules = rules),
        )
    }

    @Test
    fun `the founder is matched case-insensitively`() {
        // `adminPubkeyHex` and the roster key are hex from two sources;
        // a case difference must not demote the author to "didn't sign".
        assertEquals(
            GroupRulesStanding.AUTHOR,
            standingOf(unsigned(), key = adminHex, rules = rules, adminHex = adminHex.uppercase()),
        )
    }

    @Test
    fun `a verified signature over the current rules is signed`() {
        assertEquals(GroupRulesStanding.SIGNED, standingOf(signer().profile, rules = rules))
    }

    @Test
    fun `a member with nothing stored did not sign`() {
        assertEquals(
            GroupRulesStanding.DID_NOT_SIGN,
            standingOf(unsigned(), key = "dd", rules = rules),
        )
    }

    @Test
    fun `rules changed since they signed says so rather than failing`() {
        val standing = standingOf(signer().profile, rules = "$rules And no photos.")
        assertEquals(GroupRulesStanding.SIGNED_EARLIER_VERSION, standing)
        assertTrue("an earlier version is still a signature that checks out", standing!!.isProven)
    }

    @Test
    fun `a stored signature with no text is not a missing one`() {
        // Reachable: the admitting device records the agreement with the
        // rules as they stood, so a founder clearing them between a
        // request and its approval leaves bytes with nothing to check
        // them against. Reported as "didn't sign" that would be a claim,
        // and a false one.
        val profile = signer().profile.copy(rulesText = null)
        assertEquals(GroupRulesStanding.UNKNOWN_RULES, standingOf(profile, rules = rules))
    }

    @Test
    fun `bytes that do not verify are not a missing signature`() {
        val profile = signer().profile.copy(rulesSignature = ByteArray(64))
        val standing = standingOf(profile, rules = rules)
        assertEquals(GroupRulesStanding.DOES_NOT_VERIFY, standing)
        assertFalse(standing!!.isProven)
    }

    @Test
    fun `a signature lifted from another member does not verify here`() {
        // The signer is named inside the signed bytes, so a signature
        // taken from one member and presented under another's key
        // fails. Two locals, because `signer()` mints a fresh seed per
        // call — the previous single-expression form read as a no-op
        // and only worked by accident of that.
        val victim = signer()
        val impostor = signer()
        val transplanted = impostor.profile.copy(
            rulesSignature = victim.profile.rulesSignature,
        )
        assertEquals(
            GroupRulesStanding.DOES_NOT_VERIFY,
            standingOf(transplanted, rules = rules),
        )
    }

    @Test
    fun `a key swapped under a good signature does not verify either`() {
        // The other direction: the signature is genuine, the key it is
        // presented with is somebody else's.
        val signed = signer()
        val other = signer()
        val swapped = signed.profile.copy(sendingPubkey = other.publicKey)
        assertEquals(GroupRulesStanding.DOES_NOT_VERIFY, standingOf(swapped, rules = rules))
    }

    @Test
    fun `clearing the rules does not delete what people signed`() {
        // `invitationMessage` is mutable. Reading group state before the
        // stored bytes turned every agreement ever made into "this group
        // asks nothing of anyone" — evidence deleted by editing a text
        // field.
        val standing = standingOf(signer().profile, rules = null)
        assertEquals(GroupRulesStanding.SIGNED_EARLIER_VERSION, standing)
        assertTrue(standing!!.isProven)
    }

    @Test
    fun `a group type with no join approval marks nobody as having declined`() {
        // Anarchy and one-on-one have no join request and no admin, so
        // AUTHOR — keyed on `adminPubkeyHex`, null for both — would have
        // marked every member, including whoever wrote the rules, as
        // having refused. Unreachable today; pinned so it stays right.
        val group = group(rules = rules, members = mapOf("aa" to unsigned()))
            .copy(groupType = SepGroupType.ANARCHY, adminPubkeyHex = null)
        assertEquals(GroupRulesStanding.NOT_COLLECTED, group.rulesStanding("aa"))
    }

    @Test
    fun `a profile under a key that is not in the roster has no standing and no proof`() {
        val group = group(rules = rules, members = mapOf("cc" to signer().profile))
        assertNull(group.rulesStanding(adminHex))
        assertNull(GroupRulesProof.of(group, adminHex))
    }

    // MARK: export

    @Test
    fun `the exported bytes actually verify`() {
        assertExportVerifiesItself(groupRules = rules)
    }

    @Test
    fun `the export verifies even after the group changed its rules`() {
        // The case the export exists to get right, and the one a string
        // comparison can't prove: shipping today's wording beside an old
        // signature produces a document that fails its own instructions.
        assertExportVerifiesItself(groupRules = "$rules And no photos.")
    }

    @Test
    fun `the export carries the signed wording and names the divergence`() {
        val today = "$rules And no photos."
        val json = documentOf(group(rules = today, members = mapOf("cc" to signer().profile)), "cc")
        val carried = json["rules"]!!.jsonObject
        assertEquals(rules, carried["text"]!!.jsonPrimitive.content)
        assertFalse(carried["matches_current_rules"]!!.jsonPrimitive.content.toBoolean())
        // What the group says now lives in the group block, carried for
        // every standing rather than only for divergence.
        assertEquals(
            today,
            json["group"]!!.jsonObject["current_rules"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the readme names the shapes the document can take`() {
        // Three claims the file used to make and not keep: that the
        // rules text is always what the member signed (false for the
        // author), that the block is always present (false when nothing
        // was signed), and that a comparison is always available.
        val json = documentOf(group(rules = rules, members = mapOf("cc" to signer().profile)), "cc")
        val readme = json["_readme"]!!.jsonArray.joinToString("\n") { it.jsonPrimitive.content }
        assertTrue(readme.contains("signed is false"))
        assertTrue(readme.contains("rules is absent"))
        assertTrue(readme.contains("group.current_rules"))
        assertTrue(readme.contains("verdict"))
    }

    @Test
    fun `an unproven standing ships its bytes and says they did not check out`() {
        // Reversed deliberately from "ship nothing": a document that
        // asserts a signature does not verify while withholding the
        // signature asks to be taken on faith, which is the one thing
        // this file is not for. `signed` and `note` carry the verdict;
        // the bytes let a reader reach their own.
        val signed = signer()
        val profile = signed.profile.copy(rulesSignature = ByteArray(64))
        val json = documentOf(group(rules = rules, members = mapOf("mm" to profile)), "mm")
        val member = json["member"]!!.jsonObject

        assertFalse(member["signed"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("the bytes behind the claim ship with it", member["signature"] != null)
        assertTrue(member["sending_public_key"] != null)
        assertEquals(
            "and the wording they cover, so the check can be repeated",
            rules,
            json["rules"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertTrue(member["note"]!!.jsonPrimitive.content.contains("does not verify"))
    }

    @Test
    fun `a member who signed nothing still learns what was asked`() {
        // The group's own rules ride in the group block whatever the
        // standing. Without them a document about an agreement never
        // stated what was on the table.
        val json = documentOf(group(rules = rules, members = mapOf("dd" to unsigned())), "dd")
        assertNull("nothing was signed, so there is no signed wording", json["rules"])
        assertEquals(
            rules,
            json["group"]!!.jsonObject["current_rules"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the authors export carries their words and claims no signature`() {
        val group = group(rules = rules, members = mapOf(adminHex to unsigned("Alice")))
        val json = documentOf(group, adminHex)
        assertFalse(json["member"]!!.jsonObject["signed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "a document about the person who wrote the rules must contain them",
            rules,
            json["rules"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the readme names what the signature does not cover`() {
        // Without it a reader concludes "the member with this BLS key
        // agreed" — a pairing this app asserts, not one the signature
        // carries.
        val json = documentOf(group(rules = rules, members = mapOf("cc" to signer().profile)), "cc")
        val readme = json["_readme"]!!.jsonArray.joinToString("\n") { it.jsonPrimitive.content }
        assertTrue(readme.contains("does NOT cover"))
        assertTrue(readme.contains("alias"))
        assertTrue(readme.contains("bls_public_key"))
        assertTrue(readme.contains("onym-group-rules-v1"))
    }

    @Test
    fun `the export is stable between runs`() {
        val proof = GroupRulesProof.of(
            group(rules = rules, members = mapOf("cc" to signer().profile)),
            "cc",
        )!!
        assertEquals(proof.json(), proof.json())
    }

    // MARK: filenames

    @Test
    fun `the file name says who and which group`() {
        val key = "ab12".repeat(24)
        val proof = GroupRulesProof.of(
            group(rules = rules, name = "Maple  Garden!", members = mapOf(key to signer().profile)),
            key,
        )!!
        assertEquals("onym-rules-proof-maple-garden-bob-7054d02d3974.json", proof.suggestedFileName)
    }

    @Test
    fun `a hostile roster key cannot reach outside the export directory`() {
        // Roster keys are arbitrary JSON object keys — nothing checks
        // their shape on decode — and this one reaches a filesystem
        // path. On iOS three levels of `..` put the write in the app
        // container. The row is reachable too: a group with rules gives
        // DID_NOT_SIGN a tappable mark.
        val key = "../../../Documents/pwned"
        val name = GroupRulesProof.of(
            group(rules = rules, members = mapOf(key to unsigned())),
            key,
        )!!.suggestedFileName

        assertFalse("no parent traversal survives", name.contains(".."))
        assertFalse("and no separator either", name.contains("/"))
        assertTrue(name.all { it.code < 128 })
    }

    @Test
    fun `names that reduce to nothing still give each member their own file`() {
        // Cyrillic and CJK survive neither folding nor the ASCII filter,
        // so the readable stem collapses and every member of such a
        // group would land on one filename.
        val group = group(
            rules = rules,
            name = "Дом на Тверской",
            members = mapOf(
                "aa11bb22cc33" to unsigned("Борис"),
                "dd44ee55ff66" to unsigned("Анна"),
            ),
        )
        val boris = GroupRulesProof.of(group, "aa11bb22cc33")!!.suggestedFileName
        val anna = GroupRulesProof.of(group, "dd44ee55ff66")!!.suggestedFileName
        assertNotEquals(boris, anna)
        assertEquals("onym-rules-proof-group-rules-378d594d7bb4.json", boris)
    }

    @Test
    fun `a key too short to distinguish members is hashed rather than truncated`() {
        val group = group(
            rules = rules,
            members = mapOf("../a" to unsigned("Bo"), "../b" to unsigned("Bo")),
        )
        val first = GroupRulesProof.of(group, "../a")!!.suggestedFileName
        val second = GroupRulesProof.of(group, "../b")!!.suggestedFileName
        assertNotEquals(first, second)
        assertFalse(first.contains(".."))
    }

    @Test
    fun `an unbounded alias cannot make a name the filesystem refuses`() {
        // `groupName` and the alias arrive off the wire with no cap of
        // their own. Past NAME_MAX the write throws, and that member's
        // export is stuck on an error for good.
        val group = group(
            rules = rules,
            name = "g".repeat(400),
            members = mapOf("ab12".repeat(24) to unsigned("a".repeat(400))),
        )
        val name = GroupRulesProof.of(group, "ab12".repeat(24))!!.suggestedFileName
        assertTrue("filesystems cap a component at 255", name.length <= 255)
    }

    // MARK: helpers

    private fun assertExportVerifiesItself(groupRules: String) {
        val signed = signer()
        val json = documentOf(group(rules = groupRules, members = mapOf("cc" to signed.profile)), "cc")
        val member = json["member"]!!.jsonObject
        val carried = json["rules"]!!.jsonObject
        val gid = json["group"]!!.jsonObject["id"]!!.jsonPrimitive.content.hexToBytes()
        val key = member["sending_public_key"]!!.jsonPrimitive.content.hexToBytes()
        val signature = member["signature"]!!.jsonPrimitive.content.hexToBytes()

        // The byte counts the readme instructs the reader to assume.
        assertEquals(32, gid.size)
        assertEquals(32, key.size)
        assertEquals(64, signature.size)

        // Rebuilt from the document's own fields, the way its `_readme`
        // tells a stranger to.
        val statement = "onym-group-rules-v1".toByteArray() +
            gid +
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(carried["text"]!!.jsonPrimitive.content.toByteArray()) +
            key
        val verifier = Ed25519Signer().apply {
            init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(key, 0))
            update(statement, 0, statement.size)
        }
        assertTrue(
            "the document must verify by its own instructions",
            verifier.verifySignature(signature),
        )
    }

    private fun documentOf(group: ChatGroup, blsHex: String) =
        Json.parseToJsonElement(GroupRulesProof.of(group, blsHex)!!.json()).jsonObject

    private fun standingOf(
        member: MemberProfile,
        key: String = "cc",
        rules: String?,
        adminHex: String? = null,
    ): GroupRulesStanding? =
        group(rules = rules, members = mapOf(key to member), adminHex = adminHex)
            .rulesStanding(key)

    private fun unsigned(alias: String = "Dana") = MemberProfile(
        alias = alias,
        inboxPublicKey = ByteArray(32) { 0xAA.toByte() },
        sendingPubkey = ByteArray(32) { 0xEE.toByte() },
    )

    private class Signed(val publicKey: ByteArray, val profile: MemberProfile)

    private fun signer(): Signed {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub = priv.generatePublicKey().encoded
        val statement = GroupRules.statement(groupId, GroupRules.hash(rules), pub)
        val signature = Ed25519Signer().apply {
            init(true, priv)
            update(statement, 0, statement.size)
        }.generateSignature()
        return Signed(
            pub,
            MemberProfile(
                alias = "Bob",
                inboxPublicKey = ByteArray(32) { 0xAA.toByte() },
                sendingPubkey = pub,
                rulesHash = GroupRules.hash(rules),
                rulesSignature = signature,
                rulesText = rules,
            ),
        )
    }

    private fun group(
        rules: String?,
        name: String = "Maple Garden",
        members: Map<String, MemberProfile> = emptyMap(),
        adminHex: String? = null,
    ) = ChatGroup(
        id = groupId.joinToString("") { "%02x".format(it) },
        ownerIdentityId = UUID.randomUUID().toString(),
        name = name,
        groupSecret = ByteArray(32) { 1 },
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = members,
        epoch = 0UL,
        salt = ByteArray(32) { 2 },
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = adminHex ?: this.adminHex,
        invitationMessage = rules,
    )

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
