//
// ShelfOffline.swift — offline copies of Shelf files (0.5.4), same rules as ShelfOffline.kt.
// Local to each device; nothing here travels. Files ≤100 MB are copied automatically while they
// fit in the cap; over it the least recently used copies go first. Never dropped: pinned items
// ("Keep offline", or a pinned folder) and files this device added itself.
//

import Foundation

public enum ShelfOffline {
    public static let mb: Int64 = 1024 * 1024
    public static let gb: Int64 = 1024 * mb
    /// Settings › Shelf › Offline files; 0 = Off.
    public static let capOptions: [Int64] = [500 * mb, gb, 2 * gb, 5 * gb, 0]
    public static let phoneDefault: Int64 = gb
    public static let macDefault: Int64 = 2 * gb
    public static let away = "Available when connected"

    public static func capLabel(_ cap: Int64) -> String {
        if cap <= 0 { return "Off" }
        return cap % gb == 0 ? "\(cap / gb) GB" : "\(cap / mb) MB"
    }

    public static func usageLabel(used: Int64, cap: Int64) -> String {
        let u: String
        if used >= gb {
            let s = String(format: "%.1f GB", Double(used) / Double(gb))
            u = s.hasSuffix(".0 GB") ? s.replacingOccurrences(of: ".0 GB", with: " GB") : s
        } else if used >= mb {
            u = "\((used + mb / 2) / mb) MB"
        } else if used > 0 {
            u = "\(Swift.max(1, (used + 512) / 1024)) KB"
        } else {
            u = "0 MB"
        }
        return cap <= 0 ? "\(u) · Off" : "\(u) of \(capLabel(cap))"
    }

    public struct Entry: Equatable {
        public var id: String
        public var size: Int64
        public var local: Bool
        public var lastUsed: Int64
        public var pinned: Bool
        public var own: Bool
        public init(id: String, size: Int64, local: Bool, lastUsed: Int64, pinned: Bool, own: Bool) {
            self.id = id
            self.size = size
            self.local = local
            self.lastUsed = lastUsed
            self.pinned = pinned
            self.own = own
        }
    }

    public struct Plan: Equatable {
        public var evict: [String]
        public var fetch: [String]
    }

    /// "Keep offline" pins and last-open times, stored on this device only.
    public struct Pins: Codable, Equatable {
        public var items: Set<String>
        public var folders: Set<String>
        public var opened: [String: Int64]
        public init(items: Set<String> = [], folders: Set<String> = [], opened: [String: Int64] = [:]) {
            self.items = items
            self.folders = folders
            self.opened = opened
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            items = (try? c.decodeIfPresent(Set<String>.self, forKey: .items)) ?? []
            folders = (try? c.decodeIfPresent(Set<String>.self, forKey: .folders)) ?? []
            opened = (try? c.decodeIfPresent([String: Int64].self, forKey: .opened)) ?? [:]
        }

        public func togglingItem(_ id: String) -> Pins {
            var p = self
            if p.items.contains(id) { p.items.remove(id) } else { p.items.insert(id) }
            return p
        }

        public func togglingFolder(_ id: String) -> Pins {
            var p = self
            if p.folders.contains(id) { p.folders.remove(id) } else { p.folders.insert(id) }
            return p
        }

        public func opening(_ id: String, now: Int64) -> Pins {
            var p = self
            p.opened[id] = now
            return p
        }

        public func pruned(liveItems: Set<String>, liveFolders: Set<String>) -> Pins {
            Pins(items: items.intersection(liveItems), folders: folders.intersection(liveFolders),
                 opened: opened.filter { liveItems.contains($0.key) })
        }
    }

    public static func pinned(_ item: ShelfItem, folder: String?, pins: Pins) -> Bool {
        pins.items.contains(item.id) || (folder.map { pins.folders.contains($0) } ?? false)
    }

    public static func plan(_ files: [Entry], cap: Int64) -> Plan {
        var keep = Set<String>()
        var used: Int64 = 0
        for e in files where e.pinned || (e.own && e.local) {
            keep.insert(e.id)
            if e.local || (e.size >= 0 && e.size <= ShelfCache.maxBytes) { used += e.size }
        }
        let rest = files.filter { !keep.contains($0.id) }
            .sorted { $0.lastUsed != $1.lastUsed ? $0.lastUsed > $1.lastUsed : $0.id < $1.id }
        for e in rest where cap > 0 && e.size <= ShelfCache.maxBytes && used + e.size <= cap {
            keep.insert(e.id)
            used += e.size
        }
        return Plan(evict: files.filter { $0.local && !keep.contains($0.id) }.map(\.id),
                    fetch: files.filter { !$0.local && keep.contains($0.id) && $0.size >= 0 && $0.size <= ShelfCache.maxBytes }.map(\.id))
    }

    public static func entries(_ state: ShelfState, local: Set<String>, pins: Pins, me: String) -> [Entry] {
        state.items.compactMap { i in
            guard let f = i.file else { return nil }
            return Entry(id: i.id, size: f.size, local: local.contains(i.id), lastUsed: pins.opened[i.id] ?? i.created,
                         pinned: pinned(i, folder: state.folderOf(i), pins: pins), own: f.owner == me)
        }
    }
}
