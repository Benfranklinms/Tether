//
// Proximity.swift — "lock this Mac when my phone walks away" (0.3.0).
//

import Foundation

/// How far the phone may go, mapped to RSSI thresholds (dBm, as the phone measures the link).
/// RSSI is noisy and depends on pockets, bodies and walls, so these are approximate.
public enum ProximityDistance: String, Codable, CaseIterable {
    case near, medium, far

    /// Below this smoothed RSSI (minus hysteresis) the phone counts as away.
    public var threshold: Double {
        switch self {
        case .near: return -62    // roughly 1-2 m
        case .medium: return -72  // roughly 3-5 m
        case .far: return -82     // roughly 8-10 m
        }
    }

    public var label: String {
        switch self {
        case .near: return "About 1-2 m"
        case .medium: return "About 3-5 m"
        case .far: return "About 8-10 m"
        }
    }
}

public struct ProximityConfig: Equatable {
    public var distance: ProximityDistance
    /// How long the phone must stay away before the Mac locks.
    public var awaySeconds: Int
    /// dB the signal must drop below the threshold before counting as away (avoids flapping).
    public var hysteresis: Double
    /// Exponential smoothing factor per reading (0...1, higher reacts faster).
    public var alpha: Double

    public init(distance: ProximityDistance = .medium, awaySeconds: Int = 30, hysteresis: Double = 6, alpha: Double = 0.25) {
        self.distance = distance
        self.awaySeconds = awaySeconds
        self.hysteresis = hysteresis
        self.alpha = alpha
    }
}

/// Smoothed RSSI with hysteresis and a dwell time. Losing the Bluetooth link counts as away
/// (the phone left range entirely). Pure and clock-injected so it can be unit-tested.
public struct ProximityMonitor: Equatable {
    public enum State: String, Equatable { case unknown, near, leaving, away }

    public var config: ProximityConfig
    public private(set) var smoothed: Double?
    public private(set) var state: State = .unknown
    private var leavingSince: Int64?

    public init(config: ProximityConfig = ProximityConfig()) {
        self.config = config
    }

    /// A new RSSI reading at time `now` (ms).
    public mutating func reading(_ rssi: Int, now: Int64) {
        let r = Double(rssi)
        // Ignore impossible values some stacks report (0 or > 0 dBm).
        guard r < 0, r > -127 else { return }
        let s = smoothed.map { $0 + config.alpha * (r - $0) } ?? r
        smoothed = s
        let t = config.distance.threshold
        if s >= t {
            state = .near
            leavingSince = nil
        } else if s < t - config.hysteresis {
            if leavingSince == nil { leavingSince = now }
            state = .leaving
        }
        // In the band between: keep the previous state (hysteresis).
        evaluate(now)
    }

    /// The Bluetooth link to the phone dropped.
    public mutating func linkLost(now: Int64) {
        smoothed = nil
        if leavingSince == nil { leavingSince = now }
        if state != .away { state = .leaving }
        evaluate(now)
    }

    /// Call about once a second so the dwell time elapses without new readings.
    public mutating func tick(now: Int64) { evaluate(now) }

    /// Back to not knowing (feature turned off or re-paired).
    public mutating func reset() {
        smoothed = nil
        state = .unknown
        leavingSince = nil
    }

    private mutating func evaluate(_ now: Int64) {
        guard let since = leavingSince, state == .leaving || state == .away else { return }
        if now - since >= Int64(config.awaySeconds) * 1000 { state = .away }
    }
}

/// Locks at most once per absence, never while the Mac is showing the phone's screen, and only
/// when the user turned the feature on. Re-arms when the phone is near again.
public struct LockPolicy: Equatable {
    public private(set) var lockedThisAbsence = false

    public init() {}

    public mutating func shouldLock(state: ProximityMonitor.State, enabled: Bool, screenSharing: Bool) -> Bool {
        switch state {
        case .near, .unknown:
            lockedThisAbsence = false
            return false
        case .leaving:
            return false
        case .away:
            guard enabled, !screenSharing, !lockedThisAbsence else { return false }
            lockedThisAbsence = true
            return true
        }
    }
}
