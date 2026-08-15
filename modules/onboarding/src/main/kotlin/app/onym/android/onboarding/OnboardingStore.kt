package app.onym.android.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Persistence seam for the one bit the onboarding gate needs: has the
 * user completed (or been walked through) the first-launch flow.
 *
 * Mirrors onym-ios `OnboardingStore` (PR #249), suspend-based because
 * the production backing store is DataStore.
 */
interface OnboardingStore {
    /** Whether the completion flag is set. */
    suspend fun hasCompleted(): Boolean

    /** Set the completion flag. Idempotent. ALSO clears any pending
     *  [requestRestart] — completing the walk satisfies the request. */
    suspend fun markCompleted()

    /**
     * Clear the completion flag, so the next launch onboards again.
     * The app's test reset path calls this instead of hardcoding the
     * storage key. Idempotent.
     */
    suspend fun reset()

    /**
     * Whether the user explicitly asked to re-run onboarding
     * (Settings → Restart Onboarding). Distinct from a merely-absent
     * completion flag: the gate treats an explicit request as
     * authoritative and SKIPS the grandfathering probe — a configured
     * user is by definition "grandfathered", so without this bit the
     * probe would immediately cancel the restart they just asked for.
     * Persisted, so an app killed mid-restart-walk resumes onboarding
     * on the next launch.
     */
    suspend fun isRestartRequested(): Boolean

    /** Record an explicit restart: clears the completion flag and
     *  sets the restart bit (cleared again by [markCompleted]).
     *  Idempotent. */
    suspend fun requestRestart()
}

/**
 * Production [OnboardingStore]. Single boolean under the
 * `onboarding.` preference-key prefix, following the module-prefixed
 * key convention of :discovery (`discovery.*`) and :chain.
 */
class DataStorePreferencesOnboardingStore(
    private val dataStore: DataStore<Preferences>,
) : OnboardingStore {

    override suspend fun hasCompleted(): Boolean =
        dataStore.data.first()[KEY_COMPLETED] ?: false

    override suspend fun markCompleted() {
        dataStore.edit {
            it[KEY_COMPLETED] = true
            // The walk the user asked for just finished.
            it.remove(KEY_RESTART_REQUESTED)
        }
    }

    override suspend fun reset() {
        dataStore.edit { it.remove(KEY_COMPLETED) }
    }

    override suspend fun isRestartRequested(): Boolean =
        dataStore.data.first()[KEY_RESTART_REQUESTED] ?: false

    override suspend fun requestRestart() {
        dataStore.edit {
            it.remove(KEY_COMPLETED)
            it[KEY_RESTART_REQUESTED] = true
        }
    }

    internal companion object {
        /** Internal so tests can assert the exact key the app's reset
         *  path must clear. */
        internal val KEY_COMPLETED = booleanPreferencesKey("onboarding.completed")

        /** Explicit Settings → Restart Onboarding request; survives
         *  process death so a mid-restart-walk kill resumes. */
        internal val KEY_RESTART_REQUESTED =
            booleanPreferencesKey("onboarding.restartRequested")
    }
}

/**
 * The launch-time gate decision, including grandfathering: users who
 * predate onboarding have no completion flag but plenty of configured
 * state, and must not be onboarded as if the app were fresh.
 */
object OnboardingGate {
    /**
     * Whether first-launch onboarding should be presented.
     *
     * - [store] holds the completion flag written by
     *   [OnboardingFlow.complete].
     * - [isExistingUser] is the injected existing-user signal — the
     *   app passes "any `hasUserInteracted` is true or a non-empty
     *   configuration exists". It is only consulted when the flag is
     *   absent, so the (potentially costlier) probe never runs on the
     *   steady-state path.
     *
     * Either signal — completed flag OR existing-user state — means NO
     * onboarding. The gate is a pure read: it never writes the flag
     * itself (completion is [OnboardingFlow.complete]'s job), so a
     * grandfathered user simply answers false here on every launch.
     */
    suspend fun shouldOnboard(
        store: OnboardingStore,
        isExistingUser: suspend () -> Boolean,
    ): Boolean {
        // Explicit Settings → Restart Onboarding request: the user's
        // own intent overrides BOTH negative signals below. In
        // particular the grandfathering probe must not run — a user
        // asking to re-run setup is, by definition, a configured
        // (grandfathered) user, and the probe would immediately
        // cancel the restart. Cold-boot grandfathering is untouched:
        // this bit is only ever set by the explicit Settings action.
        if (store.isRestartRequested()) return true
        if (store.hasCompleted()) return false
        if (isExistingUser()) return false
        return true
    }
}
