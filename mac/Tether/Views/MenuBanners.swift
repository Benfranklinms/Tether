import SwiftUI

/// Paired but not linked: a manual way out instead of quitting and reopening Tether (0.3.1).
struct ReconnectBanner: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        HStack(spacing: 10) {
            Text("Not connected to your phone").font(Typo.sans(12)).foregroundColor(Mono.muted)
            Spacer(minLength: 6)
            GhostButton(title: "Reconnect") { m.reconnect() }
                .accessibilityHint("Restarts Bluetooth for Tether and looks for your phone again")
        }
        .padding(.horizontal, 16).padding(.bottom, 10)
        .transition(.opacity)
    }
}

/// macOS refused Tether's notification permission: say so where it matters and link to the fix.
struct NotificationsOffBanner: View {
    @EnvironmentObject var m: AppModel
    @AppStorage("panelOnlyAlerts") private var panelOnly = false

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: "bell.slash").font(.system(size: 13)).foregroundColor(Mono.text)
            VStack(alignment: .leading, spacing: 2) {
                Text("Mac notifications are off for Tether").font(Typo.sans(12, .medium)).foregroundColor(Mono.text)
                Text("Phone alerts, texts and calls stay in this panel until you allow them.")
                    .font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 6) {
                GhostButton(title: "Open Settings") { m.openNotificationSettings() }
                    .accessibilityHint("Opens System Settings, Notifications")
                Button("Panel only") { panelOnly = true }
                    .buttonStyle(.plain).font(Typo.sans(11)).foregroundColor(Mono.muted)
                    .accessibilityHint("Keeps alerts in this panel and hides this message")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Mono.line))
        .padding(.horizontal, 16).padding(.bottom, 10)
        .transition(.move(edge: .top).combined(with: .opacity))
        .onAppear { m.refreshNotificationAuth() }
    }
}
