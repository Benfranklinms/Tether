//
// Release052.swift — pure logic behind the 0.5.2 Mac UI: rectangle (marquee) selection, video
// playback state for the Photos viewer, the Find my Mac alarm tone, sidebar page direction,
// and the user-facing text for Insert from Phone and Instant Hotspot. No AppKit/SwiftUI here,
// so all of it is unit-tested on Linux.
//

import Foundation

/// A plain rectangle (no CoreGraphics on Linux).
public struct Box: Equatable {
    public var x: Double
    public var y: Double
    public var w: Double
    public var h: Double
    public init(x: Double, y: Double, w: Double, h: Double) {
        self.x = x
        self.y = y
        self.w = w
        self.h = h
    }

    public var maxX: Double { x + w }
    public var maxY: Double { y + h }

    public func intersects(_ o: Box) -> Bool { x < o.maxX && o.x < maxX && y < o.maxY && o.y < maxY }
    public func contains(px: Double, py: Double) -> Bool { px >= x && px <= maxX && py >= y && py <= maxY }
}

/// Finder-style rectangle selection: drag from empty space, every item the rectangle touches
/// is selected (⌘/⇧ adds to the previous selection).
public enum MarqueeSelection {
    /// Drags shorter than this are clicks.
    public static let threshold = 4.0

    public static func rect(from a: (Double, Double), to b: (Double, Double)) -> Box {
        Box(x: Swift.min(a.0, b.0), y: Swift.min(a.1, b.1), w: abs(a.0 - b.0), h: abs(a.1 - b.1))
    }

    public static func isDrag(from a: (Double, Double), to b: (Double, Double)) -> Bool {
        hypot(a.0 - b.0, a.1 - b.1) >= threshold
    }

    /// Ids whose frames the rectangle touches, in `order`.
    public static func hits(_ rect: Box, frames: [String: Box], order: [String]) -> [String] {
        order.filter { id in frames[id].map { rect.intersects($0) } ?? false }
    }

    /// A marquee may only start on empty space (a drag that starts on an item drags the item).
    public static func startsOnItem(_ x: Double, _ y: Double, frames: [String: Box]) -> Bool {
        frames.values.contains { $0.contains(px: x, py: y) }
    }
}

/// What the Photos viewer shows for a video, from the fetch state.
public enum VideoPlayback: Equatable {
    /// Not asked for yet.
    case idle
    /// FS_GET sent, waiting for the phone's offer.
    case requesting
    /// Bytes arriving. `fraction` 0...1; `wifi` = over the Wi-Fi fast path (else Bluetooth).
    case fetching(fraction: Double, wifi: Bool)
    /// Too big for Bluetooth; the phone waits for Wi-Fi.
    case waitingForWifi
    /// Ready to play from this local file.
    case ready(path: String)
    case failed(String)

    /// The playback state for an item of `size` bytes, given the newest matching incoming
    /// transfer (if any) and a cached file (if any).
    public static func state(cachedPath: String?, record: TransferRecord?, requested: Bool, error: String? = nil) -> VideoPlayback {
        if let cachedPath { return .ready(path: cachedPath) }
        if let error { return .failed(error) }
        if let r = record {
            switch r.status {
            case .DONE: return r.ref.map { .ready(path: $0) } ?? .failed("The file went missing")
            case .FAILED, .CANCELLED: return .failed(r.error ?? "The transfer stopped")
            case .WAITING:
                return (r.error ?? "").lowercased().contains("wi-fi") ? .waitingForWifi : .fetching(fraction: 0, wifi: r.via == .WIFI)
            default:
                let f = r.size > 0 ? Double(r.transferred) / Double(r.size) : 0
                return .fetching(fraction: Swift.min(1, Swift.max(0, f)), wifi: r.via == .WIFI)
            }
        }
        return requested ? .requesting : .idle
    }

    /// Short status under the progress ring.
    public var label: String {
        switch self {
        case .idle, .requesting: return "Asking your phone…"
        case .fetching(let f, let wifi): return "\(Int((f * 100).rounded()))% · \(wifi ? "Wi-Fi" : "Bluetooth")"
        case .waitingForWifi: return "Waiting for Wi-Fi (too big for Bluetooth)"
        case .ready: return ""
        case .failed(let e): return e
        }
    }

    /// The cache folder for one video (its MediaStore id, made path-safe).
    public static func folderName(for id: String) -> String {
        let safe = id.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : "_" }
        return "v-" + String(safe).prefix(64)
    }

    /// A cached copy is valid when it has the expected size (or the size is unknown).
    public static func cachedFile(in entries: [(name: String, size: Int64)], expectedSize: Int64) -> String? {
        entries.first { !$0.name.hasPrefix(".") && (expectedSize <= 0 || $0.size == expectedSize) }?.name
    }
}

/// The Find my Mac sound: a bright two-tone chime with a pause, as 16-bit mono PCM in a WAV,
/// played on a loop at full volume until someone presses Found it.
public enum AlarmTone {
    public static let sampleRate = 44_100

    public static func wav(seconds: Double = 1.6) -> Data {
        let n = Int(Double(sampleRate) * seconds)
        var pcm = [Int16](repeating: 0, count: n)
        let notes: [(start: Double, len: Double, freq: Double)] = [(0, 0.18, 1_760), (0.2, 0.18, 2_349), (0.4, 0.18, 1_760),
                                                                    (0.6, 0.30, 2_349)]
        for note in notes {
            let s0 = Int(note.start * Double(sampleRate))
            let len = Int(note.len * Double(sampleRate))
            for i in 0..<len where s0 + i < n {
                let t = Double(i) / Double(sampleRate)
                let env = Swift.min(1, Double(i) / 220) * Swift.min(1, Double(len - i) / 900)
                let v = sin(2 * .pi * note.freq * t) * 0.8 + sin(4 * .pi * note.freq * t) * 0.15
                pcm[s0 + i] = Int16(Swift.max(-1, Swift.min(1, v * env)) * 32_000)
            }
        }
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(n * 2)
        d.append(contentsOf: Array("RIFF".utf8))
        u32(36 + bytes)
        d.append(contentsOf: Array("WAVEfmt ".utf8))
        u32(16)
        u16(1)
        u16(1)
        u32(UInt32(sampleRate))
        u32(UInt32(sampleRate * 2))
        u16(2)
        u16(16)
        d.append(contentsOf: Array("data".utf8))
        u32(bytes)
        for s in pcm { u16(UInt16(bitPattern: s)) }
        return d
    }
}

/// Which way the main window's page content slides: down the sidebar = content comes up from
/// below (+1), up the sidebar = from above (−1).
public enum PageDirection {
    public static func between(_ from: Int, _ to: Int) -> Double { to >= from ? 1 : -1 }
}

/// Text for Insert from Phone.
public enum CaptureText {
    public static func status(kind: String, state: String, reason: String?) -> String {
        let what = kind == CaptureKinds.scan ? "scan" : "photo"
        switch state {
        case "asking": return "Opening the \(kind == CaptureKinds.scan ? "scanner" : "camera") on your phone…"
        case CaptureStates.open: return "Take the \(what) on your phone"
        case CaptureStates.sending: return "Receiving the \(what)…"
        case CaptureStates.done: return "Inserted"
        default: return reason.map { describe($0) } ?? "Couldn't get the \(what)"
        }
    }

    public static func describe(_ reason: String) -> String {
        switch reason {
        case CaptureReasons.cancelled: return "Cancelled on the phone"
        case CaptureReasons.disabled: return "Insert from Phone is off on your phone"
        case CaptureReasons.busy: return "Your phone is already taking one"
        case CaptureReasons.noCamera: return "No camera app on your phone"
        case CaptureReasons.tapPhone: return "Tap the notification on your phone to open the camera"
        case "timeout": return "Your phone didn't answer"
        default: return reason.hasPrefix("error:") ? "Phone error: \(reason.dropFirst(6))" : reason
        }
    }

    /// Paste into the app the user was in, or save to the Desktop when Tether can't type ⌘V.
    public enum Insert: Equatable { case paste, desktop }

    public static func insertPlan(accessibility: Bool, frontmostIsTether: Bool) -> Insert {
        accessibility && !frontmostIsTether ? .paste : .desktop
    }
}

/// Text and next steps for Instant Hotspot.
public enum HotspotText {
    public static func status(_ state: String, reason: String?, joining: Bool) -> String {
        switch state {
        case "asking", HotspotStates.starting: return "Turning on your phone's hotspot…"
        case HotspotStates.manual: return "Turn on Hotspot on your phone — it's on screen there"
        case HotspotStates.on: return joining ? "Joining…" : "Hotspot is on"
        case HotspotStates.off: return "Hotspot is off"
        default: return reason.map { describe($0) } ?? "Couldn't turn on the hotspot"
        }
    }

    public static func describe(_ reason: String) -> String {
        switch reason {
        case HotspotReasons.disabled: return "Instant Hotspot is off in Tether on your phone"
        case HotspotReasons.noService: return "Turn on Tether control on your phone so it can flip the switch"
        case HotspotReasons.noSwitch: return "Couldn't find the hotspot switch — turn it on by hand on your phone"
        case HotspotReasons.noCredentials: return "Save your hotspot name and password in Tether on your phone first"
        case HotspotReasons.timeout: return "Your phone didn't turn the hotspot on in time"
        case "join_failed": return "Couldn't join the hotspot — check the saved password on your phone"
        case "no_wifi": return "This Mac's Wi-Fi is off"
        default: return reason.hasPrefix("error:") ? "Phone error: \(reason.dropFirst(6))" : reason
        }
    }
}

/// When to (re)try the Wi-Fi fast path while the link is Bluetooth-only; mirrors
/// core/wifi/FastPathRetry.kt. See there for why (0.5.2 hotspot / network-change fix).
public struct FastPathRetry: Equatable {
    public static let baseMs: Int64 = 4_000
    public static let capMs: Int64 = 60_000
    public static let settleMs: Int64 = 1_200

    public private(set) var attempts = 0
    public private(set) var nextAt: Int64?

    public init() {}

    public mutating func linkUp(now: Int64) {
        attempts = 0
        nextAt = now
    }

    public mutating func networkChanged(now: Int64) {
        attempts = 0
        let soon = now + Self.settleMs
        nextAt = nextAt.map { Swift.max(now, Swift.min($0, soon)) } ?? soon
    }

    public mutating func attempted(now: Int64) {
        attempts += 1
        nextAt = now + Self.delay(for: attempts)
    }

    public mutating func connected() {
        attempts = 0
        nextAt = nil
    }

    public mutating func wifiLost(now: Int64) {
        attempts = 0
        nextAt = now + Self.settleMs
    }

    public mutating func linkDown() {
        attempts = 0
        nextAt = nil
    }

    public func due(now: Int64, bleReady: Bool, wifiReady: Bool) -> Bool {
        guard bleReady, !wifiReady, let at = nextAt else { return false }
        return now >= at
    }

    public static func delay(for attempt: Int) -> Int64 {
        Swift.min(capMs, baseMs << Int64(Swift.max(0, Swift.min(10, attempt - 1))))
    }

    /// Phone addresses on one of the Mac's /24 subnets first (e.g. the hotspot gateway).
    public static func rank(_ addrs: [String], local: [String]) -> [String] {
        func p24(_ a: String) -> String? {
            let p = a.split(separator: ".")
            return p.count == 4 ? p.prefix(3).joined(separator: ".") : nil
        }
        let nets = Set(local.compactMap(p24))
        let on = addrs.filter { p24($0).map(nets.contains) ?? false }
        return on + addrs.filter { !on.contains($0) }
    }
}
