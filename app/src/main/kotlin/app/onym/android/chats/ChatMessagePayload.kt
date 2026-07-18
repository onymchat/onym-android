package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import java.util.Locale
import java.util.UUID

/**
 * Plaintext chat-message payload that gets sealed (X25519 + AES-GCM
 * via `IdentityRepository.sealInvitation`) and dropped on
 * `InboxTransport.send` for each group member — same envelope path
 * as `GroupInvitationPayload`. One envelope per recipient inbox
 * key; the encrypted bytes carry this struct verbatim.
 *
 * [variant] is governance-keyed because each group flavour signs and
 * routes messages differently down the road: Tyranny only needs the
 * body, but a 1-on-1 dialog will carry per-message ratchet state and
 * Anarchy will carry a per-member signature. Today only
 * [ChatMessageVariant.Tyranny] ships — adding a new case is the
 * entire surface-area cost of turning a new governance type on for
 * chat.
 *
 * Versioned for forward compat. `version = 1` is the only shape the
 * receiver has to handle today; later versions can add fields with
 * non-failing `decodeIfPresent`-style decoders.
 *
 * Mirrors `ChatMessagePayload.swift` from onym-ios PR #147.
 */
@Serializable
data class ChatMessagePayload(
    /** Wire schema version. Bump on a breaking field change (rename,
     *  removal, semantic shift). Adding optional fields does NOT
     *  require a bump. */
    val version: Int,
    /**
     * Stable per-message identifier used for receive-side dedup —
     * the same Nostr inbox event can be re-delivered, and a single
     * message fans out as N sealed envelopes (one per recipient),
     * so the inbox-event ID is not enough.
     *
     * Wire encoding is the uppercase 36-char canonical UUID string
     * (`"00000000-0000-0000-0000-000000000000"`), matching iOS
     * `JSONEncoder`'s default `UUID` encoding. Decoders accept any
     * case; encoders always emit uppercase.
     */
    @SerialName("message_id")
    @Serializable(with = UuidStringSerializer::class)
    val messageId: UUID,
    /** The group this message belongs to. Receiver looks up the
     *  [app.onym.android.group.ChatGroup] by this 32-byte id and
     *  rejects the message if no such group exists locally. */
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    /**
     * Lowercase BLS pubkey hex (96 chars) of the sender. Keying
     * matches [app.onym.android.group.ChatGroup.memberProfiles] so
     * the receiver can verify the sender is a known member with one
     * dictionary lookup. Sender identity has to live *inside* the
     * encrypted payload — the sealed envelope's ephemeral key tells
     * the receiver nothing about who sent it.
     */
    @SerialName("sender_bls_pubkey_hex")
    val senderBlsPubkeyHex: String,
    /** Milliseconds since Unix epoch at send time. `Long` (not a
     *  date type) so the wire format is unambiguous and
     *  cross-platform. */
    @SerialName("sent_at_millis")
    val sentAtMillis: Long,
    /**
     * The message this one replies to, if any. Optional + additive,
     * so it ships under `version = 1`: a sender that omits it decodes
     * to `null` on any receiver (the `= null` default kicks in for a
     * missing key), and an older receiver decoding a payload that
     * carries it just ignores the unknown key. Only the target's ID
     * travels — the receiver resolves the quoted sender + body from
     * its own local store at render time, so a dangling ref (target
     * never delivered) degrades to "message unavailable" rather than
     * carrying stale text.
     *
     * Wire key is snake_case `reply_to_message_id`; the value is the
     * uppercase canonical UUID string (same encoding as [messageId])
     * or `null`. Matches `ChatMessagePayload.replyToMessageID` from
     * onym-ios PR #173.
     */
    @SerialName("reply_to_message_id")
    @Serializable(with = UuidStringSerializer::class)
    val replyToMessageId: UUID? = null,
    val variant: ChatMessageVariant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatMessagePayload) return false
        return version == other.version &&
            messageId == other.messageId &&
            groupId.contentEquals(other.groupId) &&
            senderBlsPubkeyHex == other.senderBlsPubkeyHex &&
            sentAtMillis == other.sentAtMillis &&
            replyToMessageId == other.replyToMessageId &&
            variant == other.variant
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + messageId.hashCode()
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + senderBlsPubkeyHex.hashCode()
        h = 31 * h + sentAtMillis.hashCode()
        h = 31 * h + replyToMessageId.hashCode()
        h = 31 * h + variant.hashCode()
        return h
    }
}

/**
 * Governance-keyed body. The discriminator string on the wire is
 * [SepGroupType.wireValue] so a single spelling identifies the
 * flavour across iOS, Android, the relayer, and the Stellar
 * contracts.
 *
 * Today only [Tyranny] carries a real case. Unknown / unsupported
 * kinds throw on decode — the chat-receive dispatcher catches and
 * drops, which is what we want: a 1-on-1 message arriving at a v1
 * receiver should be ignored, not silently shoehorned into a Tyranny
 * shape.
 *
 * Wire shape is a flat object: `{"kind": "tyranny", "body": "..."}`
 * — not a nested kotlinx-polymorphic envelope. See
 * [ChatMessageVariantSerializer].
 */
@Serializable(with = ChatMessageVariantSerializer::class)
sealed interface ChatMessageVariant {
    /** Plaintext message body. Every variant ships a body string;
     *  future variants may carry additional fields alongside it. */
    val body: String

    data class Tyranny(override val body: String) : ChatMessageVariant
}

/**
 * Hand-written serializer for [ChatMessageVariant]. The wire shape
 * is the iOS twin's flat `{"kind": "<SepGroupType.wireValue>",
 * "body": "..."}` object — kotlinx's polymorphic sealed-class
 * machinery would either nest under a `type` envelope or silently
 * route an unknown kind through a fallback, neither of which match
 * the iOS behavior. We want unknown / unsupported kinds to *throw*
 * so the dispatcher can catch+drop explicitly.
 */
object ChatMessageVariantSerializer : KSerializer<ChatMessageVariant> {
    private const val KIND_KEY = "kind"
    private const val BODY_KEY = "body"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ChatMessageVariant") {
            element<String>(KIND_KEY)
            element<String>(BODY_KEY)
        }

    override fun serialize(encoder: Encoder, value: ChatMessageVariant) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is ChatMessageVariant.Tyranny -> {
                    encodeStringElement(descriptor, 0, SepGroupType.TYRANNY.wireValue)
                    encodeStringElement(descriptor, 1, value.body)
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): ChatMessageVariant {
        return decoder.decodeStructure(descriptor) {
            var kindRaw: String? = null
            var body: String? = null
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> kindRaw = decodeStringElement(descriptor, 0)
                    1 -> body = decodeStringElement(descriptor, 1)
                    else -> throw SerializationException("Unexpected element index $index")
                }
            }
            val raw = kindRaw
                ?: throw SerializationException("Missing required field '$KIND_KEY'")
            val kind = SepGroupType.fromWire(raw)
                ?: throw SerializationException("Unknown chat-message kind '$raw'")
            when (kind) {
                SepGroupType.TYRANNY -> {
                    val resolvedBody = body
                        ?: throw SerializationException("Missing required field '$BODY_KEY'")
                    ChatMessageVariant.Tyranny(body = resolvedBody)
                }
                SepGroupType.ONE_ON_ONE,
                SepGroupType.ANARCHY,
                SepGroupType.DEMOCRACY,
                SepGroupType.OLIGARCHY ->
                    throw SerializationException(
                        "Chat-message variant '$raw' is not yet supported",
                    )
            }
        }
    }
}

/**
 * Encodes [UUID] as the uppercase 36-char canonical string
 * (`"00000000-0000-0000-0000-000000000000"`). Matches iOS
 * `JSONEncoder`'s default `UUID` encoding (which calls
 * `UUID.uuidString`, an uppercase form). Decoders accept any case
 * via [UUID.fromString].
 */
object UuidStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UuidString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString().uppercase(Locale.ROOT))
    }

    override fun deserialize(decoder: Decoder): UUID =
        UUID.fromString(decoder.decodeString())
}
