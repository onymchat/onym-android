package chat.onym.android.support

import chat.onym.android.group.IntroKeyEntry
import chat.onym.android.group.IntroKeyStore
import chat.onym.android.identity.IdentityId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reusable in-memory [IntroKeyStore]. Same contract as
 * [chat.onym.android.group.EncryptedPrefsIntroKeyStore] without the
 * EncryptedSharedPreferences plumbing — fast tests of
 * `InviteIntroducer` / future request-flow interactors that don't
 * want to stand up the Keystore.
 */
class InMemoryIntroKeyStore : IntroKeyStore {

    private val mutex = Mutex()
    private val entries = mutableListOf<IntroKeyEntry>()

    override suspend fun save(entry: IntroKeyEntry) = mutex.withLock {
        entries.removeAll { it.introPublicKey.contentEquals(entry.introPublicKey) }
        entries += entry
    }

    override suspend fun find(introPublicKey: ByteArray): IntroKeyEntry? = mutex.withLock {
        entries.firstOrNull { it.introPublicKey.contentEquals(introPublicKey) }
    }

    override suspend fun listForOwner(ownerIdentityId: IdentityId): List<IntroKeyEntry> = mutex.withLock {
        entries
            .filter { it.ownerIdentityId == ownerIdentityId }
            .sortedByDescending { it.createdAtMillis }
    }

    override suspend fun revoke(introPublicKey: ByteArray) {
        mutex.withLock {
            entries.removeAll { it.introPublicKey.contentEquals(introPublicKey) }
        }
    }

    override suspend fun deleteForOwner(ownerIdentityId: IdentityId): Int = mutex.withLock {
        val before = entries.size
        entries.removeAll { it.ownerIdentityId == ownerIdentityId }
        before - entries.size
    }
}
