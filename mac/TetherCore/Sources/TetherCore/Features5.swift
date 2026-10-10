//
// Features5.swift — protocol revision 5 (app 0.5.0) payloads, mirrored in
// android/core/.../proto/Features5.kt: phone files and photos, one-time codes, Continue on
// Mac cards, open on phone, typing into the phone, Focus/DND sync, the phone's setup status
// and clipboard images (FILE_OFFER kind "clip"). Gated on Hello.caps.
//

import Foundation

public enum Caps5 {
    public static let fs = "fs"
    public static let photos = "photos"
    public static let otp = "otp"
    public static let continueCards = "continue"
    public static let openURL = "openurl"
    public static let type = "type"
    public static let dnd = "dnd"
    public static let status = "status"
    public static let clipImage = "clipimg"
    /// 0.5.1: ALBUMS_REQ / ALBUMS, PHOTOS_REQ album + filter, PhotoItem dur/bucket/fav.
    public static let albums = "albums"
    public static let all = [fs, photos, otp, continueCards, openURL, type, dnd, status, clipImage, albums]
}

public enum FileKinds5 {
    /// A clipboard image: put it on the clipboard instead of saving it as a download.
    public static let clip = "clip"
    /// A file the Mac asked for with FS_GET.
    public static let fs = "fs"
}

public enum FsRoots {
    public static let downloads = "downloads"
    public static let documents = "documents"
    public static let whatsapp = "whatsapp"
    public static let dcim = "dcim"
    public static let pictures = "pictures"
    public static let photos = "photos"
    public static let browsable = [downloads, documents, whatsapp, dcim, pictures]

    public static func title(_ root: String) -> String {
        switch root {
        case downloads: return "Downloads"
        case documents: return "Documents"
        case whatsapp: return "WhatsApp media"
        case dcim: return "Camera (DCIM)"
        case pictures: return "Pictures"
        case photos: return "Photos"
        default: return root
        }
    }

    public static func symbol(_ root: String) -> String {
        switch root {
        case downloads: return "arrow.down.circle"
        case documents: return "doc.text"
        case whatsapp: return "bubble.left.and.bubble.right"
        case dcim: return "camera"
        case pictures: return "photo.on.rectangle"
        default: return "folder"
        }
    }
}

// MARK: - Files

public struct FsListReq: Codable, Equatable {
    public var ref: String
    public var root: String
    public var path: String
    public var offset: Int
    public var limit: Int
    public init(ref: String, root: String, path: String = "", offset: Int = 0, limit: Int = 200) {
        self.ref = ref
        self.root = root
        self.path = path
        self.offset = offset
        self.limit = limit
    }
}

public struct FsEntry: Codable, Equatable, Hashable {
    public var name: String
    public var dir: Bool
    public var size: Int64
    public var mtime: Int64
    public var mime: String?

    public init(name: String, dir: Bool = false, size: Int64 = 0, mtime: Int64 = 0, mime: String? = nil) {
        self.name = name
        self.dir = dir
        self.size = size
        self.mtime = mtime
        self.mime = mime
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        dir = try c.decodeIfPresent(Bool.self, forKey: .dir) ?? false
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        mtime = try c.decodeIfPresent(Int64.self, forKey: .mtime) ?? 0
        mime = try c.decodeIfPresent(String.self, forKey: .mime)
    }

    public var isImage: Bool { mime?.hasPrefix("image/") == true }
    public var isVideo: Bool { mime?.hasPrefix("video/") == true }
}

public struct FsListPayload: Codable, Equatable {
    public var ref: String
    public var root: String
    public var path: String
    public var entries: [FsEntry]
    public var total: Int
    public var more: Bool
    public var needs: String?

    public init(ref: String, root: String, path: String, entries: [FsEntry], total: Int? = nil, more: Bool = false, needs: String? = nil) {
        self.ref = ref
        self.root = root
        self.path = path
        self.entries = entries
        self.total = total ?? entries.count
        self.more = more
        self.needs = needs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ref = try c.decode(String.self, forKey: .ref)
        root = try c.decode(String.self, forKey: .root)
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        entries = try c.decodeIfPresent([FsEntry].self, forKey: .entries) ?? []
        total = try c.decodeIfPresent(Int.self, forKey: .total) ?? entries.count
        more = try c.decodeIfPresent(Bool.self, forKey: .more) ?? false
        needs = try c.decodeIfPresent(String.self, forKey: .needs)
    }
}

public struct FsGetPayload: Codable, Equatable {
    public var ref: String
    public var root: String
    public var path: String
    public init(ref: String, root: String, path: String) {
        self.ref = ref
        self.root = root
        self.path = path
    }
}

public struct FsGetResultPayload: Codable, Equatable {
    public var ref: String
    public var ok: Bool
    public var transferId: String?
    public var name: String?
    public var size: Int64
    public var reason: String?

    public init(ref: String, ok: Bool, transferId: String? = nil, name: String? = nil, size: Int64 = 0, reason: String? = nil) {
        self.ref = ref
        self.ok = ok
        self.transferId = transferId
        self.name = name
        self.size = size
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ref = try c.decode(String.self, forKey: .ref)
        ok = try c.decode(Bool.self, forKey: .ok)
        transferId = try c.decodeIfPresent(String.self, forKey: .transferId)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        reason = try c.decodeIfPresent(String.self, forKey: .reason)
    }
}

public struct ThumbReq: Codable, Equatable {
    public var ref: String
    public var root: String
    public var path: String
    public var size: Int
    public init(ref: String, root: String, path: String, size: Int = 256) {
        self.ref = ref
        self.root = root
        self.path = path
        self.size = size
    }
}

public struct ThumbPayload: Equatable {
    public var ref: String
    public var ok: Bool
    public var reason: String?
    public var data: Data
    public init(ref: String, ok: Bool, data: Data = Data(), reason: String? = nil) {
        self.ref = ref
        self.ok = ok
        self.data = data
        self.reason = reason
    }
}

struct ThumbHeader: Codable {
    var ref: String
    var ok: Bool
    var reason: String?
}

// MARK: - Photos

public struct PhotosReq: Codable, Equatable {
    public var ref: String
    public var limit: Int
    public var before: Int64?
    /// 0.5.1: an AlbumInfo id (nil = all) and a PhotoFilters value (nil = everything).
    public var album: String?
    public var filter: String?
    public init(ref: String, limit: Int = 60, before: Int64? = nil, album: String? = nil, filter: String? = nil) {
        self.ref = ref
        self.limit = limit
        self.before = before
        self.album = album
        self.filter = filter
    }
}

public enum PhotoFilters {
    public static let photos = "photos"
    public static let videos = "videos"
    public static let screenshots = "screenshots"
}

public enum AlbumKinds {
    public static let all = "all"
    public static let camera = "camera"
    public static let screenshots = "screenshots"
    public static let whatsapp = "whatsapp"
    public static let downloads = "downloads"
    public static let videos = "videos"
    public static let favorites = "favorites"
    public static let bucket = "bucket"
}

public struct AlbumInfo: Codable, Equatable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var count: Int
    public var cover: String?
    public var kind: String
    public init(id: String, name: String, count: Int, cover: String? = nil, kind: String = AlbumKinds.bucket) {
        self.id = id
        self.name = name
        self.count = count
        self.cover = cover
        self.kind = kind
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        count = try c.decodeIfPresent(Int.self, forKey: .count) ?? 0
        cover = try c.decodeIfPresent(String.self, forKey: .cover)
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? AlbumKinds.bucket
    }
}

public struct AlbumsReq: Codable, Equatable {
    public var ref: String
    public init(ref: String) { self.ref = ref }
}

public struct AlbumsPayload: Codable, Equatable {
    public var ref: String
    public var albums: [AlbumInfo]
    public var needs: String?
    public init(ref: String, albums: [AlbumInfo], needs: String? = nil) {
        self.ref = ref
        self.albums = albums
        self.needs = needs
    }
}

public struct PhotoItem: Codable, Equatable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var size: Int64
    public var date: Int64
    public var w: Int
    public var h: Int
    public var mime: String
    public var video: Bool
    /// 0.5.1: video duration (ms), MediaStore bucket id, favourite.
    public var dur: Int64
    public var bucket: String?
    public var fav: Bool

    public init(id: String, name: String, size: Int64 = 0, date: Int64 = 0, w: Int = 0, h: Int = 0,
                mime: String = "image/jpeg", video: Bool = false, dur: Int64 = 0, bucket: String? = nil, fav: Bool = false) {
        self.dur = dur
        self.bucket = bucket
        self.fav = fav
        self.id = id
        self.name = name
        self.size = size
        self.date = date
        self.w = w
        self.h = h
        self.mime = mime
        self.video = video
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        date = try c.decodeIfPresent(Int64.self, forKey: .date) ?? 0
        w = try c.decodeIfPresent(Int.self, forKey: .w) ?? 0
        h = try c.decodeIfPresent(Int.self, forKey: .h) ?? 0
        mime = try c.decodeIfPresent(String.self, forKey: .mime) ?? "image/jpeg"
        video = try c.decodeIfPresent(Bool.self, forKey: .video) ?? false
        dur = try c.decodeIfPresent(Int64.self, forKey: .dur) ?? 0
        bucket = try c.decodeIfPresent(String.self, forKey: .bucket)
        fav = try c.decodeIfPresent(Bool.self, forKey: .fav) ?? false
    }
}

public struct PhotosPayload: Codable, Equatable {
    public var ref: String
    public var items: [PhotoItem]
    public var more: Bool
    public var needs: String?
    public var before: Int64?

    public init(ref: String, items: [PhotoItem], more: Bool = false, needs: String? = nil, before: Int64? = nil) {
        self.ref = ref
        self.items = items
        self.more = more
        self.needs = needs
        self.before = before
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ref = try c.decode(String.self, forKey: .ref)
        items = try c.decodeIfPresent([PhotoItem].self, forKey: .items) ?? []
        more = try c.decodeIfPresent(Bool.self, forKey: .more) ?? false
        needs = try c.decodeIfPresent(String.self, forKey: .needs)
        before = try c.decodeIfPresent(Int64.self, forKey: .before)
    }
}

// MARK: - One-time codes

public struct OtpPayload: Codable, Equatable {
    public var code: String
    public var app: String
    public var src: String
    public var ts: Int64
    public init(code: String, app: String, src: String, ts: Int64) {
        self.code = code
        self.app = app
        self.src = src
        self.ts = ts
    }
}

// MARK: - Continue on Mac

public enum ContinueKinds {
    public static let photo = "photo"
    public static let download = "download"
    public static let link = "link"
    public static let share = "share"
    public static let song = "song"
    public static let tab = "tab"
}

public struct ContinuePayload: Codable, Equatable, Identifiable {
    public var id: String
    public var kind: String
    public var title: String
    public var subtitle: String?
    public var url: String?
    public var root: String?
    public var path: String?
    public var app: String?
    public var size: Int64
    public var ts: Int64
    public var ttl: Int

    public static let defaultTTL = 15 * 60

    public init(id: String, kind: String, title: String, subtitle: String? = nil, url: String? = nil, root: String? = nil,
                path: String? = nil, app: String? = nil, size: Int64 = 0, ts: Int64, ttl: Int = ContinuePayload.defaultTTL) {
        self.id = id
        self.kind = kind
        self.title = title
        self.subtitle = subtitle
        self.url = url
        self.root = root
        self.path = path
        self.app = app
        self.size = size
        self.ts = ts
        self.ttl = ttl
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(String.self, forKey: .kind)
        title = try c.decode(String.self, forKey: .title)
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle)
        url = try c.decodeIfPresent(String.self, forKey: .url)
        root = try c.decodeIfPresent(String.self, forKey: .root)
        path = try c.decodeIfPresent(String.self, forKey: .path)
        app = try c.decodeIfPresent(String.self, forKey: .app)
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        ts = try c.decode(Int64.self, forKey: .ts)
        ttl = try c.decodeIfPresent(Int.self, forKey: .ttl) ?? ContinuePayload.defaultTTL
    }
}

// MARK: - Open on phone

public struct OpenOnPhonePayload: Codable, Equatable {
    public var id: String
    public var url: String?
    public var text: String?
    public init(id: String, url: String? = nil, text: String? = nil) {
        self.id = id
        self.url = url
        self.text = text
    }
}

public struct OpenResultPayload: Codable, Equatable {
    public var id: String
    public var ok: Bool
    public var via: String?
    public var reason: String?
    public init(id: String, ok: Bool, via: String? = nil, reason: String? = nil) {
        self.id = id
        self.ok = ok
        self.via = via
        self.reason = reason
    }
}

// MARK: - Typing

public enum TypeKeys {
    public static let backspace = "backspace"
    public static let enter = "enter"
    public static let tab = "tab"
    public static let clear = "clear"
}

public struct TypeTextPayload: Codable, Equatable {
    public var seq: Int64
    public var text: String?
    public var key: String?
    public init(seq: Int64, text: String? = nil, key: String? = nil) {
        self.seq = seq
        self.text = text
        self.key = key
    }
}

public struct TypeResultPayload: Codable, Equatable {
    public var seq: Int64
    public var ok: Bool
    public var reason: String?
    public var field: String?
    public init(seq: Int64, ok: Bool, reason: String? = nil, field: String? = nil) {
        self.seq = seq
        self.ok = ok
        self.reason = reason
        self.field = field
    }
}

// MARK: - Focus / DND

public struct DndStatePayload: Codable, Equatable {
    public var on: Bool
    public var src: String
    public var ts: Int64
    public var canSet: Bool?
    public init(on: Bool, src: String, ts: Int64, canSet: Bool? = nil) {
        self.on = on
        self.src = src
        self.ts = ts
        self.canSet = canSet
    }
}

// MARK: - Phone setup status

public struct PermInfo: Codable, Equatable, Hashable, Identifiable {
    public var id: String
    public var label: String
    public var ok: Bool
    public var required: Bool
    public init(id: String, label: String, ok: Bool, required: Bool = false) {
        self.id = id
        self.label = label
        self.ok = ok
        self.required = required
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        label = try c.decode(String.self, forKey: .label)
        ok = try c.decode(Bool.self, forKey: .ok)
        required = try c.decodeIfPresent(Bool.self, forKey: .required) ?? false
    }
}

public struct PhoneStatusPayload: Codable, Equatable {
    public var perms: [PermInfo]
    public var sdk: Int
    public var ts: Int64
    public init(perms: [PermInfo], sdk: Int = 0, ts: Int64) {
        self.perms = perms
        self.sdk = sdk
        self.ts = ts
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        perms = try c.decodeIfPresent([PermInfo].self, forKey: .perms) ?? []
        sdk = try c.decodeIfPresent(Int.self, forKey: .sdk) ?? 0
        ts = try c.decode(Int64.self, forKey: .ts)
    }
}

struct StatusReqHeader: Codable { var ts: Int64? }
