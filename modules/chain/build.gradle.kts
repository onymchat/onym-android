// :chain — on-chain / relayer layer: SEP contract client + typed
// invocation payloads, contracts-manifest + known-relayers fetchers
// (signature-gated via :foundation's TrustedAssetVerifier), relayer
// configuration/selection stores, network preference, group proof
// generation over the Onym SDK, and the chain-state read seam.
plugins {
    // NOTE (integrator): `android-library` alias does not exist in
    // gradle/libs.versions.toml yet — add:
    //   android-library = { id = "com.android.library", version.ref = "agp" }
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable types throughout (SepContractTypes, RelayerEndpoint,
    // GovernanceMember, ContractEntry, AnchorSelection) — the compiler
    // plugin generates their `.serializer()`s.
    alias(libs.plugins.kotlin.serialization)
    // NOT applied — nothing here needs them:
    //  - compose-compiler: no UI in this module.
    //  - ksp: no Room / annotation processing.
}

android {
    namespace = "app.onym.android.chain"
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

    // Chain-subject fakes formerly in app/src/sharedTest/.../support/
    // (ConfigurableContractTransport, FakeContractsManifestFetcher,
    // FakeKnownRelayersFetcher, FakeSepContractTransport,
    // InMemoryAnchorSelectionStore, InMemoryChainLedger,
    // InMemoryRelayerSelectionStore, StubGroupProofGenerator) plus
    // FakeOkHttpClient (subject is OkHttp, but its only consumers are
    // this module's tests + app's BearerAuthScopingTest — both reachable
    // via testFixtures(":chain")).
    // NOTE (integrator): Kotlin sources in Android testFixtures are gated
    // behind `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x.
    testFixtures {
        enable = true
    }
}

dependencies {
    // api: TrustedAssetVerifier is a public constructor parameter
    // (defaulted) of GitHubReleasesContractsManifestFetcher and
    // GitHubReleasesKnownRelayersFetcher — callers resolving those
    // constructors need the type. Base64ByteArraySerializer also
    // appears in @Serializable(with = ...) on public types.
    api(project(":foundation"))

    // implementation: R.string references live only in enum-constant
    // initializers / function bodies (ContractEntry, RelayerEndpoint,
    // RelayerErrorMessages) — no strings type in any public signature.
    implementation(project(":strings"))

    // implementation: chat.onym.sdk.{Tyranny,OneOnOne,Anarchy} are used
    // only inside OnymGroupProofGenerator method bodies; the public
    // proof types (GroupCreateProof etc.) are ByteArray-based.
    implementation(libs.onym.sdk)

    // api: BearerAuthInterceptor implements okhttp3.Interceptor
    // (public supertype), and OkHttpClient is a public constructor
    // parameter of OkHttpSepContractTransport + both GitHub releases
    // fetchers.
    api(libs.okhttp)

    // api: DataStore<Preferences> is a public constructor parameter of
    // DataStorePreferencesAnchorSelectionStore,
    // DataStorePreferencesRelayerSelectionStore and
    // DataStoreNetworkPreferenceProvider. Also carries the
    // androidx.annotation dependency (@StringRes on public properties)
    // transitively as an api dep of androidx.datastore.
    api(libs.androidx.datastore.preferences)

    // api: KSerializer appears in the public SepContractTransport.submit
    // signature (implemented by app-side test doubles via testFixtures),
    // and the module's @Serializable data types are public API.
    api(libs.kotlinx.serialization.json)

    // api: StateFlow<RelayerState> / StateFlow<ContractsState> are
    // public repository properties; Flow is in the public
    // NetworkPreferenceProvider interface. `-core` (not `-android`):
    // no Dispatchers.Main use in this module.
    api(libs.kotlinx.coroutines.core)

    // Unit tests (moved from app/src/test/.../chain/, minus
    // BearerAuthScopingTest which tests app-level client wiring and
    // stayed in :app).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM-only DataStore factory — RelayerSelectionStoreTest /
    // AnchorSelectionStoreTest open a real PreferenceDataStoreFactory
    // backed by a per-test temp file; no Robolectric needed.
    testImplementation(libs.androidx.datastore.preferences.core)
    // Fetcher tests generate Ed25519 keys + detached signatures for the
    // TrustedAssetVerifier gate. :foundation keeps BouncyCastle as
    // `implementation`, so it does not flow here transitively.
    testImplementation(libs.bouncycastle)
    // This module's own tests consume its fixtures (Fake*/InMemory*
    // support types).
    testImplementation(testFixtures(project(":chain")))

    // testFixtures need no extra declarations: they consume this
    // module's main types plus okhttp / kotlinx-serialization /
    // coroutines-core, which all flow through the `api` deps above.
}
