package app.onym.android.naming

import android.content.SharedPreferences
import app.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the viewer's consent is persisted. Names and private keys are never stored here. */
class NamingController(
    private val preferences: SharedPreferences,
    private val holder: suspend (IdentityId) -> Holder,
) {
    data class Holder(val subject: String, val stellarAccount: String, val sign: suspend (ByteArray) -> ByteArray)
    val client = BsnNamingClient()
    private val consent = MutableStateFlow(preferences.all.filterValues { it == true }.keys.toSet())
    val enabled = consent.asStateFlow()
    fun enable(owner: IdentityId, value: Boolean) {
        preferences.edit().putBoolean(owner.value, value).apply()
        consent.value = if (value) consent.value + owner.value else consent.value - owner.value
    }
    fun remove(owner: IdentityId) {
        enable(owner, false)
        preferences.edit().remove(owner.value).apply()
    }
    suspend fun holder(owner: IdentityId) = holder.invoke(owner)
}
