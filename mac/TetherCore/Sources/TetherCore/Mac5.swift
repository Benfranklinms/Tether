//
// Mac5.swift — pure, testable logic behind the 0.5.0 Mac features: updates, clipboard
// history, Continue cards, battery alerts, call media handling, Focus sync, proximity
// unlock, global shortcuts, universal search and typing into the phone.
//

import Foundation

// MARK: - Updates

/// Dotted version comparison ("0.5.0" > "0.4.3" > "0.4.3-dev"; "0.0.0-ci.N" is oldest).
public enum Versions {
    public static func compare(_ a: String, _ b: String) -> Int {
        let (an, ap) = split(a)
        let (bn, bp) = split(b)
        for i in 0..<max(an.count, bn.count) {
            let x = i < an.count ? an[i] : 0
            let y = i < bn.count ? bn[i] : 0
            if x != y { return x < y ? -1 : 1 }
        }
        switch (ap, bp) {
        case (nil, nil): return 0
        case (nil, _): return 1
        case (_, nil): return -1
        case let (x?, y?): return x == y ? 0 : (x < y ? -1 : 1)
        }
    }

    public static func newer(_ candidate: String, than current: String) -> Bool { compare(candidate, current) > 0 }

    private static func split(_ v: String) -> ([Int], String?) {
        var s = v.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("v") || s.hasPrefix("V") { s.removeFirst() }
        let parts = s.split(separator: "-", maxSplits: 1).map(String.init)
        let nums = (parts.first ?? "").split(separator: ".").map { Int($0.prefix { $0.isNumber }) ?? 0 }
        return (nums, parts.count > 1 && !parts[1].isEmpty ? parts[1] : nil)
    }
}

public struct UpdateAsset: Codable, Equatable {
    public var url: String
    public var sha256: String?
    public var size: Int64
    /// GitHub asset API URL (…/releases/assets/<id>); downloaded with a token and
    /// `Accept: application/octet-stream` for a private repo.
    public var apiURL: String?
    public var name: String?
    public init(url: String, sha256: String? = nil, size: Int64 = 0, apiURL: String? = nil, name: String? = nil) {
        self.url = url
        self.sha256 = sha256?.lowercased()
        self.size = size
        self.apiURL = apiURL
        self.name = name
    }
}

public struct UpdateInfo: Equatable {
    public var version: String
    public var notes: String
    public var page: String?
    public var macZip: UpdateAsset?
    public var macDmg: UpdateAsset?
    public var published: String?
}

/// Parses Tether's own feed (tether-update.json) and the GitHub `releases/latest` API answer.
public enum UpdateFeed {
    public static let defaultFeedURL = "https://raw.githubusercontent.com/Benfranklinms/Tether-releases/main/tether-update.json"
    public static let githubAPIURL = "https://api.github.com/repos/Benfranklinms/Tether/releases/latest"

    public static func parse(_ data: Data) -> UpdateInfo? {
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        return obj["tag_name"] != nil ? parseGitHub(obj) : parseFeed(obj)
    }

    private static func https(_ s: String?) -> String? {
        guard let s, s.hasPrefix("https://") else { return nil }
        return s
    }

    private static func parseFeed(_ o: [String: Any]) -> UpdateInfo? {
        guard var version = o["version"] as? String, !version.isEmpty else { return nil }
        if version.hasPrefix("v") { version.removeFirst() }
        let mac = o["mac"] as? [String: Any]
        let zip = https(mac?["url"] as? String).map {
            UpdateAsset(url: $0, sha256: mac?["sha256"] as? String, size: (mac?["size"] as? NSNumber)?.int64Value ?? 0)
        }
        let dmg = https(mac?["dmg"] as? String).map {
            UpdateAsset(url: $0, sha256: mac?["dmgSha256"] as? String, size: (mac?["dmgSize"] as? NSNumber)?.int64Value ?? 0)
        }
        return UpdateInfo(version: version, notes: o["notes"] as? String ?? "", page: o["url"] as? String,
                          macZip: zip, macDmg: dmg, published: o["published"] as? String)
    }

    private static func parseGitHub(_ o: [String: Any]) -> UpdateInfo? {
        guard var tag = o["tag_name"] as? String else { return nil }
        if (o["draft"] as? Bool) == true || (o["prerelease"] as? Bool) == true { return nil }
        if tag.hasPrefix("v") { tag.removeFirst() }
        let assets = o["assets"] as? [[String: Any]] ?? []
        func asset(_ suffix: String) -> UpdateAsset? {
            guard let a = assets.first(where: { ($0["name"] as? String)?.lowercased().hasSuffix(suffix) == true }),
                  let url = https(a["browser_download_url"] as? String) else { return nil }
            var sha: String?
            if let d = a["digest"] as? String, d.hasPrefix("sha256:") { sha = String(d.dropFirst(7)) }
            return UpdateAsset(url: url, sha256: sha, size: (a["size"] as? NSNumber)?.int64Value ?? 0,
                               apiURL: GitHubReleases.apiAsset(a["url"] as? String), name: a["name"] as? String)
        }
        return UpdateInfo(version: tag, notes: o["body"] as? String ?? "", page: o["html_url"] as? String,
                          macZip: asset(".zip"), macDmg: asset(".dmg"), published: o["published_at"] as? String)
    }
}

// MARK: - Private GitHub Releases (0.5.0)

/// Updates from the private Tether repo: a fine-grained, read-only token (Contents + Metadata,
/// this repo only) pasted once per device. The token goes only to api.github.com; a redirect to
/// the asset CDN is followed WITHOUT the Authorization header.
public enum GitHubReleases {
    public static let owner = "Benfranklinms"
    public static let repo = "Tether"
    public static let latestURL = "https://api.github.com/repos/\(owner)/\(repo)/releases/latest"
    public static let apiHost = "api.github.com"
    /// Where the user creates the token (fine-grained, prefilled name and description).
    public static let newTokenURL = "https://github.com/settings/personal-access-tokens/new"
        + "?name=Tether%20updates&description=Read-only%20updates%20for%20Tether&target_name=\(owner)"
    public static let noTokenMessage = "Add a GitHub token to check for updates"

    /// Only asset URLs on api.github.com for this repo are accepted.
    public static func apiAsset(_ s: String?) -> String? {
        guard let s, let u = URL(string: s), u.scheme == "https", u.host == apiHost,
              u.path.hasPrefix("/repos/\(owner)/\(repo)/releases/assets/") else { return nil }
        return s
    }

    /// Headers for a request to `url`: the token only for api.github.com over https.
    public static func headers(for url: URL, token: String?, binary: Bool) -> [String: String] {
        var h = ["X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Tether-updater",
                 "Accept": binary ? "application/octet-stream" : "application/vnd.github+json"]
        if let t = token, !t.isEmpty, mayAuthorize(url) { h["Authorization"] = "Bearer \(t)" }
        return h
    }

    public static func mayAuthorize(_ url: URL?) -> Bool { url?.scheme == "https" && url?.host == apiHost }

    /// Redirect policy: keep Authorization only when the redirect stays on api.github.com.
    public static func redirectKeepsAuth(to url: URL?) -> Bool { mayAuthorize(url) }

    /// Rough token sanity check (fine-grained `github_pat_…` or classic `ghp_…`).
    public static func looksLikeToken(_ s: String) -> Bool {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count >= 20, t.count <= 255, !t.contains(where: { $0.isWhitespace }) else { return false }
        return t.hasPrefix("github_pat_") || t.hasPrefix("ghp_")
    }

    /// For logs and UI: never the token itself.
    public static func redact(_ s: String) -> String { s.isEmpty ? "none" : "set (\(s.count) chars)" }

    /// Human message for an HTTP status from the releases API.
    public static func explain(status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 401: return "The GitHub token was rejected. Paste a new one."
        case 403: return "The GitHub token can't read Tether (needs Contents: Read-only on Benfranklinms/Tether), or rate limited."
        case 404: return "No release found, or the token can't see the Tether repo."
        default: return "GitHub answered \(status)."
        }
    }
}

/// Phone-originated automatic media (0.2) vs the 0.5 kinds (fs, clip) that are not media.
public enum PhoneKinds {
    public static let screenshot = "screenshot"
    public static let photo = "photo"
    public static func isMedia(_ kind: String?) -> Bool { kind == screenshot || kind == photo }
}

// MARK: - Clipboard history

public struct ClipItem: Codable, Equatable, Identifiable {
    public enum Kind: String, Codable { case text, image }
    public enum Direction: String, Codable { case fromPhone, fromMac }

    public var id: String
    public var kind: Kind
    public var text: String?
    /// File name of the saved image (in the app's clips folder).
    public var image: String?
    public var direction: Direction
    public var ts: Int64
    /// Content hash, so copying the same thing again moves it to the top instead of duplicating it.
    public var hash: String

    public init(id: String = UUID().uuidString, kind: Kind, text: String? = nil, image: String? = nil,
                direction: Direction, ts: Int64, hash: String) {
        self.id = id
        self.kind = kind
        self.text = text
        self.image = image
        self.direction = direction
        self.ts = ts
        self.hash = hash
    }
}

/// The last `limit` clipboard items in both directions, newest first.
public struct ClipHistory: Codable, Equatable {
    public private(set) var items: [ClipItem] = []
    public var limit = 20

    public init(limit: Int = 20) { self.limit = limit }

    /// Adds `item`; returns the items that fell off (so their image files can be deleted).
    @discardableResult
    public mutating func add(_ item: ClipItem) -> [ClipItem] {
        var dropped: [ClipItem] = []
        if let i = items.firstIndex(where: { $0.hash == item.hash }) {
            let old = items.remove(at: i)
            if old.image != item.image, old.image != nil { dropped.append(old) }
        }
        items.insert(item, at: 0)
        while items.count > limit { dropped.append(items.removeLast()) }
        return dropped
    }

    @discardableResult
    public mutating func remove(id: String) -> ClipItem? {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return nil }
        return items.remove(at: i)
    }

    public mutating func clear() -> [ClipItem] {
        defer { items.removeAll() }
        return items
    }
}

// MARK: - Continue on Mac

public struct ContinueCardState: Equatable, Identifiable {
    public var card: ContinuePayload
    public var thumb: Data
    /// Mac clock (ms) when it arrived; expiry counts from here (the phone's clock may differ).
    public var received: Int64
    public var id: String { card.id }

    public init(card: ContinuePayload, thumb: Data, received: Int64) {
        self.card = card
        self.thumb = thumb
        self.received = received
    }

    public func expired(_ now: Int64) -> Bool { now - received > Int64(max(30, card.ttl)) * 1000 }
}

/// Live Continue cards: newest first, same id replaces, expired and dismissed ones go away.
public struct ContinueStore: Equatable {
    public private(set) var cards: [ContinueCardState] = []
    public var limit = 8
    private var dismissed: Set<String> = []

    public init(limit: Int = 8) { self.limit = limit }

    /// Returns true when the card is new (not a refresh of one already shown or dismissed).
    @discardableResult
    public mutating func add(_ card: ContinuePayload, thumb: Data, now: Int64) -> Bool {
        let key = card.id + "|" + (card.url ?? card.path ?? card.title)
        if dismissed.contains(key) { return false }
        let existed = cards.first { $0.id == card.id }
        cards.removeAll { $0.id == card.id }
        cards.insert(ContinueCardState(card: card, thumb: thumb, received: now), at: 0)
        if cards.count > limit { cards.removeLast(cards.count - limit) }
        return existed.map { ($0.card.url ?? $0.card.path ?? $0.card.title) != (card.url ?? card.path ?? card.title) } ?? true
    }

    public mutating func dismiss(_ id: String) {
        if let c = cards.first(where: { $0.id == id }) { dismissed.insert(c.id + "|" + (c.card.url ?? c.card.path ?? c.card.title)) }
        cards.removeAll { $0.id == id }
    }

    /// Drops expired cards; returns true when something changed.
    @discardableResult
    public mutating func expire(now: Int64) -> Bool {
        let before = cards.count
        cards.removeAll { $0.expired(now) }
        return cards.count != before
    }
}

/// Where a song card opens: Spotify (app URL) or YouTube Music (web), searching for the track.
public enum SongLinks {
    public static func spotify(title: String, artist: String?) -> URL? {
        let q = [title, artist ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: ":/?#"))
        guard let enc = q.addingPercentEncoding(withAllowedCharacters: allowed) else {
            return nil
        }
        return URL(string: "spotify:search:" + enc)
    }

    public static func youTubeMusic(title: String, artist: String?) -> URL? {
        var c = URLComponents(string: "https://music.youtube.com/search")
        c?.queryItems = [URLQueryItem(name: "q", value: [title, artist ?? ""].filter { !$0.isEmpty }.joined(separator: " "))]
        return c?.url
    }

    /// The phone app the song played in decides the default target.
    public static func prefersSpotify(app: String?) -> Bool {
        guard let a = app?.lowercased() else { return true }
        if a.contains("youtube") || a.contains("yt music") { return false }
        return true
    }
}

/// A link the phone sent: only web and a few safe schemes may be opened on the Mac.
public enum SafeLinks {
    public static let schemes: Set<String> = ["http", "https", "mailto", "spotify", "maps", "tel"]

    public static func url(_ s: String?) -> URL? {
        guard let s = s?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty, let u = URL(string: s),
              let scheme = u.scheme?.lowercased(), schemes.contains(scheme) else { return nil }
        return u
    }

    /// True when `text` is a single web link (used for "copied link" cards and Open on phone).
    public static func isWebLink(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.contains(" "), !t.contains("\n"), let u = URL(string: t), let s = u.scheme?.lowercased() else { return false }
        return (s == "http" || s == "https") && u.host != nil
    }
}

// MARK: - Battery alerts

/// "Fully charged" once per charge: fires when charging reaches `full`, re-arms when unplugged
/// or after dropping `rearmBelow`.
public struct ChargeCompleteMonitor: Equatable {
    public var full: Int
    public var rearmBelow: Int
    public private(set) var armed = false

    public init(full: Int = 100, rearmBelow: Int = 95) {
        self.full = full
        self.rearmBelow = rearmBelow
    }

    public mutating func update(level: Int, charging: Bool) -> Bool {
        if !charging {
            armed = true
            return false
        }
        if level < rearmBelow { armed = true }
        if armed && level >= full {
            armed = false
            return true
        }
        return false
    }
}

// MARK: - Calls and Mac media

/// Pauses Mac media and ducks the volume while the phone rings or a call is up, then restores
/// exactly what it changed. Pure: the app performs the returned actions.
public struct CallMediaPolicy: Equatable {
    public enum Action: Equatable {
        /// Pause what is playing (if anything) and lower the volume to `duckTo` (0...1).
        case quiet(duckTo: Double)
        /// Resume if we paused, and put the volume back if we changed it.
        case restore
    }

    public var duckTo: Double
    public private(set) var active = false

    public init(duckTo: Double = 0.2) { self.duckTo = duckTo }

    /// `busy` = the phone is ringing or a call is off-hook.
    public mutating func phone(busy: Bool) -> Action? {
        if busy == active { return nil }
        active = busy
        return busy ? .quiet(duckTo: duckTo) : .restore
    }

    /// Volume to restore: only if the user didn't change it while ducked.
    public static func restoreVolume(original: Double, ducked: Double, now: Double) -> Double? {
        abs(now - ducked) < 0.02 ? original : nil
    }
}

// MARK: - Focus / DND sync

public final class DndSync {
    private let echoWindowMs: Int64
    private var last: Bool?
    private var appliedRemote: (on: Bool, at: Int64)?

    public init(echoWindowMs: Int64 = 4000) { self.echoWindowMs = echoWindowMs }

    /// Local state is `on`; true when it should be sent.
    public func local(_ on: Bool, now: Int64) -> Bool {
        let echo = appliedRemote.map { $0.on == on && now - $0.at < echoWindowMs } ?? false
        let changed = last != on
        last = on
        return echo ? false : changed
    }

    /// The peer reported `on`; true when we should apply it.
    public func remote(_ on: Bool, now: Int64) -> Bool {
        if last == on { return false }
        appliedRemote = (on, now)
        return true
    }

    public func reset() {
        last = nil
        appliedRemote = nil
    }
}

// MARK: - Proximity unlock

/// Decides when to wake and unlock the Mac because the phone came near. Only while the screen
/// is locked, the link is authenticated (a live Noise session with the paired phone), the
/// phone is near, at most once per lock and never more than `maxAttempts` per `windowMs`.
public struct UnlockPolicy: Equatable {
    public var maxAttempts = 2
    public var windowMs: Int64 = 5 * 60_000
    private var attempts: [Int64] = []
    private var unlockedThisLock = false
    private var wasLocked = false

    public init() {}

    public mutating func evaluate(enabled: Bool, locked: Bool, authenticated: Bool, near: Bool, now: Int64) -> Bool {
        if locked && !wasLocked { unlockedThisLock = false }
        wasLocked = locked
        guard enabled, locked, authenticated, near, !unlockedThisLock else { return false }
        attempts.removeAll { now - $0 > windowMs }
        guard attempts.count < maxAttempts else { return false }
        attempts.append(now)
        unlockedThisLock = true
        return true
    }
}

// MARK: - Global shortcuts

/// A key combination for a global shortcut (Carbon key code + modifier flags).
public struct HotkeySpec: Codable, Equatable, Hashable {
    public static let command: UInt32 = 1 << 8
    public static let shift: UInt32 = 1 << 9
    public static let option: UInt32 = 1 << 11
    public static let control: UInt32 = 1 << 12

    public var keyCode: UInt32
    /// Carbon modifier mask (cmdKey, optionKey, …).
    public var modifiers: UInt32
    public var enabled: Bool

    public init(keyCode: UInt32, modifiers: UInt32, enabled: Bool = true) {
        self.keyCode = keyCode
        self.modifiers = modifiers
        self.enabled = enabled
    }

    public static let sendClipboard = HotkeySpec(keyCode: 9, modifiers: option | command)   // ⌥⌘V
    public static let sendFile = HotkeySpec(keyCode: 3, modifiers: option | command)        // ⌥⌘F
    public static let openTether = HotkeySpec(keyCode: 17, modifiers: option | command)     // ⌥⌘T

    /// Needs at least one of ⌘ ⌥ ⌃ so plain typing is never stolen.
    public var isValid: Bool { modifiers & (Self.command | Self.option | Self.control) != 0 }

    public var display: String {
        var s = ""
        if modifiers & Self.control != 0 { s += "⌃" }
        if modifiers & Self.option != 0 { s += "⌥" }
        if modifiers & Self.shift != 0 { s += "⇧" }
        if modifiers & Self.command != 0 { s += "⌘" }
        return s + Self.keyName(keyCode)
    }

    static let names: [UInt32: String] = [
        0: "A", 1: "S", 2: "D", 3: "F", 4: "H", 5: "G", 6: "Z", 7: "X", 8: "C", 9: "V", 11: "B", 12: "Q", 13: "W", 14: "E",
        15: "R", 16: "Y", 17: "T", 18: "1", 19: "2", 20: "3", 21: "4", 22: "6", 23: "5", 25: "9", 26: "7", 28: "8", 29: "0",
        31: "O", 32: "U", 34: "I", 35: "P", 37: "L", 38: "J", 40: "K", 45: "N", 46: "M", 36: "↩", 48: "⇥", 49: "Space",
        51: "⌫", 53: "⎋", 122: "F1", 120: "F2", 99: "F3", 118: "F4", 96: "F5", 97: "F6", 98: "F7", 100: "F8",
        123: "←", 124: "→", 125: "↓", 126: "↑", 43: ",", 47: ".", 44: "/", 41: ";", 39: "'", 33: "[", 30: "]", 42: "\\",
        27: "-", 24: "=", 50: "`",
    ]

    public static func keyName(_ code: UInt32) -> String { names[code] ?? "Key \(code)" }
}

/// The three configurable shortcuts.
public struct HotkeySettings: Codable, Equatable {
    public var sendClipboard = HotkeySpec.sendClipboard
    public var sendFile = HotkeySpec.sendFile
    public var openTether = HotkeySpec.openTether
    public init() {}

    /// Two actions on the same keys can't both register.
    public var conflicts: Bool {
        let on = [sendClipboard, sendFile, openTether].filter(\.enabled).map { "\($0.keyCode)/\($0.modifiers)" }
        return Set(on).count != on.count
    }
}

// MARK: - Universal search

public struct SearchHit: Equatable, Identifiable {
    public enum Kind: String, CaseIterable { case message, contact, call, notification }
    public var kind: Kind
    public var id: String
    public var title: String
    public var subtitle: String
    public var ts: Int64
    public var score: Int

    public init(kind: Kind, id: String, title: String, subtitle: String, ts: Int64 = 0, score: Int = 0) {
        self.kind = kind
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.ts = ts
        self.score = score
    }
}

/// Case- and diacritic-insensitive matching: every word of the query must appear; prefix and
/// title matches rank higher, then newer items.
public enum UniversalSearch {
    public static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }

    /// Digits only, for matching phone numbers typed with or without spaces/+91.
    static func digits(_ s: String) -> String { s.filter(\.isNumber) }

    public static func score(query: String, title: String, body: String) -> Int? {
        let q = fold(query).split(whereSeparator: { $0 == " " }).map(String.init).filter { !$0.isEmpty }
        guard !q.isEmpty else { return nil }
        let t = fold(title)
        let b = fold(body)
        var total = 0
        for word in q {
            let wd = digits(word)
            if t.hasPrefix(word) || t.contains(" " + word) {
                total += 30
            } else if t.contains(word) {
                total += 20
            } else if b.contains(word) {
                total += 8
            } else if wd.count >= 3, digits(t + " " + b).contains(wd) {
                total += 15
            } else {
                return nil
            }
        }
        return total
    }

    public static func rank(_ hits: [SearchHit], limitPerKind: Int = 20) -> [SearchHit] {
        var out: [SearchHit] = []
        for k in SearchHit.Kind.allCases {
            out += hits.filter { $0.kind == k }
                .sorted { $0.score != $1.score ? $0.score > $1.score : $0.ts > $1.ts }
                .prefix(limitPerKind)
        }
        return out
    }
}

// MARK: - Typing into the phone

/// Maps Mac key presses to TYPE_TEXT operations and batches fast typing into one message.
public struct TypeBatcher {
    public enum Op: Equatable {
        case text(String)
        case key(String)
    }

    private var pending = ""

    public init() {}

    /// A key press: `keyCode` (Carbon) and the characters it produced. Returns ops to send now.
    public mutating func press(keyCode: UInt16, characters: String?, command: Bool) -> [Op] {
        if command { return flush() } // ⌘-shortcuts stay on the Mac
        switch keyCode {
        case 51: return flush() + [.key(TypeKeys.backspace)]
        case 36, 76: return flush() + [.key(TypeKeys.enter)]
        case 48: return flush() + [.key(TypeKeys.tab)]
        case 53, 123, 124, 125, 126, 115, 119, 116, 121, 117: return flush() // esc/arrows/home/end/page/fwd-delete: not mapped
        default:
            guard let c = characters, !c.isEmpty, c.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else {
                return []
            }
            pending += c
            return pending.count >= 64 ? flush() : []
        }
    }

    /// Pasted text goes as one insert.
    public mutating func paste(_ text: String) -> [Op] {
        pending += text
        return flush()
    }

    public mutating func flush() -> [Op] {
        guard !pending.isEmpty else { return [] }
        defer { pending = "" }
        return [.text(pending)]
    }

    public var hasPending: Bool { !pending.isEmpty }
}

// MARK: - Dock / menu bar presence

public enum AppPresence: String, Codable, CaseIterable {
    case both, dock, menuBar

    public var showsDock: Bool { self != .menuBar }
    public var showsMenuBar: Bool { self != .dock }
    public var title: String {
        switch self {
        case .both: return "Dock and menu bar"
        case .dock: return "Dock only"
        case .menuBar: return "Menu bar only"
        }
    }
}

/// Dark / Light / System appearance for the Mac app.
public enum ColorMode: String, Codable, CaseIterable {
    case system, dark, light
    public var title: String {
        switch self {
        case .system: return "System"
        case .dark: return "Dark"
        case .light: return "Light"
        }
    }
}

// MARK: - Widgets

/// What the widgets show, written by the app into the shared app-group defaults.
public struct WidgetSnapshot: Codable, Equatable {
    public var phoneName: String?
    public var connected: Bool
    public var battery: Int?
    public var charging: Bool
    public var songTitle: String?
    public var songArtist: String?
    public var playing: Bool
    public var updated: Int64

    public static let suite = "group.in.benfranklin.tether"
    public static let key = "widgetSnapshot"

    public init(phoneName: String? = nil, connected: Bool = false, battery: Int? = nil, charging: Bool = false,
                songTitle: String? = nil, songArtist: String? = nil, playing: Bool = false, updated: Int64 = 0) {
        self.phoneName = phoneName
        self.connected = connected
        self.battery = battery
        self.charging = charging
        self.songTitle = songTitle
        self.songArtist = songArtist
        self.playing = playing
        self.updated = updated
    }

    public func encoded() -> Data? { try? JSONEncoder().encode(self) }
    public static func decode(_ d: Data?) -> WidgetSnapshot? { d.flatMap { try? JSONDecoder().decode(WidgetSnapshot.self, from: $0) } }
}
