import AppKit
import SwiftUI
import TetherCore

// 0.5.2 motion kit. iOS-style: things travel (hero), lists cascade, presses squish, symbols
// react. Every piece falls back to a short crossfade with Reduce Motion, and none of it runs
// work on the main thread per frame beyond what SwiftUI interpolates.

extension Motion {
    /// Off while rendering CI screenshots so every shot is deterministic.
    static let intro = Launch.screenshotDir == nil

    /// Shared-element travel (row → detail, tile → viewer).
    static func hero(_ reduce: Bool) -> Animation {
        reduce ? reducedFade : .spring(response: 0.46, dampingFraction: 0.84)
    }

    static func snappy(_ reduce: Bool) -> Animation {
        reduce ? reducedFade : .spring(response: 0.28, dampingFraction: 0.78)
    }

    /// List rows: new ones drop in from above with a little blur, removed ones fly off to the right.
    static func listRow(_ reduce: Bool) -> AnyTransition {
        reduce ? .opacity : .asymmetric(insertion: .modifier(active: RowIn(p: 0), identity: RowIn(p: 1)),
                                        removal: .move(edge: .trailing).combined(with: .opacity))
    }

    /// Main-window pages: the new page rises from the direction of travel down the sidebar.
    static func pageTransition(_ reduce: Bool, direction: Double) -> AnyTransition {
        reduce ? .opacity : .asymmetric(
            insertion: .modifier(active: PageMove(p: 0, dir: direction, entering: true),
                                 identity: PageMove(p: 1, dir: direction, entering: true)),
            removal: .modifier(active: PageMove(p: 0, dir: direction, entering: false),
                               identity: PageMove(p: 1, dir: direction, entering: false))
        )
    }
}

struct RowIn: ViewModifier {
    var p: Double
    func body(content: Content) -> some View {
        content.opacity(p).offset(y: (1 - p) * -18).scaleEffect(0.94 + 0.06 * p, anchor: .top).blur(radius: (1 - p) * 3)
    }
}

/// p = 1 is the resting state; p = 0 is off-stage (below/above for entering, the other way for leaving).
struct PageMove: ViewModifier {
    var p: Double
    var dir: Double
    var entering: Bool
    func body(content: Content) -> some View {
        let away = 1 - p
        content
            .opacity(p)
            .offset(y: away * (entering ? 34 : -20) * dir)
            .scaleEffect(1 - away * (entering ? 0.025 : -0.015))
            .blur(radius: away * (entering ? 6 : 3))
    }
}

/// Cascading entrance: each element fades, rises and un-shrinks a beat after the previous one.
struct StaggerIn: ViewModifier {
    let index: Int
    var step = 0.035
    @State private var shown = !Motion.intro
    @Environment(\.accessibilityReduceMotion) private var reduce

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduce ? 0 : 16)
            .scaleEffect(shown || reduce ? 1 : 0.96, anchor: .top)
            .onAppear {
                guard !shown else { return }
                let delay = reduce ? 0 : Stagger.delay(index: index, step: step, cap: 0.4)
                withAnimation((reduce ? Motion.reducedFade : .spring(response: 0.5, dampingFraction: 0.8)).delay(delay)) { shown = true }
            }
    }
}

extension View {
    func staggerIn(_ index: Int, step: Double = 0.035) -> some View { modifier(StaggerIn(index: index, step: step)) }

    /// Popover sections: each slides up a beat after the previous whenever `on` turns true.
    func cascade(_ on: Bool, _ index: Int, reduce: Bool) -> some View {
        self.opacity(on ? 1 : 0)
            .offset(y: on || reduce ? 0 : 12)
            .scaleEffect(on || reduce ? 1 : 0.97, anchor: .top)
            .animation((reduce ? Motion.reducedFade : .spring(response: 0.42, dampingFraction: 0.8)).delay(on ? Double(index) * 0.045 : 0),
                       value: on)
    }

    /// matchedGeometryEffect only when a namespace is given and the element is the active copy.
    @ViewBuilder func hero(_ id: String, in ns: Namespace.ID?, active: Bool = true) -> some View {
        if let ns, active { matchedGeometryEffect(id: id, in: ns) } else { self }
    }

    /// Drag a row sideways to remove it (it flies off, then `onRemove`).
    func swipeToRemove(enabled: Bool = true, onRemove: @escaping () -> Void) -> some View {
        modifier(SwipeToRemove(enabled: enabled, onRemove: onRemove))
    }

    /// An SF Symbol that swaps with a replace effect when its name changes (macOS 14+).
    @ViewBuilder func symbolReplace() -> some View {
        if #available(macOS 14.0, *) { self.contentTransition(.symbolEffect(.replace)) } else { self }
    }

    /// Variable-colour sweep while `active` (connecting, charging) on macOS 14+.
    @ViewBuilder func symbolSweep(_ active: Bool) -> some View {
        if #available(macOS 14.0, *) {
            self.symbolEffect(.variableColor.iterative.reversing, options: .repeating, isActive: active)
        } else {
            self
        }
    }

    /// Ringing: wiggle on macOS 15+, a repeating bounce on 14.
    @ViewBuilder func symbolRinging(_ active: Bool) -> some View {
        if #available(macOS 15.0, *) {
            self.symbolEffect(.wiggle.byLayer, options: .repeating, isActive: active)
        } else if #available(macOS 14.0, *) {
            self.symbolEffect(.pulse, options: .repeating, isActive: active)
        } else {
            self
        }
    }

    /// Breathing while `active` (charging) on macOS 15+; pulse on 14.
    @ViewBuilder func symbolBreathe(_ active: Bool) -> some View {
        if #available(macOS 15.0, *) {
            self.symbolEffect(.breathe, options: .repeating, isActive: active)
        } else if #available(macOS 14.0, *) {
            self.symbolEffect(.pulse, options: .repeating, isActive: active)
        } else {
            self
        }
    }
}

struct SwipeToRemove: ViewModifier {
    var enabled: Bool
    let onRemove: () -> Void
    @State private var dx: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduce

    func body(content: Content) -> some View {
        content
            .offset(x: dx)
            .opacity(1 - min(0.7, abs(dx) / 360))
            .background(alignment: dx < 0 ? .trailing : .leading) {
                Image(systemName: "trash").font(.system(size: 13, weight: .medium)).foregroundColor(Mono.muted)
                    .padding(.horizontal, 18)
                    .opacity(min(1, abs(dx) / 90))
                    .scaleEffect(abs(dx) > 110 ? 1.15 : 0.85)
                    .animation(.spring(response: 0.25, dampingFraction: 0.6), value: abs(dx) > 110)
            }
            .simultaneousGesture(enabled ? drag : nil)
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { g in
                guard abs(g.translation.width) > abs(g.translation.height) * 1.4 else { return }
                dx = g.translation.width
            }
            .onEnded { g in
                let far = abs(g.translation.width) > 110 || abs(g.predictedEndTranslation.width) > 280
                if far && abs(g.translation.width) > abs(g.translation.height) {
                    withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.28, dampingFraction: 0.95)) {
                        dx = g.translation.width > 0 ? 700 : -700
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        onRemove()
                        dx = 0
                    }
                } else {
                    withAnimation(.spring(response: 0.36, dampingFraction: 0.6)) { dx = 0 }
                }
            }
    }
}

// MARK: - Skeletons

/// Placeholder rows while conversations / files load (monogram + two lines, shimmering).
struct SkeletonRows: View {
    var count = 7
    var avatar: CGFloat = 34
    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<count, id: \.self) { i in
                HStack(spacing: 10) {
                    Shimmer().frame(width: avatar, height: avatar).clipShape(Circle())
                    VStack(alignment: .leading, spacing: 7) {
                        Shimmer().frame(width: CGFloat(110 + (i * 37) % 70), height: 10).clipShape(Capsule())
                        Shimmer().frame(width: CGFloat(160 + (i * 53) % 80), height: 8).clipShape(Capsule())
                    }
                    Spacer()
                }
                .padding(.horizontal, 18).padding(.vertical, 10)
                .opacity(1 - Double(i) * 0.09)
            }
        }
        .transition(.opacity)
        .accessibilityLabel("Loading")
    }
}

/// Placeholder message bubbles while a conversation loads.
struct SkeletonBubbles: View {
    var body: some View {
        VStack(spacing: 10) {
            ForEach(0..<6, id: \.self) { i in
                HStack {
                    if i % 3 == 2 { Spacer(minLength: 80) }
                    Shimmer().frame(width: CGFloat(140 + (i * 61) % 120), height: CGFloat(i % 2 == 0 ? 34 : 52))
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    if i % 3 != 2 { Spacer(minLength: 80) }
                }
            }
        }
        .padding(16)
        .transition(.opacity)
        .accessibilityLabel("Loading")
    }
}

// MARK: - Frames and rectangle selection

/// Item frames (global coordinates) reported by tiles; read on demand, never observed, so
/// scrolling doesn't re-render the page.
final class FrameRegistry {
    var frames: [String: CGRect] = [:]

    func boxes() -> [String: Box] {
        frames.mapValues { Box(x: Double($0.minX), y: Double($0.minY), w: Double($0.width), h: Double($0.height)) }
    }
}

struct ItemFramesKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

extension View {
    /// Reports this item's global frame for rectangle selection and zoom transitions.
    func reportFrame(_ id: String) -> some View {
        background(GeometryReader { g in Color.clear.preference(key: ItemFramesKey.self, value: [id: g.frame(in: .global)]) })
    }

    /// Finder-style rectangle selection over a scroll view of items that `reportFrame`.
    func marqueeSelection(registry: FrameRegistry, order: @escaping () -> [String],
                          onSelect: @escaping (_ ids: [String], _ additive: Bool) -> Void) -> some View {
        modifier(MarqueeSelect(registry: registry, order: order, onSelect: onSelect))
    }
}

struct MarqueeSelect: ViewModifier {
    let registry: FrameRegistry
    let order: () -> [String]
    let onSelect: ([String], Bool) -> Void
    @State private var start: CGPoint?
    @State private var current: CGPoint?
    @State private var additive = false
    @State private var origin: CGPoint = .zero
    @State private var lastHits: [String] = []

    func body(content: Content) -> some View {
        content
            .onPreferenceChange(ItemFramesKey.self) { registry.frames = $0 }
            .overlay(GeometryReader { g in
                ZStack(alignment: .topLeading) {
                    Color.clear
                    if let s = start, let c = current {
                        let r = CGRect(x: min(s.x, c.x) - origin.x, y: min(s.y, c.y) - origin.y,
                                       width: abs(s.x - c.x), height: abs(s.y - c.y))
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(Mono.text.opacity(0.07))
                            .overlay(RoundedRectangle(cornerRadius: 4, style: .continuous).stroke(Mono.text.opacity(0.6), lineWidth: 1))
                            .frame(width: r.width, height: r.height)
                            .offset(x: r.minX, y: r.minY)
                            .transition(.opacity)
                    }
                }
                .onAppear { origin = g.frame(in: .global).origin }
                .onChange(of: g.frame(in: .global).origin) { origin = $0 }
            }
            .allowsHitTesting(false))
            .simultaneousGesture(
                DragGesture(minimumDistance: 4, coordinateSpace: .global)
                    .onChanged { g in
                        if start == nil {
                            let p = g.startLocation
                            guard !MarqueeSelection.startsOnItem(Double(p.x), Double(p.y), frames: registry.boxes()) else { return }
                            additive = NSEvent.modifierFlags.contains(.command) || NSEvent.modifierFlags.contains(.shift)
                            start = p
                        }
                        current = g.location
                        guard let s = start else { return }
                        let rect = MarqueeSelection.rect(from: (Double(s.x), Double(s.y)), to: (Double(g.location.x), Double(g.location.y)))
                        let hits = MarqueeSelection.hits(rect, frames: registry.boxes(), order: order())
                        if hits != lastHits {
                            lastHits = hits
                            onSelect(hits, additive)
                        }
                    }
                    .onEnded { _ in
                        withAnimation(.easeOut(duration: 0.2)) {
                            start = nil
                            current = nil
                        }
                        lastHits = []
                    }
            )
    }
}

// MARK: - Animated progress ring (LoadRing) (video download, capture)

struct LoadRing: View {
    var fraction: Double?
    var size: CGFloat = 54
    @State private var spin = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.18), lineWidth: 3)
            if let f = fraction {
                Circle().trim(from: 0, to: max(0.02, f))
                    .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.spring(response: 0.5, dampingFraction: 1), value: f)
            } else {
                Circle().trim(from: 0, to: 0.28)
                    .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(spin ? 360 : 0))
                    .onAppear {
                        guard !reduce else { return }
                        withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { spin = true }
                    }
            }
        }
        .frame(width: size, height: size)
        .accessibilityLabel(fraction.map { "\(Int($0 * 100)) percent" } ?? "Loading")
    }
}
