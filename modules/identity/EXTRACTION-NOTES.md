# :identity extraction notes

Moved out of `app/` with packages unchanged (`app.onym.android.identity`,
fixtures keep `app.onym.android.support`).

## Files moved

**main (15)** → `modules/identity/src/main/kotlin/app/onym/android/identity/`:
ActiveIdentityProvider, DecryptedEnvelope, IdentitiesViewModel, Identity,
IdentityError, IdentityId, IdentityInviteUrl, IdentityRepository,
IdentitySecretStore, IdentitySummary, InvitationEnvelopeDecrypter,
InvitationEnvelopeSealer, OnymNostrSigner, SealedEnvelope, StoredSnapshot.

**test (3)** → `modules/identity/src/test/kotlin/app/onym/android/identity/`:
CrossPlatformFixtureTest, EnvelopeSenderAuthenticationTest, SealedEnvelopeTest.
(Plain JUnit; no coroutines-test / robolectric / org.json needed.)

**testFixtures (3)** → `modules/identity/src/testFixtures/kotlin/app/onym/android/support/`
(subject types live in this module):
FakeActiveIdentityProvider, FakeInvitationEnvelopeDecrypter,
TestInvitationEncryptor. Integrator wires
`testImplementation(testFixtures(project(":identity")))` (+ androidTest
equivalent) into `:app`, and needs
`android.experimental.enableTestFixturesKotlinSupport=true` in
gradle.properties if not already set for :transport.

## Public API and justifying consumers

Every top-level symbol below stays `public` because grep found a consumer
outside this module (paths relative to repo root):

| Symbol | Justifying consumer (examples) |
|---|---|
| `ActiveIdentityProvider` | `app/.../group/GroupRepository.kt`, `app/.../inbox/*`, 12 files |
| `DecryptedEnvelope` | `app/.../inbox/IncomingMessageDispatcher.kt` |
| `IdentitiesViewModel` (+ nested `Row`/`Error`/`AddResult`) | `app/.../settings/IdentitiesScreen.kt`, `SettingsScreen.kt`, `IdentityDetailScreen.kt`, `IdentityCarouselCard.kt`, `AppDependencies.kt`, `RootScreen.kt` |
| `Identity` | `app/.../group/CreateGroupInteractor.kt`, `app/.../recovery/RecoveryPhraseBackupViewModel.kt` |
| `IdentityId` | 49 files (group/inbox/chats/transport layers) |
| `IdentityRepository` | `app/.../OnymApplication.kt`, group + inbox interactors (39 files) |
| `IdentityRepository.blsSecretKey()` (secret-bearing) | `CreateGroupInteractor.kt:168`, `JoinRequestSender.kt:56`, `JoinRequestApprover.kt:574` — kept as narrow as these require (public method, no wider caching added) |
| `IdentityRepository.inboxTag` | `OnymApplication.kt:637,665`, `GroupStateVerifier.kt`, `JoinRequestSender.kt`, app unit tests |
| `IdentityRepository.decryptSealedEnvelopeWithKey` | `app/.../group/JoinRequestApprover.kt:446` |
| `IdentitySecretStore` (ctor + `listIds()` + `wipeAll()`) | `OnymApplication.kt`, `app/src/androidTest/**` (UI + integration tests) |
| `IdentitySummary` | settings screens, `CreateGroupInteractor`, 12 files |
| `IdentitySummary.inviteUrl()` | `SettingsScreen.kt`, `IdentityCarouselCard.kt`, `IdentityDetailScreen.kt` |
| `InvitationEnvelopeDecrypter` | `app/.../inbox/InvitationDecryptor.kt`, dispatcher wiring (8 files) |
| `InvitationDecryptError` | `app/.../inbox/*` (4 files) |
| `InvitationEnvelopeSealer` | group interactors (7 files) |
| `OnymNostrSigner` | `app/src/androidTest/.../transport/nostr/OnymNostrSignerTest.kt` |
| `OnymNostrSignerProvider` | `app/.../OnymApplication.kt:212` |
| `SealedEnvelope` | `TestInvitationEncryptor` (this module's testFixtures — a *separate compilation*, so `internal` would not be visible) + its serializer used by app tests through the fixture |

testFixtures top-level symbols (`FakeActiveIdentityProvider`,
`FakeInvitationEnvelopeDecrypter`, `TestInvitationEncryptor`) stay public —
consumed by `app/src/test` + `app/src/androidTest` (group, inbox, chats,
integration tests).

## Narrowed to `internal` (grep found no outside consumer)

- `IdentityError` — thrown across the public API but never named outside
  the module (callers catch `Throwable`).
- `InvitationSealError` — same.
- `StoredSnapshot` — secret material container; only `IdentityRepository`
  / `IdentitySecretStore` / in-module tests touch it.
- `IDENTITY_INVITE_URL_BASE` — only consumer is `inviteUrl()`;
  `InviteKeyCanonicalizer` (app) has its own URL constants.
- `IdentitySecretStore` members `load` / `save` / `loadCurrent` /
  `saveCurrent` / `wipe` / `listSnapshots` / `DEFAULT_PREFS_FILE_NAME` —
  secret-bearing accessors narrowed; outside consumers only use the
  constructor, `listIds()`, `wipeAll()`.
- `IdentityRepository.decryptSealedEnvelopeWithKeyAndSender` — only
  in-module callers (the `...WithKey` variant stays public for
  `JoinRequestApprover`).

Already-internal secret accessors preserved unchanged (comments intact):
`stellarSigningPrivateKey`, `inboxKeyAgreementPrivateKey`,
`stellarPublicKey`, `inboxPublicKey` (companion), `envelopeJsonFormat`,
`OnymNostrSigner.secretKey`, `StoredIndex`, `ByteArrayBase64Serializer`.
No secret-bearing accessor was widened.

## Dependency trims (vs. app/build.gradle.kts)

Kept: `:foundation` (impl), `:transport` (**api** — `NostrSigner` /
`NostrEphemeralSignerProvider` are public supertypes), coroutines
(core=api for StateFlow, android=impl), serialization-json (api —
generated `serializer()` members are public), lifecycle-viewmodel-compose
(api — `ViewModel` supertype; see note in build file about a leaner
`lifecycle-viewmodel-ktx` alias), security-crypto (impl), bouncycastle
(impl; testFixturesApi), onym-sdk (impl), junit (test).

Trimmed (unused by these files): core-ktx, activity-compose, the whole
Compose UI stack + BOM, navigation, fragment-ktx, biometric, okhttp,
datastore, zxing, camerax, media3, room + ksp, lifecycle-runtime-*,
coroutines-test, robolectric, org.json, androidx-test-*, espresso.

## Open questions for the integrator

1. **`OnymNostrSignerTest` (app androidTest) reads `OnymNostrSigner.secretKey`**
   (lines 132, 140), which is `internal` and now module-internal. That test
   was left in `app/src/androidTest` per instructions and will not compile
   against `:identity`. Options: move the test into `:identity`'s
   androidTest, or drop the two `secretKey` assertions. Widening `secretKey`
   is NOT an option (secret material).
   Similarly, `app/src/androidTest/.../identity/IdentityRepository*Test.kt`
   and `recovery/RecoveryPhraseBackup*Test.kt` (all left in :app per
   instructions) call the now-internal `IdentitySecretStore.load/loadCurrent/
   wipe` — the natural fix is moving the identity androidTests into
   `modules/identity/src/androidTest` at integration time (they become
   friend code), NOT re-widening the secret-bearing accessors.
2. `internal IdentityError` / `InvitationSealError` cross the public API as
   exceptions. No app code names them today; if a future consumer wants
   typed catches, they must be re-published deliberately.
3. `SealedEnvelope`'s properties carry
   `@Serializable(with = Base64ByteArraySerializer::class)` where the
   serializer comes from `:foundation` (declared `implementation` here, per
   plan). `:app` depends on `:foundation` directly so this is moot today;
   a future consumer that compiles its own serialization against
   `SealedEnvelope` needs its own `:foundation` edge (or flip to `api`).
4. `IdentitiesViewModel` lives here (no Compose deps needed), pulled in via
   the `lifecycle-viewmodel-compose` catalog alias because no plain
   viewmodel alias exists — consider adding one.
5. This module's own unit tests use its `internal` members via the standard
   test↔main friend relationship; `testFixtures` deliberately touch only
   public API (fixtures are not a friend compilation).
