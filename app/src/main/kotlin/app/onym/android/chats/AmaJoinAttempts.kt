package app.onym.android.chats

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.amaJoinDataStore by preferencesDataStore(name = "ama_join")

/**
 * [AmaJoinAttempts] on disk. Its own preferences file, the way the
 * relayer, discovery and transport settings each own theirs: the flag
 * outlives the process, and nothing else needs to read it.
 */
class DataStoreAmaJoinAttempts(private val context: Context) : AmaJoinAttempts {

    override suspend fun hasAttempted(identityKey: String): Boolean =
        context.amaJoinDataStore.data.first()[key(identityKey)] ?: false

    override suspend fun markAttempted(identityKey: String) {
        val key = key(identityKey)
        context.amaJoinDataStore.edit { it[key] = true }
    }

    private fun key(identityKey: String) =
        booleanPreferencesKey("attempted.${identityKey.lowercase()}")
}
