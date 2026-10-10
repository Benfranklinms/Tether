import AppKit
import CoreText
import SwiftUI

/// Pure Mono palette: black, greys and white (light: near-white, greys and near-black). State
/// is shown with weight and fill, never hue. Every colour resolves against the view's
/// effective appearance, so Dark / Light / System switch live.
enum Mono {
    static let bg = dynamic(dark: 0x09090A, light: 0xFAFAFA)
    static let surface = dynamic(dark: 0x141416, light: 0xF0F0F1)
    static let surfaceHigh = dynamic(dark: 0x1C1C1F, light: 0xE6E6E8)
    static let line = dynamic(dark: 0x2A2A2E, light: 0xD9D9DC)
    static let text = dynamic(dark: 0xF2F2F2, light: 0x0A0A0B)
    static let muted = dynamic(dark: 0x8A8A8E, light: 0x5F5F64)
    static let accent = dynamic(dark: 0xE8E8E8, light: 0x111113)

    /// The window background as an NSColor (for AppKit windows and panels).
    static let nsBg = dynamicNS(dark: 0x09090A, light: 0xFAFAFA)

    static func isDark(_ a: NSAppearance) -> Bool {
        a.bestMatch(from: [.darkAqua, .vibrantDark, .aqua, .vibrantLight]).map { $0 == .darkAqua || $0 == .vibrantDark } ?? true
    }

    private static func rgb(_ hex: Int) -> NSColor {
        NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }

    static func dynamicNS(dark: Int, light: Int) -> NSColor {
        NSColor(name: nil) { a in isDark(a) ? rgb(dark) : rgb(light) }
    }

    private static func dynamic(dark: Int, light: Int) -> Color { Color(nsColor: dynamicNS(dark: dark, light: light)) }
}

/// Manrope (bundled, SIL OFL) for text, JetBrains Mono (bundled, SIL OFL) for numbers and codes.
enum Typo {
    /// Registers the bundled Manrope weights for this process. Call once at launch.
    static func registerFonts() {
        for url in Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? [] {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let face: String
        switch weight {
        case .ultraLight, .thin, .light: face = "Manrope-Light"
        case .medium: face = "Manrope-Medium"
        case .semibold, .bold, .heavy, .black: face = "Manrope-SemiBold"
        default: face = "Manrope-Regular"
        }
        return .custom(face, size: size)
    }

    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let face: String
        switch weight {
        case .semibold, .bold, .heavy, .black: face = "JetBrainsMono-SemiBold"
        case .medium: face = "JetBrainsMono-Medium"
        default: face = "JetBrainsMono-Regular"
        }
        return .custom(face, size: size)
    }
}

/// iOS-style springs. With Reduce Motion every one of them becomes a short crossfade-length
/// ease (0.5.2), so transitions fall back to fades instead of jumping.
enum Motion {
    static let reducedFade = Animation.easeInOut(duration: 0.18)

    static func spring(_ reduce: Bool) -> Animation? {
        reduce ? reducedFade : .spring(response: 0.42, dampingFraction: 0.82)
    }

    static func bouncy(_ reduce: Bool) -> Animation? {
        reduce ? reducedFade : .spring(response: 0.38, dampingFraction: 0.62)
    }

    static func soft(_ reduce: Bool) -> Animation? {
        reduce ? reducedFade : .spring(response: 0.7, dampingFraction: 1.0)
    }
}

/// Two rings: apart and breathing while searching, springing together when linked.
struct TetherMark: View {
    var linked: Bool
    var pulse: Bool
    var size: CGFloat = 44
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var breathe = false

    var body: some View {
        let r = size * 0.25
        let w = size * 0.055
        let apart: CGFloat = linked ? 0 : (breathe ? 1.15 : 0.85)
        let dx = r * (0.45 + apart * 0.6)
        ZStack {
            Circle().stroke(Mono.text, lineWidth: w).frame(width: r * 2, height: r * 2).offset(x: -dx)
            Circle().stroke(Mono.text.opacity(linked ? 1 : 0.4), lineWidth: w).frame(width: r * 2, height: r * 2).offset(x: dx)
            Circle().fill(Mono.text).frame(width: w * 3, height: w * 3).scaleEffect(linked ? 1 : 0.01).opacity(linked ? 1 : 0)
        }
        .frame(width: size, height: size)
        .animation(reduce ? nil : .spring(response: 0.5, dampingFraction: 0.6), value: linked)
        .onAppear { startBreathing() }
        .onChange(of: pulse) { _ in startBreathing() }
        .accessibilityHidden(true)
    }

    private func startBreathing() {
        guard pulse, !reduce else {
            breathe = false
            return
        }
        withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) { breathe = true }
    }
}

struct Chip: View {
    var text: String
    var on = false

    var body: some View {
        Text(text)
            .font(Typo.mono(10, .medium))
            .foregroundColor(on ? Mono.text : Mono.muted)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .overlay(Capsule().stroke(on ? Mono.text : Mono.line, lineWidth: 1))
    }
}

/// iOS press feedback (0.5.2): scales down and dims on press, springs back with a little
/// overshoot on release. Reduce Motion: dims only.
struct PressStyle: ButtonStyle {
    var scale: CGFloat = 0.93
    @Environment(\.accessibilityReduceMotion) private var reduce

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduce ? scale : 1)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .brightness(configuration.isPressed ? -0.03 : 0)
            .animation(configuration.isPressed ? .spring(response: 0.16, dampingFraction: 0.9)
                       : (reduce ? Motion.reducedFade : .spring(response: 0.34, dampingFraction: 0.5)), value: configuration.isPressed)
    }
}

/// Large primary button (white on black) and its outlined sibling.
struct MonoButton: View {
    var title: String
    var filled = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Typo.sans(13, .semibold))
                .frame(maxWidth: .infinity, minHeight: 32)
                .foregroundColor(filled ? Mono.bg : Mono.text)
                .background(RoundedRectangle(cornerRadius: 9).fill(filled ? Mono.accent : Color.clear))
                .modifier(OutlineIfNeeded(filled: filled))
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

private struct OutlineIfNeeded: ViewModifier {
    var filled: Bool
    func body(content: Content) -> some View {
        if filled { content } else { content.tetherControl(RoundedRectangle(cornerRadius: 9)) }
    }
}

/// A square action tile with an SF Symbol.
struct ActionTile: View {
    var title: String
    var symbol: String
    var enabled = true
    /// 0.5.1: morphs into a check for a moment after the action succeeded.
    var done = false
    var action: () -> Void
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: done ? "checkmark" : symbol).font(.system(size: 15, weight: done ? .semibold : .regular))
                    .symbolBounce(done)
                Text(done ? "Sent" : title).font(Typo.sans(11, .medium))
                    .lineLimit(1).minimumScaleFactor(0.8).allowsTightening(true)
            }
            .animation(Motion.bouncy(reduce), value: done)
            .foregroundColor(enabled ? Mono.text : Mono.muted)
            .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
            .padding(.horizontal, 12)
            .tetherCard(highlighted: hover && enabled)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .scaleEffect(hover && enabled && !reduce ? 1.04 : 1)
        .offset(y: hover && enabled && !reduce ? -2 : 0)
        .shadow(color: .black.opacity(hover && enabled ? 0.22 : 0), radius: hover ? 12 : 0, y: hover ? 6 : 0)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.66), value: hover)
        .onHover { hover = $0 }
        .accessibilityLabel(title)
    }
}

/// Template menu-bar glyph: rings apart (searching) or interlocked (connected).
enum MenuIcon {
    static func image(linked: Bool) -> NSImage {
        let size = NSSize(width: 22, height: 16)
        let img = NSImage(size: size, flipped: false) { rect in
            let r: CGFloat = 4.6
            let dx: CGFloat = linked ? 2.6 : 4.4
            let cy = rect.midY
            for (i, x) in [rect.midX - dx, rect.midX + dx].enumerated() {
                let p = NSBezierPath(ovalIn: NSRect(x: x - r, y: cy - r, width: r * 2, height: r * 2))
                p.lineWidth = 1.6
                NSColor.black.withAlphaComponent(i == 1 && !linked ? 0.45 : 1).setStroke()
                p.stroke()
            }
            return true
        }
        img.isTemplate = true
        img.accessibilityDescription = linked ? "Tether, connected" : "Tether, not connected"
        return img
    }
}

func formatBytes(_ b: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: b, countStyle: .file)
}

func relativeTime(_ ms: Int64) -> String {
    let d = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let s = -d.timeIntervalSinceNow
    if s < 60 { return "now" }
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .abbreviated
    return f.localizedString(for: d, relativeTo: Date())
}
