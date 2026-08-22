package app.onym.android.group

import app.onym.android.design.OnymQrFit
import app.onym.android.design.onymQrFit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What crosses the wire: the link that carries the rules, and the
 * request that carries the agreement.
 *
 * The JSON keys are the contract with onym-ios — both platforms decode
 * each other's links — so they are asserted literally rather than
 * round-tripped through our own encoder alone.
 *
 * Mirrors `GroupRulesWireTests` in onym-ios.
 */
class GroupRulesWireTest {

    private val introPub = ByteArray(32) { 0x44 }
    private val groupId = ByteArray(32) { 0x11 }

    @Test
    fun link_carriesTheRulesThroughEncodeAndDecode() {
        val capability = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            groupName = "Book club",
            rules = "Be kind.",
        )

        val decoded = IntroCapability.decode(capability.encode())

        assertEquals("Be kind.", decoded.rules)
    }

    @Test
    fun link_usesTheAgreedJsonKey() {
        val capability = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            rules = "Be kind.",
        )
        val json = String(
            java.util.Base64.getUrlDecoder().decode(capability.encode()),
            Charsets.UTF_8,
        )
        assertTrue("the key is the cross-platform contract", json.contains("\"rules\""))
    }

    @Test
    fun link_omitsRulesEntirelyWhenThereAreNone() {
        // An older build must not see a null it has no reading for, and
        // the link is short enough to scan partly because of this.
        val capability = IntroCapability(introPublicKey = introPub, groupId = groupId)
        val json = String(
            java.util.Base64.getUrlDecoder().decode(capability.encode()),
            Charsets.UTF_8,
        )
        assertFalse(json.contains("\"rules\""))
    }

    @Test
    fun aLinkFromAnOlderBuild_hasNoRules() {
        // Forward compatibility in the other direction: a link minted
        // before rules existed still decodes, and its joiner is asked to
        // agree to nothing. Hand-written and pushed through the shipped
        // `decode`, because what has to tolerate the older shape is
        // `jsonFormat`, not a `Json` the test configured itself.
        val json = "{\"intro_pub\":\"${b64(introPub)}\"," +
            "\"group_id\":\"${b64(groupId)}\"," +
            "\"group_name\":\"X\"}"

        val decoded = IntroCapability.decode(urlSafe(json))

        assertNull(decoded.rules)
        assertEquals("X", decoded.groupName)
    }

    @Test
    fun rulesAtTheCap_areAccepted() {
        val atCap = "x".repeat(GroupRules.MAX_BYTES)
        val capability = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            rules = atCap,
        )
        assertEquals(GroupRules.MAX_BYTES, capability.rules?.toByteArray(Charsets.UTF_8)?.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overLongRules_areRejectedRatherThanTruncated() {
        // Truncating would have the joiner sign rules that end
        // mid-sentence; dropping the field would walk them into a group
        // whose rules they were never shown.
        IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            rules = "x".repeat(GroupRules.MAX_BYTES + 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonCanonicalRules_areRejected() {
        // The joiner signs `canonical(rules)`, so a link whose text is
        // not already canonical would have them agreeing to bytes the
        // link doesn't contain.
        IntroCapability(introPublicKey = introPub, groupId = groupId, rules = " Be kind. ")
    }

    @Test
    fun twoLinksDifferingOnlyInTheirRules_areNotEqual() {
        // The one field where a silent mismatch means someone signed
        // text they were never shown, so a cache or a `distinctBy` must
        // not treat these as one invitation.
        val a = IntroCapability(introPublicKey = introPub, groupId = groupId, rules = "Be kind.")
        val b = IntroCapability(introPublicKey = introPub, groupId = groupId, rules = "Be cruel.")

        assertNotEquals(a, b)
        assertEquals(
            a,
            IntroCapability(introPublicKey = introPub, groupId = groupId, rules = "Be kind."),
        )
    }

    @Test
    fun theCappedLinkStillEncodesAsAQrCode() {
        // Asserted against the renderer's own ladder rather than a
        // remembered capacity: a literal can't drift, but it also can't
        // notice the correction level changing under it, which is
        // exactly how a link that "fits" became a link nothing could
        // encode.
        val link = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            groupName = "x".repeat(30),
            rules = "字".repeat(GroupRules.MAX_BYTES / 3),
        ).toAppLink()

        assertNotEquals(
            "worst-case rules must still draw a QR: " +
                "${link.toByteArray(Charsets.UTF_8).size} bytes",
            OnymQrFit.NONE,
            onymQrFit(link),
        )
    }

    @Test
    fun theWorstCaseNameAndRulesTogether_stillDrawAQr() {
        // The headroom is shared with the group name, which has no cap
        // of its own. What has to hold is that the pair still renders
        // something — the step down the ladder is what buys that, and
        // the assertion says so rather than restating the ladder's own
        // definition back at itself.
        val link = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            groupName = "字".repeat(30),
            rules = "字".repeat(GroupRules.MAX_BYTES / 3),
        ).toAppLink()

        assertEquals(OnymQrFit.PLAIN, onymQrFit(link))
    }

    @Test
    fun theCap_leavesTheBadgedLevelBehind() {
        // A full-length rules link no longer fits level H, the only
        // level whose budget covers the centre badge. Pinned because it
        // means the step-down is load-bearing rather than theoretical.
        val link = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            rules = "x".repeat(GroupRules.MAX_BYTES),
        ).toAppLink()

        assertEquals(OnymQrFit.PLAIN, onymQrFit(link))
    }

    @Test
    fun aShortLinkKeepsTheBadge() {
        // The other side of the same boundary: an invite with no rules
        // still renders the branded code it always did.
        val link = IntroCapability(introPublicKey = introPub, groupId = groupId).toAppLink()

        assertEquals(OnymQrFit.BADGED, onymQrFit(link))
    }

    @Test
    fun aLinkTooLongForEveryLevel_drawsNoQrRatherThanThrowing() {
        // Not reachable through the cap — this is the renderer's floor,
        // and the contract the share screen's copyable-link fallback and
        // its gated caption both rest on.
        assertEquals(OnymQrFit.NONE, onymQrFit("x".repeat(5_000)))
    }

    @Test
    fun request_carriesTheAgreementThroughARoundTrip() {
        val payload = JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerBlsPublicKey = ByteArray(48) { 0x02 },
            joinerSendingPublicKey = ByteArray(32) { 0x03 },
            joinerDisplayLabel = "Bob",
            groupId = groupId,
            rulesHash = ByteArray(32) { 0x04 },
            rulesSignature = ByteArray(64) { 0x05 },
        )

        val json = Json.encodeToString(JoinRequestPayload.serializer(), payload)
        val decoded = Json.decodeFromString(JoinRequestPayload.serializer(), json)

        assertTrue(json.contains("\"rules_hash\""))
        assertTrue(json.contains("\"rules_signature\""))
        assertTrue(decoded.rulesHash!!.contentEquals(payload.rulesHash!!))
        assertTrue(decoded.rulesSignature!!.contentEquals(payload.rulesSignature!!))
    }

    @Test
    fun aRequestFromAnOlderBuild_decodesWithNoAgreement() {
        // Old builds degrade to "signed nothing" rather than failing to
        // decode — which is what lets this ship before the other
        // platform has it.
        val json = """
            {"joiner_inbox_pub":"${b64(ByteArray(32) { 0x01 })}",
             "joiner_sending_pub":"${b64(ByteArray(32) { 0x03 })}",
             "joiner_display_label":"Bob",
             "group_id":"${b64(groupId)}"}
        """.trimIndent()

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(JoinRequestPayload.serializer(), json)

        assertNull(decoded.rulesHash)
        assertNull(decoded.rulesSignature)
    }

    @Test(expected = IllegalArgumentException::class)
    fun halfAnAgreement_isRejected() {
        // Half an agreement is a shape neither side has a reading for.
        JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerSendingPublicKey = ByteArray(32) { 0x03 },
            joinerDisplayLabel = "Bob",
            groupId = groupId,
            rulesHash = ByteArray(32) { 0x04 },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongSizedAgreementBytes_areRejected() {
        JoinRequestPayload(
            joinerInboxPublicKey = ByteArray(32) { 0x01 },
            joinerSendingPublicKey = ByteArray(32) { 0x03 },
            joinerDisplayLabel = "Bob",
            groupId = groupId,
            rulesHash = ByteArray(31) { 0x04 },
            rulesSignature = ByteArray(64) { 0x05 },
        )
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun urlSafe(json: String): String = java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.toByteArray(Charsets.UTF_8))

}
