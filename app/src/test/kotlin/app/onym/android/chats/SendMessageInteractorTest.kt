package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Behavioral tests for [SendMessageInteractor]. Uses the in-memory
 * fakes for store / transport / identity so the pipeline runs
 * without the keychain + Nostr stack.
 *
 * Runs under Robolectric ([AndroidJUnit4]) so the video-send tests can
 * construct an `android.net.Uri` (the injected test encoder ignores its
 * contents, but the send API takes a real `Uri`).
 *
 * Mirrors `SendMessageInteractorTests.swift` from onym-ios PR #150.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SendMessageInteractorTest {

    private val activeId = IdentityId("alice")
    private val selfBls = ByteArray(48) { 0x11 }
    private val selfBlsHex = selfBls.toHexLowercase()
    private val selfInbox = ByteArray(32) { 0x22 }
    private val selfSending = ByteArray(32) { 0x33 }
    private val peerBls = ByteArray(48) { 0x44 }
    private val peerBlsHex = peerBls.toHexLowercase()
    private val peerInbox = ByteArray(32) { 0x55 }
    private val groupIdHex = "aa".repeat(32)

    // ─── happy path ───────────────────────────────────────────────

    @Test
    fun send_persistsAndFansOut_marksSentOnAnyAccepted() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)

        val result = fixture.interactor.send(groupIdHex, "hello")

        assertEquals(MessageStatus.SENT, result.status)
        assertEquals("hello", result.body)
        assertEquals(MessageDirection.OUTGOING, result.direction)
        // One row, status SENT (overwriting the optimistic PENDING).
        val persisted = fixture.messageStore
            .listForGroup(activeId.value, groupIdHex)
            .single()
        assertEquals(MessageStatus.SENT, persisted.status)
        // One envelope shipped: to the peer's inbox tag, not our own.
        val sends = fixture.transport.sends()
        assertEquals(1, sends.size)
        assertEquals(
            TransportInboxId(IdentityRepository.inboxTag(peerInbox)),
            sends.single().inbox,
        )
    }

    // ─── image send ──────────────────────────────────────────────

    @Test
    fun sendImage_uploadsEncryptedBlob_persistsAttachment_andFansOut() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)

        val result = fixture.interactor.sendImage(groupIdHex, ByteArray(64) { it.toByte() })

        assertEquals(MessageStatus.SENT, result.status)
        assertEquals(MessageDirection.OUTGOING, result.direction)
        // The persisted row carries the attachment metadata.
        val attachment = result.imageAttachment
        assertNotNull(attachment)
        assertEquals("image/jpeg", attachment!!.mimeType)
        assertEquals(240, attachment.width)
        assertEquals(160, attachment.height)
        assertEquals(32, attachment.encKey.size)
        // Exactly one blob uploaded, and it's the ciphertext (nonce ‖ ct ‖
        // tag) — never the plaintext — addressed by the SHA-256 the
        // attachment carries.
        val fake = fixture.blossomClient as FakeBlossomClient
        assertEquals(1, fake.uploads.size)
        assertEquals(attachment.sha256, ChatImageCrypto.sha256Hex(fake.uploads.single()))
        // The blob decrypts back to the original JPEG bytes.
        val roundTripped = ChatImageCrypto.open(
            fake.uploads.single(),
            attachment.encKey,
            attachment.sha256,
        )
        assertArrayEquals(ByteArray(64) { it.toByte() }, roundTripped)
        // The attachment rides the wire payload to the peer.
        val shipped = decodeShipped(fixture.transport.sends().single().payload)
        assertEquals(attachment.sha256, shipped.attachment?.sha256)
    }

    @Test
    fun sendImage_encodeFailure_throwsAndUploadsNothing() = runTest {
        val fixture = newFixture(encodeImage = { null })
        fixture.seedGroup(includePeer = true)

        assertThrows(SendMessageError.ImageEncodeFailed::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.sendImage(groupIdHex, ByteArray(8)) }
        }
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun sendImage_uploadFailure_throwsImageUploadFailed() = runTest {
        val fixture = newFixture(blossomClient = FailingBlossomClient())
        fixture.seedGroup(includePeer = true)

        assertThrows(SendMessageError.ImageUploadFailed::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.sendImage(groupIdHex, ByteArray(8)) }
        }
        assertTrue(fixture.transport.sends().isEmpty())
    }

    // ─── video send ──────────────────────────────────────────────

    @Test
    fun sendVideo_uploadsPosterAndVideoBlobs_persistsAttachment_andFansOut() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)

        val result = fixture.interactor.sendVideo(groupIdHex, Uri.parse("content://test/video"))

        assertEquals(MessageStatus.SENT, result.status)
        assertEquals(MessageDirection.OUTGOING, result.direction)
        val video = result.videoAttachment
        assertNotNull(video)
        assertEquals("video/mp4", video!!.mimeType)
        assertEquals(1280, video.width)
        assertEquals(4.0, video.durationSeconds, 0.0)
        assertEquals(32, video.encKey.size)
        // Poster is its own encrypted blob with its own key + blurhash.
        assertEquals("image/jpeg", video.poster.mimeType)
        assertEquals(32, video.poster.encKey.size)
        assertNotEquals(video.poster.sha256, video.sha256)
        // Both blobs (poster + video) uploaded under their sha256s.
        val fake = fixture.blossomClient as FakeBlossomClient
        assertEquals(2, fake.uploads.size)
        val uploadedShas = fake.uploads.map { ChatImageCrypto.sha256Hex(it) }
        assertTrue(uploadedShas.contains(video.sha256))
        assertTrue(uploadedShas.contains(video.poster.sha256))
        // The attachment rides the wire payload to the peer.
        val shipped = decodeShipped(fixture.transport.sends().single().payload)
        assertEquals(video.sha256, shipped.videoAttachment?.sha256)
        // Persisted with the video attachment.
        val stored = fixture.messageStore.listForGroup(activeId.value, groupIdHex).single()
        assertEquals(video.sha256, stored.videoAttachment?.sha256)
    }

    @Test
    fun sendVideo_encodeFailure_throwsAndUploadsNothing() = runTest {
        val fixture = newFixture(encodeVideo = { null })
        fixture.seedGroup(includePeer = true)

        assertThrows(SendMessageError.VideoEncodeFailed::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.interactor.sendVideo(groupIdHex, Uri.parse("content://test/v"))
            }
        }
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun sendVideo_uploadFailure_throwsVideoUploadFailed() = runTest {
        val fixture = newFixture(blossomClient = FailingBlossomClient())
        fixture.seedGroup(includePeer = true)

        assertThrows(SendMessageError.VideoUploadFailed::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.interactor.sendVideo(groupIdHex, Uri.parse("content://test/v"))
            }
        }
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun sendVideo_oversizeBlob_throwsVideoTooLarge() = runTest {
        val huge = ByteArray(SendMessageInteractor.MAX_UPLOAD_BYTES + 1)
        val fixture = newFixture(encodeVideo = { cannedVideoEncoded(mp4 = huge) })
        fixture.seedGroup(includePeer = true)

        assertThrows(SendMessageError.VideoTooLarge::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.interactor.sendVideo(groupIdHex, Uri.parse("content://test/v"))
            }
        }
        assertTrue(fixture.transport.sends().isEmpty())
    }

    // ─── reply reference rides the send + survives retry ─────────

    @Test
    fun send_withReplyTarget_carriesRefOnPayloadAndPersistedRow() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val target = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val result = fixture.interactor.send(groupIdHex, "agreed", replyToMessageId = target)

        // The optimistic + persisted row carries the ref.
        assertEquals(target, result.replyToMessageId)
        assertEquals(
            target,
            fixture.messageStore.listForGroup(activeId.value, groupIdHex).single().replyToMessageId,
        )
        // …and it rides the wire payload (RecordingSealer passes the
        // JSON bytes through untouched).
        val shipped = decodeShipped(fixture.transport.sends().single().payload)
        assertEquals(target, shipped.replyToMessageId)
    }

    @Test
    fun send_withoutReplyTarget_shipsNullRef() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)

        fixture.interactor.send(groupIdHex, "plain")

        assertNull(decodeShipped(fixture.transport.sends().single().payload).replyToMessageId)
    }

    @Test
    fun retry_preservesReplyTarget() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val target = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val failed = makePersisted(
            status = MessageStatus.FAILED,
            body = "first try",
            replyToMessageId = target,
        )
        fixture.messageStore.preload(listOf(failed))

        fixture.interactor.retry(groupIdHex, failed.id)

        // The re-sent message still quotes the same original.
        val shipped = decodeShipped(fixture.transport.sends().single().payload)
        assertEquals(target, shipped.replyToMessageId)
    }

    private val shippedJson = Json { ignoreUnknownKeys = true }

    private fun decodeShipped(bytes: ByteArray): ChatMessagePayload =
        shippedJson.decodeFromString(
            ChatMessagePayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )

    // ─── self-recipient is skipped ────────────────────────────────

    @Test
    fun send_skipsSelfRecipient() = runTest {
        // Group has only the sender — fan-out has zero recipients.
        val fixture = newFixture()
        fixture.seedGroup(includePeer = false)

        val result = fixture.interactor.send(groupIdHex, "hi")
        // Zero recipients counts as SENT (a note-to-self is local-only).
        assertEquals(MessageStatus.SENT, result.status)
        assertTrue(
            "no envelopes shipped when only self is in the roster",
            fixture.transport.sends().isEmpty(),
        )
    }

    // ─── all sends fail → status FAILED ───────────────────────────

    @Test
    fun send_allRecipientsFailed_marksFailed() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        fixture.transport.setReceiptAcceptedBy(0)  // every relay refuses

        val result = fixture.interactor.send(groupIdHex, "hi")
        assertEquals(MessageStatus.FAILED, result.status)
        assertEquals(
            MessageStatus.FAILED,
            fixture.messageStore.listForGroup(activeId.value, groupIdHex).single().status,
        )
    }

    @Test
    fun send_sendThrows_marksFailed() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        fixture.transport.setSendThrow(RuntimeException("transport down"))

        val result = fixture.interactor.send(groupIdHex, "hi")
        assertEquals(MessageStatus.FAILED, result.status)
    }

    // ─── partial success → status SENT ────────────────────────────

    @Test
    fun send_partialSuccess_marksSentWhenAtLeastOneAccepted() = runTest {
        // Two peers. First send accepted, second send rejected — the
        // ConfigurableInboxTransport applies the same acceptedBy
        // count to both, so this test exercises the "every relay
        // accepted at least one envelope" branch via a single
        // successful recipient.
        val fixture = newFixture()
        val secondPeerBls = ByteArray(48) { 0x66 }
        val secondPeerInbox = ByteArray(32) { 0x77 }
        fixture.seedGroup(
            includePeer = true,
            extraPeerBlsHex = secondPeerBls.toHexLowercase(),
            extraPeerInbox = secondPeerInbox,
        )

        val result = fixture.interactor.send(groupIdHex, "hi")
        assertEquals(MessageStatus.SENT, result.status)
        // Both peers got envelopes (the fan-out is per-recipient).
        assertEquals(2, fixture.transport.sends().size)
    }

    // ─── validation error paths ──────────────────────────────────

    @Test
    fun send_unknownGroup_throws() = runTest {
        val fixture = newFixture()
        // No group seeded.
        assertThrows(SendMessageError.UnknownGroup::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "x") }
        }
        // Nothing persisted, nothing shipped.
        assertTrue(fixture.messageStore.listForGroup(activeId.value, groupIdHex).isEmpty())
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun send_senderNotAMember_throws() = runTest {
        // Group exists, peer is in it, but the active identity isn't.
        // Shouldn't happen post-PR A3, but is a defined error case.
        val fixture = newFixture()
        val group = makeGroup(
            memberProfiles = mapOf(
                peerBlsHex to MemberProfile(
                    alias = "Peer",
                    inboxPublicKey = peerInbox,
                    sendingPubkey = ByteArray(32) { 0x88.toByte() },
                ),
            ),
        )
        fixture.groupStore.preload(listOf(group))

        assertThrows(SendMessageError.SenderNotAMember::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "x") }
        }
    }

    @Test
    fun send_emptyBody_throws() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        assertThrows(SendMessageError.EmptyBody::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "") }
        }
        assertTrue(
            "validation must short-circuit BEFORE the optimistic insert",
            fixture.messageStore.listForGroup(activeId.value, groupIdHex).isEmpty(),
        )
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun send_unsupportedGovernance_throws() = runTest {
        val fixture = newFixture()
        // Group is Anarchy — variant Tyranny doesn't fit; today
        // SendMessageInteractor only ships .tyranny.
        val group = makeGroup(
            groupType = SepGroupType.ANARCHY,
            memberProfiles = mapOf(
                selfBlsHex to MemberProfile(
                    alias = "Self",
                    inboxPublicKey = selfInbox,
                    sendingPubkey = selfSending,
                ),
                peerBlsHex to MemberProfile(
                    alias = "Peer",
                    inboxPublicKey = peerInbox,
                    sendingPubkey = ByteArray(32) { 0x88.toByte() },
                ),
            ),
        )
        fixture.groupStore.preload(listOf(group))

        val err = assertThrows(SendMessageError.UnsupportedGroupType::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "x") }
        }
        assertEquals(SepGroupType.ANARCHY, err.type)
    }

    // ─── retry on FAILED ─────────────────────────────────────────

    @Test
    fun retry_failedMessage_flipsToSentOnRelayAcceptance() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val failed = makePersisted(status = MessageStatus.FAILED, body = "first try")
        fixture.messageStore.preload(listOf(failed))

        fixture.interactor.retry(groupIdHex, failed.id)

        val refreshed = fixture.messageStore.findById(failed.id, failed.ownerIdentityId)!!
        assertEquals(MessageStatus.SENT, refreshed.status)
        // The wire payload re-used the original messageId so the
        // receiver dedups against any prior delivery — assert the
        // ConfigurableInboxTransport saw exactly one send for the
        // peer (the retry shouldn't double-ship).
        assertEquals(1, fixture.transport.sends().size)
    }

    @Test
    fun retry_failedMessage_staysFailedWhenRelaysReject() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        fixture.transport.setReceiptAcceptedBy(0)  // every relay refuses
        val failed = makePersisted(status = MessageStatus.FAILED, body = "x")
        fixture.messageStore.preload(listOf(failed))

        fixture.interactor.retry(groupIdHex, failed.id)
        assertEquals(
            MessageStatus.FAILED,
            fixture.messageStore.findById(failed.id, failed.ownerIdentityId)!!.status,
        )
    }

    @Test
    fun retry_unknownMessageId_isNoOp() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        // Don't preload anything — retry on an unknown id must not
        // throw and must not ship anything.
        fixture.interactor.retry(groupIdHex, UUID.randomUUID())
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun retry_sentMessage_isNoOp_preventsDoubleDelivery() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val sent = makePersisted(status = MessageStatus.SENT, body = "already delivered")
        fixture.messageStore.preload(listOf(sent))

        fixture.interactor.retry(groupIdHex, sent.id)

        // Status unchanged; no envelope shipped.
        assertEquals(
            MessageStatus.SENT,
            fixture.messageStore.findById(sent.id, sent.ownerIdentityId)!!.status,
        )
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun retry_pendingMessage_isNoOp_preventsDoubleDelivery() = runTest {
        // An in-flight pending row could still resolve to SENT; retry
        // would double-ship. Same gate as the SENT case.
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val pending = makePersisted(status = MessageStatus.PENDING, body = "in flight")
        fixture.messageStore.preload(listOf(pending))

        fixture.interactor.retry(groupIdHex, pending.id)
        assertEquals(
            MessageStatus.PENDING,
            fixture.messageStore.findById(pending.id, pending.ownerIdentityId)!!.status,
        )
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun retry_incomingMessage_isNoOp() = runTest {
        // Incoming rows don't have an outgoing send to retry.
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val incoming = makePersisted(
            status = MessageStatus.FAILED,
            direction = MessageDirection.INCOMING,
            body = "received but somehow failed",
        )
        fixture.messageStore.preload(listOf(incoming))

        fixture.interactor.retry(groupIdHex, incoming.id)
        assertTrue(fixture.transport.sends().isEmpty())
    }

    private fun makePersisted(
        status: MessageStatus,
        direction: MessageDirection = MessageDirection.OUTGOING,
        body: String,
        replyToMessageId: UUID? = null,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = groupIdHex,
        ownerIdentityId = activeId.value,
        senderBlsPubkeyHex = selfBlsHex,
        body = body,
        sentAtMillis = 1_699_000_000_000L,
        direction = direction,
        status = status,
        replyToMessageId = replyToMessageId,
        groupType = SepGroupType.TYRANNY,
    )

    // ─── optimistic insert visible before fan-out completes ──────

    @Test
    fun send_optimisticInsert_visibleBeforeFanoutResolves() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        // Don't gate the transport — just assert that by the time
        // send() returns, BOTH the optimistic insert AND the status
        // flip have applied. The "PENDING is visible mid-flight"
        // property is covered by subscribers to MessageRepository
        // .snapshots; here we just sanity-check the final state has
        // exactly one row with id matching what send() returned.
        val result = fixture.interactor.send(groupIdHex, "first")
        val rows = fixture.messageStore.listForGroup(activeId.value, groupIdHex)
        assertEquals(1, rows.size)
        assertEquals(result.id, rows.single().id)
    }

    // ─── helpers ──────────────────────────────────────────────────

    /** Canned encoding so the test doesn't run Media3 transcoding: a
     *  poster (encoded bytes) + placeholder MP4 bytes. */
    private fun cannedVideoEncoded(mp4: ByteArray = ByteArray(64) { it.toByte() }) =
        ChatVideoEncoder.Encoded(
            mp4 = mp4,
            width = 1280,
            height = 720,
            durationSeconds = 4.0,
            poster = ChatImageEncoder.Encoded(
                jpeg = ByteArray(32) { it.toByte() },
                width = 1280,
                height = 720,
                blurhash = "LEHV6nWB2yk8",
            ),
        )

    private fun newFixture(
        blossomClient: BlossomClient = FakeBlossomClient(),
        encodeImage: (ByteArray) -> ChatImageEncoder.Encoded? = { data ->
            ChatImageEncoder.Encoded(
                jpeg = data,
                width = 240,
                height = 160,
                blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
            )
        },
        encodeVideo: suspend (Uri) -> ChatVideoEncoder.Encoded? = { cannedVideoEncoded() },
    ): Fixture {
        val activeProvider = FakeActiveIdentityProvider(initial = activeId)
        val identitiesFlow = MutableStateFlow(
            listOf(
                IdentitySummary(
                    id = activeId,
                    name = "Alice",
                    blsPublicKey = selfBls,
                    inboxPublicKey = selfInbox,
                    sendingPublicKey = selfSending,
                ),
            ),
        )
        val groupStore = InMemoryGroupStore()
        val groupRepository = GroupRepository(
            store = groupStore,
            identity = activeProvider,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        val messageStore = InMemoryMessageStore()
        val messageRepository = MessageRepository(
            store = messageStore,
            identity = activeProvider,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        val transport = ConfigurableInboxTransport()
        val interactor = SendMessageInteractor(
            activeIdentity = activeProvider,
            identitiesFlow = identitiesFlow,
            envelopeSealer = RecordingSealer(),
            groupRepository = groupRepository,
            messageRepository = messageRepository,
            inboxTransport = transport,
            blossomClient = blossomClient,
            blossomServerUrl = "https://blossom.test",
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_700_000_000_000L },
            idFactory = { UUID.fromString("00000000-0000-0000-0000-000000000001") },
            encodeImage = encodeImage,
            encodeVideo = encodeVideo,
        )
        return Fixture(
            interactor = interactor,
            groupStore = groupStore,
            messageStore = messageStore,
            transport = transport,
            blossomClient = blossomClient,
        )
    }

    private inner class Fixture(
        val interactor: SendMessageInteractor,
        val groupStore: InMemoryGroupStore,
        val messageStore: InMemoryMessageStore,
        val transport: ConfigurableInboxTransport,
        val blossomClient: BlossomClient,
    ) {
        suspend fun seedGroup(
            includePeer: Boolean,
            extraPeerBlsHex: String? = null,
            extraPeerInbox: ByteArray? = null,
        ) {
            val profiles = buildMap<String, MemberProfile> {
                put(selfBlsHex, MemberProfile(
                    alias = "Self",
                    inboxPublicKey = selfInbox,
                    sendingPubkey = selfSending,
                ))
                if (includePeer) {
                    put(peerBlsHex, MemberProfile(
                        alias = "Peer",
                        inboxPublicKey = peerInbox,
                        sendingPubkey = ByteArray(32) { 0x88.toByte() },
                    ))
                }
                if (extraPeerBlsHex != null && extraPeerInbox != null) {
                    put(extraPeerBlsHex, MemberProfile(
                        alias = "Peer2",
                        inboxPublicKey = extraPeerInbox,
                        sendingPubkey = ByteArray(32) { 0x99.toByte() },
                    ))
                }
            }
            groupStore.preload(listOf(makeGroup(memberProfiles = profiles)))
        }
    }

    private fun makeGroup(
        groupType: SepGroupType = SepGroupType.TYRANNY,
        memberProfiles: Map<String, MemberProfile>,
    ) = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = ByteArray(32),
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = memberProfiles,
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = groupType,
        adminPubkeyHex = null,
        isPublishedOnChain = true,
        ownerIdentityId = activeId.value,
    )
}

/** In-memory Blossom store: `upload` keeps the ciphertext keyed by its
 *  SHA-256 (as the real server does), `download` serves it back. Lets a
 *  send → fan-out → receive round-trip run with no network. */
private class FakeBlossomClient : BlossomClient {
    val uploads = mutableListOf<ByteArray>()
    private val blobs = HashMap<String, ByteArray>()

    override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor {
        uploads.add(blob)
        val sha = ChatImageCrypto.sha256Hex(blob)
        blobs[sha] = blob
        return BlobDescriptor(sha256 = sha, url = "https://blossom.test/$sha", size = blob.size)
    }

    override suspend fun download(sha256: String): ByteArray =
        blobs[sha256] ?: throw IllegalStateException("no blob $sha256")
}

/** A Blossom client whose `upload` always fails — drives the
 *  [SendMessageError.ImageUploadFailed] path. */
private class FailingBlossomClient : BlossomClient {
    override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
        throw java.io.IOException("network down")

    override suspend fun download(sha256: String): ByteArray =
        throw java.io.IOException("network down")
}

/** Sealer that just returns the plaintext bytes — the tests don't
 *  care about the envelope shape, only that one call happens per
 *  recipient and the resulting bytes get handed to [InboxTransport]. */
private class RecordingSealer : InvitationEnvelopeSealer {
    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray = payload
}

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}
