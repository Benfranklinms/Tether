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
 * The phone's side of the Bluetooth link: a GATT central that finds the Mac by service
 * UUID, connects, raises the MTU, subscribes to notifications and then moves fragments both
 * ways with write-without-response flow control.
 *
 * Every callback is hopped onto [engine] (the single thread that owns the controller), so
 * this class is effectively single-threaded. Reconnects use capped exponential backoff, and
 * scanning is filtered by service UUID at [ScanSettings.SCAN_MODE_LOW_POWER] unless the app
 * is on screen, so an idle phone costs very little battery.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
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
    }

    enum class RadioState { OFF, NO_PERMISSION, SCANNING, CONNECTING, CONNECTED, IDLE }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val serviceUuid = UUID.fromString(Protocol.SERVICE_UUID)
    private val charUuid = UUID.fromString(Protocol.CHARACTERISTIC_UUID)

    private var running = false
    private var foreground = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var linkUp = false
    private var mtu = DEFAULT_MTU
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private val backoff = Backoff(baseMs = 1_000, maxMs = 30_000)
    private var pending: ScheduledFuture<*>? = null
    private var connectTimeout: ScheduledFuture<*>? = null
    private val ignored = HashMap<String, Long>()
    private var radio = RadioState.IDLE

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            engine.execute {
                when (s) {
                    BluetoothAdapter.STATE_ON -> if (running) restart("bluetooth on")
                    BluetoothAdapter.STATE_OFF -> {
                        teardown("bluetooth off")
                        setRadio(RadioState.OFF)
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
    }

    /** The app is on screen: scan aggressively. Off screen: low-power scan. */
    fun setForeground(value: Boolean) = engine.execute {
        if (foreground == value) return@execute
        foreground = value
        if (scanning) {
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

    private fun startScan() {
        if (!running || scanning || gatt != null) return
        if (!hasPermissions()) return setRadio(RadioState.NO_PERMISSION)
        val a = adapter
        if (a == null || !a.isEnabled) return setRadio(RadioState.OFF)
        val scanner = a.bluetoothLeScanner ?: return setRadio(RadioState.OFF)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (foreground) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            setRadio(RadioState.SCANNING)
            Log.i("ble.scan_start", "foreground" to foreground)
        } catch (e: SecurityException) {
            Log.e("ble.scan_denied", e)
            setRadio(RadioState.NO_PERMISSION)
        } catch (e: IllegalStateException) {
            Log.e("ble.scan_failed", e)
            scanSoon(backoff.next())
        }
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
            engine.execute { onFound(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            engine.execute {
                Log.w("ble.scan_error", "code" to errorCode)
                scanning = false
                scanSoon(backoff.next())
            }
        }
    }

    private fun onFound(device: BluetoothDevice) {
        if (!scanning || gatt != null) return
        val until = ignored[device.address]
        if (until != null && until > System.currentTimeMillis()) return
        stopScan()
        Log.i("ble.connect")
        setRadio(RadioState.CONNECTING)
        mtu = DEFAULT_MTU
        gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e("ble.connect_denied", e)
            null
        }
        if (gatt == null) return scanSoon(backoff.next())
        connectTimeout = engine.schedule({ if (!linkUp) disconnect("connect timeout") }, CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
    }

    // ---------------- GATT ----------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            engine.execute {
                if (g != gatt) return@execute
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(MAX_MTU)) g.discoverServices()
                } else {
                    Log.i("ble.disconnected", "status" to status)
                    teardown("gatt status $status")
                    if (running) scanSoon(backoff.next())
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            engine.execute {
                if (g != gatt) return@execute
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
                if (!g.discoverServices()) disconnect("discovery failed")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            engine.execute {
                if (g != gatt) return@execute
                val ch = g.getService(serviceUuid)?.getCharacteristic(charUuid)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) return@execute disconnect("service missing")
                characteristic = ch
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD) ?: return@execute disconnect("no cccd")
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!ok) disconnect("subscribe failed")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            engine.execute {
                if (g != gatt || descriptor.uuid != CCCD) return@execute
                if (status != BluetoothGatt.GATT_SUCCESS) return@execute disconnect("subscribe status $status")
                connectTimeout?.cancel(false)
                linkUp = true
                backoff.reset()
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                setRadio(RadioState.CONNECTED)
                Log.i("ble.link_up", "mtu" to mtu)
                listener.onLinkUp(mtu - ATT_OVERHEAD)
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

    private fun pump() {
        if (writing || !linkUp) return
        val g = gatt ?: return
        val ch = characteristic ?: return
        val next = queue.peekFirst() ?: return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                engine.schedule({ pump() }, BUSY_RETRY_MS, TimeUnit.MILLISECONDS)
                return
            }
            rc == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            ch.value = next
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        if (ok) {
            queue.removeFirst()
            writing = true
        } else {
            engine.schedule({ pump() }, BUSY_RETRY_MS, TimeUnit.MILLISECONDS)
        }
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
        if (running) setRadio(if (adapter?.isEnabled == true) RadioState.SCANNING else RadioState.OFF)
    }

    private fun setRadio(s: RadioState) {
        if (radio == s) return
        radio = s
        listener.onRadioState(s)
    }

    fun hasPermissions(): Boolean = permissionsGranted(context)

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val MAX_MTU = 517
        private const val ATT_OVERHEAD = 3
        private const val CONNECT_TIMEOUT_S = 20L
        private const val BUSY_RETRY_MS = 8L

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
