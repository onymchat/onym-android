package app.onym.android.chain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * User's selected Stellar network for new groups. Defaults to
 * [Testnet] because the v0.0.3 contracts only ship there today;
 * [Mainnet] is reachable via the Settings → Network toggle once
 * real contracts land on it.
 *
 * Mirrors `AppNetwork` from onym-ios PR #27.
 */
enum class AppNetwork {
    Testnet,
    Mainnet,
    ;

    /** Bridges to [ContractNetwork] (used by [AnchorSelectionKey] for
     *  the contracts manifest). The wire spelling is `public` for
     *  mainnet — see [SepNetwork]. */
    val contractNetwork: ContractNetwork
        get() = when (this) {
            Testnet -> ContractNetwork.Testnet
            Mainnet -> ContractNetwork.Public
        }

    val sepNetwork: SepNetwork
        get() = when (this) {
            Testnet -> SepNetwork.TESTNET
            Mainnet -> SepNetwork.PUBLIC_NET
        }
}

/**
 * Read-only seam over whichever store backs the user's preference.
 * [CreateGroupInteractor] depends on this rather than DataStore so
 * tests can swap it without touching IO.
 *
 * Mirrors `NetworkPreferenceProviding` from onym-ios PR #27.
 */
interface NetworkPreferenceProvider {
    /** Synchronous one-shot read. Production impl reads through
     *  DataStore in a `runBlocking` — fine for the create-group
     *  interactor which already runs in a coroutine. */
    fun current(): AppNetwork

    /** Reactive read for the Settings UI. Production impl maps the
     *  underlying Preferences flow; test impl emits a single
     *  constant value. */
    val flow: Flow<AppNetwork>

    /** Persist a new selection. */
    suspend fun set(network: AppNetwork)
}

/**
 * Production impl — backed by DataStore Preferences under the same
 * `onym.useMainnet` key the iOS twin reads via
 * `@AppStorage("onym.useMainnet")`. Cross-platform settings sync
 * (future) reads/writes the same key on each platform.
 *
 * Mirrors `UserDefaultsNetworkPreference` from onym-ios PR #27.
 */
class DataStoreNetworkPreferenceProvider(
    private val dataStore: DataStore<Preferences>,
) : NetworkPreferenceProvider {

    override fun current(): AppNetwork = runBlocking {
        val on = dataStore.data.first()[USE_MAINNET] ?: false
        if (on) AppNetwork.Mainnet else AppNetwork.Testnet
    }

    override val flow: Flow<AppNetwork> = dataStore.data.map { prefs ->
        if (prefs[USE_MAINNET] == true) AppNetwork.Mainnet else AppNetwork.Testnet
    }

    override suspend fun set(network: AppNetwork) {
        dataStore.edit { prefs ->
            prefs[USE_MAINNET] = (network == AppNetwork.Mainnet)
        }
    }

    companion object {
        /** Pinned: this key MUST match
         *  `UserDefaultsNetworkPreference.storageKey` on iOS so a
         *  future cross-platform settings sync stays consistent. */
        val USE_MAINNET = booleanPreferencesKey("onym.useMainnet")
    }
}

/** Test fake — returns whatever was passed in. */
class StaticNetworkPreferenceProvider(private val value: AppNetwork) : NetworkPreferenceProvider {
    override fun current(): AppNetwork = value
    override val flow: Flow<AppNetwork> = kotlinx.coroutines.flow.flowOf(value)
    override suspend fun set(network: AppNetwork) {
        throw UnsupportedOperationException("StaticNetworkPreferenceProvider is read-only")
    }
}
