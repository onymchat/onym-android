// :backup — client side of the device-backup seat (onym-system
// backup/UI-Backup.md, implementation profile
// onym:backup-implementation:object-http-v1 pinned in
// backup/UI-Backup-Object-HTTP.md): the pure domain (port, profile,
// terms, manifest, errors — no I/O, no crypto), seed-derived sealing
// (Padmé padding, chunked AES-256-GCM, the access-signing/agreement
// keys), archive composition/restore over a source/sink port, and the
// object-HTTP wire adapter + repository.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire types (terms, manifest fields, archive
    // header, outcome/receipt JSON).
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no UI in this module (:backup-ui owns it).
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.backup"
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

    // Fakes shared between this module's own tests and :app's
    // wiring/UI tests: an in-memory BackupPort, scripted
    // source/sink providers, and a signed-terms/manifest fixture
    // builder — same posture as :moderation's testFixtures.
    testFixtures {
        enable = true
    }
}

dependencies {
    // api: canonical-bytes helper (ServiceManifestCanonical) reused
    // for BackupTerms.termsId, plus SignedServiceManifest /
    // ServiceOffer types that appear in BackupOperatorManifest's
    // public constructor.
    api(project(":foundation"))

    // api: the module's @Serializable wire/domain types are public
    // API (BackupTerms, ErasureReceipt, archive header).
    api(libs.kotlinx.serialization.json)

    // api: StateFlow<BackupRepositoryState> is a public repository
    // property. `-core`: no Dispatchers.Main here.
    api(libs.kotlinx.coroutines.core)

    // api: OkHttpClient is a public constructor parameter of
    // ObjectHttpBackupClient.
    api(libs.okhttp)

    // api: DataStore<Preferences> is a public constructor parameter
    // of the DataStore-backed BackupStateStore.
    api(libs.androidx.datastore.preferences)

    // implementation: BouncyCastle Ed25519/X25519 (access-proof
    // signing, key derivation) used only inside method bodies —
    // same scoping as :moderation's / :identity's usage.
    implementation(libs.bouncycastle)

    // Unit tests: sealing round trips (Padmé, chunked AEAD), archive
    // framing, terms regression, canonicalization fixtures — all
    // pure JVM, no device (no FFI/JNI involvement in this feature).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle)
    testImplementation(libs.androidx.datastore.preferences.core)
    testImplementation(testFixtures(project(":backup")))
}
