# onym-android

Android app for Onym, built incrementally on top of
[`onym-sdk-kotlin`](https://github.com/onymchat/onym-sdk-kotlin).

This repo is being grown from scratch — small, hand-reviewable chunks.
This first PR lands two things in one go: a minimal Compose app
scaffold consuming OnymSDK, and the persistent reactive identity
repository on top of it. Equivalent role to onym-ios Chunks 1 + 2.

## Setup

```sh
git clone https://github.com/onymchat/onym-android.git
cd onym-android
./gradlew :app:assembleDebug
```

JDK 17, Gradle 8.14, AGP 8.7, Kotlin 2.0.21, min SDK 26, target/compile
SDK 35.

The OnymSDK AAR is fetched from the static Maven repository hosted
on the [`releases`](https://github.com/onymchat/onym-sdk-kotlin/tree/releases)
branch of `onym-sdk-kotlin` — see `settings.gradle.kts`. No
contributor-side `publishToMavenLocal` step needed; the network fetch
is the only requirement.

## Architecture

Four layers, each isolated from the others by an explicit seam.
Solid boxes exist today; dashed boxes are planned.

```
                                          ┌────────────────────────────────────┐
                                          │ Composables (Jetpack Compose)      │
                                          │ stateless · pure render            │
                                          │ RootScreen · SettingsScreen ·      │
                                          │ RecoveryPhraseBackupScreen         │
                                          └──────────┬──────────────▲──────────┘
                                                     │ intent       │ snapshot
                                                     ▼              │
                                          ┌──────────────────────────────────┐
                                          │ ViewModels (StateFlow exposed)   │
                                          │ owns flow state, not domain      │
                                          │ state · no I/O · no persistence  │
                                          │ RecoveryPhraseBackupViewModel    │
                                          │ ╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶     │
                                          │ ╎ planned: ChatViewModel ·     ╎ │
                                          │ ╎          InviteViewModel     ╎ │
                                          │ ╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶     │
                                          └──────────┬──────────────▲────────┘
                                                     │ command      │ snapshot
                                                     ▼              │
                                          ┌──────────────────────────────────┐
                                          │ Repositories (Mutex + StateFlow) │
                                          │ stateful · own ALL I/O ·         │
                                          │ StateFlow<T> reactive surface    │
                                          │                                  │
                                          │   IdentityRepository             │
                                          │   RelayerRepository              │
                                          │   ContractsRepository            │
                                          │   GroupRepository                │
                                          └──┬────────────────────────────┬──┘
                                             │                            │
                        ┌────────────────────┘                            └───────────────────┐
                        ▼                                                                     ▼
          ╔═══════════════════════╗                                       ╔══════════════════════════════╗
          ║ Persistence (seam)    ║                                       ║ Transport (seam)             ║
          ║ IdentitySecretStore   ║                                       ║ MessageTransport             ║
          ║ ╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶   ║                                       ║ InboxTransport               ║
          ║ ╎ planned: SQLite ╎   ║                                       ║                              ║
          ║ ╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶╶   ║                                       ║                              ║
          ╚══════════╤════════════╝                                       ╚══════════╤═══════════════════╝
                     │                                                               │
                     ▼                                                               ▼
          ┌──────────────────────────┐                          ┌────────────────────┬───────────────────────┐
          │ EncryptedSharedPrefs     │                          │ Nostr (today)      │ ╎ planned: Tor       ╎│
          │ + Android Keystore       │                          │ NostrRelayConn ·   │ ╎ HiddenServiceConn  ╎│
          │ AES256_GCM               │                          │ NostrEvent · NIP-01│ ╎ (drop-in adapter)  ╎│
          └──────────────────────────┘                          └────────────────────┴───────────────────────┘
                                          ╔════════════════════════════════════════╗
                                          ║ OnymSDK (FFI primitives)               ║
                                          ║ Common · Anarchy · OneOnOne ·          ║
                                          ║ Tyranny — Plonk · Poseidon · BLS ·     ║
                                          ║ BIP340 Nostr signing                   ║
                                          ║ called only by narrow crypto adapters  ║
                                          ║ owned by identity / chain / group;     ║
                                          ║ never by composables, ViewModels,      ║
                                          ║ transport, or persistence              ║
                                          ╚════════════════════════════════════════╝
```

### Touch-surface rules

What each layer is allowed to call. Statically enforced where possible
(access modifiers, `scripts/lint-secrets.py`); load-bearing in code
review where it isn't.

| Layer | May call | Forbidden |
|---|---|---|
| **Composable** | its own ViewModel (intents in, snapshots out) | repository directly · `OnymSDK` · `EncryptedSharedPreferences` · transport · `OkHttp` · another ViewModel |
| **ViewModel** | repositories (commands + snapshots) · stateless interactor functions · ViewModel-local I/O affordances (`ClipboardWriter`, `BiometricAuthenticator`, `StringProvider`) | `OnymSDK` · `EncryptedSharedPreferences` · transport · disk · network · another ViewModel's internals |
| **Repository** | persistence seam · transport seam · narrow crypto adapters / `OnymSDK` when owned by the repository's domain | another repository's internals · composables · ViewModels |
| **Persistence / Transport seam** | the one concrete backend it implements | repositories · `OnymSDK` · the other seam |
| **OnymSDK** | itself | everything above |

Three extra invariants that cut across the layers:

- **Secret material never becomes UI or shared state.** No outside
  caller reads `nostrSecretKey` / `blsSecretKey` / `entropy` /
  `recoveryPhrase` off `Identity` or any other snapshot value. The one
  intentional raw-secret hop is `IdentityRepository.blsSecretKey()` for
  immediate PLONK proof generation; callers must not retain, persist,
  log, or render that value. Enforced where possible by
  `scripts/lint-secrets.py` (default-deny diff check; see *Static
  lint*) and otherwise by review.
- **Reactive flow is unidirectional.** Repositories publish via
  `StateFlow<T>`; ViewModels observe and command; composables observe
  and intent. No bidirectional bindings, no shared mutable state
  across composables, no composable-side mutation.
- **ViewModel-local I/O affordances are interfaces, not concrete
  Android types.** `BiometricAuthenticator`, `ClipboardWriter`,
  `StringProvider` are Kotlin `interface`s defined alongside the
  ViewModel; the production `Android*` impls are wired via
  `AppDependencies` at the composition root. Tests substitute fakes
  without dragging in `LocalContext` / `BiometricPrompt` /
  `ClipboardManager`.

### How to reason about the architecture

Reason from ownership, not from package names alone. For any proposed
change, first ask which value is the source of truth and how long it
must live:

1. **Render-only state belongs to composables.** `remember` is fine for
   focus, expanded menus, and small purely visual affordances. Anything
   that survives navigation, needs testing as a state machine, or
   coordinates side effects belongs lower.
2. **Screen-flow state belongs to a ViewModel.** Text fields, selected
   cards, route enums, progress flags, and user-facing errors live in a
   ViewModel-backed `StateFlow`. Composables collect state and send
   intents; they do not derive domain state or perform I/O.
3. **Durable or shareable state belongs to a repository.** Identities,
   relayer configuration, contract-anchor selection, chats, and incoming
   invitations are owned by `Mutex` + `StateFlow` repositories. If
   multiple screens need it, if it survives process death, or if new
   collectors need an immediate replay, it is repository state.
4. **One-shot workflows belong to stateless interactors.** If an
   operation spans multiple repositories and seams, keep the pipeline in
   an interactor such as `CreateGroupInteractor` or
   `IncomingInvitationsInteractor`. It may coordinate dependencies and
   return a result, but persistence happens by commanding the owning
   repository.
5. **Concrete I/O belongs behind a seam.** EncryptedSharedPreferences,
   DataStore, Room, OkHttp, Nostr relays, relayer POSTs, biometrics,
   clipboard, and resources are accessed through a small interface or
   adapter. Tests swap the seam, not the caller.
6. **FFI belongs behind a named crypto adapter.** `OnymSDK` calls stay
   in narrow wrappers such as `IdentityRepository`, `OnymNostrSigner`,
   `OnymGroupProofGenerator`, and `GroupCommitmentBuilder`. Higher
   layers depend on their Kotlin contracts, not on SDK symbols.

The runtime shape is always:

```
composable intent
  -> ViewModel method
  -> repository command OR stateless interactor pipeline
  -> seam / crypto adapter / transport
  -> repository StateFlow
  -> ViewModel StateFlow
  -> composable render
```

When a change feels awkward, inspect the direction of that loop. Most
architecture mistakes are a dependency trying to travel upward: a
composable reaching into a repository, a ViewModel doing network work,
a transport learning domain semantics, or an interactor keeping state
that should be replayed by a repository.

### Assumptions you can trust while working with this architecture

- `OnymApplication` is the composition root. It builds repositories,
  concrete stores, fetchers, transports, crypto providers, and
  app-scoped coroutines once, then exposes only `AppDependencies`
  factories to `MainActivity` / `RootScreen`.
- Composables do not own repositories. If a composable needs behavior,
  add an intent to its ViewModel or pass a child-ViewModel factory
  through `AppDependencies`.
- Repositories are `Mutex` + `StateFlow` owners. Mutations are
  serialized, reads happen from hot replaying `StateFlow`s, and a
  successful mutation should publish a fresh value.
- ViewModels own transient UI state. They expose immutable `StateFlow`
  surfaces, call repositories or stateless interactors from
  `viewModelScope`, and keep UI-only affordances behind local
  interfaces.
- Interactors are stateless coordinators. They can depend on multiple
  repositories and seams for one operation, but they do not become the
  durable source of truth.
- Persistence and transport implementations are replaceable. Code above
  the seam should not know whether storage is EncryptedSharedPreferences,
  DataStore, Room, in-memory fakes, OkHttp, Nostr, or a test transport.
- `OnymSDK` imports are exceptional and explicit. Adding a new SDK call
  means adding or extending a narrow adapter, plus tests for byte shape
  and cross-platform fixtures where applicable.
- Secret-bearing values are not rendered, logged, cached in ViewModel
  state, or exposed on UI snapshots. The BLS scalar may cross from
  `IdentityRepository` to proof generation only for the immediate
  create/update operation.
- Multi-identity state is first-class. `IdentityRepository` owns the
  identity index and active selection; `GroupRepository.snapshots`
  filters by `currentIdentityId`; inbox fan-out subscribes to every
  identity's inbox tag, not just the active one.

### Why this beats the reference impl in `stellar-mls/clients/android`

- **Transport is a seam, not a class.** `MessageTransport` /
  `InboxTransport` are Kotlin interfaces; the Nostr implementation is
  one of several possible adapters. A future Tor / hidden-service /
  `wss://` mesh / mock transport drops in without touching any caller
  above the seam. In the reference impl, `NostrMessageTransport.kt`
  and `InvitationTransport.kt` co-mingle chat semantics (`GroupCrypto`,
  BLS attestation, member tracking) with relay framing in the same
  files, which is why a transport swap there is a refactor, not a
  substitution.
- **Persistence is a seam too.** `IdentityRepository` talks to an
  `IdentitySecretStore` reference — swapping in a SQLite-backed or
  in-memory store for a different deployment / test environment is a
  constructor change, not a rewrite.
- **ViewModels own only flow state.** The reference impl threads
  identity / chat state through ad-hoc Activity-scoped state-holders
  that double as orchestrators. We split orchestration per-flow
  (`RecoveryPhraseBackupViewModel` today, `ChatViewModel` /
  `InviteViewModel` later); each owns its own state machine and
  *only* its state machine. Repository state stays in the repository.
- **Composables never close over a repository.** Compose composables
  observe a ViewModel's `StateFlow` and dispatch intents; the
  ViewModel is the only thing that holds a reference to a repository.
  A composable written against this layering can be redesigned (or
  A/B-tested, or skinned for a Wear OS surface) without changing
  anything below it.
- **`OnymSDK` is callable only from narrow crypto adapters.** The
  Nostr transport imports zero `chat.onym.sdk.*` symbols — it asks an
  injected `NostrEphemeralSignerProvider` for a signer. Group proof and
  commitment code follow the same shape: callers see
  `GroupProofGenerator` / `GroupCommitmentBuilder`, not raw SDK
  entrypoints.

### Why `IdentityRepository` is the cryptographic root

Identity is the only repository that owns long-lived device secrets and
derives the public identity surface from EncryptedSharedPreferences.
Relayer and contracts repositories can start without it, but any
operation that signs, decrypts, proves group membership, addresses an
inbox, or builds a Soroban caller address ultimately depends on
identity-derived material.

That makes `IdentityRepository` the cryptographic root, not a global
state bag. `OnymApplication` constructs it near the top of
`buildDependencies`, bootstraps it eagerly on the application scope,
and injects it only into ViewModels, interactors, repositories, or
adapters that need identity-derived operations.

### Why `StateFlow` instead of `actor + AsyncStream`

iOS uses Swift `actor` + `AsyncStream` because SwiftUI's
`@Observable` macro doesn't auto-subscribe to streams — every view
needs an explicit `.task { for await … }` loop.

Kotlin / Compose has no equivalent gap: `StateFlow` is the canonical
hot reactive surface, Compose's `collectAsStateWithLifecycle()`
handles subscription / unsubscription via `Lifecycle`, and `Mutex`
serialises mutation cleanly. So the Android port keeps the same
semantics (current value on subscribe, then one new value per
mutation, multi-subscriber safe) with less ceremony.

## Current state

```
.
├── settings.gradle.kts                              ← Maven repo wiring for OnymSDK
├── build.gradle.kts                                 ← top-level plugin block
├── gradle/libs.versions.toml                        ← version catalog
├── app/
│   ├── build.gradle.kts                             ← Android Compose app config
│   ├── proguard-rules.pro                           ← keep OnymJni + BC
│   ├── src/main/
│   │   ├── AndroidManifest.xml                      ← allowBackup=false + backup-rules
│   │   ├── res/xml/{backup_rules,data_extraction_rules}.xml
│   │   ├── res/values/strings.xml
│   │   ├── resources/bip39-english.txt              ← 2048-word English wordlist (load via classpath)
│   │   └── kotlin/chat/onym/android/
│   │       ├── OnymApplication.kt                   ← BC provider + AppDependencies wiring
│   │       ├── AppDependencies.kt                   ← composition-root handle (factory closures)
│   │       ├── MainActivity.kt                      ← FragmentActivity host; reads AppDependencies
│   │       ├── RootScreen.kt                        ← Scaffold + NavigationBar + NavHost
│   │       ├── identity/
│   │       │   ├── Identity.kt                      ← snapshot value type the views see
│   │       │   ├── IdentityRepository.kt            ← StateFlow + Mutex + derivation
│   │       │   ├── IdentitySecretStore.kt           ← EncryptedSharedPreferences wrapper
│   │       │   ├── StoredSnapshot.kt                ← @Serializable JSON blob (Base64 ByteArrays)
│   │       │   ├── IdentityError.kt                 ← single error type
│   │       │   ├── Bip39.kt                         ← BIP39 wordlist + PBKDF2 + HKDF
│   │       │   ├── StellarStrKey.kt                 ← Ed25519 → G… account ID encoder
│   │       │   └── OnymNostrSigner.kt               ← BIP340 signer + EphemeralSignerProvider
│   │       └── transport/
│   │           ├── Transport.kt                     ← MessageTransport / InboxTransport interfaces
│   │           └── nostr/                           ← Nostr adapter (NIP-01 framing, OkHttp WS)
│   ├── src/test/kotlin/chat/onym/android/identity/
│   │   ├── CrossPlatformFixtureTest.kt              ← derivation locks (no FFI needed)
│   │   ├── Bip39Test.kt                             ← wordlist SHA-256 + round-trip + edge cases
│   │   └── StellarStrKeyTest.kt                    ← known-vector StrKey encodings
│   └── src/androidTest/kotlin/chat/onym/android/identity/
│       └── IdentityRepositoryTest.kt                ← real EncryptedSharedPreferences + FFI
└── .github/workflows/ci.yml                         ← JVM unit tests on PR/push
```

`applicationId` is `chat.onym.android`. EncryptedSharedPreferences
file is `chat.onym.android.identity.xml` (excluded from auto-backup
and device-to-device transfer — see
`app/src/main/res/xml/data_extraction_rules.xml`).

## Identity persistence

One [`EncryptedSharedPreferences`](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)
file (default name `chat.onym.android.identity`) holds a JSON-encoded
`StoredSnapshot`:

```kotlin
@Serializable
data class StoredSnapshot(
    val entropy: ByteArray?,        // 16 bytes (BIP39 128-bit entropy)
    val nostrSecretKey: ByteArray,  // 32 bytes (secp256k1)
    val blsSecretKey: ByteArray,    // 32 bytes (BLS12-381 Fr)
)
```

Single-blob layout means every mutation is one atomic
`editor.putString(...).commit()` — there's no intermediate state where
one secret has been written and another has not.

The master key is `AES256_GCM` from the Android Keystore:
hardware-backed on devices with a secure element, software-backed on
emulators. The encrypted blob is useless to anyone without the
matching Keystore handle, so `allowBackup="false"` + the
`data_extraction_rules.xml` exclusions defeat both Google Drive
auto-backup and Android-12+ direct device-to-device transfer for the
identity prefs file.

## Derivation

`Identity` exposes four public keys plus two derived identifiers.
Two pairs are persisted (nostr + BLS); the other two are HKDF-derived
from the nostr secret on every load. The private halves of the
derived pairs stay inside the repository.

Constants are **byte-identical** to onym-ios Chunk 2 and the
`stellar-mls/clients/ios/StellarChat` reference impl, so a recovery
phrase generated on any client restores the same identity on any
other.

| Step                  | Input          | Salt                      | Info                       | Algorithm                       |
|-----------------------|----------------|---------------------------|----------------------------|---------------------------------|
| Seed                  | mnemonic       | `"mnemonic"+passphrase`   | —                          | PBKDF2-HMAC-SHA512, 2048 iters  |
| Nostr secret          | seed           | `chat.onym.bip39`         | `nostr-secp256k1-v1`       | HKDF-SHA256, 32B                |
| BLS secret            | seed           | `chat.onym.bip39`         | `bls12-381-v1`             | HKDF-SHA256, 32B                |
| Stellar Ed25519 seed  | nostr secret   | `chat.onym.ios`           | `stellar-ed25519-v1`       | HKDF-SHA256, 32B                |
| X25519 seed (inbox)   | nostr secret   | `chat.onym.ios`           | `x25519-key-agreement-v1`  | HKDF-SHA256, 32B                |
| Inbox tag             | X25519 pubkey  | —                         | prefix `sep-inbox-v1`      | SHA-256, hex(prefix(8))         |
| Stellar account ID    | Ed25519 pubkey | —                         | version byte `6 << 3 = 48` | StrKey (CRC16-XMODEM + base32)  |

The salt difference between the two HKDF stages — `chat.onym.bip39`
for derivations off the BIP39 seed, `chat.onym.ios` for derivations
off the nostr secret — is a quirk of the reference impl. Note the
"ios" in the second stage is preserved on Android too, intentionally;
it's a constant string, not a platform marker.

PBKDF2 password and salt are NFKD-normalized per the BIP39 spec. For
all-ASCII mnemonics + empty passphrase (this app's default) NFKD is
a no-op, but a future custom passphrase containing non-ASCII would
otherwise produce different bytes than iOS / stellar-mls.

### Why all four pubkeys live on `Identity`

| Field             | Used by                                          |
|-------------------|--------------------------------------------------|
| `nostrPublicKey`  | Nostr event verification, npub display           |
| `blsPublicKey`    | SEP plonk membership proofs                      |
| `stellarPublicKey`| Transport bundles, attestations, envelope sigs   |
| `stellarAccountID`| `callerAddress` on every Soroban contract call   |
| `inboxPublicKey`  | ECDH target — peers encrypt invitations to us    |
| `inboxTag`        | Discoverable handle posted as a Nostr `#t`/`#d`  |
| `recoveryPhrase`  | Recovery UI                                      |

The Stellar Ed25519 and X25519 **private** halves never leave the
repository. When signing/decryption methods land they'll be
`repo.stellarSign(_)` / `repo.decryptInvitation(_)` — not raw
private-key access.

## Tests

Two layers, mirroring the iOS Chunk 2 split (modulo Android's
JVM/instrumented divide):

| Layer        | Module                  | Speed   | Covers |
|--------------|-------------------------|---------|--------|
| JVM unit     | `app/src/test/`         | < 5 sec | Bip39 wordlist integrity + round-trip; `StellarStrKey` known vectors; cross-platform fixture for the project-specific derivation chain (Bip39 → HKDF → Curve25519 → StrKey + inbox tag). `chat.onym:onym-sdk` IS on the compile classpath but no FFI is invoked, so no `.so` loading needed. |
| Instrumented | `app/src/androidTest/`  | ~30 sec on emulator | Real EncryptedSharedPreferences + Android Keystore; full `IdentityRepository` lifecycle (bootstrap, restore, wipe, snapshot replay, multi-subscriber); cross-platform fixture's FFI half (`nostrPublicKey` from `Common.nostrDerivePublicKey`, `blsPublicKey` from `Common.publicKey`). |

Run JVM tests:

```sh
./gradlew :app:testDebugUnitTest
```

Run instrumented tests against an emulator or attached device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

CI runs the JVM half on every PR / push (`.github/workflows/ci.yml`).
Instrumented tests are local-only for now — the cross-platform
fixture's FFI half is the only test that needs real-Android, and the
JVM-unit half already locks in every project-specific derivation
constant. Adding an emulator job to CI is a follow-up.

### Cross-platform fixture

`CrossPlatformFixtureTest` and `IdentityRepositoryTest.derivation_matchesCrossPlatformFixture`
both lock derivation against `abandon × 11 + about` (the canonical
BIP39 test vector for 16-byte all-zeros entropy). Together they cover:

- PBKDF2-SHA512 seed = `5eb00bbd…` (BIP39 spec test vector)
- Stellar pubkey = `2d26005f…`, account ID = `GAWSMAC772XXRU4FQHQMDQOOUOT24XMVCCYCCWQSFQVYY7VCJRQRRF2K`
- Inbox pubkey = `677244099e15…`, tag = `2257fa71222dcc05`
- (instrumented only) Nostr pubkey = `1ee9632e…`, BLS pubkey = `93c738ad…`

If any constant in the derivation chain drifts, one of these tests
breaks loudly. **Do not change a salt / info / algorithm without
coordinating with iOS and stellar-mls.**

## Static lint — no off-repo secret reads

`scripts/lint-secrets.py` is a default-deny check that any field-access
read of `.nostrSecretKey`, `.blsSecretKey`, `.recoveryPhrase`, or
`.entropy` outside an explicit allowlist fails the build. Wired into
`.github/workflows/ci.yml` as the first hard gate — JVM unit tests
won't even run if the linter trips.

The allowlist (in the script itself; adding to it requires
justification in code review):

```
app/src/main/kotlin/chat/onym/android/identity/IdentityRepository.kt
app/src/main/kotlin/chat/onym/android/identity/StoredSnapshot.kt
app/src/main/kotlin/chat/onym/android/identity/Identity.kt
app/src/androidTest/kotlin/chat/onym/android/identity/IdentityRepositoryTest.kt
```

Note `IdentitySecretStore.kt` is **not** allowlisted — kotlinx.serialization
generates the field-access code into `build/generated/`, which the
linter skips, so the source file itself never reads these fields with
`.fieldName` syntax.

To allow a specific read, annotate the line itself or any `//`
comment line in the contiguous block directly above with
`// onym:allow-secret-read`. Existing suppressions cite the
surrounding context (e.g., the recovery-phrase reveal step in
`RecoveryPhraseBackupViewModel.kt`).

The linter also catches Kotlin destructuring with secret-named
bindings (`val (entropy, …) = snapshot`) — the only way besides
`.fieldName` to read the secrets in idiomatic Kotlin. Reflection-
based reads are a known hole; flag in code review if you see one.

To run locally: `python3 scripts/lint-secrets.py`. Exits 0 on
success, 1 on any unsuppressed violation.

## Release pipeline

`.github/workflows/release.yml` — manual `workflow_dispatch`, builds a
signed universal APK and attaches it to a fresh GitHub Release.

```sh
gh workflow run Release -f tag=v0.0.1
```

Job graph:

```
       ┌─── lint ──────────────┐
       │                       │
       ├─── test ──────────────┤
       │                       ▼
trigger┤                     build  →  GitHub Release
       │                       ▲       (signed APK attached)
       └─── create-release ────┘
            (notes + Release at the tag)
```

The build job runs `assembleRelease` (no `signingConfig` declared on
the release buildType, so AGP emits an unsigned APK), `zipalign`s
the result with `-p 4` for 16 KB page-aligned native libs (Android
15+ requirement), then signs with `apksigner` using a keystore
decoded from the `ANDROID_KEYSTORE_BASE64` repo secret. APK is then
uploaded to the Release the previous job created.

APK is the only artifact: no AAB, no Play Store, no F-Droid. Single
universal APK, all four ABIs bundled (~29 MB at v0.0.1).

See [`keystore/README.md`](keystore/README.md) for keystore management
+ secret rotation. Required repo secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Recovery-phrase backup flow

The app's only screen today is the
[`RecoveryPhraseBackupScreen`](app/src/main/kotlin/chat/onym/android/recovery/RecoveryPhraseBackupScreen.kt)
flow — Intro → Reveal → Verify → Done. Mirrors onym-ios PR #4 1:1
in shape, ported to Compose + StateFlow + AndroidX BiometricPrompt.

```
.intro
  │ tappedContinueFromIntro → authenticate()
  ├─► .authFailed(reason) ── dismissedAuthError → .intro
  └─► .reveal(phrase)
        │ tappedCopyPhrase → clipboard write + 60s auto-clear
        │ tappedContinueFromReveal
        ▼
      .verify(phrase, rounds: 3, index: 0, .idle)
        │ picked(word):
        │   correct → .correct, delay 450ms,
        │             then advance index OR transition to .done
        │   wrong   → .wrong(word), retry same round
        ▼
      .done
        │ tappedDoneFromCompletion → .intro (single-screen app loop)
```

3 random verify rounds × 4 options each (one correct + three
distractors from the same phrase). Wrong pick stays on the same
round; second pick during the in-flight 450 ms advance is ignored.

### Side-effect seams

- **`BiometricAuthenticator`** — interface + `AndroidBiometricAuthenticator`
  backed by [`BiometricPrompt`](https://developer.android.com/reference/androidx/biometric/BiometricPrompt).
  Requires a [`FragmentActivity`](https://developer.android.com/reference/androidx/fragment/app/FragmentActivity)
  host (so [`MainActivity`](app/src/main/kotlin/chat/onym/android/MainActivity.kt)
  extends `FragmentActivity`, not `ComponentActivity`). Takes an
  *activity provider thunk* rather than a captured Activity reference
  so the long-lived [`RecoveryPhraseBackupViewModel`](app/src/main/kotlin/chat/onym/android/recovery/RecoveryPhraseBackupViewModel.kt)
  doesn't pin an Activity past configuration change. If the device has
  no enrolled biometric and no device credential, returns success
  without prompting (matches iOS `canEvaluatePolicy == false`
  fail-open).
- **`ClipboardWriter`** — interface + `AndroidClipboardWriter` over
  [`ClipboardManager`](https://developer.android.com/reference/android/content/ClipboardManager).
  Sets the [`IS_SENSITIVE`](https://developer.android.com/about/versions/13/features#copy-paste)
  extra on Android 13+, so the system's clipboard preview shows a
  redacted "Sensitive" placeholder instead of the actual phrase.

### Screenshot / recents protection

`MainActivity` sets [`WindowManager.LayoutParams.FLAG_SECURE`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)
on the window before `super.onCreate`. This:

- Blocks screenshots and screen recording across the entire app.
- Renders the recents thumbnail as a blank surface.

Stronger than iOS's scene-phase obscure overlay (which only blanks
the recents preview on backgrounding) — Android's flag covers both.

### Tests

[`app/src/androidTest/.../RecoveryPhraseBackupViewModelTest.kt`](app/src/androidTest/kotlin/chat/onym/android/recovery/RecoveryPhraseBackupViewModelTest.kt)
ports all 13 iOS XCTest cases against a real
`IdentityRepository` (per-test unique prefs file), a fake
`BiometricAuthenticator`, and a fake `ClipboardWriter`. Lives in
`androidTest/` (not `test/`) because the real repository requires
Android Keystore — Robolectric doesn't simulate it. Same rule as
the identity-layer instrumented tests, same rationale as iOS's
"real Keychain behaviour or it doesn't count".

Inject short delays in the test fixture
(`clipboardClearDelay = 50ms`, `verifyAdvanceDelay = 20ms`) so the
suite runs in well under a second.

## Localization

Strings live in [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml)
(English source) and [`app/src/main/res/values-ru/strings.xml`](app/src/main/res/values-ru/strings.xml)
(Russian). Mirrors `Resources/Localizable.xcstrings` from onym-ios PR #5
1:1 — same 27 strings + 1 plural, same wording, same Russian translations.

### Compose access

```kotlin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

Text(stringResource(R.string.your_identity_in_12_words))
Text(pluralStringResource(R.plurals.write_down_words_in_order, count = words.size, words.size))
```

### Outside Composables: `StringProvider`

[`RecoveryPhraseBackupViewModel`](app/src/main/kotlin/chat/onym/android/recovery/RecoveryPhraseBackupViewModel.kt)
holds no `Context` reference. The two strings it needs to resolve at
runtime — the [`BiometricPrompt`](https://developer.android.com/reference/androidx/biometric/BiometricPrompt)
title shown deep inside `authenticate()`, and the localized "recovery
phrase unavailable" error — go through a small
[`StringProvider`](app/src/main/kotlin/chat/onym/android/recovery/StringProvider.kt)
seam:

```kotlin
interface StringProvider { operator fun get(@StringRes resId: Int): String }
class AndroidStringProvider(context: Context) : StringProvider { … }
```

`MainActivity` injects the production impl backed by the application
context. Tests provide `FakeStringProvider` returning
`"string:$resId"` — assertions stay locale-independent.

### Plurals

Russian CLDR has four cardinal quantities: `one` (1, 21, 31; not 11),
`few` (2-4, 22-24; not 12-14), `many` (0, 5-20, 25-30; the form `12`
maps to → "12 слов"), and `other` (fractions like "1.5 слова").
English has just `one` and `other`. The `MissingQuantity` lint check
catches a translator who omits a CLDR-required form.

### Lint gate

[`lint { abortOnError = true }`](app/build.gradle.kts) +
`MissingTranslation` (default-on) means a string added to
`values/strings.xml` without a parallel `values-ru/` entry fails the
build at `./gradlew :app:lintDebug`. Equivalent to iOS String Catalog's
`state: new` warnings, but a hard gate.

`app_name` is marked `translatable="false"` because "Onym" is a proper
noun — keeps translators from inventing transliterations.

### Adding a new language

```sh
mkdir -p app/src/main/res/values-fr
cp app/src/main/res/values-ru/strings.xml app/src/main/res/values-fr/
# … translate every <string> + <plurals> body
./gradlew :app:lintDebug    # asserts complete coverage
```

Common BCP-47 region tags: `values-fr`, `values-de`, `values-es`,
`values-zh-rCN`, `values-pt-rBR`.

### Per-app language preference

Android 13+ supports per-app language settings (Settings → System →
Languages). When an in-app picker lands, wire it via
[`AppCompatDelegate.setApplicationLocales`](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate#setApplicationLocales(androidx.core.os.LocaleListCompat)).
For now the app honours the device language.

To smoke-test Russian without changing the device language:

```sh
adb shell am start -n chat.onym.android/.MainActivity \
  --es android.intent.extra.LOCALE ru-RU
```

## App shell

Sole entry point: [`MainActivity`](app/src/main/kotlin/chat/onym/android/MainActivity.kt)
mounts [`RootScreen`](app/src/main/kotlin/chat/onym/android/RootScreen.kt) —
a `Scaffold` with Material 3 [`NavigationBar`](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#NavigationBar(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.foundation.layout.WindowInsets,kotlin.Function1))
across the bottom and a [`NavHost`](https://developer.android.com/reference/kotlin/androidx/navigation/compose/package-summary)
for the content slot.

```
MainActivity (FragmentActivity, FLAG_SECURE, enableEdgeToEdge)
    │
    ▼
RootScreen
    │  Scaffold(bottomBar = NavigationBar { Settings | Search })
    │
    ├─► SettingsScreen           (route "settings")
    │     └── Backup row → navigate("recovery_backup")
    │                          ▼
    │                    RecoveryPhraseBackupScreen  (route "recovery_backup",
    │                                                 bottom bar hidden)
    │
    └─► SearchScreen             (route "search", placeholder)
```

### Divergences from iOS PR #6

- **No `.search` role / floating-bottom-right tab.** Material 3 has
  no equivalent and faking it would make the app feel un-native on
  Android. Both items live in the same nav bar with equal weight —
  same as Gmail / Calendar / Drive / Files.
- **Backup flow as a navigation destination, not a `ModalBottomSheet`.**
  Android-idiomatic for a multi-step flow (Intro → Reveal → Verify
  → Done); supports the system back gesture without ceremony. iOS
  uses `.sheet` for the same role.
- **Bottom bar hidden on the `recovery_backup` destination.** The
  flow gets full vertical real-estate; the `TopAppBar` back arrow +
  system back gesture are the way out.

### Edge-to-edge

`enableEdgeToEdge()` in `MainActivity.onCreate` opts in for Android
14 and earlier; Android 15+ does this by default at
`targetSdkVersion 35+`. `Scaffold` downstream applies the matching
[`WindowInsets`](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
padding so content never sits under the status / navigation bars.

## Out of scope (future chunks)

- `repo.stellarSign(_)` / `repo.decryptInvitation(_)` methods —
  no callers yet, lands when transport / invitation does.
- Onboarding (new-identity choice vs restore-from-mnemonic UI).
- Real Search screen (placeholder for now).
- More Settings sections (preferences, advanced, about).
- Promoting `Bip39` into `onym-sdk-kotlin` — wait for a third client
  to want the same derivation.
- Emulator job in CI for instrumented tests.

## License

MIT — see `LICENSE`.
