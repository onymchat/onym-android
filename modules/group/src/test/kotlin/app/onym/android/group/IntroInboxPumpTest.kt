@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.group

import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeInboxTransport
import app.onym.android.transport.InboundInbox
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IntroInboxPumpTest {

    private val now = Instant.parse("2026-05-03T12:00:00Z")
    private val alice = IdentityId("alice-uuid")
    private val groupId = ByteArray(32) { 0x42 }

    private fun entry(seed: Byte) = IntroKeyEntry(
        introPublicKey = ByteArray(32) { seed },
        introPrivateKey = ByteArray(32) { (seed + 1).toByte() },
        ownerIdentityId = alice,
        groupId = groupId,
        createdAtMillis = 1_700_000_000L,
    )

    /** Fake tag derivation — each pubkey gets the hex of its first
     *  4 bytes as its tag. Doesn't have to match
     *  IdentityRepository.inboxTag's actual SHA-256 derivation;
     *  the pump only needs the mapping to be deterministic + the
     *  inverse uniquely recoverable. */
    private fun fakeTag(pub: ByteArray): TransportInboxId =
        TransportInboxId(pub.take(4).joinToString("") { "%02x".format(it) })

    @Test
    fun runFanout_subscribesToEveryIntroPubInTheList() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryIntroRequestStore()
        val pump = IntroInboxPump(transport, store, ::fakeTag)

        val entries = MutableStateFlow(listOf(entry(0x10), entry(0x20)))

        coroutineScope {
            val job = async { pump.runFanout(entries) }
            yield()
            assertEquals(
                "two entries → two subscriptions",
                setOf(fakeTag(entry(0x10).introPublicKey), fakeTag(entry(0x20).introPublicKey)),
                transport.subscribedInboxes,
            )
            job.cancel()
            job.join()
        }
    }

    @Test
    fun runFanout_inboundOnTaggedInbox_recordedWithMatchingIntroPub() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryIntroRequestStore()
        val pump = IntroInboxPump(transport, store, ::fakeTag)

        val e = entry(0x10)
        val entries = MutableStateFlow(listOf(e))

        coroutineScope {
            val job = async { pump.runFanout(entries) }
            yield()
            transport.emit(
                InboundInbox(
                    inbox = fakeTag(e.introPublicKey),
                    payload = "request-bytes".toByteArray(),
                    receivedAt = now,
                    messageId = "ev-1",
                )
            )
            yield()
            val recorded = store.requests.first()
            assertEquals(1, recorded.size)
            // Every inbound is stamped with the introPub of the
            // entry whose tag it landed on. PR-4's approval
            // interactor looks up the privkey by this field.
            assertArrayEquals(e.introPublicKey, recorded.first().targetIntroPublicKey)
            job.cancel()
            job.join()
        }
    }

    @Test
    fun runFanout_swappingTheListCancelsOldSubsAndStartsNewOnes() = runTest(UnconfinedTestDispatcher()) {
        // Re-subscribing the SAME tag after cancellation hits a fake-
        // transport limitation (consumeAsFlow cancels the channel),
        // mirrored in IncomingInvitationsInteractorTest. Swap the list
        // to entirely different entries so the assertion exercises the
        // wholesale-resubscribe semantics without that trap.
        val transport = FakeInboxTransport()
        val store = InMemoryIntroRequestStore()
        val pump = IntroInboxPump(transport, store, ::fakeTag)

        val a = entry(0x10)
        val c = entry(0x30)
        val entries = MutableStateFlow(listOf(a))

        coroutineScope {
            val job = async { pump.runFanout(entries) }
            yield()
            assertEquals(setOf(fakeTag(a.introPublicKey)), transport.subscribedInboxes)

            entries.value = listOf(c)
            yield()
            assertEquals(setOf(fakeTag(c.introPublicKey)), transport.subscribedInboxes)
            assertTrue(
                "a's subscription was cancelled when it dropped off the list",
                transport.unsubscribedInboxes.contains(fakeTag(a.introPublicKey)),
            )
            job.cancel()
            job.join()
        }
    }

    @Test
    fun runFanout_emptyList_subscribesToNothing() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeInboxTransport()
        val store = InMemoryIntroRequestStore()
        val pump = IntroInboxPump(transport, store, ::fakeTag)

        val entries = MutableStateFlow<List<IntroKeyEntry>>(emptyList())

        coroutineScope {
            val job = async { pump.runFanout(entries) }
            yield()
            assertTrue(transport.subscribedInboxes.isEmpty())
            job.cancel()
            job.join()
        }
    }
}
