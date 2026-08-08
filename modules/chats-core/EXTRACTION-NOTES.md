# :chats-core extraction notes

Domain half of `app.onym.android.chats` moved out of :app. Packages unchanged
(`app.onym.android.chats`; fixture stays `app.onym.android.support`).
Namespace: `app.onym.android.chatscore`.

## Files moved

Main (27), from `app/src/main/kotlin/app/onym/android/chats/` to
`modules/chats-core/src/main/kotlin/app/onym/android/chats/`:
Blurhash, ChatImageAttachment, ChatImageCrypto, ChatImageEncoder,
ChatImageLoader, ChatMediaAttachment, ChatMessage, ChatMessagePayload,
ChatOutbox, ChatReceiptPayload, ChatReceiptSender, ChatVideoAttachment,
ChatVideoEncoder, ChatVideoLoader, ChatVoiceAttachment, ChatVoiceLoader,
ChatVoiceRecorder, MessageDao, MessageDatabase, MessageDirection,
MessageStatus, MessageRepository, MessageStore, PersistedMessage,
ReadReceiptsPreference, RoomMessageStore, SendMessageInteractor.

Unit tests (8), to `modules/chats-core/src/test/kotlin/app/onym/android/chats/`:
ChatImageCryptoTest, ChatMessagePayloadTest, ChatReceiptPayloadTest,
ChatVideoAttachmentTest, ChatVoiceAttachmentTest, MessageRepositoryTest,
RoomMessageStoreTest, SendMessageInteractorTest.

Test fixture (1), from `app/src/sharedTest/kotlin/app/onym/android/support/` to
`modules/chats-core/src/testFixtures/kotlin/app/onym/android/support/`:
InMemoryMessageStore (subject: this module's MessageStore).
testFixtures enabled in build.gradle.kts. Integrator: consumers need
`testImplementation(testFixtures(project(":chats-core")))` (app unit tests:
ChatsViewModelTest, ChatThreadViewModelTest,
IncomingMessageDispatcherChatMessageTest) and
`androidTestImplementation(testFixtures(project(":chats-core")))`
(ChatsSwipeDeleteScreenTest). AGP 8.x also needs
`android.experimental.enableTestFixturesKotlinSupport=true`.

Stayed in :app (UI half, per plan): ChatBubble, ChatInputPanel,
ChatMembersScreen, ChatReplyBanner, ChatReplyQuote, ChatSenderDisplay,
ChatsScreen, ChatsViewModel, ChatThreadScreen, ChatThreadViewModel, and the
UI-subject unit tests (ChatBubbleStatusTest, ChatInputPanelTest,
ChatMembersSortTest, ChatReplyQuoteTest, ChatSenderDisplayTest,
ChatThreadAutoScrollTest, ChatThreadTitleTest, ChatThreadViewModelTest,
ChatsViewModelTest). app/src/androidTest untouched.

## Visibility audit (top-level declarations: 37 total — 33 public, 4 internal)

Marked `internal` (no consumer outside this module; module unit tests are a
friend compilation and still see them):

- `ChatImageCrypto` — used only inside ChatImageLoader / ChatVideoLoader /
  ChatVoiceLoader / SendMessageInteractor bodies + its own test. The
  mentions in :transport-blossom (BlossomClient.kt, LoopbackBlossomClient.kt)
  are KDoc comments only.
- `ChatMessageVariantSerializer` — referenced only via
  `@Serializable(with = ...)` inside this module.
- `UuidStringSerializer` — same.
- `StaticReadReceiptsPreferenceProvider` — zero references anywhere in the
  repo (main, test, androidTest). Dead code; kept but internal. Candidate
  for deletion.

Public, with justifying consumer (all paths repo-relative):

| Symbol | Consumer |
|---|---|
| Blurhash | app .../chats/ChatBubble.kt |
| ChatImageAttachment | app ChatBubble.kt, ChatThreadScreen.kt |
| ChatImageEncoder | app OnymApplication.kt (also SendMessageInteractor's public default param type ChatImageEncoder.Encoded) |
| ChatImageLoader | app OnymApplication.kt, inbox/IncomingMessageDispatcher.kt, ChatThreadViewModel.kt |
| ChatMediaAttachment | app ChatBubble.kt, ChatThreadScreen.kt |
| ChatMediaSource | app ChatInputPanel.kt, ChatThreadScreen.kt, ChatThreadViewModel.kt |
| ChatMessage | app inbox/IncomingMessageDispatcher.kt + chats UI + app tests |
| ChatMessagePayload | app inbox/IncomingMessageDispatcher.kt; app tests group/GroupNamePayloadTest.kt, group/GroupAvatarPayloadTest.kt |
| ChatMessageVariant | app inbox/IncomingMessageDispatcher.kt |
| ChatOutbox | app OnymApplication.kt |
| ChatReceiptPayload | app inbox/IncomingMessageDispatcher.kt, ChatThreadViewModel.kt |
| ChatReceiptSending | app inbox/IncomingMessageDispatcher.kt, ChatThreadViewModel.kt |
| NoopChatReceiptSender | app inbox/IncomingMessageDispatcher.kt, ChatThreadViewModel.kt |
| ChatReceiptSender | app OnymApplication.kt |
| ChatVideoAttachment | app ChatBubble.kt, ChatThreadScreen.kt |
| ChatVideoEncoder | app OnymApplication.kt |
| ChatVideoLoader | app OnymApplication.kt, inbox/IncomingMessageDispatcher.kt, ChatThreadViewModel.kt |
| ChatVoiceAttachment | app ChatBubble.kt |
| ChatVoiceLoader | app OnymApplication.kt, ChatBubble.kt, ChatThreadViewModel.kt |
| ChatVoiceRecorder | app ChatInputPanel.kt, ChatThreadScreen.kt, ChatThreadViewModel.kt |
| MessageDao | app OnymApplication.kt calls MessageDatabase.messageDao(); public RoomMessageStore ctor param |
| MessageDatabase | app OnymApplication.kt (Room.databaseBuilder) |
| MessageDatabaseMigrations | app OnymApplication.kt (MIGRATION_1_2..6_7) |
| MessageDirection | app inbox/IncomingMessageDispatcher.kt + chats UI + app tests |
| MessageStatus | app inbox/IncomingMessageDispatcher.kt + chats UI + app tests |
| MessageRepository | app AppDependencies.kt, OnymApplication.kt, inbox dispatcher, search/SearchViewModel.kt, chats VMs; androidTest ChatsSwipeDeleteScreenTest.kt |
| MessageStore | public MessageRepository ctor param; implemented by testFixtures InMemoryMessageStore (fixtures compile against main as a separate component, so internal would not be visible) |
| PersistedMessage | appears in public MessageDao/RoomMessageStore surface (Room entity) — cannot be internal while MessageDao is public |
| ReadReceiptsPreferenceProvider | app AppDependencies.kt |
| DataStoreReadReceiptsPreferenceProvider | app OnymApplication.kt |
| RoomMessageStore | app OnymApplication.kt |
| SendMessageInteractor | app AppDependencies.kt, OnymApplication.kt, ChatThreadViewModel.kt |
| SendMessageError | app ChatThreadScreen.kt, ChatThreadViewModel.kt, test ChatThreadViewModelTest.kt |

Secret-material note: ChatImageCrypto (AES-GCM seal/open for media blobs) is
now internal. Attachment types (ChatImageAttachment etc.) carry `encKey`
bytes as public properties — they must remain public because the app UI
passes them to the loaders; no accessor was widened.

## Dependency trims (vs the list given)

- `:strings` — dropped: no R.string/resource use in any domain file.
- Media3 trimmed from 5 artifacts to 3: kept transformer/effect/common
  (ChatVideoEncoder bodies, `implementation`); dropped `media3-exoplayer` and
  `media3-ui` (playback-only, used solely by ChatThreadScreen in :app).
- Everything else from app/build.gradle.kts not needed here (no compose, no
  OkHttp, no BouncyCastle, no security-crypto, no onym-sdk, no CameraX/ZXing).

## api vs implementation

api: :chain (ChatMessage.groupType: SepGroupType), :identity, :transport,
:transport-blossom, :group (public ctor params), :foundation
(RoomMessageStore ctor + Base64ByteArraySerializer on public payloads),
coroutines-core (StateFlow/Flow/dispatcher params + properties),
serialization-json (public @Serializable types), room-runtime
(MessageDatabase extends RoomDatabase; Migration objects),
datastore-preferences (DataStore<Preferences> ctor param + public
Preferences.Key). implementation: coroutines-android (Dispatchers.Main in
ChatVideoEncoder), room-ktx, media3 x3.

## Open questions / integrator notes

1. ChatVideoEncoder is annotated `@OptIn(UnstableApi::class)`
   (androidx.media3.common.util.UnstableApi) while media3-common is
   `implementation`. Annotation references are not signature exposure, and
   :app keeps its own media3 deps for ChatThreadScreen, so this should be
   fine; if the lint/compiler complains at integration, bump media3-common
   to `api`.
2. `internal` on ChatMessageVariantSerializer / UuidStringSerializer relies
   on annotation arguments (`@Serializable(with = ...)`) not being exposure —
   this is standard Kotlin, but it is the one visibility change not
   verifiable without compiling (gradle forbidden during extraction).
3. StaticReadReceiptsPreferenceProvider appears to be dead code — consider
   deleting instead of keeping internal.
4. app/src/sharedTest/.../support/ lost its last chats fake; other agents own
   the remaining files there. The app source-set `srcDirs` entry for
   sharedTest is the integrator's to clean up.
5. ChatReceiptSender's companion exposes `fun ByteArray.toHexLowercase()`
   (member, not top-level; used only in-file). Left as-is to keep the diff
   move-only; could be private.
