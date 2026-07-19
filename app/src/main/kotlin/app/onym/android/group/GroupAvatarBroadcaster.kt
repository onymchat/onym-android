package app.onym.android.group

import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Admin-side sender for group-avatar changes. Applies the new photo
 * locally (persist first — the broadcast is best-effort) and fans a
 * [GroupAvatarPayload] out to every member's inbox **except the admin's
 * own key**, sealed with the same X25519 + AES-GCM + Ed25519-signature
 * envelope as [MemberAnnouncementPayload] (one envelope per recipient).
 *
 * Best-effort by design: a per-member transport failure is swallowed
 * and the loop moves on. A member who misses an update picks up the
 * current avatar from the next full snapshot
 * ([GroupInvitationPayload.avatar]). Setting [avatar] to `null` clears
 * the photo (the payload omits the `avatar` key → receivers read it as
 * removal).
 *
 * Only the cryptographic admin may change the photo — gated here on the
 * active identity's BLS key matching [ChatGroup.adminPubkeyHex], the
 * same key the receive-side trust gate verifies against (via the
 * envelope's Ed25519 signer). Non-admin governance models (Anarchy /
 * OneOnOne, where there is no admin) return [Outcome.NotAdmin].
 *
 * Mirrors `GroupAvatarBroadcaster.swift` from onym-ios PR #166.
 */
class GroupAvatarBroadcaster(
    private val activeIdentity: ActiveIdentityProvider,
    private val identitiesFlow: StateFlow<List<IdentitySummary>>,
    private val envelopeSealer: InvitationEnvelopeSealer,
    private val groupRepository: GroupRepository,
    private val inboxTransport: InboxTransport,
) {
    sealed class Outcome {
        /** Applied locally + fanned out (best-effort). */
        object Sent : Outcome()

        /** No group with this id under the active identity. */
        object UnknownGroup : Outcome()

        /** Active identity isn't this group's admin. */
        object NotAdmin : Outcome()

        /** No identity selected, or its summary couldn't be resolved. */
        object NoIdentity : Outcome()
    }

    /**
     * Set (or, with `avatar == null`, clear) the group's photo and
     * broadcast the change. Idempotent on the persistence side —
     * re-running with the same bytes rewrites the same row.
     */
    suspend fun setAvatar(groupId: String, avatar: ByteArray?): Outcome {
        val group = groupRepository.snapshots.value
            .firstOrNull { it.id.equals(groupId, ignoreCase = true) }
            ?: return Outcome.UnknownGroup

        val activeId = activeIdentity.currentIdentityId.value ?: return Outcome.NoIdentity
        val activeBls = identitiesFlow.value
            .firstOrNull { it.id == activeId }
            ?.blsPublicKey
            ?: return Outcome.NoIdentity
        val activeBlsHex = activeBls.toHexLowercase()

        // Admin gate: only the group's admin may change the photo.
        val adminHex = group.adminPubkeyHex?.lowercase() ?: return Outcome.NotAdmin
        if (activeBlsHex != adminHex) return Outcome.NotAdmin

        // Apply + persist first; broadcast is best-effort.
        groupRepository.insert(group.copy(avatar = avatar))

        val payload = GroupAvatarPayload(
            version = 1,
            groupId = group.groupIdBytes,
            senderBlsHex = activeBlsHex,
            sentAtMillis = System.currentTimeMillis(),
            avatar = avatar,
        )
        val payloadBytes = try {
            jsonFormat.encodeToString(GroupAvatarPayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            // Local apply already succeeded; skip the fan-out.
            return Outcome.Sent
        }

        for ((memberKey, profile) in group.memberProfiles) {
            if (memberKey.equals(activeBlsHex, ignoreCase = true)) continue // skip self
            val sealed = try {
                envelopeSealer.sealInvitation(payloadBytes, profile.inboxPublicKey)
            } catch (_: Throwable) {
                continue
            }
            val tag = TransportInboxId(IdentityRepository.inboxTag(profile.inboxPublicKey))
            // Discard the receipt — fan-out is best-effort; the next
            // snapshot backfills anyone who missed this update.
            runCatching { inboxTransport.send(sealed, tag) }
        }
        return Outcome.Sent
    }

    /**
     * Rename the group and broadcast the change. Same admin gate + best-
     * effort per-member fan-out as [setAvatar]. Whitespace-trimmed; a
     * blank name (or one equal to the current) is a no-op returning
     * [Outcome.Sent].
     */
    suspend fun setName(groupId: String, name: String): Outcome {
        val trimmed = name.trim()
        val group = groupRepository.snapshots.value
            .firstOrNull { it.id.equals(groupId, ignoreCase = true) }
            ?: return Outcome.UnknownGroup

        val activeId = activeIdentity.currentIdentityId.value ?: return Outcome.NoIdentity
        val activeBls = identitiesFlow.value
            .firstOrNull { it.id == activeId }
            ?.blsPublicKey
            ?: return Outcome.NoIdentity
        val activeBlsHex = activeBls.toHexLowercase()

        val adminHex = group.adminPubkeyHex?.lowercase() ?: return Outcome.NotAdmin
        if (activeBlsHex != adminHex) return Outcome.NotAdmin

        if (trimmed.isEmpty() || trimmed == group.name) return Outcome.Sent

        // Apply + persist first; broadcast is best-effort.
        groupRepository.insert(group.copy(name = trimmed))

        val payload = GroupNamePayload(
            version = 1,
            groupId = group.groupIdBytes,
            senderBlsHex = activeBlsHex,
            sentAtMillis = System.currentTimeMillis(),
            name = trimmed,
        )
        val payloadBytes = try {
            jsonFormat.encodeToString(GroupNamePayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            return Outcome.Sent
        }

        for ((memberKey, profile) in group.memberProfiles) {
            if (memberKey.equals(activeBlsHex, ignoreCase = true)) continue // skip self
            val sealed = try {
                envelopeSealer.sealInvitation(payloadBytes, profile.inboxPublicKey)
            } catch (_: Throwable) {
                continue
            }
            val tag = TransportInboxId(IdentityRepository.inboxTag(profile.inboxPublicKey))
            runCatching { inboxTransport.send(sealed, tag) }
        }
        return Outcome.Sent
    }

    private companion object {
        /** `encodeDefaults = false` so a null avatar (removal) omits the
         *  `avatar` key entirely, matching the iOS removal wire shape. */
        private val jsonFormat = Json { encodeDefaults = false; ignoreUnknownKeys = true }

        private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
