package app.onym.android

import androidx.fragment.app.FragmentActivity
import app.onym.android.chain.NetworkPreferenceProvider
import app.onym.android.chats.ChatThreadViewModel
import app.onym.android.chats.ChatsViewModel
import app.onym.android.group.CreateGroupViewModel
import app.onym.android.recovery.RecoveryPhraseBackupViewModel
import app.onym.android.settings.AnchorsPickerViewModel
import app.onym.android.settings.RelayerSettingsViewModel
import app.onym.android.transport.NostrEphemeralSignerProvider

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
    /** Lowercase BLS pubkey hex of the active identity. Surfaces gate
     *  "am I this group's admin" on it — see `ChatGroup.isAdmin`. */
    val activeBlsPubkeyHex: kotlinx.coroutines.flow.StateFlow<String?>,
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
    /** Settings → Transport → Blossom Relays. */
    val makeBlossomServerSettingsViewModel: () -> app.onym.android.settings.BlossomServerSettingsViewModel,
    /** Live snapshot of configured Blossom servers — drives the
     *  Settings entry's "{n} configured" subtitle. */
    val blossomServersFlow: kotlinx.coroutines.flow.StateFlow<app.onym.android.transport.blossom.BlossomServersConfiguration>,
    /** Wipe every local message (keeps chats). Wired to
     *  `MessageRepository.clearAll`; run behind a two-step confirm from
     *  Settings → Data → Clear local message cache. */
    val clearAllMessages: suspend () -> Unit,
    /** Discovery settings surfaces (provider list, TOFU add flow,
     *  module consent) + the live discovery state the per-seat
     *  pickers' "From catalog" sections read. `null` exactly when no
     *  [app.onym.android.discovery.DiscoveryRepository] was
     *  constructed (UI-test harness without discovery fakes) — every
     *  consumer renders as before discovery existed. */
    val discovery: DiscoveryUiDependencies? = null,
    /** First-launch onboarding gate + flow factory + the step
     *  contents' seat hooks. Always constructed in production; under
     *  the UI-test harness the gate resolves to `false` immediately
     *  so existing instrumented tests never see the walk (PR 4 adds
     *  registry slots to drive it deliberately). */
    val onboarding: OnboardingUiDependencies? = null,
)

/**
 * Everything [RootScreen]'s onboarding gate and the onboarding step
 * contents need, bundled so [AppDependencies] carries one value.
 *
 * The gate decision ([shouldOnboard]) resolves asynchronously at app
 * start — the grandfathering probe reads the persisted transport
 * configurations — and is `null` until resolved so RootScreen can
 * hold a blank frame instead of flashing the tabs at a user who is
 * about to be onboarded (or vice versa).
 */
class OnboardingUiDependencies(
    /** null → still resolving; true → present the walk; false →
     *  straight to the tabs. Flips to false on completion. */
    val shouldOnboard: kotlinx.coroutines.flow.StateFlow<Boolean?>,
    /** Fresh state machine per presentation. Partial progress is
     *  deliberately in-memory only (see OnboardingFlow's KDoc). */
    val makeFlow: () -> app.onym.android.onboarding.OnboardingFlow,
    /** Invoked once when the flow publishes `completed`: re-enables
     *  the relayer auto-populate policy (suppressed for the whole
     *  walk so the notary step's choice isn't preempted), kicks a
     *  relayer refresh so a skipped notary step gets the published
     *  defaults now rather than at next launch, and flips
     *  [shouldOnboard] to false so RootScreen proceeds to the tabs.
     *  The completion flag itself was already persisted by
     *  `OnboardingFlow.complete`. */
    val onCompleted: () -> Unit,
    /** Live relayer state — the notary step lists the published
     *  relayers ([app.onym.android.chain.RelayerState.knownRelayers],
     *  fetched even while auto-populate is suppressed) and shows
     *  which are already configured. */
    val relayerState: kotlinx.coroutines.flow.StateFlow<app.onym.android.chain.RelayerState>,
    /** Legacy add-without-consent for the notary step's published
     *  list — the same `RelayerRepository.addEndpoint` the Settings
     *  picker uses. */
    val addRelayerEndpoint: suspend (app.onym.android.chain.RelayerEndpoint) -> Unit,
    /**
     * Presentation generation, bumped on every explicit restart. The
     * host keys its retained OnboardingFlow on this so a re-run gets
     * a FRESH state machine — the prior walk's ViewModel (whose flow
     * already published `completed`) would otherwise dismiss the new
     * walk on first composition.
     */
    val generation: kotlinx.coroutines.flow.StateFlow<Int> =
        kotlinx.coroutines.flow.MutableStateFlow(0),
    /**
     * Settings → Restart Onboarding, confirmed: persists the
     * restart-requested bit (clearing the completion flag),
     * re-suppresses the relayer auto-populate policy for the new
     * walk, bumps [generation], and flips [shouldOnboard] to true so
     * RootScreen presents the walk immediately — WITHOUT re-running
     * the cold-boot grandfathering probe, which would veto the
     * restart (a configured user always reads grandfathered).
     * Identity and chats/messages are untouched: only the seat
     * selections re-run, and the steps render current state.
     */
    val requestRestart: suspend () -> Unit = {},
)

/**
 * Everything the discovery settings UI needs, bundled so
 * [AppDependencies] carries one nullable value. Mirrors iOS's
 * `DiscoveryModulePicker` bundling on `discovery-settings-ui`.
 */
class DiscoveryUiDependencies(
    /** Live discovery state: sources, verified entries, fetch status,
     *  per-source errors. */
    val stateFlow: kotlinx.coroutines.flow.StateFlow<app.onym.android.discovery.DiscoveryState>,
    /** Settings → Discovery (provider list + add flow). */
    val makeDiscoverySettingsViewModel: () -> app.onym.android.settings.DiscoverySettingsViewModel,
    /**
     * Consent flow for one catalog entry, looked up by seat type +
     * short component id (the `onym:component:` prefix stripped for
     * route friendliness). The seat-specific apply closure that
     * writes the accepted endpoint into the legacy configuration is
     * pre-bound. The returned ViewModel reports a stable error when
     * the entry has vanished from the aggregate.
     */
    val makeModuleConsentViewModel: (
        seatType: String,
        componentShortId: String,
    ) -> app.onym.android.settings.ModuleConsentViewModel,
    /** Active pinned consent for a componentId — drives the
     *  consent-state chips on catalog rows. */
    val activeConsent: suspend (componentId: String) -> app.onym.android.foundation.PinnedConsentRecord?,
)
