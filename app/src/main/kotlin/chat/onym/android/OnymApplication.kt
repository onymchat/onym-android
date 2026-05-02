package chat.onym.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.identity.OnymNostrSignerProvider
import chat.onym.android.recovery.AndroidBiometricAuthenticator
import chat.onym.android.recovery.AndroidClipboardWriter
import chat.onym.android.recovery.AndroidStringProvider
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.lang.ref.WeakReference
import java.security.Security

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
