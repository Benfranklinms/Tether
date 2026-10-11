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
        ]
        if settings.proximityUnlock {
            items.append(SetupItem(id: "accessibility", title: "Accessibility",
                detail: "Proximity unlock (types your password)",
                                   ok: v5.accessibilityTrusted, optional: true, fix: "accessibility"))
        }
        if let st = v5.phoneStatus {
            // 0.5.1: every phone row (done ones too, for the progress ring); the subtitle says what it unlocks.
            for p in st.perms {
                let e = SetupPlan.phoneEntry(p)
                items.append(SetupItem(id: e.id, title: e.title, detail: e.unlocks, ok: p.ok, optional: !e.required, fix: e.fix))
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
        case "accessibility": Accessibility.request()
        case "phone":
            refreshPhoneStatus()
            toast("Open Tether › Setup on your phone")
        case let f where f.hasPrefix("phone:"):
            openPhoneSetup(String(f.dropFirst("phone:".count)))
        default: break
        }
    }
}

extension Notification.Name {
    static let tetherOpenSettingsSection = Notification.Name("tether.openSettingsSection")
}

/// The full checklist (Settings › Setup): required first, then optional rows under the feature they unlock.
struct SetupChecklist: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let entries = m.setupEntries
        let required = SetupPlan.required(entries)
        HStack(spacing: 12) {
            ProgressRing(progress: SetupPlan.progress(entries), size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(SetupPlan.summary(entries)).font(Typo.sans(14, .medium)).foregroundColor(Mono.text)
                Text("Optional items only unlock extra features.").font(Typo.sans(11)).foregroundColor(Mono.muted)
            }
            Spacer()
            GhostButton(title: "Check again") { m.refreshPhoneStatus() }
        }
        .padding(.vertical, 4)
        SectionLabel(text: "Required")
        group(required)
        ForEach(SetupPlan.optionalByFeature(entries), id: \.feature) { g in
            SectionLabel(text: "Optional · \(g.feature)")
            group(g.entries)
        }
    }

    private func group(_ list: [SetupEntry]) -> some View {
        Card {
            ForEach(Array(list.enumerated()), id: \.element.id) { i, e in
                Row(title: e.title, detail: e.unlocks, divider: i < list.count - 1) {
                    if e.ok {
                        Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)).foregroundColor(Mono.text)
                            .transition(.scale.combined(with: .opacity))
                            .accessibilityLabel("Done")
                    } else if let fix = e.fix {
                        GhostButton(title: e.required ? "Fix" : "Set up") { m.fixSetup(fix) }
                    }
                }
            }
        }
        .animation(Motion.spring(reduce), value: list.map(\.ok))
    }
}

/// Home (0.5.1): one slim row while something REQUIRED is missing, instead of a block of rows.
/// "Hide" is remembered until a required permission that was fine goes missing again.
struct SetupHomeRow: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let entries = m.setupEntries
        let missing = SetupPlan.missingRequired(entries)
        Group {
            if m.setupHomeVisible {
                HStack(spacing: 12) {
                    ProgressRing(progress: SetupPlan.progress(entries), size: 22)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(SetupPlan.summary(entries)).font(Typo.sans(13, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                        Text(missing.map(\.title).joined(separator: ", ")).font(Typo.sans(11)).foregroundColor(Mono.muted)
                            .lineLimit(1).truncationMode(.tail)
                    }
                    .layoutPriority(1)
                    Spacer(minLength: 8)
                    Button("Hide") { withAnimation(Motion.spring(reduce)) { m.hideSetupRow() } }
                        .buttonStyle(.plain).font(Typo.sans(11, .medium)).foregroundColor(Mono.muted)
                    GhostButton(title: "Review") { m.openSetupSettings() }
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .tetherCard(highlighted: hover)
                .onHover { hover = $0 }
                .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity),
                                        removal: .scale(scale: 0.96).combined(with: .opacity)))
            }
        }
        .animation(Motion.spring(reduce), value: m.setupHomeVisible)
        .onChange(of: missing.map(\.id)) { _ in m.refreshSetupGate() }
    }
}

/// A thin progress ring (mono).
struct ProgressRing: View {
    var progress: Double
    var size: CGFloat = 22
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack {
            Circle().stroke(Mono.line, lineWidth: 2.5)
            Circle().trim(from: 0, to: max(0.02, progress)).stroke(Mono.text, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: size, height: size)
        .animation(Motion.spring(reduce), value: progress)
        .accessibilityLabel("\(Int(progress * 100)) percent set up")
    }
}

// MARK: - Keyboard window (type into the phone)

struct KeyboardView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @State private var focused = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Type on your phone").font(Typo.sans(17)).foregroundColor(Mono.text)
            Text("Tap a text field on your phone, then type here. Return sends Enter, ⌘V pastes the Mac clipboard.")
                .font(Typo.sans(12)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
            accessRow
            ZStack {
                KeyCapture(send: { ops in m.typeOps(ops) }, paste: { m.clipboard.currentText ?? "" }, focused: $focused)
                VStack(spacing: 6) {
                    Text(v5.typeEcho.isEmpty ? (focused ? "Type now" : "Click here and type") : v5.typeEcho)
                        .font(Typo.mono(v5.typeEcho.isEmpty ? 11 : 14)).foregroundColor(v5.typeEcho.isEmpty ? Mono.muted : Mono.text)
                        .lineLimit(2).truncationMode(.head)
                    if let f = v5.typeField { Text("Typing into \(f)").font(Typo.mono(10)).foregroundColor(Mono.muted).lineLimit(1) }
                }
                .padding(.horizontal, 14)
                .allowsHitTesting(false)
            }
            .frame(maxWidth: .infinity, minHeight: 110)
            .tetherCard(highlighted: focused)
            .animation(Motion.spring(reduce), value: focused)
            if let s = v5.typeStatus {
                Text(s).font(Typo.sans(12, .medium)).foregroundColor(Mono.text).fixedSize(horizontal: false, vertical: true)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            HStack {
                GhostButton(title: "Backspace") { m.typeOps([.key(TypeKeys.backspace)]) }
                GhostButton(title: "Enter") { m.typeOps([.key(TypeKeys.enter)]) }
                GhostButton(title: "Clear field") { m.typeOps([.key(TypeKeys.clear)]) }
                Spacer()
                Text("\(v5.typeTracker.sentChars) sent").font(Typo.mono(10)).foregroundColor(Mono.muted)
            }
        }
        .padding(20)
        .padding(.top, 14)
        .frame(width: 460)
        .tetherBackground()
        .tetherWindowChrome()
        .animation(Motion.spring(reduce), value: v5.typeStatus)
        .onAppear {
            m.refreshPhoneStatus()
            v5.typeStatus = nil
        }
    }

    /// The phone permission typing needs (Tether control in Accessibility), with a Fix.
    @ViewBuilder private var accessRow: some View {
        switch m.typeAccess {
        case .off:
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.circle").font(.system(size: 13)).foregroundColor(Mono.text)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Tether control is off on your phone").font(Typo.sans(12, .semibold)).foregroundColor(Mono.text)
                    Text("Accessibility › Tether control. If it's greyed out: App info › ⋮ › Allow restricted settings.")
                        .font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 6)
                GhostButton(title: "Fix") { m.openPhoneSetup("accessibility") }
            }
            .padding(10)
            .tetherCard(highlighted: true)
            .transition(.opacity)
        case .on:
            Label("Tether control is on", systemImage: "checkmark.circle").font(Typo.sans(11)).foregroundColor(Mono.muted)
        case .unknown:
            EmptyView()
        }
    }
}

/// A text view that takes key focus and turns typing (IME and dead keys included) into
/// TYPE_TEXT batches. 0.5.1: an NSTextView subclass instead of a bare NSView, so the window's
/// field editor and SwiftUI focus never steal the keystrokes, and clicks anywhere focus it.
struct KeyCapture: NSViewRepresentable {
    var send: ([TypeBatcher.Op]) -> Void
    var paste: () -> String
    @Binding var focused: Bool

    func makeNSView(context: Context) -> CaptureView {
        let v = CaptureView(frame: .zero)
        configure(v)
        return v
    }

    func updateNSView(_ v: CaptureView, context: Context) { configure(v) }

    private func configure(_ v: CaptureView) {
        v.sendOps = send
        v.pasteText = paste
        v.onFocus = { f in DispatchQueue.main.async { focused = f } }
    }

    final class CaptureView: NSTextView {
        var sendOps: (([TypeBatcher.Op]) -> Void)?
        var pasteText: (() -> String)?
        var onFocus: ((Bool) -> Void)?
        private var batcher = TypeBatcher()
        private var flushWork: DispatchWorkItem?

        override init(frame: NSRect, textContainer: NSTextContainer?) {
            super.init(frame: frame, textContainer: textContainer)
            setUp()
        }

        override init(frame: NSRect) {
            super.init(frame: frame)
            setUp()
        }

        required init?(coder: NSCoder) {
            super.init(coder: coder)
            setUp()
        }

        private func setUp() {
            drawsBackground = false
            isRichText = false
            insertionPointColor = .clear
            textColor = .clear
            isAutomaticQuoteSubstitutionEnabled = false
            isAutomaticDashSubstitutionEnabled = false
            isAutomaticTextReplacementEnabled = false
            isAutomaticSpellingCorrectionEnabled = false
            setAccessibilityLabel("Type on your phone")
        }

        override var acceptsFirstResponder: Bool { true }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            DispatchQueue.main.async { [weak self] in
                guard let self, let w = self.window else { return }
                w.makeFirstResponder(self)
            }
        }

        override func becomeFirstResponder() -> Bool {
            let ok = super.becomeFirstResponder()
            onFocus?(ok)
            return ok
        }

        override func resignFirstResponder() -> Bool {
            let ok = super.resignFirstResponder()
            if ok { onFocus?(false) }
            return ok
        }

        override func mouseDown(with event: NSEvent) { window?.makeFirstResponder(self) }

        /// Committed text (typing, IME, emoji picker): batch it, keep the view itself empty.
        override func insertText(_ string: Any, replacementRange: NSRange) {
            let s = (string as? NSAttributedString)?.string ?? (string as? String) ?? ""
            guard !s.isEmpty else { return }
            for ch in s {
                if ch == "\n" || ch == "\r" {
                    emit(batcher.press(keyCode: 36, characters: "\r", command: false))
                } else {
                    emit(batcher.press(keyCode: 0, characters: String(ch), command: false))
                }
            }
            if batcher.hasPending { scheduleFlush() }
        }

        override func doCommand(by selector: Selector) {
            switch selector {
            case #selector(deleteBackward(_:)): emit(batcher.press(keyCode: 51, characters: nil, command: false))
            case #selector(insertNewline(_:)), #selector(insertLineBreak(_:)):
                emit(batcher.press(keyCode: 36, characters: nil, command: false))
            case #selector(insertTab(_:)): emit(batcher.press(keyCode: 48, characters: nil, command: false))
            default: emit(batcher.flush())
            }
        }

        override func paste(_ sender: Any?) { emit(batcher.paste(pasteText?() ?? "")) }

        override func performKeyEquivalent(with event: NSEvent) -> Bool {
            if event.modifierFlags.contains(.command), event.charactersIgnoringModifiers == "v", window?.firstResponder === self {
                paste(nil)
                return true
            }
            return super.performKeyEquivalent(with: event)
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
            if !ops.isEmpty { sendOps?(ops) }
        }
    }
}
