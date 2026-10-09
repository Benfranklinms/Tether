import SwiftUI
import TetherCore

/// Settings window: a sliding section bar on top, pages that push left/right like iOS.
struct SettingsView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var section: Section
    @State private var forward = true
    @Namespace private var ns

    enum Section: String, CaseIterable, Identifiable {
        case general = "General"
        case notifications = "Notifications"
        case phone = "Phone"
        case diagnostics = "Diagnostics"
        case about = "About"

        var id: String { rawValue }

        var symbol: String {
            switch self {
            case .general: return "gearshape"
            case .notifications: return "bell"
            case .phone: return "iphone.gen2"
            case .diagnostics: return "waveform.path.ecg"
            case .about: return "info.circle"
            }
        }
    }

    init(initialSection: Section = .general) {
        _section = State(initialValue: initialSection)
    }

    var body: some View {
        VStack(spacing: 0) {
            bar
            Rectangle().fill(Mono.line).frame(height: 1)
            ZStack {
                page(section)
                    .id(section)
                    .transition(pageTransition)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .clipped()
        }
        .frame(width: 560, height: 600)
        .background(Mono.bg)
        .preferredColorScheme(.dark)
        .overlay(alignment: .bottom) { toast }
        .animation(Motion.spring(reduce), value: m.notice)
    }

    private var bar: some View {
        HStack(spacing: 4) {
            ForEach(Section.allCases) { s in
                Button {
                    let from = Section.allCases.firstIndex(of: section) ?? 0
                    let to = Section.allCases.firstIndex(of: s) ?? 0
                    forward = to >= from
                    withAnimation(Motion.spring(reduce)) { section = s }
                } label: {
                    VStack(spacing: 5) {
                        Image(systemName: s.symbol).font(.system(size: 15, weight: section == s ? .medium : .light))
                        Text(s.rawValue).font(Typo.sans(11, section == s ? .semibold : .regular))
                    }
                    .foregroundColor(section == s ? Mono.text : Mono.muted)
                    .frame(width: 96, height: 54)
                    .background {
                        if section == s {
                            RoundedRectangle(cornerRadius: 10).fill(Mono.surfaceHigh).matchedGeometryEffect(id: "sel", in: ns)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(section == s ? .isSelected : [])
            }
        }
        .padding(.top, 30)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity)
    }

    private var pageTransition: AnyTransition {
        guard !reduce else { return .opacity }
        return .asymmetric(
            insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
            removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)
        )
    }

    @ViewBuilder private func page(_ s: Section) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                switch s {
                case .general: GeneralPage()
                case .notifications: NotificationsPage()
                case .phone: PhonePage()
                case .diagnostics: DiagnosticsPage()
                case .about: AboutPage()
                }
            }
            .padding(20)
        }
    }

    @ViewBuilder private var toast: some View {
        if let n = m.notice {
            Text(n).font(Typo.sans(12, .medium)).foregroundColor(Mono.bg)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(Mono.accent))
                .padding(.bottom, 18)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}

// MARK: pages

private struct GeneralPage: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        SectionLabel(text: "Mac")
        Card {
            ToggleRow(title: "Open at login", isOn: Binding(get: { m.launchAtLogin }, set: { m.setLaunchAtLogin($0) }))
            ToggleRow(title: "Wi-Fi fast path", detail: "Large files go over your local network when both are on it.",
                      isOn: $m.settings.wifiFastPath, divider: false)
        }
        SectionLabel(text: "Clipboard")
        Card {
            ToggleRow(title: "Send what I copy", detail: "Password-manager items are never sent.", isOn: $m.settings.sendClipboard)
            ToggleRow(title: "Receive from phone", isOn: $m.settings.receiveClipboard, divider: false)
        }
        SectionLabel(text: "Files")
        Card {
            Row(title: "Save to", detail: m.settings.downloadURL.path.replacingOccurrences(of: NSHomeDirectory(), with: "~")) {
                GhostButton(title: "Change…") { m.chooseDownloadFolder() }
            }
            ToggleRow(title: "Notify when a file arrives", isOn: $m.settings.notifyOnReceive, divider: false)
        }
    }
}

private struct NotificationsPage: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var apps: [(pkg: String, name: String)] {
        m.history.seenApps.map { (pkg: $0.key, name: $0.value) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        if !m.notificationsAllowed {
            Card {
                Row(title: "Notifications are off for Tether", detail: "Banners need permission in System Settings.", divider: false) {
                    GhostButton(title: "Open") { m.openNotificationSettings() }
                }
            }
        }
        SectionLabel(text: "Mirroring")
        Card {
            ToggleRow(title: "Show phone notifications", isOn: $m.settings.notifications.enabled)
            ToggleRow(title: "Hide contents", detail: "Show only the app name.", isOn: $m.settings.notifications.privacyMode)
            ToggleRow(title: "Sound", isOn: $m.settings.notifications.sound)
            ToggleRow(title: "Dismiss on both", detail: "Clearing on one device clears it on the other.",
                      isOn: $m.settings.notifications.dismissSync)
            Row(title: "Keep in menu", divider: false) {
                MonoSegmented(options: MacNotificationSettings.historyChoices, selection: $m.settings.notifications.historyLimit,
                              label: { "\($0)" }, mono: true)
                    .fixedSize()
            }
        }
        SectionLabel(text: "Apps")
        if apps.isEmpty {
            Card {
                Row(title: "No apps yet", detail: "Apps appear here after their first notification.", divider: false) { EmptyView() }
            }
        } else {
            Card {
                ForEach(Array(apps.enumerated()), id: \.element.pkg) { i, app in
                    AppPrefRow(pkg: app.pkg, name: app.name, divider: i < apps.count - 1)
                }
            }
            .disabled(!m.settings.notifications.enabled)
            .animation(Motion.spring(reduce), value: m.settings.notifications.apps)
        }
    }
}

private struct AppPrefRow: View {
    @EnvironmentObject var m: AppModel
    var pkg: String
    var name: String
    var divider: Bool

    private var pref: Binding<MacAppPref> {
        Binding(get: { m.settings.notifications.pref(for: pkg) }, set: { m.settings.notifications.apps[pkg] = $0 })
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Monogram(text: name, size: 26)
                Text(name).font(Typo.sans(13, .medium)).foregroundColor(pref.wrappedValue.visibility == .show ? Mono.text : Mono.muted)
                    .lineLimit(1)
                Spacer(minLength: 8)
                MonoSegmented(options: MacAppVisibility.allCases, selection: pref.visibility,
                              label: { $0 == .show ? "Show" : "Hide" })
                    .fixedSize()
                MonoSegmented(options: MacAppDelivery.allCases, selection: pref.delivery,
                              label: { $0 == .banner ? "Banner" : "Silent" })
                    .fixedSize()
                    .disabled(pref.wrappedValue.visibility == .hide)
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
            if divider { Rectangle().fill(Mono.line).frame(height: 1).padding(.leading, 50) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(name)
    }
}

private struct PhonePage: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var confirmUnpair = false

    var body: some View {
        if let peer = m.peer {
            HStack(spacing: 16) {
                TetherMark(linked: m.isConnected, pulse: !m.isConnected, size: 56)
                VStack(alignment: .leading, spacing: 3) {
                    Text(peer.name).font(Typo.sans(20, .light)).foregroundColor(Mono.text)
                    Text(m.isConnected ? (m.onWifi ? "Connected · Wi-Fi" : "Connected · Bluetooth") : "Not connected")
                        .font(Typo.sans(12)).foregroundColor(Mono.muted)
                }
                Spacer()
            }
            .padding(.vertical, 6)
            SectionLabel(text: "Security")
            Card {
                Row(title: "Phone key") { Text(fingerprint(peer.keyBytes)).font(Typo.mono(12)).foregroundColor(Mono.text) }
                Row(title: "This Mac") { Text(m.myFingerprint).font(Typo.mono(12)).foregroundColor(Mono.text) }
                Row(title: "Paired") {
                    Text(Date(timeIntervalSince1970: TimeInterval(peer.pairedAt) / 1000).formatted(date: .abbreviated, time: .omitted))
                        .font(Typo.mono(12)).foregroundColor(Mono.muted)
                }
                Row(title: "Rotate keys", detail: "Both devices switch to fresh keys.", divider: false) {
                    GhostButton(title: "Rotate") { m.rotateKey() }.disabled(!m.isConnected)
                }
            }
            SectionLabel(text: "Pairing")
            Card {
                Row(title: "Pair a different phone") { GhostButton(title: "Pair…") { open() } }
                Row(title: "Unpair", detail: "Forget this phone on both devices.", divider: false) {
                    GhostButton(title: "Unpair") { confirmUnpair = true }
                }
            }
            .confirmationDialog("Unpair \(peer.name)?", isPresented: $confirmUnpair) {
                Button("Unpair", role: .destructive) { m.unpair() }
            } message: {
                Text("You'll need to pair again to use Tether.")
            }
        } else {
            VStack(spacing: 18) {
                TetherMark(linked: false, pulse: true, size: 72)
                Text("No phone paired").font(Typo.sans(20, .light)).foregroundColor(Mono.text)
                MonoButton(title: "Pair a phone") { open() }.frame(width: 200)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 80)
        }
    }

    private func open() {
        NSApp.activate(ignoringOtherApps: true)
        openWindow(id: "pair")
    }
}

private struct DiagnosticsPage: View {
    @EnvironmentObject var m: AppModel

    private var linkText: String {
        switch m.link {
        case .offline: return "offline"
        case .handshaking: return "handshaking"
        case .pairing: return "pairing"
        case .connected(_, let wifi): return wifi ? "connected · wifi" : "connected · ble"
        }
    }

    private var btText: String {
        switch m.bluetooth {
        case .on: return "on"
        case .off: return "off"
        case .unauthorized: return "not allowed"
        case .unsupported: return "unsupported"
        case .unknown: return "—"
        }
    }

    var body: some View {
        SectionLabel(text: "Status")
        Card {
            Row(title: "Bluetooth") { value(btText) }
            Row(title: "Link") { value(linkText) }
            Row(title: "Transfers") { value("\(m.transfers.filter { !$0.status.isFinal }.count) active") }
            Row(title: "Tether") { value(Engine.version) }
            Row(title: "macOS", divider: false) {
                value(ProcessInfo.processInfo.operatingSystemVersionString.replacingOccurrences(of: "Version ", with: ""))
            }
        }
        SectionLabel(text: "Logs")
        Card {
            Row(title: "Export logs", detail: "No clipboard text, notification content or file names are logged.") {
                GhostButton(title: "Export…") { m.exportLogs() }
            }
            Row(title: "Logs folder", divider: false) { GhostButton(title: "Show") { m.openLogsFolder() } }
        }
    }

    private func value(_ s: String) -> some View {
        Text(s).font(Typo.mono(12)).foregroundColor(Mono.muted)
    }
}

private struct AboutPage: View {
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var linked = false

    var body: some View {
        VStack(spacing: 14) {
            TetherMark(linked: linked, pulse: !linked, size: 88)
                .onTapGesture { withAnimation(Motion.bouncy(reduce)) { linked.toggle() } }
            Text("Tether").font(Typo.sans(26, .light)).foregroundColor(Mono.text)
            Text(Engine.version).font(Typo.mono(12)).foregroundColor(Mono.muted)
            Text("Your Android phone and your Mac, linked directly.\nEnd-to-end encrypted. No cloud, no account.")
                .font(Typo.sans(12)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 24)
        .padding(.bottom, 12)
        Card {
            Row(title: "License", detail: "MIT") { EmptyView() }
            Row(title: "Bluetooth framing and Noise structure", detail: "Adapted from bitchat (public domain)") { EmptyView() }
            Row(title: "Typeface", detail: "Manrope · SIL Open Font License", divider: false) { EmptyView() }
        }
        HStack {
            Spacer()
            Link("github.com/Benfranklinms/Tether", destination: URL(string: "https://github.com/Benfranklinms/Tether")!)
                .font(Typo.mono(11)).foregroundColor(Mono.muted)
            Spacer()
        }
        .padding(.top, 6)
    }
}
