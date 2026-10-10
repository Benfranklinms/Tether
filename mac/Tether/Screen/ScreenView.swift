import AppKit
import AVFoundation
import CoreMedia
import SwiftUI
import TetherCore

/// Decodes the phone's H.264 screen stream with VideoToolbox through an
/// AVSampleBufferDisplayLayer (hardware decode, displayed immediately: no buffering). Frames
/// arrive on the engine queue and are decoded on their own serial queue.
final class ScreenSink {
    let layer = AVSampleBufferDisplayLayer()
    private let queue = DispatchQueue(label: "tether.video", qos: .userInteractive)
    private let assembler = FrameAssembler()
    private var format: CMVideoFormatDescription?
    private var sps = Data()
    private var pps = Data()
    private var needKey = true
    private var stats = VideoStats()
    private var baseline: Double?
    private var lag = 0
    private var wantKeyframe = false
    /// Called on the sink queue with feedback to send to the phone (about once a second).
    var onFeedback: ((VideoFeedbackPayload) -> Void)?
    /// First decodable frame arrived (main thread).
    var onFirstFrame: (() -> Void)?
    private var shownFirst = false

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
    }

    func reset() {
        queue.async { [self] in
            needKey = true
            shownFirst = false
            baseline = nil
            flush()
        }
    }

    func receive(_ part: VideoFramePayload) {
        queue.async { [self] in
            let dropsBefore = assembler.dropped
            guard let f = assembler.accept(part) else {
                if assembler.dropped > dropsBefore { stats.drop(assembler.dropped - dropsBefore) }
                return
            }
            decode(f)
        }
    }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func decode(_ f: FrameAssembler.Frame) {
        let t = now()
        stats.received(bytes: f.data.count, now: t)
        // Lag: how far behind the encoder's clock this frame arrived, relative to the best seen.
        let skew = Double(t) - Double(f.pts) / 1000
        baseline = min(baseline ?? skew, skew)
        lag = Int(max(0, skew - (baseline ?? skew)))
        if let ps = AnnexB.parameterSets(f.data), ps.sps != sps || ps.pps != pps {
            sps = ps.sps
            pps = ps.pps
            format = Self.makeFormat(sps: sps, pps: pps)
            flush()
            needKey = true
        }
        defer { maybeFeedback(t) }
        guard let fmt = format else {
            wantKeyframe = true
            return
        }
        if needKey && !f.key {
            stats.drop()
            wantKeyframe = true
            return
        }
        let avcc = AnnexB.toAvcc(f.data, dropConfig: true)
        guard !avcc.isEmpty, let sample = Self.makeSample(avcc, fmt) else { return }
        if status == .failed {
            Log.warn("screen.decoder_failed")
            flush()
            needKey = true
            wantKeyframe = true
            return
        }
        enqueue(sample)
        needKey = false
        if !shownFirst {
            shownFirst = true
            DispatchQueue.main.async { self.onFirstFrame?() }
        }
    }

    private func maybeFeedback(_ t: Int64) {
        if let fb = stats.feedback(now: t, lagMs: lag, needKeyframe: wantKeyframe) {
            wantKeyframe = false
            onFeedback?(fb)
        }
    }

    private var status: AVQueuedSampleBufferRenderingStatus {
        if #available(macOS 14.0, *) { return layer.sampleBufferRenderer.status }
        return layer.status
    }

    private func enqueue(_ s: CMSampleBuffer) {
        if #available(macOS 14.0, *) { layer.sampleBufferRenderer.enqueue(s) } else { layer.enqueue(s) }
    }

    private func flush() {
        if #available(macOS 14.0, *) { layer.sampleBufferRenderer.flush() } else { layer.flush() }
    }

    static func makeFormat(sps: Data, pps: Data) -> CMVideoFormatDescription? {
        let s = [UInt8](sps)
        let p = [UInt8](pps)
        var fmt: CMFormatDescription?
        let status = s.withUnsafeBufferPointer { sb in
            p.withUnsafeBufferPointer { pb -> OSStatus in
                guard let sa = sb.baseAddress, let pa = pb.baseAddress else { return -1 }
                let ptrs: [UnsafePointer<UInt8>] = [sa, pa]
                let sizes = [s.count, p.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: ptrs,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &fmt
                )
            }
        }
        if status != noErr { Log.warn("screen.bad_sps", ["status": "\(status)"]) }
        return status == noErr ? fmt : nil
    }

    static func makeSample(_ avcc: Data, _ fmt: CMVideoFormatDescription) -> CMSampleBuffer? {
        var block: CMBlockBuffer?
        let len = avcc.count
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: len, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: len, flags: 0, blockBufferOut: &block
        ) == kCMBlockBufferNoErr, let bb = block else { return nil }
        let copied = avcc.withUnsafeBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return -1 }
            return CMBlockBufferReplaceDataBytes(with: base, blockBuffer: bb, offsetIntoDestination: 0, dataLength: len)
        }
        guard copied == kCMBlockBufferNoErr else { return nil }
        var sample: CMSampleBuffer?
        var size = len
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: bb, formatDescription: fmt, sampleCount: 1,
            sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1, sampleSizeArray: &size,
            sampleBufferOut: &sample
        ) == noErr, let sb = sample else { return nil }
        if let arr = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(arr) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(arr, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        return sb
    }
}

/// State of the screen window (main thread).
final class ScreenModel: ObservableObject {
    enum Phase: Equatable {
        case idle, requesting, consent, live, needsWifi, denied(String), ended(String), unsupported
    }

    @Published var phase: Phase = .idle
    @Published var size = CGSize(width: 720, height: 1600)
    @Published var rotation = 0
    @Published var control = false
    @Published var hasVideo = false
    let sink = ScreenSink()

    var statusText: String {
        switch phase {
        case .idle: return "Not sharing"
        case .requesting: return "Asking your phone…"
        case .consent: return "Approve on your phone: tap the notification, then Start now"
        case .live: return hasVideo ? (control ? "Live · click, drag, scroll and type to control" : "Live · view only") : "Starting…"
        case .needsWifi: return "Screen sharing needs Wi-Fi: put both on the same network, or join your phone's hotspot"
        case .denied(let why): return why
        case .ended(let why): return why
        case .unsupported: return "Update Tether on your phone to share its screen"
        }
    }
}

/// The video surface plus mouse/trackpad/keyboard capture. Coordinates go through
/// VideoGeometry (aspect fit) and GestureClassifier, then to the phone as SCREEN_INPUT.
final class ScreenInputView: NSView {
    weak var sink: ScreenSink?
    var videoSize = CGSize(width: 720, height: 1600)
    var rotation = 0
    var enabled = false
    var send: ((ScreenInputPayload) -> Void)?
    private var downAt: (x: Double, y: Double)?
    private var downTime = Date()
    private var scrollAcc = (dx: 0.0, dy: 0.0)
    private var scrollAt: (x: Double, y: Double)?
    private var scrollFlush: DispatchWorkItem?

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }

    init(sink: ScreenSink) {
        self.sink = sink
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(sink.layer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        sink?.layer.frame = bounds
        CATransaction.commit()
    }

    private var geometry: VideoGeometry {
        VideoGeometry(view: CGSizeLite(bounds.width, bounds.height), video: CGSizeLite(videoSize.width, videoSize.height))
    }

    private func point(_ e: NSEvent, clamp: Bool = false) -> (x: Double, y: Double)? {
        let p = convert(e.locationInWindow, from: nil)
        return geometry.normalized(x: p.x, y: p.y, clamp: clamp)
    }

    override func mouseDown(with e: NSEvent) {
        window?.makeFirstResponder(self)
        guard enabled else { return }
        downAt = point(e)
        downTime = Date()
    }

    override func mouseUp(with e: NSEvent) {
        guard enabled, let start = downAt, let end = point(e, clamp: true) else { return }
        downAt = nil
        let ms = Int64(Date().timeIntervalSince(downTime) * 1000)
        let aspect = videoSize.height > 0 ? Double(videoSize.width / videoSize.height) : 1
        send?(GestureClassifier.classify(down: start, up: end, durationMs: ms, aspect: aspect, rotation: rotation))
    }

    override func rightMouseDown(with e: NSEvent) {
        guard enabled else { return }
        send?(ScreenInputPayload(k: InputKinds.back))
    }

    override func scrollWheel(with e: NSEvent) {
        guard enabled, let p = point(e) ?? scrollAt else { return }
        let fit = geometry.fitted
        guard fit.h > 0 else { return }
        // Precise (trackpad) deltas are points; a mouse wheel gives lines.
        let scale = e.hasPreciseScrollingDeltas ? 1.0 / fit.h : 0.06
        if scrollAt == nil { scrollAt = p }
        scrollAcc.dx += e.scrollingDeltaX * scale
        scrollAcc.dy += e.scrollingDeltaY * scale
        if scrollFlush == nil {
            let w = DispatchWorkItem { [weak self] in self?.flushScroll() }
            scrollFlush = w
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: w)
        }
    }

    private func flushScroll() {
        scrollFlush = nil
        defer {
            scrollAcc = (0, 0)
            scrollAt = nil
        }
        guard let at = scrollAt,
              let s = GestureClassifier.scroll(at: at, dx: scrollAcc.dx, dy: scrollAcc.dy, rotation: rotation) else { return }
        send?(s)
    }

    override func keyDown(with e: NSEvent) {
        guard enabled else { return super.keyDown(with: e) }
        if e.modifierFlags.contains(.command) {
            if e.charactersIgnoringModifiers == "v", let text = NSPasteboard.general.string(forType: .string), !text.isEmpty {
                send?(ScreenInputPayload(k: InputKinds.text, text: text))
            }
            return
        }
        switch e.keyCode {
        case 51: send?(ScreenInputPayload(k: InputKinds.backspace))
        case 36, 76: send?(ScreenInputPayload(k: InputKinds.enter))
        case 53: send?(ScreenInputPayload(k: InputKinds.back))
        default:
            if let chars = e.characters, !chars.isEmpty, chars.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
                send?(ScreenInputPayload(k: InputKinds.text, text: chars))
            }
        }
    }
}

private struct VideoSurface: NSViewRepresentable {
    @ObservedObject var screen: ScreenModel
    var send: (ScreenInputPayload) -> Void

    func makeNSView(context: Context) -> ScreenInputView {
        let v = ScreenInputView(sink: screen.sink)
        v.send = send
        return v
    }

    func updateNSView(_ v: ScreenInputView, context: Context) {
        v.videoSize = screen.size
        v.rotation = screen.rotation
        v.enabled = screen.phase == .live && screen.control
        v.send = send
        v.needsLayout = true
        if v.enabled { DispatchQueue.main.async { v.window?.makeFirstResponder(v) } }
    }
}

/// The phone-screen window: video (aspect kept, resizable) and a floating toolbar.
struct ScreenWindow: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var screen: ScreenModel
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack(alignment: .bottom) {
            ZStack {
                VideoSurface(screen: screen) { m.screenInput($0) }
                    .aspectRatio(screen.size.width / max(screen.size.height, 1), contentMode: .fit)
                    .opacity(screen.hasVideo ? 1 : 0)
                if !screen.hasVideo { placeholder }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.bottom, 64)
            toolbar
        }
        .frame(minWidth: 300, minHeight: 480)
        .tetherBackground()
        .tetherWindowChrome()
        .animation(Motion.spring(reduce), value: screen.phase)
        .animation(Motion.spring(reduce), value: screen.hasVideo)
        .onDisappear { m.stopScreen() }
    }

    private var placeholder: some View {
        VStack(spacing: 14) {
            TetherMark(linked: screen.phase == .live, pulse: screen.phase != .live, size: 64)
            Text(screen.statusText).font(Typo.sans(13)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
                .frame(maxWidth: 280)
            if case .idle = screen.phase {
                MonoButton(title: "Share phone screen") { m.startScreen() }.frame(width: 200)
            } else if case .ended = screen.phase {
                MonoButton(title: "Share again") { m.startScreen() }.frame(width: 200)
            } else if case .denied = screen.phase {
                MonoButton(title: "Try again") { m.startScreen() }.frame(width: 200)
            } else if case .needsWifi = screen.phase {
                MonoButton(title: "Try again", filled: false) { m.startScreen() }.frame(width: 200)
            }
        }
        .padding(24)
        .transition(.opacity)
    }

    private var toolbar: some View {
        HStack(spacing: 8) {
            toolButton("chevron.backward", "Back", enabled: canControl) { m.screenInput(ScreenInputPayload(k: InputKinds.back)) }
            toolButton("circle", "Home", enabled: canControl) { m.screenInput(ScreenInputPayload(k: InputKinds.home)) }
            toolButton("square.on.square", "Recents", enabled: canControl) { m.screenInput(ScreenInputPayload(k: InputKinds.recents)) }
            Text(screen.phase == .live && screen.hasVideo ? (screen.control ? "Control" : "View only") : "")
                .font(Typo.mono(10, .medium)).foregroundColor(Mono.muted).frame(minWidth: 60)
            toolButton("stop.fill", "Stop sharing", enabled: screen.phase == .live || screen.phase == .consent) { m.stopScreen() }
        }
        .padding(6)
        .tetherControl(Capsule(), interactive: false)
        .tetherGlassGroup(spacing: 8)
        .padding(.bottom, 12)
    }

    private var canControl: Bool { screen.phase == .live && screen.control }

    private func toolButton(_ symbol: String, _ label: String, enabled: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 13, weight: .medium))
                .foregroundColor(enabled ? Mono.text : Mono.muted)
                .frame(width: 36, height: 32)
                .contentShape(Capsule())
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .help(label)
        .accessibilityLabel(label)
    }
}
