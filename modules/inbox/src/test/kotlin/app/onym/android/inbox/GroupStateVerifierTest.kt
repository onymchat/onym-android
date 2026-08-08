package app.onym.android.inbox

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupInvitationPayload
import app.onym.android.group.GroupRepository
import app.onym.android.group.GroupStateRefreshRequest
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioral tests for [GroupStateVerifier] — the verify-at-current
 * state machine (PR 159). Exercises the salt-non-disclosure gates on
 * the admin side and the unreachable fallback on the invitee side.
 * (The exact-epoch byte-verify itself is FFI-backed and lives in the
 * androidTest dispatcher test.)
 *
 * Mirrors `GroupStateVerifierTests` from onym-ios PR #159.
 */
class GroupStateVerifierTest {

    private val owner = IdentityId("owner")
    private val meBls = ByteArray(48) { 0x33 }
    private val meInbox = ByteArray(32) { 0x34 }
    private val meSending = ByteArray(32) { 0x35 }

    private lateinit var active: FakeActiveIdentityProvider
    private lateinit var groups: GroupRepository
    private lateinit var transport: ConfigurableInboxTransport
    private lateinit var store: PendingVerificationStore
    private lateinit var identities: MutableStateFlow<List<IdentitySummary>>
    private lateinit var verifierScope: CoroutineScope

    @Before
    fun setUp() = runTest {
        active = FakeActiveIdentityProvider(owner)
        groups = GroupRepository(
            store = InMemoryGroupStore(),
            identity = active,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        transport = ConfigurableInboxTransport()
        store = PendingVerificationStore()
        store.setCurrentIdentity(owner)
        identities = MutableStateFlow(
            listOf(
                IdentitySummary(
                    id = owner,
                    name = "Me",
                    blsPublicKey = meBls,
                    inboxPublicKey = meInbox,
                    sendingPublicKey = meSending,
                ),
            ),
        )
        // Real (non-test-scheduler) scope + long timeout so the launched
        // timeout never fires during the assertions.
        verifierScope = CoroutineScope(Dispatchers.Default)
    }

    private fun makeVerifier() = GroupStateVerifier(
        sealer = FakeSealer(),
        inboxTransport = transport,
        groupRepository = groups,
        store = store,
        identities = identities,
        activeIdentityId = active.currentIdentityId,
        scope = verifierScope,
        refreshTimeoutMillis = 10 * 60 * 1000,
    )

    /** A snapshot whose admin we can't reach (no admin entry in the
     *  shipped roster) is recorded as UNREACHABLE and surfaced — not
     *  silently dropped, never materialized. */
    @Test
    fun deferVerification_noAdminInbox_marksUnreachable() = runTest {
        val verifier = makeVerifier()
        val invitation = invitation(
            groupId = ByteArray(32) { 0x42 },
            adminPubkeyHex = "aa".repeat(48),
            memberProfiles = null,  // admin not reachable
        )

        verifier.deferVerification(invitation, owner)

        val snapshot = store.snapshots.value
        assertEquals(1, snapshot.size)
        assertEquals(PendingGroupVerification.Status.UNREACHABLE, snapshot.first().status)
        assertTrue("deferral must not materialize a group", groups.snapshots.value.isEmpty())
        assertEquals("no admin inbox → nothing to send", 0, transport.sends().size)
    }

    /** With a reachable admin, defer sends a refresh and marks the group
     *  VERIFYING (hidden from the chats list until verified). */
    @Test
    fun deferVerification_withAdminInbox_sendsRefreshAndMarksVerifying() = runTest {
        val verifier = makeVerifier()
        val adminBlsHex = "cc".repeat(48)
        val adminInbox = ByteArray(32) { 0x10 }
        val invitation = invitation(
            groupId = ByteArray(32) { 0x42 },
            adminPubkeyHex = adminBlsHex,
            memberProfiles = mapOf(
                adminBlsHex to MemberProfile(
                    alias = "Admin",
                    inboxPublicKey = adminInbox,
                    sendingPubkey = ByteArray(32) { 0x20 },
                ),
            ),
        )

        verifier.deferVerification(invitation, owner)

        assertEquals("a refresh request must be sent to the admin", 1, transport.sends().size)
        val snapshot = store.snapshots.value
        assertEquals(1, snapshot.size)
        assertEquals(PendingGroupVerification.Status.VERIFYING, snapshot.first().status)
        assertTrue("deferral must not materialize a group", groups.snapshots.value.isEmpty())
    }

    /** The admin must not answer a refresh request from a non-member —
     *  the reply carries the salt. */
    @Test
    fun handleRefreshRequest_nonMember_doesNotReply() = runTest {
        val verifier = makeVerifier()
        val groupId = ByteArray(32) { 0x42 }
        val memberBlsHex = "bb".repeat(48)
        groups.insert(
            seededGroup(
                groupId = groupId,
                memberProfiles = mapOf(
                    memberBlsHex to MemberProfile(
                        alias = "Member",
                        inboxPublicKey = ByteArray(32) { 0x10 },
                        sendingPubkey = ByteArray(32) { 0xEE.toByte() },
                    ),
                ),
            ),
        )

        // Requester whose BLS isn't in the roster.
        verifier.handleRefreshRequest(
            GroupStateRefreshRequest(
                groupId = groupId,
                requesterInboxPublicKey = ByteArray(32) { 0x01 },
                requesterBlsPublicKey = ByteArray(48) { 0x02 },
            ),
            ownerIdentityId = owner,
            requesterEd25519 = ByteArray(32) { 0xAB.toByte() },
        )

        assertEquals(
            "non-member must not receive the current snapshot (salt)",
            0,
            transport.sends().size,
        )
    }

    /** A current member whose envelope-signer + inbox match their stored
     *  profile gets the current snapshot. */
    @Test
    fun handleRefreshRequest_member_repliesWithSnapshot() = runTest {
        val verifier = makeVerifier()
        val groupId = ByteArray(32) { 0x42 }
        val reqBls = ByteArray(48) { 0x07 }
        val reqBlsHex = reqBls.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val reqInbox = ByteArray(32) { 0x08 }
        val reqEd = ByteArray(32) { 0x09 }
        groups.insert(
            seededGroup(
                groupId = groupId,
                memberProfiles = mapOf(
                    reqBlsHex to MemberProfile(
                        alias = "Member",
                        inboxPublicKey = reqInbox,
                        sendingPubkey = reqEd,
                    ),
                ),
            ),
        )

        verifier.handleRefreshRequest(
            GroupStateRefreshRequest(
                groupId = groupId,
                requesterInboxPublicKey = reqInbox,
                requesterBlsPublicKey = reqBls,
            ),
            ownerIdentityId = owner,
            requesterEd25519 = reqEd,
        )

        assertEquals("current member should receive the snapshot", 1, transport.sends().size)
    }

    /** Even a member is refused when the envelope's Ed25519 signer
     *  doesn't match their stored sending key (insider-spoof defense). */
    @Test
    fun handleRefreshRequest_memberButSignerMismatch_doesNotReply() = runTest {
        val verifier = makeVerifier()
        val groupId = ByteArray(32) { 0x42 }
        val reqBls = ByteArray(48) { 0x07 }
        val reqBlsHex = reqBls.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val reqInbox = ByteArray(32) { 0x08 }
        groups.insert(
            seededGroup(
                groupId = groupId,
                memberProfiles = mapOf(
                    reqBlsHex to MemberProfile(
                        alias = "Member",
                        inboxPublicKey = reqInbox,
                        sendingPubkey = ByteArray(32) { 0x09 },
                    ),
                ),
            ),
        )

        verifier.handleRefreshRequest(
            GroupStateRefreshRequest(
                groupId = groupId,
                requesterInboxPublicKey = reqInbox,
                requesterBlsPublicKey = reqBls,
            ),
            ownerIdentityId = owner,
            requesterEd25519 = ByteArray(32) { 0xFF.toByte() },  // wrong signer
        )

        assertEquals(0, transport.sends().size)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun invitation(
        groupId: ByteArray,
        adminPubkeyHex: String?,
        memberProfiles: Map<String, MemberProfile>?,
    ) = GroupInvitationPayload(
        version = 1,
        groupId = groupId,
        groupSecret = ByteArray(32) { 0x55 },
        name = "Family",
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32) { 0x66 },
        commitment = ByteArray(32) { 0x77 },
        tierRaw = SepTier.SMALL.rawValue,
        groupTypeRaw = SepGroupType.TYRANNY.wireValue,
        adminPubkeyHex = adminPubkeyHex,
        memberProfiles = memberProfiles,
    )

    private fun seededGroup(
        groupId: ByteArray,
        memberProfiles: Map<String, MemberProfile>,
    ): ChatGroup {
        val hex = groupId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return ChatGroup(
            id = hex,
            name = "Family",
            groupSecret = ByteArray(32) { 0x55 },
            createdAtMillis = 0L,
            members = emptyList(),
            memberProfiles = memberProfiles,
            epoch = 1uL,
            salt = ByteArray(32) { 0x66 },
            commitment = ByteArray(32) { 0x77 },
            tier = SepTier.SMALL,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = "aa".repeat(48),
            isPublishedOnChain = true,
            ownerIdentityId = owner.value,
        )
    }
}

/** Trivial sealer — the verifier only needs *some* bytes to ship; the
 *  envelope crypto is exercised elsewhere. */
private class FakeSealer : InvitationEnvelopeSealer {
    override suspend fun sealInvitation(payload: ByteArray, recipientInboxPublicKey: ByteArray): ByteArray =
        payload
}
