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
    /** `writeScope` (nullable): an outliving scope for repository
     *  writes — the onboarding hub passes its host-retained scope so
     *  a back-pop can't cancel an in-flight add; Settings passes
     *  null (the VM's own scope). */
    val makeRelayerSettingsViewModel: (writeScope: kotlinx.coroutines.CoroutineScope?) -> RelayerSettingsViewModel,
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
    /** Approver UI for incoming join requests. Single shared
     *  instance — the toolbar badge on the chats screen and the
     *  modal screen both consume the same flow so a request that
     *  lands on the relay shows up in the badge before the modal
     *  is opened. */
    val approveRequestsViewModel: app.onym.android.group.ApproveRequestsViewModel,
    /** Lowercase BLS pubkey hex of the active identity. Surfaces gate
     *  "am I this group's admin" on it — see `ChatGroup.isAdmin`. */
    val activeBlsPubkeyHex: kotlinx.coroutines.flow.StateFlow<String?>,
    /** The chats a person is waiting to be let into. Single shared
     *  instance — the pending rows in the chats list and the thread
     *  behind them read the same flow, and its watcher runs for the
     *  app's lifetime, so a chat approved while the app was closed is in
     *  the list on the first render. */
    val pendingChatsViewModel: app.onym.android.inbox.PendingChatsViewModel,
    /** Settings → Transport → Nostr Relays. */
    /** `writeScope`: see [makeRelayerSettingsViewModel]. */
    val makeNostrRelaySettingsViewModel: (writeScope: kotlinx.coroutines.CoroutineScope?) -> app.onym.android.settings.NostrRelaySettingsViewModel,
    /** Live snapshot of configured Nostr relays — drives the
     *  Settings entry's "{n} configured" subtitle. */
    val nostrRelaysFlow: kotlinx.coroutines.flow.StateFlow<app.onym.android.transport.nostr.NostrRelaysConfiguration>,
    /** Settings → Transport → Blossom Relays. */
    /** `writeScope`: see [makeRelayerSettingsViewModel]. */
    val makeBlossomServerSettingsViewModel: (writeScope: kotlinx.coroutines.CoroutineScope?) -> app.onym.android.settings.BlossomServerSettingsViewModel,
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
    /** The moderation seat's gate + consent surfaces. `null` exactly
     *  when moderation is dark — no `MODERATION_BASE_URL` configured
     *  and no UI-test fakes registered — in which case RootScreen and
     *  onboarding render as before the seat existed. */
    val moderation: ModerationUiDependencies? = null,
    /** The push seat's Settings surface + lifecycle hooks. `null`
     *  exactly when push is dark — no `PUSH_BASE_URL` configured — in
     *  which case the NOTIFICATIONS section is omitted and nothing
     *  Firebase-shaped runs. */
    val push: PushUiDependencies? = null,
    /** One entry per backup vendor the holder is currently consented
     *  to — `storage.backup` consent is multi-vendor by construction
     *  (see `BackupSeat`'s doc comment), so a device can back up its
     *  history to several operators at once, each under its own
     *  seed-derived key material. Empty exactly when no backup
     *  operator is consented, or the active identity has no recovery
     *  phrase to derive backup keys from — an ordinary state, and
     *  every consumer (Settings) renders as before the feature
     *  existed.
     *
     *  A flow, not a snapshot: consent to a backup operator is written
     *  by the generic module-consent flow mid-session, and a list
     *  resolved once at boot would leave the holder staring at a
     *  Settings screen that says nothing happened until the next
     *  process restart. */
    val backupVendors: kotlinx.coroutines.flow.StateFlow<List<BackupUiDependencies>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
    /** Re-reads the pinned consent store and composes a vendor for
     *  every `storage.backup` operator not already in [backupVendors].
     *  Composing only the MISSING componentIds is deliberate: a vendor
     *  already on the list keeps its instance, and with it its settings
     *  flow, its memoized (expensive) key material, and its
     *  once-per-session schedule jitter. */
    val refreshBackupVendors: suspend () -> Unit = {},
)

/**
 * Everything ONE backup vendor's Settings surfaces need. One instance
 * per consented operator — [AppDependencies.backupVendors] carries the
 * list. Mirrors iOS's `BackupSeatComposer`-produced bundle, extended
 * for multi-vendor.
 */
class BackupUiDependencies(
    /** `onym:component:<operator>` — fixed for this instance's
     *  lifetime (one instance is always exactly one vendor). Used to
     *  key navigation routes and as the row identity in a vendor
     *  list. */
    val componentId: String,
    /** Human-legible label for this vendor's row — currently just
     *  [componentId]'s trailing segment (manifests don't carry a
     *  separate display name field today). */
    val displayName: String,
    val settingsFlow: app.onym.android.backup.ui.DeviceBackupSettingsFlow,
    /** Launches one compose→preflight→upload cycle on the vendor's own
     *  long-lived scope (NOT the caller's) — deliberately not
     *  `suspend`. A backup started from a tapped button must survive
     *  the person navigating away from the screen that started it:
     *  `recordPendingLocked` writes the pending-operation record
     *  before the upload even begins, so a cancelled-by-navigation run
     *  would abandon real, already-committed local state exactly like
     *  a lost network response would — reconcile can recover from
     *  that on a later attempt, but there is no reason to manufacture
     *  the same failure mode on every screen change. */
    val backUpNow: () -> Unit,
    /** Retries a pending-payment operation after a purchase. Same
     *  fire-and-forget posture as [backUpNow]. */
    val retryAfterPurchase: () -> Unit,
    /** Same fire-and-forget posture as [backUpNow] — an erase in
     *  flight must not be interrupted by navigating off the screen
     *  that started it. */
    val erase: () -> Unit,
    /** Call on every app-open (cold start AND resume from
     *  background — both are "opening the app" to a person, and the
     *  consent screen's disclosure sentence doesn't qualify which one
     *  it means) — no-ops instantly unless [BackupSchedule]'s
     *  conditions AND interval are actually satisfied right now. This
     *  is what makes that sentence a description of real behavior
     *  rather than dead code sitting next to honest-sounding copy. */
    val checkOpportunistic: () -> Unit,
    /** Builds the consent/enrolment screen's static disclosure content
     *  for the currently consented (or about-to-be-consented) operator.
     *  `null` if no operator manifest/terms are currently resolvable. */
    val makeEnrolmentDisclosure: suspend () -> Pair<
        List<app.onym.android.backup.ui.BackupDisclosureItem>,
        String,
        >?,
    /** Pins acceptance of the currently-fetched terms (its digest) so
     *  the next [backUpNow] composes and uploads under it. Called once
     *  the enrolment screen's "Turn On Backup" is tapped. */
    val acceptEnrolment: suspend (termsId: String) -> Unit,
    /** History-restore surface — see Phase 8. `null` until wired. */
    val makeRestoreFlow: (suspend () -> app.onym.android.backup.BackupRestoreSummary)? = null,
)

/**
 * Everything the Settings NOTIFICATIONS section (and MainActivity's
 * resume-time revocation check) needs from the push seat, bundled so
 * [AppDependencies] carries one nullable value.
 */
class PushUiDependencies(
    /** The opt-in preference — the toggle's checked state. Default
     *  OFF. */
    val enabledFlow: kotlinx.coroutines.flow.Flow<Boolean>,
    /** Whether the backend has confirmed a registration. `enabled &&
     *  !registered` renders the "still activating" footnote. */
    val registeredFlow: kotlinx.coroutines.flow.Flow<Boolean>,
    /** Flip ON — call only once POST_NOTIFICATIONS is granted (below
     *  API 33 it is granted by install); the Settings host owns the
     *  permission request, and a denial simply never calls this, so
     *  the toggle snaps back on its own. */
    val enable: suspend () -> Unit,
    /** Flip OFF — asks the server to forget this device, retried
     *  until it succeeds. */
    val disable: suspend () -> Unit,
    /** App start / resume: if the preference is ON but the OS has
     *  notifications blocked, runs the full disable path. */
    val checkRevocation: () -> Unit,
)

/**
 * Everything RootScreen's moderation gate and the consent surfaces
 * need, bundled so [AppDependencies] carries one value.
 */
class ModerationUiDependencies(
    /** The RootGate the app root switches on, plus the foreground /
     *  retry / post-consent hooks. */
    val gate: app.onym.android.moderation.ui.ModerationGateFlow,
    /** Fresh consent controller per surface (onboarding step or the
     *  full-screen NeedsConsent host) — each runs its own
     *  one-snapshot review. `resumeExistingMandate`: true for
     *  first-consent hosts (a rotation after a persisted consent
     *  re-emits Consented); false for the enrollment-lost re-consent
     *  host, which needs a FRESH transaction despite the local
     *  record. */
    val makeConsentController: (resumeExistingMandate: Boolean) -> app.onym.android.moderation.ui.ModerationConsentController,
    /** Whether the authority directory currently offers anything to
     *  consent to — the onboarding step's mandatory/Unavailable
     *  arithmetic reads this. Failure answers false (softening toward
     *  operational, never a block on the network). */
    val directoryNonEmpty: suspend () -> Boolean,
    /** The consent/mandate state, for the Settings → Moderation
     *  surface (records, per-identity active mandate, registration
     *  state). */
    val repository: app.onym.android.moderation.ModerationRepository,
    /** Retry delivery of the pending signed mandate (directory lookup
     *  + registerPending); answers a human-readable failure or null
     *  on success / nothing pending. */
    val retryRegistration: suspend () -> String?,
    /** In-app markdown viewer fetch for policy documents — the same
     *  one the consent surface uses, so definition links behave
     *  identically on the Settings terms view. */
    val documents: app.onym.android.moderation.PolicyDocumentFetcher,
    /** Fresh appeal controller per case surface — [state] is the whole
     *  surface's state, so one instance must never serve two cases. */
    val makeCaseAppealController: (caseId: String) -> app.onym.android.moderation.ui.CaseAppealController,
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
    /**
     * Awaits the identity bootstrap and answers whether a snapshot
     * exists — the identity step's checklist binds to this instead of
     * asserting success it can't know about. Idempotent (it awaits
     * the same bootstrap the app kicks at start); a false answer
     * renders the step's failure card with Try again. REQUIRED, no
     * default: the identity step is the walk's one fail-closed gate,
     * and a `{ true }` default would quietly fail it open at any
     * construction site that forgot the wiring.
     */
    val identityReady: suspend () -> Boolean,
    /**
     * Accepting the RECOMMENDED setup on the services step is the
     * explicit confirm of the seeded default directory: this runs
     * the same fetch→verify→pin path the hub's "Verify & Confirm"
     * uses, programmatically, for the SEEDED source only (see
     * RecommendedDirectoryPinner for the trust rationale), then
     * kicks a repository refresh so catalogs populate. Idempotent
     * (no-op when already pinned or removed) and non-blocking:
     * failure (offline first-run) leaves the source unpinned and
     * the Done summary says so. Inert default for builds without
     * discovery.
     */
    val pinRecommendedDirectory: suspend () -> Unit = {},
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
    /**
     * Replace the active identity with one restored from a 12/24-word
     * BIP39 recovery phrase — the welcome step's "I have a recovery
     * phrase" path (iOS parity). Wired to
     * `IdentityRepository.restore`, which wipes the fresh-install
     * auto-bootstrap identity it replaces (its removal cascade runs
     * over an empty chat set on a first run). null — the default, and
     * the UI-test harness posture — hides the entry entirely.
     */
    val restoreIdentity: (suspend (String) -> Unit)? = null,
    /**
     * Whether the welcome step may OFFER the restore entry — probed
     * per walk presentation, because the answer changes at runtime: a
     * Settings → Restart Onboarding walk runs over a real identity
     * with real chats, and [restoreIdentity] would wipe them. Mirrors
     * iOS's `identityRestoreAllowed: !OnboardingLaunch.isExistingUser()`.
     * Fail-closed default: no wiring, no entry.
     */
    val restoreIdentityAllowed: suspend () -> Boolean = { false },
)

/**
 * The [OnboardingUiDependencies.identityReady] probe body: awaits the
 * (idempotent) identity bootstrap and answers whether it produced a
 * snapshot. A plain failure answers `false` (the identity step's
 * failure card + Try again), but a [kotlinx.coroutines.CancellationException]
 * is RETHROWN — a `runCatching`-style swallow would flip a merely
 * cancelled check (the user tapped Back, the composable left the
 * composition) into a scary failure card. Extracted from the
 * composition root so the distinction is unit-testable.
 */
internal suspend fun probeIdentityReady(bootstrap: suspend () -> Unit): Boolean =
    try {
        bootstrap()
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }

/**
 * Everything the discovery settings UI needs, bundled so
 * [AppDependencies] carries one nullable value. Mirrors iOS's
 * `DiscoveryModulePicker` bundling on `discovery-settings-ui`.
 */
class DiscoveryUiDependencies(
    /** Live discovery state: sources, verified entries, fetch status,
     *  per-source errors. */
    val stateFlow: kotlinx.coroutines.flow.StateFlow<app.onym.android.discovery.DiscoveryState>,
    /** Settings → Discovery (provider list + add flow).
     *  `writeScope`: see [AppDependencies.makeRelayerSettingsViewModel]. */
    val makeDiscoverySettingsViewModel: (writeScope: kotlinx.coroutines.CoroutineScope?) -> app.onym.android.settings.DiscoverySettingsViewModel,
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
