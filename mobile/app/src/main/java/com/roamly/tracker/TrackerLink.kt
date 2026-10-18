package com.roamly.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.roamly.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** GATT layout of the Roamly hardware tracker (trackerhw/firmware/src/ble.h). */
object TrackerUuids {
    val SERVICE: UUID = UUID.fromString("7f6c0001-8a2b-4c3d-9e5f-726f616d6c79")
    val STATUS: UUID = UUID.fromString("7f6c0002-8a2b-4c3d-9e5f-726f616d6c79")
    val SETTINGS: UUID = UUID.fromString("7f6c0003-8a2b-4c3d-9e5f-726f616d6c79")
    val COMMAND: UUID = UUID.fromString("7f6c0004-8a2b-4c3d-9e5f-726f616d6c79")
    val CONSOLE: UUID = UUID.fromString("7f6c0005-8a2b-4c3d-9e5f-726f616d6c79")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MANUFACTURER_ID = 0xFFFF
}

/** What the tracker's beacon says, without connecting. */
data class TrackerAdvert(
    val address: String,
    val name: String?,
    val flags: Int,
    val battery: Int?,
    val seq: Int,
    val intervalS: Int,
    val rssi: Int,
    val seenAtMs: Long,
) {
    val recording get() = flags and 0x01 != 0
    val trackerRecordsWhenTogether get() = flags and 0x02 != 0
    val charging get() = flags and 0x04 != 0
    val hasFix get() = flags and 0x08 != 0
    val pausedForPhone get() = flags and 0x10 != 0
    val userPaused get() = flags and 0x20 != 0
    val pairing get() = flags and 0x40 != 0
}

enum class LinkState { IDLE, WAITING, CONNECTING, CONNECTED }

/**
 * Bluetooth LE link to the Roamly hardware tracker — the one place in the app
 * that touches BLE.
 *
 * **Presence and hand-off.** The tracker advertises a short beacon window every
 * minute or so (it can't afford to keep its radio on). A low-power, filtered
 * scan run from the tracking service sees those windows; the tracker is *near*
 * while one was seen within [presenceTimeoutMs]. The beacon carries the
 * tracker's own state, so the hand-off needs no connection in the common case:
 *
 *  - set to *tracker records when together*: the phone pauses its own GPS while
 *    the tracker is near, recording, and has a fix ([phoneShouldPause]);
 *  - set to *phone records when together*: the phone connects every
 *    [LEASE_EVERY_MS] to renew a lease, and the tracker pauses while it holds
 *    one. When the phone goes away the lease simply expires and the tracker
 *    resumes by itself.
 *
 * Either way the device being carried is never the one paused: walking away
 * from the other one ends the hand-off within a few minutes on its own.
 *
 * **Sessions.** The Tracker tab holds a session open: connect at the next
 * beacon window, subscribe to live status, and keep the connection until the
 * screen goes away. Every characteristic requires an encrypted, authenticated
 * bond, created once with a passkey shown on the tracker's screen.
 */
@Singleton
class TrackerLink @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences,
) {
    companion object {
        private const val TAG = "TrackerLink"
        const val LEASE_EVERY_MS = 120_000L
        const val LEASE_TTL_S = 300
        private const val TICK_MS = 10_000L
        private const val OP_TIMEOUT_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val btManager get() = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()
    private val _advert = MutableStateFlow<TrackerAdvert?>(null)
    val advert: StateFlow<TrackerAdvert?> = _advert.asStateFlow()
    private val _near = MutableStateFlow(false)
    val near: StateFlow<Boolean> = _near.asStateFlow()
    private val _phoneShouldPause = MutableStateFlow(false)
    /** True while the phone should leave recording to the tracker. */
    val phoneShouldPause: StateFlow<Boolean> = _phoneShouldPause.asStateFlow()
    private val _linkState = MutableStateFlow(LinkState.IDLE)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()
    private val _status = MutableStateFlow<JSONObject?>(null)
    val status: StateFlow<JSONObject?> = _status.asStateFlow()
    private val _settings = MutableStateFlow<JSONObject?>(null)
    val settings: StateFlow<JSONObject?> = _settings.asStateFlow()
    private val _console = MutableStateFlow("")
    val console: StateFlow<String> = _console.asStateFlow()
    private val _reply = MutableStateFlow<String?>(null)
    val reply: StateFlow<String?> = _reply.asStateFlow()
    private val _pairCandidates = MutableStateFlow<List<TrackerAdvert>>(emptyList())
    val pairCandidates: StateFlow<List<TrackerAdvert>> = _pairCandidates.asStateFlow()
    private val _bonding = MutableStateFlow(false)
    val bonding: StateFlow<Boolean> = _bonding.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Set by the tracking service: leases are only renewed while the phone is
     *  actually recording, otherwise the tracker must keep recording. */
    @Volatile var phoneTracking = false

    private var presenceUsers = 0
    private var scanning = false
    private var pairScanning = false
    private var sessionWanted = false
    private var lastLeaseMs = 0L
    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    private var pendingOp: CompletableDeferred<Boolean>? = null
    private val consoleBuf = StringBuilder()

    init {
        scope.launch { prefs.trackerBleAddress.collect { _address.value = it } }
        scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    // ── Presence (scan) ─────────────────────────────────────────────────────

    /** Reference-counted: the tracking service and the Tracker tab each hold one. */
    fun startPresence() {
        presenceUsers++
        updateScan()
    }

    fun stopPresence() {
        presenceUsers = (presenceUsers - 1).coerceAtLeast(0)
        updateScan()
    }

    @SuppressLint("MissingPermission")
    private fun updateScan() {
        val want = (presenceUsers > 0 && _address.value != null) || pairScanning
        val scanner = adapter?.bluetoothLeScanner
        if (!hasPermissions() || scanner == null || !bluetoothOn()) {
            scanning = false
            return
        }
        if (want == scanning) return
        try {
            if (want) {
                // Filtered by service UUID, which keeps a long-running scan from
                // being demoted to opportunistic, and lets the controller do the
                // filtering. LOW_POWER (~0.5 s every ~5 s) is enough: a beacon
                // window lasts longer than one scan cycle.
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(TrackerUuids.SERVICE)).build()
                val mode = if (pairScanning || sessionWanted) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER
                val settings = ScanSettings.Builder().setScanMode(mode).build()
                scanner.startScan(listOf(filter), settings, scanCb)
            } else {
                scanner.stopScan(scanCb)
            }
            scanning = want
        } catch (e: Exception) {
            Log.w(TAG, "scan change failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartScan() {
        // Scan mode changes need a restart (Android caps starts at 5 per 30 s,
        // and this only happens when a session or pairing starts or ends).
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) {
            try { scanner.stopScan(scanCb) } catch (_: Exception) {}
            scanning = false
        }
        updateScan()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(r: ScanResult) {
        val md = r.scanRecord?.getManufacturerSpecificData(TrackerUuids.MANUFACTURER_ID) ?: return
        if (md.size < 4 || md[0].toInt() != 1) return
        val adv = TrackerAdvert(
            address = r.device.address,
            name = r.scanRecord?.deviceName,
            flags = md[1].toInt() and 0xFF,
            battery = (md[2].toInt() and 0xFF).takeIf { it <= 100 },
            seq = md[3].toInt() and 0xFF,
            intervalS = if (md.size >= 5) (md[4].toInt() and 0xFF) * 10 else 60,
            rssi = r.rssi,
            seenAtMs = System.currentTimeMillis(),
        )
        if (pairScanning) {
            val list = _pairCandidates.value.filterNot { it.address == adv.address } + adv
            _pairCandidates.value = list.sortedByDescending { it.rssi }
        }
        if (adv.address.equals(_address.value, ignoreCase = true)) {
            _advert.value = adv
            evaluate()
            // It's in a window right now: the moment to connect.
            if ((sessionWanted || leaseDue()) && gatt == null) connect(r.device)
        }
    }

    private fun presenceTimeoutMs(): Long {
        val interval = (_advert.value?.intervalS ?: 60).coerceAtLeast(30)
        return maxOf(200_000L, interval * 3_500L)
    }

    private fun evaluate() {
        val adv = _advert.value
        val near = adv != null && System.currentTimeMillis() - adv.seenAtMs < presenceTimeoutMs()
        _near.value = near
        // Pause only for a tracker that is demonstrably recording well: near,
        // recording, set to take over, and holding a fix. A tracker indoors with
        // no fix would otherwise leave a hole in the history.
        _phoneShouldPause.value = near && adv!!.recording && adv.trackerRecordsWhenTogether && adv.hasFix
    }

    private fun leaseDue(): Boolean {
        val adv = _advert.value ?: return false
        return phoneTracking && !adv.trackerRecordsWhenTogether &&
            System.currentTimeMillis() - lastLeaseMs > LEASE_EVERY_MS
    }

    private fun tick() {
        evaluate()
        updateScan()
    }

    // ── Session (the Tracker tab) ───────────────────────────────────────────

    fun startSession() {
        sessionWanted = true
        startPresence()
        restartScan()
        if (_linkState.value == LinkState.IDLE) _linkState.value = LinkState.WAITING
    }

    fun endSession() {
        sessionWanted = false
        stopPresence()
        restartScan()
        disconnect()
    }

    // ── GATT ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) return
        _linkState.value = LinkState.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        pendingOp?.complete(false)
        _linkState.value = if (sessionWanted) LinkState.WAITING else LinkState.IDLE
    }

    private val gattCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                scope.launch { setUp(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                pendingOp?.complete(false)
                _linkState.value = if (sessionWanted) LinkState.WAITING else LinkState.IDLE
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { pendingOp?.complete(true) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, c.value ?: ByteArray(0))
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onValue(c.uuid, value)
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onValue(c.uuid, c.value ?: ByteArray(0))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onValue(c.uuid, value)
        }
    }

    /** Android allows one GATT operation in flight; run them one at a time. */
    private suspend fun op(block: () -> Boolean): Boolean = opMutex.withLock {
        val d = CompletableDeferred<Boolean>()
        pendingOp = d
        if (!block()) { pendingOp = null; return false }
        val ok = withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() } ?: false
        pendingOp = null
        ok
    }

    @SuppressLint("MissingPermission")
    private suspend fun setUp(g: BluetoothGatt) {
        op { g.requestMtu(517) }
        if (!op { g.discoverServices() }) { _lastError.value = "Couldn't read the tracker's services"; disconnect(); return }
        val svc = g.getService(TrackerUuids.SERVICE)
        if (svc == null) { _lastError.value = "Not a Roamly tracker"; disconnect(); return }
        // The first encrypted access is what makes Android bring up the bond's
        // encryption; it can fail once while that happens, so retry.
        for (uuid in listOf(TrackerUuids.STATUS, TrackerUuids.SETTINGS, TrackerUuids.COMMAND, TrackerUuids.CONSOLE)) {
            val c = svc.getCharacteristic(uuid) ?: continue
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(TrackerUuids.CCCD) ?: continue
            var ok = false
            for (attempt in 0 until 3) {
                ok = op { writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                if (ok) break
                delay(600)
            }
            if (!ok) { _lastError.value = "Tracker refused the connection — pair it again"; disconnect(); return }
        }
        _linkState.value = LinkState.CONNECTED
        _lastError.value = null
        svc.getCharacteristic(TrackerUuids.SETTINGS)?.let { c -> op { g.readCharacteristic(c) } }
        svc.getCharacteristic(TrackerUuids.STATUS)?.let { c -> op { g.readCharacteristic(c) } }
        if (leaseDue() || (_advert.value?.trackerRecordsWhenTogether == false && phoneTracking)) sendLease(g)
        if (!sessionWanted) {
            // A lease-only visit: get off the air so the tracker can sleep.
            delay(1500)
            disconnect()
        }
    }

    private fun sendLease(g: BluetoothGatt) {
        val json = JSONObject()
            .put("cmd", "lease")
            .put("ttl", LEASE_TTL_S)
            .put("t", System.currentTimeMillis() / 1000)
        scope.launch {
            if (write(g, TrackerUuids.COMMAND, json.toString())) lastLeaseMs = System.currentTimeMillis()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    @SuppressLint("MissingPermission")
    private suspend fun write(g: BluetoothGatt, uuid: UUID, text: String): Boolean {
        val c = g.getService(TrackerUuids.SERVICE)?.getCharacteristic(uuid) ?: return false
        val bytes = text.toByteArray()
        return op {
            if (Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    private fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            TrackerUuids.STATUS -> parse(value)?.let { _status.value = it }
            TrackerUuids.SETTINGS -> parse(value)?.let { _settings.value = it }
            TrackerUuids.COMMAND -> _reply.value = String(value)
            TrackerUuids.CONSOLE -> {
                // Streamed in MTU-sized pieces; 0x04 ends a reply.
                if (value.size == 1 && value[0].toInt() == 0x04) {
                    _console.value = consoleBuf.toString()
                    consoleBuf.clear()
                } else consoleBuf.append(String(value))
            }
        }
    }

    private fun parse(v: ByteArray): JSONObject? =
        try { JSONObject(String(v)) } catch (_: Exception) { null }

    // ── Commands from the UI ────────────────────────────────────────────────

    fun command(cmd: String) {
        val g = gatt ?: return
        scope.launch { write(g, TrackerUuids.COMMAND, JSONObject().put("cmd", cmd).toString()) }
    }

    fun setSetting(key: String, value: Any) {
        val g = gatt ?: return
        scope.launch { write(g, TrackerUuids.SETTINGS, JSONObject().put(key, value).toString()) }
    }

    fun runConsole(line: String) {
        val g = gatt ?: return
        consoleBuf.clear()
        _console.value = "…"
        scope.launch { write(g, TrackerUuids.CONSOLE, line) }
    }

    // ── Pairing ─────────────────────────────────────────────────────────────

    fun startPairScan() {
        _pairCandidates.value = emptyList()
        pairScanning = true
        restartScan()
    }

    fun stopPairScan() {
        pairScanning = false
        restartScan()
    }

    /** Bond with a tracker in pairing mode. Android shows the passkey prompt;
     *  the code is on the tracker's screen. */
    @SuppressLint("MissingPermission")
    fun pair(adv: TrackerAdvert) {
        val device = adapter?.getRemoteDevice(adv.address) ?: return
        _bonding.value = true
        _lastError.value = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != adv.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> {
                        _bonding.value = false
                        scope.launch { prefs.setTrackerBle(adv.address, adv.name ?: "Roamly Tracker") }
                        stopPairScan()
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    }
                    BluetoothDevice.BOND_NONE -> {
                        _bonding.value = false
                        _lastError.value = "Pairing didn't complete — check the code on the tracker and try again"
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            scope.launch { prefs.setTrackerBle(adv.address, adv.name ?: "Roamly Tracker") }
            _bonding.value = false
            stopPairScan()
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            return
        }
        if (!device.createBond()) {
            _bonding.value = false
            _lastError.value = "Couldn't start pairing"
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    /** Forget the tracker on the phone side (and ask it to forget us, if connected). */
    @SuppressLint("MissingPermission")
    fun unpair() {
        command("forget")
        val addr = _address.value
        scope.launch {
            delay(500)
            disconnect()
            prefs.setTrackerBle(null, null)
            _advert.value = null
            _status.value = null
            _settings.value = null
            evaluate()
            // Remove the system bond too (hidden API; best effort).
            try {
                val dev = addr?.let { adapter?.getRemoteDevice(it) }
                dev?.javaClass?.getMethod("removeBond")?.invoke(dev)
            } catch (_: Exception) {}
        }
    }
}
