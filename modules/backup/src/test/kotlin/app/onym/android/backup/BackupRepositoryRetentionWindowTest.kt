package app.onym.android.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.time.Instant

private class NoContentSource : BackupSourceProviding {
    override suspend fun identityCount(): Int = 1
    override suspend fun groups(): List<BackupGroupRecord> = emptyList()
    override suspend fun messages(groupId: String, ownerIdentityId: String): List<BackupMessageRecord> = emptyList()
    override suspend fun invitations(): List<BackupInvitationRecord> = emptyList()
    override suspend fun consents(): List<BackupConsentRecord> = emptyList()
    override suspend fun blobCiphertext(sha256: String): BackupBlobAvailability = BackupBlobAvailability.Gone
}

/**
 * A port that holds a fixed set of snapshots and records every
 * erasure it is asked for, in order — so a test can assert not just
 * *that* something was erased but exactly which snapshot and how
 * many.
 */
private class RetentionPort(
    retained: List<RetainedSnapshot>,
    /** Fails the upload path (after any pruning has already run) —
     *  for the ordering test. */
    private val failUpload: Boolean = false,
    /** Refuses the signed erasure request — for the "a refused erase
     *  is a real fault" test. */
    private val refuseErasure: Boolean = false,
) : BackupPort {
    private val retained = retained.toMutableList()
    val erasedDigests = mutableListOf<String>()
    var uploadAttempts = 0
        private set

    override suspend fun connect(): BackupConnection = BackupConnection(null)

    override suspend fun preflight(snapshot: SealedSnapshot): BackupPreflight {
        uploadAttempts += 1
        if (failUpload) throw BackupError.OperatorUnavailable
        return BackupPreflight.Grant(
            BackupUploadGrant("upload-1", snapshot.sealedBytesFile.length().coerceAtLeast(1), 1, Instant.now().plusSeconds(60)),
        )
    }

    override suspend fun uploadSnapshot(snapshot: SealedSnapshot, grant: BackupUploadGrant): BackupOutcome =
        BackupOutcome(snapshot.operationId, snapshot.snapshotReference, BackupOutcomeStatus.Retained)

    override suspend fun listSnapshots(): List<RetainedSnapshot> = retained.toList()

    override suspend fun downloadSnapshot(reference: SnapshotReference, destination: File) = error("not used")

    override suspend fun eraseSnapshot(scope: ErasureScope): ErasureReceipt {
        val reference = (scope as? ErasureScope.Snapshot)?.reference
            ?: error("the rolling window must never erase with scope `all`")
        if (refuseErasure) throw BackupError.AccessRefused
        erasedDigests += reference.digest
        retained.removeAll { it.snapshotReference.digest == reference.digest }
        return ErasureReceipt(
            receiptId = "receipt-${erasedDigests.size}",
            operator = "onym:component:op-1",
            scope = reference.digest,
            acknowledgedAt = Instant.now(),
            completionCommittedBy = Instant.now().plusSeconds(3600),
            coveredScope = "the named snapshot at this operator",
            excludedScope = "copies held by other participants and copies the holder exported",
            termsId = TERMS,
            signature = "sig",
        )
    }

    override suspend fun exportSnapshots(directory: File) = error("not used")

    override suspend fun queryOutcome(operationId: String): BackupOutcome? = null
}

private class InMemoryStore : BackupStateStore {
    var state: PersistedBackupState? = null
    override suspend fun load(): PersistedBackupState? = state
    override suspend fun save(state: PersistedBackupState?) { this.state = state }
}

private const val COMPONENT = "onym:component:op-1"
private val TERMS = "sha256:" + "e".repeat(64)

/** The n-th test snapshot's digest — `sha256:` plus one repeated hex
 *  character, so the digests are both valid and orderable by eye. */
private fun digest(index: Int): String = "sha256:" + "0123456789abcd"[index].toString().repeat(64)

/**
 * The rolling window: a holder who re-backs-up routinely must never
 * pile up against the operator's declared `maximumRetainedSnapshots`
 * and start getting `quota_exceeded` forever. The client erases the
 * oldest retained snapshot itself — holder-initiated, with a signed
 * §11 receipt — so the operator never has to delete anything nobody
 * asked it to delete.
 */
class BackupRepositoryRetentionWindowTest {

    private fun tempDir(): File = File.createTempFile("retention-test", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun keyMaterial() = BackupKeyMaterial(
        archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) },
        accessSigningKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(SecureRandom()),
        accessAgreementKey = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(SecureRandom()),
        rotation = 0,
    )

    /** `count` snapshots, oldest first, one hour apart — deliberately
     *  handed to the port in *newest*-first order so a test that
     *  passes can't be passing on list order. */
    private fun snapshots(count: Int): List<RetainedSnapshot> {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        return (0 until count).map { index ->
            RetainedSnapshot(
                snapshotReference = SnapshotReference(digest = digest(index), sealedByteSize = 100L),
                acceptedTermsId = TERMS,
                retainedAt = base.plusSeconds(3600L * index),
                retainedUntil = null,
                supersedes = null,
                status = "retained",
            )
        }.reversed()
    }

    @Test
    fun at_the_limit_the_oldest_snapshot_is_erased_and_the_new_one_uploads() = runTest {
        val workDir = tempDir()
        // The deployed shape of the bug: the operator declares room
        // for two, already holds two, and the third backup used to be
        // refused with `quota_exceeded` — permanently.
        val port = RetentionPort(snapshots(2))
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            InMemoryStore(),
            workDir,
            maximumRetainedSnapshots = 2,
        )

        val result = repository.backUp(keyMaterial(), COMPONENT, TERMS)

        assertEquals(
            "exactly one slot is needed, so exactly one snapshot is erased — the oldest",
            listOf(digest(0)),
            port.erasedDigests,
        )
        assertTrue("the upload must then go through", result is BackupRunResult.Success)
        assertEquals(
            BackupOutcomeStatus.Retained,
            (result as BackupRunResult.Success).outcome.status,
        )
    }

    @Test
    fun two_slots_short_erases_exactly_two_and_no_more() = runTest {
        val workDir = tempDir()
        // Over the limit already (an operator that lowered its
        // declared limit, or a snapshot uploaded under an older
        // build): three held, room for two, one arriving — two must go.
        val port = RetentionPort(snapshots(3))
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            InMemoryStore(),
            workDir,
            maximumRetainedSnapshots = 2,
        )

        repository.backUp(keyMaterial(), COMPONENT, TERMS)

        assertEquals(
            "erase exactly enough to fit, oldest first — never down to a floor",
            listOf(digest(0), digest(1)),
            port.erasedDigests,
        )
    }

    @Test
    fun below_the_limit_nothing_is_erased() = runTest {
        val workDir = tempDir()
        val port = RetentionPort(snapshots(2))
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            InMemoryStore(),
            workDir,
            maximumRetainedSnapshots = 5,
        )

        repository.backUp(keyMaterial(), COMPONENT, TERMS)

        assertEquals("there is room for the new snapshot, so nothing is erased", emptyList<String>(), port.erasedDigests)
    }

    @Test
    fun an_operator_declaring_no_limit_never_prunes() = runTest {
        val workDir = tempDir()
        // No `limits.maximumRetainedSnapshots` on the manifest. The
        // client must not invent one: erasing a holder's snapshots to
        // satisfy a rule nobody declared is worse than filling up.
        val port = RetentionPort(snapshots(9))
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            InMemoryStore(),
            workDir,
            maximumRetainedSnapshots = null,
        )

        repository.backUp(keyMaterial(), COMPONENT, TERMS)

        assertEquals("an undeclared limit means no client-side pruning at all", emptyList<String>(), port.erasedDigests)
    }

    @Test
    fun a_declared_limit_of_zero_erases_nothing_rather_than_emptying_the_operator() = runTest {
        val workDir = tempDir()
        // Zero leaves no room whatever is erased — clearing the
        // holder out to discover that would be pure loss. Let the
        // operator refuse the upload instead.
        val port = RetentionPort(snapshots(2))
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            InMemoryStore(),
            workDir,
            maximumRetainedSnapshots = 0,
        )

        repository.backUp(keyMaterial(), COMPONENT, TERMS)

        assertEquals(emptyList<String>(), port.erasedDigests)
    }

    @Test
    fun a_prune_then_a_failed_upload_leaves_the_sealed_bytes_retryable() = runTest {
        val workDir = tempDir()
        // The cost of pruning before the upload: this run erases the
        // oldest snapshot and then loses the operator. That is
        // acceptable only because what it spends is recoverable — the
        // pending-operation record and its sealed file must both
        // survive, so a retry resubmits the identical bytes rather
        // than the holder being one snapshot down with nothing to
        // resend.
        val port = RetentionPort(snapshots(2), failUpload = true)
        val store = InMemoryStore()
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            store,
            workDir,
            maximumRetainedSnapshots = 2,
        )

        runCatching { repository.backUp(keyMaterial(), COMPONENT, TERMS) }

        assertEquals(listOf(digest(0)), port.erasedDigests)
        val pending = store.state?.pendingOperation
        assertNotNull("the failed upload must stay pending, not vanish", pending)
        assertTrue(
            "its sealed bytes must survive for the retry to resubmit",
            File(pending!!.sealedFilePath).exists(),
        )
    }

    @Test
    fun a_refused_erasure_surfaces_instead_of_being_masked_by_quota_exceeded() = runTest {
        val workDir = tempDir()
        // An operator that refuses a signed erasure request is a real
        // fault. Swallowing it and uploading anyway would only earn a
        // `quota_exceeded` that hides which of the two went wrong.
        val port = RetentionPort(snapshots(2), refuseErasure = true)
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            InMemoryStore(),
            workDir,
            maximumRetainedSnapshots = 2,
        )

        val thrown = runCatching { repository.backUp(keyMaterial(), COMPONENT, TERMS) }.exceptionOrNull()

        assertTrue("the erasure failure is what the holder is told about", thrown is BackupError.AccessRefused)
        assertEquals("and no upload is attempted on top of it", 0, port.uploadAttempts)
    }

    @Test
    fun a_snapshot_the_operator_already_holds_costs_no_slot_and_prunes_nothing() = runTest {
        val workDir = tempDir()
        // The payment-retry path resubmits an identical sealed
        // snapshot. If the operator already has those exact bytes the
        // upload resolves as `already_retained` and occupies no new
        // slot — erasing the oldest to make room for it would be a
        // free loss.
        val existing = snapshots(2)
        val store = InMemoryStore()
        val sealedFile = File(workDir, "pending.seal").apply { writeBytes(ByteArray(10)) }
        val alreadyHeld = existing.minByOrNull { it.retainedAt }!!
        store.state = PersistedBackupState(
            componentId = COMPONENT,
            acceptedTermsId = TERMS,
            pendingPayment = PendingPayment(
                componentId = COMPONENT,
                operationId = "op-1",
                snapshotDigest = alreadyHeld.snapshotReference.digest,
                sealedByteSize = 10,
                sealedFilePath = sealedFile.absolutePath,
                acceptedTermsId = TERMS,
                offerIds = emptyList(),
            ),
        )
        val port = RetentionPort(existing)
        val repository = BackupRepository(
            port,
            BackupComposer(NoContentSource(), BackupMediaPolicy.DescriptorsOnly, workDir),
            store,
            workDir,
            maximumRetainedSnapshots = 2,
        )

        repository.retry(COMPONENT)

        assertEquals(emptyList<String>(), port.erasedDigests)
    }
}
