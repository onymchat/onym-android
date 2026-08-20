package app.onym.android.backup

import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.time.Instant

/** [BackupRepository.backUp] and [BackupRepository.retry]'s result. */
sealed class BackupRunResult {
    data class Success(val outcome: BackupOutcome) : BackupRunResult()
    /** A concurrent call was already in flight — Kotlin's [Mutex] has
     *  no reentrancy, so this is the observable "already running"
     *  answer rather than a deadlock or a silent second run. */
    data object AlreadyRunning : BackupRunResult()
    /** [BackupRepository.retry] found no pending-payment operation to
     *  resume. */
    data object NothingPending : BackupRunResult()
}

/**
 * Drives one operator's compose → preflight → upload cycle, plus
 * reconciliation and payment retry. Guarded by a [Mutex] so two
 * concurrent `backUp()`/`retry()` calls never race each other's state
 * writes.
 *
 * Four behaviors this class exists to get right (see the
 * implementation plan's parameter table for the full rationale):
 *
 * 1. Payment refusal preserves the exact sealed bytes and
 *    `operationId` — a retry resubmits the identical composed
 *    snapshot rather than re-sealing (which would mint a new
 *    `snapshotSalt` and a new digest).
 * 2. A pending-operation record is written **before** the upload
 *    attempt, not after — a lost response then reconciles to
 *    `unknown` rather than being silently treated as clean.
 * 3. `terms_changed` / an operator mismatch stops uploading and is
 *    never re-pinned automatically.
 * 4. Retention is a rolling window: a routine re-backup never wedges
 *    the holder against the operator's declared
 *    `maximumRetainedSnapshots` — see [pruneToFitLocked].
 *
 * Mirrors `BackupRepository` in onym-ios OnymBackup (an `actor`
 * there; a [Mutex]-guarded class here for the same serialization
 * property).
 */
class BackupRepository(
    private val port: BackupPort,
    private val composer: BackupComposer,
    private val stateStore: BackupStateStore,
    private val workingDirectory: File,
    /**
     * This operator's declared `limits.maximumRetainedSnapshots`, read
     * off its verified [BackupOperatorManifest]. Per-operator by
     * construction — one repository is one operator, and one
     * operator's limit says nothing about another's.
     *
     * `null` (the operator declares no snapshot limit) means **no
     * client-side pruning at all**. Inventing a limit the operator
     * never declared would have this client erase a holder's
     * snapshots to satisfy a rule nobody stated; filling up and being
     * told so by the operator is the strictly better failure.
     */
    private val maximumRetainedSnapshots: Long? = null,
) {
    private val mutex = Mutex()

    /** Compose (or resume a pending payment retry) and upload one
     *  snapshot for [componentId]. Reconciles any earlier pending
     *  operation first. */
    suspend fun backUp(
        keyMaterial: BackupKeyMaterial,
        componentId: String,
        acceptedTermsId: String,
        now: Instant = Instant.now(),
    ): BackupRunResult {
        if (!mutex.tryLock()) return BackupRunResult.AlreadyRunning
        try {
            reconcileLocked(componentId)

            val state = stateStore.load()
            if (state?.componentId != null && state.componentId != componentId) {
                throw BackupError.OperatorChanged
            }

            val pendingPayment = state?.pendingPayment?.takeIf { it.componentId == componentId }
            val snapshot = pendingPayment?.let {
                SealedSnapshot(
                    operationId = it.operationId,
                    snapshotReference = SnapshotReference(digest = it.snapshotDigest, sealedByteSize = it.sealedByteSize),
                    sealedBytesFile = File(it.sealedFilePath),
                    sealedAt = now,
                    acceptedTermsId = it.acceptedTermsId,
                )
            } ?: composer.compose(keyMaterial, acceptedTermsId, now = now)

            recordPendingLocked(componentId, snapshot)
            pruneToFitLocked(snapshot)

            val outcome = try {
                when (val preflight = port.preflight(snapshot)) {
                    is BackupPreflight.Resolved -> preflight.outcome
                    is BackupPreflight.Grant -> port.uploadSnapshot(snapshot, preflight.upload)
                }
            } catch (paymentRequired: BackupError.PaymentRequired) {
                recordPendingPaymentLocked(componentId, snapshot, paymentRequired)
                throw paymentRequired
            }

            resolvePendingLocked(componentId, snapshot, outcome)
            return BackupRunResult.Success(outcome)
        } finally {
            mutex.unlock()
        }
    }

    /** Retries the pending payment operation for [componentId], if
     *  any, after an entitlement was obtained — same operation id,
     *  same sealed bytes. No-op success with no pending payment. */
    suspend fun retry(componentId: String, now: Instant = Instant.now()): BackupRunResult {
        if (!mutex.tryLock()) return BackupRunResult.AlreadyRunning
        try {
            val state = stateStore.load()
            val pending = state?.pendingPayment?.takeIf { it.componentId == componentId }
                ?: return BackupRunResult.NothingPending

            // The snapshot was sealed under `pending.acceptedTermsId`.
            // If the local pin has since moved past it, resubmitting
            // would only be refused again at the operator
            // (terms_changed) and leave nothing behind to retry
            // against — so the stale pending payment is cleared here,
            // same as iOS, rather than sent.
            if (state.acceptedTermsId != null && state.acceptedTermsId != pending.acceptedTermsId) {
                val file = File(pending.sealedFilePath)
                if (file.exists()) file.delete()
                stateStore.save(state.copy(pendingPayment = null))
                throw BackupError.TermsChanged(state.acceptedTermsId)
            }

            val snapshot = SealedSnapshot(
                operationId = pending.operationId,
                snapshotReference = SnapshotReference(digest = pending.snapshotDigest, sealedByteSize = pending.sealedByteSize),
                sealedBytesFile = File(pending.sealedFilePath),
                sealedAt = now,
                acceptedTermsId = pending.acceptedTermsId,
            )
            recordPendingLocked(componentId, snapshot)
            // The same window applies to a payment retry: this is a
            // real upload of a snapshot the operator does not hold
            // yet, and the quota does not care that it was composed
            // before the purchase.
            pruneToFitLocked(snapshot)
            val outcome = when (val preflight = port.preflight(snapshot)) {
                is BackupPreflight.Resolved -> preflight.outcome
                is BackupPreflight.Grant -> port.uploadSnapshot(snapshot, preflight.upload)
            }
            resolvePendingLocked(componentId, snapshot, outcome)
            return BackupRunResult.Success(outcome)
        } finally {
            mutex.unlock()
        }
    }

    /** Reconciles any earlier pending operation before composing a
     *  new one: checks `listSnapshots()` first (authoritative), then
     *  `queryOutcome()` (valid only within the operator's declared
     *  outcome-record retention window). Only a *failed* fetch stays
     *  pending — a successful check that doesn't find the digest
     *  resolves negatively rather than staying stuck forever. Either
     *  way, if the pending digest turns out to actually be retained —
     *  a response that was lost on the wire but landed at the
     *  operator — that's a genuine success and must set
     *  `lastSuccessAtEpochSeconds`, not just silently clear the
     *  pending record and leave Settings still reading stale/no-backup. */
    private suspend fun reconcileLocked(componentId: String) {
        val state = stateStore.load() ?: return
        val pending = state.pendingOperation?.takeIf { it.componentId == componentId } ?: return

        val viaList = runCatching { port.listSnapshots() }.getOrNull()
        if (viaList != null) {
            // `listSnapshots` is authoritative either way — presence
            // or absence both resolve the pending record; only a
            // *failed* fetch leaves it open for a later attempt.
            val wasRetained = viaList.any { it.snapshotReference.digest == pending.snapshotDigest }
            clearPendingOperationLocked(state, componentId = componentId, terms = pending.acceptedTermsId, wasRetained = wasRetained)
            return
        }

        val viaQuery = runCatching { port.queryOutcome(pending.operationId) }
        if (viaQuery.isSuccess) {
            // Unlike `listSnapshots` above, a successful query here
            // does NOT resolve the record on every answer: `listSnapshots`
            // itself just failed, so a `null`/in-flight status has no
            // corroborating "not in the authoritative list" to resolve
            // against — it may simply not be recorded yet. Only a
            // determinate answer (actually retained, or explicitly
            // refused) resolves the pending record here; queued/
            // submitted/accepted/unreachable/unknown/no-record all stay
            // pending for a later reconcile attempt.
            val outcome = viaQuery.getOrNull()
            when {
                outcome?.status?.isRetention == true ->
                    clearPendingOperationLocked(state, componentId = componentId, terms = pending.acceptedTermsId, wasRetained = true)
                outcome?.status == BackupOutcomeStatus.Rejected ->
                    clearPendingOperationLocked(state, componentId = componentId, terms = pending.acceptedTermsId, wasRetained = false)
                else -> Unit
            }
        }
        // A failed fetch (both list and query threw) leaves the
        // pending record untouched — reconciled on a later attempt.
    }

    /**
     * Keeps this operator's retention a rolling window: if accepting
     * [snapshot] would put the holder over the operator's declared
     * `maximumRetainedSnapshots`, the oldest retained snapshots are
     * erased first — exactly as many as the new one needs, never down
     * to some floor.
     *
     * **Why the client erases and not the operator.** Erasure is
     * holder-initiated by design: the request is signed by the
     * holder's access key and answered with a signed §11 erasure
     * receipt (`UI-Backup-Object-HTTP.md` §11), which is the holder's
     * evidence that a specific snapshot was asked to be destroyed and
     * under which terms. Letting the operator drop the oldest
     * snapshot on its own to make room would delete a holder's data
     * with no holder request behind it and no receipt in front of it,
     * and would cost the operator the property that no code path of
     * its own removes a snapshot the holder did not ask it to remove.
     * So the client — the only party who can sign such a request —
     * does it. Note that `supersedes` does **not** free a slot: it is
     * a recorded pointer, and nothing in the profile obliges the
     * operator to erase what a snapshot supersedes.
     *
     * **Why before the upload, not after.** Reacting to
     * `quota_exceeded` is wrong twice over: that code also covers the
     * byte limit and the concurrent-grant limit, so retrying on it
     * would erase a snapshot to fix a problem erasing snapshots does
     * not solve. Pruning *after* a successful commit (down to
     * `limit - 1`, keeping a slot of headroom) is the tempting
     * alternative, and it is the safer ordering in isolation — but it
     * cannot unwedge a holder who is already at the limit, which is
     * precisely the holder this exists for: their next upload is
     * refused before there is any commit to prune after. So the erase
     * happens first.
     *
     * The cost of that ordering — an erase that succeeds followed by
     * an upload that fails leaves the holder one snapshot down —
     * is real but bounded: the device still holds the data being
     * backed up (a snapshot is a copy, not the original), the sealed
     * bytes remain on disk under the pending-operation record written
     * just above, and the retry resubmits those identical bytes. What
     * is spent is the *oldest* snapshot — the one with the least
     * value to a holder restoring — and never more than one per slot
     * actually needed.
     *
     * The operator's `GET /v1/snapshots` is the authority on what it
     * holds; local state is not consulted. "Oldest" is by `retainedAt`
     * among rows whose `status` is `retained` (ties broken by digest,
     * so the choice is deterministic rather than list-order
     * dependent).
     *
     * A failed listing skips pruning entirely rather than guessing —
     * the upload then proceeds and the operator gives whatever answer
     * it would have given anyway. A failed *erase* propagates: the
     * operator refusing a signed erasure request is a genuine fault,
     * and swallowing it would only produce a `quota_exceeded` that
     * hides which of the two actually went wrong.
     */
    private suspend fun pruneToFitLocked(snapshot: SealedSnapshot) {
        val limit = maximumRetainedSnapshots ?: return
        // A declared limit of zero (or a nonsense negative) is not a
        // window this client can slide: there is no room for the new
        // snapshot no matter what is erased, and erasing everything to
        // learn that would be pure loss.
        if (limit <= 0) return

        val listed = runCatching { port.listSnapshots() }.getOrNull() ?: return
        // Nothing to make room for if the operator already holds these
        // exact bytes — the upload will resolve as `already_retained`
        // and consume no new slot. (Reachable on the payment-retry
        // path, where the same sealed snapshot is resubmitted.)
        if (listed.any { it.snapshotReference.digest == snapshot.snapshotReference.digest }) return

        // Only rows the operator actually calls `retained` occupy a
        // slot — anything else it chooses to list (an erasure still
        // completing, say) is not this window's to slide.
        val retained = listed.filter { it.status == BackupOutcomeStatus.Retained.wireValue }
        val excess = (retained.size + 1L - limit).coerceAtMost(retained.size.toLong())
        if (excess <= 0L) return

        val oldestFirst = retained.sortedWith(
            compareBy({ it.retainedAt }, { it.snapshotReference.digest }),
        )
        for (victim in oldestFirst.take(excess.toInt())) {
            port.eraseSnapshot(ErasureScope.Snapshot(victim.snapshotReference))
            // The receipt is deliberately not written to
            // `lastErasureReceipt`: that field is the holder's record
            // of an erase-*all* they asked for, and Settings reads it
            // to show that state. Overwriting it from a routine
            // window slide would tell the holder their backup had
            // been erased when it had just been rotated.
        }
    }

    private suspend fun recordPendingLocked(componentId: String, snapshot: SealedSnapshot) {
        val state = stateStore.load() ?: PersistedBackupState()
        stateStore.save(
            state.copy(
                componentId = componentId,
                pendingOperation = PendingOperation(
                    componentId = componentId,
                    operationId = snapshot.operationId,
                    snapshotDigest = snapshot.snapshotReference.digest,
                    sealedByteSize = snapshot.snapshotReference.sealedByteSize,
                    sealedFilePath = snapshot.sealedBytesFile.absolutePath,
                    acceptedTermsId = snapshot.acceptedTermsId,
                    sealedAtEpochSeconds = snapshot.sealedAt.epochSecond,
                ),
            ),
        )
    }

    private suspend fun recordPendingPaymentLocked(
        componentId: String,
        snapshot: SealedSnapshot,
        error: BackupError.PaymentRequired,
    ) {
        val state = stateStore.load() ?: PersistedBackupState()
        stateStore.save(
            state.copy(
                componentId = componentId,
                pendingOperation = null,
                pendingPayment = PendingPayment(
                    componentId = componentId,
                    operationId = snapshot.operationId,
                    snapshotDigest = snapshot.snapshotReference.digest,
                    sealedByteSize = snapshot.snapshotReference.sealedByteSize,
                    sealedFilePath = snapshot.sealedBytesFile.absolutePath,
                    acceptedTermsId = snapshot.acceptedTermsId,
                    offerIds = error.offerIds,
                ),
            ),
        )
    }

    private suspend fun resolvePendingLocked(componentId: String, snapshot: SealedSnapshot, outcome: BackupOutcome) {
        val state = stateStore.load() ?: PersistedBackupState()
        val resolved = outcome.status.isRetention || outcome.status == BackupOutcomeStatus.Rejected
        stateStore.save(
            state.copy(
                componentId = componentId,
                acceptedTermsId = snapshot.acceptedTermsId,
                pendingOperation = if (resolved) null else state.pendingOperation,
                pendingPayment = null,
                lastSuccessAtEpochSeconds = if (outcome.status.isRetention) {
                    Instant.now().epochSecond
                } else {
                    state.lastSuccessAtEpochSeconds
                },
            ),
        )
        if (resolved) {
            // The pending snapshot's sealed bytes are no longer
            // needed once its outcome is settled — a retry-relevant
            // file is only kept while pendingOperation/pendingPayment
            // still reference it.
            if (snapshot.sealedBytesFile.exists()) snapshot.sealedBytesFile.delete()
        }
    }

    private suspend fun clearPendingOperationLocked(
        state: PersistedBackupState,
        componentId: String,
        terms: String,
        wasRetained: Boolean,
    ) {
        // The pending record's sealed bytes are only kept while
        // something might still retry against them — once reconcile
        // resolves the record (found retained, or confirmed the
        // operator has no memory of it), the file is dead weight.
        // Left undeleted, every reconciled-but-failed upload attempt
        // accumulates a `.seal` file in the working directory forever.
        state.pendingOperation?.let { pending ->
            val file = File(pending.sealedFilePath)
            if (file.exists()) file.delete()
        }
        stateStore.save(
            state.copy(
                componentId = componentId,
                acceptedTermsId = terms,
                pendingOperation = null,
                lastSuccessAtEpochSeconds = if (wasRetained) Instant.now().epochSecond else state.lastSuccessAtEpochSeconds,
            ),
        )
    }
}
