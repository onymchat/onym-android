package app.onym.android.group

import app.onym.android.foundation.Base64ByteArraySerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * View-facing directory entry for one peer the local user has
 * interacted with through a group. Carries what the UI needs to
 * render "X joined" / "you are talking to Y" without crossing into
 * secret material. Stored on [ChatGroup.memberProfiles] keyed by
 * the peer's lowercase BLS pubkey hex.
 *
 * Distinct from [GovernanceMember]: that's the on-chain Merkle-tree
 * roster (V1: creator only, static). [MemberProfile] covers the
 * app-level "who's in this conversation" set, which V1 grows as
 * joiners are admitted even though the on-chain roster doesn't
 * change.
 *
 * Trust: [alias] is self-asserted by its owner — never load-bearing.
 * Surfaces should always offer the member's BLS-pubkey fingerprint
 * alongside (matches the inviter-approval pattern documented on
 * [JoinRequestPayload]).
 *
 * [inboxPublicKey] is the 32-byte X25519 raw pub. Persisted so the
 * admin (or any authorized fanout sender, in future governance
 * models) can reach every member's inbox to announce roster changes
 * without re-deriving from the join request each time.
 *
 * Mirrors `MemberProfile.swift` from onym-ios.
 */
@Serializable(with = MemberProfileSerializer::class)
data class MemberProfile(
    val alias: String,
    @SerialName("inbox_public_key")
    @Serializable(with = Base64ByteArraySerializer::class)
    val inboxPublicKey: ByteArray,
    /**
     * 32-byte Ed25519 envelope-signing pubkey. Matches
     * [app.onym.android.identity.Identity.stellarPublicKey] — the
     * key the receiver verifies sealed envelopes against. Plumbed
     * end-to-end through the join + invite flows (PR A3) so PR A4's
     * chat dispatcher can match a chat envelope's signature against
     * the claimed [senderBlsPubkeyHex] member with one direct
     * equality check, closing the insider-spoofing gap a malicious
     * group member could otherwise exploit.
     */
    @SerialName("sending_pubkey")
    @Serializable(with = Base64ByteArraySerializer::class)
    val sendingPubkey: ByteArray,
    /**
     * 32-byte `SHA256` of the rules this member agreed to when they
     * asked to join, and their 64-byte Ed25519 signature over
     * [GroupRules.statement]. Both null for a member who joined before
     * the group had rules, or from a build that predates them.
     *
     * Kept on the member rather than on the request, because the request
     * is consumed at approval and the question ("did they agree?")
     * outlives it by the whole life of the membership.
     *
     * Announced alongside the rest of the profile, so any member can
     * check any other member's agreement against the [sendingPubkey]
     * they already hold — the founder who admitted them is not a
     * required witness.
     */
    @SerialName("rules_hash")
    @Serializable(with = Base64ByteArraySerializer::class)
    val rulesHash: ByteArray? = null,
    @SerialName("rules_signature")
    @Serializable(with = Base64ByteArraySerializer::class)
    val rulesSignature: ByteArray? = null,
    /**
     * The rules text those bytes cover, as this device held it when the
     * member was admitted.
     *
     * Retained rather than pointed at. [GroupRules]' own doc is the
     * argument: a signature is evidence only if the bytes it covers can
     * be produced again, and a hash beside a *live*
     * [ChatGroup.invitationMessage] proves that something was agreed and
     * never what — one edit by the founder and every retained agreement
     * becomes unattributable to any text on the device.
     *
     * It is the admitting device's own copy, never the joiner's: the
     * request deliberately doesn't carry the text, because a joiner who
     * supplied it would be choosing what their own signature is checked
     * against.
     *
     * Carried per member rather than once per group, because members
     * are admitted under whatever the rules said *that day*: two people
     * in the same roster can have agreed to different wordings, and a
     * single hoisted copy could only be one of them. The duplication is
     * bounded by the same cap the link is, and only rows with an
     * agreement carry it at all.
     *
     * Null when the admitting device didn't hold the text
     * [rulesHash] names — [rulesHash] and [rulesSignature] survive
     * alone there, since they still separate a joiner who signed
     * something uncheckable from one who signed nothing. What cannot
     * happen is text that isn't the text the hash names; `init` refuses
     * it.
     */
    @SerialName("rules_text")
    val rulesText: String? = null,
) {
    init {
        require(inboxPublicKey.size == 32) {
            "inboxPublicKey: expected 32 bytes, got ${inboxPublicKey.size}"
        }
        require(sendingPubkey.size == 32) {
            "sendingPubkey: expected 32 bytes, got ${sendingPubkey.size}"
        }
        // Same shape the wire enforces on [JoinRequestPayload], enforced
        // here too: profiles arrive inside peer-announced snapshots, and
        // a malformed pair should fail at the boundary rather than at
        // verify time, where "wrong size" would read as "didn't agree".
        require((rulesHash == null) == (rulesSignature == null)) {
            "rulesHash and rulesSignature must be absent or present together"
        }
        require(rulesHash == null || rulesHash.size == 32) {
            "rulesHash: expected 32 bytes, got ${rulesHash?.size}"
        }
        require(rulesSignature == null || rulesSignature.size == 64) {
            "rulesSignature: expected 64 bytes, got ${rulesSignature?.size}"
        }
        // Capped like the link that carried it, and measured as
        // *received* rather than as canonicalized: `fits` trims before
        // measuring and `hash` trims before hashing, so a peer could
        // otherwise send megabytes of leading whitespace in front of the
        // real rules and have both checks pass while the raw string was
        // persisted. Requiring the canonical form closes the same door
        // from the other side.
        require(
            rulesText == null ||
                rulesText.toByteArray(Charsets.UTF_8).size <= GroupRules.MAX_BYTES,
        ) {
            "rulesText: expected at most ${GroupRules.MAX_BYTES} UTF-8 bytes as sent"
        }
        require(rulesText == null || rulesText == GroupRules.canonical(rulesText)) {
            "rulesText: expected canonical (ends-trimmed) text"
        }
        // The text has to be the text the hash names. Storing one
        // beside a hash of something else would have every later reader
        // verifying a signature against words it was never made over —
        // and `agreedToRules` would answer a question nobody asked.
        require(
            rulesText == null ||
                (rulesHash != null && GroupRules.hash(rulesText).contentEquals(rulesHash)),
        ) {
            "rulesText must be the text rulesHash names"
        }
    }

    /**
     * Whether this member's stored signature verifies against the text
     * stored beside it, for [groupId].
     *
     * The whole question in one place, and the only place that answers
     * it: the retained text is what the signature covers, so anything
     * reaching for the group's current rules instead would report a
     * founder's later edit as a member who never agreed.
     */
    fun agreedToRules(groupId: ByteArray): Boolean =
        storedAgreement(groupId) == StoredAgreement.VERIFIED

    /**
     * What this member's retained evidence actually shows.
     *
     * Three outcomes rather than a boolean, for the same reason
     * [JoinRequestApprover.RulesAgreement] has five: "didn't agree" and
     * "agreed to something this device can't check" are different facts
     * about a person, and collapsing them here would throw away exactly
     * what the retained bytes were kept for.
     */
    fun storedAgreement(groupId: ByteArray): StoredAgreement {
        val signature = rulesSignature ?: return StoredAgreement.NONE
        val text = rulesText ?: return StoredAgreement.UNCHECKABLE
        return if (
            GroupRules.isAgreement(
                signature = signature,
                rules = text,
                groupId = groupId,
                joinerSendingPublicKey = sendingPubkey,
            )
        ) {
            StoredAgreement.VERIFIED
        } else {
            StoredAgreement.NOT_VERIFIED
        }
    }

    /** See [storedAgreement]. */
    enum class StoredAgreement {
        /** No agreement was recorded: an older build, or a group that
         *  had no rules when this member was admitted. */
        NONE,

        /** Bytes were recorded, and the text they cover isn't held by
         *  this device — signed something, and never what. */
        UNCHECKABLE,

        /** The signature verifies against the retained text. */
        VERIFIED,

        /** The retained text is the text the hash names, and the
         *  signature over it doesn't verify. */
        NOT_VERIFIED,
    }

    companion object {
        /**
         * [text] if it can be stored beside [hash], else null — the one
         * predicate every boundary has to apply.
         *
         * Shared rather than restated because the two places that
         * decode a peer's profile differ in how much a rejection costs:
         * the invitation carries a whole roster and the announcement
         * carries one member, but in both cases `init` refuses a text
         * that isn't canonical, is over the cap as sent, or isn't the
         * text the hash names — and a caller that checked only the hash
         * would throw from inside a `collect` and take an identity's
         * inbox subscription down with it.
         */
        fun storableRulesText(text: String?, hash: ByteArray?): String? = text?.takeIf {
            hash != null &&
                it.toByteArray(Charsets.UTF_8).size <= GroupRules.MAX_BYTES &&
                it == GroupRules.canonical(it) &&
                GroupRules.hash(it).contentEquals(hash)
        }
    }

    override fun equals(other: Any?): Boolean = this === other ||
        (other is MemberProfile &&
            alias == other.alias &&
            inboxPublicKey.contentEquals(other.inboxPublicKey) &&
            sendingPubkey.contentEquals(other.sendingPubkey) &&
            // A profile that gained an agreement is not the profile
            // without it: left out, the `snapshots` flow would treat the
            // two as one value and never re-emit the group whose only
            // change is the evidence a founder decided on.
            (rulesHash?.contentEquals(other.rulesHash) ?: (other.rulesHash == null)) &&
            (rulesSignature?.contentEquals(other.rulesSignature)
                ?: (other.rulesSignature == null)) &&
            rulesText == other.rulesText)

    override fun hashCode(): Int {
        var h = alias.hashCode()
        h = 31 * h + inboxPublicKey.contentHashCode()
        h = 31 * h + sendingPubkey.contentHashCode()
        h = 31 * h + (rulesHash?.contentHashCode() ?: 0)
        h = 31 * h + (rulesSignature?.contentHashCode() ?: 0)
        h = 31 * h + (rulesText?.hashCode() ?: 0)
        return h
    }
}


/**
 * Decodes a peer's profile without letting one bad field cost the whole
 * message.
 *
 * `MemberProfile` arrives inside `GroupInvitationPayload.memberProfiles`
 * — the snapshot a joiner materializes their group from — and the
 * dispatcher turns any decode failure into a dropped message. So a
 * single roster entry whose agreement bytes are wrong-sized, half
 * present, over-cap, or not the text their hash names would otherwise
 * take the entire invitation down with it, and the joiner would never
 * see the group at all.
 *
 * Agreement bytes that don't hold up are therefore dropped to null,
 * which is what "we can't show they agreed" already means. What is
 * *not* relaxed is the constructor: local writes still go through the
 * strict `init`, so nothing this device stores can hold a text beside a
 * hash it doesn't name. Same posture as `MemberAnnouncementPayload`,
 * and as `MemberProfile.init(from:)` on iOS.
 */
internal object MemberProfileSerializer : KSerializer<MemberProfile> {

    @Serializable
    @SerialName("MemberProfile")
    private class Surrogate(
        val alias: String,
        @SerialName("inbox_public_key")
        @Serializable(with = Base64ByteArraySerializer::class)
        val inboxPublicKey: ByteArray,
        @SerialName("sending_pubkey")
        @Serializable(with = Base64ByteArraySerializer::class)
        val sendingPubkey: ByteArray,
        @SerialName("rules_hash")
        @Serializable(with = Base64ByteArraySerializer::class)
        val rulesHash: ByteArray? = null,
        @SerialName("rules_signature")
        @Serializable(with = Base64ByteArraySerializer::class)
        val rulesSignature: ByteArray? = null,
        @SerialName("rules_text")
        val rulesText: String? = null,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): MemberProfile {
        val raw = decoder.decodeSerializableValue(Surrogate.serializer())
        // Wrong-sized or half-present bytes are no agreement.
        val keep = raw.rulesHash?.size == 32 && raw.rulesSignature?.size == 64
        val hash = raw.rulesHash.takeIf { keep }
        val signature = raw.rulesSignature.takeIf { keep }
        // And the text is kept only when it is the text that hash names,
        // measured and compared exactly as it arrived.
        val text = MemberProfile.storableRulesText(raw.rulesText, hash)
        return MemberProfile(
            alias = raw.alias,
            inboxPublicKey = raw.inboxPublicKey,
            sendingPubkey = raw.sendingPubkey,
            rulesHash = hash,
            rulesSignature = signature,
            rulesText = text,
        )
    }

    override fun serialize(encoder: Encoder, value: MemberProfile) {
        encoder.encodeSerializableValue(
            Surrogate.serializer(),
            Surrogate(
                alias = value.alias,
                inboxPublicKey = value.inboxPublicKey,
                sendingPubkey = value.sendingPubkey,
                rulesHash = value.rulesHash,
                rulesSignature = value.rulesSignature,
                rulesText = value.rulesText,
            ),
        )
    }
}
