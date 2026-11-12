package app.onym.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/** The mesh's 128-bit service UUID, for v1 frames in Service Data (spec §3). */
val MESH_SERVICE_UUID: UUID = UUID.fromString("76797324-4B57-4664-8E4D-53F4C3D38BC4")

private const val TAG = "OnymMesh"

/**
 * Listens for mesh frames: compact ones in Manufacturer Specific Data under
 * company `0xFFFF`, v1 ones in the mesh Service Data. Never connects.
 *
 * Listening sends nothing on the air: a scanner only sends scan requests to
 * scannable advertisements, and mesh broadcasters must not be scannable.
 * Requires `BLUETOOTH_SCAN` (declared `neverForLocation`); without it, or
 * with Bluetooth off, the flow simply yields nothing.
 */
class BleMeshScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun frames(): Flow<ByteArray> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "no Bluetooth LE scanner; is Bluetooth on?")
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                frame(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> frame(r)?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "mesh scan failed: $errorCode")
            }
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(MeshFrameCodec.COMPANY_ID, byteArrayOf(MeshFrameCodec.COMPACT_KIND), byteArrayOf(0xFF.toByte()))
                .build(),
            ScanFilter.Builder().setServiceData(ParcelUuid(MESH_SERVICE_UUID), null).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .apply { if (adapter.isLeExtendedAdvertisingSupported) setLegacy(false) }
            .build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to scan", e)
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
                // Bluetooth turned off underneath us.
            }
        }
    }

    private fun frame(result: ScanResult): ByteArray? {
        val record = result.scanRecord ?: return null
        record.getManufacturerSpecificData(MeshFrameCodec.COMPANY_ID)
            ?.takeIf { it.firstOrNull() == MeshFrameCodec.COMPACT_KIND }
            ?.let { return it }
        return record.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
    }
}

/**
 * Puts [outbox] on the air — the station half of the mesh.
 *
 * One legacy advertising set, non-connectable and non-scannable (spec §3),
 * whose data is swapped to the next compact frame every [frameInterval]
 * milliseconds. Nothing comes back: no connection can be made to it and no
 * scan request sent, so it never learns who heard what. The set is
 * restarted at every epoch boundary so the random device address rotates
 * along with the tags — one address must not link two epochs.
 *
 * Requires `BLUETOOTH_ADVERTISE`.
 */
class BleMeshBroadcaster(
    private val context: Context,
    private val outbox: MeshOutbox,
    private val frameInterval: Long = 200,
    private val now: () -> Instant = Instant::now,
) {
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.run() {
        val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "no Bluetooth LE advertiser; is Bluetooth on?")
            return
        }
        var set: AdvertisingSet? = null
        var setEpoch = -1L
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) set = advertisingSet else Log.w(TAG, "advertising failed: $status")
            }
        }
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        fun stopSet() {
            try {
                advertiser.stopAdvertisingSet(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
            set = null
            setEpoch = -1
        }

        try {
            while (isActive) {
                val frame = outbox.next()
                if (frame == null) {
                    if (setEpoch >= 0) stopSet()
                    delay(1_000)
                    continue
                }
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addManufacturerData(MeshFrameCodec.COMPANY_ID, frame)
                    .build()
                val epoch = MeshEpoch.number(now())
                if (setEpoch != epoch) {
                    // New epoch (or first frame): a fresh set, so a fresh address.
                    if (setEpoch >= 0) stopSet()
                    try {
                        advertiser.startAdvertisingSet(parameters, data, null, null, null, callback)
                        setEpoch = epoch
                    } catch (e: SecurityException) {
                        Log.w(TAG, "no permission to advertise", e)
                        return
                    }
                } else {
                    set?.setAdvertisingData(data)
                }
                delay(frameInterval)
            }
        } finally {
            stopSet()
        }
    }
}
