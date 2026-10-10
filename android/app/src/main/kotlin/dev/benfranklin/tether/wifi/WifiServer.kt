package dev.benfranklin.tether.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dev.benfranklin.tether.core.proto.Protocol
import dev.benfranklin.tether.log.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ScheduledExecutorService

/**
 * Phone side of the Wi-Fi fast path. Listens on a random TCP port on the local network
 * (Wi-Fi, or the phone's own hotspot), advertises `_tether._tcp` over mDNS so the Mac can
 * find it, and hands accepted sockets to the engine thread. The Mac learns the address and a
 * one-time token over Bluetooth first; the TCP session is then Noise-encrypted with the same
 * static keys, so nothing on the LAN can read or inject.
 */
class WifiServer(
    private val context: Context,
    private val engine: ScheduledExecutorService,
    private val onSocket: (Socket) -> Unit,
    private val onNetworkChanged: () -> Unit,
) {
    private var server: ServerSocket? = null
    private var nsdRegistered: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = server != null

    /** Start listening (idempotent). [serviceId] goes in the mDNS TXT record so the Mac picks the right phone. */
    fun start(serviceId: String) {
        if (server != null) return
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        } catch (e: IOException) {
            Log.e("wifi.listen_failed", e)
            return
        }
        server = s
        Thread({ acceptLoop(s) }, "tether-wifi-accept").apply { isDaemon = true }.start()
        register(serviceId, s.localPort)
        ensureWatching()
        Log.i("wifi.listening", "port" to s.localPort)
    }

    fun stop() {
        server?.let {
            try {
                it.close()
            } catch (_: IOException) {
                // closing anyway
            }
        }
        server = null
        unregister()
        netCallback?.let {
            try {
                context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
        }
        netCallback = null
        unwatchTether()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val socket = s.accept()
                engine.execute { onSocket(socket) }
            } catch (_: SocketException) {
                break // closed
            } catch (e: IOException) {
                Log.w("wifi.accept_error", "reason" to e.message)
            }
        }
    }

    private fun register(serviceId: String, port: Int) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        multicast = wifi?.createMulticastLock("tether-mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val info = NsdServiceInfo().apply {
            serviceName = "Tether-$serviceId"
            serviceType = Protocol.BONJOUR_TYPE
            setPort(port)
            setAttribute("id", serviceId)
            setAttribute("v", Protocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Log.i("wifi.mdns_registered")
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = Log.w("wifi.mdns_failed", "code" to code)
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdRegistered = listener
        } catch (e: IllegalArgumentException) {
            Log.w("wifi.mdns_error", "reason" to e.message)
        }
    }

    private fun unregister() {
        nsdRegistered?.let {
            try {
                context.getSystemService(NsdManager::class.java)?.unregisterService(it)
            } catch (_: IllegalArgumentException) {
                // not registered
            }
        }
        nsdRegistered = null
        multicast?.let { if (it.isHeld) it.release() }
        multicast = null
    }

    private var tetherReceiver: android.content.BroadcastReceiver? = null

    /**
     * 0.5.2: every network the Mac could reach us on. The phone's own hotspot is NOT a
     * ConnectivityManager network (softAP isn't a client network), so also watch the tether /
     * soft-AP state broadcasts. The listener is bound to the wildcard address, so it accepts on
     * a new interface (ap0, wlan1, swlan0…) without rebinding; what changes is the offer.
     */
    /** Start watching networks/tethering (idempotent); runs even before there is a LAN address to listen on. */
    fun ensureWatching() {
        if (netCallback == null && tetherReceiver == null) watchNetworks()
    }

    private fun watchNetworks() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = engine.execute(onNetworkChanged)
            override fun onLost(network: Network) = engine.execute(onNetworkChanged)
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) =
                engine.execute(onNetworkChanged)
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .apply { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) addTransportType(TRANSPORT_USB) }
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
        } catch (e: SecurityException) {
            Log.w("wifi.netcb_denied", "reason" to e.message)
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                Log.i("wifi.tether_changed", "action" to i.action?.substringAfterLast('.'))
                engine.execute(onNetworkChanged)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_TETHER_STATE_CHANGED)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, r, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        tetherReceiver = r
    }

    private fun unwatchTether() {
        tetherReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // not registered
            }
        }
        tetherReceiver = null
    }

    companion object {
        /** Hidden ConnectivityManager / WifiManager broadcasts (system-sent, stable since Android 4). */
        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val TRANSPORT_USB = 8 // NetworkCapabilities.TRANSPORT_USB (API 31)
        private val CELLULAR = listOf("rmnet", "ccmni", "pdp", "v4-rmnet", "dummy", "tun", "ipsec", "clat")

        /**
         * Private IPv4 addresses on Wi-Fi, hotspot and USB/Ethernet tethering interfaces.
         * Mobile-data and VPN interfaces are skipped: the Mac can only reach us on the LAN.
         */
        fun lanAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && CELLULAR.none { p -> it.name.startsWith(p) } }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>() }
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (_: SocketException) {
            emptyList()
        }
    }
}
