import SwiftUI
import TetherCore

/// Phone battery: level glyph (bolt while charging), rolling percent, network. 0.5.2: the glyph
/// swaps with a replace effect, breathes while charging, and the number rolls.
struct BatteryBadge: View {
    let b: BatteryPayload

    private var symbol: String {
        if b.charging { return "battery.100.bolt" }
        switch b.level {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }

    private var network: String? {
        switch b.net {
        case "wifi": return "Wi-Fi"
        case "cellular": return b.gen ?? "Cellular"
        default: return nil
        }
    }

    var body: some View {
        HStack(spacing: 5) {
            if let network {
                Text(network).font(Typo.mono(10, .medium)).foregroundColor(Mono.muted)
                    .id(network).transition(.move(edge: .bottom).combined(with: .opacity))
            }
            Image(systemName: symbol).font(.system(size: 13)).foregroundColor(b.level <= 20 && !b.charging ? Mono.text : Mono.muted)
                .symbolReplace()
                .symbolBreathe(b.charging)
            Text("\(b.level)%").font(Typo.mono(11, .medium)).foregroundColor(Mono.text).contentTransition(.numericText())
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.72), value: b.level)
        .animation(.spring(response: 0.4, dampingFraction: 0.72), value: b.charging)
        .animation(.spring(response: 0.4, dampingFraction: 0.72), value: network)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Phone battery \(b.level) percent\(b.charging ? ", charging" : "")")
    }
}
