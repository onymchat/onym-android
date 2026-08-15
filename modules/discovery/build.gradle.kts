// :discovery — client side of the Discovery seat's static-snapshot /
// Ed25519 profile (onym-system discovery/Discovery-Static-Ed25519.md):
// canonical signing bytes, hard-enforced trust verification (provider
// manifest TOFU key pinning, snapshot chain, destination digest
// binding), source configuration + DataStore persistence, the
// repository publishing source-attributed catalog entries, and the
// append-only per-seat module selection store.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable types throughout (DiscoveryProviderManifest,
    // CatalogSnapshot, CatalogEntry, DiscoverySourcesConfiguration,
    // ModuleSelection) — the compiler plugin generates their
    // `.serializer()`s.
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no UI in this module.
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.discovery"
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

    // Discovery-subject fakes (FakeDiscoveryFetcher,
    // InMemoryDiscoveryStore) live in testFixtures so this module's
    // own tests and :app's wiring/UI tests share one implementation.
    // Kotlin sources here are gated behind
    // `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x (already set for :chain /
    // :transport).
    testFixtures {
        enable = true
    }
}

dependencies {
    // api: the module's @Serializable document + configuration types
    // are public API, and JsonElement appears in CatalogSnapshot's
    // public `entries` property (lossy per-entry decoding).
    api(libs.kotlinx.serialization.json)

    // api: StateFlow<DiscoveryState> is a public repository property.
    // `-core` (not `-android`): no Dispatchers.Main use in this module.
    api(libs.kotlinx.coroutines.core)

    // api: OkHttpClient is a public constructor parameter of
    // OkHttpDiscoveryFetcher.
    api(libs.okhttp)

    // api: DataStore<Preferences> is a public constructor parameter of
    // DataStorePreferencesDiscoveryStore and
    // DataStorePreferencesSeatSelectionStore.
    api(libs.androidx.datastore.preferences)

    // implementation: Ed25519Signer / Ed25519PublicKeyParameters are
    // used only inside DiscoveryTrust method bodies — no BouncyCastle
    // type in any public signature. Same scoping as :foundation's
    // TrustedAssetVerifier (which stays untouched; DiscoveryTrust is
    // hard-enforced and independent of its soft-mode switch).
    implementation(libs.bouncycastle)

    // Unit tests: fixture-pinned canonical bytes + trust verification
    // against onym-discovery's conformance fixtures, plus temp-file
    // DataStore round-trips.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM-only DataStore factory — the store tests open a real
    // PreferenceDataStoreFactory backed by a per-test temp file; no
    // Robolectric needed (same pattern as RelayerSelectionStoreTest).
    testImplementation(libs.androidx.datastore.preferences.core)
    // Mutation tests generate an in-test Ed25519 key and re-sign
    // freshly built manifests/snapshots.
    testImplementation(libs.bouncycastle)
    // This module's own tests consume its fixtures (Fake*/InMemory*
    // support types).
    testImplementation(testFixtures(project(":discovery")))

    // testFixtures need no extra declarations: they consume this
    // module's main types plus kotlinx-serialization / coroutines-core,
    // which all flow through the `api` deps above.
}
