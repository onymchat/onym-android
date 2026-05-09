import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "chat.onym.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "chat.onym.android"
        // EncryptedSharedPreferences requires API 23+. Picking 26 (O)
        // also unlocks AEAD ciphers + the keystore-backed master key
        // path with no compatibility shims.
        minSdk = 26
        targetSdk = 35
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

    // Shared test source set — fakes / fixtures / encryptors that
    // both `test/` (JVM unit) and `androidTest/` (instrumented) can
    // consume from the same `chat.onym.android.support` package.
    // Mirrors the iOS pattern of a single `Tests/OnymIOSTests/Support/`
    // directory available to every XCTest target.
    sourceSets {
        getByName("test") {
            java.srcDirs("src/test/kotlin", "src/sharedTest/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin", "src/sharedTest/kotlin")
        }
    }
}

dependencies {
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

    // ZXing core — pure-Java QR encoder + decoder. Used by `OnymQrCode`
    // to turn an invite URL into a 1-bit module grid (Compose Canvas
    // does the actual drawing — rounded modules + centre logo overlay
    // matching the iOS design without ZXing's stock bitmap renderer),
    // and by `QrCodeScannerView`'s ImageAnalysis pipeline to decode
    // QR codes from CameraX frames at scan time.
    implementation(libs.zxing.core)

    // CameraX — preview surface + ImageAnalysis pipeline for the
    // Create Group → Invite by Inbox Key → Scan QR flow. Pure
    // AndroidX (no Google Play Services dependency).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

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
