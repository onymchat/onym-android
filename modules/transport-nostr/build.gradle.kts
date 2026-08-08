plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire/persistence models: NostrRelayEndpoint,
    // NostrRelaysConfiguration, KnownNostrRelaysDocument.
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Kotlin packages stay `app.onym.android.transport.nostr`; the
    // namespace drops the dot so it can never be mistaken for a
    // sub-namespace of :transport's `app.onym.android.transport`.
    namespace = "app.onym.android.transportnostr"
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
    // `api`: transport seam types appear in this module's public API —
    // NostrInboxTransport implements InboxTransport and its constructor
    // takes NostrEphemeralSignerProvider; NostrEvent.build takes a
    // NostrSigner.
    api(project(":transport"))

    // `api`: TrustedAssetVerifier appears as a (defaulted) public
    // constructor parameter of GitHubReleasesKnownNostrRelaysFetcher.
    api(project(":foundation"))

    // `api`: StateFlow<NostrRelaysConfiguration> is the type of
    // NostrRelaysRepository.snapshots; CoroutineScope is a public
    // constructor parameter of both transports. Android-free `-core`
    // suffices (Dispatchers.IO is JVM coroutines-core; no Main here).
    api(libs.kotlinx.coroutines.core)

    // `api`: OkHttpClient is a public constructor parameter of
    // GitHubReleasesKnownNostrRelaysFetcher (passed by OnymApplication
    // and :chain's BearerAuthScopingTest).
    api(libs.okhttp)

    // `api`: DataStore<Preferences> is a public constructor parameter
    // of DataStoreNostrRelaysSelectionStore (OnymApplication passes the
    // app-owned preferences DataStore in).
    api(libs.androidx.datastore.preferences)

    // JSON codec for the persisted relay configuration + the fetched
    // nostr-relays.json document. No outside consumer touches the
    // generated serializers, so `implementation` is enough.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android unit tests stub `org.json` (every method throws "not
    // mocked"); NostrEvent + its tests exercise the real codec. Same
    // pinned coordinate :app uses — not in the version catalog.
    testImplementation("org.json:json:20240303")
}
