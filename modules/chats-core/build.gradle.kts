// :chats-core — the chats DOMAIN half: message domain model (ChatMessage /
// MessageDirection / MessageStatus), wire payloads (ChatMessagePayload /
// ChatReceiptPayload), Room persistence (MessageDatabase / MessageDao /
// PersistedMessage / RoomMessageStore behind the MessageStore seam),
// MessageRepository, the send pipeline (SendMessageInteractor + ChatOutbox +
// media encoders/loaders/crypto), receipt sending, and the read-receipts
// preference. The chats UI half (screens / view models / bubbles) stays in
// :app — that placement dissolves the chats<->inbox cycle.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire payloads (ChatMessagePayload, ChatReceiptPayload,
    // attachment metadata blobs persisted by RoomMessageStore).
    alias(libs.plugins.kotlin.serialization)
    // Room annotation processing (MessageDatabase / MessageDao).
    alias(libs.plugins.ksp)
    // NO compose plugin: this module has no @Composable — all chats UI
    // stays in :app.
}

android {
    namespace = "app.onym.android.chatscore"
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

    // Chats-subject fake formerly in app/src/sharedTest/.../support/:
    // InMemoryMessageStore (subject: this module's MessageStore seam).
    // Consumed by this module's own tests (MessageRepositoryTest,
    // SendMessageInteractorTest) and by app-side suites (ChatsViewModelTest,
    // ChatThreadViewModelTest, IncomingMessageDispatcherChatMessageTest,
    // androidTest ChatsSwipeDeleteScreenTest) — integrator wires
    // testImplementation(testFixtures(project(":chats-core"))) /
    // androidTestImplementation(...) on consumers.
    // NOTE (integrator): Kotlin sources in Android testFixtures are gated
    // behind `android.experimental.enableTestFixturesKotlinSupport=true`
    // in gradle.properties on AGP 8.x.
    testFixtures {
        enable = true
    }
}

dependencies {
    // ---- project modules ----

    // api: SepGroupType is a public property of ChatMessage
    // (ChatMessage.groupType) and appears throughout ChatMessagePayload's
    // public serializable surface.
    api(project(":chain"))

    // api: ActiveIdentityProvider / IdentitySummary (via
    // StateFlow<List<IdentitySummary>>) / InvitationEnvelopeSealer are
    // public constructor parameters of SendMessageInteractor,
    // ChatReceiptSender and MessageRepository.
    api(project(":identity"))

    // api: InboxTransport is a public constructor parameter of
    // SendMessageInteractor and ChatReceiptSender.
    api(project(":transport"))

    // api: BlossomClient is a public constructor parameter of
    // ChatImageLoader / ChatVideoLoader / ChatVoiceLoader /
    // SendMessageInteractor.
    api(project(":transport-blossom"))

    // api: GroupRepository is a public constructor parameter of
    // SendMessageInteractor.
    api(project(":group"))

    // api: StorageEncryption is a public constructor parameter of
    // RoomMessageStore; Base64ByteArraySerializer appears in
    // @Serializable(with = ...) on public payload types.
    api(project(":foundation"))

    // TRIMMED: :strings — no R.string / resource reference anywhere in the
    // domain files (all user-facing strings live in the UI half in :app).

    // ---- external ----

    // api: StateFlow<List<IdentitySummary>> / Flow / CoroutineScope /
    // CoroutineDispatcher are public constructor parameters and public
    // properties (MessageRepository.snapshots, ReadReceiptsPreferenceProvider.flow).
    api(libs.kotlinx.coroutines.core)
    // implementation: Dispatchers.Main inside ChatVideoEncoder (Media3
    // Transformer must run on the main looper) — body-level only.
    implementation(libs.kotlinx.coroutines.android)

    // api: the module's public payload types (ChatMessagePayload,
    // ChatReceiptPayload, attachment metadata) are @Serializable — the
    // annotation + generated serializer API come from serialization-core,
    // an api dep of -json.
    api(libs.kotlinx.serialization.json)

    // api: MessageDatabase publicly extends RoomDatabase; MessageDao is a
    // public @Dao interface returned by MessageDatabase.messageDao()
    // (called from app's OnymApplication, which also consumes the public
    // androidx.room.migration.Migration objects in MessageDatabaseMigrations).
    api(libs.androidx.room.runtime)
    // implementation: coroutines/Flow query support for the DAO.
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // api: DataStore<Preferences> is a public constructor parameter of
    // DataStoreReadReceiptsPreferenceProvider, and its public companion
    // exposes a Preferences.Key (SEND_READ_RECEIPTS).
    api(libs.androidx.datastore.preferences)

    // implementation: Media3 Transformer/effect/common are used only
    // inside ChatVideoEncoder bodies (public API is Context/Uri/ByteArray).
    // TRIMMED vs the 5 app artifacts: media3-exoplayer + media3-ui are
    // playback-only and used solely by ChatThreadScreen, which stays in :app.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    // ---- unit tests (moved from app/src/test/.../chats/, minus the
    // UI-subject suites — ChatBubbleStatusTest, ChatInputPanelTest,
    // ChatMembersSortTest, ChatReplyQuoteTest, ChatSenderDisplayTest,
    // ChatThreadAutoScrollTest, ChatThreadTitleTest, ChatThreadViewModelTest,
    // ChatsViewModelTest — which stay in :app with their subjects) ----
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric drives Room from the JVM runner (RoomMessageStoreTest)
    // and hosts the AndroidJUnit4-run SendMessageInteractorTest; ext-junit
    // supplies AndroidJUnit4 + ApplicationProvider.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // FakeActiveIdentityProvider (MessageRepositoryTest,
    // SendMessageInteractorTest).
    testImplementation(testFixtures(project(":identity")))
    // InMemoryGroupStore (SendMessageInteractorTest).
    testImplementation(testFixtures(project(":group")))
    // ConfigurableInboxTransport (SendMessageInteractorTest).
    testImplementation(testFixtures(project(":transport")))
    // This module's own fixture (InMemoryMessageStore).
    testImplementation(testFixtures(project(":chats-core")))
    // Ed25519 verification of the authority's normative proof fixture
    // (ChatModerationProofTest) — test-only; production signing lives
    // in :identity.
    testImplementation(libs.bouncycastle)
}
