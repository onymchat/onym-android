package app.onym.android.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.onym.android.AppDependencies
import app.onym.android.BackupUiDependencies
import app.onym.android.backup.ui.BackupSchedule
import app.onym.android.backup.ui.DeviceBackupSettingsFlow
import app.onym.android.foundation.PinnedConsentRecord
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.group.GroupStore
import app.onym.android.chats.MessageStore
import app.onym.android.persistence.InvitationStore
import app.onym.android.identity.IdentityRepository
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Memoized suspend initializer — computes [initializer] at most once,
 * on first [get], and caches the result. Used to keep expensive,
 * seed-derived construction (BIP39 PBKDF2 stretch, wire client) off
 * the app-boot path: [BackupSeatComposer] builds every vendor's cheap
 * surface (status display, terms fetch) eagerly, but defers this
 * until the person actually taps something that needs it.
 */
private class SuspendLazy<T>(private val initializer: suspend () -> T) {
    private val mutex = Mutex()
    @Volatile private var cached: T? = null

    suspend fun get(): T {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: initializer().also { cached = it }
        }
    }
}

/** The expensive, seed-derived half of one vendor's dependencies —
 *  everything [SuspendLazy]-deferred by [BackupSeatComposer]. */
private class VendorRuntime(
    val keyMaterial: BackupKeyMaterial,
    val client: ObjectHttpBackupClient,
    val repository: BackupRepository,
    val restorer: BackupRestorer,
)

/**
 * Builds one [BackupUiDependencies] per consented backup vendor — a
 * holder may back up to several operators at once (see
 * [BackupSeat]'s doc comment) — or an empty list when there's nothing
 * to build: no consented backup operator, or the active identity has
 * no recovery phrase to derive backup keys from. Both are ordinary
 * states that hide the Settings section rather than showing a broken
 * screen — mirrors iOS's `BackupSeatComposer`.
 *
 * Per the review-fix checklist this port carries forward from the
 * iOS PR history: the enrolment factory below is REAL, not a stub —
 * every unreachable screen in this stack has, on iOS, turned out to
 * be a screen someone believed shipped.
 */
object BackupSeatComposer {

    suspend fun composeAll(
        identityRepository: IdentityRepository,
        consentStore: PinnedConsentStore,
        groupStore: GroupStore,
        messageStore: MessageStore,
        invitationStore: InvitationStore,
        blossomClient: BlossomClient?,
        httpClient: OkHttpClient,
        filesDir: File,
        scope: CoroutineScope,
        backupStateDataStore: DataStore<Preferences>,
        strings: app.onym.android.foundation.StringProvider,
        runConditions: () -> app.onym.android.backup.ui.BackupRunConditions,
    ): List<BackupUiDependencies> {
        // No recovery phrase (raw-key-imported identity) means no
        // seed to derive ANY backup key from — hide the whole feature
        // rather than offer vendors that can never actually seal
        // anything. `hasRecoveryPhrase` is the non-secret presence
        // check; reading the phrase field itself outside :identity is
        // forbidden by scripts/lint-secrets.py. Identity-wide, so
        // checked once rather than per vendor.
        val identity = identityRepository.currentIdentity() ?: return emptyList()
        if (!identity.hasRecoveryPhrase) return emptyList()

        return BackupSeat.activeConsents(consentStore).mapNotNull { consent ->
            try {
                composeOne(
                    consent = consent,
                    identityRepository = identityRepository,
                    consentStore = consentStore,
                    groupStore = groupStore,
                    messageStore = messageStore,
                    invitationStore = invitationStore,
                    blossomClient = blossomClient,
                    httpClient = httpClient,
                    filesDir = filesDir,
                    scope = scope,
                    backupStateDataStore = backupStateDataStore,
                    strings = strings,
                    runConditions = runConditions,
                )
                // A single vendor failing to compose (malformed
                // manifest, key-derivation failure) must not take
                // every OTHER vendor's Settings row down with it —
                // but a cancelled coroutine (boot aborted) is not
                // "one vendor failed," it's the whole call being torn
                // down, and must propagate rather than being read as
                // "this vendor has no manifest."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun composeOne(
        consent: PinnedConsentRecord,
        identityRepository: IdentityRepository,
        consentStore: PinnedConsentStore,
        groupStore: GroupStore,
        messageStore: MessageStore,
        invitationStore: InvitationStore,
        blossomClient: BlossomClient?,
        httpClient: OkHttpClient,
        filesDir: File,
        scope: CoroutineScope,
        backupStateDataStore: DataStore<Preferences>,
        strings: app.onym.android.foundation.StringProvider,
        runConditions: () -> app.onym.android.backup.ui.BackupRunConditions,
    ): BackupUiDependencies? {
        val manifest = BackupSeat.manifest(consent) ?: return null
        val endpoint = manifest.endpoint ?: return null

        val workingDir = BackupSeat.workingDirectory(filesDir, manifest.componentId)
        val blobDir = BackupSeat.blobDirectory(filesDir, manifest.componentId)
        val stateStore = DataStorePreferencesBackupStateStore(backupStateDataStore, manifest.componentId)

        // Everything below this point is expensive (BIP39 seed
        // derivation is a 2048-round PBKDF2-HMAC-SHA512 stretch — a
        // few hundred milliseconds, per vendor) and is deliberately
        // NEVER constructed eagerly here: `composeAll` runs inside
        // `runBlocking` on the app-boot thread, and a device backing
        // up to several vendors would multiply that cost straight
        // into ANR territory. `runtime` builds it once, off the boot
        // path, the first time a closure below is actually invoked
        // (tapping "Back Up Now", opening enrolment's accept flow,
        // restoring, or erasing) — never merely for showing the
        // Settings row's status, which only reads `stateStore`.
        val runtime = SuspendLazy {
            val seedDeriving = object : BackupSeedDeriving {
                override suspend fun deriveSeedScopedKey(info: String): ByteArray =
                    identityRepository.deriveSeedScopedKey(info)
                override suspend fun deriveSeedScopedKeys(infos: List<String>): List<ByteArray> =
                    identityRepository.deriveSeedScopedKeys(infos)
            }
            val keyMaterial = BackupKeys.material(seedDeriving, manifest.componentId)
            val holderReference = BackupKeys.holderReference(keyMaterial.accessSigningKey.generatePublicKey().encoded)

            // Read fresh per request, never captured — a paid operator
            // must not keep refusing after a successful purchase just
            // because a stale `null` was captured at construction time.
            // Entitlement redemption/storage lands with the Play Billing
            // purchase flow; until then this operator's uploads proceed
            // unauthenticated on the entitlement axis (fine for a free
            // operator, refused with `payment_required` by a paid one,
            // surfaced to Settings as `PaymentRequired`).
            val entitlementProvider: suspend () -> String? = { null }

            val client = ObjectHttpBackupClient(
                baseUrl = endpoint,
                httpClient = httpClient,
                signingKey = keyMaterial.accessSigningKey,
                holderReference = holderReference,
                entitlementProvider = entitlementProvider,
            )

            val source = AppBackupSource(
                groupStore = groupStore,
                messageStore = messageStore,
                invitationStore = invitationStore,
                consentStore = consentStore,
                blossomClient = blossomClient,
                identityCountProvider = { identityRepository.identities.value.size },
            )
            val sink = AppBackupSink(
                groupStore = groupStore,
                messageStore = messageStore,
                invitationStore = invitationStore,
                consentStore = consentStore,
                blobWriter = { sha256, ciphertext -> File(blobDir, sha256).writeBytes(ciphertext) },
            )
            val composer = BackupComposer(source, BackupMediaPolicy.DescriptorsOnly, workingDir)
            val repository = BackupRepository(client, composer, stateStore, workingDir)
            val restorer = BackupRestorer(sink)

            VendorRuntime(keyMaterial, client, repository, restorer)
        }

        val schedule = BackupSchedule()
        val settingsFlow = DeviceBackupSettingsFlow(
            stateStore = stateStore,
            schedule = schedule,
            // One instance is always exactly one vendor now (the
            // consent-store list, not a single slot, is what can gain
            // or lose vendors) — this is definitionally always true,
            // kept so DeviceBackupSettingsFlow's shape stays shared
            // with the single-vendor path it was designed for.
            currentComponentId = { manifest.componentId },
            scope = scope,
        )

        suspend fun runBackup(block: suspend () -> BackupRunResult) {
            settingsFlow.markRunning()
            try {
                block()
            } catch (termsChanged: BackupError.TermsChanged) {
                settingsFlow.reportTermsChanged()
            } catch (e: Exception) {
                settingsFlow.reportFailed(e.message ?: strings[app.onym.android.strings.R.string.backup_error_generic_failed])
            } finally {
                settingsFlow.refresh()
            }
        }

        /** @return `false` without touching [settingsFlow] if backup
         *  was never turned on — the opportunistic checker needs to
         *  tell "not enrolled" apart from "ran and failed" so it never
         *  flips an un-enrolled vendor's status to Failed just because
         *  it happened to check. */
        suspend fun runBackUpNowIfEnrolled(): Boolean {
            val acceptedTermsId = stateStore.load()?.acceptedTermsId ?: return false
            runBackup {
                val rt = runtime.get()
                rt.repository.backUp(rt.keyMaterial, manifest.componentId, acceptedTermsId)
            }
            return true
        }

        // Drawn once here — composeOne runs once per vendor per app
        // process — matching BackupSchedule.drawJitter's "once per
        // session, not re-drawn per check" contract.
        val jitter = schedule.drawJitter()

        return BackupUiDependencies(
            componentId = manifest.componentId,
            displayName = manifest.componentId.substringAfterLast(':'),
            settingsFlow = settingsFlow,
            backUpNow = {
                scope.launch {
                    if (!runBackUpNowIfEnrolled()) {
                        settingsFlow.reportFailed(strings[app.onym.android.strings.R.string.backup_error_not_enrolled])
                    }
                }
            },
            retryAfterPurchase = {
                scope.launch { runBackup { runtime.get().repository.retry(manifest.componentId) } }
            },
            checkOpportunistic = {
                scope.launch {
                    val persisted = stateStore.load() ?: return@launch
                    // Never enrolled, or reconcile hasn't caught up
                    // with an earlier attempt yet — an opportunistic
                    // check is not the place to start racing either.
                    if (persisted.acceptedTermsId == null) return@launch
                    if (persisted.pendingOperation != null || persisted.pendingPayment != null) return@launch
                    val lastSuccessAt = persisted.lastSuccessAtEpochSeconds?.let(java.time.Instant::ofEpochSecond)
                    if (schedule.permitsOpportunisticRun(runConditions(), jitter, lastSuccessAt)) {
                        runBackUpNowIfEnrolled()
                    }
                }
            },
            erase = {
                scope.launch {
                    settingsFlow.markRunning()
                    try {
                        val receipt = runtime.get().client.eraseSnapshot(ErasureScope.All)
                        // Erasure touches local state too: nothing is
                        // retained at the operator anymore, so a pending
                        // operation/payment referencing a now-erased
                        // snapshot is moot, and `lastSuccessAtEpochSeconds`
                        // must stop claiming a backup exists. The pinned
                        // terms survive — erase doesn't turn backup off,
                        // it clears what was retained under it. The
                        // receipt itself is kept, not discarded, so
                        // `isCompletionPending` is actually checkable
                        // later against the clock, not just true for the
                        // lifetime of this call.
                        val current = stateStore.load() ?: PersistedBackupState()
                        // Both a pending upload and a pending-payment
                        // snapshot point at now-moot sealed bytes (nothing
                        // is retained at the operator to reconcile against
                        // or pay for anymore) — delete both files, not
                        // just the state fields referencing them.
                        current.pendingOperation?.let { File(it.sealedFilePath).let { f -> if (f.exists()) f.delete() } }
                        current.pendingPayment?.let { File(it.sealedFilePath).let { f -> if (f.exists()) f.delete() } }
                        stateStore.save(
                            current.copy(
                                pendingOperation = null,
                                pendingPayment = null,
                                lastSuccessAtEpochSeconds = null,
                                lastErasureReceipt = PersistedErasureReceipt(
                                    receiptId = receipt.receiptId,
                                    acknowledgedAtEpochSeconds = receipt.acknowledgedAt.epochSecond,
                                    completionCommittedByEpochSeconds = receipt.completionCommittedBy.epochSecond,
                                    coveredScope = receipt.coveredScope,
                                    excludedScope = receipt.excludedScope,
                                    termsId = receipt.termsId,
                                ),
                            ),
                        )
                    } catch (e: Exception) {
                        // A destructive, privacy-sensitive user action —
                        // the whole point of the affordance is that the
                        // person knows whether it worked. Silent failure
                        // here would leave "Backed up" showing while the
                        // erase never reached the operator.
                        settingsFlow.reportFailed(e.message ?: strings[app.onym.android.strings.R.string.backup_error_erase_failed])
                    } finally {
                        settingsFlow.refresh()
                    }
                }
            },
            makeEnrolmentDisclosure = {
                fetchAndDisclose(client = httpClient, manifest = manifest, schedule = schedule, strings = strings)
            },
            acceptEnrolment = { termsId ->
                val current = stateStore.load() ?: PersistedBackupState()
                stateStore.save(current.copy(componentId = manifest.componentId, acceptedTermsId = termsId))
                settingsFlow.refresh()
            },
            makeRestoreFlow = {
                // An empty result is an ordinary answer, not an error
                // — a different operator or a different identity has a
                // different holder key and legitimately sees nothing.
                // "Your history is gone" would be the wrong read here.
                val rt = runtime.get()
                val snapshots = rt.client.listSnapshots()
                val latest = snapshots.maxByOrNull { it.retainedAt }
                if (latest == null) {
                    BackupRestoreSummary(0, 0, 0, 0, 0, emptyMap(), emptyList())
                } else {
                    val sealedFile = File(workingDir, "restore-${latest.snapshotReference.digest.removePrefix("sha256:")}.seal")
                    try {
                        rt.client.downloadSnapshot(latest.snapshotReference, sealedFile)
                        rt.restorer.restore(sealedFile, latest.snapshotReference, rt.keyMaterial, workingDir)
                    } finally {
                        sealedFile.delete()
                    }
                }
            },
        )
    }

    private suspend fun fetchAndDisclose(
        client: OkHttpClient,
        manifest: BackupOperatorManifest,
        schedule: BackupSchedule,
        strings: app.onym.android.foundation.StringProvider,
    ): Pair<List<app.onym.android.backup.ui.BackupDisclosureItem>, String>? = withContext(Dispatchers.IO) {
        val termsUrl = manifest.termsUrl(manifest.declaredTermsDigest) ?: return@withContext null
        try {
            val request = Request.Builder().url(termsUrl).get().build()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.bytes()
            } ?: return@withContext null
            val terms = BackupTerms.decode(bytes)
            if (terms.termsId != manifest.declaredTermsDigest) return@withContext null
            val items = app.onym.android.backup.ui.BackupDisclosure.items(
                manifest.componentId,
                terms,
                BackupMediaPolicy.DescriptorsOnly,
                strings,
            )
            items to schedule.disclosureSentence(strings)
        } catch (_: Exception) {
            null
        }
    }
}
