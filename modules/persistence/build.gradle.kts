// :persistence — the Room-backed invitation persistence layer. The
// InvitationStore seam + its two impls (RoomInvitationStore behind
// Room + StorageEncryption; InMemoryInvitationStore as the V1
// process-lifetime default), plus the Room entity/DAO/database
// plumbing (all internal — callers only ever see the seam).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // ksp: Room annotation processor (InvitationDao / InvitationDatabase
    // / PersistedInvitation bindings). Same wiring pattern as :app.
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no @Composable code.
    //  - kotlin-serialization: no @Serializable types (the payload is
    //    an opaque ByteArray).
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.onym.android.persistence"
    compileSdk = 36

    defaultConfig {
        // Same floor as :app — EncryptedSharedPreferences requires
        // API 23+; 26 unlocks AEAD ciphers + the keystore-backed
        // master-key path (this module's StorageEncryption input
        // rides on that stack).
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
    // implementation: StorageEncryption appears only in
    // RoomInvitationStore's constructor + body, and RoomInvitationStore
    // is internal — :foundation types never reach this module's public
    // API. (:app depends on :foundation directly, so its own uses don't
    // ride on this edge.)
    implementation(project(":foundation"))

    // implementation: Mutex / withContext / Dispatchers.IO in bodies
    // only. The public seam (InvitationStore) is suspend-only — suspend
    // compiles against kotlin-stdlib's Continuation, not kotlinx
    // types — and exposes no Flow, so `-core` and implementation
    // scope both suffice.
    implementation(libs.kotlinx.coroutines.core)

    // Room — `suspend` DAO + KSP-generated bindings. implementation
    // (not api): the entity/DAO/database trio is internal; the public
    // seam trades in plain Kotlin types (String / ByteArray / Instant).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Unit tests: RoomInvitationStoreTest drives the real Room backend
    // via Robolectric (Room needs a Context to open the in-memory DB).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // ApplicationProvider + AndroidJUnit4 runner (androidx.test:core
    // arrives transitively).
    testImplementation(libs.androidx.test.ext.junit)
}
