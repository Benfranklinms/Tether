//
// Features.swift — protocol revision 2 (app 0.2.0) payloads, mirrored in
// android/core/.../proto/Features.kt. Gated on Hello.caps; older builds ignore them.
//

import Foundation

public enum Caps {
    public static let clip = "clip"
    public static let notif = "notif"
    public static let file = "file"
    public static let reply = "reply"
    public static let media = "media"
    public static let battery = "battery"
    public static let ring = "ring"
    public static let screen = "screen"

    /// Everything a 0.1.x build announced.
    public static let v1 = [clip, notif, file]
    /// What this build announces.
    public static let current: [String] = {
        var all: [String] = v1 + [reply, media, battery, ring, screen]
        all += Caps3.all
        all += Caps4.all
        all += Caps5.all
        all += Caps6.all
        all += Caps7.all
        return all
    }()
}

/// One button on a mirrored notification; `reply` marks an inline-text (RemoteInput) action.
public struct NotifActionInfo: Codable, Equatable, Hashable {
    public var id: String
    public var title: String
    public var reply: Bool

    public init(id: String, title: String, reply: Bool = false) {
        self.id = id
        self.title = title
        self.reply = reply
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        reply = try c.decodeIfPresent(Bool.self, forKey: .reply) ?? false
    }
}

public struct NotifActionPayload: Codable, Equatable {
    public var key: String
    public var action: String
    public var text: String?

    public init(key: String, action: String, text: String? = nil) {
        self.key = key
        self.action = action
        self.text = text
    }
}

public struct NotifActionResultPayload: Codable, Equatable {
    public var key: String
    public var action: String
    public var ok: Bool
    public var reason: String?

    public init(key: String, action: String, ok: Bool, reason: String? = nil) {
        self.key = key
        self.action = action
        self.ok = ok
        self.reason = reason
    }
}

public struct BatteryPayload: Codable, Equatable {
    public var level: Int
    public var charging: Bool
    public var plug: String?
    public var net: String?
    public var gen: String?
    public var signal: Int?
    public var ts: Int64

    public init(level: Int, charging: Bool, plug: String? = nil, net: String? = nil, gen: String? = nil,
                signal: Int? = nil, ts: Int64) {
        self.level = level
        self.charging = charging
        self.plug = plug
        self.net = net
        self.gen = gen
        self.signal = signal
        self.ts = ts
    }
}

public struct ScreenStartPayload: Codable, Equatable {
    public var maxSize: Int
    public var fps: Int
    public var bitrate: Int

    public init(maxSize: Int = 1280, fps: Int = 60, bitrate: Int = 6_000_000) {
        self.maxSize = maxSize
        self.fps = fps
        self.bitrate = bitrate
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        maxSize = try c.decodeIfPresent(Int.self, forKey: .maxSize) ?? 1280
        fps = try c.decodeIfPresent(Int.self, forKey: .fps) ?? 60
        bitrate = try c.decodeIfPresent(Int.self, forKey: .bitrate) ?? 6_000_000
    }
}

public enum ScreenStates {
    public static let consent = "consent"
    public static let live = "live"
    public static let stopped = "stopped"
    public static let denied = "denied"
    public static let needsWifi = "needs_wifi"
    public static let error = "error"
}

public struct ScreenStatePayload: Codable, Equatable {
    public var state: String
    public var w: Int
    public var h: Int
    public var rotation: Int
    public var control: Bool
    public var reason: String?

    public init(state: String, w: Int = 0, h: Int = 0, rotation: Int = 0, control: Bool = false, reason: String? = nil) {
        self.state = state
        self.w = w
        self.h = h
        self.rotation = rotation
        self.control = control
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        state = try c.decode(String.self, forKey: .state)
        w = try c.decodeIfPresent(Int.self, forKey: .w) ?? 0
        h = try c.decodeIfPresent(Int.self, forKey: .h) ?? 0
        rotation = try c.decodeIfPresent(Int.self, forKey: .rotation) ?? 0
        control = try c.decodeIfPresent(Bool.self, forKey: .control) ?? false
        reason = try c.decodeIfPresent(String.self, forKey: .reason)
    }
}

public struct VideoFramePayload: Equatable {
    public static let maxPart = 1024 * 1024

    public var src: String
    public var seq: Int64
    public var pts: Int64
    public var key: Bool
    public var config: Bool
    public var part: Int
    public var parts: Int
    public var data: Data

    public init(src: String, seq: Int64, pts: Int64, key: Bool, config: Bool, part: Int = 0, parts: Int = 1, data: Data) {
        self.src = src
        self.seq = seq
        self.pts = pts
        self.key = key
        self.config = config
        self.part = part
        self.parts = parts
        self.data = data
    }
}

struct VideoHeader: Codable {
    var src: String
    var seq: Int64
    var pts: Int64
    var key: Bool?
    var config: Bool?
    var part: Int?
    var parts: Int?
}

public enum InputKinds {
    public static let tap = "tap"
    public static let longPress = "long"
    public static let swipe = "swipe"
    public static let scroll = "scroll"
    public static let text = "text"
    public static let backspace = "backspace"
    public static let enter = "enter"
    public static let back = "back"
    public static let home = "home"
    public static let recents = "recents"
}

/// Mac -> phone input; coordinates normalised (0...1) in the shown frame.
public struct ScreenInputPayload: Codable, Equatable {
    public var k: String
    public var x: Double
    public var y: Double
    public var x2: Double?
    public var y2: Double?
    public var dur: Int64?
    public var text: String?
    public var rot: Int?

    public init(k: String, x: Double = 0, y: Double = 0, x2: Double? = nil, y2: Double? = nil, dur: Int64? = nil,
                text: String? = nil, rot: Int? = nil) {
        self.k = k
        self.x = x
        self.y = y
        self.x2 = x2
        self.y2 = y2
        self.dur = dur
        self.text = text
        self.rot = rot
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        k = try c.decode(String.self, forKey: .k)
        x = try c.decodeIfPresent(Double.self, forKey: .x) ?? 0
        y = try c.decodeIfPresent(Double.self, forKey: .y) ?? 0
        x2 = try c.decodeIfPresent(Double.self, forKey: .x2)
        y2 = try c.decodeIfPresent(Double.self, forKey: .y2)
        dur = try c.decodeIfPresent(Int64.self, forKey: .dur)
        text = try c.decodeIfPresent(String.self, forKey: .text)
        rot = try c.decodeIfPresent(Int.self, forKey: .rot)
    }
}

public struct VideoFeedbackPayload: Codable, Equatable {
    public var src: String
    public var kbps: Int
    public var lagMs: Int
    public var dropped: Int
    public var keyframe: Bool

    public init(src: String = "screen", kbps: Int = 0, lagMs: Int = 0, dropped: Int = 0, keyframe: Bool = false) {
        self.src = src
        self.kbps = kbps
        self.lagMs = lagMs
        self.dropped = dropped
        self.keyframe = keyframe
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        src = try c.decodeIfPresent(String.self, forKey: .src) ?? "screen"
        kbps = try c.decodeIfPresent(Int.self, forKey: .kbps) ?? 0
        lagMs = try c.decodeIfPresent(Int.self, forKey: .lagMs) ?? 0
        dropped = try c.decodeIfPresent(Int.self, forKey: .dropped) ?? 0
        keyframe = try c.decodeIfPresent(Bool.self, forKey: .keyframe) ?? false
    }
}

// MARK: - Helpers (pure, unit-tested)

/// H.264 Annex B parsing and AVCC conversion (VideoToolbox wants AVCC samples).
public enum AnnexB {
    public static let nalIDR: UInt8 = 5
    public static let nalSPS: UInt8 = 7
    public static let nalPPS: UInt8 = 8

    /// Payload ranges of each NAL unit (start codes excluded).
    public static func nalUnits(_ data: Data) -> [Range<Int>] {
        let b = [UInt8](data)
        var starts: [(code: Int, payload: Int)] = []
        var i = 0
        while i + 2 < b.count {
            var len = 0
            if b[i] == 0 && b[i + 1] == 0 {
                if b[i + 2] == 1 {
                    len = 3
                } else if i + 3 < b.count && b[i + 2] == 0 && b[i + 3] == 1 {
                    len = 4
                }
            }
            if len > 0 {
                starts.append((i, i + len))
                i += len
            } else {
                i += 1
            }
        }
        var out: [Range<Int>] = []
        for (k, s) in starts.enumerated() {
            let end = k + 1 < starts.count ? starts[k + 1].code : b.count
            if end > s.payload { out.append(s.payload..<end) }
        }
        return out
    }

    public static func nalTypes(_ data: Data) -> [UInt8] {
        let b = [UInt8](data)
        return nalUnits(data).map { b[$0.lowerBound] & 0x1F }
    }

    public static func isKeyframe(_ data: Data) -> Bool { nalTypes(data).contains(nalIDR) }

    /// SPS and PPS payloads (no start codes), if both are present.
    public static func parameterSets(_ data: Data) -> (sps: Data, pps: Data)? {
        let b = [UInt8](data)
        var sps: Data?
        var pps: Data?
        for r in nalUnits(data) {
            switch b[r.lowerBound] & 0x1F {
            case nalSPS: sps = Data(b[r])
            case nalPPS: pps = Data(b[r])
            default: break
            }
        }
        guard let s = sps, let p = pps else { return nil }
        return (s, p)
    }

    /// Annex B -> AVCC (4-byte big-endian lengths). Parameter sets are dropped when `dropConfig`.
    public static func toAvcc(_ data: Data, dropConfig: Bool = false) -> Data {
        let b = [UInt8](data)
        var out = Data()
        for r in nalUnits(data) {
            let t = b[r.lowerBound] & 0x1F
            if dropConfig && (t == nalSPS || t == nalPPS) { continue }
            out.appendU32(UInt32(r.count))
            out.append(contentsOf: b[r])
        }
        return out
    }
}

public enum FrameSplitter {
    public static func split(src: String, seq: Int64, pts: Int64, key: Bool, config: Bool, data: Data,
                             maxPart: Int = VideoFramePayload.maxPart) -> [VideoFramePayload] {
        let parts = max(1, (data.count + maxPart - 1) / maxPart)
        return (0..<parts).map { p in
            let from = data.startIndex + p * maxPart
            let to = min(data.endIndex, from + maxPart)
            return VideoFramePayload(src: src, seq: seq, pts: pts, key: key, config: config, part: p, parts: parts,
                                     data: Data(data[from..<to]))
        }
    }
}

/// Reassembles split frames; an incomplete frame is abandoned when a newer one starts.
public final class FrameAssembler {
    public struct Frame: Equatable {
        public let seq: Int64
        public let pts: Int64
        public let key: Bool
        public let config: Bool
        public let data: Data
    }

    private var seq: Int64 = -1
    private var next = 0
    private var buf = Data()
    private var head: VideoFramePayload?
    public private(set) var dropped = 0

    public init() {}

    public func accept(_ f: VideoFramePayload) -> Frame? {
        if f.parts == 1 {
            if next != 0 { abandon() }
            return Frame(seq: f.seq, pts: f.pts, key: f.key, config: f.config, data: f.data)
        }
        if f.seq != seq {
            if next != 0 { abandon() }
            guard f.part == 0 else {
                dropped += 1
                return nil
            }
            seq = f.seq
            buf = Data()
            head = f
        } else if f.part != next {
            abandon()
            return nil
        }
        buf.append(f.data)
        next = f.part + 1
        guard next >= f.parts else { return nil }
        let h = head ?? f
        next = 0
        seq = -1
        return Frame(seq: h.seq, pts: h.pts, key: h.key, config: h.config, data: buf)
    }

    private func abandon() {
        dropped += 1
        next = 0
        seq = -1
        head = nil
    }
}

/// Where the video sits inside a view (aspect fit, centred) and the mapping from view points
/// to normalised frame coordinates. `flipped` is true for AppKit's default bottom-left origin.
public struct VideoGeometry: Equatable {
    public var view: CGSizeLite
    public var video: CGSizeLite

    public init(view: CGSizeLite, video: CGSizeLite) {
        self.view = view
        self.video = video
    }

    /// The fitted video rect inside the view (origin top-left).
    public var fitted: (x: Double, y: Double, w: Double, h: Double) {
        guard view.w > 0, view.h > 0, video.w > 0, video.h > 0 else { return (0, 0, 0, 0) }
        let scale = min(view.w / video.w, view.h / video.h)
        let w = video.w * scale
        let h = video.h * scale
        return ((view.w - w) / 2, (view.h - h) / 2, w, h)
    }

    /// Normalised (0...1) frame coordinates for a view point, or nil in the letterbox.
    /// `bottomLeftOrigin`: AppKit view coordinates (y up) instead of top-left.
    public func normalized(x: Double, y: Double, bottomLeftOrigin: Bool = false, clamp: Bool = false) -> (x: Double, y: Double)? {
        let f = fitted
        guard f.w > 0, f.h > 0 else { return nil }
        let ty = bottomLeftOrigin ? view.h - y : y
        var nx = (x - f.x) / f.w
        var ny = (ty - f.y) / f.h
        if clamp {
            nx = min(1, max(0, nx))
            ny = min(1, max(0, ny))
        }
        guard (0...1).contains(nx), (0...1).contains(ny) else { return nil }
        return (nx, ny)
    }
}

/// A platform-neutral size (CoreGraphics isn't available to the Linux test build).
public struct CGSizeLite: Equatable {
    public var w: Double
    public var h: Double
    public init(_ w: Double, _ h: Double) {
        self.w = w
        self.h = h
    }
}

/// Turns one mouse press (down ... up) into a phone gesture: a tap, a long press, or a
/// swipe/drag that follows the pointer from where it went down to where it came up.
public enum GestureClassifier {
    /// Movement (in normalised units of the frame's shorter side) below which a press is still a tap.
    public static let slop = 0.012
    public static let longPressMs: Int64 = 450
    public static let maxSwipeMs: Int64 = 2_500

    public static func classify(down: (x: Double, y: Double), up: (x: Double, y: Double), durationMs: Int64,
                                aspect: Double = 1, rotation: Int? = nil) -> ScreenInputPayload {
        // Compare movement in comparable units: scale x by the frame's aspect (w/h).
        let dx = (up.x - down.x) * max(aspect, 1)
        let dy = (up.y - down.y) * max(1 / max(aspect, 0.0001), 1)
        let moved = (dx * dx + dy * dy).squareRoot() > slop
        if !moved {
            if durationMs >= longPressMs {
                return ScreenInputPayload(k: InputKinds.longPress, x: down.x, y: down.y, dur: max(durationMs, 600), rot: rotation)
            }
            return ScreenInputPayload(k: InputKinds.tap, x: down.x, y: down.y, rot: rotation)
        }
        let dur = min(max(durationMs, 60), maxSwipeMs)
        return ScreenInputPayload(k: InputKinds.swipe, x: down.x, y: down.y, x2: up.x, y2: up.y, dur: dur, rot: rotation)
    }

    /// A scroll-wheel burst at (x, y) as a short swipe. Positive `dy` (content moves down /
    /// wheel towards the user on natural scrolling) drags the finger down. Distances are
    /// normalised and clamped so the swipe stays on screen.
    public static func scroll(at p: (x: Double, y: Double), dx: Double, dy: Double, rotation: Int? = nil) -> ScreenInputPayload? {
        guard dx != 0 || dy != 0 else { return nil }
        let x2 = min(0.98, max(0.02, p.x + dx))
        let y2 = min(0.98, max(0.02, p.y + dy))
        return ScreenInputPayload(k: InputKinds.scroll, x: p.x, y: p.y, x2: x2, y2: y2, dur: 120, rot: rotation)
    }
}

/// Fires the Mac's low-battery alert once when the phone drops to `threshold` or below while
/// not charging; re-arms once it charges or climbs above `threshold + hysteresis`.
public struct LowBatteryMonitor: Equatable {
    public var threshold: Int
    public var hysteresis: Int
    public private(set) var armed = true

    public init(threshold: Int = 20, hysteresis: Int = 5) {
        self.threshold = threshold
        self.hysteresis = hysteresis
    }

    /// Returns true when the alert should be shown for this reading.
    public mutating func update(level: Int, charging: Bool) -> Bool {
        if charging || level > threshold + hysteresis {
            armed = true
            return false
        }
        if armed && level <= threshold {
            armed = false
            return true
        }
        return false
    }
}

/// Mac-side frame statistics for VideoFeedback (bitrate, lag behind the newest frame, drops).
public struct VideoStats {
    private var bytes = 0
    private var windowStart: Int64 = 0
    public private(set) var dropped = 0
    public private(set) var frames = 0

    public init() {}

    public mutating func received(bytes n: Int, now: Int64) {
        if windowStart == 0 { windowStart = now }
        bytes += n
        frames += 1
    }

    public mutating func drop(_ n: Int = 1) { dropped += n }

    /// Returns feedback once at least a second has passed, and resets the window.
    public mutating func feedback(now: Int64, lagMs: Int, needKeyframe: Bool) -> VideoFeedbackPayload? {
        guard windowStart > 0, now - windowStart >= 1000 || needKeyframe else { return nil }
        let secs = max(Double(now - windowStart) / 1000, 0.001)
        let kbps = Int(Double(bytes) * 8 / 1000 / secs)
        let fb = VideoFeedbackPayload(kbps: kbps, lagMs: lagMs, dropped: dropped, keyframe: needKeyframe)
        bytes = 0
        dropped = 0
        frames = 0
        windowStart = now
        return fb
    }
}
