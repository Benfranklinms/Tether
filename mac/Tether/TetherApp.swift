import AppKit
import SwiftUI
import TetherCore

/// Command-line switches:
///   --demo               run with sample data and no radios (try the UI without a phone)
///   --screenshots <dir>  render every screen with sample data into <dir> as PNGs, then quit
enum Launch {
    static let args = ProcessInfo.processInfo.arguments

    static let screenshotDir: URL? = {
        guard let i = args.firstIndex(of: "--screenshots"), i + 1 < args.count else { return nil }
        return URL(fileURLWithPath: args[i + 1], isDirectory: true)
    }()

    static let demo = args.contains("--demo") || screenshotDir != nil || ProcessInfo.processInfo.environment["TETHER_DEMO"] == "1"
}

@main
struct TetherApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    init() {
        Typo.registerFonts()
    }

    var body: some Scene {
        // The menu-bar item, its (detachable) panel and the windows are AppKit-managed;
        // see Support/Windows.swift. SwiftUI needs at least one scene.
        Settings { EmptyView() }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: AppModel?
    private var status: StatusController?
    private var router: WindowRouter?
    private var callPanel: FloatingCallController?
    /// Links that arrived before launch finished.
    private var pendingURLs: [URL] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        if let dir = Launch.screenshotDir {
            Screenshots.run(into: dir) { NSApp.terminate(nil) }
            return
        }
        let m = AppModel(demo: Launch.demo)
        if Launch.demo { Demo.load(m, .connected) }
        let r = WindowRouter(model: m)
        m.windows = r
        model = m
        router = r
        status = StatusController(model: m)
        callPanel = FloatingCallController(model: m)
        let early = pendingURLs
        pendingURLs.removeAll()
        handle(early)
        if Launch.demo || UserDefaults.standard.object(forKey: "mainWindowOpen") == nil
            || UserDefaults.standard.bool(forKey: "mainWindowOpen") || !m.settings.presence.showsMenuBar {
            r.show(.main)
        }
        // First launch with no phone paired: open the pairing window.
        let d = UserDefaults.standard
        if m.peer == nil, !d.bool(forKey: "didOfferPairing") {
            d.set(true, forKey: "didOfferPairing")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { r.show(.pair) }
        }
    }

    /// Opening the app again from Finder/Spotlight shows the panel or settings.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { router?.show(.main) }
        return true
    }

    /// Dock menu: the slim actions.
    func applicationDockMenu(_ sender: NSApplication) -> NSMenu? {
        let menu = NSMenu()
        menu.addItem(withTitle: "Send Clipboard to Phone", action: #selector(dockSendClipboard), keyEquivalent: "")
        menu.addItem(withTitle: "Send File to Phone…", action: #selector(dockSendFile), keyEquivalent: "")
        menu.addItem(withTitle: "Keyboard", action: #selector(dockKeyboard), keyEquivalent: "")
        menu.items.forEach { $0.target = self }
        return menu
    }

    @objc private func dockSendClipboard() { model?.sendClipboardNow() }
    @objc private func dockSendFile() { model?.chooseFiles() }
    @objc private func dockKeyboard() { router?.show(.keyboard) }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// tel: / callto: links (Tether registered as their handler) and anything else opened with Tether.
    /// tel:/callto: links, tether:// links (share extension inbox, Focus automation) and files
    /// dropped on the Dock icon (sent to the phone).
    func application(_ application: NSApplication, open urls: [URL]) {
        guard model != nil else { return pendingURLs.append(contentsOf: urls) }
        handle(urls)
    }

    private func handle(_ urls: [URL]) {
        guard let m = model else { return }
        var files: [URL] = []
        for url in urls {
            let scheme = url.scheme?.lowercased() ?? ""
            if TelURL.schemes.contains(scheme) {
                m.openPhoneLink(url)
            } else if scheme == "tether" {
                TetherLinks.handle(url, model: m)
            } else if url.isFileURL {
                files.append(url)
            }
        }
        if !files.isEmpty { m.sendFiles(files) }
    }
}

/// Renders real views with demo data in an on-screen borderless window and writes PNGs
/// (CI uses this for the README images).
/// tether://inbox (Finder Share extension), tether://dnd?on=1|0 (Focus automations),
/// tether://open (bring up the window).
enum TetherLinks {
    static func handle(_ url: URL, model m: AppModel) {
        let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        switch url.host?.lowercased() {
        case "inbox": ShareInbox.drain(into: m)
        case "dnd":
            let on = q.first { $0.name == "on" }?.value
            m.macFocusChanged(on == "1" || on == "true")
        case "open": m.windows?.show(.main)
        case "send":
            if let t = q.first(where: { $0.name == "text" })?.value { m.openOnPhone(text: t) }
        default: Log.info("link.unknown", ["host": url.host ?? "-"])
        }
    }
}

enum Screenshots {
    struct Shot {
        let name: String
        let scene: Demo.Shot
        let view: () -> AnyView
    }

    static let shots: [Shot] = [
        Shot(name: "menu", scene: .connected) { AnyView(MenuView()) },
        Shot(name: "menu-transfers", scene: .connected) { AnyView(MenuView(initialTab: .transfers)) },
        Shot(name: "menu-unpaired", scene: .unpaired) { AnyView(MenuView()) },
        Shot(name: "pair-qr", scene: .qr) { AnyView(PairView()) },
        Shot(name: "pair-code", scene: .code) { AnyView(PairView()) },
        Shot(name: "pair-done", scene: .done) { AnyView(PairView()) },
        Shot(name: "pair-linking", scene: .linking) { AnyView(PairView()) },
        Shot(name: "pair-failed", scene: .failed) { AnyView(PairView()) },
        Shot(name: "settings-general", scene: .connected) { AnyView(SettingsView(initialSection: .general)) },
        Shot(name: "settings-notifications", scene: .connected) { AnyView(SettingsView(initialSection: .notifications)) },
        Shot(name: "settings-phone", scene: .connected) { AnyView(SettingsView(initialSection: .phone)) },
        Shot(name: "settings-about", scene: .connected) { AnyView(SettingsView(initialSection: .about)) },
        Shot(name: "settings-features", scene: .connected) { AnyView(SettingsView(initialSection: .features)) },
        Shot(name: "menu-detached", scene: .connected) {
            AnyView(MenuView(panel: PanelControls(detached: true, onTop: true, detach: {}, attach: {}, toggleOnTop: {}, open: { _ in }))
                .frame(width: 380, height: 640)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous)))
        },
        Shot(name: "screen", scene: .screen) { AnyView(ScreenWindow(screen: ScreenModel()).frame(width: 380, height: 760)) },
        Shot(name: "menu-call", scene: .call) { AnyView(MenuView()) },
        Shot(name: "messages", scene: .connected) { AnyView(MessagesView().frame(width: 820, height: 560)) },
        Shot(name: "menu-live-call", scene: .liveCall) { AnyView(MenuView()) },
        Shot(name: "menu-calls", scene: .calls) { AnyView(MenuView(initialTab: .calls)) },
        Shot(name: "menu-codes", scene: .codes) { AnyView(MenuView()) },
        // 0.5.0
        Shot(name: "slim-menu", scene: .connected) { AnyView(SlimMenuView()) },
        Shot(name: "main-home", scene: .connected) { AnyView(main(.home)) },
        Shot(name: "main-files", scene: .connected) { AnyView(main(.files)) },
        Shot(name: "main-photos", scene: .connected) { AnyView(main(.photos)) },
        Shot(name: "main-clipboard", scene: .connected) { AnyView(main(.clipboard)) },
        Shot(name: "main-notifications", scene: .connected) { AnyView(main(.notifications)) },
        Shot(name: "main-messages", scene: .connected) { AnyView(main(.messages)) },
        Shot(name: "main-calls", scene: .calls) { AnyView(main(.calls)) },
        Shot(name: "settings-appearance", scene: .connected) { AnyView(SettingsView(initialSection: .appearance)) },
        Shot(name: "settings-mac", scene: .connected) { AnyView(SettingsView(initialSection: .mac)) },
        Shot(name: "settings-updates", scene: .connected) { AnyView(SettingsView(initialSection: .updates)) },
        Shot(name: "keyboard", scene: .connected) { AnyView(KeyboardView(v5: V5State())) },
        // 0.5.1
        Shot(name: "main-photos-viewer", scene: .connected) {
            AnyView(MainShot(page: .photos) { m in m.v5.viewer = m.v5.library.items.first })
        },
        Shot(name: "main-files-grid", scene: .connected) {
            AnyView(MainShot(page: .files) { _ in UserDefaults.standard.set("grid", forKey: FilesViewMode.key(root: FsRoots.downloads)) })
        },
        Shot(name: "settings-setup", scene: .connected) { AnyView(SettingsView(initialSection: .setup)) },
        Shot(name: "call-banner", scene: .call) { AnyView(FloatingCallView(onClose: { _ in }).padding(20)) },
    ]

    /// The main window on one page (the model's V5State is swapped in by `next`).
    private static func main(_ page: MainPage) -> some View {
        MainShot(page: page) { _ in UserDefaults.standard.removeObject(forKey: FilesViewMode.key(root: FsRoots.downloads)) }
    }

    /// Dark first, then Light ("-light" suffix).
    static var appearance: NSAppearance? = NSAppearance(named: .darkAqua)
    private static var lightPass = false

    private static var window: NSWindow?

    static func run(into dir: URL, done: @escaping () -> Void) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        next(shots[...], dir) {
            lightPass = true
            appearance = NSAppearance(named: .aqua)
            NSApp.appearance = appearance
            next(shots[...], dir, done)
        }
    }

    private static func next(_ rest: ArraySlice<Shot>, _ dir: URL, _ done: @escaping () -> Void) {
        guard let shot = rest.first else {
            window?.orderOut(nil)
            done()
            return
        }
        let model = AppModel(demo: true)
        Demo.load(model, shot.scene)
        Demo5.load(model)
        let host = NSHostingView(rootView: shot.view().environmentObject(model).environment(\.tetherStyle, model.settings.appearance))
        let size = host.fittingSize
        host.frame = NSRect(origin: .zero, size: size)
        let w = window ?? NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window = w
        w.isReleasedWhenClosed = false
        w.appearance = Screenshots.appearance
        w.backgroundColor = Mono.nsBg
        w.contentView = host
        w.setContentSize(size)
        w.setFrameOrigin(NSPoint(x: 40, y: 40))
        w.orderFrontRegardless()
        // Let springs, the code reveal and the QR pop settle before capturing.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            capture(host, to: dir.appendingPathComponent("\(shot.name)\(lightPass ? "-light" : "").png"))
            next(rest.dropFirst(), dir, done)
        }
    }

    private static func capture(_ view: NSView, to url: URL) {
        view.layoutSubtreeIfNeeded()
        guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        if let png = rep.representation(using: .png, properties: [:]) {
            try? png.write(to: url)
            print("screenshot \(url.lastPathComponent) \(Int(view.bounds.width))x\(Int(view.bounds.height))")
        }
    }
}
