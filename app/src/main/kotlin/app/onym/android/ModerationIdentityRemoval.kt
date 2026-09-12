package app.onym.android

import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.moderation.keyReference

/**
 * The two reads behind the moderation ledgers' identity-removal purge,
 * extracted from the wiring so the one that can destroy data is
 * testable.
 *
 * `ModerationRepository.purgeForRemovedIdentities` deletes every row
 * whose owner is outside the keep-set it is handed, which makes an
 * empty keep-set a total wipe of the mandate, report and appeal
 * ledgers. The identity summary list it is derived from is a
 * `StateFlow` that starts empty and stays empty until something loads
 * identity storage — so "no identities" and "nobody has looked yet"
 * arrive as the same value, and only one of them means the rows are
 * orphaned.
 */
internal object ModerationIdentityRemoval {

    /**
     * Whether [summaries] is a loaded list, evidenced by it still
     * carrying the identity that is being removed.
     *
     * The removal listeners run before the wipe, so a loaded list
     * always contains [removed]. One that doesn't is a list nobody has
     * populated — the state a `restore()` on a cold start presents,
     * where treating it as "this device holds no identities" would
     * purge every ledger row on the device, including those of the
     * identity being restored onto.
     */
    fun listIsLoaded(summaries: List<IdentitySummary>, removed: IdentityId): Boolean =
        summaries.any { it.id == removed }

    /**
     * The `onym:key:<hex>` references of [summaries] — what the
     * mandate, report and appeal rows are keyed by.
     *
     * `IdentitySummary.sendingPublicKey` is the Stellar-derived
     * Ed25519 public key, the same one `IdentityModerationSigner`
     * wraps for `userKeyId()`, so these match `mandate.user`,
     * `report.reporter` and `CaseSubmissionRecord.user` exactly.
     */
    fun keepSet(summaries: List<IdentitySummary>): Set<String> =
        summaries.mapTo(mutableSetOf()) { keyReference(it.sendingPublicKey) }
}
