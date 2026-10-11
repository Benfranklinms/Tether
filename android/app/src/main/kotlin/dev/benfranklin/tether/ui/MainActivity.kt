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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
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
import dev.benfranklin.tether.ui.screens.ClipAutoSteps
import dev.benfranklin.tether.ui.screens.FeatureActions
import dev.benfranklin.tether.ui.screens.FeatureStatus
import dev.benfranklin.tether.call.CallMonitor
import dev.benfranklin.tether.notify.SensitiveWatch
import dev.benfranklin.tether.sms.SmsStore
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
import dev.benfranklin.tether.ui.screens.More5Actions
import dev.benfranklin.tether.ui.screens.More5Screen
import dev.benfranklin.tether.ui.screens.SetupScreen
import dev.benfranklin.tether.ui.screens.setupSummary
import dev.benfranklin.tether.ui.screens.updateUi
import dev.benfranklin.tether.ui.theme.TetherTheme
import dev.benfranklin.tether.core.features.UpdatePrompt
import dev.benfranklin.tether.core.proto.Caps6
import dev.benfranklin.tether.ui.screens.FindModel
import dev.benfranklin.tether.ui.screens.HotspotActions
import dev.benfranklin.tether.ui.screens.HotspotScreen
import dev.benfranklin.tether.ui.screens.ShelfActions
import dev.benfranklin.tether.ui.screens.ShelfFilesUi
import dev.benfranklin.tether.ui.screens.TransfersActions
import dev.benfranklin.tether.ui.screens.TransfersScreen
import dev.benfranklin.tether.core.features.WhatsNewPolicy
import dev.benfranklin.tether.ui.screens.ShelfDetailScreen
import dev.benfranklin.tether.ui.screens.ShelfScreen
import dev.benfranklin.tether.ui.screens.UpdateSheet
import dev.benfranklin.tether.ui.screens.UpdateSheetModel
import dev.benfranklin.tether.ui.screens.WhatsNewSheet
import dev.benfranklin.tether.ui.screens.hotspotStatusText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        tetherEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TetherTheme { TetherApp(runtime) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+: the clipboard is readable only while we have focus.
        if (hasFocus && runtime.ui.value.link is LinkState.Connected) {
            when (val c = dev.benfranklin.tether.clip.readClipContent(this)) {
                is dev.benfranklin.tether.core.features.ClipContent.Image -> runtime.clipboardImageSeen(Uri.parse(c.uri))
                is dev.benfranklin.tether.core.features.ClipContent.Text -> runtime.clipboardSeen(c.text)
                dev.benfranklin.tether.core.features.ClipContent.Empty -> Unit
            }
        }
    }
}

/** Builds the home screen model from runtime state. Pure; unit-tested. */
fun homeModel(s: UiState, transfers: List<TransferRecord>, mirroringOff: Boolean): HomeModel {
    val link = s.link
    val (title, sub) = when (link) {
        is LinkState.Connected -> link.peerName to if (link.wifi) "Connected · Wi-Fi" else "Connected · Bluetooth"
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
        canReconnect = s.peer != null && !s.pairingMode &&
            (link is LinkState.Offline || link is LinkState.Handshaking) &&
            s.radio != BleCentral.RadioState.OFF && s.radio != BleCentral.RadioState.NO_PERMISSION,
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
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { rt.clipWatcher.onBackground() }

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
    val shelfFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(rt.v6::addFile)
        if (uris.isNotEmpty()) rt.note(if (uris.size == 1) "Added to Shelf" else "Added ${uris.size} items to Shelf")
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
            "sms" -> permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS,
                ),
            )
            "contacts" -> permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            "calls" -> permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.READ_CALL_LOG)
                    add(Manifest.permission.READ_CONTACTS)
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                    add(Manifest.permission.CALL_PHONE)
                }.toTypedArray(),
            )
            "dnd" -> ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    // "What's new" once after an update (0.5.3: also after updating from builds that never saved a
    // version — a paired / onboarded app is an update, not a fresh install). Notes come from the
    // CHANGELOG.md bundled in the APK, so they show however the update was installed.
    var whatsNew by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val s = rt.settings.current
        val used = s.onboardingDone || rt.ui.value.peer != null
        when (WhatsNewPolicy.decide(BuildConfig.VERSION_NAME, s.whatsNewSeen, used)) {
            WhatsNewPolicy.Action.SHOW -> whatsNew = true
            WhatsNewPolicy.Action.RECORD -> rt.settings.update { it.copy(whatsNewSeen = BuildConfig.VERSION_NAME) }
            WhatsNewPolicy.Action.NONE -> Unit
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

    // Update checks (0.5.2): on launch, then on coming to the foreground at most every 6 h.
    fun checkForUpdate(launch: Boolean) {
        val s = rt.settings.current
        val now = System.currentTimeMillis()
        val due = UpdatePrompt.shouldCheck(s.lastUpdateCheck, now, launch = launch, foreground = !launch)
        if (s.autoUpdateCheck && now - s.lastUpdateCheck > CHECK_GAP_MS && due) {
            rt.settings.update { it.copy(lastUpdateCheck = now) }
            rt.updater.check()
        }
    }
    LaunchedEffect(Unit) { checkForUpdate(launch = true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { checkForUpdate(launch = false) }
    val upd by rt.updater.status.collectAsStateWithLifecycle()
    val shelf by rt.v6.shelf.collectAsStateWithLifecycle()
    val find by rt.v6.find.collectAsStateWithLifecycle()
    val hotspot by rt.v6.hotspot.collectAsStateWithLifecycle()
    val offered = updateOffered(upd)
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(upd) {
        val st = upd
        if (st is dev.benfranklin.tether.v5.Updater.State.Available) {
            val s = rt.settings.current
            if (UpdatePrompt.shouldShow(st.info.version, BuildConfig.VERSION_NAME, s.skippedVersion, s.updateSnoozedUntil,
                    System.currentTimeMillis())) {
                showUpdate = true
            }
        }
    }
    val shelfLocal by rt.v6.localFiles.collectAsStateWithLifecycle()
    val shelfFetching by rt.v6.fetching.collectAsStateWithLifecycle()
    val shelfConnected = state.link is LinkState.Connected && Caps6.SHELF in state.peerCaps
    val shelfFiles = ShelfFilesUi(shelfLocal, shelfFetching, shelfConnected)
    val shelfActions = remember(rt) { RuntimeShelfActions(ctx, rt) }
    val nav = rememberNavController()
    shelfActions.navigate = { id -> nav.navigate("shelf/$id") }
    shelfActions.pickFile = { shelfFilePicker.launch(arrayOf("*/*")) }
    val reduce = LocalReduceMotion.current
    // iOS-style push: the new screen slides in from the right on a spring while the old one
    // drifts left by a third. Predictive back (Android 14+) scrubs the same animation.
    val slide = Motion.spring<IntOffset>(reduce)
    val fade = Motion.spring<Float>(reduce)
    SharedTransitionLayout {
    val sharedScope = this
    NavHost(
        nav, startDestination = "home",
        // Reduce motion / animator scale 0: a short crossfade instead of the slide (0.5.1).
        enterTransition = { if (reduce) fadeIn(tween(REDUCED_FADE_MS)) else slideInHorizontally(slide) { it } },
        exitTransition = {
            if (reduce) {
                fadeOut(tween(REDUCED_FADE_MS))
            } else {
                slideOutHorizontally(slide) { -it / 3 } + fadeOut(fade, targetAlpha = 0.6f)
            }
        },
        popEnterTransition = {
            if (reduce) {
                fadeIn(tween(REDUCED_FADE_MS))
            } else {
                slideInHorizontally(slide) { -it / 3 } + fadeIn(fade, initialAlpha = 0.6f)
            }
        },
        popExitTransition = { if (reduce) fadeOut(tween(REDUCED_FADE_MS)) else slideOutHorizontally(slide) { it } },
    ) {
        composable("home") {
          CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, this)) {
            val feedsMac by rt.feeds.mac.collectAsStateWithLifecycle()
            val clips by rt.feeds.clips.collectAsStateWithLifecycle()
            var clipboard by remember { mutableStateOf<String?>(null) }
            var photos by remember { mutableStateOf<List<dev.benfranklin.tether.v7.RecentPhoto>>(emptyList()) }
            val photosAllowed = remember(permsTick) { dev.benfranklin.tether.v7.RecentPhotos.allowed(ctx) }
            var resumed by remember { mutableIntStateOf(0) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
            LaunchedEffect(resumed, photosAllowed, transfers.size) {
                clipboard = readClipboardText(ctx)
                photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dev.benfranklin.tether.v7.RecentPhotos.latest(ctx, HOME_PHOTOS)
                }
            }
            val homeLayout = remember(settings.homePanels) { dev.benfranklin.tether.core.features.HomeLayout.from(settings.homePanels) }
            val hotspotSsid = remember(resumed) { dev.benfranklin.tether.v6.HotspotStore.ssid(ctx) }
            var inFront by remember { mutableStateOf(true) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { inFront = true }
            LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { inFront = false }
            val linked = state.link is LinkState.Connected
            val wantsMedia = dev.benfranklin.tether.core.features.PanelKind.NOW_PLAYING in homeLayout
            val wantsMac = wantsMedia || dev.benfranklin.tether.core.features.PanelKind.MAC in homeLayout
            // Poll the Mac's status while Home shows the Mac / Now playing panel and the app is in front.
            LaunchedEffect(inFront, linked, wantsMac, wantsMedia) {
                if (!inFront || !linked || !wantsMac) return@LaunchedEffect
                while (true) {
                    rt.feeds.request(wantsMedia)
                    kotlinx.coroutines.delay(dev.benfranklin.tether.core.features.MacStatusPolicy.POLL_MS)
                }
            }
            HomeScreen(
                m = homeModel(state, transfers, mirroringOff = settings.notifications.enabled && !listenerOn),
                layout = homeLayout,
                data = dev.benfranklin.tether.ui.screens.HomeData(
                    shelf = shelf.ordered, clipboard = clipboard, clips = clips, mac = feedsMac,
                    macSupported = state.link !is LinkState.Connected ||
                        dev.benfranklin.tether.core.proto.Caps7.MAC_STATUS in state.peerCaps,
                    photos = photos, photosAllowed = photosAllowed, hotspotSsid = hotspotSsid, hotspotState = hotspot.state,
                    updateVersion = offered?.version?.takeIf { it != settings.skippedVersion },
                ),
                a = object : dev.benfranklin.tether.ui.screens.HomeActions {
                    override fun sendClipboard() = ctx.startActivity(dev.benfranklin.tether.clip.ClipSendActivity.intent(ctx))
                    override fun sendFile() = filePicker.launch(arrayOf("*/*"))
                    override fun settings() = nav.navigate("settings")
                    override fun openShelf() = nav.navigate("shelf")
                    override fun openShelfItem(id: String) = nav.navigate("shelf/$id")
                    override fun copyClip(text: String) = copyText(ctx, text)
                    override fun seeAllTransfers() = nav.navigate("transfers")
                    override fun openTransfer(t: dev.benfranklin.tether.core.transfer.TransferRecord) {
                        rt.openTransfer(t)?.let(rt::note)
                    }
                    override fun mediaCommand(cmd: String) = rt.feeds.mediaCommand(cmd)
                    override fun sendPhotos(photos: List<dev.benfranklin.tether.v7.RecentPhoto>) = rt.sendFiles(photos.map { it.uri })
                    override fun allowPhotos() = grant("media")
                    override fun openHotspot() = nav.navigate("hotspot")
                    override fun update() {
                        showUpdate = true
                    }
                    override fun reconnect() = rt.reconnect()
                    override fun stopScreen() = rt.stopScreenSharing()
                    override fun fixMirroring() = grant("listener")
                    override fun noticeShown() = rt.clearNotice()
                    override fun layoutChanged(layout: dev.benfranklin.tether.core.features.HomeLayout) =
                        rt.settings.update { it.copy(homePanels = layout.specs()) }
                    override suspend fun photoThumb(p: dev.benfranklin.tether.v7.RecentPhoto): ImageBitmap? =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            dev.benfranklin.tether.v7.RecentPhotos.thumbnail(ctx, p, PHOTO_THUMB_PX)
                        }
                    override suspend fun shelfThumb(item: dev.benfranklin.tether.core.proto.ShelfItem): ImageBitmap? =
                        shelfActions.thumbnail(item)
                },
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
                    lastCrash = remember { dev.benfranklin.tether.log.CrashLog.stamp(ctx) },
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
                    override fun copyLastCrash() {
                        dev.benfranklin.tether.log.CrashLog.read(ctx)?.let { copyText(ctx, it) }
                    }
                    override fun openFeatures() = nav.navigate("features")
                    override fun openSetup() = nav.navigate("setup")
                    override fun openMore5() = nav.navigate("more5")
                    override fun openShelf() = nav.navigate("shelf")
                    override fun openHotspot() = nav.navigate("hotspot")
                    override fun setupSummary() = setupSummary(setupRows(ctx))
                },
                onBack = { nav.popBackStack() },
                find = FindModel(
                    ringing = find.ringing, pending = find.pending, locked = find.locked, error = find.error,
                    available = state.link is LinkState.Connected && Caps6.FIND_MAC in state.peerCaps,
                ).takeIf { state.peer != null },
                onFind = rt.v6::findMac,
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
        composable("shelf") {
          CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, this)) {
            ShelfScreen(
                items = shelf.ordered, connected = shelfConnected,
                a = shelfActions, onBack = { nav.popBackStack() }, files = shelfFiles,
            )
          }
        }
        composable("shelf/{id}") { entry ->
          CompositionLocalProvider(LocalSharedScopes provides SharedScopes(sharedScope, this)) {
            val id = entry.arguments?.getString("id")
            ShelfDetailScreen(
                item = shelf.items.firstOrNull { it.id == id }, a = shelfActions, onBack = { nav.popBackStack() }, files = shelfFiles,
            )
          }
        }
        composable("transfers") {
            TransfersScreen(
                transfers = transfers,
                a = object : TransfersActions {
                    override fun cancel(id: String) = rt.cancelTransfer(id)
                    override fun retry(id: String) = rt.retryTransfer(id)
                    override fun remove(id: String) = rt.removeTransfer(id)
                    override fun clearFinished() = rt.clearFinishedTransfers()
                    override fun open(t: dev.benfranklin.tether.core.transfer.TransferRecord): String? = rt.openTransfer(t)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("hotspot") {
            var tick by remember { mutableIntStateOf(0) }
            val saved = remember(tick) { dev.benfranklin.tether.v6.HotspotStore.ssid(ctx) }
            val hasPass = remember(tick) { !dev.benfranklin.tether.v6.HotspotStore.psk(ctx).isNullOrEmpty() }
            HotspotScreen(
                s = settings, savedSsid = saved, hasPassword = hasPass,
                controlOn = remember(permsTick) { ControlService.enabledInSettings(ctx) },
                status = hotspotStatusText(hotspot.state, hotspot.reason),
                a = object : HotspotActions {
                    override fun update(change: (AppSettings) -> AppSettings) = rt.settings.update(change)
                    override fun save(ssid: String, password: String) {
                        // An empty password field keeps the saved one (it is never shown back).
                        val keep = if (password.isEmpty()) dev.benfranklin.tether.v6.HotspotStore.psk(ctx) else password
                        dev.benfranklin.tether.v6.HotspotStore.save(ctx, ssid, keep)
                        tick++
                    }
                    override fun forget() {
                        dev.benfranklin.tether.v6.HotspotStore.clear(ctx)
                        tick++
                    }
                    override fun openAccessibility() = grant("accessibility")
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("setup") {
            val rows = remember(permsTick) { setupRows(ctx) }
            SetupScreen(
                rows = rows,
                onFix = { id -> fixSetup(ctx, id) { p -> permissionLauncher.launch(p.toTypedArray()) } },
                onCopy = { text -> copyText(ctx, text) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("more5") {
            val upd by rt.updater.status.collectAsStateWithLifecycle()
            More5Screen(
                s = settings, setup = remember(permsTick) { setupSummary(setupRows(ctx)) }, upd = updateUi(upd),
                version = BuildConfig.VERSION_NAME,
                a = object : More5Actions {
                    override fun update(change: (AppSettings) -> AppSettings) {
                        rt.settings.update(change)
                        rt.permissionsChanged()
                    }
                    override fun openSetup() = nav.navigate("setup")
                    override fun updateAction() {
                        when (val st = upd) {
                            is dev.benfranklin.tether.v5.Updater.State.Available -> {
                                if (dev.benfranklin.tether.v5.Setup.canInstall(ctx)) {
                                    rt.updater.download(st.info)
                                } else {
                                    fixSetup(ctx, "install") {}
                                }
                            }
                            else -> rt.updater.check()
                        }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("features") {
            val sensitive by rt.sensitive.state.collectAsStateWithLifecycle()
            val clipStatus by rt.clipWatcher.status.collectAsStateWithLifecycle()
            val st = remember(permsTick, state.peerCaps, state.link, sensitive, clipStatus) {
                FeatureStatus(
                    connected = state.link is LinkState.Connected,
                    macCaps = state.peerCaps.takeIf { state.link is LinkState.Connected },
                    listenerEnabled = listenerOn,
                    mediaPermission = MediaWatcher.hasPermission(ctx),
                    mediaLimited = MediaWatcher.limitedAccess(ctx),
                    phoneState = BatteryMonitor.hasPhoneState(ctx),
                    fullScreenAlerts = Ringer.canUseFullScreen(ctx),
                    controlEnabled = ControlService.enabledInSettings(ctx),
                    sms = SmsStore.granted(ctx, Manifest.permission.READ_SMS) && SmsStore.granted(ctx, Manifest.permission.SEND_SMS),
                    contacts = SmsStore.granted(ctx, Manifest.permission.READ_CONTACTS),
                    callWatch = CallMonitor.canWatch(ctx),
                    callNumber = SmsStore.granted(ctx, Manifest.permission.READ_CALL_LOG),
                    callDecline = CallMonitor.canDecline(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P,
                    dndAccess = CallMonitor.canSilence(ctx),
                    callPhone = SmsStore.granted(ctx, Manifest.permission.CALL_PHONE),
                    callAnswer = CallMonitor.canAnswer(ctx),
                    sensitiveApplies = SensitiveWatch.applies,
                    sensitiveRedacting = sensitive.redacting,
                    sensitiveGranted = sensitive.granted,
                    oem = Build.MANUFACTURER.orEmpty(),
                    adbCommand = SensitiveWatch.adbCommand(ctx),
                    clipStatus = clipStatus,
                    clipCommands = ClipAutoSteps.commandBlock(ctx.packageName),
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
                    override fun grantSms() = grant("sms")
                    override fun grantContacts() = grant("contacts")
                    override fun grantCalls() = grant("calls")
                    override fun openDndAccess() = grant("dnd")
                    override fun copyAdbCommand() {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", SensitiveWatch.adbCommand(ctx)))
                        rt.note("Command copied")
                    }
                    override fun sendClipCommandsToMac() {
                        rt.sendClip(ClipAutoSteps.commandBlock(ctx.packageName), fromUser = true)
                    }
                    override fun copyClipCommands() {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", ClipAutoSteps.commandBlock(ctx.packageName)))
                        rt.note("Commands copied")
                    }
                    override fun openOverlaySettings() {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        } catch (_: android.content.ActivityNotFoundException) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        }
                    }
                    override fun allowLogAccess() {
                        rt.clipWatcher.retryLogAccess()
                    }
                    override fun openDeveloperOptions() {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: android.content.ActivityNotFoundException) {
                            ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                        }
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
    }

    val sheetInfo = offered
    if (showUpdate && sheetInfo != null) {
        UpdateSheet(
            UpdateSheetModel(
                version = sheetInfo.version, current = BuildConfig.VERSION_NAME,
                changelog = UpdatePrompt.changelog(sheetInfo.notes),
                progress = (upd as? dev.benfranklin.tether.v5.Updater.State.Downloading)?.progress,
                installing = upd is dev.benfranklin.tether.v5.Updater.State.Installing,
                error = (upd as? dev.benfranklin.tether.v5.Updater.State.Failed)?.reason,
            ),
            onUpdate = {
                if (dev.benfranklin.tether.v5.Setup.canInstall(ctx)) {
                    rt.settings.update {
                        it.copy(whatsNewVersion = sheetInfo.version, whatsNewNotes = UpdatePrompt.changelog(sheetInfo.notes))
                    }
                    rt.updater.download(sheetInfo)
                } else {
                    fixSetup(ctx, "install") {}
                }
            },
            onLater = {
                rt.settings.update { it.copy(updateSnoozedUntil = System.currentTimeMillis() + UpdatePrompt.SNOOZE_MS) }
                showUpdate = false
            },
            onSkip = {
                rt.settings.update { it.copy(skippedVersion = sheetInfo.version) }
                showUpdate = false
            },
            onDismiss = {
                // Swiping the sheet away counts as Later.
                rt.settings.update { it.copy(updateSnoozedUntil = System.currentTimeMillis() + UpdatePrompt.SNOOZE_MS) }
                showUpdate = false
            },
        )
    }
    if (whatsNew) {
        val notes = remember { bundledNotes(ctx) } ?: settings.whatsNewNotes.takeIf { settings.whatsNewVersion == BuildConfig.VERSION_NAME }
        WhatsNewSheet(BuildConfig.VERSION_NAME, notes) {
            whatsNew = false
            rt.settings.update { it.copy(whatsNewSeen = BuildConfig.VERSION_NAME, whatsNewVersion = null, whatsNewNotes = null) }
        }
    }
}

/** The release an update is offered / in progress for, if any. */
private fun updateOffered(st: dev.benfranklin.tether.v5.Updater.State): dev.benfranklin.tether.core.features.UpdateInfo? = when (st) {
    is dev.benfranklin.tether.v5.Updater.State.Available -> st.info
    is dev.benfranklin.tether.v5.Updater.State.Downloading -> st.info
    is dev.benfranklin.tether.v5.Updater.State.Installing -> st.info
    else -> null
}

/** Shelf actions backed by the runtime; [navigate] and [pickFile] are set by the composable that owns the NavController. */
private class RuntimeShelfActions(private val ctx: Context, private val rt: TetherRuntime) : ShelfActions {
    var navigate: (String) -> Unit = {}
    var pickFile: () -> Unit = {}
    override fun addText(text: String) = rt.v6.addText(text)
    override fun addFile() = pickFile()
    override fun pasteText(): String? = readClipboardText(ctx)
    override fun remove(id: String) = rt.v6.remove(id)
    override fun restore(item: dev.benfranklin.tether.core.proto.ShelfItem) = rt.v6.restore(item)
    override fun move(id: String, to: Int) = rt.v6.move(id, to)
    override fun primary(item: dev.benfranklin.tether.core.proto.ShelfItem): String = rt.v6.primary(item)
    override fun copy(item: dev.benfranklin.tether.core.proto.ShelfItem): String = rt.v6.copyItem(item)
    override fun open(id: String) = navigate(id)
    override fun rename(id: String, title: String): Boolean = rt.v6.rename(id, title)
    override fun share(item: dev.benfranklin.tether.core.proto.ShelfItem): String = rt.v6.share(item)
    override suspend fun thumbnail(item: dev.benfranklin.tether.core.proto.ShelfItem): ImageBitmap? =
        withContext(Dispatchers.IO) { rt.v6.shelfFiles.thumbnail(item)?.asImageBitmap() }
}

/** This version's section of the CHANGELOG.md bundled in the APK (assets), or null. */
private fun bundledNotes(c: Context): String? = runCatching {
    c.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
}.getOrNull()?.let { WhatsNewPolicy.section(it, BuildConfig.VERSION_NAME) }

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

/** The clipboard's image, if it holds one (only readable while Tether has focus). */
fun readClipboardImage(c: android.content.Context): android.net.Uri? = runCatching {
    val cm = c.getSystemService(android.content.ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    val desc = clip.description
    val isImage = (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
    if (!isImage || clip.itemCount == 0) return null
    // Our own FileProvider = an image that came from the Mac: never echo it back.
    clip.getItemAt(0).uri?.takeIf { it.authority != c.packageName + ".files" }
}.getOrNull()

private const val REDUCED_FADE_MS = 150
/** Launch and foreground checks can fire together; one network check per minute is plenty. */
private const val CHECK_GAP_MS = 60_000L
private const val HOME_PHOTOS = 8
private const val PHOTO_THUMB_PX = 256

/** Checklist rows for the UI (pure mapping of [dev.benfranklin.tether.v5.Setup.items]). */
fun setupRows(c: android.content.Context): List<dev.benfranklin.tether.ui.screens.SetupRow> =
    dev.benfranklin.tether.v5.Setup.items(c).map { i ->
        val adb = i.fix as? dev.benfranklin.tether.v5.Setup.Fix.Adb
        dev.benfranklin.tether.ui.screens.SetupRow(i.id, i.label, i.why, i.ok, i.required, adb?.commands.orEmpty(), adb?.note)
    }

/** Fix one checklist row: ask for runtime permissions, or open the Settings screen that grants it. */
fun fixSetup(c: android.content.Context, id: String, request: (List<String>) -> Unit) {
    val item = dev.benfranklin.tether.v5.Setup.items(c).firstOrNull { it.id == id } ?: return
    when (val f = item.fix) {
        is dev.benfranklin.tether.v5.Setup.Fix.Runtime -> request(f.permissions)
        is dev.benfranklin.tether.v5.Setup.Fix.Screen -> try {
            c.startActivity(f.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (@Suppress("SwallowedException") e: android.content.ActivityNotFoundException) {
            // Some OEMs lack a specific screen: fall back to the app's info page.
            val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))
            c.startActivity(info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        is dev.benfranklin.tether.v5.Setup.Fix.Adb, null -> Unit
    }
}

fun copyText(c: android.content.Context, text: String) {
    c.getSystemService(android.content.ClipboardManager::class.java)
        ?.setPrimaryClip(android.content.ClipData.newPlainText("Tether", text))
    android.widget.Toast.makeText(c, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}
