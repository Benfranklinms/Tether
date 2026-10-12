import AppKit
import TetherCore

/// `--smoke-panel`: CI smoke test for the menu-bar panel (0.5.6, the macOS 27 resize crash).
/// Runs the real StatusController with demo data: opens the attached panel, grows and shrinks its
/// content, detaches, resizes, re-attaches and stresses rapid content changes. Any AppKit exception
/// crashes the process (NSApplicationCrashOnExceptions), a failed check exits 1, a hang is killed by CI.
/// Prints "SMOKE PASS" and exits 0 when everything held.
enum PanelSmoke {
    private static var model: AppModel?
    private static var status: StatusController?
    private static var failures: [String] = []
    private static var savedNotes = NotificationHistory()

    static func run() {
        // Registration domain only (not saved): an AppKit exception aborts instead of being logged and swallowed.
        UserDefaults.standard.register(defaults: ["NSApplicationCrashOnExceptions": true])
        UserDefaults.standard.removeObject(forKey: "detachedPanelFrame")
        let m = AppModel(demo: true)
        Demo.load(m, .connected)
        model = m
        status = StatusController(model: m)
        savedNotes = m.history
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { finish("timed out") }
        steps(stepList[...])
    }

    private typealias Step = (name: String, run: () -> Void)

    private static let stepList: [Step] = [
        ("open attached", { status?.reveal() }),
        ("attached placed", { checkAttached("first open") }),
        ("shrink: clear notifications", { model?.history = NotificationHistory() }),
        ("shrunk", {
            checkAttached("after shrink")
            smallHeight = status?.panelWindow?.frame.height
        }),
        ("grow: notifications + incoming call", {
            model?.history = savedNotes
            model?.call = CallStatePayload(id: "smoke", state: "ringing", number: "+1 415-555-0132", name: "Alex",
                                           ts: Demo.now())
        }),
        ("grown", {
            checkAttached("after grow")
            if let small = smallHeight, let big = status?.panelWindow?.frame.height, big <= small {
                fail("panel did not grow (\(small) -> \(big))")
            }
        }),
        ("detach", { status?.detach() }),
        ("detached", {
            guard let p = status?.panelWindow else { return fail("no panel") }
            if !p.styleMask.contains(.resizable) { fail("detached panel is not resizable") }
            if !p.isVisible { fail("detached panel hidden") }
        }),
        ("resize detached + content change", {
            guard let p = status?.panelWindow else { return }
            p.setFrame(NSRect(x: p.frame.minX, y: p.frame.minY - 160, width: 420, height: p.frame.height + 160), display: true)
            model?.call = nil
        }),
        ("detached kept user size", {
            guard let p = status?.panelWindow else { return }
            if abs(p.frame.width - 420) > 1 { fail("detached width changed to \(p.frame.width)") }
        }),
        ("attach", { status?.attach() }),
        ("attached hidden", { if status?.panelWindow?.isVisible == true { fail("panel still visible after attach") } }),
        ("reopen attached", { status?.reveal() }),
        ("reattached placed", {
            checkAttached("after re-attach")
            if status?.panelWindow?.styleMask.contains(.resizable) == true { fail("attached panel is resizable") }
        }),
        ("rapid content changes", {
            for i in 0..<40 {
                model?.history = i.isMultiple(of: 2) ? NotificationHistory() : savedNotes
                if i.isMultiple(of: 3) {
                    model?.call = i.isMultiple(of: 6) ? nil : CallStatePayload(id: "s\(i)", state: "ringing", ts: 0)
                }
            }
            model?.call = nil
            model?.history = savedNotes
        }),
        ("settled", { checkAttached("after rapid changes") }),
        ("detach/attach toggles", {
            status?.detach()
            status?.attach()
            status?.reveal()
            status?.detach()
            status?.attach()
            status?.reveal()
        }),
        ("final", { checkAttached("after toggles") }),
    ]

    private static var smallHeight: CGFloat?

    private static func steps(_ rest: ArraySlice<Step>) {
        guard let step = rest.first else { return finish(nil) }
        log("step: \(step.name)")
        step.run()
        // Let SwiftUI update, report its height and the coalesced resize land.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { steps(rest.dropFirst()) }
    }

    /// The attached panel is visible, not resizable, as tall as its measured content (clamped to the
    /// screen) and hangs under the menu-bar icon.
    private static func checkAttached(_ when: String) {
        guard let s = status, let p = s.panelWindow else { return fail("\(when): no panel") }
        if !p.isVisible { return fail("\(when): panel not visible") }
        if abs(p.frame.width - StatusController.attachedWidth) > 1 { fail("\(when): width \(p.frame.width)") }
        let screen = p.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? .zero
        let expected = min(max(s.contentHeight, 120), screen.height - 16)
        if s.contentHeight <= 0 { fail("\(when): content height never measured") }
        if abs(p.frame.height - expected) > 2 { fail("\(when): height \(p.frame.height), content \(s.contentHeight)") }
        if let r = s.statusItemScreenRect, abs(p.frame.maxY - (r.minY - 6)) > 2 {
            fail("\(when): top \(p.frame.maxY) not under the status item (\(r.minY))")
        }
        log("\(when): frame \(NSStringFromRect(p.frame)) content \(s.contentHeight)")
    }

    private static func fail(_ message: String) {
        failures.append(message)
        log("FAIL \(message)")
    }

    private static func log(_ s: String) {
        FileHandle.standardError.write(Data("[smoke] \(s)\n".utf8))
    }

    private static func finish(_ error: String?) {
        if let error { failures.append(error) }
        if failures.isEmpty {
            log("SMOKE PASS")
            exit(0)
        }
        log("SMOKE FAIL: \(failures.joined(separator: "; "))")
        exit(1)
    }
}
