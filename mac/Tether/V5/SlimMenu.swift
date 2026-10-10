import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// The slim menu-bar popover (0.5.0): phone and battery, now playing, the latest
/// notifications, Send clipboard, Send file, and Open Tether for everything else.
struct SlimMenuView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    var panel: PanelControls?
    @State private var dropping = false

    private var detached: Bool { panel?.detached == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header.background(panel.map { p in PanelDragArea(onTearOff: p.detach) })
            if m.peer == nil {
                MonoButton(title: "Pair a phone") { open(.pair) }
            } else {
                if let c = m.call, m.settings.callAlerts, m.liveCall?.isOffhook != true { CallBanner(call: c) }
                if let live = m.liveCall, live.isOffhook { LiveCallCard(call: live) }
                if let otp = m.v5.otp { OtpCard(otp: otp) }
                if let np = m.nowPlaying, np.active { CompactNowPlaying(np: np) }
                if let c = m.v5.continueCards.cards.first { SlimContinue(state: c) }
                notifications
                HStack(spacing: 8) {
                    ActionTile(title: "Send clipboard", symbol: "doc.on.clipboard", enabled: m.isConnected) { m.sendClipboardNow() }
                    ActionTile(title: "Send file", symbol: "arrow.up.doc", enabled: m.isConnected) { m.chooseFiles() }
                }
            }
            HStack {
                Button("Open Tether") { open(.main) }.buttonStyle(.plain).font(Typo.sans(12, .semibold)).foregroundColor(Mono.text)
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }.buttonStyle(.plain).font(Typo.sans(12)).foregroundColor(Mono.muted)
            }
        }
        .padding(16)
        .frame(width: detached ? nil : 340)
        .frame(minWidth: 320, maxWidth: detached ? .infinity : 340, alignment: .top)
        .overlay(alignment: .topTrailing) { pin }
        .tetherBackground()
        .tetherWindowChrome()
        .overlay(dropOverlay)
        .overlay(alignment: .bottom) { notice }
        .onDrop(of: [UTType.fileURL], isTargeted: $dropping, perform: drop)
        .animation(Motion.spring(reduce), value: m.notice)
        .animation(Motion.spring(reduce), value: dropping)
    }

    private var header: some View {
        HStack(spacing: 12) {
            TetherMark(linked: m.isConnected, pulse: !m.isConnected && m.peer != nil, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(m.peer?.name ?? "Tether").font(Typo.sans(15)).foregroundColor(Mono.text).lineLimit(1)
                Text(status).font(Typo.sans(11)).foregroundColor(Mono.muted)
            }
            Spacer()
            if let b = m.battery { BatteryBadge(b: b) }
        }
        .padding(.trailing, panel == nil ? 0 : 26)
    }

    private var status: String {
        if m.isConnected { return m.onWifi ? "Connected · Wi-Fi" : "Connected · Bluetooth" }
        if m.peer == nil { return "Not paired" }
        return m.bluetooth == .off ? "Bluetooth is off" : "Waiting for your phone"
    }

    @ViewBuilder private var notifications: some View {
        let items = Array(m.history.items.prefix(3))
        if !items.isEmpty {
            VStack(spacing: 0) {
                ForEach(items) { n in
                    NotificationRow(n: n, privacy: m.settings.notifications.privacyMode,
                                    actionsEnabled: m.settings.notificationActions && m.peerCaps.contains(Caps.reply),
                                    onDismiss: { m.dismiss(n.key) })
                }
            }
            .tetherCard()
        }
    }

    @ViewBuilder private var pin: some View {
        if let p = panel {
            Button { p.detached ? p.attach() : p.detach() } label: {
                Image(systemName: p.detached ? "xmark" : "pin").font(.system(size: 11)).foregroundColor(Mono.muted)
                    .frame(width: 22, height: 22)
            }
            .buttonStyle(.plain)
            .padding(12)
            .accessibilityLabel(p.detached ? "Dock to menu bar" : "Detach")
        }
    }

    @ViewBuilder private var dropOverlay: some View {
        if dropping {
            RoundedRectangle(cornerRadius: 14).stroke(Mono.text, style: StrokeStyle(lineWidth: 1.5, dash: [6]))
                .overlay(Text("Drop to send to your phone").font(Typo.sans(13, .medium)).foregroundColor(Mono.text))
                .padding(6)
        }
    }

    @ViewBuilder private var notice: some View {
        if let n = m.notice {
            Text(n).font(Typo.sans(12, .medium)).foregroundColor(Mono.bg)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(Mono.accent)).padding(.bottom, 10)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func open(_ id: WindowID) {
        if let p = panel { p.open(id) } else { m.windows?.show(id) }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        for p in providers {
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                if let url { DispatchQueue.main.async { m.sendFiles([url]) } }
            }
        }
        return true
    }
}

struct SlimContinue: View {
    @EnvironmentObject var m: AppModel
    let state: ContinueCardState
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.turn.down.right").font(.system(size: 12)).foregroundColor(Mono.muted)
            VStack(alignment: .leading, spacing: 1) {
                Text("CONTINUE ON MAC").font(Typo.mono(9, .medium)).tracking(0.8).foregroundColor(Mono.muted)
                Text(state.card.title).font(Typo.sans(12, .medium)).foregroundColor(Mono.text).lineLimit(1)
            }
            Spacer()
            GhostButton(title: "Open") { m.performContinue(state.card) }
        }
        .padding(10)
        .tetherCard()
    }
}
