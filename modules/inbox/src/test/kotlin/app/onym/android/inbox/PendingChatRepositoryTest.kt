package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The per-identity snapshot layer over [PendingChatStore]. Same contract
 * as `GroupRepository`, because it feeds the same list: collectors see
 * one identity's rows, and every mutation republishes.
 *
 * Mirrors `PendingChatRepositoryTests` in onym-ios.
 */
class PendingChatRepositoryTest {

    private val alice = IdentityId("alice")
    private val bob = IdentityId("bob")

    @Test
    fun snapshots_areFilteredToTheCurrentIdentity() = runTest {
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        repository.record(chat(group = 0x11, owner = alice))
        repository.record(chat(group = 0x22, owner = bob))

        assertEquals(listOf(alice), repository.snapshots.value.map { it.ownerIdentityId })
    }

    @Test
    fun noIdentitySelected_yieldsNothing() = runTest {
        // Cold start before the selection lands. Showing another
        // identity's invitations "until the filter arrives" would be a
        // cross-identity leak on the most-looked-at screen in the app.
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.record(chat(group = 0x11, owner = alice))
        assertTrue(repository.snapshots.value.isEmpty())
    }

    @Test
    fun record_reportsWhatTheWriteDid() = runTest {
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        val row = chat(group = 0x11, owner = alice)

        assertEquals(PendingChatWriteOutcome.INSERTED, repository.record(row))
        assertEquals(PendingChatWriteOutcome.ALREADY_PRESENT, repository.record(row))
    }

    @Test
    fun record_onARepeatOffer_takesTheNewerReplyChannel() = runTest {
        // A re-invite mints a fresh intro key and revokes the old one.
        // Keeping the first would seal Accept to a dead address.
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        val first = chat(group = 0x11, owner = alice)
        repository.record(first)
        repository.markRequested(first.id)

        val outcome = repository.record(
            first.copy(
                introPublicKey = ByteArray(32) { 0x99.toByte() },
                groupName = "Maple Garden (renamed)",
                // A re-invite is a *newer* offer, and that is what wins
                // it the row — see the stale-replay test below. The
                // stamp that decides is the sender's, not this device's
                // clock, so both move together.
                receivedAt = first.receivedAt.plusSeconds(60),
                offerReceivedAt = first.offerReceivedAt?.plusSeconds(60),
                status = PendingChat.Status.Offered,
            ),
        )

        assertEquals(PendingChatWriteOutcome.ALREADY_PRESENT, outcome)
        val row = repository.snapshots.value.single()
        assertTrue(row.introPublicKey.contentEquals(ByteArray(32) { 0x99.toByte() }))
        assertEquals("Maple Garden (renamed)", row.groupName)
        assertEquals(
            "the newer key changes where to ask, not what was asked",
            PendingChat.Status.Requested,
            row.status,
        )
    }

    @Test
    fun record_onAStaleReplay_keepsTheLiveReplyChannel() = runTest {
        // Out-of-order delivery, which relays do routinely: the founder
        // re-invites, and a relay then re-delivers the *retained*
        // original offer. It arrives last but was sent first, and
        // taking it would put the revoked intro key back — Accept and
        // Ask again would report success into an inbox nobody reads.
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        val original = chat(group = 0x11, owner = alice)
        val live = ByteArray(32) { 0x99.toByte() }
        repository.record(original)
        repository.record(
            original.copy(
                introPublicKey = live,
                receivedAt = original.receivedAt.plusSeconds(60),
                offerReceivedAt = original.offerReceivedAt?.plusSeconds(60),
            ),
        )

        val outcome = repository.record(original)

        assertEquals(PendingChatWriteOutcome.ALREADY_PRESENT, outcome)
        assertTrue(
            "the replay is older than the re-invite, so it loses",
            repository.snapshots.value.single().introPublicKey.contentEquals(live),
        )
    }

    @Test
    fun markFailed_carriesACodeTheScreenCanRenderInAnyLanguage() = runTest {
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        val row = chat(group = 0x11, owner = alice)
        repository.record(row)

        repository.markFailed(row.id, PendingChat.SendFailure.TRANSPORT)

        assertEquals(
            PendingChat.Status.Failed(PendingChat.SendFailure.TRANSPORT),
            repository.snapshots.value.single().status,
        )
    }

    @Test
    fun consumeForMaterialized_dropsTheWaitThatIsOver() = runTest {
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        val landed = chat(group = 0x11, owner = alice)
        val waiting = chat(group = 0x22, owner = alice)
        repository.record(landed)
        repository.record(waiting)

        repository.consumeForMaterialized(listOf(landed.groupIdHex to alice))

        assertEquals(
            listOf(waiting.groupIdHex),
            repository.snapshots.value.map { it.groupIdHex },
        )
    }

    @Test
    fun consumeForMaterialized_leavesTheOtherIdentitysRowAlone() = runTest {
        // The group stream is filtered to the selected identity, so the
        // pairs arriving here name one owner. Matching on the group
        // alone deleted the other identity's row for the same chat.
        val store = InMemoryPendingChatStore()
        val repository = PendingChatRepository(store)
        val mine = chat(group = 0x11, owner = alice)
        val theirs = chat(group = 0x11, owner = bob)
        repository.setCurrentIdentity(alice)
        repository.record(mine)
        repository.record(theirs)

        repository.consumeForMaterialized(listOf(mine.groupIdHex to alice))

        assertEquals(listOf(bob), store.list().map { it.ownerIdentityId })
    }

    @Test
    fun consumeForMaterialized_worksAgainstAColdCache() = runTest {
        // Launch order isn't guaranteed: the group watcher starts from
        // the view-model while the startup reload runs elsewhere, so the
        // first emission can arrive before anything has been read.
        // Deciding "no rows match" from an unread cache leaves the stale
        // row in the list until some unrelated group change.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = alice)
        store.insert(row)

        val repository = PendingChatRepository(store)
        repository.setCurrentIdentity(alice)
        repository.consumeForMaterialized(listOf(row.groupIdHex to alice))

        assertTrue(
            "the row must go even if the cache was never read",
            repository.snapshots.value.isEmpty(),
        )
        assertTrue("and it must go from the store, not just the cache", store.list().isEmpty())
    }

    @Test
    fun remove_dropsTheRowTheUserSwipedAway() = runTest {
        // The only user-initiated delete in the feature, and the one the
        // row's swipe is gated on.
        val store = InMemoryPendingChatStore()
        val repository = PendingChatRepository(store)
        repository.setCurrentIdentity(alice)
        val row = chat(group = 0x11, owner = alice)
        repository.record(row)

        repository.remove(row.id)

        assertTrue(repository.snapshots.value.isEmpty())
        assertTrue("local-only, but it does have to be local", store.list().isEmpty())
    }

    @Test
    fun removeForOwner_cascades() = runTest {
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        repository.setCurrentIdentity(alice)
        repository.record(chat(group = 0x11, owner = alice))

        repository.removeForOwner(alice)

        assertTrue(repository.snapshots.value.isEmpty())
    }

    @Test
    fun currentChats_readsThroughAColdCacheAndAcrossIdentities() = runTest {
        // The deeplink path's first question on a cold start, before
        // anything has read the store: without the read-through it
        // answers "no rows" and a second waiting room is created beside
        // the one already on disk. The answer must also not depend on
        // which identity happens to be selected.
        val store = InMemoryPendingChatStore()
        store.insert(chat(group = 0x11, owner = alice))
        store.insert(chat(group = 0x22, owner = bob))

        val repository = PendingChatRepository(store)
        val all = repository.currentChats()

        assertEquals(setOf(alice, bob), all.mapTo(mutableSetOf()) { it.ownerIdentityId })
    }

    private fun chat(group: Int, owner: IdentityId) = PendingChat(
        groupId = ByteArray(32) { group.toByte() },
        ownerIdentityId = owner,
        introPublicKey = ByteArray(32) { 0x44 },
        groupName = "Maple Garden",
        inviterAlias = "Alice",
        invitationMessage = null,
        receivedAt = Instant.ofEpochSecond(1_000),
        status = PendingChat.Status.Offered,
        offerReceivedAt = Instant.ofEpochSecond(1_000),
    )
}
