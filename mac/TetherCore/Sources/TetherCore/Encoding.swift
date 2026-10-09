import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public enum B64 {
    public static func encode(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    /// Decodes base64url with or without padding; nil for malformed input.
    public static func decode(_ s: String) -> Data? {
        var t = s.trimmingCharacters(in: CharacterSet(charactersIn: "="))
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard t.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        t = t.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}

public enum Hex {
    public static func encode(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    public static func decode(_ s: String) -> Data? {
        let chars = Array(s.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 48...57: return c - 48
        case 97...102: return c - 87
        case 65...70: return c - 55
        default: return nil
        }
    }
}

/// Constant-time equality for secrets.
public func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
    guard a.count == b.count else { return false }
    var diff: UInt8 = 0
    for (x, y) in zip(a, b) { diff |= x ^ y }
    return diff == 0
}

/// `3F9A·0C21·77B4` — short fingerprint of a static key for display.
public func fingerprint(_ publicKey: Data) -> String {
    let h = Hex.encode(Data(SHA256.hash(data: publicKey)).prefix(6)).uppercased()
    var parts: [String] = []
    var s = Substring(h)
    while !s.isEmpty {
        parts.append(String(s.prefix(4)))
        s = s.dropFirst(4)
    }
    return parts.joined(separator: "·")
}

public func sha256Hex(_ d: Data) -> String { Hex.encode(Data(SHA256.hash(data: d))) }

/// Incremental SHA-256 for hashing files in chunks.
public struct StreamingSHA256 {
    private var h = SHA256()
    public init() {}
    public mutating func update(_ d: Data) { h.update(data: d) }
    public func finalizeHex() -> String { Hex.encode(Data(h.finalize())) }
}

/// Capped exponential backoff with jitter (mirrors Backoff.kt).
public struct Backoff {
    public let baseMs: Int64
    public let maxMs: Int64
    public let jitter: Double
    public private(set) var attempt = 0
    private var random: () -> Double

    public init(baseMs: Int64 = 1_000, maxMs: Int64 = 60_000, jitter: Double = 0.25,
                random: @escaping () -> Double = { Double.random(in: 0..<1) }) {
        self.baseMs = baseMs
        self.maxMs = maxMs
        self.jitter = jitter
        self.random = random
    }

    public mutating func next() -> Int64 {
        let exp = Int64(min(attempt, 30))
        let raw = min(maxMs, baseMs << exp)
        attempt += 1
        return max(1, Int64(Double(raw) * (1 - jitter * random())))
    }

    public mutating func reset() { attempt = 0 }
}

/// Keepalive bookkeeping (mirrors Heartbeat in Backoff.kt).
public struct Heartbeat {
    public enum Action { case none, sendPing, dead }
    let intervalMs: Int64
    let timeoutMs: Int64
    private var lastHeard: Int64 = 0
    private var pingSentAt: Int64 = -1

    public init(intervalMs: Int64 = 45_000, timeoutMs: Int64 = 20_000) {
        self.intervalMs = intervalMs
        self.timeoutMs = timeoutMs
    }

    public mutating func reset(_ now: Int64) {
        lastHeard = now
        pingSentAt = -1
    }

    public mutating func heard(_ now: Int64) {
        lastHeard = now
        pingSentAt = -1
    }

    public mutating func tick(_ now: Int64) -> Action {
        if pingSentAt >= 0 && now - pingSentAt >= timeoutMs { return .dead }
        if pingSentAt < 0 && now - lastHeard >= intervalMs {
            pingSentAt = now
            return .sendPing
        }
        return .none
    }
}
