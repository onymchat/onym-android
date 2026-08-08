# :transport extraction notes

Pure seam module: transport-agnostic message/inbox interfaces + Nostr signer
seam interfaces. No project deps, no Onym SDK imports (verified: the two moved
files import only `kotlinx.coroutines.flow.Flow`, `java.net.URI`,
`java.time.Instant`).

## Files moved

Main (package `app.onym.android.transport`, unchanged):
- `app/src/main/kotlin/app/onym/android/transport/Transport.kt`
  → `modules/transport/src/main/kotlin/app/onym/android/transport/Transport.kt`
- `app/src/main/kotlin/app/onym/android/transport/NostrSigner.kt`
  → `modules/transport/src/main/kotlin/app/onym/android/transport/NostrSigner.kt`

testFixtures (package `app.onym.android.support`, unchanged) — sharedTest fakes
whose subject (`InboxTransport`) lives in this module:
- `app/src/sharedTest/kotlin/app/onym/android/support/ConfigurableInboxTransport.kt`
  → `modules/transport/src/testFixtures/kotlin/app/onym/android/support/ConfigurableInboxTransport.kt`
- `app/src/sharedTest/kotlin/app/onym/android/support/LoopbackInboxTransport.kt`
  → `modules/transport/src/testFixtures/kotlin/app/onym/android/support/LoopbackInboxTransport.kt`

Left in `:app` (next tier / not this module's subject):
- `app/src/main/kotlin/app/onym/android/transport/nostr/`, `.../transport/blossom/`
- `app/src/test/kotlin/app/onym/android/transport/nostr/`, `.../transport/blossom/`
- `app/src/test/kotlin/app/onym/android/transport/DeeplinkCaptureTest.kt` — sits in
  the transport package folder but its subject is `app.onym.android.group.DeeplinkCapture`;
  it belongs with the group/deeplink module, not here (moving it would force a
  project dep, and this module must have none).
- `app/src/sharedTest/.../LoopbackBlossomClient.kt` (subject: `transport.blossom.BlossomClient`, stays in :app tier)
- `ConfigurableContractTransport.kt` / `FakeSepContractTransport.kt` (subject: `app.onym.android.chain.SepContractTransport`, not this module)

## Visibility audit (hard requirement)

Every top-level declaration was grepped across `app/src` and `modules/`
(excluding this module). **All 12 have outside consumers → all stay `public`;
0 marked `internal`.** No secret material is held by any type here (NostrSigner
deliberately never exposes the secret key — `publicKey()`/`signEventId()` only).

| Symbol | Justifying consumer (example) |
|---|---|
| `TransportEndpoint` | `app/src/main/kotlin/app/onym/android/transport/nostr/NostrMessageTransport.kt` (connect signature) |
| `TransportTopic` | `app/src/main/kotlin/app/onym/android/transport/nostr/NostrMessageTransport.kt`; `app/src/test/.../nostr/NostrMessageTransportTest.kt` |
| `TransportInboxId` | 19 consumers, e.g. `app/src/main/kotlin/app/onym/android/transport/nostr/NostrInboxTransport.kt`, chats/invitations repositories |
| `InboundMessage` | `app/src/main/kotlin/app/onym/android/transport/nostr/NostrMessageTransport.kt` |
| `InboundInbox` | `NostrInboxTransport.kt`, repositories, fixtures |
| `PublishReceipt` | `NostrMessageTransport.kt`, `NostrInboxTransport.kt`, repositories |
| `TransportError` | thrown by `NostrMessageTransport.kt` / `NostrInboxTransport.kt` (`NotConnected`, `PublishRejected`) |
| `MessageTransport` | implemented by `NostrMessageTransport.kt` |
| `InboxTransport` | 25 consumers: `NostrInboxTransport.kt`, repositories, the two moved fixtures |
| `NostrSigner` | `app/src/main/kotlin/app/onym/android/identity/OnymNostrSigner.kt`, nostr transports |
| `NostrEphemeralSignerProvider` | nostr transports / composition root (7 consumers) |
| `NostrSignerError` | `app/src/main/kotlin/app/onym/android/identity/OnymNostrSigner.kt` (throws both subclasses) |

Nested-declaration note: `TransportError.InvalidPayload` currently has no
external reference, but it stays public — it is a subclass of a public sealed
class documented as supporting exhaustive `when` without `else` downstream;
hiding it would silently change that contract. Not a top-level declaration, so
outside the internal-by-default rule anyway.

testFixtures classes `ConfigurableInboxTransport` / `LoopbackInboxTransport`
stay public: consumed by `:app` unit tests and androidTest
(`UITestRegistry.inboxTransport`) once the integrator wires
`testFixtures(project(":transport"))`.

## Dependency trims (vs app/build.gradle.kts)

Kept: `api(kotlinx-coroutines-core)` only — `Flow` is in the public API
(`subscribe` return types), hence `api` not `implementation`.

Trimmed everything else the app declares: all Compose/AndroidX UI, lifecycle,
navigation, fragment/biometric, security-crypto, OkHttp, DataStore,
serialization + its plugin, compose-compiler plugin, KSP/Room, BouncyCastle,
ZXing, CameraX, Media3, **onym-sdk** (must not appear here — verified absent),
org.json, JUnit/Robolectric/test-ext (no unit tests moved), coroutines-android
(module is Android-free in deps; core suffices).

## Integrator actions / open questions

1. Catalog additions needed in `gradle/libs.versions.toml` (off-limits to this agent):
   - `[plugins] android-library = { id = "com.android.library", version.ref = "agp" }`
   - `[libraries] kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }`
2. `gradle.properties` (off-limits): AGP 8.11 gates Kotlin sources in
   testFixtures behind `android.experimental.enableTestFixturesKotlinSupport=true`.
3. Wire consumers: `implementation(project(":transport"))` in `:app` (and the
   future nostr/blossom tier); `testImplementation(testFixtures(project(":transport")))`
   + `androidTestImplementation(testFixtures(project(":transport")))` for the fakes.
4. `DeeplinkCaptureTest.kt` is misfiled under the transport test package —
   suggest the group-module agent (or integrator) relocates it with
   `DeeplinkCapture`.
