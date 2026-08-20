// :foundation — pure crypto / encoding primitives with no project deps.
// Bip39 (BIP39 + project HKDF stretches), StellarStrKey (SEP-0023),
// StorageEncryption (AES-256-GCM field encryption), TrustedAssetVerifier
// + DetachedSignatureFetch (Ed25519 gate for fetched config assets),
// Base64Serializers (iOS-compatible ByteArray JSON encoding), and the
// seat-generic consent/offers primitives (SignedServiceManifest /
// ServiceManifestReviewer / PinnedConsentStore / entitlements).
plugins {
    // No `android-library` alias exists in libs.versions.toml (the
    // integrator may want to add one). The root build.gradle.kts loads
    // AGP onto the build classpath via `alias(libs.plugins.android
    // .application) apply false`, and `com.android.library` ships in
    // the same artifact, so it resolves here without a version.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    // @Serializable PinnedConsentRecord — the compiler plugin
    // generates its `.serializer()`. (Base64Serializers still
    // hand-implements KSerializer; that needs no plugin.)
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no UI.
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.foundation"
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
    // api: `OkHttpClient.fetchDetachedSignature(...)` is a public
    // extension on OkHttpClient, so the type is part of this module's
    // public API surface.
    api(libs.okhttp)

    // api: Base64ByteArraySerializer implements KSerializer<ByteArray>
    // (a kotlinx-serialization-core type) and is referenced from
    // @Serializable(with = ...) sites in consuming modules. Only the
    // -core artifact is strictly needed, but the catalog exposes -json
    // (which carries -core transitively) and the integrator owns the
    // catalog.
    api(libs.kotlinx.serialization.json)

    // api: DataStore<Preferences> is a public constructor parameter
    // of DataStorePreferencesPinnedConsentStore (same posture as
    // :chain's selection stores).
    api(libs.androidx.datastore.preferences)

    // api: PinnedConsentStore's methods are `suspend`, so coroutine
    // types are part of the public API surface.
    api(libs.kotlinx.coroutines.core)

    // implementation: BouncyCastle types (HKDFBytesGenerator, Ed25519
    // Signer/PublicKeyParameters) appear only in bodies / internal
    // members, never in public signatures.
    implementation(libs.bouncycastle)

    // implementation: EncryptedSharedPreferences + MasterKey are used
    // only inside StorageEncryption.fromContext(); the public signature
    // exposes android.content.Context only.
    implementation(libs.androidx.security.crypto)

    // api: PurchasesUpdatedListener / Purchase / BillingClient appear
    // in SeatPurchaseFlow's and PlayBillingEntitlementProvider's
    // public constructor signatures.
    api(libs.play.billing)

    // Unit tests: plain JUnit4. The tests reach BouncyCastle (test key
    // generation / provider registration) through the implementation
    // dep above — testImplementation extends implementation.
    testImplementation(libs.junit)
    // runTest for the suspend consent-store API, and the pure-JVM
    // DataStore artifact for temp-file-backed store tests (same
    // pattern as :chain's RelayerSelectionStoreTest).
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences.core)
}
