# :chain extraction notes

Extracted from `app/` with packages unchanged (`app.onym.android.chain`, fixtures keep
`app.onym.android.support`). Project deps: `api(":foundation")`, `implementation(":strings")`.

## Files moved

Main (`app/src/main/kotlin/app/onym/android/chain/` → `src/main/kotlin/app/onym/android/chain/`),
all 20 files:
`AnchorSelection.kt`, `AnchorSelectionStore.kt`, `BearerAuthInterceptor.kt`,
`CachingChainStateReader.kt`, `CanonicalFr.kt`, `ChainStateReading.kt`, `ContractEntry.kt`,
`ContractsManifestFetcher.kt`, `ContractsRepository.kt`, `GovernanceMember.kt`,
`GroupProofGenerator.kt`, `KnownRelayersFetcher.kt`, `NetworkPreference.kt`,
`RelayerEndpoint.kt`, `RelayerErrorMessages.kt`, `RelayerRepository.kt`,
`RelayerSelectionStore.kt`, `SepContractClient.kt`, `SepContractError.kt`,
`SepContractTypes.kt`.

`strings.R` rewrite: **already done before this extraction** — `ContractEntry.kt`,
`RelayerErrorMessages.kt` and `RelayerEndpoint.kt` were found importing
`app.onym.android.strings.R` (rewritten when `:strings` was extracted). No edit needed.

Unit tests (`app/src/test/.../chain/` → `src/test/kotlin/app/onym/android/chain/`), 14 files:
`AnchorSelectionStoreTest`, `CachingChainStateReaderTest`, `CanonicalFrTest`,
`ContractEntryTest`, `ContractsManifestFetcherTest`, `ContractsRepositoryTest`,
`GroupProofGeneratorTest`, `KnownRelayersFetcherTest`, `RelayerConfigurationTest`,
`RelayerEndpointSchemaTest`, `RelayerRepositoryTest`, `RelayerSelectionStoreTest`,
`SepContractClientTest`, `SepContractTypesTest`.

- ~~`BearerAuthScopingTest.kt`~~ — **moved back to `app/src/test/.../chain/`**: it
  reconstructs `OnymApplication`'s client wiring and imports app-side transport classes
  (`OkHttpBlossomClient`, `GitHubReleasesKnownNostrRelaysFetcher`,
  `NostrEphemeralSignerProvider`, `NostrSigner`), so it cannot compile in `:chain`.
  It still uses `FakeOkHttpClient`, which app tests now reach via
  `testImplementation(testFixtures(project(":chain")))` (integrator wires this).

`app/src/androidTest/` untouched (incl. `app/src/androidTest/.../chain/GroupProofGeneratorFfiTest.kt`).

sharedTest fakes → `src/testFixtures/kotlin/app/onym/android/support/` (9 files):
`ConfigurableContractTransport`, `FakeContractsManifestFetcher`, `FakeKnownRelayersFetcher`,
`FakeSepContractTransport`, `InMemoryAnchorSelectionStore`, `InMemoryChainLedger`
(+ `LedgerSepContractTransport` in the same file), `InMemoryRelayerSelectionStore`,
`StubGroupProofGenerator` — subjects all live in `:chain`.
Judgment call: `FakeOkHttpClient.kt` also moved here although its subject is OkHttp, not a
chain type — its only consumers in the whole repo are this module's fetcher/client tests and
app's `BearerAuthScopingTest`, all of which can consume `testFixtures(":chain")`.
`testFixtures { enable = true }` set; needs
`android.experimental.enableTestFixturesKotlinSupport=true` in `gradle.properties` (integrator).

## Visibility

Kotlin-default-public trimmed to grep-justified surface. Counts over top-level declarations
in main: **57 public, 6 internal**.

### Made internal by this extraction (2)

| Symbol (file) | Why |
|---|---|
| `KnownRelayersDocument` (`RelayerEndpoint.kt`) | Only used in `GitHubReleasesKnownRelayersFetcher` / `DataStorePreferencesRelayerSelectionStore` bodies + own unit tests (friend). |
| `RelayersFetchError` (`RelayerEndpoint.kt`) | Thrown/matched only inside this module (`KnownRelayersFetcher`, `relayerFetchErrorMessageResolver` body); zero outside references. |

### Already internal, kept (4)

`RawContractsManifest`, `RawContractRelease`, `RawContractEntry` (`ContractEntry.kt`),
`RelayerEndpointSerializer` (`RelayerEndpoint.kt`). **Caveat**: the
moved fixture `InMemoryAnchorSelectionStore` calls `RawContractsManifest.serializer()` from
the `testFixtures` compilation — see open questions.

### Flipped internal → public (1)

| Symbol | Justifying consumer |
|---|---|
| `CanonicalFr` (`CanonicalFr.kt`) | `app/src/main/.../group/CreateGroupInteractor.kt` calls `CanonicalFr.randomCanonicalFr32()` — same-module internal before, cross-module now. |

### Public symbols and justifying consumers (57)

Paths relative to `app/src/` unless noted. "fixtures" = this module's own
`testFixtures` (separate compilation — internal is not reliably visible there).

| Symbol | Justifying outside consumer(s) |
|---|---|
| `AnchorSelectionKey` | `settings/AnchorsPickerViewModel.kt`, `group/CreateGroupInteractor.kt`, `group/JoinRequestApprover.kt`, androidTest E2E |
| `AnchorBinding` | `settings/AnchorsPickerViewModel.kt` |
| `AnchorSelectionStore` | `UITestSupport.kt`; fixtures (`InMemoryAnchorSelectionStore`) |
| `DataStorePreferencesAnchorSelectionStore` | `OnymApplication.kt`, `UITestSupport.kt` |
| `BearerAuthInterceptor` | `OnymApplication.kt`, androidTest E2E, app test `BearerAuthScopingTest` |
| `CachingChainStateReader` | `OnymApplication.kt` |
| `ChainStateReading` | `inbox/IncomingMessageDispatcher.kt` |
| `ChainReadError` | androidTest `IncomingMessageDispatcherConvergeForwardFfiTest.kt` |
| `SepContractChainStateReader` | `OnymApplication.kt` |
| `CanonicalFr` | `group/CreateGroupInteractor.kt` (see flip above) |
| `ContractNetwork` | `RootScreen.kt`, `settings/*` screens/VM, androidTest |
| `GovernanceType` | `RootScreen.kt`, `settings/*`, `group/*`, androidTest |
| `ContractEntry` | `settings/AnchorsPickerViewModel.kt`, tests |
| `ContractRelease` | `settings/AnchorsPickerViewModel.kt`, tests |
| `ContractsManifest` | androidTest harnesses, `settings/AnchorsPickerViewModelTest.kt`; fixtures |
| `ContractsManifestFetcher` | `UITestSupport.kt`; fixtures (`FakeContractsManifestFetcher`) |
| `GitHubReleasesContractsManifestFetcher` | `OnymApplication.kt`, `UITestSupport.kt`, androidTest E2E |
| `ContractsState` | type of public `ContractsRepository.snapshots: StateFlow<ContractsState>` (marking internal is a compile error); read by `settings/AnchorsPickerViewModel.kt` |
| `ContractsRepository` | `OnymApplication.kt`, `settings/AnchorsPickerViewModel.kt`, `group/*` |
| `GovernanceMember` | `group/*` (ChatGroup, CreateGroupInteractor, RoomGroupStore, …), inbox tests |
| `GroupCreateProof` | `group/CreateGroupInteractor.kt` |
| `GroupProofCreateInput` | `group/CreateGroupInteractor.kt`, androidTest FFI test |
| `GroupProofGeneratorError` | `group/CreateGroupInteractor.kt`, `group/JoinRequestApprover.kt` |
| `GroupProofGenerator` | `group/CreateGroupInteractor.kt`, `group/JoinRequestApprover.kt`; fixtures (`StubGroupProofGenerator`) |
| `GroupProofUpdateInput` | `group/JoinRequestApprover.kt` |
| `GroupUpdateProof` | return type of public `GroupProofGenerator.proveUpdate`; fixtures |
| `OnymGroupProofGenerator` | `identity/IdentityRepository.kt`, `group/*`, androidTest |
| `KnownRelayersFetcher` | `transport/nostr/KnownNostrRelaysFetcher.kt`, `UITestSupport.kt`; fixtures |
| `GitHubReleasesKnownRelayersFetcher` | `OnymApplication.kt`, `UITestSupport.kt` |
| `AppNetwork` | `RootScreen.kt`, `settings/AnchorsScreen.kt`, `settings/AnchorsPickerViewModel.kt` |
| `NetworkPreferenceProvider` | `AppDependencies.kt`, `chats/ReadReceiptsPreference.kt`, `group/*` |
| `DataStoreNetworkPreferenceProvider` | `OnymApplication.kt` |
| `StaticNetworkPreferenceProvider` | app test `AnchorsPickerViewModelTest.kt`, androidTest E2E |
| `RelayerEndpoint` | `settings/RelayerSettingsScreen.kt` / `RelayerSettingsViewModel.kt`, androidTest; fixtures |
| `RelayerStrategy` | `settings/RelayerSettings*`, androidTest |
| `RelayerConfiguration` | `settings/RelayerSettingsViewModel.kt`, androidTest harnesses; fixtures |
| `RelayerFetchStatus` | `settings/RelayerSettings*` |
| `RelayerState` | type of public `RelayerRepository.snapshots: StateFlow<RelayerState>`; read by `settings/RelayerSettingsViewModel.kt` |
| `relayerFetchErrorMessageResolver` | `OnymApplication.kt` |
| `RelayerRepository` | `OnymApplication.kt`, `UITestSupport.kt`, `settings/*`, `group/*` |
| `RelayerSelectionStore` | `UITestSupport.kt`; fixtures (`InMemoryRelayerSelectionStore`) |
| `DataStorePreferencesRelayerSelectionStore` | `OnymApplication.kt`, `UITestSupport.kt` |
| `SepContractClient` | `group/CreateGroupInteractor.kt`, `group/JoinRequestApprover.kt`, androidTest E2E |
| `SepContractTransport` | `OnymApplication.kt`, `UITestSupport.kt`, `group/*`; fixtures implement it |
| `OkHttpSepContractTransport` | `OnymApplication.kt`, `UITestSupport.kt`, `group/*` |
| `SepContractError` | `group/CreateGroupInteractor.kt`, `group/JoinRequestApprover.kt` |
| `SepGroupType` | very wide: `inbox/*`, `group/*`, `chats/*` + their tests |
| `SepTier` | `inbox/*`, `group/*` + tests |
| `SepNetwork` | androidTest E2E suites |
| `SepContractInvocation` | parameter type of public `SepContractTransport.submit` (internal would be a compile error); fixtures |
| `TyrannyCreateGroupPayload` | `group/CreateGroupInteractor.kt` |
| `OneOnOneCreateGroupPayload` | `group/CreateGroupInteractor.kt` |
| `AnarchyCreateGroupPayload` | `group/CreateGroupInteractor.kt` |
| `TyrannyUpdateCommitmentPayload` | `group/JoinRequestApprover.kt` |
| `GetCommitmentPayload` | fixtures (`InMemoryChainLedger` builds/decodes it); sibling payloads of the same public wire family |
| `SepCommitmentEntry` | `inbox/IncomingMessageDispatcherTest.kt`, androidTest; fixtures |
| `SepSubmissionResponse` | androidTest `CreateGroupInteractorTest.kt`; fixtures |

No secret-material accessors are exposed by this module: `BearerAuthInterceptor` takes the
token as a constructor param and never exposes it; proof inputs hold caller-supplied key
bytes in plain `data class` fields that pre-date the extraction (unchanged).

## Dependency decisions / trims

Kept (from the task's suggested list — all used):
- `chat.onym:onym-sdk` → **implementation** (SDK types only in `OnymGroupProofGenerator` bodies)
- `okhttp` → **api** (`Interceptor` supertype; `OkHttpClient` public ctor params)
- `androidx-datastore-preferences` → **api** (`DataStore<Preferences>` public ctor params)
- `kotlinx-serialization-json` → **api** (`KSerializer` in `SepContractTransport.submit`; public `@Serializable` types)
- coroutines → **api**, but the `-core` alias, not `-android` (StateFlow/Flow in public API; no `Dispatchers.Main` here)
- `:foundation` → **api**, not implementation (`TrustedAssetVerifier` is a defaulted public ctor param of both GitHub releases fetchers; callers need it resolvable)

Trimmed relative to `app/build.gradle.kts` (nothing in `:chain` uses them): all Compose/UI,
lifecycle/navigation/activity, biometric/fragment, security-crypto, BouncyCastle (main —
tests re-add it, see below), zxing, CameraX, Media3, Room/KSP, core-ktx, robolectric,
`org.json`, androidx.test artifacts, `coroutines-android`.

Test-only additions: `testImplementation(libs.bouncycastle)` (fetcher tests hand-sign
Ed25519 fixtures; `:foundation` keeps BC as `implementation`, so it doesn't flow),
`testImplementation(libs.androidx.datastore.preferences.core)` (JVM DataStore factory),
`testImplementation(testFixtures(project(":chain")))` (own fakes).

`androidx.annotation` (`@StringRes` on public properties in `ContractEntry.kt` /
`RelayerEndpoint.kt`) has no catalog alias; it resolves through
`api(androidx-datastore-preferences)` → `androidx.datastore:datastore` → `androidx.annotation`
(compile scope, verified in the cached POM). Integrator may prefer an explicit alias.

## Open questions for the integrator

1. **testFixtures vs `internal`**: `InMemoryAnchorSelectionStore` (fixtures) uses
   `internal RawContractsManifest.serializer()`. sharedTest was a friend compilation of
   app's main; whether AGP 8.11 + Kotlin 2.0.21 testFixtures get friend access to `main`
   internals is unverified here (no gradle allowed). If compilation fails, options:
   (a) add `-Xfriend-paths` for the fixtures compilation, (b) flip `RawContractsManifest`
   (+ `RawContractRelease`, `RawContractEntry`, reachable from its fields) public, or
   (c) rewrite the fixture to build a `ContractsManifest` without the raw wire types.
2. **App consumers of the moved fakes**: app unit tests and androidTest suites that import
   `app.onym.android.support.*` chain fakes (incl. `FakeOkHttpClient`, used by the
   moved-back `BearerAuthScopingTest`) need
   `testImplementation(testFixtures(project(":chain")))` /
   `androidTestImplementation(testFixtures(project(":chain")))` in `app/build.gradle.kts`.
3. **Catalog**: `android-library` plugin alias must exist (also flagged by :strings/:transport);
   `android.experimental.enableTestFixturesKotlinSupport=true` needed in `gradle.properties`.
4. **`KnownRelayersDocument` / `RelayersFetchError` made internal** — if any in-flight
   sibling extraction (e.g. transport implementations) starts referencing them, flip back
   to public with that justification.
