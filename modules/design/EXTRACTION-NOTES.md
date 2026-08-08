# :design extraction notes

Module: `modules/design`, namespace `app.onym.android.design`, package unchanged
(`app.onym.android.design`). Compose library (compose-compiler plugin applied),
compileSdk 36 / minSdk 26 / Java 17.

## Files moved (plain `mv`, packages untouched)

From `app/src/main/kotlin/app/onym/android/design/` to
`modules/design/src/main/kotlin/app/onym/android/design/`:

- `OnymBrand.kt`
- `SettingsAtoms.kt`
- `OnymQrCode.kt`

No unit tests moved: `app/src/test/kotlin/app/onym/android/design/` does not
exist. No sharedTest fakes have a :design subject, so testFixtures is NOT
enabled.

## Public symbols and justifying consumers

Grep scope: `app/src` + `modules/*` (excluding `modules/design`), at extraction
time (siblings were moving files concurrently; consumer paths below are where
grep found them, mostly still under `app/`).

| Symbol | Kind | Justifying consumers (examples) |
|---|---|---|
| `OnymTokens` | data class | `MainActivity.kt`, `chats/ChatBubble.kt` |
| `LocalOnymTokens` | top-level val | `MainActivity.kt`, `RootScreen.kt`, `settings/IdentityCarouselCard.kt`, `group/OnymBrandSepBridge.kt` (6 files) |
| `OnymAccent` | enum | `group/creategroup/CreateGroupScreen.kt`, `chats/*`, tests `group/OnymAccentSenderColorTest.kt`, `chats/ChatSenderDisplayTest.kt` (9 files) |
| `OnymTheme` | @Composable | `RootScreen.kt`, `MainActivity.kt`, `group/QrScannerScreen.kt`, `chats/ChatBubble.kt` |
| `OnymMark` | @Composable | `settings/IdentitiesScreen.kt`, `settings/AboutOnymScreen.kt`, `settings/SettingsScreen.kt` (5 files) |
| `OnymGroupAvatar` | @Composable | `group/creategroup/CreateGroupScreen.kt`, `chats/ChatsScreen.kt` |
| `SettingsTile` | object (palette) | 13 settings screens (`SettingsScreen.kt`, `AboutOnymScreen.kt`, ...) |
| `SettingsCard` | @Composable | 9 settings screens |
| `SettingsSectionLabel` | @Composable | 9 settings screens |
| `SettingsFootnote` | @Composable | 8 settings screens |
| `SettingsHairline` | @Composable | `settings/IdentityDetailScreen.kt`, `settings/IdentitiesScreen.kt` |
| `SettingsTileBox` | @Composable | 8 settings screens |
| `SettingsTileLabel` | @Composable | `settings/UseExistingContractScreen.kt`, `settings/PrivacyEncryptionScreen.kt`, `settings/ContractDetailScreen.kt` |
| `SettingsRow` | @Composable | 8 settings screens |
| `heroHex` | top-level fun | `settings/IdentitiesScreen.kt`, `settings/IdentityCarouselCard.kt`, `settings/IdentityDetailScreen.kt` |
| `OnymQrCode` | @Composable | `settings/IdentityDetailScreen.kt`, `settings/IdentityCarouselCard.kt`, `group/creategroup/ShareInviteScreen.kt` |

Public count: 16. No secret material is held or exposed by this module
(colors, geometry, and a QR encoder only; `heroHex`/`shortHex` render caller-
supplied public-key bytes).

## Internal / private symbols

- `shortHex` — kept `internal`; zero external references, only consumer is
  `heroHex` in the same file.
- `GAP_ORIENTATION_DEG`, `QrBitMatrix`, `encodeQrMatrix` — already `private`,
  unchanged.

Visibility changes made: `OnymQrCode`, `SettingsTile`, `SettingsCard`,
`SettingsSectionLabel`, `SettingsFootnote`, `SettingsHairline`,
`SettingsTileBox`, `SettingsTileLabel`, `SettingsRow`, `heroHex` flipped from
`internal` to public — inside `:app`, `internal` meant "app-wide"; the new
module boundary would have cut off the grep-verified consumers above. All
`OnymBrand.kt` symbols were already public and stay so.

## Dependencies (vs. app/build.gradle.kts) and trims

Kept:
- `api(platform(androidx-compose-bom))`, `api(androidx-compose-ui)`,
  `api(androidx-compose-ui-graphics)` — `Modifier`, `Dp`, `Color`,
  `ImageVector`, `@Composable` appear in public signatures.
- `implementation(androidx-compose-material3)` — internal `MaterialTheme` /
  `Text` / `Icon` use only; no material3 type escapes.
- `implementation(zxing-core)` — private QR encoding.

Trimmed (unused by these three files):
- `project(":strings")` — mandated by the task spec, but no file references
  string resources / `stringResource` / any `R.` class; dropped. Re-add at
  integration if :strings ends up hosting something design consumes.
- `androidx-compose-material-icons-extended` — the only icon used
  (`Icons.AutoMirrored.Filled.KeyboardArrowRight`) ships in
  `material-icons-core`, which material3 exposes via `api`. If integration
  compile disagrees, re-add the extended alias.
- `androidx-compose-ui-tooling(-preview)` — no `@Preview` in this module.
- Everything else from app's list (core-ktx, lifecycle, activity-compose,
  navigation, fragment/biometric, security-crypto, okhttp, datastore,
  coroutines-android, serialization-json, bouncycastle, camera*, media3*,
  onym-sdk, room*, all test deps) — no imports from any of them.

Plugins trimmed vs app: `kotlin-serialization`, `ksp` (no serializable types,
no Room/KSP processing here).

## Open questions for the integrator

1. **No `android-library` catalog alias** — `gradle/libs.versions.toml` only
   defines `android-application` and this task forbade editing the catalog, so
   the build file applies `id("com.android.library")` version-less (resolved
   from AGP already on the root classpath). Consider adding an alias +
   `apply false` in the root build for consistency.
2. **`OnymAccentSenderColorTest`** lives at
   `app/src/test/kotlin/app/onym/android/group/OnymAccentSenderColorTest.kt` —
   its subject (`OnymAccent.forSender`) is :design code, but its subpath is
   `group/`, so per the subpath rule it was left for the :group extraction /
   integration to place (wherever it lands, that module needs a :design dep).
3. **`icons-extended` trim** (above) assumes material3's `api` on
   `material-icons-core`; cheap to revert if the integration compile fails.
4. `SettingsAtoms.kt`'s old file-level comment promised the atoms were
   app-internal; rewritten to reflect that they are now the module's public
   API (each consumer screen already depended on them).
