# :transport-blossom extraction notes

Extracted from `app/src/main/kotlin/app/onym/android/transport/blossom/`
(packages unchanged: `app.onym.android.transport.blossom`; namespace
`app.onym.android.transportblossom`).

## Files moved

Main (5):
- `BlossomClient.kt` (BlobDescriptor, BlossomClient, BlossomException, OkHttpBlossomClient)
- `BlossomServerEndpoint.kt` (BlossomServerEndpoint, BlossomServersConfiguration)
- `BlossomServersRepository.kt`
- `BlossomServersSelectionStore.kt` (interface + DataStore + InMemory impls)
- `KnownBlossomServersFetcher.kt` (interface, wire doc, GitHub fetcher)

Test (1):
- `BlossomServersRepositoryTest.kt` → `src/test/kotlin/...` (one test removed, see below)

testFixtures (1, from `app/src/sharedTest/kotlin/app/onym/android/support/`):
- `LoopbackBlossomClient.kt` (package `app.onym.android.support` kept) —
  consumed by `app/src/androidTest/.../uitests/LoopbackRegistryHarness.kt`;
  integrator wires `androidTestImplementation(testFixtures(project(":transport-blossom")))`.

## Public symbols and justifying consumers

| Symbol | Consumer(s) outside this module |
|---|---|
| `BlobDescriptor` | `app/src/test/.../chats/SendMessageInteractorTest.kt`; this module's testFixtures `LoopbackBlossomClient` |
| `BlossomClient` | `OnymApplication.kt`, `UITestSupport.kt`, `chats/SendMessageInteractor.kt`, `chats/ChatImageLoader.kt`, `chats/ChatVideoLoader.kt`, `chats/ChatVoiceLoader.kt`, `SendMessageInteractorTest.kt`, `LoopbackBlossomClient` |
| `OkHttpBlossomClient` | `OnymApplication.kt`; `app/src/test/.../chain/BearerAuthScopingTest.kt` |
| `BlossomServerEndpoint` (+ public `custom()`) | `settings/BlossomServerSettingsScreen.kt`, `settings/BlossomServerSettingsViewModel.kt` (VM calls `BlossomServerEndpoint.custom`) |
| `BlossomServersConfiguration` (+ public `empty`) | `AppDependencies.kt`, `settings/BlossomServerSettingsViewModel.kt` (default arg `= BlossomServersConfiguration.empty`) |
| `BlossomServersRepository` | `OnymApplication.kt`, `settings/BlossomServerSettingsViewModel.kt` |
| `BlossomServersSelectionStore` | No direct external reference, but it appears in public signatures external consumers use: `BlossomServersRepository`'s public constructor param and `DataStoreBlossomServersSelectionStore`'s public supertype — an internal type in a public signature is a compile error |
| `DataStoreBlossomServersSelectionStore` | `OnymApplication.kt` |
| `KnownBlossomServersFetcher` | `OnymApplication.kt`, `UITestSupport.kt` |
| `GitHubReleasesKnownBlossomServersFetcher` | `OnymApplication.kt` |

## Marked `internal` (no external consumer found by grep)

- `BlossomException` (top-level) — thrown, never caught by type outside
- `OkHttpBlossomClient.Companion.authorizationHeader` — used only in-file
- `BlossomServerEndpoint.Companion.onymOfficial` — used in-module + this module's unit test only (nostr's twin stays public because *its* test lives in app; blossom's does not)
- `BlossomServersConfiguration.Companion.seed` — in-module + own test only
- `InMemoryBlossomServersSelectionStore` (top-level) — own unit test only (unit tests have friend access to internal)
- `KnownBlossomServersDocument` (top-level) — wire wrapper, fetcher-internal
- `GitHubReleasesKnownBlossomServersFetcher.Companion.DEFAULT_URL` — only used as the constructor default arg (chain module's twins stay public because their tests assert on them)

No accessors exposing secret material in this module (Blossom blobs are
ciphertext; the ephemeral Nostr signer stays behind `:transport`'s
`NostrEphemeralSignerProvider` seam).

## Dependency decisions / trims

Kept (with api/implementation rationale in build.gradle.kts):
- `api(project(":transport"))`, `implementation(project(":transport-nostr"))`,
  `api(project(":foundation"))`
- `api(libs.okhttp)`, `api(libs.androidx.datastore.preferences)`,
  `api(libs.kotlinx.coroutines.core)`, `implementation(libs.kotlinx.serialization.json)`
- Test: `junit`, `kotlinx-coroutines-test`

Trims vs app/build.gradle.kts:
- `:transport-nostr` NOT trimmed — `BlossomClient.kt` really builds a
  `NostrEvent` (kind 24242 BUD-01 auth), but demoted to `implementation`
  (never in a public signature).
- Everything else trimmed: Compose stack (+ compose-compiler plugin), KSP +
  Room, lifecycle/navigation/activity/fragment/biometric, security-crypto,
  BouncyCastle, ZXing, CameraX, Media3, onym-sdk, core-ktx, robolectric,
  datastore-preferences-core, org.json test shim (this module's unit test
  touches no `org.json` path), `:strings`, `:design`.
- `kotlinx-coroutines-android` swapped for `-core` (no Main dispatcher used;
  StateFlow is public API).

## Edits made to moved files (beyond visibility)

1. `BlossomServersRepositoryTest.kt`: removed `viewModel_validatesScheme`
   (+ its `validate` bridge helper + now-unused `assertNotNull` import) —
   it tests `app.onym.android.settings.BlossomServerSettingsViewModel.validate`,
   which lives in :app; a library test cannot depend on :app.
   **Integrator: re-home this test next to the ViewModel:**

   ```kotlin
   @Test
   fun viewModel_validatesScheme() {
       assertNotNull(validate("https://x.com"))
       assertNotNull(validate("http://localhost:3000"))
       assertEquals(null, validate("wss://x.com"))
       assertEquals(null, validate(""))
       assertEquals(null, validate("   "))
   }

   private fun validate(raw: String): String? =
       app.onym.android.settings.BlossomServerSettingsViewModel.validate(raw)
   ```

2. `LoopbackBlossomClient.kt`: replaced `ChatImageCrypto.sha256Hex(blob)`
   (`app.onym.android.chats`, stays in :app) with a private one-line
   `MessageDigest` duplicate — same pattern `OkHttpBlossomClient` already
   uses so the transport layer doesn't import `chats/`. Behavior identical
   (hex SHA-256).

## Open questions for the integrator

1. Wire consumers: `implementation(project(":transport-blossom"))` in :app;
   `androidTestImplementation(testFixtures(project(":transport-blossom")))`
   for `LoopbackRegistryHarness`; `testImplementation(...)` if
   `SendMessageInteractorTest` stays in :app.
2. Ensure `android.experimental.enableTestFixturesKotlinSupport=true` is in
   gradle.properties (also needed by :transport's fixtures).
3. Re-home the removed `viewModel_validatesScheme` test (snippet above).
4. This module assumes `:transport-nostr` exposes
   `app.onym.android.transport.nostr.NostrEvent` and `:transport` exposes
   `app.onym.android.transport.NostrEphemeralSignerProvider` as `public` —
   sibling agents must not mark those `internal` (this module is the
   justifying consumer).
5. `kotlinx.serialization` kept as `implementation`; flip to `api` if any
   consumer starts calling the generated `serializer()` members directly.
