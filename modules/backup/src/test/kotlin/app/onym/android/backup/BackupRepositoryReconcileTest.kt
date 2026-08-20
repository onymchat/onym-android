package app.onym.android.backup

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.time.Instant

private class EmptySource : BackupSourceProviding {
    override suspend fun identityCount(): Int = 1
    override suspend fun groups(): List<BackupGroupRecord> = emptyList()
    override suspend fun messages(groupId: String, ownerIdentityId: String): List<BackupMessageRecord> = emptyList()
    override suspend fun invitations(): List<BackupInvitationRecord> = emptyList()
    override suspend fun consents(): List<BackupConsentRecord> = emptyList()
    override suspend fun blobCiphertext(sha256: String): BackupBlobAvailability = BackupBlobAvailability.Gone
}

/** A port whose `listSnapshots()` answers with a fixed set of digests
 *  (simulating "the operator actually has this"). `preflight` can be
 *  made to fail so a test can isolate what `reconcileLocked` itself
 *  did from `backUp()`'s own subsequent fresh-upload attempt. */
private class FakePort(
    private val retainedDigests: Set<String>,
    private val failNewAttempts: Boolean = false,
) : BackupPort {
    override suspend fun connect(): BackupConnection = BackupConnection(null)
    override suspend fun preflight(snapshot: SealedSnapshot): BackupPreflight {
        if (failNewAttempts) throw BackupError.OperatorUnavailable
        return BackupPreflight.Grant(BackupUploadGrant("upload-1", snapshot.sealedBytesFile.length(), 1, Instant.now().plusSeconds(60)))
    }
    override suspend fun uploadSnapshot(snapshot: SealedSnapshot, grant: BackupUploadGrant): BackupOutcome =
        BackupOutcome(snapshot.operationId, snapshot.snapshotReference, BackupOutcomeStatus.Retained)
    override suspend fun listSnapshots(): List<RetainedSnapshot> = retainedDigests.map { digest ->
        RetainedSnapshot(
            snapshotReference = SnapshotReference(digest = digest, sealedByteSize = 100L),
            acceptedTermsId = "sha256:" + "a".repeat(64),
            retainedAt = Instant.now(),
            retainedUntil = null,
            supersedes = null,
            status = "retained",
        )
    }
    override suspend fun downloadSnapshot(reference: SnapshotReference, destination: File) = error("not used")
    override suspend fun eraseSnapshot(scope: ErasureScope) = error("not used")
    override suspend fun exportSnapshots(directory: File) = error("not used")
    override suspend fun queryOutcome(operationId: String): BackupOutcome? = null
}

private class InMemoryStateStore : BackupStateStore {
    var state: PersistedBackupState? = null
    override suspend fun load(): PersistedBackupState? = state
    override suspend fun save(state: PersistedBackupState?) { this.state = state }
}

class BackupRepositoryReconcileTest {

    private fun tempDir(): File = File.createTempFile("reconcile-test", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun keyMaterial() = BackupKeyMaterial(
        archiveRoot = ByteArray(32).also { SecureRandom().nextBytes(it) },
        accessSigningKey = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(SecureRandom()),
        accessAgreementKey = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(SecureRandom()),
        rotation = 0,
    )

    @Test
    fun reconcile_itself_records_success_when_the_pending_digest_is_actually_retained() = runTest {
        val workDir = tempDir()
        val pendingDigest = "sha256:" + "b".repeat(64)
        val sealedFile = File(workDir, "pending.seal").apply { writeBytes(ByteArray(10)) }

        val stateStore = InMemoryStateStore()
        stateStore.state = PersistedBackupState(
            componentId = "onym:component:op-1",
            acceptedTermsId = "sha256:" + "a".repeat(64),
            pendingOperation = PendingOperation(
                componentId = "onym:component:op-1",
                operationId = "op-1",
                snapshotDigest = pendingDigest,
                sealedByteSize = 10,
                sealedFilePath = sealedFile.absolutePath,
                acceptedTermsId = "sha256:" + "a".repeat(64),
                sealedAtEpochSeconds = Instant.now().epochSecond,
            ),
            lastSuccessAtEpochSeconds = null,
        )

        // The operator DOES have this exact digest retained — a
        // response that was lost on the wire but actually landed.
        // `failNewAttempts = true` means backUp()'s OWN fresh upload
        // attempt (which runs after reconcile) fails, so any success
        // timestamp recorded here can only have come from reconcile
        // itself finding the pending digest retained.
        val port = FakePort(retainedDigests = setOf(pendingDigest), failNewAttempts = true)
        val composer = BackupComposer(EmptySource(), BackupMediaPolicy.DescriptorsOnly, workDir)
        val repository = BackupRepository(port, composer, stateStore, workDir)

        // The fresh-upload attempt after reconcile is expected to
        // fail (that's the whole point of failNewAttempts) — the
        // assertions below are about what RECONCILE itself did.
        runCatching { repository.backUp(keyMaterial(), "onym:component:op-1", "sha256:" + "a".repeat(64)) }

        // This is the exact bug: reconcile used to clear the pending
        // record without ever checking whether it was actually
        // retained, so a lost-response-but-actually-succeeded upload
        // never set lastSuccessAtEpochSeconds and Settings kept
        // reading "no backup yet" / stale.
        assertNotNull(
            "reconcile must record success for a digest the operator actually retained",
            stateStore.state?.lastSuccessAtEpochSeconds,
        )
        assertFalse("the resolved pending operation's sealed file must be cleaned up", sealedFile.exists())
    }

    @Test
    fun reconcile_does_not_fabricate_success_for_a_digest_the_operator_does_not_have() = runTest {
        val workDir = tempDir()
        val pendingDigest = "sha256:" + "c".repeat(64)
        val sealedFile = File(workDir, "pending2.seal").apply { writeBytes(ByteArray(10)) }

        val stateStore = InMemoryStateStore()
        stateStore.state = PersistedBackupState(
            componentId = "onym:component:op-1",
            acceptedTermsId = "sha256:" + "a".repeat(64),
            pendingOperation = PendingOperation(
                componentId = "onym:component:op-1",
                operationId = "op-2",
                snapshotDigest = pendingDigest,
                sealedByteSize = 10,
                sealedFilePath = sealedFile.absolutePath,
                acceptedTermsId = "sha256:" + "a".repeat(64),
                sealedAtEpochSeconds = Instant.now().epochSecond,
            ),
            lastSuccessAtEpochSeconds = null,
        )

        // The operator's list is authoritative and does NOT contain
        // this digest — genuinely not retained. The subsequent fresh
        // attempt also fails, so nothing at all should record success.
        val port = FakePort(retainedDigests = emptySet(), failNewAttempts = true)
        val composer = BackupComposer(EmptySource(), BackupMediaPolicy.DescriptorsOnly, workDir)
        val repository = BackupRepository(port, composer, stateStore, workDir)

        // The fresh-upload attempt after reconcile is expected to
        // fail (that's the whole point of failNewAttempts) — the
        // assertions below are about what RECONCILE itself did.
        runCatching { repository.backUp(keyMaterial(), "onym:component:op-1", "sha256:" + "a".repeat(64)) }

        assertNull(
            "reconcile must not record success for a digest the operator doesn't have",
            stateStore.state?.lastSuccessAtEpochSeconds,
        )
        assertFalse("the pending operation is resolved (not retained) so its sealed file is dead weight", sealedFile.exists())
    }
}
