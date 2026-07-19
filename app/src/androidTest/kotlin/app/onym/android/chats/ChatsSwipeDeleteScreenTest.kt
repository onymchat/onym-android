package app.onym.android.chats

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.R
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Compose UI test for swipe-to-delete on the Chats list. Renders
 * [ChatsScreen] directly with a real [ChatsViewModel] over in-memory
 * stores (no group-creation harness needed), and drives the swipe →
 * confirm / cancel flow. The actual delete (group + messages, owner-
 * scoped, local-only) is unit-tested in [ChatsViewModelTest]. Android
 * twin of onym-ios's `DeleteChatUITests`.
 */
@RunWith(AndroidJUnit4::class)
class ChatsSwipeDeleteScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val owner = IdentityId("test-owner")
    private val groupId = "01".repeat(32)

    private lateinit var ctx: Context
    private lateinit var groupStore: InMemoryGroupStore
    private lateinit var viewModel: ChatsViewModel

    @Before
    fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        groupStore = InMemoryGroupStore()
        groupStore.preload(listOf(makeGroup(id = groupId, name = "Silver Orchard")))
        val identity = FakeActiveIdentityProvider(initial = owner)
        val groupRepo = GroupRepository(
            store = groupStore,
            identity = identity,
            scope = CoroutineScope(Dispatchers.Main),
        )
        groupRepo.reload()
        val messageRepo = MessageRepository(
            store = InMemoryMessageStore(),
            identity = identity,
            scope = CoroutineScope(Dispatchers.Main),
        )
        viewModel = ChatsViewModel(repository = groupRepo, messageRepository = messageRepo)
    }

    @Test
    fun swipeThenConfirm_removesChat() {
        setContent()
        composeRule.onNodeWithText("Silver Orchard").assertIsDisplayed()

        composeRule.onNodeWithTag("chats.row.swipe.$groupId")
            .performTouchInput { swipeLeft() }

        // Swiping opens the confirmation; the chat is NOT gone yet.
        composeRule.onNodeWithText(string(R.string.chats_delete_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Silver Orchard").assertIsDisplayed()

        composeRule.onNodeWithTag("chats.delete.confirm").performClick()

        // Confirmed → the row disappears.
        composeRule.waitUntil(timeoutMillis = 5.seconds.inWholeMilliseconds) {
            viewModel.items.value.none { it.group.id == groupId }
        }
        composeRule.onNodeWithText("Silver Orchard").assertDoesNotExist()
    }

    @Test
    fun swipeThenCancel_keepsChat() {
        setContent()
        composeRule.onNodeWithText("Silver Orchard").assertIsDisplayed()

        composeRule.onNodeWithTag("chats.row.swipe.$groupId")
            .performTouchInput { swipeLeft() }
        composeRule.onNodeWithText(string(R.string.chats_delete_title)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        // Cancelled → the chat survives.
        composeRule.onNodeWithText(string(R.string.chats_delete_title)).assertDoesNotExist()
        composeRule.onNodeWithText("Silver Orchard").assertIsDisplayed()
        assertTrue(viewModel.items.value.any { it.group.id == groupId })
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                ChatsScreen(viewModel = viewModel, onCreateGroup = {})
            }
        }
    }

    private fun string(resId: Int): String = ctx.getString(resId)

    private fun makeGroup(id: String, name: String) = ChatGroup(
        id = id,
        name = name,
        groupSecret = ByteArray(32),
        createdAtMillis = 1_700_000_000_000L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
        ownerIdentityId = owner.value,
    )
}
