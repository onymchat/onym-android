package app.onym.android.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed form of a report filing. `Report` is the one boundary
 * object whose keys collide under case-insensitive sorting —
 * `reportId`/`reportVersion` sort BEFORE `reporter` by UTF-8 byte
 * order and AFTER it case-insensitively — so the ordering assertions
 * here are the ones that catch a canonicalization swap that every
 * other object would let slide. Mirrors iOS
 * `ModerationCanonicalOrderTests`.
 */
class ModerationReportTest {

    private fun report(signature: String = "c2ln") = ModerationReport(
        reportId = "report-1",
        reporter = "onym:key:aabb",
        reporterMandate = "ff".repeat(32),
        accused = "onym:key:ccdd",
        classId = "csam",
        evidence = listOf(
            ReportEvidenceItem(
                disclosedContent = "{\"body\":\"x\"}",
                authenticityProof = "cHJvb2Y=",
            ),
        ),
        filedAt = "2026-08-16T00:00:00Z",
        signature = signature,
    )

    @Test
    fun signingBytesOrderKeysByUtf8ByteOrder() {
        val text = report().signingBytes().decodeToString()
        val expectedOrder = listOf(
            "\"accused\"", "\"classId\"", "\"evidence\"", "\"filedAt\"",
            "\"reportId\"", "\"reportVersion\"", "\"reporter\"", "\"reporterMandate\"",
        )
        val positions = expectedOrder.map { key ->
            text.indexOf(key).also { assertTrue("$key missing from $text", it >= 0) }
        }
        assertEquals("keys must appear in UTF-8 byte order", positions.sorted(), positions)
        // The collision that catches a case-insensitive sort swap:
        assertTrue(text.indexOf("\"reportId\"") < text.indexOf("\"reporter\""))
        assertTrue(text.indexOf("\"reportVersion\"") < text.indexOf("\"reporter\""))
    }

    @Test
    fun signingBytesExcludeTheSignature_andAreSignatureIndependent() {
        val text = report().signingBytes().decodeToString()
        assertFalse(text.contains("signature"))
        assertTrue(
            report(signature = "AAAA").signingBytes()
                .contentEquals(report(signature = "BBBB").signingBytes()),
        )
    }

    @Test
    fun evidenceContextIsOmittedWhenAbsent_neverNull() {
        val text = report().signingBytes().decodeToString()
        assertFalse(text.contains("context"))
        assertFalse(report().wireBytes().decodeToString().contains("null"))
    }

    @Test
    fun wireBytesRoundTrip() {
        val original = report()
        val decoded = ModerationJson.json.decodeFromString(
            ModerationReport.serializer(),
            original.wireBytes().decodeToString(),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun receiptDecodesFirstFilingAndDuplicateReplayShapes() {
        val first = ModerationJson.json.decodeFromString(
            ReportReceipt.serializer(),
            """{"reportId":"report-1","receivedAt":"2026-08-16T00:00:01Z","caseId":"case-9",
               "intakeWeight":1.0,"responseDeadline":"2026-08-23T00:00:01Z",
               "decisionDeadline":"2026-08-30T00:00:01Z"}""",
        )
        assertEquals("case-9", first.caseId)
        assertEquals(null, first.duplicate)

        val replay = ModerationJson.json.decodeFromString(
            ReportReceipt.serializer(),
            """{"reportId":"report-1","caseId":"case-9","duplicate":true}""",
        )
        assertEquals(true, replay.duplicate)
        assertEquals(null, replay.intakeWeight)
    }
}
