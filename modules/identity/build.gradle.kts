// :identity — the on-device identity layer. IdentityRepository (BIP39 +
// HKDF derivation, EncryptedSharedPreferences persistence, X25519+AES-GCM
// invitation seal/decrypt), IdentitySecretStore, the SealedEnvelope wire
// format, the ActiveIdentityProvider / InvitationEnvelope{Sealer,Decrypter}
// seams, OnymNostrSigner (the only OnymSDK FFI touchpoint for Nostr
// signing), and the Settings→Identities ViewModel.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable classes: IdentityId, SealedEnvelope, StoredSnapshot,
    // StoredIndex.
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no @Composable code (IdentitiesViewModel is a
    //    plain ViewModel; the screens stay in :app).
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.identity"
    compileSdk = 36

    defaultConfig {
        // EncryptedSharedPreferences requires API 23+; 26 unlocks AEAD
        // ciphers + the keystore-backed master key path (same rationale
        // as :app).
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Reusable identity fakes for :app's unit + instrumented tests,
    // formerly app/src/sharedTest/.../support/: FakeActiveIdentityProvider,
    // FakeInvitationEnvelopeDecrypter, TestInvitationEncryptor.
    // NOTE (integrator): Kotlin sources in Android testFixtures are gated
    // behind `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x (same note as :transport).
    testFixtures {
        enable = true
    }
}

dependencies {
    // implementation: Bip39 / StellarStrKey / Base64ByteArraySerializer
    // appear only in bodies and in @Serializable(with = ...) annotation
    // arguments, never in public signatures. (:app depends on :foundation
    // directly, so its own uses don't ride on this edge.)
    implementation(project(":foundation"))

    // api: NostrSigner is the public supertype of OnymNostrSigner, and
    // NostrEphemeralSignerProvider of OnymNostrSignerProvider — both part
    // of this module's public API surface.
    api(project(":transport"))

    // api: StateFlow appears in public signatures (IdentityRepository.
    // snapshots/identities, ActiveIdentityProvider.currentIdentityId,
    // IdentitiesViewModel.items/error/addResult).
    api(libs.kotlinx.coroutines.core)
    // implementation: Dispatchers.Main for viewModelScope at runtime.
    implementation(libs.kotlinx.coroutines.android)

    // api: the kotlinx-serialization plugin generates public members on
    // public @Serializable types (SealedEnvelope.serializer(), IdentityId's
    // serializer) whose signatures expose KSerializer.
    api(libs.kotlinx.serialization.json)

    // api: androidx.lifecycle.ViewModel is the public supertype of
    // IdentitiesViewModel. NOTE (integrator): the catalog's only viewmodel
    // alias is the -compose artifact; a leaner
    //   androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "androidx-lifecycle" }
    // alias would carry ViewModel + viewModelScope without the Compose
    // bridge if you prefer to add one.
    api(libs.androidx.lifecycle.viewmodel.compose)

    // implementation: EncryptedSharedPreferences + MasterKey live entirely
    // inside IdentitySecretStore's body; the public constructor exposes
    // only android.content.Context.
    implementation(libs.androidx.security.crypto)

    // implementation: BouncyCastle types (X25519/Ed25519 parameters,
    // signers, agreement) appear in bodies and internal members only
    // (stellarSigningPrivateKey / inboxKeyAgreementPrivateKey are
    // internal because they return secret material).
    implementation(libs.bouncycastle)

    // implementation: chat.onym.sdk.Common / OnymException appear only in
    // bodies — the FFI never leaks into public signatures. Structural
    // touch-surface rule: OnymSDK is imported only from this package.
    implementation(libs.onym.sdk)

    // Unit tests: plain JUnit4. CrossPlatformFixtureTest /
    // EnvelopeSenderAuthenticationTest / SealedEnvelopeTest reach
    // BouncyCastle + kotlinx-serialization + :foundation through the
    // main-source deps above (testImplementation extends implementation).
    // No coroutines-test / robolectric / org.json needed here.
    testImplementation(libs.junit)

    // testFixtures: TestInvitationEncryptor's public signature exposes
    // Ed25519PrivateKeyParameters (callers mint sender keys), so
    // BouncyCastle must be api for fixture consumers.
    testFixturesApi(libs.bouncycastle)
    // testFixtures body uses Bip39.hkdfSha256 for the envelope KDF.
    testFixturesImplementation(project(":foundation"))
    // Coroutines (FakeActiveIdentityProvider's MutableStateFlow) and
    // kotlinx-serialization (TestInvitationEncryptor's Json encode) flow
    // through this module's main `api` deps — no extra declarations.
}
