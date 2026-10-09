package dev.benfranklin.tether.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.core.proto.Battery
import dev.benfranklin.tether.log.Log

/**
 * Battery level, charging state and network type, pushed to the Mac on change (the
 * [dev.benfranklin.tether.core.features.BatteryThrottle] in the runtime decides what is
 * actually sent). The cellular generation (4G/5G) needs READ_PHONE_STATE and is left out
 * without it; the signal level needs no permission.
 */
class BatteryMonitor(private val context: Context, private val onChange: (Battery) -> Unit) {
    private var receiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastIntent: Intent? = null

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                lastIntent = i
                emit()
            }
        }
        lastIntent = ContextCompat.registerReceiver(
            context, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = emit()
            override fun onLost(network: Network) = emit()
        }
        try {
            cm?.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: SecurityException) {
            Log.w("battery.net_callback_denied", "reason" to e.message)
        }
        emit()
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        netCallback?.let { cb ->
            runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
    }

    /** The current snapshot, or null before the first battery broadcast. */
    fun snapshot(): Battery? {
        val i = lastIntent ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, PERCENT)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> null
        }
        val (net, gen, signal) = network()
        return Battery(
            level = (level * PERCENT / scale).coerceIn(0, PERCENT), charging = charging, plug = plug,
            net = net, gen = gen, signal = signal, ts = System.currentTimeMillis(),
        )
    }

    private fun emit() {
        snapshot()?.let(onChange)
    }

    // dataNetworkType is read only after hasPhoneState() and inside runCatching (SecurityException).
    @SuppressLint("MissingPermission")
    private fun network(): Triple<String, String?, Int?> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val net = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val tm = context.getSystemService(TelephonyManager::class.java)
        val signal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { tm?.signalStrength?.level }.getOrNull()
        } else {
            null
        }
        val gen = if (tm != null && hasPhoneState(context)) runCatching { generation(tm.dataNetworkType) }.getOrNull() else null
        return Triple(net, gen, signal)
    }

    companion object {
        private const val PERCENT = 100

        fun hasPhoneState(c: Context): Boolean =
            ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        /** TelephonyManager.NETWORK_TYPE_* -> "2G".."5G". Pure; unit-tested. */
        @Suppress("CyclomaticComplexMethod", "DEPRECATION")
        fun generation(type: Int): String? = when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            -> "3G"
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_GSM,
            -> "2G"
            else -> null
        }
    }
}
