package app.onym.android.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.chats.MessageRepository
import app.onym.android.group.GroupRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** One search hit: group name, a snippet of the matched message body,
 *  and the sent timestamp. Android twin of iOS `MessageSearchResult`. */
data class MessageSearchResult(
    val messageId: UUID,
    val groupId: String,
    val groupName: String,
    val snippet: String,
    val sentAtMillis: Long,
)

/**
 * Backs the Search tab: full-text search across the active identity's
 * chat messages. Each query edit (debounced) scans this identity's
 * decrypted message bodies via [MessageRepository.search] and resolves
 * group names from [GroupRepository]. Android twin of iOS `SearchView`'s
 * inline search logic.
 */
class SearchViewModel(
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<MessageSearchResult>>(emptyList())
    val results: StateFlow<List<MessageSearchResult>> = _results.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MILLIS)
            val matches = messageRepository.search(trimmed)
            val groupsById = groupRepository.snapshots.value.associateBy { it.id }
            _results.value = matches.map { message ->
                MessageSearchResult(
                    messageId = message.id,
                    groupId = message.groupId,
                    groupName = groupsById[message.groupId]?.name ?: "Chat",
                    snippet = message.body,
                    sentAtMillis = message.sentAtMillis,
                )
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 200L
    }
}
