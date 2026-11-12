package app.onym.android.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.onym.android.chats.NearbyBroadcaster
import app.onym.android.group.GroupStore
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentityRepository
import app.onym.android.inbox.IncomingInvitationsInteractor
import app.onym.android.inbox.IncomingInvitationsRepository
import app.onym.android.inbox.IncomingMessageDispatcher
import app.onym.android.mesh.BleMeshBroadcaster
import app.onym.android.mesh.BleMeshScanner
import app.onym.android.mesh.MeshContact
import app.onym.android.mesh.MeshInboxTransport
import app.onym.android.mesh.MeshOutbox
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The two Bluetooth switches, both off by default. Plain SharedPreferences:
 * the foreground service and the composition root read them synchronously.
 */
class MeshSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app.onym.android.mesh", Context.MODE_PRIVATE)
    private val _listen = MutableStateFlow(prefs.getBoolean(KEY_LISTEN, false))
    private val _broadcast = MutableStateFlow(prefs.getBoolean(KEY_BROADCAST, false))

    /** Receive messages broadcast nearby. Silent: never transmits. */
    val listen: StateFlow<Boolean> = _listen.asStateFlow()

    /** Also put this device's text messages on the air — the station role. */
    val broadcast: StateFlow<Boolean> = _broadcast.asStateFlow()

    fun setListen(on: Boolean) {
        prefs.edit().putBoolean(KEY_LISTEN, on).apply()
        _listen.value = on
    }

    fun setBroadcast(on: Boolean) {
        prefs.edit().putBoolean(KEY_BROADCAST, on).apply()
        _broadcast.value = on
    }

    private companion object {
        const val KEY_LISTEN = "listen"
        const val KEY_BROADCAST = "broadcast"
    }
}

/**
 * Runs the Bluetooth mesh for this device (onym-ios docs/mesh-ble-v1.md):
 *
 *  - **Listening** — a receive-only [MeshInboxTransport] fed by the
 *    scanner, fanned out per identity into the same dispatcher the relays
 *    feed. What it delivers is marked Bluetooth, so no receipt goes back.
 *  - **Broadcasting** — as the [NearbyBroadcaster] for text sends: each
 *    sealed envelope is queued on the [MeshOutbox] under the
 *    (sender, recipient) pair's rotating tag, and [BleMeshBroadcaster]
 *    loops the outbox as non-connectable advertisements.
 *
 * Either one running keeps [MeshService] in the foreground, so the radio
 * survives the screen going off. Without the Bluetooth permissions the
 * switches stay set but the radio stays off until they're granted.
 */
class MeshController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository,
    private val groupStore: GroupStore,
    private val invitationsRepository: IncomingInvitationsRepository,
    val settings: MeshSettings = MeshSettings(context),
) : NearbyBroadcaster {

    private val outbox = MeshOutbox()
    private val broadcaster = BleMeshBroadcaster(context, outbox)
    private var dispatcher: IncomingMessageDispatcher? = null
    private var listening: Job? = null

    /** Starts following the switches. Call once, with the app's dispatcher. */
    fun start(dispatcher: IncomingMessageDispatcher) {
        this.dispatcher = dispatcher
        scope.launch {
            combine(settings.listen, settings.broadcast) { l, b -> l to b }.collect { (l, b) -> apply(l, b) }
        }
    }

    /** Re-apply after the user answers the permission prompt. */
    fun refresh() = apply(settings.listen.value, settings.broadcast.value)

    @Synchronized
    private fun apply(listen: Boolean, broadcast: Boolean) {
        val permitted = hasRadioPermissions(context)
        val dispatcher = dispatcher
        if (listen && permitted && dispatcher != null) {
            if (listening?.isActive != true) listening = scope.launch { listen(dispatcher) }
        } else {
            listening?.cancel()
            listening = null
        }
        if (broadcast && permitted) broadcaster.start(scope) else broadcaster.stop()
        if ((listen || broadcast) && permitted) MeshService.start(context) else MeshService.stop(context)
    }

    override suspend fun broadcast(sealedEnvelope: ByteArray, sender: IdentityId, recipientInboxKey: ByteArray) {
        if (!settings.broadcast.value) return
        val pairKey = identityRepository.meshPairKey(sender, recipientInboxKey)
        outbox.enqueue(sealedEnvelope, pairKey)
    }

    private suspend fun listen(dispatcher: IncomingMessageDispatcher) {
        val transport = MeshInboxTransport(
            frames = BleMeshScanner(context).frames(),
            contacts = { contacts() },
            scope = scope,
        )
        transport.connect(emptyList())
        try {
            IncomingInvitationsInteractor(
                inboxTransport = transport,
                repository = invitationsRepository,
                dispatcher = dispatcher,
            ).runFanout(
                identityRepository.identities.map { summaries ->
                    summaries.map { it.id to TransportInboxId(IdentityRepository.inboxTag(it.inboxPublicKey)) }
                },
            )
        } finally {
            transport.disconnect()
        }
    }

    /**
     * Every (local identity, group member) pair, keyed for the mesh. Only
     * people this identity shares a group with have a pair key, so a
     * stranger's frames never match.
     */
    private suspend fun contacts(): List<MeshContact> {
        val groups = groupStore.list()
        val contacts = ArrayList<MeshContact>()
        for (summary in identityRepository.identities.value) {
            val inbox = TransportInboxId(IdentityRepository.inboxTag(summary.inboxPublicKey))
            val peers = groups
                .filter { it.ownerIdentityId == summary.id.value }
                .flatMap { it.memberProfiles.values.map { p -> p.inboxPublicKey } }
                .filterNot { it.contentEquals(summary.inboxPublicKey) }
                .distinctBy { it.toList() }
            for (peer in peers) {
                runCatching { identityRepository.meshPairKey(summary.id, peer) }
                    .onSuccess { contacts += MeshContact(inbox, it) }
            }
        }
        return contacts
    }

    companion object {
        /** What the switches need: scan + advertise on 12+, location for scanning before it. */
        fun radioPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasRadioPermissions(context: Context): Boolean = radioPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
