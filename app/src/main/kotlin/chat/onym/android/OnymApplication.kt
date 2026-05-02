package chat.onym.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import chat.onym.android.chain.ContractsRepository
import chat.onym.android.chain.DataStorePreferencesAnchorSelectionStore
import chat.onym.android.chain.DataStorePreferencesRelayerSelectionStore
import chat.onym.android.chain.GitHubReleasesContractsManifestFetcher
import chat.onym.android.chain.GitHubReleasesKnownRelayersFetcher
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.identity.OnymNostrSignerProvider
import chat.onym.android.recovery.AndroidBiometricAuthenticator
import chat.onym.android.recovery.AndroidClipboardWriter
import chat.onym.android.recovery.AndroidStringProvider
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import chat.onym.android.settings.AnchorsPickerViewModel
import chat.onym.android.settings.RelayerPickerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.lang.ref.WeakReference
import java.security.Security

/**
 * DataStore Preferences for the relayer URL selection + cached
 * known-relayer list. The `preferencesDataStore` delegate
 * guarantees one instance per `Context` per filename across the
 * process — safe to call from anywhere; we only read it from
 * [OnymApplication.onCreate].
 *
 * Identity material continues to live in EncryptedSharedPreferences
 * via [IdentitySecretStore]; URLs aren't secret so DataStore is fine.
 */
private val Context.relayerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat.onym.android.relayer_prefs",
)

/**
 * DataStore Preferences for the anchor (contract version)
 * selections + cached `contracts-manifest.json`. Separate file from
 * [relayerDataStore] so each domain's storage layer has its own
 * blob — easier to reason about + selectively wipe in tests.
 */
private val Context.contractsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat.onym.android.contracts_prefs",
)

/**
 * Composition root. Two responsibilities:
 *
 *  - Register the BouncyCastle JCE provider once at process start
 *    (we use BC for Curve25519 raw-key APIs the JDK doesn't expose
 *    — see [chat.onym.android.identity.IdentityRepository]). BC
 *    must be installed BEFORE any provider lookup that picks the
 *    first match (some JCA APIs cache providers per-thread); doing
 *    it in [Application.onCreate] guarantees ordering.
 *  - Build [AppDependencies] — the single seam between the
 *    repository / FFI layer and the View / ViewModel layer.
 *    Repositories and I/O affordances live as captures inside the
 *    factory closures; nothing above this point holds a reference
 *    to [IdentityRepository] or to `chat.onym.sdk.*`.
 */
class OnymApplication : Application() {

    /** Built once in [onCreate]. Read by [MainActivity] via
     *  `(application as OnymApplication).dependencies`. */
    lateinit var dependencies: AppDependencies
        private set

    /** The currently-resumed Activity, used to host the AndroidX
     *  `BiometricPrompt` dialog fragment. `WeakReference` so a
     *  background-and-finish doesn't pin the Activity. */
    private var resumedActivity: WeakReference<Activity>? = null

    /** Application-scoped CoroutineScope for fire-and-forget jobs
     *  that must outlive any Activity (e.g., the boot fetch of the
     *  relayer list). [SupervisorJob] so a single failing child
     *  doesn't cancel the rest. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Insert at position 1 so BC is preferred for algorithms it
        // implements (X25519, Ed25519 raw-key params). The Android
        // platform's Conscrypt provider stays first for everything else.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val identityRepository = IdentityRepository(
            store = IdentitySecretStore(applicationContext),
        )
        val nostrSignerProvider = OnymNostrSignerProvider()
        val clipboard = AndroidClipboardWriter(applicationContext)
        val strings = AndroidStringProvider(applicationContext)

        val httpClient = OkHttpClient()

        // Relayer wiring (PR #17). Bootstrap loads cached + selection
        // from disk; start() fires the network fetch. Both run on the
        // application scope so launch never blocks on the network.
        val relayerStore = DataStorePreferencesRelayerSelectionStore(
            dataStore = applicationContext.relayerDataStore,
        )
        val relayerFetcher = GitHubReleasesKnownRelayersFetcher(httpClient = httpClient)
        val relayerRepository = RelayerRepository(
            store = relayerStore,
            fetcher = relayerFetcher,
        )
        applicationScope.launch {
            relayerRepository.bootstrap()
            relayerRepository.start()
        }

        // Contracts/anchors wiring — same shape as the relayer block.
        // Separate DataStore file so the two domains' storage layers
        // are independent (one less coupling at audit time).
        val contractsStore = DataStorePreferencesAnchorSelectionStore(
            dataStore = applicationContext.contractsDataStore,
        )
        val contractsFetcher = GitHubReleasesContractsManifestFetcher(httpClient = httpClient)
        val contractsRepository = ContractsRepository(
            store = contractsStore,
            fetcher = contractsFetcher,
        )
        applicationScope.launch {
            contractsRepository.bootstrap()
            contractsRepository.start()
        }

        dependencies = AppDependencies(
            nostrSignerProvider = nostrSignerProvider,
            makeRecoveryPhraseBackupViewModel = { activityProvider ->
                RecoveryPhraseBackupViewModel(
                    repository = identityRepository,
                    authenticator = AndroidBiometricAuthenticator(
                        activityProvider = activityProvider,
                    ),
                    clipboard = clipboard,
                    strings = strings,
                )
            },
            makeRelayerPickerViewModel = {
                RelayerPickerViewModel(repository = relayerRepository)
            },
            makeAnchorsPickerViewModel = {
                AnchorsPickerViewModel(repository = contractsRepository)
            },
        )
    }

    /** Resolve the currently-on-top [FragmentActivity] for biometric
     *  prompts. Throws if called when no Activity is resumed — this
     *  should be unreachable in practice because the recovery flow
     *  is reached via UI navigation. */
    internal fun requireCurrentFragmentActivity(): FragmentActivity =
        resumedActivity?.get() as? FragmentActivity
            ?: error("No resumed FragmentActivity to host BiometricPrompt")
}
