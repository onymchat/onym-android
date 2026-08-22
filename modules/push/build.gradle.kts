// :push — client side of the privacy-first push-notification design:
// the device registers its inbox tags + relay URLs + an encrypted FCM
// token with an Onym-run backend (push-android.onym.app) that watches
// the configured Nostr relays and sends a content-free, data-only FCM
// wake with server-side jitter. This module owns the wire contract
// (signed payloads pinned to the backend's normative fixtures), the
// sealed token envelope, the OkHttp transport, the DataStore-backed
// preference, and the registration reconciler. App wiring (Firebase,
// Settings, composition root) stays in :app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire types (requests, responses, the token
    // envelope).
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no UI in this module (:app owns Settings).
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.push"
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
}

dependencies {
    // api: Base64ByteArraySerializer appears in @Serializable(with = …)
    // on PushTokenEnvelope's public fields; Bip39.hkdfSha256 backs the
    // envelope's key derivation.
    api(project(":foundation"))

    // api: the module's public wire types are @Serializable.
    api(libs.kotlinx.serialization.json)

    // api: the interactor's public constructor takes a CoroutineScope;
    // the preference exposes Flow. `-core`: no Dispatchers.Main here.
    api(libs.kotlinx.coroutines.core)

    // api: OkHttpClient is a public constructor parameter of
    // OkHttpPushBackendClient.
    api(libs.okhttp)

    // api: DataStore<Preferences> is a public constructor parameter of
    // DataStorePushPreferenceProvider.
    api(libs.androidx.datastore.preferences)

    // implementation: X25519Agreement inside PushTokenEnvelope.seal's
    // body only — no BouncyCastle type in a public signature.
    implementation(libs.bouncycastle)

    // Unit tests: fixture-pinned payload bytes (the Rust backend's
    // fixtures are normative), envelope seal/decrypt round-trips,
    // canned-interceptor transport tests, and the reconciler under
    // virtual time — all pure JVM.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle)
    testImplementation(libs.androidx.datastore.preferences.core)
}
