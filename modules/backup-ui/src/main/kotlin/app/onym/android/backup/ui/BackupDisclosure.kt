package app.onym.android.backup.ui

import app.onym.android.backup.BackupMediaPolicy
import app.onym.android.backup.BackupTerms
import app.onym.android.foundation.StringProvider
import app.onym.android.strings.R

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
    fun thirdPartyConsequence(strings: StringProvider): String = strings[R.string.backup_disclosure_third_party_value]

    fun noResetPath(strings: StringProvider): String = strings[R.string.backup_disclosure_recovery_value]

    fun items(
        operatorComponentId: String,
        terms: BackupTerms,
        mediaPolicy: BackupMediaPolicy,
        strings: StringProvider,
    ): List<BackupDisclosureItem> {
        val yes = strings[R.string.backup_disclosure_yes]
        val no = strings[R.string.backup_disclosure_no]
        return listOf(
            BackupDisclosureItem("operator", strings[R.string.backup_disclosure_operator], operatorComponentId),
            BackupDisclosureItem("retention.class", strings[R.string.backup_disclosure_retention_class], terms.retention.retentionClass),
            BackupDisclosureItem("retention.period", strings[R.string.backup_disclosure_retention_period], terms.retention.maximumRetentionPeriod),
            BackupDisclosureItem("retention.snapshotsRetained", strings[R.string.backup_disclosure_snapshots_retained], terms.retention.snapshotsRetained),
            BackupDisclosureItem("retention.expiryBehavior", strings[R.string.backup_disclosure_retention_expiry], terms.retention.expiryBehavior),
            BackupDisclosureItem("erasure.acknowledgementDeadline", strings[R.string.backup_disclosure_erasure_ack_deadline], terms.erasure.acknowledgementDeadline),
            BackupDisclosureItem("erasure.completionDeadline", strings[R.string.backup_disclosure_erasure_completion_deadline], terms.erasure.completionDeadline),
            BackupDisclosureItem("erasure.scope", strings[R.string.backup_disclosure_erasure_scope], terms.erasure.scope),
            BackupDisclosureItem("erasure.excluded", strings[R.string.backup_disclosure_erasure_excluded], terms.erasure.excluded),
            BackupDisclosureItem("jurisdictions", strings[R.string.backup_disclosure_jurisdictions], terms.jurisdictions.joinToString(", ")),
            BackupDisclosureItem(
                "subProcessors",
                strings[R.string.backup_disclosure_sub_processors],
                terms.subProcessors.joinToString(", ") { "${it.role} (${it.jurisdiction})" },
            ),
            BackupDisclosureItem("lawfulAccess.disclosure", strings[R.string.backup_disclosure_lawful_access], terms.lawfulAccess.disclosureWhatIsProduced),
            BackupDisclosureItem(
                "lawfulAccess.notify",
                strings[R.string.backup_disclosure_lawful_access_notify],
                if (terms.lawfulAccess.notifyHolderWhenPermitted) yes else no,
            ),
            BackupDisclosureItem("breachDisclosure.holderNotice", strings[R.string.backup_disclosure_breach_notice], terms.breachDisclosureHolderNotice),
            BackupDisclosureItem("export.format", strings[R.string.backup_disclosure_export_format], terms.export.format),
            BackupDisclosureItem(
                "export.availableWhileUnpaid",
                strings[R.string.backup_disclosure_export_while_unpaid],
                if (terms.export.availableWhileUnpaid) yes else no,
            ),
            BackupDisclosureItem("shutdownNotice", strings[R.string.backup_disclosure_shutdown_notice], terms.shutdownNotice),
            BackupDisclosureItem("endOfPayment.notice", strings[R.string.backup_disclosure_end_of_payment_notice], terms.endOfPayment.notice),
            BackupDisclosureItem("endOfPayment.grace", strings[R.string.backup_disclosure_end_of_payment_grace], terms.endOfPayment.grace),
            BackupDisclosureItem("endOfPayment.duringGrace", strings[R.string.backup_disclosure_end_of_payment_during_grace], terms.endOfPayment.duringGrace.joinToString(", ")),
            BackupDisclosureItem("endOfPayment.afterGrace", strings[R.string.backup_disclosure_end_of_payment_after_grace], terms.endOfPayment.afterGrace),
            BackupDisclosureItem("metadataRetention.accessLogs", strings[R.string.backup_disclosure_metadata_access_logs], terms.metadataRetention.accessLogs),
            BackupDisclosureItem("metadataRetention.sizeAndTiming", strings[R.string.backup_disclosure_metadata_size_timing], terms.metadataRetention.sizeAndTiming),
            BackupDisclosureItem(
                "mediaPolicy",
                strings[R.string.backup_disclosure_media_policy],
                if (mediaPolicy == BackupMediaPolicy.IncludeCiphertext) {
                    strings[R.string.backup_disclosure_media_included]
                } else {
                    strings[R.string.backup_disclosure_media_not_included]
                },
            ),
            BackupDisclosureItem("termsId", strings[R.string.backup_disclosure_terms_digest], terms.termsId),
            BackupDisclosureItem("thirdPartyConsequence", strings[R.string.backup_disclosure_third_party_label], thirdPartyConsequence(strings)),
            BackupDisclosureItem("noResetPath", strings[R.string.backup_disclosure_recovery_label], noResetPath(strings)),
        )
    }
}
