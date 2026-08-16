package app.onym.android.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCodecTest {
    private val json = ModerationJson.json

    /** Absent optionals must be omitted, never `null` — the backend's
     * serde types treat the two differently. */
    @Test
    fun `requests omit absent optionals`() {
        val enroll = json.encodeToString(
            EnrollmentRequest.serializer(),
            EnrollmentRequest(
                userKey = "onym:key:aabb",
                timestamp = "2026-08-08T12:00:00Z",
                challenge = "Y2g=",
                integrityToken = null,
                signature = "c2ln",
            ),
        )
        assertFalse(enroll, enroll.contains("null"))
        assertFalse(enroll, enroll.contains("integrityToken"))

        val gate = json.encodeToString(
            GateCheckRequest.serializer(),
            GateCheckRequest(
                userKey = "onym:key:aabb",
                mandateRef = null,
                timestamp = "2026-08-08T12:00:00Z",
                challenge = "Y2g=",
                integrityToken = "token",
                signature = "c2ln",
            ),
        )
        assertFalse(gate, gate.contains("mandateRef"))
        assertTrue(gate, gate.contains("\"integrityToken\":\"token\""))
    }

    @Test
    fun `every gate result variant decodes from the backend shape`() {
        assertEquals(
            GateCheckResult.Clear,
            json.decodeFromString(GateCheckResult.serializer(), """{"status":"clear"}"""),
        )

        val caseOpen = json.decodeFromString(
            GateCheckResult.serializer(),
            """{"status":"caseOpen","notices":[{"caseId":"c1","authority":"onym:component:a"}]}""",
        )
        assertEquals(
            listOf("c1"),
            (caseOpen as GateCheckResult.CaseOpen).notices.map { it.caseId },
        )

        val banned = json.decodeFromString(
            GateCheckResult.serializer(),
            """{"status":"banned","ban":{
                 "verdictRef":"v1",
                 "authorityContact":"appeals@a.org",
                 "banExpires":"2026-09-08T00:00:00Z",
                 "appealUrl":"https://a.org/appeal",
                 "newHolderUrl":"https://a.org/new-holder",
                 "verdict":{"caseId":"c1"}
               }}""",
        )
        val ban = (banned as GateCheckResult.Banned).ban
        assertEquals("v1", ban.verdictRef)
        assertEquals("appeals@a.org", ban.authorityContact)
        assertEquals("https://a.org/new-holder", ban.newHolderUrl)

        val required = json.decodeFromString(
            GateCheckResult.serializer(),
            """{"status":"checkRequired","reason":"tokenInvalid"}""",
        )
        assertEquals(
            CheckRequiredReason.TOKEN_INVALID,
            (required as GateCheckResult.CheckRequired).reason,
        )
    }

    /** The persisted gate state re-encodes the same tagged shape it
     * decoded, so a relaunch reads back exactly what was served. */
    @Test
    fun `persisted gate state round-trips every variant`() {
        val states = listOf(
            GateCheckResult.Clear,
            GateCheckResult.CaseOpen(listOf(CaseNotice(caseId = "c1", authority = "a"))),
            GateCheckResult.Banned(BanState(verdictRef = "v1", authorityContact = "contact")),
            GateCheckResult.CheckRequired(CheckRequiredReason.REIDENTIFICATION_REQUIRED),
        )
        for (result in states) {
            val persisted = PersistedGateState(result, "2026-08-08T12:00:00Z")
            val encoded = json.encodeToString(PersistedGateState.serializer(), persisted)
            assertEquals(
                persisted,
                json.decodeFromString(PersistedGateState.serializer(), encoded),
            )
        }
    }

    /** Unknown extra fields must not brick the client. */
    @Test
    fun `decoding tolerates unknown fields`() {
        val result = json.decodeFromString(
            GateCheckResult.serializer(),
            """{"status":"clear","futureField":42}""",
        )
        assertEquals(GateCheckResult.Clear, result)
    }
}
