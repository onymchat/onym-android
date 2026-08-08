# :transport-nostr extraction notes

Module: `modules/transport-nostr` — Nostr protocol adapter (NIP-01 events,
relay WebSocket connection, inbox/message transports, relay-list
configuration + fetch). Kotlin package stays
`app.onym.android.transport.nostr`; Android namespace is
`app.onym.android.transportnostr` (dot dropped so it is not a
sub-namespace of `:transport`).

## Files moved

Main (8), `app/src/main/kotlin/app/onym/android/transport/nostr/` →
`modules/transport-nostr/src/main/kotlin/app/onym/android/transport/nostr/`:

- KnownNostrRelaysFetcher.kt
- NostrEvent.kt
- NostrInboxTransport.kt
- NostrMessageTransport.kt
- NostrRelayConnection.kt
- NostrRelayEndpoint.kt
- NostrRelaysRepository.kt
- NostrRelaysSelectionStore.kt

Tests (4), `app/src/test/kotlin/app/onym/android/transport/nostr/` →
`modules/transport-nostr/src/test/kotlin/app/onym/android/transport/nostr/`:

- NostrEventTest.kt
- NostrInboxTransportTest.kt
- NostrMessageTransportTest.kt
- NostrRelaysRepositoryTest.kt

No testFixtures: nothing in `app/src/sharedTest/kotlin/app/onym/android/support/`
has its subject in this module (`FakeKnownRelayersFetcher` fakes
`app.onym.android.chain.KnownRelayersFetcher`; `FakeOkHttpClient` is generic
OkHttp scaffolding). `app/src/androidTest` untouched.

## Public API surface (9 public / 4 internal top-level declarations)

Every public symbol below is justified by a grep-verified outside consumer;
everything else was narrowed to `internal`.

| Symbol | Kept | Justifying consumer(s) |
|---|---|---|
| `KnownNostrRelaysFetcher` (interface) | public | `app/src/main/kotlin/app/onym/android/UITestSupport.kt:87` (property type), `OnymApplication.kt:394`; also `NostrRelaysRepository` public ctor param |
| `GitHubReleasesKnownNostrRelaysFetcher` | public | `OnymApplication.kt:396`, `modules/chain/src/test/kotlin/app/onym/android/chain/BearerAuthScopingTest.kt:149` |
| `NostrEvent` | public | `app/src/main/kotlin/app/onym/android/transport/blossom/BlossomClient.kt:80` (`NostrEvent.build`) |
| `NostrInboxTransport` | public | `OnymApplication.kt:438` |
| `NostrRelaysRepository` | public | `OnymApplication.kt:397`, `app/src/main/kotlin/app/onym/android/settings/NostrRelaySettingsViewModel.kt` |
| `NostrRelaysSelectionStore` (interface) | public | type of `NostrRelaysRepository` public ctor param `store` (set from `OnymApplication.kt:398`) |
| `DataStoreNostrRelaysSelectionStore` | public | `OnymApplication.kt:398` |
| `NostrRelayEndpoint` | public | `settings/NostrRelaySettingsViewModel.kt`, `settings/NostrRelaySettingsScreen.kt`; `NostrRelaysRepository` public API |
| `NostrRelaysConfiguration` | public | `app/src/main/kotlin/app/onym/android/AppDependencies.kt:83` (`StateFlow<NostrRelaysConfiguration>`), `settings/NostrRelaySettingsViewModel.kt` |
| `KnownNostrRelaysDocument` | **internal** | wire wrapper used only inside `GitHubReleasesKnownNostrRelaysFetcher` |
| `NostrMessageTransport` | **internal** | no consumer outside this module (only its own unit test, which moved with it); `modules/transport/.../NostrSigner.kt` mentions it in a KDoc comment only |
| `NostrRelayConnection` | **internal** | used only by the two transports' private state |
| `InMemoryNostrRelaysSelectionStore` | **internal** | used only by `NostrRelaysRepositoryTest` (same module) |

No accessor in this module exposes secret material (relay URLs and NIP-01
events only; signing keys stay behind the `:transport` `NostrSigner` seam).

## Dependencies

- `api(project(":transport"))` — `InboxTransport`/`MessageTransport`,
  `NostrSigner`, `NostrEphemeralSignerProvider`, `TransportEndpoint`, etc.
  appear in public signatures.
- `api(project(":foundation"))` — `TrustedAssetVerifier` is a defaulted
  public constructor parameter of `GitHubReleasesKnownNostrRelaysFetcher`.
  (No consumer currently passes one; demote to `implementation` if that
  parameter is ever made internal.)
- `api(libs.kotlinx.coroutines.core)` — `StateFlow` / `CoroutineScope` in
  public signatures. Android-free `-core` on purpose; no Main dispatcher.
- `api(libs.okhttp)` — `OkHttpClient` public ctor param, passed by consumers.
- `api(libs.androidx.datastore.preferences)` — `DataStore<Preferences>`
  public ctor param, passed by `OnymApplication`.
- `implementation(libs.kotlinx.serialization.json)` + `kotlin-serialization`
  plugin — persisted/wire JSON models; serializers not consumed externally.
- `testImplementation`: `junit`, `kotlinx-coroutines-test`, and
  `org.json:json:20240303` (real `org.json` for JVM unit tests — same
  pinned coordinate as `:app`; **not in the version catalog**).

### Trims (in app/build.gradle.kts but NOT needed here)

- `chat.onym:onym-sdk` — **trimmed despite being listed in the extraction
  spec**: no file in this module imports `chat.onym.sdk.*`. Both transports
  take a constructor-injected `NostrEphemeralSignerProvider` precisely so
  the SDK/FFI stays in the identity layer (see KDoc in
  `NostrInboxTransport.kt` / `NostrMessageTransport.kt` line ~48).
- `kotlinx-coroutines-android` — no `Dispatchers.Main` usage; `-core` covers
  `Dispatchers.IO`.
- Compose, KSP/Room, security-crypto, biometric, camera, media3, zxing,
  bouncycastle, robolectric — unused by these files.
- `org.json` at runtime comes from the Android platform (no main-source dep).

## Open questions for the integrator

1. **`NostrMessageTransport` is now `internal`.** Grep found no consumer
   outside the module today (nothing constructs it — only
   `NostrInboxTransport` is wired in `OnymApplication`). If message-path
   wiring lands in `:app` later, flip it back to `public`.
2. **Namespace choice**: spec said use `app.onym.android.transportnostr` if
   AGP rejects the dotted form; gradle was off-limits here so this module
   uses `transportnostr` unconditionally. Harmless either way (no
   resources), but rename if you prefer the dotted convention.
3. `org.json:json:20240303` test dep is declared with a raw coordinate;
   consider adding a catalog alias (`:app` uses the same raw coordinate).
4. Settings wiring (`include(":transport-nostr")`) and the app-side
   `implementation(project(":transport-nostr"))` are left to the
   integration step, per the plan.
