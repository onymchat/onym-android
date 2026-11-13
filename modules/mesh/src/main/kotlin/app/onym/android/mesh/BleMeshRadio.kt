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

/**
 * The 16-bit UUID coded frames travel under (spec §4c), both listed and
 * keying their Service Data: the list is what a locked iPhone's filtered
 * scan matches, and a 16-bit one leaves room for the frame.
 */
val MESH_CODED_UUID: ParcelUuid = ParcelUuid.fromString("00004F4E-0000-1000-8000-00805F9B34FB")

private const val TAG = "OnymMesh"

/**
 * Listens for mesh frames: coded ones in Service Data under
 * [MESH_CODED_UUID], compact ones in Manufacturer Specific Data under
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
            ScanFilter.Builder().setServiceData(MESH_CODED_UUID, byteArrayOf(MeshCodedFrame.KIND), byteArrayOf(0xFF.toByte())).build(),
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
        record.getServiceData(MESH_CODED_UUID)
            ?.takeIf { it.firstOrNull() == MeshCodedFrame.KIND }
            ?.let { return it }
        record.getManufacturerSpecificData(MeshFrameCodec.COMPANY_ID)
            ?.takeIf { it.firstOrNull() == MeshFrameCodec.COMPACT_KIND }
            ?.let { return it }
        return record.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
    }
}

/**
 * Puts [outbox] on the air — the station half of the mesh.
 *
 * Up to [lanes] legacy advertising sets at once, each non-connectable and
 * non-scannable (spec §3), each cycling its own stream of coded frames
 * every [frameInterval] ms. More sets is more frames on the air per
 * second. Nothing comes back: no connection can be made and no scan
 * request sent. A set the controller refuses (too many advertisers) is
 * dropped and the rest carry on.
 *
 * Every [framesPerAddress] frames, and at each epoch boundary, a lane
 * starts a fresh set, so a fresh random address: a locked iPhone's scan
 * reports each advertiser once however often its data changes, so a
 * frame on a new address is a frame it can hear.
 *
 * Requires `BLUETOOTH_ADVERTISE`.
 */
class BleMeshBroadcaster(
    private val context: Context,
    private val outbox: MeshOutbox,
    private val frameInterval: Long = 200,
    private val lanes: Int = 3,
    private val framesPerAddress: Int = 1,
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

    private inner class Lane(val number: Int) {
        @Volatile var set: AdvertisingSet? = null
        @Volatile var refused = false
        var epoch = -1L
        var framesOnAddress = 0
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                when (status) {
                    ADVERTISE_SUCCESS -> set = advertisingSet
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        refused = true
                        Log.i(TAG, "lane $number refused: too many advertisers; carrying on with fewer")
                    }
                    else -> Log.w(TAG, "lane $number advertising failed: $status")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun CoroutineScope.run() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "no Bluetooth LE advertiser; is Bluetooth on?")
            return
        }
        val laneCount = if (adapter.isMultipleAdvertisementSupported) lanes.coerceAtLeast(1) else 1
        val all = List(laneCount) { Lane(it) }
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        fun stop(lane: Lane) {
            try {
                advertiser.stopAdvertisingSet(lane.callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
            lane.set = null
            lane.epoch = -1
            lane.framesOnAddress = 0
        }

        try {
            while (isActive) {
                val live = all.filterNot { it.refused }
                var sent = 0
                for (lane in live) {
                    val frame = outbox.next() ?: break
                    sent++
                    val data = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(MESH_CODED_UUID)
                        .addServiceData(MESH_CODED_UUID, frame)
                        .build()
                    val epoch = MeshEpoch.number(now())
                    if (lane.epoch != epoch || lane.framesOnAddress >= framesPerAddress) {
                        // A fresh set, so a fresh address.
                        if (lane.epoch >= 0) stop(lane)
                        if (lane.epoch != epoch) {
                            Log.i(TAG, "lane ${lane.number}: epoch $epoch (${outbox.size} envelopes queued)")
                        }
                        try {
                            advertiser.startAdvertisingSet(parameters, data, null, null, null, lane.callback)
                            lane.epoch = epoch
                            lane.framesOnAddress = 1
                        } catch (e: SecurityException) {
                            Log.w(TAG, "no permission to advertise", e)
                            return
                        } catch (e: IllegalArgumentException) {
                            Log.w(TAG, "lane ${lane.number} could not start", e)
                        }
                    } else {
                        lane.set?.setAdvertisingData(data)
                        lane.framesOnAddress++
                    }
                }
                if (sent == 0) {
                    all.filter { it.epoch >= 0 }.forEach(::stop)
                    delay(1_000)
                } else {
                    delay(frameInterval)
                }
            }
        } finally {
            all.forEach(::stop)
        }
    }
}
