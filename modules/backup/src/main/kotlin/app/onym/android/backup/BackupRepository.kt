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
 * Three behaviors this class exists to get right (see the
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
