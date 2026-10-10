import AVKit
import SwiftUI
import TetherCore

/// 0.5.2 Photos viewer. The picture zooms out of its tile and back into it (computed from the
/// tile's real frame, so it lands exactly where it came from), the backdrop and info panel
/// follow on the same spring, a downward drag shrinks it away. Videos now play: the file is
/// fetched over the Wi-Fi fast path (or Bluetooth when that's all there is), with the phone's
/// poster frame and a progress ring while it arrives, then an AVKit player with scrubbing.
struct PhotoViewer52: View {
    @EnvironmentObject var m: AppModel
    let item: PhotoItem
    let frames: FrameRegistry
    @ObservedObject var v5: V5State
    @State private var expanded = false
    @State private var drag: CGSize = .zero
    @Environment(\.accessibilityReduceMotion) private var reduce

    private static let panel: CGFloat = 240
    private var base: String { m.thumbKey(root: FsRoots.photos, path: item.id) }
    private var dragProgress: Double { min(1, abs(drag.height) / 300) }

    var body: some View {
        GeometryReader { g in
            let container = g.frame(in: .global)
            let stage = CGRect(x: 30, y: 30, width: max(120, g.size.width - Self.panel - 60), height: max(120, g.size.height - 60))
            let fit = Self.aspectFit(aspect(), in: stage)
            let origin = frames.frames[item.id].map { $0.offsetBy(dx: -container.minX, dy: -container.minY) }
            let rect = expanded ? fit : (origin ?? fit.insetBy(dx: fit.width * 0.1, dy: fit.height * 0.1))
            ZStack(alignment: .topLeading) {
                Color.black.opacity((expanded ? 0.9 : 0) * (1 - dragProgress * 0.7))
                    .frame(width: g.size.width, height: g.size.height)
                    .onTapGesture(perform: close)
                media
                    .frame(width: rect.width, height: rect.height)
                    .clipShape(RoundedRectangle(cornerRadius: expanded ? 8 : 4, style: .continuous))
                    .shadow(color: .black.opacity(expanded ? 0.45 : 0), radius: 30, y: 10)
                    .scaleEffect(1 - dragProgress * 0.25)
                    .offset(drag)
                    .position(x: rect.midX, y: rect.midY)
                    .opacity(expanded || origin != nil ? 1 : 0)
                    .gesture(dismissDrag)
                info
                    .frame(width: Self.panel, height: g.size.height)
                    .offset(x: g.size.width - Self.panel + (expanded || reduce ? 0 : 60))
                    .opacity(expanded ? 1 : 0)
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
        }
        .background(keys)
        .onAppear {
            withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.42, dampingFraction: 0.86)) { expanded = true }
        }
    }

    /// Width / height of the picture: the decoded thumbnail knows the real (rotated) shape.
    private func aspect() -> CGFloat {
        for k in [base + "@1024", base + "@512", base] {
            if let img = ThumbCache.shared[k], img.size.height > 0 { return img.size.width / img.size.height }
        }
        if item.w > 0 && item.h > 0 { return CGFloat(item.w) / CGFloat(item.h) }
        return 1
    }

    static func aspectFit(_ aspect: CGFloat, in r: CGRect) -> CGRect {
        let a = max(0.05, aspect)
        var w = r.width
        var h = w / a
        if h > r.height {
            h = r.height
            w = h * a
        }
        return CGRect(x: r.midX - w / 2, y: r.midY - h / 2, width: w, height: h)
    }

    @ViewBuilder private var media: some View {
        ZStack {
            ThumbImage(key: base + "@1024", fallbacks: ThumbPixels.all.map { base + "@\($0)" } + [base]) {
                m.requestThumb(root: FsRoots.photos, path: item.id, size: 1024)
            }
            if item.video && expanded { VideoStage(item: item).transition(.opacity) }
        }
        .id(item.id)
        .transition(.opacity)
    }

    private var info: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Spacer()
                Button(action: close) {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .medium)).foregroundColor(.white)
                        .frame(width: 26, height: 26).background(Circle().fill(Color.white.opacity(0.14)))
                }
                .buttonStyle(PressStyle()).accessibilityLabel("Close")
            }
            Text(item.name).font(Typo.sans(14, .medium)).foregroundColor(.white).lineLimit(3)
            line("Date", Date(timeIntervalSince1970: TimeInterval(item.date) / 1000).formatted(date: .long, time: .shortened))
            line("Size", ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file))
            if item.w > 0 && item.h > 0 { line("Resolution", "\(item.w) × \(item.h)") }
            if item.video && item.dur > 0 { line("Duration", PhotoSections.duration(item.dur)) }
            line("Type", item.mime)
            Spacer()
            MonoButton(title: "Download") { m.fetchPhoneFile(root: FsRoots.photos, path: item.id, name: item.name) }
            MonoButton(title: "Open on Mac", filled: false) {
                m.fetchPhoneFile(root: FsRoots.photos, path: item.id, name: item.name, open: true)
            }
            Text("← → to browse · Esc to close").font(Typo.mono(9)).foregroundColor(.white.opacity(0.5))
        }
        .padding(18)
        .frame(maxHeight: .infinity)
        .background(Color.white.opacity(0.06))
        .environment(\.colorScheme, .dark)
    }

    private func line(_ k: String, _ v: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(k.uppercased()).font(Typo.mono(9, .medium)).tracking(0.8).foregroundColor(.white.opacity(0.5))
            Text(v).font(Typo.sans(12)).foregroundColor(.white.opacity(0.9)).textSelection(.enabled)
        }
    }

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { drag = $0.translation }
            .onEnded { g in
                if abs(g.translation.height) > 120 || abs(g.predictedEndTranslation.height) > 300 {
                    close()
                } else {
                    withAnimation(.interactiveSpring(response: 0.32, dampingFraction: 0.8)) { drag = .zero }
                }
            }
    }

    private var keys: some View {
        ZStack {
            Button("", action: { step(-1) }).keyboardShortcut(.leftArrow, modifiers: [])
            Button("", action: { step(1) }).keyboardShortcut(.rightArrow, modifiers: [])
            Button("", action: close).keyboardShortcut(.cancelAction)
        }
        .opacity(0).allowsHitTesting(false)
    }

    private func step(_ d: Int) {
        let order = v5.library.items.map(\.id)
        guard let next = GridSelection.neighbour(of: item.id, in: order, step: d), next != item.id,
              let p = v5.library.items.first(where: { $0.id == next }) else { return }
        withAnimation(reduce ? Motion.reducedFade : .easeOut(duration: 0.2)) { v5.viewer = p }
        if next == order.last && v5.library.more { m.loadPhotos(more: true) }
    }

    /// Shrinks back into the tile, then removes the viewer (the tile reappears underneath).
    private func close() {
        withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.38, dampingFraction: 0.88)) {
            expanded = false
            drag = .zero
        }
        let id = item.id
        DispatchQueue.main.asyncAfter(deadline: .now() + (reduce ? 0.2 : 0.36)) {
            if v5.viewer?.id == id { v5.viewer = nil }
        }
    }
}

/// The video part of the viewer: poster + progress until the file is here, then the player.
struct VideoStage: View {
    @EnvironmentObject var m: AppModel
    let item: PhotoItem

    var body: some View {
        let state = m.videoPlayback(item)
        ZStack {
            switch state {
            case .ready(let path):
                PlayerView(url: URL(fileURLWithPath: path))
                    .transition(.opacity)
            case .failed(let reason):
                statusCard {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 22, weight: .light)).foregroundColor(.white)
                    Text(reason).font(Typo.sans(12)).foregroundColor(.white).multilineTextAlignment(.center)
                    GhostButton(title: "Try again") { m.playVideo(item, retry: true) }.environment(\.colorScheme, .dark)
                }
            default:
                statusCard {
                    if case .fetching(let f, _) = state {
                        LoadRing(fraction: f)
                    } else {
                        LoadRing(fraction: nil)
                    }
                    Text(state.label).font(Typo.mono(11, .medium)).foregroundColor(.white)
                        .contentTransition(.numericText())
                }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: state)
        .onAppear { m.playVideo(item) }
    }

    private func statusCard<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        ZStack {
            Color.black.opacity(0.35)
            VStack(spacing: 10) { c() }
                .padding(18)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.black.opacity(0.55)))
        }
        .transition(.opacity)
    }
}

/// AVKit's player view with the floating controls (play/pause, scrubbing, volume, full screen).
struct PlayerView: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> AVPlayerView {
        let v = AVPlayerView()
        v.controlsStyle = .floating
        v.showsFullScreenToggleButton = true
        v.videoGravity = .resizeAspect
        let p = AVPlayer(url: url)
        v.player = p
        p.play()
        return v
    }

    func updateNSView(_ v: AVPlayerView, context: Context) {
        if (v.player?.currentItem?.asset as? AVURLAsset)?.url != url {
            v.player?.pause()
            v.player = AVPlayer(url: url)
            v.player?.play()
        }
    }

    static func dismantleNSView(_ v: AVPlayerView, coordinator: ()) {
        v.player?.pause()
        v.player = nil
    }
}
