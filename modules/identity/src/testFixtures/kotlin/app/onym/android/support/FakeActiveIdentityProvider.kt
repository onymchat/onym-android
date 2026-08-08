package app.onym.android.support

import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Test-only [ActiveIdentityProvider] backed by a [MutableStateFlow]
 * the test can mutate to simulate identity selection / removal.
 *
 * Usage:
 *
 * ```kotlin
 * val active = FakeActiveIdentityProvider()
 * active.setActive(IdentityId("alice"))
 * val repo = GroupRepository(store, active, scope = TestScope())
 * ```
 *
 * Removal listeners are invoked via [emitRemoval] so tests can
 * deterministically trigger the cascade-delete path.
 */
class FakeActiveIdentityProvider(
    initial: IdentityId? = null,
) : ActiveIdentityProvider {

    private val _currentIdentityId = MutableStateFlow(initial)
    override val currentIdentityId = _currentIdentityId
    private var listener: (suspend (IdentityId) -> Unit)? = null

    fun setActive(id: IdentityId?) {
        _currentIdentityId.value = id
    }

    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {
        this.listener = listener
    }

    /** Invoke the registered removal listener (no-op when unset). */
    suspend fun emitRemoval(id: IdentityId) {
        listener?.invoke(id)
    }
}
