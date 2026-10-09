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
        // First launch with no phone paired: open the pairing window.
        let d = UserDefaults.standard
        if m.peer == nil, !d.bool(forKey: "didOfferPairing") {
            d.set(true, forKey: "didOfferPairing")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { r.show(.pair) }
        }
    }

    /// Opening the app again from Finder/Spotlight shows the panel or settings.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { router?.show(.settings) }
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

/// Renders real views with demo data in an on-screen borderless window and writes PNGs
/// (CI uses this for the README images).
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
    ]

    private static var window: NSWindow?

    static func run(into dir: URL, done: @escaping () -> Void) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        next(shots[...], dir, done)
    }

    private static func next(_ rest: ArraySlice<Shot>, _ dir: URL, _ done: @escaping () -> Void) {
        guard let shot = rest.first else {
            window?.orderOut(nil)
            done()
            return
        }
        let model = AppModel(demo: true)
        Demo.load(model, shot.scene)
        let host = NSHostingView(rootView: shot.view().environmentObject(model).environment(\.tetherStyle, model.settings.appearance))
        let size = host.fittingSize
        host.frame = NSRect(origin: .zero, size: size)
        let w = window ?? NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window = w
        w.isReleasedWhenClosed = false
        w.appearance = NSAppearance(named: .darkAqua)
        w.backgroundColor = NSColor(calibratedRed: 0x09 / 255, green: 0x09 / 255, blue: 0x0A / 255, alpha: 1)
        w.contentView = host
        w.setContentSize(size)
        w.setFrameOrigin(NSPoint(x: 40, y: 40))
        w.orderFrontRegardless()
        // Let springs, the code reveal and the QR pop settle before capturing.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            capture(host, to: dir.appendingPathComponent("\(shot.name).png"))
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
