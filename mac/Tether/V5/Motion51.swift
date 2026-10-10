import SwiftUI
import TetherCore

// 0.5.1 motion kit: iOS-style springs (≈200–350 ms), all of it off with Reduce Motion (a short
// crossfade instead), nothing that blocks input.

extension Motion {
    /// Page / tab switches: content fades and slides a little; crossfade with Reduce Motion.
    static func page(_ reduce: Bool) -> AnyTransition {
        reduce ? .opacity : .asymmetric(insertion: .opacity.combined(with: .offset(y: 10)), removal: .opacity)
    }

    /// Rows that come and go (notifications, transfers, clips).
    static func row(_ reduce: Bool) -> AnyTransition {
        reduce ? .opacity : .asymmetric(insertion: .move(edge: .top).combined(with: .opacity),
                                        removal: .opacity.combined(with: .scale(scale: 0.96, anchor: .center)))
    }

    static let quick = Animation.spring(response: 0.3, dampingFraction: 0.82)
}

/// Hover lift and press scale for cards and tiles (Mac).
struct HoverLift: ViewModifier {
    var enabled = true
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    func body(content: Content) -> some View {
        content
            // 0.5.2: a real lift — grows, rises 2 pt and casts a softer, deeper shadow.
            .scaleEffect(hover && enabled && !reduce ? 1.03 : 1)
            .offset(y: hover && enabled && !reduce ? -2 : 0)
            .shadow(color: .black.opacity(hover && enabled ? 0.22 : 0), radius: hover ? 14 : 0, y: hover ? 7 : 0)
            .animation(reduce ? Motion.reducedFade : .spring(response: 0.32, dampingFraction: 0.7), value: hover)
            .onHover { hover = $0 }
    }
}

extension View {
    func hoverLift(_ enabled: Bool = true) -> some View { modifier(HoverLift(enabled: enabled)) }

    /// Numbers that roll (battery %, counters) on macOS 14+.
    @ViewBuilder func rollingNumber() -> some View {
        if #available(macOS 14.0, *) { self.contentTransition(.numericText()) } else { self }
    }

    /// An SF Symbol that pulses while `active` (connecting, ringing) on macOS 14+.
    @ViewBuilder func symbolPulse(_ active: Bool) -> some View {
        if #available(macOS 14.0, *) {
            self.symbolEffect(.pulse, options: .repeating, isActive: active)
        } else {
            self
        }
    }

    /// A one-shot bounce when `value` changes (macOS 14+).
    @ViewBuilder func symbolBounce<V: Equatable>(_ value: V) -> some View {
        if #available(macOS 14.0, *) { self.symbolEffect(.bounce, value: value) } else { self }
    }

    /// Popovers and sheets: scale + blur in on a spring.
    func springIn(_ shown: Bool, reduce: Bool) -> some View {
        self.scaleEffect(shown || reduce ? 1 : 0.94, anchor: .top)
            .blur(radius: shown || reduce ? 0 : 6)
            .opacity(shown ? 1 : 0)
    }
}

/// A label that morphs into a check mark when its action succeeded.
struct SuccessLabel: View {
    let title: String
    let symbol: String
    let done: Bool

    var body: some View {
        ZStack(alignment: .leading) {
            Label(title, systemImage: symbol).opacity(done ? 0 : 1)
            Label("Sent", systemImage: "checkmark").opacity(done ? 1 : 0).scaleEffect(done ? 1 : 0.8, anchor: .leading)
        }
        .labelStyle(.titleAndIcon)
    }
}
