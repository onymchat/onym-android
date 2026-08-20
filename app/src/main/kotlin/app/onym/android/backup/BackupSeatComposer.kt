package app.onym.android.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.onym.android.AppDependencies
import app.onym.android.BackupUiDependencies
import app.onym.android.backup.ui.BackupSchedule
import app.onym.android.backup.ui.DeviceBackupSettingsFlow
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.group.GroupStore
import app.onym.android.chats.MessageStore
import app.onym.android.persistence.InvitationStore
import app.onym.android.identity.IdentityRepository
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Builds [BackupUiDependencies], or `null` when there's nothing to
 * build: no consented backup operator, or the active identity has no
 * recovery phrase to derive a backup key from. Both are ordinary
 * states that hide the Settings section rather than showing a broken
 * screen — mirrors iOS's `BackupSeatComposer`.
 *
 * Per the review-fix checklist this port carries forward from the
 * iOS PR history: the enrolment factory below is REAL, not a stub —
 * every unreachable screen in this stack has, on iOS, turned out to
 * be a screen someone believed shipped.
 */
object BackupSeatComposer {

    suspend fun compose(
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
        val consent = BackupSeat.activeConsent(consentStore) ?: return null
        val manifest = BackupSeat.manifest(consent) ?: return null
        val endpoint = manifest.endpoint ?: return null

        // No recovery phrase (raw-key-imported identity) means no
        // seed to derive a backup key from — hide the section rather
        // than offer a feature that can never actually seal anything.
        val identity = identityRepository.currentIdentity() ?: return null
        if (identity.recoveryPhrase == null) return null

        val seedDeriving = object : BackupSeedDeriving {
            override suspend fun deriveSeedScopedKey(info: String): ByteArray =
                identityRepository.deriveSeedScopedKey(info)
        }
        val keyMaterial = try {
            BackupKeys.material(seedDeriving, manifest.componentId)
        } catch (_: Exception) {
            return null
        }

        val workingDir = BackupSeat.workingDirectory(filesDir, manifest.componentId)
        val blobDir = BackupSeat.blobDirectory(filesDir, manifest.componentId)
        val holderReference = BackupKeys.holderReference(keyMaterial.accessSigningKey.generatePublicKey().encoded)

        val stateStore = DataStorePreferencesBackupStateStore(backupStateDataStore)

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
            currentComponentId = { BackupSeat.activeConsent(consentStore)?.componentId },
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
            consentedComponentId = { BackupSeat.activeConsent(consentStore)?.componentId },
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
                try {
                    client.eraseSnapshot(ErasureScope.All)
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

    private fun fetchAndDisclose(
        client: OkHttpClient,
        manifest: BackupOperatorManifest,
        schedule: BackupSchedule,
    ): Pair<List<app.onym.android.backup.ui.BackupDisclosureItem>, String>? {
        val termsUrl = manifest.termsUrl(manifest.declaredTermsDigest) ?: return null
        return try {
            val request = Request.Builder().url(termsUrl).get().build()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            } ?: return null
            val terms = BackupTerms.decode(bytes)
            if (terms.termsId != manifest.declaredTermsDigest) return null
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
