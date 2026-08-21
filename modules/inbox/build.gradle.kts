// :inbox — the receive side of the app: the inbox pump
// (IncomingInvitationsInteractor) that fans out per-identity transport
// subscriptions, the receive-time decrypt-and-route dispatcher
// (IncomingMessageDispatcher), the legacy incoming-invitations
// repository, the push-invite pending stores + Accept/Dismiss flow
// (PendingInvites*), and the verify-at-current-state machinery
// (GroupStateVerifier / PendingVerificationStore).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable DecryptedInvitation (parsed BootstrapPayload subset).
    alias(libs.plugins.kotlin.serialization)
    // Compose UI: the pending-chat thread screen.
    alias(libs.plugins.compose.compiler)
    // Room codegen for the durable pending-chat store.
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.onym.android.inbox"
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
}

dependencies {
    // ---- project modules ----

    // api: chats types saturate IncomingMessageDispatcher's public
    // constructor — MessageRepository? and ChatReceiptSending (default
    // NoopChatReceiptSender()) are public parameter types.
    api(project(":chats-core"))

    // api: GroupRepository is a public constructor parameter of
    // IncomingMessageDispatcher / GroupStateVerifier /
    // PendingInvitesViewModel; GroupInvitationPayload +
    // GroupStateRefreshRequest appear in the public GroupStateRefreshing
    // interface methods; IntroCapability / JoinRequestSender.Outcome are
    // in PendingInvitesViewModel's public submitJoin lambda type.
    api(project(":group"))

    // api: IdentityId is a public property/parameter type everywhere
    // (PendingInvite, GroupStateRefreshing, repository mutators);
    // InvitationEnvelopeDecrypter / InvitationEnvelopeSealer /
    // ActiveIdentityProvider / IdentitySummary are public constructor
    // parameters.
    api(project(":identity"))

    // api: ChainStateReading? is a public constructor parameter of
    // IncomingMessageDispatcher (SepTier / SepGroupType are body-level).
    api(project(":chain"))

    // api: InboxTransport / TransportInboxId are public constructor /
    // method parameters of IncomingInvitationsInteractor and
    // GroupStateVerifier.
    api(project(":transport"))

    // api: InvitationStore is a public constructor parameter of
    // IncomingInvitationsRepository; IncomingInvitationStatus is the
    // public updateStatus(...) parameter type.
    api(project(":persistence"))

    // implementation: Base64ByteArraySerializer only annotates the
    // internal DecryptedInvitation — no foundation type escapes a public
    // signature. (Not in the planned dep list; required by
    // DecryptedInvitation.kt — see EXTRACTION-NOTES.md.)
    implementation(project(":foundation"))

    // implementation: R.string references live only in composable bodies.
    implementation(project(":strings"))

    // ---- external ----

    // api(platform): the BOM must also pin the api'd compose artifact
    // below on consumers' classpaths.
    api(platform(libs.androidx.compose.bom))
    // api: exported screens (PendingInvitesScreen,
    // PendingInvitesToolbarBadge) are @Composable — consumers need
    // compose-runtime (api dep of ui) on the compile classpath.
    api(libs.androidx.compose.ui)
    // implementation: Material3 widgets, icons, graphics (Color) are all
    // body-level.
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // api: PendingInvitesViewModel publicly extends
    // androidx.lifecycle.ViewModel.
    api(libs.androidx.lifecycle.viewmodel.compose)
    // implementation: collectAsStateWithLifecycle in composable bodies.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // api: StateFlow is a public property type (PendingInvitesStore.
    // snapshots, PendingVerificationStore.snapshots, the view-model's
    // pending/verifying/... streams) and a public constructor parameter
    // (GroupStateVerifier's identities/activeIdentityId,
    // IncomingMessageDispatcher's identitiesFlow); CoroutineScope is a
    // public constructor parameter.
    api(libs.kotlinx.coroutines.core)

    // api: PendingChatDao / PendingChatDatabase are public types the
    // app module builds and hands to RoomPendingChatStore.
    api(libs.androidx.room.runtime)
    // implementation: DAO bodies + the suspend query extensions.
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // implementation: Json encode/decode inside dispatcher/verifier/
    // decryptor bodies; the only @Serializable type here
    // (DecryptedInvitation) is internal.
    implementation(libs.kotlinx.serialization.json)

    // ---- unit tests (moved from app/src/test/.../inbox/) ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // FakeActiveIdentityProvider, FakeInvitationEnvelopeDecrypter.
    testImplementation(testFixtures(project(":identity")))
    // InMemoryGroupStore.
    testImplementation(testFixtures(project(":group")))
    // FakeInboxTransport, ConfigurableInboxTransport.
    testImplementation(testFixtures(project(":transport")))
    // InMemoryMessageStore (subject: chats' MessageStore) — expected to
    // land in :chats-core's testFixtures during the parallel chats-core
    // extraction; today it still sits in app/src/sharedTest/.../support/.
    // Integrator: confirm or rewire (see EXTRACTION-NOTES.md).
    testImplementation(testFixtures(project(":chats-core")))
}
