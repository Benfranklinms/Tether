import AppKit
import CoreText
import SwiftUI

/// Pure Mono palette: black, greys and white. State is shown with weight and fill, never hue.
enum Mono {
    static let bg = Color(red: 0x09 / 255, green: 0x09 / 255, blue: 0x0A / 255)
    static let surface = Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x16 / 255)
    static let surfaceHigh = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1F / 255)
    static let line = Color(red: 0x2A / 255, green: 0x2A / 255, blue: 0x2E / 255)
    static let text = Color(red: 0xF2 / 255, green: 0xF2 / 255, blue: 0xF2 / 255)
    static let muted = Color(red: 0x8A / 255, green: 0x8A / 255, blue: 0x8E / 255)
    static let accent = Color(red: 0xE8 / 255, green: 0xE8 / 255, blue: 0xE8 / 255)
}

/// Manrope (bundled, SIL OFL) for text, SF Mono for numbers and codes.
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
        .system(size: size, weight: weight, design: .monospaced)
    }
}

/// iOS-style springs; nil (no animation) when the user turned on Reduce Motion.
enum Motion {
    static func spring(_ reduce: Bool) -> Animation? {
        reduce ? nil : .spring(response: 0.42, dampingFraction: 0.82)
    }

    static func bouncy(_ reduce: Bool) -> Animation? {
        reduce ? nil : .spring(response: 0.38, dampingFraction: 0.62)
    }

    static func soft(_ reduce: Bool) -> Animation? {
        reduce ? nil : .spring(response: 0.7, dampingFraction: 1.0)
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

/// Scales down on press with a spring, like iOS buttons.
struct PressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduce

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduce ? 0.96 : 1)
            .animation(Motion.spring(reduce), value: configuration.isPressed)
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
                .overlay(RoundedRectangle(cornerRadius: 9).stroke(filled ? Color.clear : Mono.line))
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }
}

/// A square action tile with an SF Symbol.
struct ActionTile: View {
    var title: String
    var symbol: String
    var enabled = true
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: symbol).font(.system(size: 15, weight: .regular))
                Text(title).font(Typo.sans(11, .medium))
            }
            .foregroundColor(enabled ? Mono.text : Mono.muted)
            .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
            .padding(.horizontal, 12)
            .background(RoundedRectangle(cornerRadius: 12).fill(hover && enabled ? Mono.surfaceHigh : Mono.surface))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Mono.line))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
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
