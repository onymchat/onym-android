package app.onym.android.group

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
        assertFalse(json.contains("rules"))
    }

    @Test
    fun aLinkFromAnOlderBuild_hasNoRules() {
        // Forward compatibility in the other direction: a link minted
        // before rules existed still decodes, and its joiner is asked to
        // agree to nothing.
        val older = IntroCapability(introPublicKey = introPub, groupId = groupId, groupName = "X")
        val json = Json { encodeDefaults = false }
            .encodeToString(IntroCapability.serializer(), older)

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(IntroCapability.serializer(), json)

        assertNull(decoded.rules)
    }

    @Test
    fun rulesAtTheCap_areAccepted() {
        val atCap = "x".repeat(GroupRules.MAX_LENGTH)
        val capability = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            rules = atCap,
        )
        assertEquals(GroupRules.MAX_LENGTH, capability.rules?.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overLongRules_areRejectedRatherThanTruncated() {
        // Truncating would have the joiner sign rules that end
        // mid-sentence; dropping the field would walk them into a group
        // whose rules they were never shown.
        IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            rules = "x".repeat(GroupRules.MAX_LENGTH + 1),
        )
    }

    @Test
    fun theCappedLinkFitsAQrCode() {
        // The cap is the QR code's, and the case that decides it is
        // three bytes per character.
        val cjk = "字".repeat(GroupRules.MAX_LENGTH)
        val link = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            groupName = "x".repeat(30),
            rules = cjk,
        ).toAppLink()

        assertTrue(
            "worst-case rules must still encode inside the level-M ceiling: ${link.length}",
            link.toByteArray(Charsets.UTF_8).size <= QR_BYTE_CEILING,
        )
    }

    @Test
    fun theWorstCaseNameAndRulesTogether_overrunTheQrCode() {
        // The headroom is thin and shared with the group name, which has
        // no cap of its own. Pinned so a future change to either has to
        // re-measure rather than discover this from a link that scans
        // nowhere.
        val cjk = "字".repeat(GroupRules.MAX_LENGTH)
        val link = IntroCapability(
            introPublicKey = introPub,
            groupId = groupId,
            groupName = "字".repeat(30),
            rules = cjk,
        ).toAppLink()

        assertTrue(
            "a CJK name alongside CJK rules is expected to overrun: ${link.length}",
            link.toByteArray(Charsets.UTF_8).size > QR_BYTE_CEILING,
        )
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

    private companion object {
        /** Byte-mode capacity of a version-40 QR code at correction
         *  level M, which `SettingsQRCode` renders at. */
        const val QR_BYTE_CEILING = 2331
    }
}
