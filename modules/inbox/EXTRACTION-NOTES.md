# :inbox extraction notes

Package `app.onym.android.inbox` moved verbatim from
`app/src/main/kotlin/app/onym/android/inbox/` (10 files) and
`app/src/test/kotlin/app/onym/android/inbox/` (8 files). No package
renames.

## Public symbols and their justifying consumers

Consumers were located by grepping `app/src` + `modules` (excluding this
module) at extraction time. `OnymApplication` / `RootScreen` /
`AppDependencies` / `ChatsScreen` references are fully-qualified
(`app.onym.android.inbox.X`), not imports — grep for `inbox.` when
re-verifying.

| Symbol | Justifying consumer(s) |
| --- | --- |
| `IncomingInvitationsRepository` | `app/src/main/.../OnymApplication.kt:545`; `app/src/androidTest/.../inbox/IncomingMessageDispatcherConvergeForwardFfiTest.kt` (androidTest stays in :app) |
| `IncomingInvitationsInteractor` | `OnymApplication.kt:627` |
| `IncomingMessageDispatcher` | `OnymApplication.kt:602`; FfiTest (androidTest) |
| `GroupStateVerifier` | `OnymApplication.kt:584` (also wires `::retry` into the VM) |
| `GroupStateRefreshing` | FfiTest's `SpyRefresher` implements it (androidTest stays in :app); also a public constructor-parameter type of `IncomingMessageDispatcher` / supertype of public `GroupStateVerifier` |
| `PendingInvitesRecording` | public constructor-parameter type of `IncomingMessageDispatcher` (`pendingInvites:`); OnymApplication passes the shared store through it — internal would be an "exposes internal type" compile error |
| `PendingInvitesStore` | `OnymApplication.kt:579` |
| `PendingVerificationStore` | `OnymApplication.kt:583`; public constructor-parameter type of `GroupStateVerifier` |
| `PendingInvite` | `app/src/main/.../chats/ChatsScreen.kt:110` (`emptyList<app.onym.android.inbox.PendingInvite>()`) — note ChatsScreen is being moved by the :chats-core agent; the reference travels with it |
| `PendingGroupVerification` | `ChatsScreen.kt:112` |
| `PendingInvitesViewModel` | `OnymApplication.kt:730`, `AppDependencies.kt:78`, `ChatsScreen.kt:82` |
| `PendingInvitesScreen` | `RootScreen.kt:275` |
| `PendingInvitesToolbarBadge` | `ChatsScreen.kt:81` |

## Marked internal (no outside consumer found)

- `DecryptedInvitation` — only used by `InvitationDecryptor` + this
  module's tests (identity's KDoc mentions it, comment-only).
- `InvitationDecryptor` — no code consumer anywhere outside the module
  (identity's KDoc mention only). The dispatcher path replaced the
  legacy queue-side decrypt.
- `NoopGroupStateRefresher` — referenced only as the default value of
  `IncomingMessageDispatcher`'s public constructor parameter (default
  value expressions may reference internal declarations).
- `IncomingInvitation` — no external code reference (identity KDoc
  only). Required also marking `IncomingInvitationsRepository.invitations`
  **internal** (a public property may not expose an internal type); no
  external reader of that stream exists — the legacy invitations UI that
  consumed it is gone.

No secret-material accessors in this module (payload bytes held by
`IncomingInvitation`/`PendingInvite` are opaque ciphertext/public keys).

## Dependency trims vs. the planned list

- **`:design` — trimmed.** No `app.onym.android.design` import anywhere
  in the module (PendingInvitesScreen uses raw Material3 + hardcoded
  colors).
- **`:foundation` — added** (was not on the planned list):
  `DecryptedInvitation` uses `foundation.Base64ByteArraySerializer`.
  `implementation` scope (the annotated type is internal).
- No KSP / Room / OkHttp / BouncyCastle / core-ktx needed.

## api vs implementation rationale

`api`: `:chats-core` (MessageRepository/ChatReceiptSending in dispatcher
ctor), `:group`, `:identity`, `:chain` (ChainStateReading in dispatcher
ctor), `:transport`, `:persistence` (InvitationStore ctor param,
IncomingInvitationStatus in `updateStatus`), compose BOM + `compose.ui`
(public @Composables), `lifecycle-viewmodel-compose` (public ViewModel
supertype), `kotlinx-coroutines-core` (StateFlow/CoroutineScope in
public signatures).
`implementation`: `:strings`, `:foundation`, material3/icons/graphics,
`lifecycle-runtime-compose`, `kotlinx-serialization-json`.

## testFixtures

None created. `app/src/sharedTest/.../support/InMemoryMessageStore.kt`
was inspected: its subject is `app.onym.android.chats.MessageStore`
(:chats-core), not an inbox type — left in place for the :chats-core
agent/integrator.

## Open questions for the integrator

1. **`InMemoryMessageStore`** — `IncomingMessageDispatcherChatMessageTest`
   imports `app.onym.android.support.InMemoryMessageStore`, currently in
   `app/src/sharedTest/`. This build file optimistically declares
   `testImplementation(testFixtures(project(":chats-core")))` on the
   assumption the parallel :chats-core extraction moves it into its
   testFixtures under the same package. Rewire if it lands elsewhere.
2. **`InMemoryInvitationStore` duplication** — it exists BOTH at
   `modules/persistence/src/main/.../persistence/InMemoryInvitationStore.kt`
   (main source!) and `app/src/test/.../support/InMemoryInvitationStore.kt`.
   Four dispatcher tests import the `persistence` one (resolves via
   `api(project(":persistence"))`), but `IncomingInvitationsInteractorTest`
   and `IncomingInvitationsRepositoryTest` import
   `app.onym.android.support.InMemoryInvitationStore` — that copy stayed
   in :app's test tree (not this module's subpath, subject lives in
   :persistence). Those two tests won't compile until the import is
   flipped to the `persistence` package or the support copy moves to
   :persistence testFixtures.
3. **androidTest** — `IncomingMessageDispatcherConvergeForwardFfiTest`
   stays in `app/src/androidTest/.../inbox/` per instructions; :app
   needs `implementation(project(":inbox"))` (or androidTestImplementation)
   for it and for OnymApplication/RootScreen/AppDependencies/ChatsScreen.
