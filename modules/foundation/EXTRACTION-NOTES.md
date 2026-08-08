# :foundation extraction notes

Extracted from `app/` with packages unchanged. No project deps.

## Files moved

Main (`app/src/main/kotlin/app/onym/android/foundation/` → `src/main/kotlin/app/onym/android/foundation/`):
- `Bip39.kt`
- `StellarStrKey.kt`
- `StorageEncryption.kt`
- `TrustedAssetVerifier.kt`
- `DetachedSignatureFetch.kt`
- `Base64Serializers.kt`

Resource: `app/src/main/resources/bip39-english.txt` → `src/main/resources/bip39-english.txt`
(SHA-256 verified after the move: `2f5eed53…4dbda`, the canonical BIP39 wordlist hash that
`Bip39Test.test_wordlist_hash` asserts. `Bip39` loads it via classloader, so the java-res
placement keeps it on both the unit-test and APK classpaths.)

Unit tests (packages kept; note they were NOT under a `foundation/` test dir):
- `app/src/test/.../identity/Bip39Test.kt` → `src/test/kotlin/app/onym/android/identity/`
- `app/src/test/.../identity/StellarStrKeyTest.kt` → `src/test/kotlin/app/onym/android/identity/`
- ~~`app/src/test/.../identity/CrossPlatformFixtureTest.kt`~~ — **moved back to `:app` at
  integration**: despite importing only foundation symbols, it calls
  `IdentityRepository.stellarPublicKey/inboxPublicKey/inboxTag` without an import (same
  `app.onym.android.identity` package), so it depends on app-layer code and does not compile
  in this module.
- `app/src/test/.../security/TrustedAssetVerifierTest.kt` → `src/test/kotlin/app/onym/android/security/`
- `app/src/test/.../persistence/StorageEncryptionTest.kt` → `src/test/kotlin/app/onym/android/persistence/`

No `sharedTest` fakes moved: nothing in `app/src/sharedTest/.../support/` fakes a foundation
type (they fake stores/transports/encrypters owned by other modules and merely *use*
foundation types), so testFixtures is not enabled.

## Public API surface and justifying consumers

Top level — all public, each grep-justified:

| Symbol | Justifying outside consumers |
|---|---|
| `Base64ByteArraySerializer` | `@Serializable(with=...)` sites across group/chats/chain/inbox/identity (e.g. `group/ChatGroup.kt`, `chats/ChatMessagePayload.kt`, `identity/SealedEnvelope.kt`, `chain/SepContractTypes.kt`) |
| `Bip39` | `identity/IdentityRepository.kt`; tests `identity/EnvelopeSenderAuthenticationTest.kt`, `support/TestInvitationEncryptor.kt` |
| `StellarStrKey` | `identity/IdentityRepository.kt` |
| `StorageEncryption` | `OnymApplication.kt`, `persistence/RoomInvitationStore.kt`, `group/RoomGroupStore.kt`, `chats/RoomMessageStore.kt`, + their tests |
| `TrustedAssetVerifier` | `chain/ContractsManifestFetcher.kt`, `chain/KnownRelayersFetcher.kt`, `transport/nostr/KnownNostrRelaysFetcher.kt`, `transport/blossom/KnownBlossomServersFetcher.kt`, `chain/ContractsManifestFetcherTest.kt` |
| `signatureUrlFor` | same four fetchers |
| `OkHttpClient.fetchDetachedSignature` | same four fetchers |

Member-level tightening (no outside consumer found):
- `SignatureVerificationException` → **internal** (only consumer was `TrustedAssetVerifierTest`,
  now in-module; outside callers catch it as `IOException`, which is exactly the documented
  contract — it extends `IOException` so fetch error handling keeps working).
- `TrustedAssetVerifier.Result` + `verify()` → **internal** (fetchers only call `gate()`;
  only the in-module test uses `verify`).
- `TrustedAssetVerifier.{PUBLIC_KEY_LENGTH, SIGNATURE_LENGTH, SIGNING_PUBLIC_KEY,
  ENFORCE_SIGNATURES}` → **internal**. Notably `SIGNING_PUBLIC_KEY` (key material constant)
  and the enforcement flag are no longer part of the module API; they remain reachable as
  ctor defaults. `SIGNATURE_LENGTH` is used in-module by `DetachedSignatureFetch.kt`.
- Kept public with justification: `StorageEncryption.{NONCE_SIZE, TAG_SIZE}`
  (`RoomGroupStoreTest`, `RoomInvitationStoreTest`, `RoomMessageStoreTest`),
  `StorageEncryption.fromContext` (`OnymApplication.kt`), all `Bip39` members incl. the
  seed/key derivations (`IdentityRepository.kt`), `StellarStrKey.encodeAccountID`
  (`IdentityRepository.kt`), `TrustedAssetVerifier` ctor with `publicKey`/`enforce`/
  `logWarning` params (`ContractsManifestFetcherTest` constructs custom-key verifiers),
  `TrustedAssetVerifier.gate` (all four fetchers). `Bip39.hkdfSha256` was already internal.

One visibility **widening**: `StorageEncryption`'s constructor was `internal constructor(
SecretKeySpec)`; the Room store tests that stay outside this module (`RoomGroupStoreTest`,
`RoomInvitationStoreTest`, `RoomMessageStoreTest`) call it directly with per-test keys, and
`internal` stops working across the new module boundary. Made public (it accepts key
material but never exposes it; no accessor returns the key).

Public top-level symbols: 7 of 8 (1 made internal). Members tightened to internal: 6.

## Dependency decisions

- `api(libs.okhttp)` — `fetchDetachedSignature` is a public extension on `OkHttpClient`.
- `api(libs.kotlinx.serialization.json)` — `Base64ByteArraySerializer : KSerializer<ByteArray>`
  exposes serialization-core types; catalog has no `-core`-only alias.
- `implementation(libs.bouncycastle)` — internal use only (HKDF, Ed25519).
- `implementation(libs.androidx.security.crypto)` — **added beyond the listed set**:
  `StorageEncryption.fromContext` uses `EncryptedSharedPreferences` + `MasterKey`.
- **Trimmed:** `kotlinx-coroutines` (listed for this module, but no file imports coroutines).
- No Compose, no KSP, no kotlin-serialization compiler plugin (hand-written `KSerializer`,
  zero `@Serializable` declarations here), no `res/`, no BuildConfig.
- Tests: `testImplementation(libs.junit)` only (BouncyCastle reaches tests via
  `implementation`; no coroutines-test / Robolectric needed — all five tests are plain JUnit).

## Open questions for the integrator

1. `libs.versions.toml` has no `android-library` plugin alias; this build file applies
   `id("com.android.library")` version-less (resolves because root loads AGP with
   `apply false`). Consider adding an alias for consistency.
2. `CrossPlatformFixtureTest.kt` sits in package `app.onym.android.identity` and mirrors an
   identity-repository iOS test, but imports only foundation symbols — moved here so the
   derivation-constant lockdown travels with `Bip39`/`StellarStrKey`. If the :identity
   extraction also claims it, keep one copy (this one is self-contained).
3. `app/build.gradle.kts` packaging excludes for BouncyCastle META-INF duplicates stay
   app-side (library modules don't package the APK).
4. Could not run Gradle from this extraction step (forbidden while siblings work): the
   classloader lookup of `bip39-english.txt` from `src/main/resources` in a library
   module's unit tests should be verified once the module is wired into settings.
