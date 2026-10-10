//
// Reconnect.swift — the "quit and reopen" safety net (0.3.1), mirrored in Reconnect.kt.
//

import Foundation

/// Fed the link state every few seconds; says when the Bluetooth link has been down long
/// enough, while it should be up (paired, radio on, not pairing), that the app should tear
/// its radio stack down and start it again. Waits `firstAfterMs`, then doubles up to
/// `maxAfterMs`; `poke()` (wake from sleep, network change) allows one early restart once the
/// link has been down for `pokeAfterMs`.
public struct ReconnectWatchdog {
    let firstAfterMs: Int64
    let maxAfterMs: Int64
    let pokeAfterMs: Int64
    public private(set) var attempts = 0
    private var downSince: Int64 = -1
    private var poked = false

    public init(firstAfterMs: Int64 = 45_000, maxAfterMs: Int64 = 300_000, pokeAfterMs: Int64 = 10_000) {
        self.firstAfterMs = firstAfterMs
        self.maxAfterMs = maxAfterMs
        self.pokeAfterMs = pokeAfterMs
    }

    /// Restart now? `up` is "Bluetooth session established"; `eligible` "it should be".
    public mutating func observe(now: Int64, up: Bool, eligible: Bool) -> Bool {
        if up {
            reset()
            return false
        }
        guard eligible else {
            downSince = -1
            poked = false
            return false
        }
        if downSince < 0 { downSince = now }
        let down = now - downSince
        guard down >= thresholdMs || (poked && down >= pokeAfterMs) else { return false }
        attempts += 1
        downSince = now
        poked = false
        return true
    }

    public var thresholdMs: Int64 { min(firstAfterMs << Int64(min(attempts, 8)), maxAfterMs) }

    public mutating func poke() { poked = true }

    public mutating func reset() {
        attempts = 0
        downSince = -1
        poked = false
    }
}

/// Which phone the Mac's GATT peripheral talks to when a central subscribes.
public enum SubscriberDecision: Equatable {
    case accept, replace, ignore
}

public enum LinkPolicy {
    /// A new subscription: keep a live link, but never let a stale central (a phone whose
    /// disconnect CoreBluetooth never reported, or the same phone under a new identifier after
    /// its address rotated) block the reconnect. 0.3.0 ignored it, so the link stayed dead
    /// until Tether was quit.
    public static func subscriber(current: UUID?, incoming: UUID, currentLinkEstablished: Bool) -> SubscriberDecision {
        guard let current else { return .accept }
        if current == incoming { return .replace } // same phone re-subscribed: fresh link
        return currentLinkEstablished ? .ignore : .replace
    }

    /// CBError.alreadyAdvertising (9) means we are advertising: success, not a failure.
    public static let alreadyAdvertisingCode = 9

    public enum AdvertiseOutcome: Equatable { case advertising, retry }

    public static func advertiseResult(errorCode: Int?) -> AdvertiseOutcome {
        guard let code = errorCode else { return .advertising }
        return code == alreadyAdvertisingCode ? .advertising : .retry
    }
}
