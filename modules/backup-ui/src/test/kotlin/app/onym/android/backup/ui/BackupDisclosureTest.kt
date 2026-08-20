package app.onym.android.backup.ui

import app.onym.android.backup.BackupMediaPolicy
import app.onym.android.backup.BackupTerms
import app.onym.android.foundation.StringProvider
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resource-name fake — the disclosure test only asserts on [id] and
 *  raw [BackupTerms] field values, never on label text, so a fake that
 *  just echoes back an identifiable marker is enough. */
private class FakeDisclosureStringProvider : StringProvider {
    override fun get(resId: Int): String = "resId=$resId"
    override fun get(resId: Int, vararg formatArgs: Any): String = "resId=$resId args=${formatArgs.joinToString()}"
    override fun getQuantity(pluralsResId: Int, quantity: Int, vararg formatArgs: Any): String =
        "pluralsResId=$pluralsResId quantity=$quantity"
}

class BackupDisclosureTest {

    private val strings = FakeDisclosureStringProvider()

    private fun sampleTerms(): BackupTerms {
        val json = """
            {
              "termsVersion": 1,
              "operator": "onym:key:${"a".repeat(64)}",
              "retention": {"class": "measurable", "maximumRetentionPeriod": "P90D", "snapshotsRetained": "1", "expiryBehavior": "erase"},
              "erasure": {"acknowledgementDeadline": "PT24H", "completionDeadline": "P7D", "scope": "primary plus replicas", "excluded": "recipient copies"},
              "jurisdictions": ["us"],
              "subProcessors": [{"role": "storage", "jurisdiction": "us"}],
              "lawfulAccess": {"disclosureWhatIsProduced": "sealed bytes only", "notifyHolderWhenPermitted": true},
              "breachDisclosure": {"holderNotice": "P3D"},
              "export": {"format": "portable-sealed-form", "availableWhileUnpaid": true},
              "shutdownNotice": "P30D",
              "endOfPayment": {"notice": "P14D", "grace": "P30D", "duringGrace": ["download", "export", "erase"], "afterGrace": "erase"},
              "metadataRetention": {"accessLogs": "none", "sizeAndTiming": "P30D", "holderIdentifiers": "while retained", "operationOutcomes": "PT24H", "erasureReceipts": "P365D", "entitlementRecords": "P30D"},
              "signature": "AAAA"
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        return BackupTerms.decode(json)
    }

    @Test
    fun every_required_term_is_present_by_field_id() {
        val items = BackupDisclosure.items("onym:component:backup-op-1", sampleTerms(), BackupMediaPolicy.DescriptorsOnly, strings)
        val ids = items.map { it.id }.toSet()
        for (required in listOf(
            "operator", "retention.class", "retention.period", "erasure.scope", "erasure.excluded",
            "jurisdictions", "subProcessors", "lawfulAccess.disclosure", "breachDisclosure.holderNotice",
            "export.format", "shutdownNotice", "endOfPayment.notice", "endOfPayment.grace",
            "metadataRetention.accessLogs", "termsId", "thirdPartyConsequence", "noResetPath",
        )) {
            assertTrue("missing required disclosure item: $required", required in ids)
        }
    }

    @Test
    fun excluded_scope_is_rendered_verbatim() {
        val items = BackupDisclosure.items("onym:component:backup-op-1", sampleTerms(), BackupMediaPolicy.DescriptorsOnly, strings)
        val excluded = items.first { it.id == "erasure.excluded" }
        assertTrue(excluded.value == "recipient copies")
    }
}
