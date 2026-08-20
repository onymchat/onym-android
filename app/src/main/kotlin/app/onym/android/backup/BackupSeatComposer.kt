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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

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
            runCatching {
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
                )
            }.getOrNull()
            // A single vendor failing to compose (malformed manifest,
            // key-derivation failure) must not take every OTHER
            // vendor's Settings row down with it.
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
    ): BackupUiDependencies? {
        val manifest = BackupSeat.manifest(consent) ?: return null
        val endpoint = manifest.endpoint ?: return null

        val seedDeriving = object : BackupSeedDeriving {
            override suspend fun deriveSeedScopedKey(info: String): ByteArray =
                identityRepository.deriveSeedScopedKey(info)
            override suspend fun deriveSeedScopedKeys(infos: List<String>): List<ByteArray> =
                identityRepository.deriveSeedScopedKeys(infos)
        }
        val keyMaterial = try {
            BackupKeys.material(seedDeriving, manifest.componentId)
        } catch (_: Exception) {
            return null
        }

        val workingDir = BackupSeat.workingDirectory(filesDir, manifest.componentId)
        val blobDir = BackupSeat.blobDirectory(filesDir, manifest.componentId)
        val holderReference = BackupKeys.holderReference(keyMaterial.accessSigningKey.generatePublicKey().encoded)

        val stateStore = DataStorePreferencesBackupStateStore(backupStateDataStore, manifest.componentId)

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
                settingsFlow.reportFailed(e.message ?: "backup failed")
            } finally {
                settingsFlow.refresh()
            }
        }

        return BackupUiDependencies(
            componentId = manifest.componentId,
            displayName = manifest.componentId.substringAfterLast(':'),
            settingsFlow = settingsFlow,
            backUpNow = {
                val acceptedTermsId = stateStore.load()?.acceptedTermsId
                if (acceptedTermsId == null) {
                    settingsFlow.reportFailed("Backup has not been turned on yet")
                } else {
                    runBackup { repository.backUp(keyMaterial, manifest.componentId, acceptedTermsId) }
                }
            },
            retryAfterPurchase = { runBackup { repository.retry(manifest.componentId) } },
            erase = {
                settingsFlow.markRunning()
                try {
                    client.eraseSnapshot(ErasureScope.All)
                } catch (e: Exception) {
                    // A destructive, privacy-sensitive user action —
                    // the whole point of the affordance is that the
                    // person knows whether it worked. Silent failure
                    // here would leave "Backed up" showing while the
                    // erase never reached the operator.
                    settingsFlow.reportFailed(e.message ?: "erase failed")
                } finally {
                    settingsFlow.refresh()
                }
            },
            makeEnrolmentDisclosure = {
                fetchAndDisclose(client = httpClient, manifest = manifest, schedule = schedule)
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
                val snapshots = client.listSnapshots()
                val latest = snapshots.maxByOrNull { it.retainedAt }
                if (latest == null) {
                    BackupRestoreSummary(0, 0, 0, 0, 0, emptyMap(), emptyList())
                } else {
                    val sealedFile = File(workingDir, "restore-${latest.snapshotReference.digest.removePrefix("sha256:")}.seal")
                    try {
                        client.downloadSnapshot(latest.snapshotReference, sealedFile)
                        restorer.restore(sealedFile, latest.snapshotReference, keyMaterial, workingDir)
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
            )
            items to schedule.disclosureSentence()
        } catch (_: Exception) {
            null
        }
    }
}
