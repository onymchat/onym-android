package app.onym.android

import androidx.fragment.app.FragmentActivity
import app.onym.android.chain.NetworkPreferenceProvider
import app.onym.android.chats.ChatThreadViewModel
import app.onym.android.chats.ChatsViewModel
import app.onym.android.group.CreateGroupViewModel
import app.onym.android.recovery.RecoveryPhraseBackupViewModel
import app.onym.android.settings.AnchorsPickerViewModel
import app.onym.android.settings.RelayerSettingsViewModel
import app.onym.android.transport.nostr.NostrEphemeralSignerProvider

/**
 * Composition-root handle. Built once in [OnymApplication.onCreate]
 * and threaded down through [MainActivity] → [RootScreen] →
 * per-flow composables.
 *
 * Each `make*ViewModel` is a closure that **captures** the
 * repositories + I/O affordances it needs and exposes them to the
 * UI as a no-context factory. The UI never sees [app.onym.android.identity.IdentityRepository]
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
    /** Symmetric read-receipt setting, bound to the Settings → Chat
     *  toggle and read by the dispatcher + chat thread. */
    val readReceiptsPreferenceProvider: app.onym.android.chats.ReadReceiptsPreferenceProvider,
    val makeCreateGroupViewModel: () -> CreateGroupViewModel,
    /** Chats tab — read-only view over [app.onym.android.group.GroupRepository.snapshots].
     *  Mirrors `makeChatsFlow` from onym-ios PR #30. */
    val makeChatsViewModel: () -> ChatsViewModel,
    /** Chat-thread screen factory — takes the path-arg `groupId` so
     *  the VM can subscribe to that group's [app.onym.android.chats.MessageRepository.snapshots]
     *  stream and dispatch sends via the captured [SendMessageInteractor].
     *  Mirrors the UIViewControllerRepresentable bridge factory from
     *  onym-ios PR #151, Android-idiomatic types. */
    val makeChatThreadViewModel: (groupId: String) -> ChatThreadViewModel,
    /** Search tab — full-text message search over the active identity. */
    val makeSearchViewModel: () -> app.onym.android.search.SearchViewModel,
    /** Settings → Identities — multi-identity management (PR-5). */
    val makeIdentitiesViewModel: () -> app.onym.android.identity.IdentitiesViewModel,
    /** Post-create deeplink invite share (deeplink-invite PR-5). */
    val makeShareInviteViewModel: () -> app.onym.android.group.ShareInviteViewModel,
    /** Joiner-side post-deeplink-tap surface (deeplink-invite PR-7).
     *  Takes the decoded capability so the same factory works for
     *  both `https://onym.app/join` and `onym://join` intents. */
    val makeJoinViewModel: (app.onym.android.group.IntroCapability) -> app.onym.android.group.JoinViewModel,
    /** Approver UI for incoming join requests. Single shared
     *  instance — the toolbar badge on the chats screen and the
     *  modal screen both consume the same flow so a request that
     *  lands on the relay shows up in the badge before the modal
     *  is opened. */
    val approveRequestsViewModel: app.onym.android.group.ApproveRequestsViewModel,
    /** Invitee-side push-invitation surface (PR 158). Single shared
     *  instance — the Chats toolbar "Invitations" badge and the modal
     *  list both consume the same flow, so an offer that lands on the
     *  relay shows up in the badge before the modal is opened. */
    val pendingInvitesViewModel: app.onym.android.inbox.PendingInvitesViewModel,
    /** Settings → Transport → Nostr Relays. */
    val makeNostrRelaySettingsViewModel: () -> app.onym.android.settings.NostrRelaySettingsViewModel,
    /** Live snapshot of configured Nostr relays — drives the
     *  Settings entry's "{n} configured" subtitle. */
    val nostrRelaysFlow: kotlinx.coroutines.flow.StateFlow<app.onym.android.transport.nostr.NostrRelaysConfiguration>,
)
