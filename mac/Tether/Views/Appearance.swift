import AppKit
import SwiftUI

/// The Mac app's look: Pure Mono (solid #09090A, greys) or Liquid Glass (macOS 26's glass
/// material, tinted dark and colourless; translucent vibrancy on macOS 13-15). Reduce
/// Transparency always falls back to solid Pure Mono. Switches live.
enum AppearanceStyle: String, Codable, CaseIterable, Identifiable {
    case mono, glass

    var id: String { rawValue }
    var title: String { self == .mono ? "Pure Mono" : "Liquid Glass" }
}

private struct StyleKey: EnvironmentKey {
    static let defaultValue = AppearanceStyle.mono
}

extension EnvironmentValues {
    var tetherStyle: AppearanceStyle {
        get { self[StyleKey.self] }
        set { self[StyleKey.self] = newValue }
    }
}

enum GlassSupport {
    /// Real Liquid Glass APIs exist in this build (compiled with the macOS 26 SDK) and at runtime.
    static var native: Bool {
        #if compiler(>=6.2)
        if #available(macOS 26.0, *) { return true }
        #endif
        return false
    }

    /// Short description for Settings / diagnostics.
    static var support: String {
        if native { return "Liquid Glass (macOS 26)" }
        #if compiler(>=6.2)
        return "Translucent vibrancy (Liquid Glass needs macOS 26)"
        #else
        return "Translucent vibrancy (this build has no macOS 26 SDK)"
        #endif
    }
}

/// Resolves the effective look for a view: glass only when chosen and Reduce Transparency is off.
private struct Effective: ViewModifier {
    @Environment(\.tetherStyle) private var style
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    let build: (Bool, AnyView) -> AnyView

    func body(content: Content) -> some View {
        build(style == .glass && !reduceTransparency, AnyView(content))
    }
}

extension View {
    /// Root background of a window or the menu-bar panel.
    func tetherBackground() -> some View {
        modifier(Effective { glass, content in
            if !glass { return AnyView(content.background(Mono.bg)) }
            return AnyView(content.background(GlassBackdrop()))
        })
    }

    /// A grouped card (settings rows, tiles).
    func tetherCard(cornerRadius: CGFloat = 12, highlighted: Bool = false) -> some View {
        modifier(Effective { glass, content in
            let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            if !glass {
                return AnyView(content
                    .background(shape.fill(highlighted ? Mono.surfaceHigh : Mono.surface))
                    .overlay(shape.stroke(Mono.line)))
            }
            return AnyView(content.glassSurface(shape, tint: highlighted ? 0.10 : 0.05, interactive: false))
        })
    }

    /// A small control surface (ghost buttons, chips, toolbar buttons).
    func tetherControl<S: InsettableShape>(_ shape: S, interactive: Bool = true) -> some View {
        modifier(Effective { glass, content in
            // strokeBorder keeps the hairline inside the control's bounds; a centred stroke spilled half a
            // point outside and was clamped into a "bracket" at the ends of capsules (GhostButton).
            if !glass { return AnyView(content.overlay(shape.strokeBorder(Mono.line, lineWidth: 1))) }
            return AnyView(content.glassSurface(shape, tint: 0.06, interactive: interactive))
        })
    }

    /// The glass itself: Liquid Glass on macOS 26, material + hairline before.
    @ViewBuilder
    fileprivate func glassSurface<S: InsettableShape>(_ shape: S, tint: Double, interactive: Bool) -> some View {
        #if compiler(>=6.2)
        if #available(macOS 26.0, *) {
            self.glassEffect(Glass26.style(tint: tint, interactive: interactive), in: shape)
        } else {
            materialSurface(shape, tint: tint)
        }
        #else
        materialSurface(shape, tint: tint)
        #endif
    }

    private func materialSurface<S: InsettableShape>(_ shape: S, tint: Double) -> some View {
        background(shape.fill(.ultraThinMaterial))
            .background(shape.fill(Color.white.opacity(tint)))
            .overlay(shape.strokeBorder(Color.white.opacity(0.10)))
    }

    /// Groups glass shapes so they blend and morph together (GlassEffectContainer on macOS 26).
    @ViewBuilder
    func tetherGlassGroup(spacing: CGFloat = 10) -> some View {
        #if compiler(>=6.2)
        if #available(macOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { self }
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// Makes the hosting window see-through in glass mode (so the backdrop is the desktop).
    func tetherWindowChrome() -> some View {
        modifier(Effective { glass, content in
            AnyView(content.background(WindowConfigurator(clear: glass)))
        })
    }
}

#if compiler(>=6.2)
@available(macOS 26.0, *)
private enum Glass26 {
    /// Monochrome: a faint white (never coloured) tint keeps the dark glass neutral.
    static func style(tint: Double, interactive: Bool) -> SwiftUI.Glass {
        let g = SwiftUI.Glass.regular.tint(Color.white.opacity(tint))
        return interactive ? g.interactive() : g
    }
}
#endif

/// Window/panel backdrop for glass mode: Liquid Glass on macOS 26, behind-window vibrancy
/// (the desktop blurred through the window) before. A scrim keeps text readable in Dark and Light.
private struct GlassBackdrop: View {
    var body: some View {
        ZStack {
            VibrancyView()
            #if compiler(>=6.2)
            if #available(macOS 26.0, *) {
                Rectangle().fill(Color.clear).glassEffect(.regular.tint(Mono.bg.opacity(0.35)), in: Rectangle())
            }
            #endif
            Scrim()
        }
        .ignoresSafeArea()
    }
}

/// Keeps text readable over the desktop: a dark scrim in Dark, a light one in Light.
private struct Scrim: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        Rectangle().fill(scheme == .dark ? Color.black.opacity(0.28) : Color.white.opacity(0.45))
    }
}

/// NSVisualEffectView blending with what is behind the window.
private struct VibrancyView: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .hudWindow
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }

    func updateNSView(_ v: NSVisualEffectView, context: Context) {}
}

/// Clears (or restores) the hosting window's background so glass can show the desktop.
private struct WindowConfigurator: NSViewRepresentable {
    var clear: Bool

    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async { apply(v.window) }
        return v
    }

    func updateNSView(_ v: NSView, context: Context) {
        DispatchQueue.main.async { apply(v.window) }
    }

    private func apply(_ w: NSWindow?) {
        guard let w else { return }
        // 0.5.6: only touch what changed. This runs after every SwiftUI update; re-setting the titlebar
        // invalidates the window's safe area and constraints even when nothing differs.
        if w.isOpaque == clear { w.isOpaque = !clear }
        let bg: NSColor = clear ? .clear : Mono.nsBg
        if w.backgroundColor != bg { w.backgroundColor = bg }
        if !w.titlebarAppearsTransparent { w.titlebarAppearsTransparent = true }
    }
}
