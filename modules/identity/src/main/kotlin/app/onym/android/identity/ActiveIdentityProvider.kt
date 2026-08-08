package app.onym.android.identity

import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only seam onto the currently-selected identity. Layers
 * downstream of [IdentityRepository] (`GroupRepository`,
 * `InboxTransport`, etc.) depend on this rather than the full
 * concrete repository so they can be tested with a stub flow.
 *
 * - [currentIdentityId] is the same hot stream
 *   `IdentityRepository.currentIdentityId` exposes — null when no
 *   identity is selected.
 * - [registerRemovalListener] hooks the `setRemovalListener` slot
 *   on the concrete repository. Single-listener — last writer wins.
 *   Pass `null` to clear.
 */
interface ActiveIdentityProvider {
    val currentIdentityId: StateFlow<IdentityId?>
    fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?)
}
