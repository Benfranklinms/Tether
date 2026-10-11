//
// Features8.swift — protocol revision 8 (app 0.5.4), mirrored in android/core/.../proto/Features8.kt:
// Shelf folders. No new message types: SHELF_SYNC gains `folders` / `removedFolders` and each
// ShelfItem an optional `folderId`, sent only to a peer that announced "shelffolders" (older
// peers ignore them anyway and see every item as loose). Hello.v stays 1. Offline-copy settings
// and "Keep offline" pins are local to each device and never travel.
//

import Foundation

public enum Caps8 {
    public static let shelfFolders = "shelffolders"
    public static let all = [shelfFolders]
}

/// One Shelf folder; `order` is a fractional index (lower = further left), `updated` (ms) decides merges.
public struct ShelfFolder: Codable, Equatable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var order: Double
    public var updated: Int64

    public init(id: String, name: String, order: Double, updated: Int64) {
        self.id = id
        self.name = name
        self.order = order
        self.updated = updated
    }
}
