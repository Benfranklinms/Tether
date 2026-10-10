import AppKit
import Combine
import Foundation
import TetherCore
import UserNotifications

/// UI state for every window. Only touched on the main thread (`Engine` hops there).
final class AppModel: ObservableObject {
    @Published var link: LinkState = .offline
    @Published var bluetooth: BluetoothStatus = .unknown
    @Published var peer: PeerRecord?
    @Published var history = NotificationHistory()
    @Published var transfers: [TransferRecord] = []
    @Published var settings: MacSettings { didSet { settingsChanged(oldValue) } }
    @Published var pairingURI: String?
    @Published var justPaired = false
    @Published var lastError: String?
    @Published var notice: String?
    @Published var launchAtLogin = LoginItem.isEnabled
    @Published var notificationsAllowed = true
    @Published var myFingerprint = ""
    @Published var radio = RadioDiag()
    /// Set when a pairing attempt got stuck after the phone connected; the pair window shows it.
    @Published var pairFailure: String?
    // 0.2.0
    /// Features the connected phone announced (empty offline; a 0.1.x phone lacks the new ones).
    @Published var peerCaps: Set<String> = []
    @Published var peerApp: String?
    @Published var battery: BatteryPayload?
    @Published var ringing = false
    @Published var ringRequested = false
    /// Per notification key: "sending", "sent" or an error, for the reply field.
    @Published var actionStatus: [String: String] = [:]
    let screen = ScreenModel()
    // 0.3.0
    @Published var sms = SmsModel()
    /// The incoming call ringing right now (nil when none).
    @Published var call: CallStatePayload?
    @Published var callNote: String?
    @Published var nowPlaying: NowPlayingPayload?
    @Published var artwork: NSImage?
    @Published var proximity = ProximityStatus()
    var artCache: [String: NSImage] = [:]
    /// Phone clock minus Mac clock (ms), estimated from now-playing timestamps.
    var phoneClockSkew: Int64 = 0
    var proximityMonitor = ProximityMonitor()
    var lockPolicy = LockPolicy()
    var proximityTimer: Timer?
    var mediaKeys: MediaKeys?
    /// Owns the pair / settings / screen windows (set by the app delegate).
    var windows: WindowRouter?
    var lowBattery = LowBatteryMonitor()

    /// How long the pair window waits for the secure link once the phone has connected.
    static let linkingLimit: TimeInterval = 45
    private var linkingSince: Date?
    private var linkingCheck: DispatchWorkItem?

    let demo: Bool
    var engine: Engine?
    private let clipboard = ClipboardWatcher()
    let notifier = Notifier()
    private let settingsFile = JSONFile<MacSettings>("settings.json")
    private let historyFile = JSONFile<NotificationHistory>("history.json")
    private var historyWrite: DispatchWorkItem?
    private var noticeClear: DispatchWorkItem?
    private var activeObserver: NSObjectProtocol?

    init(demo: Bool = false) {
        self.demo = demo
        settings = demo ? MacSettings() : (JSONFile<MacSettings>("settings.json").read() ?? MacSettings())
        guard !demo else { return }
        history = historyFile.read() ?? NotificationHistory()
        let e = Engine(model: self, settings: settings)
        engine = e
        peer = e.peer
        transfers = e.savedTransfers
        myFingerprint = e.myFingerprint
        notifier.setUp()
        notifier.onDismiss = { [weak self] key in
            DispatchQueue.main.async { self?.dismiss(key, fromSystem: true) }
        }
        notifier.onOpen = { _ in NSApp.activate(ignoringOtherApps: true) }
        notifier.onAction = { [weak self] key, action, text in
            DispatchQueue.main.async { self?.performAction(key: key, action: action, text: text) }
        }
        notifier.onCall = { [weak self] id, action in
            DispatchQueue.main.async { self?.callAction(id: id, action: action) }
        }
        notifier.onMedia = { path, reveal in
            DispatchQueue.main.async {
                let url = URL(fileURLWithPath: path)
                if reveal { NSWorkspace.shared.activateFileViewerSelecting([url]) } else { NSWorkspace.shared.open(url) }
            }
        }
        e.attach(sink: screen.sink)
        screen.sink.onFirstFrame = { [weak self] in self?.screen.hasVideo = true }
        notifier.requestAuthorization { ok in DispatchQueue.main.async { self.notificationsAllowed = ok } }
        // Coming back from System Settings: pick up a change to notification permission.
        activeObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.refreshNotificationAuth() }
        clipboard.onCopy = { [weak self] text in
            guard let self, self.settings.sendClipboard, self.isConnected else { return }
            self.engine?.sendClip(text, manual: false)
        }
        clipboard.start()
        e.start()
        setUp3()
    }

    var isConnected: Bool {
        if case .connected = link { return true }
        return false
    }

    var onWifi: Bool {
        if case .connected(_, let w) = link { return w }
        return false
    }

    // MARK: engine -> UI

    func apply(link: LinkState, peer: PeerRecord?, caps: Set<String> = [], peerApp: String? = nil, wifi: Bool = false) {
        // Read before assigning: 0.3.0 read it after, so reconnect hooks (re-requesting the
        // Messages list, clearing a stale call) never ran.
        let wasConnected = isConnected
        self.link = link
        self.peer = peer
        self.peerCaps = caps
        self.peerApp = peerApp
        if case .connected = link {} else {
            battery = nil
            ringing = false
            ringRequested = false
        }
        linkChanged3(wasConnected: wasConnected)
        if screen.phase == .live || screen.phase == .consent || screen.phase == .requesting {
            if case .connected(_, let w) = link, w {} else {
                screen.phase = .ended("The Wi-Fi link to your phone dropped")
                screen.hasVideo = false
            }
        }
        if isConnected { lastError = nil }
        trackLinking()
    }

    /// One line for the pair window: what Bluetooth is doing right now.
    var pairStatus: String {
        switch bluetooth {
        case .off: return "Bluetooth is off. Turn it on in Control Center."
        case .unauthorized: return "Tether isn't allowed to use Bluetooth."
        case .unsupported: return "This Mac doesn't support Bluetooth LE."
        case .unknown: return "Starting Bluetooth…"
        case .on: break
        }
        if case .handshaking = link { return "Phone connected · securing the link…" }
        if case .pairing = link { return "Secure link ready · compare the codes" }
        if let e = radio.advertiseError { return "Bluetooth couldn't advertise: \(e)" }
        if !radio.serviceReady || !radio.advertising { return "Getting Bluetooth ready…" }
        if radio.subscribed { return "Phone connected · starting…" }
        return "Visible over Bluetooth · waiting for your phone"
    }

    /// Start a 45 s watch the first time the phone connects during pairing; if the codes
    /// never appear, the pair window switches to an explained error instead of spinning.
    private func trackLinking() {
        guard pairingURI != nil, !demo else { return }
        switch link {
        case .handshaking:
            guard linkingSince == nil else { return }
            linkingSince = Date()
            let w = DispatchWorkItem { [weak self] in
                guard let self, self.pairingURI != nil, !self.justPaired else { return }
                if case .pairing = self.link { return }
                if case .connected = self.link { return }
                Log.warn("pair.stuck", ["radio": self.radio.description])
                self.pairFailure = "Your phone connected, but the secure link didn't finish. "
                    + "Try again with both devices close. If it keeps happening, export diagnostics on both."
            }
            linkingCheck?.cancel()
            linkingCheck = w
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.linkingLimit, execute: w)
        case .pairing, .connected:
            linkingCheck?.cancel()
            linkingSince = nil
        case .offline:
            break
        }
    }

    func retryPairing() {
        pairFailure = nil
        linkingCheck?.cancel()
        linkingSince = nil
        engine?.stopPairing()
        pairingURI = nil
        startPairing()
    }

    func openBluetoothPrivacy() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth")
        if let url { NSWorkspace.shared.open(url) }
    }

    func paired(_ p: PeerRecord) {
        peer = p
        pairingURI = nil
        justPaired = true
    }

    func unpaired() {
        peer = nil
        link = .offline
        toast("Unpaired")
    }

    func receiveClip(_ text: String) {
        clipboard.set(text)
    }

    func receiveNotification(_ n: NotifPayload) {
        let p = MacNotificationPolicy.present(n, settings: settings.notifications)
        history.noteApp(n)
        guard p.record else { return scheduleHistoryWrite() }
        history.add(n, limit: settings.notifications.historyLimit)
        scheduleHistoryWrite()
        if notificationsAllowed {
            notifier.post(
                key: n.key, threadId: n.pkg, title: p.title, subtitle: settings.notifications.privacyMode ? nil : n.app,
                body: p.body, banner: p.banner, sound: settings.notifications.sound,
                actions: settings.notificationActions && peerCaps.contains(Caps.reply) ? n.actions : []
            )
        }
    }

    func notificationRemoved(_ key: String) {
        guard settings.notifications.dismissSync else { return }
        if history.remove(key: key) { scheduleHistoryWrite() }
        notifier.remove(key: key)
    }

    func fileReceived(_ r: TransferRecord) {
        notifier.postLocal(id: "file-\(r.id)", title: "Received \(r.name)", body: "Saved to \(settings.downloadURL.lastPathComponent)")
    }

    func toast(_ s: String) {
        notice = s
        noticeClear?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.notice = nil }
        noticeClear = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5, execute: w)
    }

    // MARK: UI intents

    func startPairing() {
        justPaired = false
        pairFailure = nil
        linkingSince = nil
        NSApp.activate(ignoringOtherApps: true)
        if demo {
            pairingURI = Demo.pairingURI
            return
        }
        engine?.startPairing { [weak self] uri in self?.pairingURI = uri }
    }

    func stopPairing() {
        pairingURI = nil
        pairFailure = nil
        linkingCheck?.cancel()
        linkingSince = nil
        engine?.stopPairing()
    }

    func confirmPairing() {
        if demo {
            Demo.finishPairing(self)
            return
        }
        engine?.confirmPairing()
    }

    func rejectPairing() { engine?.rejectPairing() }

    func sendClipboardNow() {
        guard let text = clipboard.currentText, !text.isEmpty else { return toast("The clipboard is empty") }
        engine?.sendClip(text, manual: true)
    }

    func sendFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        engine?.sendFiles(urls)
        toast(urls.count == 1 ? "Sending \(urls[0].lastPathComponent)" : "Sending \(urls.count) files")
    }

    func chooseFiles() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        panel.message = "Choose files to send to your phone"
        if panel.runModal() == .OK { sendFiles(panel.urls) }
    }

    func chooseDownloadFolder() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.prompt = "Use folder"
        if panel.runModal() == .OK, let url = panel.url { settings.downloadFolder = url.path }
    }

    func cancel(_ id: String) { engine?.cancel(id) }
    func retry(_ id: String) { engine?.retry(id) }
    func remove(_ id: String) { engine?.remove(id) }

    func reveal(_ r: TransferRecord) {
        if r.direction == .IN, let path = r.ref {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
        } else {
            NSWorkspace.shared.open(settings.downloadURL)
        }
    }

    func openDownloads() {
        try? FileManager.default.createDirectory(at: settings.downloadURL, withIntermediateDirectories: true)
        NSWorkspace.shared.open(settings.downloadURL)
    }

    /// Dismiss from the menu list (or the system dismissed the banner).
    func dismiss(_ key: String, fromSystem: Bool = false) {
        if history.remove(key: key) { scheduleHistoryWrite() }
        if !fromSystem { notifier.remove(key: key) }
        engine?.dismissOnPhone(key)
    }

    func clearHistory() {
        history.clear()
        notifier.removeAll()
        scheduleHistoryWrite()
    }

    func unpair() { engine?.unpair() }
    func rotateKey() { engine?.rotateKey() }

    /// Panel button: rebuild the Bluetooth side now (what quitting and reopening used to do).
    func reconnect() {
        Log.info("ui.reconnect")
        engine?.reconnect()
        toast("Reconnecting…")
    }

    /// System Settings › Notifications (Tether's row when macOS supports deep links to it).
    func openNotificationSettings() {
        let id = Bundle.main.bundleIdentifier ?? ""
        let candidates = [
            "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(id)",
            "x-apple.systempreferences:com.apple.Notifications-Settings.extension",
            "x-apple.systempreferences:com.apple.preference.notifications",
        ]
        for c in candidates {
            if let url = URL(string: c), NSWorkspace.shared.open(url) { return }
        }
    }

    /// Re-reads whether macOS lets Tether post notifications (the user may have just changed it).
    func refreshNotificationAuth() {
        guard !demo else { return }
        notifier.authorizationStatus { status in
            let ok = status == .authorized || status == .provisional
            DispatchQueue.main.async {
                if ok != self.notificationsAllowed { Log.info("notify.auth", ["allowed": "\(ok)"]) }
                self.notificationsAllowed = ok
            }
        }
    }

    func openLogsFolder() {
        NSWorkspace.shared.open(Paths.logs)
    }

    func setLaunchAtLogin(_ on: Bool) {
        guard !demo else {
            launchAtLogin = on
            return
        }
        if let msg = LoginItem.set(on) { toast(msg) }
        launchAtLogin = LoginItem.isEnabled
    }

    func exportLogs() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "tether-logs.txt"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let os = ProcessInfo.processInfo.operatingSystemVersionString
        let header = "Tether \(Engine.version) · macOS \(os) · link=\(link) bluetooth=\(bluetooth) radio=\(radio)"
        do {
            try Log.export(header: header).write(to: url, options: .atomic)
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } catch {
            toast("Couldn't save the logs")
        }
    }

    // MARK: internals

    private func settingsChanged(_ old: MacSettings) {
        guard settings != old, !demo else { return }
        settingsFile.write(settings)
        engine?.apply(settings)
        settingsChanged3(old)
        if settings.notifications.historyLimit != old.notifications.historyLimit {
            history.trim(settings.notifications.historyLimit)
            scheduleHistoryWrite()
        }
    }

    private func scheduleHistoryWrite() {
        guard !demo else { return }
        historyWrite?.cancel()
        let snapshot = history
        let w = DispatchWorkItem { [historyFile] in historyFile.write(snapshot) }
        historyWrite = w
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 1, execute: w)
    }
}
