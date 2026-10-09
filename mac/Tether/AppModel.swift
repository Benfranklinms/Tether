import AppKit
import Combine
import Foundation
import TetherCore

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

    let demo: Bool
    private var engine: Engine?
    private let clipboard = ClipboardWatcher()
    private let notifier = Notifier()
    private let settingsFile = JSONFile<MacSettings>("settings.json")
    private let historyFile = JSONFile<NotificationHistory>("history.json")
    private var historyWrite: DispatchWorkItem?
    private var noticeClear: DispatchWorkItem?

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
        notifier.requestAuthorization { ok in DispatchQueue.main.async { self.notificationsAllowed = ok } }
        clipboard.onCopy = { [weak self] text in
            guard let self, self.settings.sendClipboard, self.isConnected else { return }
            self.engine?.sendClip(text, manual: false)
        }
        clipboard.start()
        e.start()
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

    func apply(link: LinkState, peer: PeerRecord?) {
        self.link = link
        self.peer = peer
        if isConnected { lastError = nil }
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
                body: p.body, banner: p.banner, sound: settings.notifications.sound
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
        if demo {
            pairingURI = Demo.pairingURI
            return
        }
        engine?.startPairing { [weak self] uri in self?.pairingURI = uri }
    }

    func stopPairing() {
        pairingURI = nil
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

    func openNotificationSettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications")
        if let url { NSWorkspace.shared.open(url) }
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
        let header = "Tether \(Engine.version) · macOS \(os) · link=\(link) bluetooth=\(bluetooth)"
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
