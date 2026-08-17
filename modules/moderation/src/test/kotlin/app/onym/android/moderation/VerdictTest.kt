package app.onym.android.moderation

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The attached verdict is what makes an in-app appeal reachable while
 * banned: `POST /v1/cases/{caseId}/appeal` needs a case id, [BanState]
 * carries none, and the ban gate replaces the whole shell — so without
 * this the appeal vertical is unreachable for the one person who needs
 * it.
 *
 * Field names are the reference authority's camelCase wire form
 * (`authority/src/types.rs`, `#[serde(rename_all = "camelCase")]`),
 * and the Android profile's gate always attaches the document
 * (`android/src/enforcement.rs`, `ban_state`).
 */
class VerdictTest {

    private fun ban(verdictJson: String?): BanState =
        ModerationJson.json.decodeFromString(
            BanState.serializer(),
            """
            {
              "verdictRef": "v1",
              "authorityContact": "appeals@authority.example"
              ${verdictJson?.let { ""","verdict": $it""" } ?: ""}
            }
            """.trimIndent(),
        )

    private val full = """
        {
          "verdictVersion": 1,
          "caseId": "case-42",
          "authority": "onym:component:authority",
          "mandateRef": "abc123",
          "accusedKeys": ["onym:key:aa"],
          "deviceBinding": "device-1",
          "classId": "csam",
          "disposition": "ban",
          "marks": {"case-open": false, "banned": true},
          "banExpires": "2026-12-01T00:00:00Z",
          "reasoning": "Verified report from two independent parties.",
          "appealDeadline": "2026-09-01T00:00:00Z",
          "decidedAt": "2026-08-01T00:00:00Z",
          "signature": "c2ln",
          "final": false
        }
    """.trimIndent()

    @Test
    fun decodesTheReferenceAuthoritysWireShape() {
        val verdict = Verdict.from(ban(full).verdict)

        assertNotNull(verdict)
        assertEquals("case-42", verdict!!.caseId)
        assertEquals("abc123", verdict.mandateRef)
        assertEquals("csam", verdict.classId)
        assertEquals("2026-09-01T00:00:00Z", verdict.appealDeadline)
        assertEquals("Verified report from two independent parties.", verdict.reasoning)
        // `final` on the wire, `isFinal` in Kotlin — `final` is a hard
        // keyword, so a rename rather than a backtick at every use.
        assertEquals(false, verdict.isFinal)
    }

    /** No verdict attached: the ban screen degrades to the URL-only
     *  form it had before, never to a blank one. */
    @Test
    fun anAbsentVerdict_isNull() {
        assertNull(Verdict.from(ban(null).verdict))
    }

    /** An authority that adds fields must not brick the ban screen —
     *  `ModerationJson` ignores unknown keys. */
    @Test
    fun unknownFields_areIgnored() {
        val verdict = Verdict.from(
            ban("""{"caseId":"case-7","somethingNew":{"nested":true},"reasoning":"x"}""").verdict,
        )
        assertEquals("case-7", verdict?.caseId)
    }

    /** Optional fields absent — the minimum a verdict can be and still
     *  give the accused a route. */
    @Test
    fun onlyACaseId_isEnough() {
        val verdict = Verdict.from(ban("""{"caseId":"case-7"}""").verdict)
        assertEquals("case-7", verdict?.caseId)
        assertNull(verdict?.appealDeadline)
    }

    /** Garbage, or a document with no case id, yields null rather than
     *  an exception on the gate path: there is nothing to appeal
     *  against, and the screen still has to render. */
    @Test
    fun anUnparseableOrCaseIdLessVerdict_isNull() {
        assertNull(Verdict.from(JsonPrimitive("not an object")))
        assertNull(Verdict.from(ban("""{"reasoning":"no case id here"}""").verdict))
        assertNull(Verdict.from(ban("""{"caseId":""}""").verdict))
    }
}
