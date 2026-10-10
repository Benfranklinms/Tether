//
// Calling.swift — Mac-side logic for 0.4.0 calling and the panel layout, kept in TetherCore
// so it is unit-tested on every platform: dialable numbers, tel: URLs, the missed-call badge,
// the call timer, recents/contacts matching, and the pinned action tiles.
//

import Foundation

/// Cleans a typed or clicked number into something the phone can dial (same rules as the
/// phone's `Dialable`): digits, a leading +, * and #, pauses (`,`/`p`) and waits (`;`/`w`).
public enum Dialable {
    private static let separators: Set<Character> = [
        " ", "-", "(", ")", ".", "/", "\u{00A0}", "\u{2011}", "\u{2012}", "\u{2013}", "\u{2212}",
    ]
    public static let maxLength = 40

    public static func clean(_ raw: String) -> String? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        var out = ""
        var sawPlus = false
        for ch in s {
            if ch.isASCII && ch.isNumber {
                out.append(ch)
            } else if ch == "+" {
                guard out.isEmpty, !sawPlus else { return nil }
                sawPlus = true
                out.append(ch)
            } else if ch == "*" || ch == "#" {
                out.append(ch)
            } else if ch == "," || ch == "p" || ch == "P" {
                out.append(",")
            } else if ch == ";" || ch == "w" || ch == "W" {
                out.append(";")
            } else if separators.contains(ch) {
                continue
            } else {
                return nil
            }
        }
        let main = out.prefix { $0 != "," && $0 != ";" }
        guard main.contains(where: { $0.isNumber || $0 == "*" || $0 == "#" }), out.count <= maxLength else { return nil }
        return out
    }

    /// Digits only, for matching ("+91 98470-12345" -> "919847012345").
    public static func digits(_ s: String) -> String { String(s.filter { $0.isASCII && $0.isNumber }) }
}

/// tel: / callto: / telprompt: links clicked anywhere on the Mac.
public enum TelURL {
    public static let schemes: Set<String> = ["tel", "callto", "telprompt"]

    /// The dialable number in [link], or nil when it isn't a phone link or holds no number.
    public static func number(from link: String) -> String? {
        guard let colon = link.firstIndex(of: ":") else { return nil }
        let scheme = link[..<colon].lowercased()
        guard schemes.contains(scheme) else { return nil }
        var rest = String(link[link.index(after: colon)...])
        if rest.hasPrefix("//") { rest.removeFirst(2) }
        if let q = rest.firstIndex(of: "?") { rest = String(rest[..<q]) }
        rest = rest.removingPercentEncoding ?? rest
        // RFC 3966 parameters: keep an extension as a pause, drop the rest (phone-context, isub…).
        var parts = rest.split(separator: ";", omittingEmptySubsequences: true).map(String.init)
        guard !parts.isEmpty else { return nil }
        var number = parts.removeFirst()
        if let ext = parts.first(where: { $0.lowercased().hasPrefix("ext=") }) {
            number += "," + ext.dropFirst(4)
        }
        if number.hasSuffix("/") { number.removeLast() }
        return Dialable.clean(number)
    }
}

/// The missed-call badge: missed calls in the log the user hasn't looked at on this Mac, plus
/// missed-call events that arrived after the last log refresh. All times are the phone's clock.
public enum MissedCalls {
    public static func badge(log: [CallLogEntry], liveMissed: [Int64], seenUntil: Int64) -> Int {
        let fromLog = log.filter { $0.isMissed && $0.date > seenUntil }.count
        let newest = log.map(\.date).max() ?? Int64.min
        let pending = liveMissed.filter { $0 > seenUntil && $0 > newest }.count
        return fromLog + pending
    }

    /// What "seen" moves to when the user opens the call list.
    public static func seen(log: [CallLogEntry], liveMissed: [Int64], previous: Int64) -> Int64 {
        max(previous, log.map(\.date).max() ?? previous, liveMissed.max() ?? previous)
    }
}

public enum CallClock {
    /// "0:07", "12:34" or "1:02:03" since [since] (phone ms), given the phone's time now.
    public static func elapsed(since: Int64, phoneNow: Int64) -> String {
        guard since > 0 else { return "" }
        let s = max(0, Int((phoneNow - since) / 1000))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    /// "1 min 15 s", "45 s", "" for a call-log duration in seconds.
    public static func duration(_ seconds: Int64) -> String {
        guard seconds > 0 else { return "" }
        if seconds < 60 { return "\(seconds) s" }
        let m = seconds / 60
        let s = seconds % 60
        if m >= 60 { return "\(m / 60) h \(m % 60) min" }
        return s == 0 ? "\(m) min" : "\(m) min \(s) s"
    }
}

public enum CallSearch {
    /// True when [query] matches a name (case/diacritic-insensitive) or, with 3+ digits, a number.
    public static func matches(_ query: String, name: String?, numbers: [String]) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return true }
        if let name, name.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil { return true }
        let d = Dialable.digits(q)
        guard d.count >= 3 else { return false }
        return numbers.contains { Dialable.digits($0).contains(d) }
    }
}

// MARK: - Panel layout (0.4.0 tidy)

/// The six quick actions in the menu-bar panel.
public enum PanelTile: String, Codable, CaseIterable, Identifiable {
    case clipboard, file, messages, downloads, findPhone, screen
    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .clipboard: return "Send clipboard"
        case .file: return "Send file…"
        case .messages: return "Messages"
        case .downloads: return "Downloads"
        case .findPhone: return "Find phone"
        case .screen: return "Phone screen"
        }
    }

    public var symbol: String {
        switch self {
        case .clipboard: return "doc.on.clipboard"
        case .file: return "arrow.up.doc"
        case .messages: return "message"
        case .downloads: return "folder"
        case .findPhone: return "speaker.wave.2"
        case .screen: return "rectangle.on.rectangle"
        }
    }
}

/// Which three actions are pinned, whether "More" is open, and whether the player is compact.
public struct PanelLayout: Codable, Equatable {
    public static let pinnedCount = 3
    public static let defaultPinned: [PanelTile] = [.clipboard, .file, .messages]

    public private(set) var pinned: [PanelTile]
    public var moreExpanded: Bool
    public var compactPlayer: Bool

    public init(pinned: [PanelTile] = PanelLayout.defaultPinned, moreExpanded: Bool = false, compactPlayer: Bool = false) {
        self.pinned = Self.normalize(pinned)
        self.moreExpanded = moreExpanded
        self.compactPlayer = compactPlayer
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Unknown tiles (from a newer build) are dropped, not fatal.
        let raw = try? c.decodeIfPresent([String].self, forKey: .pinned)
        pinned = Self.normalize((raw ?? Self.defaultPinned.map(\.rawValue)).compactMap(PanelTile.init(rawValue:)))
        moreExpanded = (try? c.decodeIfPresent(Bool.self, forKey: .moreExpanded)) ?? false
        compactPlayer = (try? c.decodeIfPresent(Bool.self, forKey: .compactPlayer)) ?? false
    }

    /// The tiles behind "More", in their usual order.
    public var more: [PanelTile] { PanelTile.allCases.filter { !pinned.contains($0) } }

    /// Puts [tile] in [slot]; if it was pinned elsewhere the two swap places.
    public mutating func pin(_ tile: PanelTile, at slot: Int) {
        guard pinned.indices.contains(slot) else { return }
        if let i = pinned.firstIndex(of: tile) {
            pinned.swapAt(i, slot)
        } else {
            pinned[slot] = tile
        }
    }

    /// Moves the pinned tile at [from] to [to] (reorder).
    public mutating func move(from: Int, to: Int) {
        guard pinned.indices.contains(from), pinned.indices.contains(to), from != to else { return }
        let t = pinned.remove(at: from)
        pinned.insert(t, at: to)
    }

    public mutating func reset() { pinned = Self.defaultPinned }

    /// Exactly three distinct tiles: duplicates dropped, gaps filled from the defaults, then the rest.
    static func normalize(_ list: [PanelTile]) -> [PanelTile] {
        var out: [PanelTile] = []
        for t in list where !out.contains(t) && out.count < pinnedCount { out.append(t) }
        for t in defaultPinned + PanelTile.allCases where out.count < pinnedCount && !out.contains(t) { out.append(t) }
        return out
    }
}
