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
    @StateObject private var model: AppModel

    init() {
        Typo.registerFonts()
        let m = AppModel(demo: Launch.demo)
        if Launch.demo && Launch.screenshotDir == nil { Demo.load(m, .connected) }
        _model = StateObject(wrappedValue: m)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuView().environmentObject(model).environment(\.tetherStyle, model.settings.appearance)
        } label: {
            MenuLabel(linked: model.isConnected, unpaired: model.peer == nil,
                      battery: model.settings.batteryInMenuBar && model.isConnected ? model.battery : nil)
        }
        .menuBarExtraStyle(.window)

        Window("Pair a phone", id: "pair") {
            PairView().environmentObject(model).environment(\.tetherStyle, model.settings.appearance)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .defaultPosition(.center)

        Window("Tether Settings", id: "settings") {
            SettingsView().environmentObject(model).environment(\.tetherStyle, model.settings.appearance)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .defaultPosition(.center)

        Window("Phone screen", id: "screen") {
            ScreenWindow(screen: model.screen).environmentObject(model).environment(\.tetherStyle, model.settings.appearance)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentMinSize)
        .defaultSize(width: 420, height: 860)
        .defaultPosition(.center)
    }
}

/// Menu-bar glyph. On the very first launch with no phone paired it also opens the pairing window.
private struct MenuLabel: View {
    var linked: Bool
    var unpaired: Bool
    var battery: BatteryPayload?
    @Environment(\.openWindow) private var openWindow
    @AppStorage("didOfferPairing") private var didOfferPairing = false

    var body: some View {
        HStack(spacing: 3) {
            Image(nsImage: MenuIcon.image(linked: linked))
            if let b = battery { Text("\(b.level)%\(b.charging ? "⚡︎" : "")").font(.system(size: 12, weight: .medium).monospacedDigit()) }
        }
            .onAppear {
                guard unpaired, !didOfferPairing, Launch.screenshotDir == nil else { return }
                didOfferPairing = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    NSApp.activate(ignoringOtherApps: true)
                    openWindow(id: "pair")
                }
            }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        if let dir = Launch.screenshotDir {
            Screenshots.run(into: dir) { NSApp.terminate(nil) }
            return
        }
        // SwiftUI may restore Window scenes at launch; a menu-bar app starts quiet.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            for w in NSApp.windows {
                let id = w.identifier?.rawValue ?? ""
                if id.contains("settings") || id.contains("screen") { w.close() }
            }
        }
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
        Shot(name: "screen", scene: .screen) { AnyView(ScreenWindow(screen: ScreenModel()).frame(width: 380, height: 760)) },
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
