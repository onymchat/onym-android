package chat.onym.android.support

import chat.onym.android.group.IntroKeyEntry
import chat.onym.android.group.IntroKeyStore
import chat.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable in-memory [IntroKeyStore]. Same contract as
 * [chat.onym.android.group.EncryptedPrefsIntroKeyStore] without the
 * EncryptedSharedPreferences plumbing — fast tests of
 * `InviteIntroducer` / future request-flow interactors that don't
 * want to stand up the Keystore.
 */
class InMemoryIntroKeyStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : IntroKeyStore {

    private val mutex = Mutex()
    private val entries = mutableListOf<IntroKeyEntry>()

    private val _entriesFlow = MutableStateFlow<List<IntroKeyEntry>>(emptyList())
    override val entriesFlow: StateFlow<List<IntroKeyEntry>> = _entriesFlow.asStateFlow()

    override suspend fun save(entry: IntroKeyEntry) = mutex.withLock {
        purgeExpiredUnlocked()
        entries.removeAll { it.introPublicKey.contentEquals(entry.introPublicKey) }
        entries += entry
        _entriesFlow.value = entries.toList()
    }

    override suspend fun find(introPublicKey: ByteArray): IntroKeyEntry? = mutex.withLock {
        purgeExpiredUnlocked()
        entries.firstOrNull { it.introPublicKey.contentEquals(introPublicKey) }
    }

    override suspend fun listForOwner(ownerIdentityId: IdentityId): List<IntroKeyEntry> = mutex.withLock {
        purgeExpiredUnlocked()
        entries
            .filter { it.ownerIdentityId == ownerIdentityId }
            .sortedByDescending { it.createdAtMillis }
    }

    override suspend fun revoke(introPublicKey: ByteArray) {
        mutex.withLock {
            val changed = entries.removeAll { it.introPublicKey.contentEquals(introPublicKey) }
            if (changed) _entriesFlow.value = entries.toList()
        }
    }

    override suspend fun deleteForOwner(ownerIdentityId: IdentityId): Int = mutex.withLock {
        val before = entries.size
        entries.removeAll { it.ownerIdentityId == ownerIdentityId }
        val removed = before - entries.size
        if (removed > 0) _entriesFlow.value = entries.toList()
        removed
    }

    /** Drop entries whose [IntroKeyEntry.createdAtMillis] is older
     *  than [IntroKeyEntry.LIFETIME_MILLIS] relative to the
     *  injected [clock] and re-emit [entriesFlow] so the
     *  [chat.onym.android.group.IntroInboxPump] reconciler can
     *  cancel relayer subscriptions for expired slots. */
    private fun purgeExpiredUnlocked() {
        val nowMillis = clock()
        val before = entries.size
        entries.removeAll { it.isExpired(nowMillis) }
        if (entries.size != before) _entriesFlow.value = entries.toList()
    }
}
