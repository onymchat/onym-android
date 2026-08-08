// :group — the groups domain: create-group flow (interactor + VM +
// Compose screens), join/approve request flows, intro-key machinery
// (mint/store/pump), group persistence (Room GroupDatabase), avatar
// broadcast/encode, wire payloads (invitation / invite-offer / name /
// avatar / member-announcement / join-request), QR scanner + deeplink
// capture, and the SEP commitment builder over the Onym SDK.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire payloads throughout (ChatGroup,
    // GroupInvitationPayload, JoinRequestPayload, IntroCapability, ...).
    alias(libs.plugins.kotlin.serialization)
    // Compose UI: CreateGroupScreen, JoinScreen, ApproveRequestsScreen,
    // ScanToJoinScreen, ShareInviteScreen, QrScannerScreen.
    alias(libs.plugins.compose.compiler)
    // Room annotation processing (GroupDatabase / GroupDao).
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.onym.android.group"
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

    buildFeatures {
        compose = true
    }

    // Group-subject fakes formerly in app/src/sharedTest/.../support/:
    // InMemoryGroupStore (subject GroupStore) and InMemoryIntroKeyStore
    // (subject IntroKeyStore). Consumed by this module's own tests and
    // by app-side test suites (inbox dispatcher tests, chats VM tests,
    // androidTest E2E suites) — integrator wires
    // testImplementation(testFixtures(project(":group"))) on consumers.
    // NOTE (integrator): Kotlin sources in Android testFixtures are gated
    // behind `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x.
    testFixtures {
        enable = true
    }
}

dependencies {
    // ---- project modules ----

    // api: chain types saturate the public surface — ChatGroup exposes
    // GovernanceMember/SepTier/SepGroupType properties;
    // CreateGroupInteractor's public constructor takes RelayerRepository /
    // ContractsRepository / NetworkPreferenceProvider / GroupProofGenerator
    // and a (String) -> SepContractTransport factory;
    // GroupCommitmentBuilder.computeMerkleRoot takes List<GovernanceMember>.
    api(project(":chain"))

    // api: IdentityRepository / ActiveIdentityProvider / IdentitySummary /
    // InvitationEnvelopeSealer are public constructor parameters
    // (CreateGroupInteractor, GroupRepository, JoinRequestSender,
    // GroupAvatarBroadcaster); IdentityId is a public property type
    // (IntroKeyEntry.ownerIdentityId) and appears in IntroKeyStore's
    // public methods.
    api(project(":identity"))

    // api: InboxTransport / TransportInboxId are public constructor
    // parameters of IntroInboxPump, JoinRequestSender,
    // GroupAvatarBroadcaster and CreateGroupInteractor.
    api(project(":transport"))

    // api: StorageEncryption is a public constructor parameter of
    // RoomGroupStore; Base64ByteArraySerializer appears in
    // @Serializable(with = ...) on public payload types.
    api(project(":foundation"))

    // implementation: design tokens (LocalOnymTokens), OnymAccent,
    // OnymGroupAvatar and OnymQrCode are used only inside composable
    // bodies — no design type escapes a public signature.
    implementation(project(":design"))

    // implementation: R.string / R.plurals references live only in
    // composable bodies.
    implementation(project(":strings"))

    // ---- external ----

    // api(platform): the BOM must also pin the api'd compose artifact
    // below on consumers' classpaths.
    api(platform(libs.androidx.compose.bom))
    // api: exported screens (CreateGroupScreen, JoinScreen,
    // ApproveRequestsScreen, ScanToJoinScreen, ShareInviteScreen,
    // ApproveRequestsToolbarBadge) are @Composable — the annotation
    // lives in compose-runtime, an api dependency of ui, so consumers
    // need it on the compile classpath to call them.
    api(libs.androidx.compose.ui)
    // implementation: Material3 widgets, icons, foundation layouts,
    // graphics (Canvas/Path/Color) are all body-level.
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // api: public ViewModels (CreateGroupViewModel, JoinViewModel,
    // ApproveRequestsViewModel, ShareInviteViewModel) extend
    // androidx.lifecycle.ViewModel (public supertype) — viewmodel-compose
    // carries lifecycle-viewmodel (ViewModel + viewModelScope on 2.8).
    api(libs.androidx.lifecycle.viewmodel.compose)
    // implementation: collectAsStateWithLifecycle / LocalLifecycleOwner
    // in composable bodies.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // implementation: rememberLauncherForActivityResult +
    // PickVisualMediaRequest (avatar picker) in composable bodies.
    implementation(libs.androidx.activity.compose)
    // implementation: ContextCompat permission check in QrScannerScreen.
    implementation(libs.androidx.core.ktx)

    // api: StateFlow/Flow are public repository/store properties
    // (GroupRepository.snapshots, IntroKeyStore.entriesFlow,
    // IntroRequestStore.requests); CoroutineScope / CoroutineDispatcher
    // are public constructor parameters.
    api(libs.kotlinx.coroutines.core)

    // api: the module's public payload types are @Serializable (the
    // annotation + generated serializer API come from serialization-core,
    // an api dep of -json).
    api(libs.kotlinx.serialization.json)

    // api: GroupDatabase publicly extends RoomDatabase, and GroupDao is
    // a public @Dao interface returned by GroupDatabase.groupDao()
    // (called from app's OnymApplication).
    api(libs.androidx.room.runtime)
    // implementation: coroutines/Flow query support for the DAO.
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // implementation: EncryptedSharedPreferences + MasterKey inside
    // EncryptedPrefsIntroKeyStore bodies only.
    implementation(libs.androidx.security.crypto)

    // implementation: OkHttpClient() is constructed inside the default
    // makeContractTransport lambdas of CreateGroupInteractor /
    // JoinRequestApprover — no OkHttp type in a public signature (the
    // exposed factory type is chain's SepContractTransport).
    implementation(libs.okhttp)

    // implementation: X25519 keygen inside InviteIntroducer bodies.
    implementation(libs.bouncycastle)

    // implementation: MultiFormatReader decode inside the internal
    // QrCodeAnalyzer.
    implementation(libs.zxing.core)

    // implementation: CameraX preview + analysis behind the internal
    // QrScannerScreen; camera2 is the runtime backend.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // implementation: chat.onym.sdk.Common is used only inside
    // GroupCommitmentBuilder / GroupAvatarImage bodies — the public
    // surface is ByteArray-based.
    implementation(libs.onym.sdk)

    // ---- unit tests (moved from app/src/test/.../group/, minus
    // GroupNamePayloadTest + GroupAvatarPayloadTest which decode
    // app-side ChatMessagePayload and stayed in :app) ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric drives Room from the JVM runner (RoomGroupStoreTest);
    // ext-junit supplies AndroidJUnit4 + ApplicationProvider.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // FakeActiveIdentityProvider.
    testImplementation(testFixtures(project(":identity")))
    // This module's own fixtures (InMemoryGroupStore, InMemoryIntroKeyStore).
    testImplementation(testFixtures(project(":group")))
    // FakeInboxTransport (subject: :transport's InboxTransport) — moved
    // from app/src/test/.../support/ into :transport's testFixtures at
    // integration time; consumed here by IntroInboxPumpTest and by app's
    // IncomingInvitationsInteractorTest.
    testImplementation(testFixtures(project(":transport")))
}
