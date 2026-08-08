plugins {
    // NOTE (integrator): `android-library` alias may not exist in
    // gradle/libs.versions.toml yet — if missing, add:
    //   android-library = { id = "com.android.library", version.ref = "agp" }
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // `BlossomServerEndpoint` / `BlossomServersConfiguration` /
    // `KnownBlossomServersDocument` are @Serializable (JSON persisted to
    // DataStore + fetched from GitHub Releases).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.onym.android.transportblossom"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Reusable fake (LoopbackBlossomClient, formerly app/src/sharedTest)
    // for :app's instrumented UI tests (LoopbackRegistryHarness).
    // NOTE (integrator): Kotlin sources in Android testFixtures are gated
    // behind `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x.
    testFixtures {
        enable = true
    }
}

dependencies {
    // `NostrEphemeralSignerProvider` appears in `OkHttpBlossomClient`'s
    // public constructor — must be `api`, not `implementation`.
    api(project(":transport"))
    // `NostrEvent` is only used inside the (internal) BUD-01 auth-header
    // builder — never in a public signature — so `implementation`.
    implementation(project(":transport-nostr"))
    // `TrustedAssetVerifier` appears in
    // `GitHubReleasesKnownBlossomServersFetcher`'s public constructor
    // (default-arg param) — `api`.
    api(project(":foundation"))

    // `OkHttpClient` appears in the public constructors of
    // `OkHttpBlossomClient` and `GitHubReleasesKnownBlossomServersFetcher`.
    api(libs.okhttp)
    // `DataStore<Preferences>` appears in
    // `DataStoreBlossomServersSelectionStore`'s public constructor.
    api(libs.androidx.datastore.preferences)
    // `StateFlow` appears in the public API
    // (`BlossomServersRepository.snapshots`). `-core` (Android-free)
    // flavour, matching :transport; `Dispatchers.IO` lives in core too.
    api(libs.kotlinx.coroutines.core)
    // kotlinx-serialization: `Json` encode/decode is internal to the
    // store/fetcher, and no outside consumer calls the generated
    // `serializer()` members — `implementation`. Flip to `api` if a
    // consumer ever serializes these types itself.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
