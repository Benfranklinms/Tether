//
// Shelf.swift — 0.5.2 Shelf: type detection, the merge-by-id-and-timestamp state and the
// at-rest encryption. Mirrors android/core/.../features/Shelf.kt rule for rule; both run the
// shared vectors in protocol/vectors/shelf-detect.json.
//

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public struct ShelfGuess: Equatable {
    public var kind: String
    public var title: String
    public var body: String
    public var secret: String?
}

private extension String {
    func matches(_ re: NSRegularExpression) -> Bool {
        re.firstMatch(in: self, range: NSRange(startIndex..., in: self)) != nil
    }

    func fullMatch(_ re: NSRegularExpression) -> Bool {
        guard let m = re.firstMatch(in: self, range: NSRange(startIndex..., in: self)) else { return false }
        return m.range.location == 0 && m.range.length == (self as NSString).length
    }

    func take(_ n: Int) -> String { String(prefix(n)) }
}

private func re(_ p: String, _ o: NSRegularExpression.Options = []) -> NSRegularExpression {
    // The patterns are constants; a typo is a programming error caught by the tests.
    // swiftlint:disable:next force_try
    try! NSRegularExpression(pattern: p, options: o)
}

public enum ShelfDetect {
    public static let titleMax = 80
    private static let keyMax = 24
    private static let addressMax = 300

    private static let url = re(#"^(https?://\S+|www\.\S+\.\S+)$"#, .caseInsensitive)
    private static let bareDomain = re(#"^[a-z0-9-]+(\.[a-z0-9-]+)*\.(com|org|net|io|in|gle|dev|app|co|edu|gov|me|ly|gl)(/\S*)?$"#,
                                       .caseInsensitive)
    private static let wifiQr = re(#"^WIFI:(.*);;?$"#, [.caseInsensitive, .dotMatchesLineSeparators])
    private static let pin = re(#"\b\d{6}\b|\b\d{5}(-\d{4})?\b"#)
    private static let street = re(#"\b(road|rd|street|st|avenue|ave|lane|ln|nagar|marg|colony|sector|block|floor|building|"#
        + #"college|po|p\.o|district|dist|near|opp|cross|main|layout|drive|dr|blvd|kochi|kerala)\b"#, .caseInsensitive)
    private static let assignment = re(#"^[A-Za-z_][\w.]*\s*[:+-]?=\s*\S"#)
    private static let commands = [
        "adb ", "git ", "npm ", "npx ", "yarn ", "pnpm ", "ssh ", "scp ", "curl ", "wget ", "sudo ", "cd ", "ls ", "pip ", "pip3 ",
        "python ", "python3 ", "docker ", "kubectl ", "brew ", "apt ", "gradle ", "./", "export ", "echo ", "cat ", "chmod ",
        "mkdir ", "rm ", "mv ", "cp ", "java ", "node ", "cargo ", "swift ", "xcrun ", "defaults ", "$ ",
    ]

    public static func classify(_ raw: String) -> ShelfGuess {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return ShelfGuess(kind: ShelfKinds.text, title: "", body: "") }
        let lines = text.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if let w = wifi(text, lines) { return w }
        if let l = link(lines) { return l }
        if isSnippet(text, lines) {
            if lines.count > 1 && !looksLikeCode(lines[0]) {
                return ShelfGuess(kind: ShelfKinds.snippet, title: lines[0].take(titleMax), body: lines.dropFirst().joined(separator: "\n"))
            }
            let words = lines[0].split(separator: " ", omittingEmptySubsequences: false).prefix(3).joined(separator: " ")
            return ShelfGuess(kind: ShelfKinds.snippet, title: String(words).take(titleMax), body: text)
        }
        if let a = address(text, lines) { return a }
        return plain(lines)
    }

    private static func wifi(_ text: String, _ lines: [String]) -> ShelfGuess? {
        let ns = text as NSString
        if let m = wifiQr.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) {
            let fields = splitQr(ns.substring(with: m.range(at: 1)))
            guard let ssid = fields["S"] else { return nil }
            return ShelfGuess(kind: ShelfKinds.wifi, title: ssid, body: fields["P"] ?? "", secret: fields["P"])
        }
        var ssid: String?
        var pass: String?
        for l in lines {
            guard let i = l.firstIndex(of: ":") else { continue }
            let k = l[..<i].trimmingCharacters(in: .whitespaces).lowercased()
            let v = l[l.index(after: i)...].trimmingCharacters(in: .whitespaces)
            if v.isEmpty { continue }
            if ["ssid", "wifi", "wi-fi", "network", "wifi name", "wi-fi name", "network name"].contains(k) { ssid = v }
            if ["password", "pass", "pwd", "key", "wifi password", "wi-fi password", "psk"].contains(k) { pass = v }
        }
        if let ssid, let pass { return ShelfGuess(kind: ShelfKinds.wifi, title: ssid, body: pass, secret: pass) }
        return nil
    }

    private static func splitQr(_ s: String) -> [String: String] {
        var parts: [String] = []
        var cur = ""
        var esc = false
        for c in s {
            if esc {
                cur.append(c)
                esc = false
            } else if c == "\\" {
                esc = true
            } else if c == ";" {
                parts.append(cur)
                cur = ""
            } else {
                cur.append(c)
            }
        }
        if !cur.isEmpty { parts.append(cur) }
        var out: [String: String] = [:]
        for p in parts {
            if let i = p.firstIndex(of: ":"), i != p.startIndex {
                out[p[..<i].uppercased()] = String(p[p.index(after: i)...])
            }
        }
        return out
    }

    private static func link(_ lines: [String]) -> ShelfGuess? {
        guard let last = lines.last, lines.count <= 2, last.fullMatch(url) || last.fullMatch(bareDomain) else { return nil }
        let title = lines.count == 2 ? lines[0] : hostOf(last)
        return ShelfGuess(kind: ShelfKinds.link, title: title.take(titleMax), body: last)
    }

    public static func hostOf(_ u: String) -> String {
        var s = u
        for p in ["https://", "http://", "www."] where s.hasPrefix(p) { s = String(s.dropFirst(p.count)) }
        let host = String(s.prefix { $0 != "/" }.prefix { $0 != "?" })
        return host.isEmpty ? u : host
    }

    static func looksLikeCode(_ l: String) -> Bool {
        let lower = l.lowercased()
        return commands.contains { lower.hasPrefix($0) } || l.contains("{") || l.contains("=>") || l.hasSuffix(";")
            || l.hasPrefix("#!") || l.contains("();") || l.matches(assignment)
    }

    private static func isSnippet(_ text: String, _ lines: [String]) -> Bool {
        if text.hasPrefix("```") { return true }
        let code = lines.filter { looksLikeCode($0) }.count
        return code >= 1 && code * 2 >= lines.count
    }

    private static func address(_ text: String, _ lines: [String]) -> ShelfGuess? {
        let hasPin = text.matches(pin)
        let commas = text.filter { $0 == "," }.count
        let streety = text.matches(street)
        guard (hasPin && (commas >= 1 || lines.count >= 2)) || (streety && commas >= 2), text.count <= addressMax else { return nil }
        func trimComma(_ s: String) -> String { s.hasSuffix(",") ? String(s.dropLast()) : s }
        if lines.count >= 2 {
            return ShelfGuess(kind: ShelfKinds.address, title: trimComma(lines[0]).take(titleMax),
                              body: lines.dropFirst().map(trimComma).joined(separator: ", "))
        }
        let first = String(text.prefix { $0 != "," }).trimmingCharacters(in: .whitespaces)
        let rest = text.firstIndex(of: ",").map { String(text[text.index(after: $0)...]).trimmingCharacters(in: .whitespaces) } ?? ""
        return ShelfGuess(kind: ShelfKinds.address, title: first.take(titleMax), body: rest)
    }

    private static func plain(_ lines: [String]) -> ShelfGuess {
        if lines.count == 1 {
            let l = lines[0]
            if let i = l.firstIndex(of: ":") {
                let n = l.distance(from: l.startIndex, to: i)
                if n >= 1 && n <= keyMax && n < l.count - 1 {
                    return ShelfGuess(kind: ShelfKinds.text, title: l[..<i].trimmingCharacters(in: .whitespaces),
                                      body: l[l.index(after: i)...].trimmingCharacters(in: .whitespaces))
                }
            }
            return ShelfGuess(kind: ShelfKinds.text, title: l.take(titleMax), body: l.count > titleMax ? l : "")
        }
        return ShelfGuess(kind: ShelfKinds.text, title: lines[0].take(titleMax), body: lines.dropFirst().joined(separator: "\n"))
    }

    /// The one primary action on a card.
    public static func action(_ kind: String) -> String {
        switch kind {
        case ShelfKinds.link, ShelfKinds.file: return "Open"
        case ShelfKinds.address: return "Map"
        default: return "Copy"
        }
    }

    public static func copyText(_ item: ShelfItem) -> String {
        switch item.kind {
        case ShelfKinds.wifi: return item.secret ?? item.body
        case ShelfKinds.text: return item.body.isEmpty ? item.title : item.body
        default: return item.body.isEmpty ? item.title : item.body
        }
    }

    /// Apple Maps search URL for an address card.
    public static func mapsURL(_ item: ShelfItem) -> URL? {
        let q = [item.title, item.body].filter { !$0.isEmpty }.joined(separator: ", ")
        var c = URLComponents(string: "http://maps.apple.com/")
        c?.queryItems = [URLQueryItem(name: "q", value: q)]
        return c?.url
    }

    /// The URL a link card opens (bare domains get https://).
    public static func linkURL(_ item: ShelfItem) -> URL? {
        let s = item.body.trimmingCharacters(in: .whitespaces)
        let lower = s.lowercased()
        return URL(string: lower.hasPrefix("http://") || lower.hasPrefix("https://") ? s : "https://" + s)
    }
}

/// The Shelf on one device; same merge rules as ShelfState in Shelf.kt (items, folders and their
/// tombstones; an item whose folder is gone shows as loose).
public struct ShelfState: Codable, Equatable {
    /// 0.5.4: was 50.
    public static let limit = 500
    public static let folderLimit = 100
    public static let tombTTL: Int64 = 30 * 24 * 3600 * 1000
    public static let bodyMax = 4000

    public var items: [ShelfItem]
    public var tombs: [ShelfTomb]
    public var folders: [ShelfFolder]
    public var folderTombs: [ShelfTomb]

    public init(items: [ShelfItem] = [], tombs: [ShelfTomb] = [], folders: [ShelfFolder] = [], folderTombs: [ShelfTomb] = []) {
        self.items = items
        self.tombs = tombs
        self.folders = folders
        self.folderTombs = folderTombs
    }

    enum CodingKeys: String, CodingKey { case items, tombs, folders, folderTombs }

    /// Tolerant: a 0.5.3 file has no folders; a damaged entry never loses the whole Shelf.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = (try? c.decodeIfPresent([Lenient<ShelfItem>].self, forKey: .items))?.compactMap(\.value) ?? []
        tombs = (try? c.decodeIfPresent([Lenient<ShelfTomb>].self, forKey: .tombs))?.compactMap(\.value) ?? []
        folders = (try? c.decodeIfPresent([Lenient<ShelfFolder>].self, forKey: .folders))?.compactMap(\.value) ?? []
        folderTombs = (try? c.decodeIfPresent([Lenient<ShelfTomb>].self, forKey: .folderTombs))?.compactMap(\.value) ?? []
    }

    public var ordered: [ShelfItem] {
        items.sorted { $0.order != $1.order ? $0.order < $1.order : $0.id < $1.id }
    }

    public var orderedFolders: [ShelfFolder] {
        folders.sorted { $0.order != $1.order ? $0.order < $1.order : $0.id < $1.id }
    }

    /// The live folder `item` is in, or nil when loose (or its folder is gone).
    public func folderOf(_ item: ShelfItem) -> String? {
        guard let f = item.folderId, folders.contains(where: { $0.id == f }) else { return nil }
        return f
    }

    public func items(in folderId: String?) -> [ShelfItem] { ordered.filter { folderOf($0) == folderId } }

    public func counts() -> [String: Int] {
        var out: [String: Int] = [:]
        for i in items { if let f = folderOf(i) { out[f, default: 0] += 1 } }
        return out
    }

    private static func bump(_ item: ShelfItem, _ now: Int64) -> Int64 { Swift.max(now, item.updated + 1) }

    private func with(items: [ShelfItem]? = nil, tombs: [ShelfTomb]? = nil, folders: [ShelfFolder]? = nil,
                      folderTombs: [ShelfTomb]? = nil) -> ShelfState {
        ShelfState(items: items ?? self.items, tombs: tombs ?? self.tombs, folders: folders ?? self.folders,
                   folderTombs: folderTombs ?? self.folderTombs)
    }

    public func add(_ item: ShelfItem) -> ShelfState {
        let top = items.map(\.order).min() ?? 0
        var placed = item
        placed.order = top - 1
        return with(items: items.filter { $0.id != item.id } + [placed], tombs: tombs.filter { $0.id != item.id }).limited()
    }

    public func remove(_ id: String, now: Int64) -> ShelfState {
        with(items: items.filter { $0.id != id }, tombs: tombs.filter { $0.id != id } + [ShelfTomb(id: id, ts: now)])
    }

    public func restore(_ item: ShelfItem, now: Int64) -> ShelfState {
        var back = item
        back.updated = now
        return with(items: items.filter { $0.id != item.id } + [back], tombs: tombs.filter { $0.id != item.id }).limited()
    }

    public func move(_ id: String, to: Int, now: Int64) -> ShelfState { move(id, to: to, within: ordered, now: now) }

    /// Moves `id` to `to` within `list` (a folder's or the loose items, as shown).
    public func move(_ id: String, to: Int, within list: [ShelfItem], now: Int64) -> ShelfState {
        var list = list
        guard let from = list.firstIndex(where: { $0.id == id }) else { return self }
        let item = list.remove(at: from)
        let i = Swift.max(0, Swift.min(to, list.count))
        let before = i > 0 ? list[i - 1].order : nil
        let after = i < list.count ? list[i].order : nil
        let order: Double
        switch (before, after) {
        case (nil, nil): order = item.order
        case (nil, let a?): order = a - 1
        case (let b?, nil): order = b + 1
        case (let b?, let a?): order = (b + a) / 2
        }
        return with(items: items.map { x in
            guard x.id == item.id else { return x }
            var y = x
            y.order = order
            y.updated = Self.bump(x, now)
            return y
        })
    }

    public func update(_ item: ShelfItem) -> ShelfState {
        with(items: items.map { $0.id == item.id ? item : $0 })
    }

    // MARK: folders (0.5.4)

    public func createFolder(id: String, name: String, now: Int64) -> ShelfState? {
        guard let n = ShelfFolders.cleanName(name), folders.count < Self.folderLimit else { return nil }
        let order = (folders.map(\.order).max() ?? 0) + 1
        return with(folders: folders.filter { $0.id != id } + [ShelfFolder(id: id, name: n, order: order, updated: now)],
                    folderTombs: folderTombs.filter { $0.id != id })
    }

    public func renameFolder(_ id: String, name: String, now: Int64) -> ShelfState? {
        guard let n = ShelfFolders.cleanName(name), let f = folders.first(where: { $0.id == id }), f.name != n else { return nil }
        return with(folders: folders.map { x in
            guard x.id == id else { return x }
            var y = x
            y.name = n
            y.updated = Swift.max(now, x.updated + 1)
            return y
        })
    }

    /// Its items become loose (fresh timestamps so the move wins everywhere); Shelf items are never removed.
    public func deleteFolder(_ id: String, now: Int64) -> ShelfState {
        guard folders.contains(where: { $0.id == id }) else { return self }
        return with(items: items.map { x in
            guard x.folderId == id else { return x }
            var y = x
            y.folderId = nil
            y.updated = Self.bump(x, now)
            return y
        }, folders: folders.filter { $0.id != id }, folderTombs: folderTombs.filter { $0.id != id } + [ShelfTomb(id: id, ts: now)])
    }

    /// Nil when nothing changes (same folder) or the folder is gone.
    public func moveTo(_ itemId: String, folder folderId: String?, now: Int64) -> ShelfState? {
        guard let item = items.first(where: { $0.id == itemId }) else { return nil }
        if let f = folderId, !folders.contains(where: { $0.id == f }) { return nil }
        guard folderOf(item) != folderId else { return nil }
        let top = items(in: folderId).map(\.order).min().map { $0 - 1 } ?? item.order
        return with(items: items.map { x in
            guard x.id == itemId else { return x }
            var y = x
            y.folderId = folderId
            y.order = top
            y.updated = Self.bump(x, now)
            return y
        })
    }

    public func syncOf(items itemIds: Set<String> = [], folders folderIds: Set<String> = []) -> ShelfSyncPayload {
        ShelfSyncPayload(items: items.filter { itemIds.contains($0.id) }, folders: folders.filter { folderIds.contains($0.id) },
                         removedFolders: folderTombs.filter { folderIds.contains($0.id) })
    }

    public func merge(_ sync: ShelfSyncPayload) -> ShelfState {
        var byId = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var tomb = Dictionary(tombs.map { ($0.id, $0.ts) }, uniquingKeysWith: { Swift.max($0, $1) })
        for t in sync.removed { tomb[t.id] = Swift.max(tomb[t.id] ?? 0, t.ts) }
        for r in sync.items {
            if let dead = tomb[r.id], dead >= r.updated { continue }
            if let mine = byId[r.id] {
                if r.updated > mine.updated || (r.updated == mine.updated && r.from > mine.from) { byId[r.id] = r }
            } else {
                byId[r.id] = r
            }
        }
        for (id, ts) in tomb {
            if let mine = byId[id], mine.updated <= ts { byId[id] = nil }
        }
        let remaining = tomb.filter { byId[$0.key] == nil }.map { ShelfTomb(id: $0.key, ts: $0.value) }.sorted { $0.id < $1.id }
        var fById = Dictionary(folders.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var fTomb = Dictionary(folderTombs.map { ($0.id, $0.ts) }, uniquingKeysWith: { Swift.max($0, $1) })
        for t in sync.removedFolders { fTomb[t.id] = Swift.max(fTomb[t.id] ?? 0, t.ts) }
        for r in sync.folders {
            if let dead = fTomb[r.id], dead >= r.updated { continue }
            if let mine = fById[r.id] {
                if r.updated > mine.updated || (r.updated == mine.updated && r.name > mine.name) { fById[r.id] = r }
            } else {
                fById[r.id] = r
            }
        }
        for (id, ts) in fTomb {
            if let mine = fById[id], mine.updated <= ts { fById[id] = nil }
        }
        let fRemaining = fTomb.filter { fById[$0.key] == nil }.map { ShelfTomb(id: $0.key, ts: $0.value) }.sorted { $0.id < $1.id }
        return ShelfState(items: Array(byId.values), tombs: remaining, folders: Array(fById.values), folderTombs: fRemaining).limited()
    }

    public func snapshot() -> ShelfSyncPayload {
        ShelfSyncPayload(items: items, removed: tombs, full: true, folders: folders, removedFolders: folderTombs)
    }

    public func pruned(now: Int64, maxAge: Int64 = ShelfState.tombTTL) -> ShelfState {
        with(tombs: tombs.filter { now - $0.ts < maxAge }, folderTombs: folderTombs.filter { now - $0.ts < maxAge })
    }

    private func limited() -> ShelfState {
        guard items.count > Self.limit else { return self }
        let keep = Set(items.sorted { $0.updated != $1.updated ? $0.updated > $1.updated : $0.id < $1.id }.prefix(Self.limit).map(\.id))
        return with(items: items.filter { keep.contains($0.id) })
    }

    public static func item(from text: String, id: String, from: String, now: Int64, folderId: String? = nil) -> ShelfItem {
        let g = ShelfDetect.classify(text)
        return ShelfItem(id: id, kind: g.kind, title: g.title.isEmpty ? "Note" : g.title, body: String(g.body.prefix(bodyMax)),
                         secret: g.secret, from: from, created: now, updated: now, order: 0, folderId: folderId)
    }

    public static func fileItem(_ file: ShelfFile, id: String, from: String, now: Int64, folderId: String? = nil) -> ShelfItem {
        ShelfItem(id: id, kind: ShelfKinds.file, title: file.name, body: file.mime, from: from, created: now, updated: now, order: 0,
                  file: file, folderId: folderId)
    }
}

/// Decodes one element of an array, or nil if that element is damaged (the rest still load).
struct Lenient<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

/// Folder names and what goes on the wire (0.5.4); same rules as ShelfFolders in Shelf.kt.
public enum ShelfFolders {
    public static let nameMax = 40
    public static let pageBudget = 48 * 1024

    public static func cleanName(_ raw: String) -> String? {
        let one = raw.split(whereSeparator: { $0.isWhitespace || $0.isNewline }).joined(separator: " ")
        let n = String(one.prefix(nameMax)).trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? nil : n
    }

    /// A peer without "shelffolders" gets no folder fields at all.
    public static func forPeer(_ sync: ShelfSyncPayload, folders: Bool) -> ShelfSyncPayload {
        guard !folders else { return sync }
        var s = sync
        s.items = s.items.map { var i = $0; i.folderId = nil; return i }
        s.folders = []
        s.removedFolders = []
        return s
    }

    /// Splits a sync into pages of about `budget` bytes of JSON (Bluetooth records are ≤128 KiB).
    public static func pages(_ sync: ShelfSyncPayload, budget: Int = pageBudget, size: (ShelfItem) -> Int) -> [ShelfSyncPayload] {
        var out: [ShelfSyncPayload] = []
        var cur: [ShelfItem] = []
        var used = 0
        for item in sync.items {
            let n = size(item) + 1
            if !cur.isEmpty && used + n > budget {
                out.append(ShelfSyncPayload(items: cur, full: sync.full))
                cur = []
                used = 0
            }
            cur.append(item)
            used += n
        }
        if !cur.isEmpty || out.isEmpty { out.append(ShelfSyncPayload(items: cur, full: sync.full)) }
        out[0].removed = sync.removed
        out[0].folders = sync.folders
        out[0].removedFolders = sync.removedFolders
        return out
    }

    /// JSON size of one item, for `pages`.
    public static func jsonSize(_ item: ShelfItem) -> Int { (try? JSONEncoder().encode(item).count) ?? ShelfState.bodyMax * 2 }
}

/// The Shelf on disk: JSON sealed with AES-GCM under a 256-bit key that lives in the Keychain.
public enum ShelfVault {
    public static func newKey() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }

    public static func seal(_ state: ShelfState, key: Data) throws -> Data {
        let json = try JSONEncoder().encode(state)
        guard let combined = try AES.GCM.seal(json, using: SymmetricKey(data: key)).combined else {
            throw ProtocolError("seal failed")
        }
        return combined
    }

    public static func open(_ data: Data, key: Data) throws -> ShelfState {
        let box = try AES.GCM.SealedBox(combined: data)
        let json = try AES.GCM.open(box, using: SymmetricKey(data: key))
        return try JSONDecoder().decode(ShelfState.self, from: json)
    }
}

/// "From phone · 2 min" under each card.
public enum ShelfAge {
    public static func label(from: String, updatedMs: Int64, nowMs: Int64) -> String {
        let who = from == "phone" ? "From phone" : "From Mac"
        let s = Swift.max(0, (nowMs - updatedMs) / 1000)
        let age: String
        switch s {
        case ..<60: age = "now"
        case ..<3600: age = "\(s / 60) min"
        case ..<86_400: age = "\(s / 3600) h"
        default: age = "\(s / 86_400) d"
        }
        return "\(who) · \(age)"
    }
}
