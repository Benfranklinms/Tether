//
// Features6.swift — protocol revision 6 (app 0.5.2) payloads, mirrored in
// android/core/.../proto/Features6.kt: Insert from Phone (CAPTURE_REQ / CAPTURE_RESULT, file
// kind "capture"), Find my Mac (FIND_MAC / FIND_MAC_STATE), Instant Hotspot (HOTSPOT_REQ /
// HOTSPOT_STATE) and Shelf (SHELF_SYNC / SHELF_FILE_REQ, file kind "shelf"). Gated on
// Hello.caps; all of it only ever travels inside the paired, Noise-encrypted session.
//

import Foundation

public enum Caps6 {
    public static let capture = "capture"
    public static let findMac = "findmac"
    public static let hotspot = "hotspot"
    public static let shelf = "shelf"
    public static let all = [capture, findMac, hotspot, shelf]
}

public enum FileKinds6 {
    public static let capture = "capture"
    public static let shelf = "shelf"
}

public enum CaptureKinds {
    public static let photo = "photo"
    public static let scan = "scan"
}

public enum CaptureStates {
    public static let open = "open"
    public static let sending = "sending"
    public static let done = "done"
    public static let failed = "failed"
}

public enum CaptureReasons {
    public static let cancelled = "cancelled"
    public static let disabled = "disabled"
    public static let busy = "busy"
    public static let noCamera = "no_camera"
    public static let tapPhone = "tap_phone"
}

public struct CaptureReq: Codable, Equatable {
    public var ref: String
    public var kind: String
    public init(ref: String, kind: String) {
        self.ref = ref
        self.kind = kind
    }
}

public struct CaptureResultPayload: Codable, Equatable {
    public var ref: String
    public var state: String
    public var id: String?
    public var name: String?
    public var reason: String?
    public var pages: Int?
    public var via: String?
    public init(ref: String, state: String, id: String? = nil, name: String? = nil, reason: String? = nil, pages: Int? = nil,
                via: String? = nil) {
        self.ref = ref
        self.state = state
        self.id = id
        self.name = name
        self.reason = reason
        self.pages = pages
        self.via = via
    }
}

public enum FindActions {
    public static let ring = "ring"
    public static let stop = "stop"
    public static let lock = "lock"
}

public struct FindMacPayload: Codable, Equatable {
    public var id: String
    public var action: String
    public init(id: String, action: String) {
        self.id = id
        self.action = action
    }
}

public struct FindMacStatePayload: Codable, Equatable {
    public var id: String
    public var ringing: Bool
    public var ok: Bool?
    public var locked: Bool?
    public var reason: String?
    public var by: String?
    public init(id: String, ringing: Bool, ok: Bool? = true, locked: Bool? = false, reason: String? = nil, by: String? = nil) {
        self.id = id
        self.ringing = ringing
        self.ok = ok
        self.locked = locked
        self.reason = reason
        self.by = by
    }
}

public struct HotspotReq: Codable, Equatable {
    public var ref: String
    public var on: Bool
    public init(ref: String, on: Bool = true) {
        self.ref = ref
        self.on = on
    }
}

public enum HotspotStates {
    public static let starting = "starting"
    public static let manual = "manual"
    public static let on = "on"
    public static let off = "off"
    public static let failed = "failed"
}

public enum HotspotReasons {
    public static let disabled = "disabled"
    public static let noService = "no_service"
    public static let noSwitch = "no_switch"
    public static let noCredentials = "no_credentials"
    public static let timeout = "timeout"
}

public struct HotspotStatePayload: Codable, Equatable, CustomStringConvertible {
    public var ref: String?
    public var state: String
    public var ssid: String?
    public var psk: String?
    public var reason: String?
    public init(ref: String? = nil, state: String, ssid: String? = nil, psk: String? = nil, reason: String? = nil) {
        self.ref = ref
        self.state = state
        self.ssid = ssid
        self.psk = psk
        self.reason = reason
    }

    /// Never log the password.
    public var description: String { "HotspotState(state=\(state), ssid=\(ssid != nil), psk=\(psk != nil), reason=\(reason ?? "-"))" }
}

public enum ShelfKinds {
    public static let text = "text"
    public static let link = "link"
    public static let wifi = "wifi"
    public static let address = "address"
    public static let snippet = "snippet"
    public static let file = "file"
    public static let all = [text, link, wifi, address, snippet, file]
}

public struct ShelfFile: Codable, Equatable, Hashable {
    public var name: String
    public var size: Int64
    public var mime: String
    public var sha256: String
    public var owner: String
    public init(name: String, size: Int64, mime: String, sha256: String, owner: String) {
        self.name = name
        self.size = size
        self.mime = mime
        self.sha256 = sha256
        self.owner = owner
    }
}

/// One Shelf item; see ShelfItem in Features6.kt for the merge rules.
public struct ShelfItem: Codable, Equatable, Hashable, Identifiable {
    public var id: String
    public var kind: String
    public var title: String
    public var body: String
    public var secret: String?
    public var from: String
    public var created: Int64
    public var updated: Int64
    public var order: Double
    public var file: ShelfFile?
    /// Revision 8 (0.5.4, cap "shelffolders"): the folder this item is in; nil = loose. Older builds ignore it.
    public var folderId: String?

    public init(id: String, kind: String, title: String, body: String = "", secret: String? = nil, from: String, created: Int64,
                updated: Int64, order: Double, file: ShelfFile? = nil, folderId: String? = nil) {
        self.id = id
        self.kind = kind
        self.title = title
        self.body = body
        self.secret = secret
        self.from = from
        self.created = created
        self.updated = updated
        self.order = order
        self.file = file
        self.folderId = folderId
    }

    enum CodingKeys: String, CodingKey { case id, kind, title, body, secret, from, created, updated, order, file, folderId }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(String.self, forKey: .kind)
        title = try c.decode(String.self, forKey: .title)
        body = try c.decodeIfPresent(String.self, forKey: .body) ?? ""
        secret = try c.decodeIfPresent(String.self, forKey: .secret)
        from = try c.decode(String.self, forKey: .from)
        created = try c.decode(Int64.self, forKey: .created)
        updated = try c.decode(Int64.self, forKey: .updated)
        order = try c.decode(Double.self, forKey: .order)
        file = try? c.decodeIfPresent(ShelfFile.self, forKey: .file)
        folderId = try? c.decodeIfPresent(String.self, forKey: .folderId)
    }
}

public struct ShelfTomb: Codable, Equatable, Hashable {
    public var id: String
    public var ts: Int64
    public init(id: String, ts: Int64) {
        self.id = id
        self.ts = ts
    }
}

public struct ShelfSyncPayload: Codable, Equatable {
    public var items: [ShelfItem]
    public var removed: [ShelfTomb]
    public var full: Bool
    /// Revision 8 (0.5.4, cap "shelffolders").
    public var folders: [ShelfFolder]
    public var removedFolders: [ShelfTomb]

    public init(items: [ShelfItem] = [], removed: [ShelfTomb] = [], full: Bool = false, folders: [ShelfFolder] = [],
                removedFolders: [ShelfTomb] = []) {
        self.items = items
        self.removed = removed
        self.full = full
        self.folders = folders
        self.removedFolders = removedFolders
    }

    enum CodingKeys: String, CodingKey { case items, removed, full, folders, removedFolders }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decodeIfPresent([ShelfItem].self, forKey: .items) ?? []
        removed = try c.decodeIfPresent([ShelfTomb].self, forKey: .removed) ?? []
        full = try c.decodeIfPresent(Bool.self, forKey: .full) ?? false
        folders = (try? c.decodeIfPresent([ShelfFolder].self, forKey: .folders)) ?? []
        removedFolders = (try? c.decodeIfPresent([ShelfTomb].self, forKey: .removedFolders)) ?? []
    }
}

public struct ShelfFileReq: Codable, Equatable {
    public var id: String
    public init(id: String) { self.id = id }
}
