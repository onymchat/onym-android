import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.onym.android"
    // Android 16. `targetSdk` (below) is what Play gates on; `compileSdk`
    // only needs to be >= it. Independent of `minSdk = 26` — raising the
    // target does NOT drop support for older devices, it opts the app
    // into API 36 runtime behaviours.
    compileSdk = 36

    defaultConfig {
        applicationId = "app.onym.android"
        // EncryptedSharedPreferences requires API 23+. Picking 26 (O)
        // also unlocks AEAD ciphers + the keystore-backed master key
        // path with no compatibility shims.
        minSdk = 26
        targetSdk = 36
        // Single source of truth: the GitHub release tag. See
        // `resolveReleaseVersion()` for the resolution order
        // (release-workflow env → -PreleaseVersion → git describe →
        // dev fallback). The release tag flows through here into
        // `BuildConfig.VERSION_NAME` / `VERSION_CODE`, which the
        // About screen renders directly — no manual bump needed.
        val releaseVersion = resolveReleaseVersion()
        versionCode = releaseVersion.code
        versionName = releaseVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Match the four Android ABIs the OnymSDK AAR ships .so files
        // for. Anything else would produce an APK that crashes on
        // System.loadLibrary("onym_sdk_jni").
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // Bearer token the relayer's `validate_auth` requires on every
        // POST. Sourced from (in order):
        //   1. ENV `RELAYER_AUTH_TOKEN` — release CI passes the GitHub
        //      Actions secret here.
        //   2. `local.properties` `relayer.authToken=…` — local dev
        //      (gitignored).
        //   3. Empty string — build still succeeds; the relayer 401s
        //      every call with a clear message, surfacing the missing
        //      config to the dev rather than failing silently.
        // See `OnymApplication.buildDependencies` for the OkHttp
        // interceptor that consumes this.
        buildConfigField("String", "RELAYER_AUTH_TOKEN", "\"${relayerAuthToken()}\"")

        // Moderation enforcement backend (the `android/` service in
        // onym-moderation). Sourced like RELAYER_AUTH_TOKEN: ENV
        // `MODERATION_BASE_URL` → local.properties `moderation.baseUrl`
        // → empty. EMPTY IS THE DARK-LAUNCH SWITCH: with no base URL
        // (and no UI-test fakes) OnymApplication builds no moderation
        // dependencies at all — no gate, no onboarding step, no Play
        // Integrity calls. Flip only when the backend and the Play
        // Console device-recall opt-in are live.
        buildConfigField("String", "MODERATION_BASE_URL", "\"${moderationBaseUrl()}\"")
        // The Google Cloud project number linked in the Play Console —
        // StandardIntegrityManager.prepare needs it. ENV
        // `PLAY_CLOUD_PROJECT_NUMBER` → local.properties
        // `play.cloudProjectNumber` → 0 (unusable, kept dark).
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${playCloudProjectNumber()}L")

        // Half-configured moderation is worse than dark: a base URL
        // with no cloud project number makes the Play provider latch
        // CLOUD_PROJECT_NUMBER_IS_INVALID as "unsupported" for the
        // process, so every session goes token-less and every user
        // blocks at the gate. Refuse the build instead.
        check(moderationBaseUrl().isBlank() || playCloudProjectNumber() != 0L) {
            "MODERATION_BASE_URL is set but PLAY_CLOUD_PROJECT_NUMBER is not — the moderation " +
                "seat needs both (or neither, to stay dark). Set play.cloudProjectNumber in " +
                "local.properties or the PLAY_CLOUD_PROJECT_NUMBER env var."
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8 disabled BuildConfig generation by default — we
        // re-enable it to surface RELAYER_AUTH_TOKEN to runtime.
        buildConfig = true
    }

    // Lint hard-gates the build on missing localizations: every string
    // added to `res/values/strings.xml` must have a parallel entry in
    // every `res/values-<lang>/strings.xml`, otherwise the lint
    // `MissingTranslation` check fails. Equivalent to iOS String
    // Catalog's `state: new` warnings, but enforced as a hard gate.
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        // MissingTranslation is enabled by default; explicit re-enable
        // below in case it gets suppressed in a future config sweep.
        disable.remove("MissingTranslation")
    }

    packaging {
        resources {
            // BouncyCastle ships duplicate META-INF entries that AGP
            // refuses to bundle without an explicit pickFirst rule.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests.all {
            // The whole unit-test suite runs in one JVM fork. A few
            // media tests allocate large buffers (e.g. the video
            // oversize check seals a >95 MB blob — ~190 MB live), which
            // OOMs the default fork heap on CI and surfaces as the wrong
            // exception. Give the fork ample headroom.
            it.maxHeapSize = "2g"
        }
    }

    // NOTE: the former src/sharedTest source set is gone — every shared
    // fake in `app.onym.android.support` now lives in the owning
    // module's testFixtures (:transport, :chain, :identity, :group,
    // :transport-blossom, :chats-core), wired below.
}

dependencies {
    // Extracted library modules (modules/): string resources, brand/
    // settings UI atoms, crypto/encoding primitives, transport seam
    // interfaces.
    implementation(project(":strings"))
    implementation(project(":design"))
    implementation(project(":foundation"))
    implementation(project(":transport"))
    implementation(project(":chain"))
    implementation(project(":discovery"))
    implementation(project(":transport-nostr"))
    implementation(project(":transport-blossom"))
    implementation(project(":identity"))
    implementation(project(":persistence"))
    implementation(project(":group"))
    implementation(project(":chats-core"))
    implementation(project(":inbox"))
    implementation(project(":search"))
    implementation(project(":onboarding"))
    implementation(project(":moderation"))
    implementation(project(":moderation-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // BiometricPrompt requires a FragmentActivity host. fragment-ktx
    // pulls in the FragmentActivity class; biometric pulls in the
    // prompt itself.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(libs.androidx.security.crypto)

    // OkHttp WebSocket — NostrRelayConnection uses it for the relay
    // connection. Built-in pingInterval handles heartbeat; no need
    // for the iOS CFNetwork-pong workaround. Also used by
    // GitHubReleasesKnownRelayersFetcher (HTTPS GET of relayers.json).
    implementation(libs.okhttp)

    // DataStore Preferences — flow-based persistence for the relayer
    // URL selection. URLs aren't secret material; identity bytes
    // continue to live in EncryptedSharedPreferences via
    // IdentitySecretStore.
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.bouncycastle)

    // ZXing core — pure-Java QR encoder. Used by `OnymQrCode` to turn
    // an invite URL into a 1-bit module grid; the Compose Canvas does
    // the actual drawing (so we get rounded modules + center logo
    // overlay matching the iOS design without ZXing's stock bitmap
    // renderer).
    implementation(libs.zxing.core)

    // CameraX — preview + image analysis behind the invite-QR scanner
    // (`QrScannerScreen`). camera-view supplies `PreviewView`; the
    // analysis frames are decoded with the zxing-core reader above so
    // we add no ML Kit model to the APK.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Media3 — video transcode (Transformer) + playback (ExoPlayer + UI)
    // for chat video messages.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.onym.sdk)

    // Room — `suspend` DAO + KSP-generated bindings. PersistenceStore
    // for incoming invitations + (later) groups / messages / contact
    // aliases / transport bundles.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // JVM unit tests. Pure-logic only — Bip39, StellarStrKey, the
    // cross-platform fixture. Anything touching EncryptedSharedPreferences
    // goes in androidTest.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Shared inbox-transport fakes (ConfigurableInboxTransport,
    // LoopbackInboxTransport) moved from src/sharedTest to
    // :transport's testFixtures.
    testImplementation(testFixtures(project(":transport")))
    // Chain-subject fakes (ConfigurableContractTransport,
    // FakeContractsManifestFetcher, InMemoryChainLedger, FakeOkHttpClient,
    // …) moved from src/sharedTest to :chain's testFixtures.
    testImplementation(testFixtures(project(":chain")))
    // Discovery-subject fakes (FakeDiscoveryFetcher,
    // InMemoryDiscoveryStore) for the seat-adapter unit tests.
    testImplementation(testFixtures(project(":discovery")))
    // Identity fakes (FakeActiveIdentityProvider,
    // FakeInvitationEnvelopeDecrypter, TestInvitationEncryptor) moved
    // from src/sharedTest to :identity's testFixtures.
    testImplementation(testFixtures(project(":identity")))
    // Group-subject fakes (InMemoryGroupStore, InMemoryIntroKeyStore)
    // moved from src/sharedTest to :group's testFixtures — consumed by
    // the inbox dispatcher / chats VM unit-test suites.
    testImplementation(testFixtures(project(":group")))
    // Chats-subject fake (InMemoryMessageStore) moved from
    // src/sharedTest to :chats-core's testFixtures — consumed by
    // ChatsViewModelTest / ChatThreadViewModelTest.
    testImplementation(testFixtures(project(":chats-core")))
    // InMemoryOnboardingStore — consumed by the gate-resolution tests.
    testImplementation(testFixtures(project(":onboarding")))
    // Android unit tests stub `org.json` (every method throws "not
    // mocked"). The transport layer's NostrEvent / subscriptionFilters
    // use JSONObject + JSONArray for canonical JSON, so tests need a
    // real impl on the classpath.
    testImplementation("org.json:json:20240303")
    // Robolectric — drives Room (`Context.getApplicationContext()` is
    // required to open the in-memory DB) from the JVM unit-test
    // runner. Used by `RoomInvitationStoreTest` only; everything else
    // in `app/src/test/` is plain JUnit and won't load Robolectric.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // DataStore-Preferences `-core` artifact — JVM-only flavour with
    // no Android dep. RelayerSelectionStoreTest opens a real
    // PreferenceDataStoreFactory backed by a per-test temp file; no
    // Robolectric / mocking required.
    testImplementation(libs.androidx.datastore.preferences.core)

    // Instrumented tests. Real EncryptedSharedPreferences against the
    // emulator's hardware-backed Keystore.
    androidTestImplementation(testFixtures(project(":transport")))
    // Chain + identity fakes for instrumented harnesses/E2E suites, and
    // LoopbackBlossomClient for uitests/LoopbackRegistryHarness.
    androidTestImplementation(testFixtures(project(":chain")))
    androidTestImplementation(testFixtures(project(":identity")))
    androidTestImplementation(testFixtures(project(":transport-blossom")))
    // InMemoryGroupStore / InMemoryIntroKeyStore for the instrumented
    // group + E2E suites.
    androidTestImplementation(testFixtures(project(":group")))
    // InMemoryMessageStore for ChatsSwipeDeleteScreenTest.
    androidTestImplementation(testFixtures(project(":chats-core")))
    // FakeDiscoveryFetcher / InMemoryDiscoveryStore for the Discovery
    // settings UI tests (the byte fixtures they replay live in
    // src/androidTest/resources/fixtures — copies of the shared
    // conformance fixtures in modules/discovery/src/test/resources).
    androidTestImplementation(testFixtures(project(":discovery")))
    // InMemoryOnboardingStore — drives the onboarding gate in the
    // walk-through UI test (PR 4).
    androidTestImplementation(testFixtures(project(":onboarding")))
    androidTestImplementation(testFixtures(project(":moderation")))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Override the older transitive espresso-core (~3.6.1) pulled in
    // by ui-test-junit4. 3.7.0 is the first version with the
    // InputManager.getInstance() reflection fix for Android 15+/16+;
    // older versions crash at the first compose action with
    // `NoSuchMethodException: android.hardware.input.InputManager.getInstance []`.
    androidTestImplementation(libs.androidx.test.espresso.core)

    // ui-test-manifest registers the test-only ComponentActivity in
    // the debug manifest so `createComposeRule()` can host content
    // without a real Activity from main code. Required by Compose UI
    // tests that don't use `createAndroidComposeRule<MyActivity>`.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // fastlane screengrab — drives the App Store / Play screenshot
    // capture from the instrumented ScreenshotTest. Test-only.
    androidTestImplementation("tools.fastlane:screengrab:2.1.1")
}

/**
 * Resolves the relayer Bearer token at configuration time.
 *
 * Resolution order:
 *  1. Environment variable `RELAYER_AUTH_TOKEN` (release CI passes
 *     `${{ secrets.RELAYER_AUTH_TOKEN }}` through this).
 *  2. `local.properties` `relayer.authToken=…` (local dev — the file
 *     is gitignored so the token never gets committed).
 *  3. Empty string — the build still succeeds, but every relayer
 *     call 401s with a clear message. The
 *     `BearerAuthInterceptor.takeIf { it.isNotBlank() }` guard skips
 *     the `Authorization` header entirely on empty so the failure is
 *     "no header" (relayer's `validate_auth` says so) rather than
 *     `Bearer ""` (more confusing).
 *
 * Mirrors the iOS `RelayerSecrets` resolution flow from PR #28 —
 * different storage primitives (UserDefaults / Info.plist over there;
 * `BuildConfig` here) but same precedence + fallback story.
 */
fun moderationBaseUrl(): String {
    System.getenv("MODERATION_BASE_URL")?.takeIf { it.isNotBlank() }?.let { return it }
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    return props.getProperty("moderation.baseUrl").orEmpty()
}

fun playCloudProjectNumber(): Long {
    System.getenv("PLAY_CLOUD_PROJECT_NUMBER")?.toLongOrNull()?.let { return it }
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    return props.getProperty("play.cloudProjectNumber")?.toLongOrNull() ?: 0L
}

fun relayerAuthToken(): String {
    System.getenv("RELAYER_AUTH_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    return props.getProperty("relayer.authToken").orEmpty()
}

/**
 * The app's `versionName` + `versionCode`, derived (in this order) from:
 *
 *  1. Environment variable `RELEASE_VERSION` — `release.yml` passes
 *     the dispatch input through here at `assembleRelease`. This is
 *     the canonical CI path; the explicit override skips git so the
 *     APK version always matches the release tag exactly, even on a
 *     shallow clone.
 *  2. Gradle property `-PreleaseVersion=v0.0.10` — same purpose,
 *     command-line equivalent. Useful for one-off local "what would
 *     this look like at v0.0.X" builds.
 *  3. `git describe --tags --match 'v*'` — local dev between tags
 *     gets a descriptor like `v0.0.10-3-gca6471b` that's useful in
 *     bug reports (encodes the last release + commits-since + SHA).
 *  4. Fallback `v0.0.0-dev` — covers shallow clones with no tags
 *     fetched, no-git sandboxes, and brand-new repos before the first
 *     tag.
 *
 * `name` strips the leading `v` (Play / About-screen convention).
 *
 * `code` is `MAJOR * 10000 + MINOR * 100 + PATCH` parsed from the
 * resolved name (any `-N-gXXX` dev suffix is ignored). Monotonic
 * across `v0.x.y` and across the eventual jump to `v0.1.0`. Floors
 * at 1 so AGP doesn't reject `versionCode = 0` on a brand-new repo.
 */
data class ReleaseVersion(val name: String, val code: Int)

fun resolveReleaseVersion(): ReleaseVersion {
    val raw = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("releaseVersion") as? String)?.takeIf { it.isNotBlank() }
        ?: gitDescribeOrNull()
        ?: "v0.0.0-dev"
    val name = raw.removePrefix("v")
    val parts = name.substringBefore('-').split('.')
    val code = if (parts.size == 3) {
        val major = parts[0].toIntOrNull() ?: 0
        val minor = parts[1].toIntOrNull() ?: 0
        val patch = parts[2].toIntOrNull() ?: 0
        major * 10000 + minor * 100 + patch
    } else 0
    return ReleaseVersion(name = name, code = code.coerceAtLeast(1))
}

/** `git describe`-derived release identifier, or `null` if no `v*`
 *  tag is reachable from `HEAD` (or git itself isn't available).
 *
 *  Uses `providers.exec` (Gradle 8.5+) rather than a direct
 *  `ProcessBuilder` so the call is configuration-cache-safe — direct
 *  subprocesses at configure time are forbidden by the cache. */
fun gitDescribeOrNull(): String? {
    val exec = providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v*", "--abbrev=7")
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }
    val exit = exec.result.get().exitValue
    val output = exec.standardOutput.asText.get().trim()
    return output.takeIf { exit == 0 && it.isNotBlank() }
}
