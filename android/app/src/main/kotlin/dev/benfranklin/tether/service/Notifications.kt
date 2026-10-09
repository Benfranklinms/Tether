package dev.benfranklin.tether.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.benfranklin.tether.R
import dev.benfranklin.tether.UiState
import dev.benfranklin.tether.ble.BleCentral
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.transfer.Direction
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.transfer.TransferStatus
import dev.benfranklin.tether.core.transfer.Transport
import dev.benfranklin.tether.ui.MainActivity

/** All system notifications the app posts (never the mirrored ones; those go to the Mac). */
object Notifications {
    const val CH_LINK = "link"
    const val CH_TRANSFERS = "transfers"
    const val CH_ALERTS = "alerts"
    const val CH_FIND = "find"
    const val CH_SCREEN = "screen"
    const val CH_REQUESTS = "requests"
    const val ID_ONGOING = 1
    const val ID_RING = 2
    const val ID_SCREEN = 3
    const val ID_SCREEN_REQUEST = 4
    private const val ID_TRANSFER_BASE = 1000
    private const val ID_RECEIVED_BASE = 50_000
    private const val SPAN = 40_000
    private const val PERCENT = 100

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_LINK, c.getString(R.string.channel_link), NotificationManager.IMPORTANCE_MIN).apply {
                    description = c.getString(R.string.channel_link_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_TRANSFERS, c.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW).apply {
                    description = c.getString(R.string.channel_transfers_desc)
                },
                NotificationChannel(CH_ALERTS, c.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = c.getString(R.string.channel_alerts_desc)
                },
                NotificationChannel(CH_FIND, c.getString(R.string.channel_find), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = c.getString(R.string.channel_find_desc)
                    setSound(null, null) // Tether plays the alarm itself on the alarm stream
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_REQUESTS, c.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = c.getString(R.string.channel_requests_desc)
                },
                NotificationChannel(CH_SCREEN, c.getString(R.string.channel_screen), NotificationManager.IMPORTANCE_LOW).apply {
                    description = c.getString(R.string.channel_screen_desc)
                    setShowBadge(false)
                },
            ),
        )
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun statusText(s: UiState): String = when (val l = s.link) {
        is LinkState.Connected -> if (l.wifi) "Connected to ${l.peerName} · Wi-Fi" else "Connected to ${l.peerName}"
        is LinkState.Pairing -> "Pairing with ${l.peerName}"
        LinkState.Handshaking -> "Connecting…"
        LinkState.Offline -> when (s.radio) {
            BleCentral.RadioState.OFF -> "Bluetooth is off"
            BleCentral.RadioState.NO_PERMISSION -> "Needs Nearby devices permission"
            else -> s.peer?.let { "Looking for ${it.name}" } ?: "Looking for your Mac"
        }
    }

    fun ongoing(c: Context, s: UiState): Notification =
        NotificationCompat.Builder(c, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(statusText(s))
            .setContentIntent(openApp(c))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun updateOngoing(c: Context, s: UiState) {
        if (!TetherService.running) return
        post(c, ID_ONGOING, ongoing(c, s))
    }

    fun transfer(c: Context, r: TransferRecord) {
        val id = ID_TRANSFER_BASE + (r.id.hashCode() and Int.MAX_VALUE) % SPAN
        if (r.status.isFinal && r.status != TransferStatus.FAILED) return cancel(c, id)
        val verb = if (r.direction == Direction.OUT) "Sending" else "Receiving"
        val via = if (r.via == Transport.WIFI) " · Wi-Fi" else ""
        val text = when (r.status) {
            TransferStatus.WAITING -> r.error ?: "Waiting for your Mac"
            TransferStatus.FAILED -> r.error ?: "Failed"
            TransferStatus.VERIFYING -> "Verifying…"
            else -> "${(r.progress * PERCENT).toInt()}%$via"
        }
        val b = NotificationCompat.Builder(c, CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(if (r.status == TransferStatus.FAILED) "Couldn't send ${r.name}" else "$verb ${r.name}")
            .setContentText(text)
            .setContentIntent(openApp(c))
            .setOnlyAlertOnce(true)
            .setSilent(true)
        if (!r.status.isFinal) b.setOngoing(true).setProgress(PERCENT, (r.progress * PERCENT).toInt(), r.status == TransferStatus.QUEUED)
        post(c, id, b.build())
    }

    fun cancelTransfer(c: Context, transferId: String) =
        cancel(c, ID_TRANSFER_BASE + (transferId.hashCode() and Int.MAX_VALUE) % SPAN)

    fun received(c: Context, r: TransferRecord) {
        val uri = r.ref?.let(Uri::parse)
        val view = uri?.let {
            Intent(Intent.ACTION_VIEW).setDataAndType(it, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pi = if (view != null && view.resolveActivity(c.packageManager) != null) {
            PendingIntent.getActivity(c, r.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        } else {
            openApp(c)
        }
        post(
            c, ID_RECEIVED_BASE + (r.id.hashCode() and Int.MAX_VALUE) % SPAN,
            NotificationCompat.Builder(c, CH_TRANSFERS)
                .setSmallIcon(R.drawable.ic_stat_tether)
                .setContentTitle("Received ${r.name}")
                .setContentText(if (r.ref?.contains("/tree/") == true) "Saved to your folder" else "Saved to Downloads/Tether")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun alert(c: Context, title: String, text: String) = post(
        c, title.hashCode(),
        NotificationCompat.Builder(c, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(c))
            .setAutoCancel(true)
            .build(),
    )

    /** Post a notification built elsewhere (ring, screen sharing), if notifications are allowed. */
    fun postRaw(c: Context, id: Int, n: Notification) = post(c, id, n)

    fun cancelId(c: Context, id: Int) = cancel(c, id)

    private fun post(c: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(c).notify(id, n)
        } catch (_: SecurityException) {
            // permission revoked between check and post
        }
    }

    private fun cancel(c: Context, id: Int) = NotificationManagerCompat.from(c).cancel(id)
}
