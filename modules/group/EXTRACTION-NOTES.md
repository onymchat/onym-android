# :group extraction notes

Module: `modules/group` — namespace `app.onym.android.group`. Kotlin
packages unchanged (`app.onym.android.group`, `.group.creategroup`,
fixtures in `app.onym.android.support`).

## Files moved

- **main (44)**: everything under `app/src/main/kotlin/app/onym/android/group/`
  (41 files, incl. `DeeplinkCapture.kt`, `OnymBrandSepBridge.kt`,
  `QrCodeAnalyzer.kt`/`QrScannerScreen.kt`, `GroupDatabase.kt` Room setup)
  plus `group/creategroup/` (3 files) →
  `modules/group/src/main/kotlin/app/onym/android/group[/creategroup]/`.
- **test (23 of 25)**: `app/src/test/kotlin/app/onym/android/group/` →
  `modules/group/src/test/kotlin/app/onym/android/group/`.
  **Left in :app**: `GroupNamePayloadTest.kt`, `GroupAvatarPayloadTest.kt`
  — they decode `app.onym.android.chats.ChatMessagePayload`, and :chats
  (extraction in flight) will depend on :group, so a group→chats test
  dependency would be a cycle-shaped wart. They compile in :app, which
  sees both.
- **testFixtures (2)**: `InMemoryGroupStore.kt`, `InMemoryIntroKeyStore.kt`
  (subjects `GroupStore` / `IntroKeyStore` live here) from
  `app/src/sharedTest/.../support/` →
  `modules/group/src/testFixtures/kotlin/app/onym/android/support/`.
  `testFixtures { enable = true }` set. External consumers (app unit +
  androidTest suites) need `testImplementation(testFixtures(project(":group")))`
  — integrator wires.
- No string-import rewrites were needed: all R references already used
  `app.onym.android.strings.R`.

## Visibility (top-level declarations in src/main)

Counts: **52 public / 12 internal / ~30 private** top-level declarations.

Newly marked `internal` (no consumer outside the module — grep over
`app/src` incl. androidTest and `modules/*` excl. group):
`canonicalizeInviteKey`, `OnymGovIcon`, `QrCodeAnalyzer`,
`QrScannerScreen` (only in-module callers: ScanToJoinScreen /
CreateGroupScreen).

Widened `internal → public`: `DeeplinkCapture` — consumed from :app's
`MainActivity`, `transport/DeeplinkCaptureTest` (app unit test) and
`uitests/MultiIdentityChatUITest` (androidTest). Was internal only
because everything shared one module.

Already internal, kept: `decodeHex`, `StoredIntroKeysBlob`,
`StoredIntroKey`, and the five `CreateGroupChrome` composables.

### Public symbols and their justifying consumers

Direct external references (files in :app unless noted):
| Symbol | Consumer |
|---|---|
| ApproveRequestsScreen | RootScreen, inbox/PendingInvitesScreen |
| ApproveRequestsToolbarBadge | chats/ChatsScreen |
| ApproveRequestsViewModel | AppDependencies, OnymApplication, inbox/PendingInvitesViewModel, chats/ChatsScreen |
| ChatGroup | inbox/IncomingMessageDispatcher, chats/* (ChatMessage, ChatsViewModel, …), many app tests |
| CreateGroupInteractor | OnymApplication, AppDependencies, chats/SendMessageInteractor, androidTest E2E + group/CreateGroupInteractorTest |
| CreateGroupError | androidTest integration/CreateGroupOneOnOneE2ETest |
| CreateGroupViewModel | AppDependencies, OnymApplication, RootScreen |
| EncryptedPrefsIntroKeyStore | OnymApplication |
| GroupAvatarBroadcaster | OnymApplication, chats/ChatsViewModel |
| GroupAvatarImage | chats/ChatMembersScreen, androidTest group/GroupAvatarImageTest |
| GroupAvatarPayload | inbox/IncomingMessageDispatcher (+ app test) |
| GroupCommitmentBuilder | inbox/IncomingMessageDispatcher, androidTest FFI tests |
| GroupDatabase, GroupDatabaseMigrations | OnymApplication, chats/MessageDatabase |
| GroupInvitationPayload | inbox/* (dispatcher, interactor, stores), chats/ChatMessagePayload |
| GroupInviteOfferPayload | inbox/IncomingMessageDispatcher, inbox/PendingInvitesStore |
| GroupNamePayload | inbox/IncomingMessageDispatcher |
| GroupRepository | OnymApplication, AppDependencies, inbox/chats/search, identity comments aside — plus androidTest suites |
| GroupStateRefreshRequest | inbox/IncomingMessageDispatcher, inbox/GroupStateVerifier |
| GroupStore | chats/MessageStore, inbox dispatcher tests |
| IntroCapability | MainActivity, RootScreen, AppDependencies, OnymApplication, inbox/PendingInvitesViewModel, transport/DeeplinkCaptureTest |
| InvalidIntroCapability | transport/DeeplinkCaptureTest |
| IntroInboxPump | OnymApplication |
| IntroKeyStore | OnymApplication |
| IntroRequestStore, InMemoryIntroRequestStore | OnymApplication, inbox/PendingInvitesStore |
| InviteIntroducer | OnymApplication, androidTest E2E |
| JoinRequestApprover | OnymApplication, inbox/IncomingMessageDispatcher, chats/ChatsScreen |
| JoinRequestPayload | OnymApplication, inbox/* |
| JoinRequestSender | OnymApplication, inbox/PendingInvitesViewModel |
| JoinScreen | RootScreen, inbox/PendingInvitesScreen |
| JoinViewModel | AppDependencies, OnymApplication, RootScreen, inbox/PendingInvitesViewModel |
| MemberAnnouncementPayload | OnymApplication, inbox/* |
| MemberProfile | chats/* (ChatSenderDisplay, ChatReplyQuote, screens), inbox dispatcher |
| RoomGroupStore | OnymApplication |
| ScanToJoinScreen, CreateGroupScreen, ShareInviteScreen | RootScreen |
| ShareInviteViewModel | AppDependencies, OnymApplication, RootScreen |
| DeeplinkCapture | MainActivity, app tests (see above) |

Public only because a justified-public signature exposes them (Kotlin
"public exposes internal" rule — no direct external reference):
| Symbol | Exposing signature |
|---|---|
| CreateGroupProgress | `CreateGroupInteractor.create(onProgress: (CreateGroupProgress) -> Unit)` |
| CreateGroupRoute, OnymInvitee, OnymUIGovernance, CreateCtaLabel, CreateGroupState | `CreateGroupViewModel.state: StateFlow<CreateGroupState>` (+ `setGovernance`), `CreateGroupState` properties |
| GroupCreator (typealias) | `CreateGroupViewModel` public constructor parameter (constructed in OnymApplication/AppDependencies) |
| GroupDao | `GroupDatabase.groupDao()` (called in OnymApplication) + `RoomGroupStore` ctor |
| PersistedGroup | `GroupDao` method signatures, `GroupDatabase` entity |
| IntroKeyEntry | `IntroKeyStore.save/find/entriesFlow` (+ testFixtures fake) |
| IntroRequest | `IntroRequestStore.requests/record` |
| JoinRequestApproving | public supertype of `JoinRequestApprover`; `ApproveRequestsViewModel` ctor param |

Secret-material surface: `ChatGroup.groupSecret` and
`IntroKeyEntry.introPrivateKey` remain public properties — both types
are wire/store values whose consumers (envelope sealing in :app,
message-key derivation in chats) need the bytes; no accessor was
widened by this extraction.

## Dependency trims (vs. the requested list / app/build.gradle.kts)

- **`:persistence` trimmed** — only KDoc mentions
  (`IntroRequest`, `GroupStore`, `IntroRequestStore`, `GroupDatabase`
  reference it in comments); no code dependency.
- **DataStore trimmed** — no `androidx.datastore` import anywhere in the
  moved files ("datastore if used" — it isn't).
- Also not needed and not declared: navigation-compose, fragment-ktx,
  biometric, media3, compose ui-tooling(-preview) (no `@Preview`),
  `org.json` test shim (no JSONObject in these tests), chain/transport
  testFixtures (no chain fakes used; transport fake — see open Q1).
- api vs implementation rationale is inline in build.gradle.kts;
  api: :chain, :identity, :transport, :foundation, compose-bom+ui,
  lifecycle-viewmodel-compose, coroutines-core, serialization-json,
  room-runtime. Everything else implementation.

## Open questions for the integrator

1. **FakeInboxTransport** (`app/src/test/.../support/FakeInboxTransport.kt`):
   consumed by this module's `IntroInboxPumpTest` *and* app's
   `IncomingInvitationsInteractorTest`. Subject is :transport's
   `InboxTransport`, so its natural home is :transport testFixtures.
   Until it moves (then add `testImplementation(testFixtures(project(":transport")))`
   here), `IntroInboxPumpTest` won't compile.
2. **Two payload tests left in :app** (`GroupNamePayloadTest`,
   `GroupAvatarPayloadTest`) because they exercise round-trips through
   chats' `ChatMessagePayload`; revisit once :chats lands (could move to
   :chats' tests or take a `testImplementation(project(":chats"))` here —
   legal, but smells like a cycle).
3. **DeeplinkCaptureTest** sits in `app/src/test/.../transport/` but its
   subject (`DeeplinkCapture`) now lives here; left untouched (compiles
   in :app). Candidate to move into this module later.
4. androidTest suites (`app/src/androidTest/.../group/` and the E2E/uitests)
   stay in :app per plan; they only use symbols kept public, and need
   `androidTestImplementation(testFixtures(project(":group")))` for
   `InMemoryGroupStore`/`InMemoryIntroKeyStore`.
5. Same AGP note as :chain — Kotlin testFixtures sources need
   `android.experimental.enableTestFixturesKotlinSupport=true` in
   gradle.properties.
6. No `AndroidManifest.xml` written (AGP 8 doesn't require one). The
   CAMERA permission the QR scanner requests at runtime stays declared
   in :app's manifest.
