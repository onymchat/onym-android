@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Contract tests for [ChatThreadViewModel]. Verifies the data
 * plumbing the screen depends on:
 *  - `group` resolves the active group by id from
 *    [GroupRepository.snapshots].
 *  - `messages` mirrors [MessageRepository.snapshots] for the same id.
 *  - `send(body)` delegates to the captured interactor closure and
 *    routes errors into `lastSendError`; empty bodies short-circuit
 *    without invoking the closure.
 *  - `sendInFlight` flips around the closure execution.
 *
 * Mirrors `ChatThreadViewControllerTests.swift` from onym-ios PR #151
 * in spirit — iOS tests verify the UIKit controller wiring; Android
 * verifies the equivalent VM surface (the Compose screen is a thin
 * `collectAsStateWithLifecycle` reader over this VM).
 */
class ChatThreadViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val activeId = IdentityId("alice")
    private val groupIdHex = "aa".repeat(32)

    // ─── group resolution ────────────────────────────────────────

    @Test
    fun group_resolvesByGroupId_fromActiveIdentitySnapshots() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex, name = "Family")

        val vm = fixture.makeViewModel()
        assertEquals("Family", vm.group.first { it != null }?.name)
    }

    @Test
    fun group_nullWhenGroupNotPresent() = runTest {
        val fixture = newFixture()
        // No group seeded.
        val vm = fixture.makeViewModel()
        assertNull(vm.group.value)
    }

    // ─── message stream wiring ───────────────────────────────────

    @Test
    fun messages_minorOfMessageRepositorySnapshots() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val msg = sampleMessage(groupIdHex, body = "hi")
        fixture.messageStore.preload(listOf(msg))

        val vm = fixture.makeViewModel()
        val rows = vm.messages.first { it.isNotEmpty() }
        assertEquals(1, rows.size)
        assertEquals("hi", rows.single().body)
    }

    @Test
    fun messages_emitsOnAppend() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel()
        // Subscribe so MessageRepository's lazy cache materializes.
        assertEquals(0, vm.messages.first().size)

        fixture.messageRepository.append(sampleMessage(groupIdHex, body = "new"))
        val rows = vm.messages.first { it.isNotEmpty() }
        assertEquals("new", rows.single().body)
    }

    // ─── send action ─────────────────────────────────────────────

    @Test
    fun send_invokesInteractorWithTrimmedBody() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val captured = mutableListOf<Pair<String, String>>()
        val vm = fixture.makeViewModel(
            send = { gid, body -> captured.add(gid to body) },
        )

        vm.send("  hello world  ")
        assertEquals(1, captured.size)
        assertEquals(groupIdHex to "hello world", captured.single())
    }

    @Test
    fun send_emptyOrWhitespaceBody_isShortCircuited() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val captured = mutableListOf<Pair<String, String>>()
        val vm = fixture.makeViewModel(
            send = { gid, body -> captured.add(gid to body) },
        )

        vm.send("")
        vm.send("   ")
        assertTrue(
            "empty / whitespace bodies must not reach the interactor",
            captured.isEmpty(),
        )
    }

    @Test
    fun send_interactorThrows_routesIntoLastSendError() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            send = { _, _ -> throw SendMessageError.SenderNotAMember },
        )

        vm.send("hi")
        assertNotNull(vm.lastSendError.value)
    }

    @Test
    fun clearError_resetsLastSendError() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        val vm = fixture.makeViewModel(
            send = { _, _ -> throw SendMessageError.EmptyBody },
        )
        vm.send("hi")
        assertNotNull(vm.lastSendError.value)

        vm.clearError()
        assertNull(vm.lastSendError.value)
    }

    @Test
    fun send_successClearsPreviousError() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(groupIdHex)
        var shouldThrow = true
        val vm = fixture.makeViewModel(
            send = { _, _ ->
                if (shouldThrow) throw SendMessageError.SenderNotAMember
            },
        )
        vm.send("first")  // throws → error set
        assertNotNull(vm.lastSendError.value)

        shouldThrow = false
        vm.send("second")  // succeeds → error cleared
        assertNull(vm.lastSendError.value)
    }

    // ─── helpers ─────────────────────────────────────────────────

    private fun sampleMessage(groupIdHex: String, body: String): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = groupIdHex,
        ownerIdentityId = activeId.value,
        senderBlsPubkeyHex = "cc".repeat(48),
        body = body,
        sentAtMillis = 1_700_000_000_000L,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.RECEIVED,
        groupType = SepGroupType.TYRANNY,
    )

    private fun newFixture(): Fixture {
        val activeProvider = FakeActiveIdentityProvider(initial = activeId)
        val groupStore = InMemoryGroupStore()
        val groupRepository = GroupRepository(
            store = groupStore,
            identity = activeProvider,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        groupRepository.start()
        val messageStore = InMemoryMessageStore()
        val messageRepository = MessageRepository(
            store = messageStore,
            identity = activeProvider,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        return Fixture(groupStore, groupRepository, messageStore, messageRepository)
    }

    private inner class Fixture(
        val groupStore: InMemoryGroupStore,
        val groupRepository: GroupRepository,
        val messageStore: InMemoryMessageStore,
        val messageRepository: MessageRepository,
    ) {
        suspend fun seedGroup(id: String, name: String = "Group") {
            groupStore.preload(
                listOf(
                    ChatGroup(
                        id = id,
                        name = name,
                        groupSecret = ByteArray(32),
                        createdAtMillis = 0L,
                        members = emptyList(),
                        memberProfiles = mapOf(
                            "aa".repeat(48) to MemberProfile(
                                alias = "Alice",
                                inboxPublicKey = ByteArray(32) { 0x22 },
                                sendingPubkey = ByteArray(32) { 0x33 },
                            ),
                        ),
                        epoch = 0uL,
                        salt = ByteArray(32),
                        commitment = null,
                        tier = SepTier.SMALL,
                        groupType = SepGroupType.TYRANNY,
                        adminPubkeyHex = null,
                        isPublishedOnChain = true,
                        ownerIdentityId = activeId.value,
                    ),
                ),
            )
            groupRepository.reload()
        }

        fun makeViewModel(
            send: suspend (String, String) -> Unit = { _, _ -> },
        ) = ChatThreadViewModel(
            groupId = groupIdHex,
            groupRepository = groupRepository,
            messageRepository = messageRepository,
            sendMessage = send,
        )
    }
}
