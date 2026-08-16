// :moderation — client side of the moderation seat's Google
// device-recall enforcement profile (onym-system
// moderation/Moderation-Device-Recall.md, profile
// onym:moderation-enforcement-profile:google-device-recall-v1):
// signed-session payloads pinned byte-for-byte to the backend's
// published fixtures (onym-moderation android/fixtures/), the
// enforcement-backend transport (challenge, enroll, countersign,
// gate-check), authority directory + authenticated manifest review,
// mandate consent, the Play Integrity attestation provider, and the
// gate-check repository with the pure offline-grace arithmetic.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire types throughout (requests, gate results,
    // mandate, manifest, persisted gate state).
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no UI in this module (:moderation-ui owns it).
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.moderation"
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

    // Moderation-subject fakes (FakeDeviceAttestationProvider,
    // ScriptedEnforcementBackendClient, in-memory stores, the signed
    // manifest fixture builder) live in testFixtures so this module's
    // own tests and :app's wiring/UI tests share one implementation.
    testFixtures {
        enable = true
    }
}

dependencies {
    // api: the module's @Serializable wire/consent types are public
    // API, and JsonElement appears in BanState's public `verdict`
    // (kept opaque — display validation is a deferred seam).
    api(libs.kotlinx.serialization.json)

    // api: StateFlow<GateStatus> / StateFlow<ModerationState> are
    // public repository properties. `-core`: no Dispatchers.Main here.
    api(libs.kotlinx.coroutines.core)

    // api: OkHttpClient is a public constructor parameter of the
    // OkHttp fetchers and the enforcement backend client.
    api(libs.okhttp)

    // api: DataStore<Preferences> is a public constructor parameter of
    // the DataStore-backed mandate and gate-state stores.
    api(libs.androidx.datastore.preferences)

    // Play Integrity — StandardIntegrityManager (prepare + request
    // with the backend-challenge-bound requestHash). implementation:
    // no Play type appears in a public signature; the seam is
    // DeviceAttestationProvider.
    implementation(libs.play.integrity)
    // Task.await() for the Play Integrity Tasks API.
    implementation(libs.kotlinx.coroutines.play.services)

    // implementation: Ed25519 detached-signature verification of
    // authority manifests inside method bodies only — same scoping as
    // :discovery's DiscoveryTrust.
    implementation(libs.bouncycastle)

    // Unit tests: fixture-pinned payload bytes (the backend's
    // android/fixtures are normative), canonical mandate bytes, codec
    // round-trips, the derive truth table, and the prepare rate
    // limiter — all pure JVM, no device (the FFI constraint keeps
    // JNI-dependent tests in :app's androidTest).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle)
    testImplementation(libs.androidx.datastore.preferences.core)
    testImplementation(testFixtures(project(":moderation")))
}
