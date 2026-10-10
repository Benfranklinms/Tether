package dev.benfranklin.tether.ble

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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.core.util.Backoff
import dev.benfranklin.tether.log.Log
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The phone's side of the Bluetooth link: a GATT central that finds the Mac, connects,
 * raises the MTU, subscribes to notifications and then moves fragments both ways with
 * write-without-response flow control.
 *
 * Every callback is hopped onto [engine] (the single thread that owns the controller), so
 * this class is effectively single-threaded.
 *
 * Finding the Mac: while the app is on screen the scan is unfiltered and matched in software
 * (Tether's service UUID anywhere in the advertisement or scan response, or the local name
 * "Tether"), because some phones' hardware filters miss 128-bit UUIDs that macOS puts in the
 * scan response. Off screen the scan is filtered (service UUID or name) at low power, as
 * Android requires for screen-off scanning. Scan starts are rate-limited to stay under
 * Android's "5 starts per 30 s" throttle, which otherwise silently returns no results.
 *
 * Everything that happens is reported as a [Diag] so the UI can say what is going on and a
 * failed pairing can say why.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
@Suppress("TooManyFunctions", "LargeClass")
class BleCentral(
    private val context: Context,
    private val engine: ScheduledExecutorService,
    private val listener: Listener,
) {
    interface Listener {
        /** GATT is ready; [maxFragment] = ATT MTU - 3. */
        fun onLinkUp(maxFragment: Int)
        fun onFragment(fragment: ByteArray)
        fun onLinkDown(reason: String)
        fun onRadioState(state: RadioState)
        fun onDiag(diag: Diag) {}
        /** Signal strength of the Mac link in dBm (answer to [readRssi]). */
        fun onRssi(rssi: Int) {}
    }

    enum class RadioState { OFF, NO_PERMISSION, SCANNING, CONNECTING, CONNECTED, IDLE }

    /** Fine-grained progress for the status line and for explaining failures. */
    enum class Stage { IDLE, OFF, NO_PERMISSION, SCANNING, FOUND, CONNECTING, DISCOVERING, SUBSCRIBING, CONNECTED }

    data class Diag(
        val stage: Stage = Stage.IDLE,
        /** Advertisements received since scanning began (any device). */
        val adsSeen: Int = 0,
        /** Distinct Apple devices heard (a Mac with the pair window closed still shows up here). */
        val appleDevices: Int = 0,
        /** The Mac's Tether service was seen at least once since [reset]. */
        val macSeen: Boolean = false,
        val macRssi: Int? = null,
        val connectAttempts: Int = 0,
        val linkUps: Int = 0,
        val lastError: String? = null,
        val locationOff: Boolean = false,
        val unfiltered: Boolean = false,
    )

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val serviceUuid = UUID.fromString(Protocol.SERVICE_UUID)
    private val serviceParcel = ParcelUuid(serviceUuid)
    private val charUuid = UUID.fromString(Protocol.CHARACTERISTIC_UUID)

    private var running = false
    private var foreground = false
    private var scanning = false
    private var scanUnfiltered = false
    private val scanStarts = ArrayDeque<Long>()
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var quickRetries = 0
    private var characteristic: BluetoothGattCharacteristic? = null
    private var linkUp = false
    private var mtu = DEFAULT_MTU
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var writeStartedAt = 0L
    private var refusedWrites = 0
    private val backoff = Backoff(baseMs = 1_000, maxMs = 30_000)
    private var pending: ScheduledFuture<*>? = null
    private var connectTimeout: ScheduledFuture<*>? = null
    private var stepTimer: ScheduledFuture<*>? = null
    private val ignored = HashMap<String, Long>()
    private val apple = HashSet<String>()
    private var radio = RadioState.IDLE
    private var diag = Diag()

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            engine.execute {
                when (s) {
                    BluetoothAdapter.STATE_ON -> if (running) restart("bluetooth on")
                    BluetoothAdapter.STATE_OFF -> {
                        teardown("bluetooth off")
                        setRadio(RadioState.OFF)
                        setStage(Stage.OFF)
                    }
                }
            }
        }
    }

    fun start() = engine.execute {
        if (running) return@execute
        running = true
        ContextCompat.registerReceiver(
            context, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        backoff.reset()
        scanSoon(0)
    }

    fun stop() = engine.execute {
        if (!running) return@execute
        running = false
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        teardown("stopped")
        setRadio(RadioState.IDLE)
        setStage(Stage.IDLE)
    }

    /** Clear the counters (a new pairing attempt starts from zero). */
    fun resetDiag() = engine.execute {
        apple.clear()
        diag = Diag(stage = diag.stage, locationOff = locationOff(), unfiltered = diag.unfiltered)
        listener.onDiag(diag)
    }

    /** The app is on screen: unfiltered low-latency scan. Off screen: filtered low-power scan. */
    fun setForeground(value: Boolean) = engine.execute {
        if (foreground == value) return@execute
        foreground = value
        if (scanning && scanUnfiltered != value) {
            stopScan()
            startScan()
        }
    }

    /** Burst mode during file transfers; low power otherwise. */
    fun setHighThroughput(high: Boolean) = engine.execute {
        if (!hasPermissions()) return@execute
        gatt?.requestConnectionPriority(
            if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED,
        )
    }

    /** Drop the current link and avoid this Mac for a while (it rejected us / isn't ours). */
    fun avoidCurrent(minutes: Long) = engine.execute {
        gatt?.device?.address?.let { ignored[it] = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes) }
        disconnect("avoided")
    }

    fun clearAvoided() = engine.execute { ignored.clear() }

    /** Drop the link; the reconnect loop takes over. */
    fun disconnect(reason: String) = engine.execute {
        if (gatt == null) return@execute
        teardown(reason)
        if (running) scanSoon(backoff.next())
    }

    /** Queue a fragment; written one at a time as the stack acknowledges each. */
    /** Asks the controller for the link's RSSI; the answer arrives on [Listener.onRssi]. */
    @SuppressLint("MissingPermission")
    fun readRssi() = engine.execute {
        if (!linkUp || !hasPermissions()) return@execute
        gatt?.readRemoteRssi()
    }

    fun write(fragment: ByteArray) {
        engine.execute {
            if (!linkUp) return@execute
            queue.addLast(fragment)
            pump()
        }
    }

    val queuedFragments: Int get() = queue.size

    // ---------------- scanning ----------------

    private fun scanSoon(delayMs: Long) {
        pending?.cancel(false)
        pending = engine.schedule({ startScan() }, delayMs, TimeUnit.MILLISECONDS)
    }

    @Suppress("ReturnCount")
    private fun startScan() {
        if (!running || scanning || gatt != null) return
        if (!hasPermissions()) {
            setStage(Stage.NO_PERMISSION)
            return setRadio(RadioState.NO_PERMISSION)
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            setStage(Stage.OFF)
            return setRadio(RadioState.OFF)
        }
        val scanner = a.bluetoothLeScanner ?: return setRadio(RadioState.OFF)
        val wait = throttleWait(System.currentTimeMillis())
        if (wait > 0) {
            Log.i("ble.scan_throttled", "waitMs" to wait)
            return scanSoon(wait)
        }
        val unfiltered = foreground
        val filters = if (unfiltered) {
            emptyList()
        } else {
            listOf(
                ScanFilter.Builder().setServiceUuid(serviceParcel).build(),
                ScanFilter.Builder().setDeviceName(ADV_NAME).build(),
            )
        }
        val settings = ScanSettings.Builder()
            .setScanMode(if (unfiltered) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanStarts.addLast(System.currentTimeMillis())
            scanning = true
            scanUnfiltered = unfiltered
            setRadio(RadioState.SCANNING)
            diag = diag.copy(unfiltered = unfiltered, locationOff = locationOff())
            setStage(Stage.SCANNING)
            Log.i("ble.scan_start", "unfiltered" to unfiltered, "locationOff" to diag.locationOff)
        } catch (e: SecurityException) {
            Log.e("ble.scan_denied", e)
            setRadio(RadioState.NO_PERMISSION)
            setStage(Stage.NO_PERMISSION)
        } catch (e: IllegalStateException) {
            Log.e("ble.scan_failed", e)
            scanSoon(backoff.next())
        }
    }

    /** Milliseconds to wait so this start stays within Android's scan-start budget. */
    private fun throttleWait(now: Long): Long {
        while (scanStarts.isNotEmpty() && now - scanStarts.peekFirst() > SCAN_WINDOW_MS) scanStarts.removeFirst()
        if (scanStarts.size < MAX_SCAN_STARTS) return 0
        return SCAN_WINDOW_MS - (now - scanStarts.peekFirst()) + THROTTLE_SLACK_MS
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // permission revoked; nothing to stop
        } catch (_: IllegalStateException) {
            // adapter turned off
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            engine.execute { onResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            engine.execute { results.forEach(::onResult) }
        }

        override fun onScanFailed(errorCode: Int) {
            engine.execute {
                Log.w("ble.scan_error", "code" to errorCode)
                scanning = false
                diag = diag.copy(lastError = "scan failed ($errorCode)")
                scanSoon(backoff.next())
            }
        }
    }

    private fun onResult(result: ScanResult) {
        if (!scanning || gatt != null) return
        val record = result.scanRecord
        val addr = result.device.address
        val isApple = record?.getManufacturerSpecificData(APPLE_COMPANY_ID) != null
        if (isApple) apple.add(addr)
        val byUuid = record?.serviceUuids?.contains(serviceParcel) == true
        val byName = record?.deviceName == ADV_NAME
        diag = diag.copy(adsSeen = diag.adsSeen + 1, appleDevices = apple.size)
        if (diag.adsSeen % DIAG_EVERY == 0) listener.onDiag(diag)
        if (!byUuid && !byName) return
        val until = ignored[addr]
        if (until != null && until > System.currentTimeMillis()) return
        diag = diag.copy(macSeen = true, macRssi = result.rssi)
        Log.i("ble.found", "rssi" to result.rssi, "byUuid" to byUuid, "byName" to byName, "seen" to diag.adsSeen)
        stopScan()
        quickRetries = 0
        connect(result.device)
    }

    private fun connect(d: BluetoothDevice) {
        device = d
        setRadio(RadioState.CONNECTING)
        diag = diag.copy(connectAttempts = diag.connectAttempts + 1)
        setStage(Stage.CONNECTING)
        Log.i("ble.connect", "attempt" to diag.connectAttempts)
        mtu = DEFAULT_MTU
        gatt = try {
            d.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e("ble.connect_denied", e)
            null
        }
        if (gatt == null) {
            diag = diag.copy(lastError = "connectGatt refused")
            return scanSoon(backoff.next())
        }
        connectTimeout?.cancel(false)
        connectTimeout = engine.schedule({ if (!linkUp) failed("connect timeout") }, CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
    }

    /**
     * A connection attempt failed before the link came up. GATT 133 and friends are common on
     * the first try, so retry the same Mac quickly a couple of times before scanning again.
     */
    private fun failed(reason: String) {
        diag = diag.copy(lastError = reason)
        Log.w("ble.connect_failed", "reason" to reason, "quickRetries" to quickRetries)
        val d = device
        teardown(reason)
        if (!running) return
        if (d != null && quickRetries < MAX_QUICK_RETRIES) {
            quickRetries++
            pending?.cancel(false)
            pending = engine.schedule({ if (running && gatt == null) connect(d) }, QUICK_RETRY_MS, TimeUnit.MILLISECONDS)
        } else {
            scanSoon(backoff.next())
        }
    }

    // ---------------- GATT ----------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            engine.execute {
                if (g != gatt) return@execute
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("ble.connected")
                    setStage(Stage.DISCOVERING)
                    // Give the stack a moment (OxygenOS and others fail discovery if asked instantly).
                    stepTimer?.cancel(false)
                    stepTimer = engine.schedule({ if (g == gatt) askMtu(g) }, SETTLE_MS, TimeUnit.MILLISECONDS)
                } else if (!linkUp) {
                    Log.i("ble.disconnected", "status" to status, "state" to newState)
                    failed("gatt status $status")
                } else {
                    Log.i("ble.disconnected", "status" to status, "state" to newState)
                    teardown("gatt status $status")
                    if (running) scanSoon(backoff.next())
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            engine.execute {
                if (g != gatt || characteristic != null) return@execute
                stepTimer?.cancel(false)
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
                Log.i("ble.mtu", "mtu" to mtu, "status" to status)
                discover(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            engine.execute {
                if (g != gatt) return@execute
                val service = g.getService(serviceUuid)
                val ch = service?.getCharacteristic(charUuid)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                    Log.w(
                        "ble.service_missing", "status" to status,
                        "services" to g.services.joinToString(",") { it.uuid.toString().take(8) },
                    )
                    return@execute failed(if (status != BluetoothGatt.GATT_SUCCESS) "discovery status $status" else "service missing")
                }
                characteristic = ch
                setStage(Stage.SUBSCRIBING)
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD) ?: return@execute failed("no cccd")
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!ok) failed("subscribe failed")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            engine.execute {
                if (g != gatt || descriptor.uuid != CCCD) return@execute
                if (status != BluetoothGatt.GATT_SUCCESS) return@execute failed("subscribe status $status")
                // Let the Mac finish handling the subscription before the first handshake write.
                stepTimer?.cancel(false)
                stepTimer = engine.schedule({ if (g == gatt) onSubscribed(g) }, SUBSCRIBE_SETTLE_MS, TimeUnit.MILLISECONDS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            engine.execute {
                if (g != gatt) return@execute
                writing = false
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w("ble.write_status", "status" to status)
                pump()
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) engine.execute { if (g == gatt && linkUp) listener.onRssi(rssi) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            engine.execute { if (g == gatt && c.uuid == charUuid) listener.onFragment(copy) }
        }

        @Deprecated("Pre-Android 13 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val copy = c.value?.copyOf() ?: return
            engine.execute { if (g == gatt && c.uuid == charUuid) listener.onFragment(copy) }
        }
    }

    private fun askMtu(g: BluetoothGatt) {
        if (!g.requestMtu(REQUEST_MTU)) return discover(g)
        // Some stacks never report the MTU; don't wait forever for it.
        stepTimer = engine.schedule({ if (g == gatt && characteristic == null) discover(g) }, MTU_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun discover(g: BluetoothGatt) {
        stepTimer?.cancel(false)
        if (!g.discoverServices()) failed("discovery failed")
    }

    private fun onSubscribed(g: BluetoothGatt) {
        connectTimeout?.cancel(false)
        linkUp = true
        quickRetries = 0
        backoff.reset()
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        setRadio(RadioState.CONNECTED)
        diag = diag.copy(linkUps = diag.linkUps + 1, lastError = null)
        setStage(Stage.CONNECTED)
        Log.i("ble.link_up", "mtu" to mtu, "frag" to minOf(mtu - ATT_OVERHEAD, MAX_ATTR_LEN))
        // A GATT value is at most 512 bytes and Android 13+ throws for longer writes, so even
        // with MTU 517 a fragment must not exceed 512 (TetherController clamps it too).
        listener.onLinkUp(minOf(mtu - ATT_OVERHEAD, MAX_ATTR_LEN))
    }

    private fun pump() {
        if (!linkUp) return
        if (writing) {
            // Some stacks never confirm write-without-response; don't stall the queue on them.
            if (System.currentTimeMillis() - writeStartedAt < WRITE_STALL_MS) return
            Log.w("ble.write_stalled")
            writing = false
        }
        val g = gatt ?: return
        val ch = characteristic ?: return
        val next = queue.peekFirst() ?: return
        val ok = try {
            writeNow(g, ch, next) ?: return
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // IllegalArgumentException (value too long), SecurityException (permission revoked),
            // DeadObjectException wrappers... A fragment that can't be written leaves its record
            // undeliverable, so restart the link instead of retrying it forever (0.3.0 wedged here).
            queue.clear()
            writing = false
            Log.e("ble.write_failed", e, "bytes" to next.size)
            restart("write failed")
            return
        }
        if (ok) {
            queue.removeFirst()
            writing = true
            writeStartedAt = System.currentTimeMillis()
            engine.schedule({ pump() }, WRITE_STALL_MS + 1, TimeUnit.MILLISECONDS)
        } else {
            engine.schedule({ pump() }, BUSY_RETRY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Writes one fragment: true when queued, false to retry shortly, null when a retry is already scheduled. */
    @SuppressLint("MissingPermission")
    private fun writeNow(g: BluetoothGatt, ch: BluetoothGattCharacteristic, next: ByteArray): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                engine.schedule({ pump() }, BUSY_RETRY_MS, TimeUnit.MILLISECONDS)
                return null
            }
            return accepted(rc == BluetoothStatusCodes.SUCCESS, rc)
        }
        @Suppress("DEPRECATION")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        ch.value = next
        @Suppress("DEPRECATION")
        return accepted(g.writeCharacteristic(ch), -1)
    }

    /** Counts refused writes; a stack that keeps refusing (not just busy) gets a fresh link. */
    private fun accepted(ok: Boolean, rc: Int): Boolean {
        if (ok) {
            refusedWrites = 0
            return true
        }
        if (++refusedWrites >= MAX_REFUSED_WRITES) {
            refusedWrites = 0
            error("writes refused (rc=$rc)")
        }
        return false
    }

    private fun restart(reason: String) {
        teardown(reason)
        backoff.reset()
        scanSoon(0)
    }

    private fun teardown(reason: String) {
        stopScan()
        pending?.cancel(false)
        connectTimeout?.cancel(false)
        stepTimer?.cancel(false)
        val wasUp = linkUp
        linkUp = false
        writing = false
        queue.clear()
        characteristic = null
        gatt?.let { g ->
            try {
                g.disconnect()
                g.close()
            } catch (_: SecurityException) {
                // permission revoked mid-connection
            }
        }
        gatt = null
        if (wasUp) listener.onLinkDown(reason)
        if (running) {
            val on = adapter?.isEnabled == true
            setRadio(if (on) RadioState.SCANNING else RadioState.OFF)
            setStage(if (on) Stage.SCANNING else Stage.OFF)
        }
    }

    private fun setRadio(s: RadioState) {
        if (radio == s) return
        radio = s
        listener.onRadioState(s)
    }

    private fun setStage(s: Stage) {
        diag = diag.copy(stage = s)
        listener.onDiag(diag)
    }

    private fun locationOff(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return !lm.isLocationEnabled
    }

    fun hasPermissions(): Boolean = permissionsGranted(context)

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** The Mac advertises this local name next to the service UUID. */
        const val ADV_NAME = "Tether"
        private const val APPLE_COMPANY_ID = 0x004C
        private const val DEFAULT_MTU = 23
        /** 247 = 244-byte payloads: fits one LE data-length-extended packet and every Mac. */
        private const val REQUEST_MTU = 247
        private const val ATT_OVERHEAD = 3
        /** GATT_MAX_ATTR_LEN: BluetoothGatt.writeCharacteristic rejects longer values (Android 13+). */
        const val MAX_ATTR_LEN = 512
        private const val CONNECT_TIMEOUT_S = 15L
        private const val BUSY_RETRY_MS = 8L
        private const val MAX_REFUSED_WRITES = 250
        private const val WRITE_STALL_MS = 400L
        private const val SETTLE_MS = 300L
        private const val SUBSCRIBE_SETTLE_MS = 150L
        private const val MTU_WAIT_MS = 2_000L
        private const val QUICK_RETRY_MS = 700L
        private const val MAX_QUICK_RETRIES = 2
        private const val SCAN_WINDOW_MS = 30_000L
        private const val MAX_SCAN_STARTS = 4
        private const val THROTTLE_SLACK_MS = 250L
        private const val DIAG_EVERY = 25

        fun permissionsGranted(c: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
        }

        fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
