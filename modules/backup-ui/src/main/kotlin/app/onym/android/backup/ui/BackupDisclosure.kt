package app.onym.android.backup.ui

import app.onym.android.backup.BackupMediaPolicy
import app.onym.android.backup.BackupTerms

/** One rendered disclosure line. [id] is a stable, testable handle —
 *  the enrolment-screen fixture asserts presence by [id], not by
 *  scanning rendered prose, so copy can be reworded without breaking
 *  the "every required term is shown" guarantee. */
data class BackupDisclosureItem(val id: String, val label: String, val value: String)

/**
 * Builds the enrolment screen's full, unsummarized disclosure from an
 * operator's signed [BackupTerms] — data, not something assembled
 * inline in a Composable, specifically so a fixture test can assert
 * every required term is present (onym-system `backup/UI-Backup.md`
 * §7.3, `backup/UI-Backup-Object-HTTP.md` §18.7: "verified by fixture
 * rather than by review").
 *
 * Mirrors `BackupDisclosure` in onym-ios OnymBackupUI.
 */
object BackupDisclosure {

    /** Two fixed strings the spec requires regardless of what any
     *  operator declares: the third-party consequence, and that there
     *  is no operator-side reset path. */
    const val THIRD_PARTY_CONSEQUENCE =
        "A backup extends how long this history exists for everyone in every conversation it contains — " +
            "including people who did not choose this operator."
    const val NO_RESET_PATH =
        "Nobody — not this operator, not Onym — can recover this archive without your recovery phrase. " +
            "A lost recovery phrase means a permanently unreadable backup. There is no support-driven reset."

    fun items(operatorComponentId: String, terms: BackupTerms, mediaPolicy: BackupMediaPolicy): List<BackupDisclosureItem> =
        listOf(
            BackupDisclosureItem("operator", "Operator", operatorComponentId),
            BackupDisclosureItem("retention.class", "Retention class", terms.retention.retentionClass),
            BackupDisclosureItem("retention.period", "Maximum retention period", terms.retention.maximumRetentionPeriod),
            BackupDisclosureItem("retention.snapshotsRetained", "Snapshots retained", terms.retention.snapshotsRetained),
            BackupDisclosureItem("retention.expiryBehavior", "When retention expires", terms.retention.expiryBehavior),
            BackupDisclosureItem("erasure.acknowledgementDeadline", "Erasure acknowledged within", terms.erasure.acknowledgementDeadline),
            BackupDisclosureItem("erasure.completionDeadline", "Erasure completed within", terms.erasure.completionDeadline),
            BackupDisclosureItem("erasure.scope", "What erasure covers", terms.erasure.scope),
            BackupDisclosureItem("erasure.excluded", "What erasure does NOT cover", terms.erasure.excluded),
            BackupDisclosureItem("jurisdictions", "Jurisdictions", terms.jurisdictions.joinToString(", ")),
            BackupDisclosureItem(
                "subProcessors",
                "Sub-processors",
                terms.subProcessors.joinToString(", ") { "${it.role} (${it.jurisdiction})" },
            ),
            BackupDisclosureItem("lawfulAccess.disclosure", "What a lawful-access demand can produce", terms.lawfulAccess.disclosureWhatIsProduced),
            BackupDisclosureItem(
                "lawfulAccess.notify",
                "Notified when legally permitted",
                if (terms.lawfulAccess.notifyHolderWhenPermitted) "Yes" else "No",
            ),
            BackupDisclosureItem("breachDisclosure.holderNotice", "Breach notice", terms.breachDisclosureHolderNotice),
            BackupDisclosureItem("export.format", "Export format", terms.export.format),
            BackupDisclosureItem(
                "export.availableWhileUnpaid",
                "Export available while unpaid",
                if (terms.export.availableWhileUnpaid) "Yes" else "No",
            ),
            BackupDisclosureItem("shutdownNotice", "Notice before shutdown", terms.shutdownNotice),
            BackupDisclosureItem("endOfPayment.notice", "End-of-payment notice", terms.endOfPayment.notice),
            BackupDisclosureItem("endOfPayment.grace", "End-of-payment grace period", terms.endOfPayment.grace),
            BackupDisclosureItem("endOfPayment.duringGrace", "Available during grace", terms.endOfPayment.duringGrace.joinToString(", ")),
            BackupDisclosureItem("endOfPayment.afterGrace", "After grace ends", terms.endOfPayment.afterGrace),
            BackupDisclosureItem("metadataRetention.accessLogs", "Access-log retention", terms.metadataRetention.accessLogs),
            BackupDisclosureItem("metadataRetention.sizeAndTiming", "Size/timing metadata retention", terms.metadataRetention.sizeAndTiming),
            BackupDisclosureItem(
                "mediaPolicy",
                "Attachments",
                if (mediaPolicy == BackupMediaPolicy.IncludeCiphertext) "Included in the backup" else "Not included — descriptors only",
            ),
            BackupDisclosureItem("termsId", "Terms digest", terms.termsId),
            BackupDisclosureItem("thirdPartyConsequence", "What this means for people you talk to", THIRD_PARTY_CONSEQUENCE),
            BackupDisclosureItem("noResetPath", "Recovery", NO_RESET_PATH),
        )
}
