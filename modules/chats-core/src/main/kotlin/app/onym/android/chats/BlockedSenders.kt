package app.onym.android.chats

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * People this device's identities have blocked — onym-ios
 * `BlockedSenders` parity.
 *
 * Per local identity (the owner), keyed by the sender's BLS public key,
 * the roster key a chat message names its author by. A blocked sender's
 * messages leave the owner's chats the moment they're blocked
 * ([MessageRepository.removeIncoming]) and new ones are dropped on
 * arrival (the inbox dispatcher), until they're unblocked.
 *
 * Holds keys and the alias shown beside them, nothing from any message.
 */
class BlockedSenders(private val storage: Storage) {
    /** Where the entries live: SharedPreferences in production. */
    interface Storage {
        fun load(): Set<String>
        fun save(entries: Set<String>)
    }

    data class Entry(val ownerIdentityId: String, val senderBlsPubkeyHex: String)

    private val lock = Any()
    private val _revision = MutableStateFlow(0)

    /** Bumped on every block / unblock, for screens that show the state. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun isBlocked(senderBlsPubkeyHex: String, ownerIdentityId: String): Boolean =
        key(ownerIdentityId, senderBlsPubkeyHex) in synchronized(lock) { storage.load() }

    fun entries(ownerIdentityId: String): List<Entry> =
        synchronized(lock) { storage.load() }
            .mapNotNull { parse(it) }
            .filter { it.ownerIdentityId == ownerIdentityId }

    fun block(senderBlsPubkeyHex: String, ownerIdentityId: String) = mutate {
        it + key(ownerIdentityId, senderBlsPubkeyHex)
    }

    fun unblock(senderBlsPubkeyHex: String, ownerIdentityId: String) = mutate {
        it - key(ownerIdentityId, senderBlsPubkeyHex)
    }

    /** Forget every block an identity made, when the identity is removed. */
    fun removeOwner(ownerIdentityId: String) = mutate { entries ->
        entries.filterNot { it.startsWith("$ownerIdentityId$SEPARATOR") }.toSet()
    }

    private fun mutate(change: (Set<String>) -> Set<String>) {
        synchronized(lock) { storage.save(change(storage.load())) }
        _revision.value += 1
    }

    private fun key(owner: String, sender: String) = "$owner$SEPARATOR${sender.lowercase()}"

    private fun parse(key: String): Entry? {
        val parts = key.split(SEPARATOR)
        return if (parts.size == 2) Entry(parts[0], parts[1]) else null
    }

    companion object {
        private const val SEPARATOR = "|"
        private const val PREF_KEY = "blocked_senders_v1"

        fun sharedPreferences(prefs: SharedPreferences) = BlockedSenders(object : Storage {
            override fun load(): Set<String> = prefs.getStringSet(PREF_KEY, emptySet()).orEmpty().toSet()
            override fun save(entries: Set<String>) {
                prefs.edit().putStringSet(PREF_KEY, entries).apply()
            }
        })

        /** In memory, for tests and previews. */
        fun inMemory() = BlockedSenders(object : Storage {
            private var entries = emptySet<String>()
            override fun load() = entries
            override fun save(entries: Set<String>) {
                this.entries = entries
            }
        })
    }
}
