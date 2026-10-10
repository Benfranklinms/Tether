import AppKit
import SwiftUI
import TetherCore

extension AppModel {
    /// The Mac setup checklist: what Tether needs on this Mac (and from the phone), each with a Fix.
    var setupItems: [SetupItem] {
        var items: [SetupItem] = [
            SetupItem(id: "pair", title: "Phone paired", detail: peer?.name ?? "Pair your Android phone.", ok: peer != nil, fix: "pair"),
            SetupItem(id: "bluetooth", title: "Bluetooth", detail: bluetooth == .on ? "On and allowed" : "Turn it on and allow Tether.",
                      ok: bluetooth == .on, fix: "bluetooth"),
            SetupItem(id: "notifications", title: "Notifications", detail: notificationsAllowed ? "Allowed" : "Banners for phone alerts.",
                      ok: notificationsAllowed || PanelOnlyAlerts.isOn(), fix: "notifications"),
            SetupItem(id: "login", title: "Open at login", detail: "Stay connected after a restart.", ok: launchAtLogin,
                optional: true, fix: "login"),
            SetupItem(id: "updates", title: "Updates", detail: "Checking \(settings.updateFeed?.isEmpty == false ? "your feed" : "GitHub releases")",
                      ok: true, optional: true, fix: "updates"),
        ]
        if settings.proximityUnlock {
            items.append(SetupItem(id: "accessibility", title: "Accessibility",
                detail: "Needed to type your password for proximity unlock.",
                                   ok: v5.accessibilityTrusted, fix: "accessibility"))
        }
        if let st = v5.phoneStatus {
            for p in st.perms where !p.ok {
                items.append(SetupItem(id: "phone-\(p.id)", title: "Phone: \(p.label)", detail: "Allow it in Tether › Setup on your phone.",
                                       ok: false, optional: !p.required, fix: "phone"))
            }
        }
        return items
    }

    func fixSetup(_ fix: String) {
        switch fix {
        case "pair": windows?.show(.pair)
        case "bluetooth": openBluetoothPrivacy()
        case "notifications": openNotificationSettings()
        case "login": setLaunchAtLogin(true)
        case "updates":
            v5.page = .settings
            windows?.show(.main)
            NotificationCenter.default.post(name: .tetherOpenSettingsSection, object: "Updates")
        case "accessibility": Accessibility.request()
        case "phone":
            refreshPhoneStatus()
            toast("Open Tether › Setup on your phone")
        default: break
        }
    }
}

extension Notification.Name {
    static let tetherOpenSettingsSection = Notification.Name("tether.openSettingsSection")
}

struct SetupChecklist: View {
    @EnvironmentObject var m: AppModel
    var compact = false

    var body: some View {
        let items = m.setupItems
        let open = items.filter { !$0.ok }
        if !compact || !open.isEmpty {
            SectionLabel(text: compact ? "Finish setting up" : "Setup")
            Card {
                ForEach(Array((compact ? open : items).enumerated()), id: \.element.id) { i, item in
                    Row(title: item.title, detail: item.detail, divider: i < (compact ? open : items).count - 1) {
                        if item.ok {
                            Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)).foregroundColor(Mono.text)
                                .accessibilityLabel("Done")
                        } else if let fix = item.fix {
                            GhostButton(title: item.optional ? "Set up" : "Fix") { m.fixSetup(fix) }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Keyboard window (type into the phone)

struct KeyboardView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Type on your phone").font(Typo.sans(17)).foregroundColor(Mono.text)
            Text("Tap a text field on your phone, then type here. Return sends Enter, ⌘V pastes the Mac clipboard.")
                .font(Typo.sans(12)).foregroundColor(Mono.muted)
            KeyCapture { ops in m.typeOps(ops) } paste: {
                m.clipboard.currentText ?? ""
            }
            .frame(maxWidth: .infinity, minHeight: 120)
            .tetherCard()
            .overlay(Text(v5.typeField.map { "Typing into \($0)" } ?? "Click here and type")
                .font(Typo.mono(11)).foregroundColor(Mono.muted))
            if let s = v5.typeStatus { Text(s).font(Typo.sans(12, .medium)).foregroundColor(Mono.text) }
            HStack {
                GhostButton(title: "Backspace") { m.typeOps([.key(TypeKeys.backspace)]) }
                GhostButton(title: "Enter") { m.typeOps([.key(TypeKeys.enter)]) }
                GhostButton(title: "Clear field") { m.typeOps([.key(TypeKeys.clear)]) }
            }
        }
        .padding(20)
        .padding(.top, 14)
        .frame(width: 440)
        .tetherBackground()
        .tetherWindowChrome()
    }
}

/// An NSView that takes key focus and turns keystrokes into TYPE_TEXT batches.
struct KeyCapture: NSViewRepresentable {
    var send: ([TypeBatcher.Op]) -> Void
    var paste: () -> String

    func makeNSView(context: Context) -> CaptureView {
        let v = CaptureView()
        v.send = send
        v.paste = paste
        return v
    }

    func updateNSView(_ v: CaptureView, context: Context) {
        v.send = send
        v.paste = paste
    }

    final class CaptureView: NSView {
        var send: (([TypeBatcher.Op]) -> Void)?
        var paste: (() -> String)?
        private var batcher = TypeBatcher()
        private var flushWork: DispatchWorkItem?

        override var acceptsFirstResponder: Bool { true }
        override func mouseDown(with event: NSEvent) { window?.makeFirstResponder(self) }
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            DispatchQueue.main.async { self.window?.makeFirstResponder(self) }
        }

        override func keyDown(with event: NSEvent) {
            let cmd = event.modifierFlags.contains(.command)
            if cmd && event.charactersIgnoringModifiers == "v" {
                emit(batcher.paste(paste?() ?? ""))
                return
            }
            emit(batcher.press(keyCode: event.keyCode, characters: event.characters, command: cmd))
            if batcher.hasPending { scheduleFlush() }
        }

        private func scheduleFlush() {
            flushWork?.cancel()
            let w = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.emit(self.batcher.flush())
            }
            flushWork = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: w)
        }

        private func emit(_ ops: [TypeBatcher.Op]) {
            if !ops.isEmpty { send?(ops) }
        }
    }
}
