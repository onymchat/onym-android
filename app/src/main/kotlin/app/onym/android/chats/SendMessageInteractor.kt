package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.group.GroupRepository
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Outgoing-message pipeline. Persist a `PENDING` row locally
 * (optimistic insert so the chat thread shows the bubble
 * immediately), seal one envelope per other group member, ship via
 * [InboxTransport.send], then flip the status to:
 *
 *  - [MessageStatus.SENT] if at least one relay accepted any
 *    envelope (or the roster is just the sender — a local-only
 *    "note to self" sends with no recipients).
 *  - [MessageStatus.FAILED] if every send threw or no relay
 *    accepted.
 *
 * Mirrors [app.onym.android.group.CreateGroupInteractor]'s invitation
 * send loop — same `sealInvitation` + `InboxTransport.send` per
 * recipient, same inbox-tag derivation. Splits the optimistic insert
 * out so the chat UI sees the bubble before the fan-out completes;
 * the status flip is the UI's signal that the network is done.
 *
 * ## Dependencies
 *
 * Takes [ActiveIdentityProvider] + [identitiesFlow] + [envelopeSealer]
 * instead of the concrete [IdentityRepository] so the interactor is
 * unit-testable without standing up the real keychain. Production
 * wiring in `OnymApplication` passes the `IdentityRepository` for
 * all three slots (it implements [ActiveIdentityProvider] and
 * [InvitationEnvelopeSealer] and exposes `identities` as a
 * `StateFlow<List<IdentitySummary>>`).
 *
 * Mirrors `SendMessageInteractor.swift` from onym-ios PR #150.
 */
class SendMessageInteractor(
    private val activeIdentity: ActiveIdentityProvider,
    private val identitiesFlow: StateFlow<List<IdentitySummary>>,
    private val envelopeSealer: InvitationEnvelopeSealer,
    private val groupRepository: GroupRepository,
    private val messageRepository: MessageRepository,
    private val inboxTransport: InboxTransport,
    private val blossomClient: BlossomClient,
    private val blossomServerUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> UUID = { UUID.randomUUID() },
    /** Injectable so JVM unit tests can supply a canned encoding without
     *  the Android `Bitmap` framework. Production uses [ChatImageEncoder]. */
    private val encodeImage: (ByteArray) -> ChatImageEncoder.Encoded? = ChatImageEncoder::encode,
    /** Transcodes + extracts a poster from a picked video. Injectable so
     *  tests supply a canned encoding instead of running Media3. Wired to
     *  the real encoder (which needs a `Context`) in `OnymApplication`;
     *  defaults to a no-op so construction sites that never send video
     *  stay unchanged. */
    private val encodeVideo: suspend (android.net.Uri) -> ChatVideoEncoder.Encoded? = { null },
) {

    /**
     * Run the full send pipeline. Returns the locally-persisted
     * [ChatMessage] after the fan-out completes — its
     * [ChatMessage.status] reflects the final outcome. Callers that
     * want the optimistic `PENDING` view subscribe to
     * [MessageRepository.snapshots] instead.
     */
    suspend fun send(
        groupId: String,
        body: String,
        replyToMessageId: UUID? = null,
    ): ChatMessage = withContext(ioDispatcher) {
        if (body.isEmpty()) throw SendMessageError.EmptyBody

        val activeIdentityId = activeIdentity.currentIdentityId.value
            ?: throw SendMessageError.NoIdentityLoaded
        val activeSummary = identitiesFlow.value.firstOrNull { it.id == activeIdentityId }
            ?: throw SendMessageError.NoIdentityLoaded
        val group = groupRepository.findForOwner(activeIdentityId.value, groupId)
            ?: throw SendMessageError.UnknownGroup

        val myBlsHex = activeSummary.blsPublicKey.toHexLowercase()
        if (group.memberProfiles[myBlsHex] == null) {
            throw SendMessageError.SenderNotAMember
        }

        val variant: ChatMessageVariant = when (group.groupType) {
            SepGroupType.TYRANNY -> ChatMessageVariant.Tyranny(body = body)
            SepGroupType.ANARCHY,
            SepGroupType.ONE_ON_ONE,
            SepGroupType.DEMOCRACY,
            SepGroupType.OLIGARCHY ->
                throw SendMessageError.UnsupportedGroupType(group.groupType)
        }

        val messageId = idFactory()
        val sentAtMillis = clock()
        val payload = ChatMessagePayload(
            version = 1,
            messageId = messageId,
            groupId = group.groupIdBytes,
            senderBlsPubkeyHex = myBlsHex,
            sentAtMillis = sentAtMillis,
            replyToMessageId = replyToMessageId,
            variant = variant,
        )

        // Optimistic insert — UI shows the bubble immediately.
        val pending = ChatMessage(
            id = messageId,
            groupId = groupId,
            ownerIdentityId = activeIdentityId.value,
            senderBlsPubkeyHex = myBlsHex,
            body = body,
            sentAtMillis = sentAtMillis,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            replyToMessageId = replyToMessageId,
            groupType = group.groupType,
        )
        messageRepository.append(pending)

        val recipients = recipientInboxKeysFor(group, myBlsHex)
        val finalStatus = try {
            val successCount = fanOut(payload, recipients)
            if (recipients.isEmpty() || successCount > 0) MessageStatus.SENT
            else MessageStatus.FAILED
        } catch (e: EncodingException) {
            messageRepository.updateStatus(messageId, activeIdentityId.value, MessageStatus.FAILED)
            throw SendMessageError.EncodingFailed(e.message ?: e.javaClass.simpleName)
        }
        messageRepository.updateStatus(messageId, activeIdentityId.value, finalStatus)

        pending.copy(status = finalStatus)
    }

    /**
     * Send an image message. Encodes + AES-GCM-encrypts the image,
     * uploads the ciphertext to Blossom, then ships a normal
     * [ChatMessagePayload] carrying a [ChatImageAttachment] (+ optional
     * caption). The optimistic bubble is inserted after a successful
     * upload but before the fan-out, so receivers never get an
     * attachment pointing at a missing blob; upload failure throws
     * before anything is persisted. Mirrors iOS `sendImage`.
     */
    suspend fun sendImage(
        groupId: String,
        imageData: ByteArray,
        caption: String = "",
    ): ChatMessage = withContext(ioDispatcher) {
        val activeIdentityId = activeIdentity.currentIdentityId.value
            ?: throw SendMessageError.NoIdentityLoaded
        val activeSummary = identitiesFlow.value.firstOrNull { it.id == activeIdentityId }
            ?: throw SendMessageError.NoIdentityLoaded
        val group = groupRepository.findForOwner(activeIdentityId.value, groupId)
            ?: throw SendMessageError.UnknownGroup
        val myBlsHex = activeSummary.blsPublicKey.toHexLowercase()
        if (group.memberProfiles[myBlsHex] == null) throw SendMessageError.SenderNotAMember
        val variant: ChatMessageVariant = when (group.groupType) {
            SepGroupType.TYRANNY -> ChatMessageVariant.Tyranny(body = caption)
            else -> throw SendMessageError.UnsupportedGroupType(group.groupType)
        }

        val encoded = encodeImage(imageData) ?: throw SendMessageError.ImageEncodeFailed
        val sealed = ChatImageCrypto.seal(encoded.jpeg)
        try {
            blossomClient.upload(sealed.blob, "image/jpeg")
        } catch (e: Exception) {
            throw SendMessageError.ImageUploadFailed(e.message ?: e.javaClass.simpleName)
        }
        val attachment = ChatImageAttachment(
            sha256 = sealed.sha256Hex,
            mimeType = "image/jpeg",
            byteSize = sealed.blob.size,
            width = encoded.width,
            height = encoded.height,
            encKey = sealed.key,
            blurhash = encoded.blurhash,
            server = blossomServerUrl,
        )

        val messageId = idFactory()
        val sentAtMillis = clock()
        val payload = ChatMessagePayload(
            version = 1,
            messageId = messageId,
            groupId = group.groupIdBytes,
            senderBlsPubkeyHex = myBlsHex,
            sentAtMillis = sentAtMillis,
            replyToMessageId = null,
            variant = variant,
            attachment = attachment,
        )
        val pending = ChatMessage(
            id = messageId,
            groupId = groupId,
            ownerIdentityId = activeIdentityId.value,
            senderBlsPubkeyHex = myBlsHex,
            body = caption,
            sentAtMillis = sentAtMillis,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            replyToMessageId = null,
            groupType = group.groupType,
            imageAttachment = attachment,
        )
        messageRepository.append(pending)

        val recipients = recipientInboxKeysFor(group, myBlsHex)
        val finalStatus = try {
            val successCount = fanOut(payload, recipients)
            if (recipients.isEmpty() || successCount > 0) MessageStatus.SENT else MessageStatus.FAILED
        } catch (_: EncodingException) {
            messageRepository.updateStatus(messageId, activeIdentityId.value, MessageStatus.FAILED)
            MessageStatus.FAILED
        }
        messageRepository.updateStatus(messageId, activeIdentityId.value, finalStatus)
        pending.copy(status = finalStatus)
    }

    /**
     * Send a video message. Transcodes to 720p + extracts a poster, then
     * encrypts + uploads *two* blobs — the poster (small) and the video
     * (large) — before shipping a [ChatMessagePayload] carrying a
     * [ChatVideoAttachment] (+ optional caption). Both uploads complete
     * before the optimistic bubble is inserted, so a receiver never gets
     * a descriptor pointing at a missing blob. Any encode / size / upload
     * failure throws before anything is persisted or fanned out.
     */
    suspend fun sendVideo(
        groupId: String,
        videoUri: android.net.Uri,
        caption: String = "",
    ): ChatMessage = withContext(ioDispatcher) {
        val activeIdentityId = activeIdentity.currentIdentityId.value
            ?: throw SendMessageError.NoIdentityLoaded
        val activeSummary = identitiesFlow.value.firstOrNull { it.id == activeIdentityId }
            ?: throw SendMessageError.NoIdentityLoaded
        val group = groupRepository.findForOwner(activeIdentityId.value, groupId)
            ?: throw SendMessageError.UnknownGroup
        val myBlsHex = activeSummary.blsPublicKey.toHexLowercase()
        if (group.memberProfiles[myBlsHex] == null) throw SendMessageError.SenderNotAMember
        val variant: ChatMessageVariant = when (group.groupType) {
            SepGroupType.TYRANNY -> ChatMessageVariant.Tyranny(body = caption)
            else -> throw SendMessageError.UnsupportedGroupType(group.groupType)
        }

        val encoded = encodeVideo(videoUri) ?: throw SendMessageError.VideoEncodeFailed
        val posterSealed = ChatImageCrypto.seal(encoded.poster.jpeg)
        val videoSealed = ChatImageCrypto.seal(encoded.mp4)
        if (videoSealed.blob.size > MAX_UPLOAD_BYTES) throw SendMessageError.VideoTooLarge
        try {
            blossomClient.upload(posterSealed.blob, "image/jpeg")
            blossomClient.upload(videoSealed.blob, "video/mp4")
        } catch (e: Exception) {
            throw SendMessageError.VideoUploadFailed(e.message ?: e.javaClass.simpleName)
        }

        val poster = ChatImageAttachment(
            sha256 = posterSealed.sha256Hex,
            mimeType = "image/jpeg",
            byteSize = posterSealed.blob.size,
            width = encoded.poster.width,
            height = encoded.poster.height,
            encKey = posterSealed.key,
            blurhash = encoded.poster.blurhash,
            server = blossomServerUrl,
        )
        val videoAttachment = ChatVideoAttachment(
            sha256 = videoSealed.sha256Hex,
            mimeType = "video/mp4",
            byteSize = videoSealed.blob.size,
            width = encoded.width,
            height = encoded.height,
            durationSeconds = encoded.durationSeconds,
            encKey = videoSealed.key,
            poster = poster,
            server = blossomServerUrl,
        )

        val messageId = idFactory()
        val sentAtMillis = clock()
        val payload = ChatMessagePayload(
            version = 1,
            messageId = messageId,
            groupId = group.groupIdBytes,
            senderBlsPubkeyHex = myBlsHex,
            sentAtMillis = sentAtMillis,
            replyToMessageId = null,
            variant = variant,
            videoAttachment = videoAttachment,
        )
        val pending = ChatMessage(
            id = messageId,
            groupId = groupId,
            ownerIdentityId = activeIdentityId.value,
            senderBlsPubkeyHex = myBlsHex,
            body = caption,
            sentAtMillis = sentAtMillis,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            replyToMessageId = null,
            groupType = group.groupType,
            videoAttachment = videoAttachment,
        )
        messageRepository.append(pending)

        val recipients = recipientInboxKeysFor(group, myBlsHex)
        val finalStatus = try {
            val successCount = fanOut(payload, recipients)
            if (recipients.isEmpty() || successCount > 0) MessageStatus.SENT else MessageStatus.FAILED
        } catch (_: EncodingException) {
            messageRepository.updateStatus(messageId, activeIdentityId.value, MessageStatus.FAILED)
            MessageStatus.FAILED
        }
        messageRepository.updateStatus(messageId, activeIdentityId.value, finalStatus)
        pending.copy(status = finalStatus)
    }

    /**
     * Retry a previously-failed outgoing message. No-op (silent) for
     * unknown / non-failed / non-outgoing rows — closes the
     * double-delivery hole where a tap on a row that already flipped
     * to SENT (status-flip race vs UI) would ship a second envelope.
     *
     * On a valid retry:
     *  1. Flip status to PENDING immediately so the UI glyph swaps
     *     to the in-flight clock before any network work.
     *  2. Re-fan-out using the **original** payload fields (same
     *     messageId + body + sentAtMillis) so receivers' dispatcher
     *     dedup against any prior delivery
     *     (`MessageRepository.append`'s id-keyed `IGNORE` strategy
     *     handles this on the receive side).
     *  3. Flip to SENT / FAILED on completion.
     *
     * Mirrors `SendMessageInteractor.retry(groupID:messageID:)` from
     * onym-ios PR #155.
     */
    suspend fun retry(groupId: String, messageId: java.util.UUID) = withContext(ioDispatcher) {
        // Resolve the active identity first so the message lookup is
        // scoped to the right owner (a wire id can be held by two
        // local identities).
        val activeIdentityId = activeIdentity.currentIdentityId.value ?: return@withContext
        val message = messageRepository.findById(messageId, activeIdentityId.value)
            ?: return@withContext
        if (message.direction != MessageDirection.OUTGOING) return@withContext
        if (message.status != MessageStatus.FAILED) return@withContext
        if (message.groupId != groupId) return@withContext

        val activeSummary = identitiesFlow.value.firstOrNull { it.id == activeIdentityId }
            ?: return@withContext
        val group = groupRepository.findForOwner(activeIdentityId.value, groupId)
            ?: return@withContext
        val myBlsHex = activeSummary.blsPublicKey.toHexLowercase()
        if (group.memberProfiles[myBlsHex] == null) return@withContext

        // Re-derive the variant from the message's stored group type.
        // Same governance gating as send — anything but Tyranny is
        // silently dropped today; retry follows the same posture.
        val variant: ChatMessageVariant = when (message.groupType) {
            SepGroupType.TYRANNY -> ChatMessageVariant.Tyranny(body = message.body)
            else -> return@withContext
        }

        // Flip to PENDING immediately so the glyph swaps to the
        // in-flight clock before the network round-trip.
        messageRepository.updateStatus(messageId, activeIdentityId.value, MessageStatus.PENDING)

        // Preserve the original messageId + sentAt + reply target so
        // receivers dedup against any prior delivery via their
        // dispatcher and the re-sent message still quotes the same
        // original.
        val payload = ChatMessagePayload(
            version = 1,
            messageId = messageId,
            groupId = group.groupIdBytes,
            senderBlsPubkeyHex = myBlsHex,
            sentAtMillis = message.sentAtMillis,
            replyToMessageId = message.replyToMessageId,
            variant = variant,
        )
        val recipients = recipientInboxKeysFor(group, myBlsHex)
        val finalStatus = try {
            val successCount = fanOut(payload, recipients)
            if (recipients.isEmpty() || successCount > 0) MessageStatus.SENT
            else MessageStatus.FAILED
        } catch (_: EncodingException) {
            MessageStatus.FAILED
        }
        messageRepository.updateStatus(messageId, activeIdentityId.value, finalStatus)
    }

    /**
     * Encode the payload once and seal+ship per recipient. Returns
     * the count of recipients whose envelope was accepted by at
     * least one relay. Best-effort per recipient — a thrown
     * seal/send for one peer is logged-by-silence and the loop
     * moves on, mirroring [send]'s pre-extraction behavior.
     *
     * Throws [EncodingException] if the payload itself can't be
     * encoded (a programmer error — `Data`-keyed fields can't fail
     * at JSON encode). Callers flip the message's status to FAILED
     * and surface the error appropriately.
     */
    private suspend fun fanOut(
        payload: ChatMessagePayload,
        recipients: List<ByteArray>,
    ): Int {
        val payloadBytes = try {
            jsonFormat.encodeToString(ChatMessagePayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
        } catch (e: Throwable) {
            throw EncodingException(e.message ?: e.javaClass.simpleName)
        }
        var successCount = 0
        for (inboxKey in recipients) {
            val sealed = try {
                envelopeSealer.sealInvitation(payloadBytes, inboxKey)
            } catch (_: Throwable) {
                continue
            }
            val tag = TransportInboxId(IdentityRepository.inboxTag(inboxKey))
            val receipt = try {
                inboxTransport.send(sealed, tag)
            } catch (_: Throwable) {
                continue
            }
            if (receipt.acceptedBy >= 1) successCount++
        }
        return successCount
    }

    private fun recipientInboxKeysFor(
        group: app.onym.android.group.ChatGroup,
        selfBlsHex: String,
    ): List<ByteArray> = group.memberProfiles
        .filterKeys { it != selfBlsHex }
        .map { (_, profile) -> profile.inboxPublicKey }

    private class EncodingException(message: String) : Exception(message)

    companion object {
        private val jsonFormat = Json { encodeDefaults = true }

        /** Hard ceiling on an encrypted blob we'll attempt to upload —
         *  sits under Blossom's ~100MB cap so a long clip fails fast
         *  client-side rather than with an opaque server rejection
         *  mid-upload. */
        const val MAX_UPLOAD_BYTES = 95 * 1024 * 1024

        private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}

/**
 * Failure modes for [SendMessageInteractor.send]. Sealed so the UI
 * can `when`-exhaust and surface a precise message for each kind.
 */
sealed class SendMessageError(message: String) : Exception(message) {

    object EmptyBody : SendMessageError("Message body must not be empty") {
        private fun readResolve(): Any = EmptyBody
    }

    object NoIdentityLoaded : SendMessageError("No identity is loaded") {
        private fun readResolve(): Any = NoIdentityLoaded
    }

    object UnknownGroup : SendMessageError("Group not found for the active identity") {
        private fun readResolve(): Any = UnknownGroup
    }

    /** Active identity isn't in the group's `memberProfiles`. Fires
     *  when the creator's own profile was never recorded (shouldn't
     *  happen post-PR A3) or when the active identity switched
     *  mid-flight. */
    object SenderNotAMember : SendMessageError("Active identity is not a member of this group") {
        private fun readResolve(): Any = SenderNotAMember
    }

    /** Today only the Tyranny variant ships; other governance types
     *  surface as this until their variant cases come online. */
    class UnsupportedGroupType(val type: SepGroupType) :
        SendMessageError("$type chat isn't supported yet")

    class EncodingFailed(reason: String) :
        SendMessageError("Couldn't encode chat-message payload: $reason")

    /** The picked image couldn't be decoded / re-encoded. */
    object ImageEncodeFailed : SendMessageError("Couldn't process the image") {
        private fun readResolve(): Any = ImageEncodeFailed
    }

    /** Encrypting or uploading the image blob to Blossom failed. */
    class ImageUploadFailed(reason: String) :
        SendMessageError("Couldn't upload the image: $reason")

    /** The picked video couldn't be transcoded / poster-extracted. */
    object VideoEncodeFailed : SendMessageError("Couldn't process the video") {
        private fun readResolve(): Any = VideoEncodeFailed
    }

    /** Encrypting or uploading a video (or its poster) blob failed. */
    class VideoUploadFailed(reason: String) :
        SendMessageError("Couldn't upload the video: $reason")

    /** The transcoded + encrypted video exceeds the upload cap. */
    object VideoTooLarge : SendMessageError("This video is too large to send") {
        private fun readResolve(): Any = VideoTooLarge
    }
}
