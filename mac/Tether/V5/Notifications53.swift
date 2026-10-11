import SwiftUI
import TetherCore

/// "Clear all" for phone notifications (0.5.3), in the main window and the menu-bar panel.
/// Clears the history and the delivered banners (and, with "Dismiss on both", the phone's own);
/// asks first only when a lot would go at once. The list collapses on a spring.
struct ClearNotificationsButton: View {
    enum Style { case ghost, text }

    @EnvironmentObject var m: AppModel
    var style: Style
    @State private var asking = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let count = m.history.items.count
        Group {
            if count > 0 {
                switch style {
                case .ghost: GhostButton(title: "Clear all", action: tapped)
                case .text:
                    Button(action: tapped) {
                        Text("Clear all").font(Typo.sans(11, .medium)).foregroundColor(Mono.muted).lineLimit(1).fixedSize()
                            .padding(.horizontal, 4).padding(.vertical, 2).contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle())
                }
            }
        }
        .transition(.opacity)
        .accessibilityLabel("Clear all notifications")
        .confirmationDialog("Clear \(count) notifications?", isPresented: $asking) {
            Button("Clear all", role: .destructive, action: clear)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(m.settings.notifications.dismissSync ? "They're dismissed on your phone too." : "They stay on your phone.")
        }
    }

    private func tapped() {
        if NotificationClear.needsConfirmation(m.history.items.count) { asking = true } else { clear() }
    }

    private func clear() {
        withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.42, dampingFraction: 0.8)) { m.clearHistory() }
    }
}
