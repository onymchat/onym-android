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

Three rules, enforced by package layout:

- **Repositories own all I/O** — EncryptedSharedPreferences, network,
  on-device state. Pure references; no UI concerns.
- **Unidirectional reactive flow to views** — repositories publish
  state via `StateFlow`; views observe with
  `collectAsStateWithLifecycle()` and render; user actions flow back as
  intents that mutate repository state via `suspend` mutators. No
  bidirectional bindings, no shared mutable state across views.
- **OnymSDK is internal-only** — repositories wrap it; views never
  call it directly.

The first repository — `IdentityRepository` — owns the on-device
identity. Mutations are serialised by a `Mutex`; observers watch
`snapshots: StateFlow<Identity?>`. A `Compose` screen drives the
initial `bootstrap()` from a `LaunchedEffect` and re-renders whenever
a fresh snapshot lands; it never sees secret material, never calls
OnymSDK, never touches EncryptedSharedPreferences.

### Why `StateFlow` instead of `actor + AsyncStream`

iOS Chunk 2 uses Swift `actor` + `AsyncStream` because SwiftUI's
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
│   │       ├── OnymApplication.kt                   ← BouncyCastle provider registration
│   │       ├── MainActivity.kt                      ← @main, owns the repo
│   │       └── identity/
│   │           ├── Identity.kt                      ← snapshot value type the views see
│   │           ├── IdentityRepository.kt            ← StateFlow + Mutex + derivation
│   │           ├── IdentitySecretStore.kt           ← EncryptedSharedPreferences wrapper
│   │           ├── StoredSnapshot.kt                ← @Serializable JSON blob (Base64 ByteArrays)
│   │           ├── IdentityError.kt                 ← single error type
│   │           ├── Bip39.kt                         ← BIP39 wordlist + PBKDF2 + HKDF
│   │           ├── StellarStrKey.kt                 ← Ed25519 → G… account ID encoder
│   │           └── IdentityBootstrapScreen.kt       ← collectAsStateWithLifecycle drains snapshots
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
`// onym:allow-secret-read`. The one current suppression
(`IdentityBootstrapScreen.kt`) cites the surrounding context.

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

## Out of scope (future chunks)

- `repo.stellarSign(_)` / `repo.decryptInvitation(_)` methods —
  no callers yet, lands when transport / invitation does.
- Onboarding / recovery-reveal UI (the bootstrap screen exists only
  to make the repo wiring exercisable end-to-end).
- Promoting `Bip39` into `onym-sdk-kotlin` — wait for a third client
  to want the same derivation.
- Emulator job in CI for instrumented tests.

## License

MIT — see `LICENSE`.
