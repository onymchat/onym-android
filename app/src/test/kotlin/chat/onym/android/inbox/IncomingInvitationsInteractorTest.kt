@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package chat.onym.android.inbox

import chat.onym.android.identity.IdentityId
import chat.onym.android.support.FakeActiveIdentityProvider
import chat.onym.android.support.FakeInboxTransport
import chat.onym.android.support.InMemoryInvitationStore
import chat.onym.android.transport.InboundInbox
import chat.onym.android.transport.TransportInboxId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * End-to-end pump test against both reusable fakes
 * ([FakeInboxTransport] + [InMemoryInvitationStore]). Validates the
 * one job [IncomingInvitationsInteractor] has — forward each
 * inbound message into the repository, dedup at the repo, exit
 * cleanly on cancellation. Per-identity-keyed fan-out (PR-6 of
 * deeplink-invite stack) is also covered: each subscription
 * captures its identity ID and stamps inbounds with it.
 *
 * Mirrors `IncomingInvitationsInteractorTests.swift` from onym-ios PR #16.
 */
class IncomingInvitationsInteractorTest {

    private val inbox = TransportInboxId("inbox-test")
    private val now = Instant.parse("2026-05-02T12:00:00Z")
    private val testOwner = IdentityId("test-owner")

    @Test
    fun oneInbound_oneRecord() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val repo = makeRepo(store, this)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        coroutineScope {
            val job = async { interactor.run(inbox, testOwner) }
            transport.emit(inbound("ev1", "p1"))
            transport.finish(inbox)
            job.await()
        }

        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("ev1", all[0].id)
        assertArrayEquals("p1".toByteArray(), all[0].payload)
        assertEquals(testOwner.value, all[0].ownerIdentityIdString)
    }

    @Test
    fun multipleInbounds_persistedInOrder() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val repo = makeRepo(store, this)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        coroutineScope {
            val job = async { interactor.run(inbox, testOwner) }
            transport.emit(inbound("ev1", "first", at = now))
            transport.emit(inbound("ev2", "second", at = now.plusSeconds(1)))
            transport.emit(inbound("ev3", "third", at = now.plusSeconds(2)))
            transport.finish(inbox)
            job.await()
        }

        // store.list() is ordered receivedAt desc.
        assertEquals(listOf("ev3", "ev2", "ev1"), store.list().map { it.id })
    }

    @Test
    fun duplicateMessageId_dedupedAtRepository() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val repo = makeRepo(store, this)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        coroutineScope {
            val job = async { interactor.run(inbox, testOwner) }
            // Same id, different payload — relays can replay events
            // across reconnects and the dedup-on-id property is what
            // protects the repository.
            transport.emit(inbound("ev1", "first"))
            transport.emit(inbound("ev1", "second-replay"))
            transport.finish(inbox)
            job.await()
        }

        val rec = store.list().single()
        assertEquals("ev1", rec.id)
        assertArrayEquals("first".toByteArray(), rec.payload)
    }

    @Test
    fun cancellation_unsubscribesUpstream() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val repo = makeRepo(store, this)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        coroutineScope {
            val job = async { interactor.run(inbox, testOwner) }
            // Make sure subscribe() has fired before we cancel.
            transport.emit(inbound("ev1", "x"))
            yield()
            assertEquals(1, transport.subscribeCallCount)
            job.cancel()
            // Wait for the cancellation to propagate through onCompletion.
            withTimeout(1_000) {
                while (transport.unsubscribedInboxes.isEmpty()) yield()
            }
        }

        assertTrue(
            "cancellation must trigger upstream unsubscribe (mirrors NostrInboxTransport's awaitClose)",
            transport.unsubscribedInboxes.contains(inbox),
        )
    }

    @Test
    fun finishWhileIdle_exitsCleanly() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val repo = makeRepo(store, this)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        coroutineScope {
            val job = async { interactor.run(inbox, testOwner) }
            // No inbounds — just close the channel.
            yield()
            transport.finish(inbox)
            job.await()  // must complete without throwing
        }

        assertTrue("no records persisted on a finish-while-idle path", store.list().isEmpty())
        assertTrue(
            "subscribe Flow's onCompletion must still fire unsubscribe on graceful close",
            transport.unsubscribedInboxes.contains(inbox),
        )
    }

    @Test
    fun runFanout_subscribesToEveryEntryInTheList_andStampsOwnerPerSubscription() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val alice = IdentityId("alice-id")
        val bob = IdentityId("bob-id")
        val aliceTag = TransportInboxId("alice-tag")
        val bobTag = TransportInboxId("bob-tag")
        val repo = makeRepo(store, this, initialActive = alice)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        val entries = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(alice to aliceTag, bob to bobTag),
        )

        coroutineScope {
            val job = async { interactor.runFanout(entries) }
            yield()
            // Inbounds on either tag must land in the store, each
            // stamped with its subscription's identity ID.
            transport.emit(InboundInbox(aliceTag, "alice-msg".toByteArray(), now, "alice-1"))
            transport.emit(InboundInbox(bobTag, "bob-msg".toByteArray(), now, "bob-1"))
            yield()
            val rows = store.list()
            assertEquals(2, rows.size)
            val aliceRow = rows.first { it.id == "alice-1" }
            val bobRow = rows.first { it.id == "bob-1" }
            assertEquals(alice.value, aliceRow.ownerIdentityIdString)
            assertEquals(bob.value, bobRow.ownerIdentityIdString)
            // Cancel the fan-out so the test exits.
            job.cancel()
            job.join()
        }
    }

    @Test
    fun runFanout_unsubscribesRemovedEntries_andSubscribesNewOnes() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryInvitationStore()
        val alice = IdentityId("alice-id")
        val carol = IdentityId("carol-id")
        val aliceTag = TransportInboxId("alice-tag")
        val carolTag = TransportInboxId("carol-tag")
        val repo = makeRepo(store, this, initialActive = alice)
        val interactor = IncomingInvitationsInteractor(transport, repo)

        val entries = kotlinx.coroutines.flow.MutableStateFlow(
            listOf(alice to aliceTag),
        )

        coroutineScope {
            val job = async { interactor.runFanout(entries) }
            yield()
            // Initial subscription set is just Alice.
            assertEquals(setOf(aliceTag), transport.subscribedInboxes)

            // Swap the entire list to [carol]: Alice unsubscribes,
            // Carol subscribes. Wholesale cancel-and-rebuild
            // semantics — collectLatest cancels the inner scope which
            // cancels every child launch, then re-launches with the
            // new list.
            entries.value = listOf(carol to carolTag)
            yield()
            assertEquals(setOf(carolTag), transport.subscribedInboxes)
            assertTrue(
                "Alice was unsubscribed when she dropped off the list",
                transport.unsubscribedInboxes.contains(aliceTag),
            )

            job.cancel()
            job.join()
        }
    }

    private fun makeRepo(
        store: InMemoryInvitationStore,
        scope: kotlinx.coroutines.CoroutineScope,
        initialActive: IdentityId = testOwner,
    ): IncomingInvitationsRepository = IncomingInvitationsRepository(
        store = store,
        identity = FakeActiveIdentityProvider(initial = initialActive),
        scope = scope,
    )

    private fun inbound(id: String, payload: String, at: Instant = now) = InboundInbox(
        inbox = inbox,
        payload = payload.toByteArray(Charsets.UTF_8),
        receivedAt = at,
        messageId = id,
    )
}
