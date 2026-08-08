# :persistence extraction notes

## Public API surface (grep-justified)

| Symbol | Visibility | Justifying consumer(s) outside this module |
|---|---|---|
| `InvitationStore` | public | `app/src/main/.../inbox/IncomingInvitationsRepository.kt` (constructor param + field); `app/src/main/.../group/GroupStore.kt` and `app/src/test/.../support/InMemoryInvitationStore.kt` (implements the seam) |
| `IncomingInvitationRecord` | public | `inbox/IncomingInvitationsRepository.kt` (constructs + maps records); `app/src/test/.../support/InMemoryInvitationStore.kt`; `app/src/test/.../inbox/IncomingInvitationsRepositoryTest.kt` |
| `IncomingInvitationStatus` | public | `inbox/IncomingInvitationsRepository.kt` (`updateStatus` param, `IncomingInvitation.status` field); same test/support files as above |
| `InMemoryInvitationStore` | public | `app/src/main/.../OnymApplication.kt:546` (`app.onym.android.persistence.InMemoryInvitationStore()` — the V1 default store); `app/src/test/.../inbox/IncomingMessageDispatcher*Test.kt`; `app/src/androidTest/.../inbox/IncomingMessageDispatcherConvergeForwardFfiTest.kt` |
| `RoomInvitationStore` | **internal** | No code consumer outside the module (grep hits are KDoc-only). Its test (`RoomInvitationStoreTest`) moved into this module's `src/test` and sees internal. "Not yet wired" per its own KDoc — flip to public when `OnymApplication` swaps it in. |
| `InvitationDao` | **internal** | No consumers anywhere outside the module. |
| `InvitationDatabase` | **internal** | Only KDoc mention (`group/GroupDatabase.kt`). Room's generated `_Impl` lives in this module; `Room.inMemoryDatabaseBuilder` use is in the in-module test. |
| `PersistedInvitation` | **internal** | Only KDoc mention (`group/PersistedGroup.kt`). |

Counts: 4 public, 4 internal (of 8 top-level declarations).

No secret-material accessors here — payload encryption is delegated to
`:foundation`'s `StorageEncryption`; this module never exposes keys.

## Dependency trims (vs. the prescribed list / app deps)

- **`project(":identity")` — TRIMMED.** No Kotlin code in this module
  imports `app.onym.android.identity`. The only references are KDoc
  links (`[app.onym.android.identity.IdentityId.value]` in
  `InvitationStore.kt` / `PersistedInvitation.kt`) — the seam
  deliberately takes the raw `String` "so this seam stays free of the
  identity-layer type" (its own doc). KDoc links don't need a compile
  dep; Dokka (not used) would warn at worst.
- `project(":foundation")` kept as `implementation` (not `api`):
  `StorageEncryption` appears only in the constructor/body of the
  now-internal `RoomInvitationStore`.
- `kotlinx-coroutines-core` instead of `-android`: no `Dispatchers.Main`
  usage; only `Mutex` / `withContext` / `Dispatchers.IO`.
- Everything else from app/build.gradle.kts (compose, serialization,
  okhttp, datastore, camera, media3, bouncycastle, onym-sdk, zxing,
  security-crypto, lifecycle/navigation, robolectric-adjacent org.json)
  not needed and not declared.

## Tests / fixtures

- Moved `app/src/test/.../persistence/RoomInvitationStoreTest.kt` →
  `modules/persistence/src/test/...` (same package). Test deps: junit,
  kotlinx-coroutines-test, robolectric, androidx-test-ext-junit.
- `app/src/sharedTest/.../support/` fakes (InMemoryGroupStore,
  InMemoryIntroKeyStore, InMemoryMessageStore) have group/chats
  subjects — not this module; left alone. `testFixtures` NOT enabled.
- The de-facto fake for THIS module's seam is the **main-source**
  `InMemoryInvitationStore` (kept public, already used by app tests),
  plus a second `app/src/test/.../support/InMemoryInvitationStore.kt`
  which lives in `test/` (not `sharedTest/`) and is consumed by inbox
  tests — left in :app per instructions.

## Open questions for the integrator

1. `app/src/test/kotlin/app/onym/android/support/InMemoryInvitationStore.kt`
   duplicates the main-source fake's contract; if the inbox tests move
   to a module, consider consolidating on the public
   `persistence.InMemoryInvitationStore` or promoting the support fake
   into `:persistence` testFixtures.
2. `RoomInvitationStore` / `InvitationDatabase` were made internal on
   the "not yet wired" basis. Wiring the Room-backed store in
   `OnymApplication` (or a DI module) will require flipping
   `RoomInvitationStore` + `InvitationDatabase` (builder needs the
   class) back to public.
3. Robolectric unit tests in a library module may need
   `testOptions { unitTests { isIncludeAndroidResources = true } }`
   if resource resolution errors appear; :app ran without it, so it
   was not added here.
