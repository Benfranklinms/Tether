import AppKit
import Combine
import SwiftUI
import TetherCore

/// The app's windows, owned by AppKit (not SwiftUI scenes) so the menu-bar panel can be
/// detached into a floating window and every view can open the others the same way.
enum WindowID: String {
    case pair, settings, screen
}

/// Applies the live appearance (Pure Mono / Liquid Glass) and the model to any root view.
struct Themed<Content: View>: View {
    @ObservedObject var model: AppModel
    let content: Content

    var body: some View {
        content
            .environmentObject(model)
            .environment(\.tetherStyle, model.settings.appearance)
    }
}

/// Opens, fronts and closes the pair / settings / screen windows.
final class WindowRouter: NSObject, NSWindowDelegate {
    private weak var model: AppModel?
    private var windows: [WindowID: NSWindow] = [:]

    init(model: AppModel) {
        self.model = model
    }

    func show(_ id: WindowID) {
        NSApp.activate(ignoringOtherApps: true)
        if let w = windows[id] {
            w.makeKeyAndOrderFront(nil)
            return
        }
        guard let model else { return }
        let root: AnyView
        var style: NSWindow.StyleMask = [.titled, .closable, .fullSizeContentView]
        var size: NSSize
        switch id {
        case .pair:
            root = AnyView(PairView())
            size = NSSize(width: 420, height: 540)
        case .settings:
            root = AnyView(SettingsView())
            size = NSSize(width: 560, height: 600)
        case .screen:
            root = AnyView(ScreenWindow(screen: model.screen))
            style.insert(.resizable)
            style.insert(.miniaturizable)
            size = NSSize(width: 420, height: 860)
        }
        let host = NSHostingController(rootView: Themed(model: model, content: root))
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: style, backing: .buffered, defer: false)
        w.contentViewController = host
        w.setContentSize(size)
        w.titleVisibility = .hidden
        w.titlebarAppearsTransparent = true
        w.isMovableByWindowBackground = id != .screen
        w.isReleasedWhenClosed = false
        w.appearance = NSAppearance(named: .darkAqua)
        w.title = id == .pair ? "Pair a phone" : id == .settings ? "Tether Settings" : "Phone screen"
        w.identifier = NSUserInterfaceItemIdentifier(id.rawValue)
        w.delegate = self
        if id == .screen {
            w.contentMinSize = NSSize(width: 300, height: 480)
            w.setFrameAutosaveName("TetherScreenWindow")
        }
        if w.frameAutosaveName.isEmpty || !w.setFrameUsingName(w.frameAutosaveName) { w.center() }
        windows[id] = w
        w.makeKeyAndOrderFront(nil)
    }

    func close(_ id: WindowID) {
        windows[id]?.close()
    }

    func isOpen(_ id: WindowID) -> Bool { windows[id]?.isVisible == true }

    func windowWillClose(_ notification: Notification) {
        guard let w = notification.object as? NSWindow, let raw = w.identifier?.rawValue, let id = WindowID(rawValue: raw) else { return }
        if id == .screen { model?.stopScreen() }
        // A fresh window (and SwiftUI view) next time, so onAppear/onDisappear run as expected.
        DispatchQueue.main.async { self.windows[id] = nil }
    }
}

/// The menu-bar item and its panel. Attached, the panel drops down under the icon and hides
/// when you click elsewhere, like a menu. Drag its header (or press the pin) to tear it off
/// into a floating panel you can move and resize; it stays on top (optional), remembers where
/// it was, and its close button docks it back to the menu bar.
final class StatusController: NSObject, NSWindowDelegate {
    private let model: AppModel
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private var panel: TetherPanel?
    private var subs: Set<AnyCancellable> = []
    private var outsideMonitor: Any?
    private static let frameKey = "detachedPanelFrame"

    init(model: AppModel) {
        self.model = model
        super.init()
        if let button = item.button {
            button.target = self
            button.action = #selector(clicked)
            button.imagePosition = .imageLeading
        }
        model.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] in DispatchQueue.main.async { self?.refresh() } }
            .store(in: &subs)
        refresh()
        if model.settings.panelDetached { DispatchQueue.main.async { self.showDetached() } }
    }

    // MARK: status item

    private func refresh() {
        guard let button = item.button else { return }
        button.image = MenuIcon.image(linked: model.isConnected)
        if model.settings.batteryInMenuBar, model.isConnected, let b = model.battery {
            button.title = " \(b.level)%\(b.charging ? "⚡︎" : "")"
            button.font = NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .medium)
        } else {
            button.title = ""
        }
        button.setAccessibilityLabel(model.isConnected ? "Tether, connected" : "Tether")
        panel?.isDetached = model.settings.panelDetached
        panel?.level = panelLevel
    }

    private var panelLevel: NSWindow.Level {
        model.settings.panelDetached ? (model.settings.panelOnTop ? .floating : .normal) : .popUpMenu
    }

    @objc private func clicked() {
        if model.settings.panelDetached {
            showDetached()
        } else if let p = panel, p.isVisible {
            hideAttached()
        } else {
            showAttached()
        }
    }

    // MARK: panel

    private func makePanel() -> TetherPanel {
        if let p = panel { return p }
        let p = TetherPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 600))
        let root = PanelRoot(model: model, controller: self)
        let host = NSHostingController(rootView: root)
        host.sizingOptions = [.preferredContentSize]
        p.contentViewController = host
        p.delegate = self
        panel = p
        return p
    }

    private func showAttached() {
        let p = makePanel()
        p.isDetached = false
        p.level = panelLevel
        p.layoutIfNeeded()
        let size = p.contentViewController?.view.fittingSize ?? NSSize(width: 360, height: 600)
        p.setContentSize(size)
        if let button = item.button, let bw = button.window {
            let r = bw.convertToScreen(button.convert(button.bounds, to: nil))
            let screen = bw.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
            var x = r.midX - size.width / 2
            x = min(max(x, screen.minX + 8), screen.maxX - size.width - 8)
            p.setFrameTopLeftPoint(NSPoint(x: x, y: r.minY - 6))
        }
        p.makeKeyAndOrderFront(nil)
        watchOutsideClicks()
    }

    private func hideAttached() {
        stopWatching()
        panel?.orderOut(nil)
    }

    private func showDetached() {
        stopWatching()
        let p = makePanel()
        p.isDetached = true
        p.level = panelLevel
        if let s = UserDefaults.standard.string(forKey: Self.frameKey) {
            let f = NSRectFromString(s)
            if f.width > 200, f.height > 200, NSScreen.screens.contains(where: { $0.visibleFrame.intersects(f) }) {
                p.setFrame(f, display: true)
            }
        }
        p.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Tear the attached panel off (header drag or pin). Keeps its current position.
    func detach() {
        guard !model.settings.panelDetached else { return }
        stopWatching()
        model.settings.panelDetached = true
        panel?.isDetached = true
        panel?.level = panelLevel
        saveFrame()
        Log.info("panel.detached")
    }

    /// Dock back to the menu bar (the panel's close button).
    func attach() {
        saveFrame()
        model.settings.panelDetached = false
        panel?.orderOut(nil)
        panel?.isDetached = false
        Log.info("panel.attached")
    }

    func toggleOnTop() {
        model.settings.panelOnTop.toggle()
        panel?.level = panelLevel
    }

    /// Hide the attached panel (e.g. after choosing an action that opens a window).
    func dismissIfAttached() {
        if !model.settings.panelDetached { hideAttached() }
    }

    private func saveFrame() {
        guard let p = panel, model.settings.panelDetached else { return }
        UserDefaults.standard.set(NSStringFromRect(p.frame), forKey: Self.frameKey)
    }

    func windowDidMove(_ notification: Notification) { saveFrame() }
    func windowDidEndLiveResize(_ notification: Notification) { saveFrame() }

    func windowDidResignKey(_ notification: Notification) {
        // Attached behaves like a menu: it goes away when you click elsewhere.
        if !model.settings.panelDetached { hideAttached() }
    }

    private func watchOutsideClicks() {
        stopWatching()
        outsideMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            guard let self, !self.model.settings.panelDetached else { return }
            self.hideAttached()
        }
    }

    private func stopWatching() {
        if let m = outsideMonitor { NSEvent.removeMonitor(m) }
        outsideMonitor = nil
    }

    func open(_ id: WindowID) {
        dismissIfAttached()
        model.windows?.show(id)
    }
}

/// A rounded, shadowed, non-activating panel that can take keyboard focus (reply fields)
/// without stealing activation from the app you were in.
final class TetherPanel: NSPanel {
    var isDetached = false {
        didSet {
            guard oldValue != isDetached else { return }
            if isDetached {
                styleMask.insert(.resizable)
                collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
                contentMinSize = NSSize(width: 340, height: 420)
            } else {
                styleMask.remove(.resizable)
                collectionBehavior = [.canJoinAllSpaces, .transient, .ignoresCycle]
            }
            hidesOnDeactivate = false
        }
    }

    init(contentRect: NSRect) {
        super.init(contentRect: contentRect, styleMask: [.nonactivatingPanel, .titled, .fullSizeContentView],
                   backing: .buffered, defer: false)
        titleVisibility = .hidden
        titlebarAppearsTransparent = true
        for b in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] { standardWindowButton(b)?.isHidden = true }
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        isMovable = true
        isMovableByWindowBackground = false // only the header drags (see PanelDragArea)
        isFloatingPanel = true
        becomesKeyOnlyIfNeeded = false
        isReleasedWhenClosed = false
        appearance = NSAppearance(named: .darkAqua)
        collectionBehavior = [.canJoinAllSpaces, .transient, .ignoresCycle]
        animationBehavior = .utilityWindow
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    override func cancelOperation(_ sender: Any?) {
        if !isDetached { orderOut(nil) }
    }
}

/// What the panel hosts: the menu content with the detach/attach controls, clipped round.
struct PanelRoot: View {
    @ObservedObject var model: AppModel
    weak var controller: StatusController?

    var body: some View {
        Themed(model: model, content:
            MenuView(panel: PanelControls(
                detached: model.settings.panelDetached,
                onTop: model.settings.panelOnTop,
                detach: { controller?.detach() },
                attach: { controller?.attach() },
                toggleOnTop: { controller?.toggleOnTop() },
                open: { controller?.open($0) }
            ))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        )
    }
}

/// Panel actions passed into MenuView (nil in screenshots / previews).
struct PanelControls {
    var detached: Bool
    var onTop: Bool
    var detach: () -> Void
    var attach: () -> Void
    var toggleOnTop: () -> Void
    var open: (WindowID) -> Void
}

/// The draggable header strip. Dragging it moves the panel; dragging the attached panel
/// more than a few points tears it off first.
struct PanelDragArea: NSViewRepresentable {
    var onTearOff: () -> Void

    func makeNSView(context: Context) -> DragView {
        let v = DragView()
        v.onTearOff = onTearOff
        return v
    }

    func updateNSView(_ v: DragView, context: Context) { v.onTearOff = onTearOff }

    final class DragView: NSView {
        var onTearOff: (() -> Void)?
        private var start: NSPoint?

        override func mouseDown(with event: NSEvent) { start = NSEvent.mouseLocation }

        override func mouseDragged(with event: NSEvent) {
            guard let s = start else { return }
            let now = NSEvent.mouseLocation
            if hypot(now.x - s.x, now.y - s.y) > 4 {
                if let p = window as? TetherPanel, !p.isDetached { onTearOff?() }
                start = nil
                window?.performDrag(with: event)
            }
        }

        override func mouseUp(with event: NSEvent) { start = nil }

        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    }
}
