package dev.benfranklin.tether.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.IntOffset
import dev.benfranklin.tether.ui.theme.LocalReduceMotion
import dev.benfranklin.tether.ui.theme.Motion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.benfranklin.tether.BuildConfig
import dev.benfranklin.tether.TetherRuntime
import dev.benfranklin.tether.UiState
import dev.benfranklin.tether.pairStatus
import dev.benfranklin.tether.ble.BleCentral
import dev.benfranklin.tether.clip.readClipboardText
import dev.benfranklin.tether.core.LinkState
import dev.benfranklin.tether.core.notify.AppRule
import dev.benfranklin.tether.core.notify.NotificationFacts
import dev.benfranklin.tether.core.notify.NotificationFilter
import dev.benfranklin.tether.core.notify.NotificationSettings
import dev.benfranklin.tether.core.notify.QuietHours
import dev.benfranklin.tether.core.transfer.TransferRecord
import dev.benfranklin.tether.core.session.PairingUri
import dev.benfranklin.tether.core.util.fingerprint
import dev.benfranklin.tether.data.AppSettings
import dev.benfranklin.tether.data.SeenApp
import dev.benfranklin.tether.log.Log
import dev.benfranklin.tether.runtime
import dev.benfranklin.tether.service.TetherService
import dev.benfranklin.tether.device.BatteryMonitor
import dev.benfranklin.tether.device.Ringer
import dev.benfranklin.tether.media.MediaWatcher
import dev.benfranklin.tether.screen.ControlService
import dev.benfranklin.tether.ui.screens.FeatureActions
import dev.benfranklin.tether.ui.screens.FeatureStatus
import dev.benfranklin.tether.ui.screens.FeaturesScreen
import dev.benfranklin.tether.ui.screens.AppRow
import dev.benfranklin.tether.ui.screens.AppsScreen
import dev.benfranklin.tether.ui.screens.DeviceInfo
import dev.benfranklin.tether.ui.screens.HomeModel
import dev.benfranklin.tether.ui.screens.HomeScreen
import dev.benfranklin.tether.ui.screens.KeywordsScreen
import dev.benfranklin.tether.ui.screens.LicensesScreen
import dev.benfranklin.tether.ui.screens.OnboardingScreen
import dev.benfranklin.tether.ui.screens.OnboardingStep
import dev.benfranklin.tether.ui.screens.PairView
import dev.benfranklin.tether.ui.screens.PermissionItem
import dev.benfranklin.tether.ui.screens.SettingsActions
import dev.benfranklin.tether.ui.screens.SettingsScreen
import dev.benfranklin.tether.ui.theme.TetherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TetherTheme { TetherApp(runtime) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+: the clipboard is readable only while we have focus.
        if (hasFocus && runtime.ui.value.link is LinkState.Connected) runtime.clipboardSeen(readClipboardText(this))
    }
}

/** Builds the home screen model from runtime state. Pure; unit-tested. */
fun homeModel(s: UiState, transfers: List<TransferRecord>, mirroringOff: Boolean): HomeModel {
    val link = s.link
    val (title, sub) = when (link) {
        is LinkState.Connected -> link.peerName to if (link.wifi) "Connected · Wi-Fi fast path" else "Connected over Bluetooth"
        is LinkState.Pairing -> link.peerName to "Pairing"
        LinkState.Handshaking -> (s.peer?.name ?: "Your Mac") to "Connecting…"
        LinkState.Offline -> (s.peer?.name ?: "Your Mac") to when (s.radio) {
            BleCentral.RadioState.OFF -> "Bluetooth is off"
            BleCentral.RadioState.NO_PERMISSION -> "Allow Nearby devices to connect"
            else -> "Looking nearby…"
        }
    }
    return HomeModel(
        title = title, subtitle = sub, connected = link is LinkState.Connected,
        searching = link !is LinkState.Connected && s.radio != BleCentral.RadioState.OFF,
        wifi = (link as? LinkState.Connected)?.wifi == true, error = s.lastError, notice = s.notice,
        transfers = transfers, mirroringOff = mirroringOff, screenLive = s.screenLive,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TetherApp(rt: TetherRuntime) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val state by rt.ui.collectAsStateWithLifecycle()
    val settings by rt.settings.settings.collectAsStateWithLifecycle()
    val transfers by rt.transfers.transfers.collectAsStateWithLifecycle()
    var permsTick by remember { mutableIntStateOf(0) }
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permsTick++
        rt.setForeground(true)
        rt.permissionsChanged()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { rt.setForeground(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsTick++
        rt.permissionsChanged()
        if (BleCentral.permissionsGranted(ctx) && state.peer != null) TetherService.start(ctx)
    }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val qr = PairingUri.parse(text)
        if (qr == null) rt.note("That isn't a Tether pairing code") else rt.startPairing(qr)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) rt.sendFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val label = DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Chosen folder" }
            rt.settings.update { it.copy(downloadTreeUri = uri.toString(), downloadTreeLabel = label) }
        }
    }

    val listenerOn = remember(permsTick) { NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    val perms = remember(permsTick) { permissionItems(ctx, listenerOn) }

    fun grant(id: String) {
        when (id) {
            "listener" -> ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "bluetooth" -> permissionLauncher.launch(BleCentral.requiredPermissions().toTypedArray())
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            "camera" -> permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            "storage" -> permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            "media" -> permissionLauncher.launch(arrayOf(MediaWatcher.permission()))
            "phone" -> permissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
                )
            }
            "accessibility" -> ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    val paired = state.peer != null
    if (!paired || !settings.onboardingDone) {
        val pairView = when (val l = state.link) {
            is LinkState.Pairing -> if (l.auto) PairView.Auto(l.peerName) else PairView.Confirm(l.sas, l.peerName)
            else -> when {
                paired -> PairView.Auto(state.peer?.name.orEmpty())
                state.pairingMode -> PairView.Searching(state.pairingQrName, pairStatus(state))
                state.lastError != null -> PairView.Failed(state.lastError.orEmpty())
                else -> PairView.Idle
            }
        }
        LaunchedEffect(paired) { if (paired) rt.settings.update { it.copy(onboardingDone = true) } }
        OnboardingScreen(
            step = step, permissions = perms, pair = pairView,
            onNext = { step = if (step == OnboardingStep.WELCOME) OnboardingStep.PERMISSIONS else OnboardingStep.PAIR },
            onGrant = ::grant,
            onScanQr = {
                qrLauncher.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code on your Mac")
                        .setBeepEnabled(false).setOrientationLocked(false),
                )
            },
            onPairNearby = { rt.startPairing(null) },
            onConfirm = rt::confirmPairing,
            onReject = rt::rejectPairing,
            onCancelPairing = rt::cancelPairing,
            onExportLogs = { exportLogs(activity, state) },
        )
        return
    }

    val nav = rememberNavController()
    val reduce = LocalReduceMotion.current
    // iOS-style push: the new screen slides in from the right on a spring while the old one
    // drifts left by a third. Predictive back (Android 14+) scrubs the same animation.
    val slide = Motion.spring<IntOffset>(reduce)
    val fade = Motion.spring<Float>(reduce)
    SharedTransitionLayout {
    val sharedScope = this
    NavHost(
        nav, startDestination = "home",
        enterTransition = { slideInHorizontally(slide) { it } },
        exitTransition = { slideOutHorizontally(slide) { -it / 3 } + fadeOut(fade, targetAlpha = 0.6f) },
        popEnterTransition = { slideInHorizontally(slide) { -it / 3 } + fadeIn(fade, initialAlpha = 0.6f) },
        popExitTransition = { slideOutHorizontally(slide) { it } },
    ) {
        composable("home") {
          CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, this)) {
            HomeScreen(
                m = homeModel(state, transfers, mirroringOff = settings.notifications.enabled && !listenerOn),
                onSendClipboard = { ctx.startActivity(dev.benfranklin.tether.clip.ClipSendActivity.intent(ctx)) },
                onSendFile = { filePicker.launch(arrayOf("*/*")) },
                onSettings = { nav.navigate("settings") },
                onCancel = rt::cancelTransfer, onRetry = rt::retryTransfer, onRemove = rt::removeTransfer,
                onNoticeShown = rt::clearNotice,
                onFixMirroring = { grant("listener") },
                onStopScreen = rt::stopScreenSharing,
            )
          }
        }
        composable("settings") {
          CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, this)) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            SettingsScreen(
                s = settings,
                d = DeviceInfo(
                    macName = state.peer?.name, macFingerprint = state.peer?.let { fingerprint(it.keyBytes) },
                    phoneFingerprint = state.fingerprint, connected = state.link is LinkState.Connected,
                    listenerEnabled = listenerOn, version = BuildConfig.VERSION_NAME,
                    batteryOptimized = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == false,
                ),
                a = object : SettingsActions {
                    override fun update(change: (AppSettings) -> AppSettings) = rt.settings.update(change)
                    override fun openApps() = nav.navigate("apps")
                    override fun openKeywords() = nav.navigate("keywords")
                    override fun pickQuietStart() = pickTime(activity, settings.notifications.quietHours.startMinute) { m ->
                        rt.settings.update { it.withQuiet { q -> q.copy(startMinute = m) } }
                    }
                    override fun pickQuietEnd() = pickTime(activity, settings.notifications.quietHours.endMinute) { m ->
                        rt.settings.update { it.withQuiet { q -> q.copy(endMinute = m) } }
                    }
                    override fun openNotificationAccess() = grant("listener")
                    override fun pickFolder() = folderPicker.launch(null)
                    override fun resetFolder() = rt.settings.update { it.copy(downloadTreeUri = null, downloadTreeLabel = null) }
                    override fun openBatterySettings() {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    override fun rotateKeys() = rt.rotateKey()
                    override fun unpair() = rt.unpair()
                    override fun exportLogs() = exportLogs(activity, state)
                    override fun openLicenses() = nav.navigate("licenses")
                    override fun openFeatures() = nav.navigate("features")
                },
                onBack = { nav.popBackStack() },
            )
          }
        }
        composable("apps") {
            val seen by rt.seenApps.apps.collectAsStateWithLifecycle()
            var rows by remember { mutableStateOf(emptyList<AppRow>()) }
            LaunchedEffect(seen, settings.notifications.apps) {
                rows = withContext(Dispatchers.IO) { appRows(ctx, seen, settings.notifications) }
            }
            AppsScreen(
                apps = rows,
                onSet = { pkg, rule -> rt.settings.update { it.copy(notifications = it.notifications.withRule(pkg, rule)) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("keywords") {
            KeywordsScreen(
                words = settings.notifications.mutedKeywords,
                onChange = { w -> rt.settings.update { it.copy(notifications = it.notifications.copy(mutedKeywords = w)) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable("licenses") { LicensesScreen(onBack = { nav.popBackStack() }) }
        composable("features") {
            val st = remember(permsTick, state.peerCaps, state.link) {
                FeatureStatus(
                    connected = state.link is LinkState.Connected,
                    macCaps = state.peerCaps.takeIf { state.link is LinkState.Connected },
                    listenerEnabled = listenerOn,
                    mediaPermission = MediaWatcher.hasPermission(ctx),
                    mediaLimited = MediaWatcher.limitedAccess(ctx),
                    phoneState = BatteryMonitor.hasPhoneState(ctx),
                    fullScreenAlerts = Ringer.canUseFullScreen(ctx),
                    controlEnabled = ControlService.enabledInSettings(ctx),
                )
            }
            FeaturesScreen(
                s = settings, st = st,
                a = object : FeatureActions {
                    override fun update(change: (AppSettings) -> AppSettings) {
                        rt.settings.update(change)
                        // Turning a feature on asks for what it needs straight away.
                        val now = rt.settings.current
                        if ((now.sendScreenshots || now.sendPhotos) && !MediaWatcher.hasPermission(ctx)) grant("media")
                    }
                    override fun grantMedia() = grant("media")
                    override fun grantPhoneState() = grant("phone")
                    override fun openFullScreenSettings() = grant("fullscreen")
                    override fun openAccessibility() = grant("accessibility")
                    override fun openNotificationAccess() = grant("listener")
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
    }
}

private fun AppSettings.withQuiet(change: (QuietHours) -> QuietHours) =
    copy(notifications = notifications.copy(quietHours = change(notifications.quietHours)))

private fun permissionItems(ctx: Context, listenerOn: Boolean): List<PermissionItem> {
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return buildList {
        add(
            PermissionItem(
                "bluetooth", "Nearby devices", "Find and stay connected to your Mac over Bluetooth",
                BleCentral.permissionsGranted(ctx), true,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                PermissionItem(
                    "notifications", "Notifications", "Show the connection and transfer progress",
                    granted(Manifest.permission.POST_NOTIFICATIONS), false,
                ),
            )
        }
        add(PermissionItem("listener", "Notification access", "Mirror your phone's notifications to the Mac", listenerOn, false))
        add(
            PermissionItem(
                "camera", "Camera", "Scan the pairing code (you can pair without it)", granted(Manifest.permission.CAMERA), false,
            ),
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(
                PermissionItem(
                    "storage", "Storage", "Save received files to Downloads",
                    granted(Manifest.permission.WRITE_EXTERNAL_STORAGE), false,
                ),
            )
        }
    }
}

private fun pickTime(activity: ComponentActivity, minutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(activity, { _, h, m -> onPicked(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
}

private fun exportLogs(activity: ComponentActivity, s: UiState) {
    Thread {
        val header = "Tether ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · link=${s.link::class.simpleName} radio=${s.radio}\n${s.diag}"
        val file = Log.export(activity, header)
        val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.runOnUiThread { activity.startActivity(Intent.createChooser(send, "Export Tether logs")) }
    }.start()
}

/** Seen apps plus installed launcher apps, with icons, sorted on-first then by name. */
private fun appRows(
    ctx: Context, seen: Map<String, SeenApp>, n: NotificationSettings,
): List<AppRow> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val installed = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
    val pkgs = (installed + seen.keys + n.apps.keys) - ctx.packageName
    return pkgs.mapNotNull { pkg ->
        val ai = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val label = ai?.let { pm.getApplicationLabel(it).toString() } ?: seen[pkg]?.label ?: return@mapNotNull null
        val system = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        val facts = NotificationFacts(
            key = "", pkg = pkg, appLabel = label, title = "", text = "", postedAt = 0, isSystemApp = system,
            isSocialApp = ai?.category == ApplicationInfo.CATEGORY_SOCIAL,
        )
        val rule = n.ruleFor(pkg)
        val on = when (rule) {
            AppRule.ALLOW -> true
            AppRule.BLOCK -> false
            AppRule.INHERIT -> NotificationFilter.defaultAllowed(facts)
        }
        val icon: ImageBitmap? = try {
            pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        AppRow(pkg, label, rule, on, icon)
    }.sortedWith(compareByDescending<AppRow> { it.effectiveOn }.thenBy { it.label.lowercase() })
}

private const val ICON_PX = 96
