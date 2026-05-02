package chat.onym.android

import androidx.fragment.app.FragmentActivity
import chat.onym.android.chain.NetworkPreferenceProvider
import chat.onym.android.group.CreateGroupViewModel
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import chat.onym.android.settings.AnchorsPickerViewModel
import chat.onym.android.settings.RelayerSettingsViewModel
import chat.onym.android.transport.nostr.NostrEphemeralSignerProvider

/**
 * Composition-root handle. Built once in [OnymApplication.onCreate]
 * and threaded down through [MainActivity] → [RootScreen] →
 * per-flow composables.
 *
 * Each `make*ViewModel` is a closure that **captures** the
 * repositories + I/O affordances it needs and exposes them to the
 * UI as a no-context factory. The UI never sees [chat.onym.android.identity.IdentityRepository]
 * (or any other repo) directly — it only knows how to ask for a
 * fresh ViewModel.
 *
 * The `activityProvider` argument on [makeRecoveryPhraseBackupViewModel]
 * is the one piece that can't be captured at app start: AndroidX
 * `BiometricPrompt` needs a [FragmentActivity] host that's
 * currently in `RESUMED`. Composables consult `LocalContext` to
 * resolve it at render time and pass the thunk to the factory.
 *
 * Mirrors the iOS `AppDependencies` pattern (Option-A architecture
 * alignment in onym-ios) — same role, Android-idiomatic types.
 */
class AppDependencies(
    /** Used by future Nostr transport wiring; instantiated here so
     *  no caller has to reach into the identity package for the
     *  default impl. Currently unused — transports aren't wired
     *  into the app shell yet. */
    val nostrSignerProvider: NostrEphemeralSignerProvider,
    val makeRecoveryPhraseBackupViewModel: (activityProvider: () -> FragmentActivity) -> RecoveryPhraseBackupViewModel,
    val makeRelayerSettingsViewModel: () -> RelayerSettingsViewModel,
    val makeAnchorsPickerViewModel: () -> AnchorsPickerViewModel,
    /** App-wide testnet/mainnet preference. Settings exposes a Switch
     *  bound to this; CreateGroupInteractor reads it per call. */
    val networkPreferenceProvider: NetworkPreferenceProvider,
    val makeCreateGroupViewModel: () -> CreateGroupViewModel,
)
