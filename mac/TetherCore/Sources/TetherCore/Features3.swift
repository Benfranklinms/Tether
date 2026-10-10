//
// Features3.swift — protocol revision 3 (app 0.3.0) payloads, mirrored in
// android/core/.../proto/Features3.kt: SMS, call alerts, now playing, proximity.
// Gated on Hello.caps; older builds ignore the new types.
//

import Foundation

public enum Caps3 {
    public static let sms = "sms"
    public static let call = "call"
    public static let nowPlaying = "nowplaying"
    public static let proximity = "proximity"
    public static let all = [sms, call, nowPlaying, proximity]
}

extension KeyedDecodingContainer {
    /// Decodes an optional-with-default field the way kotlinx.serialization does.
    func value<T: Decodable>(_ key: Key, _ fallback: T) throws -> T {
        try decodeIfPresent(T.self, forKey: key) ?? fallback
    }
}

// MARK: - SMS

public struct SimInfo: Codable, Equatable, Hashable, Identifiable {
    public var id: Int
    public var slot: Int
    public var name: String
    public var number: String?

    public init(id: Int, slot: Int, name: String, number: String? = nil) {
        self.id = id
        self.slot = slot
        self.name = name
        self.number = number
    }
}

public struct SmsAttachment: Codable, Equatable, Hashable {
    public var id: String
    public var mime: String
    public var w: Int
    public var h: Int

    public init(id: String, mime: String, w: Int = 0, h: Int = 0) {
        self.id = id
        self.mime = mime
        self.w = w
        self.h = h
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        mime = try c.value(.mime, "application/octet-stream")
        w = try c.value(.w, 0)
        h = try c.value(.h, 0)
    }

    /// "Photo", "Video", "Audio" or "Attachment" for the bubble label.
    public var label: String {
        if mime.hasPrefix("image/") { return "Photo" }
        if mime.hasPrefix("video/") { return "Video" }
        if mime.hasPrefix("audio/") { return "Audio" }
        return "Attachment"
    }
}

public enum SmsStatus {
    public static let received = "received"
    public static let sent = "sent"
    public static let pending = "pending"
    public static let failed = "failed"
}

public struct SmsMessage: Codable, Equatable, Hashable, Identifiable {
    public var id: String
    public var thread: Int64
    public var addr: String
    public var name: String?
    public var body: String
    public var date: Int64
    public var out: Bool
    public var status: String
    public var sub: Int?
    public var att: [SmsAttachment]
    public var read: Bool

    public init(id: String, thread: Int64, addr: String, name: String? = nil, body: String = "", date: Int64,
                out: Bool = false, status: String = SmsStatus.received, sub: Int? = nil, att: [SmsAttachment] = [],
                read: Bool = true) {
        self.id = id
        self.thread = thread
        self.addr = addr
        self.name = name
        self.body = body
        self.date = date
        self.out = out
        self.status = status
        self.sub = sub
        self.att = att
        self.read = read
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        thread = try c.decode(Int64.self, forKey: .thread)
        addr = try c.value(.addr, "")
        name = try c.decodeIfPresent(String.self, forKey: .name)
        body = try c.value(.body, "")
        date = try c.decode(Int64.self, forKey: .date)
        out = try c.value(.out, false)
        status = try c.value(.status, SmsStatus.received)
        sub = try c.decodeIfPresent(Int.self, forKey: .sub)
        att = try c.value(.att, [])
        read = try c.value(.read, true)
    }
}

public struct SmsThread: Codable, Equatable, Hashable, Identifiable {
    public var id: Int64
    public var addrs: [String]
    public var names: [String?]
    public var snippet: String
    public var date: Int64
    public var unread: Int
    public var outLast: Bool

    public init(id: Int64, addrs: [String], names: [String?] = [], snippet: String = "", date: Int64, unread: Int = 0,
                outLast: Bool = false) {
        self.id = id
        self.addrs = addrs
        self.names = names
        self.snippet = snippet
        self.date = date
        self.unread = unread
        self.outLast = outLast
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        addrs = try c.value(.addrs, [])
        names = try c.value(.names, [])
        snippet = try c.value(.snippet, "")
        date = try c.decode(Int64.self, forKey: .date)
        unread = try c.value(.unread, 0)
        outLast = try c.value(.outLast, false)
    }

    /// "Asha", "Asha, Ben +2" or the number(s) when there is no contact.
    public var title: String {
        let parts = addrs.enumerated().map { i, a in (i < names.count ? names[i] : nil) ?? a }
        if parts.isEmpty { return "Unknown" }
        if parts.count <= 2 { return parts.joined(separator: ", ") }
        return "\(parts[0]), \(parts[1]) +\(parts.count - 2)"
    }
}

public struct SmsThreadsReq: Codable, Equatable {
    public var limit: Int
    public var before: Int64?
    public var query: String?
    public init(limit: Int = 50, before: Int64? = nil, query: String? = nil) {
        self.limit = limit
        self.before = before
        self.query = query
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        limit = try c.value(.limit, 50)
        before = try c.decodeIfPresent(Int64.self, forKey: .before)
        query = try c.decodeIfPresent(String.self, forKey: .query)
    }
}

public struct SmsThreadsPayload: Codable, Equatable {
    public var threads: [SmsThread]
    public var more: Bool
    public var query: String?
    public var needs: String?
    public init(threads: [SmsThread], more: Bool = false, query: String? = nil, needs: String? = nil) {
        self.threads = threads
        self.more = more
        self.query = query
        self.needs = needs
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        threads = try c.value(.threads, [])
        more = try c.value(.more, false)
        query = try c.decodeIfPresent(String.self, forKey: .query)
        needs = try c.decodeIfPresent(String.self, forKey: .needs)
    }
}

public struct SmsThreadReq: Codable, Equatable {
    public var thread: Int64
    public var limit: Int
    public var before: Int64?
    public init(thread: Int64, limit: Int = 60, before: Int64? = nil) {
        self.thread = thread
        self.limit = limit
        self.before = before
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        thread = try c.decode(Int64.self, forKey: .thread)
        limit = try c.value(.limit, 60)
        before = try c.decodeIfPresent(Int64.self, forKey: .before)
    }
}

public struct SmsMessagesPayload: Codable, Equatable {
    public var thread: Int64
    public var msgs: [SmsMessage]
    public var more: Bool
    public var search: String?
    /// Why the page is empty (revision 3.1): "disabled", "sms" or "error:<reason>".
    public var error: String?
    public init(thread: Int64, msgs: [SmsMessage], more: Bool = false, search: String? = nil, error: String? = nil) {
        self.thread = thread
        self.msgs = msgs
        self.more = more
        self.search = search
        self.error = error
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        thread = try c.decode(Int64.self, forKey: .thread)
        msgs = try c.value(.msgs, [])
        more = try c.value(.more, false)
        search = try c.decodeIfPresent(String.self, forKey: .search)
        error = try c.decodeIfPresent(String.self, forKey: .error)
    }
}

public struct SmsSendPayload: Codable, Equatable {
    public var ref: String
    public var to: [String]
    public var body: String
    public var thread: Int64?
    public var sub: Int?
    public init(ref: String, to: [String], body: String, thread: Int64? = nil, sub: Int? = nil) {
        self.ref = ref
        self.to = to
        self.body = body
        self.thread = thread
        self.sub = sub
    }
}

public struct SmsSendResultPayload: Codable, Equatable {
    public var ref: String
    public var ok: Bool
    public var reason: String?
    public init(ref: String, ok: Bool, reason: String? = nil) {
        self.ref = ref
        self.ok = ok
        self.reason = reason
    }
}

struct SmsNewHeader: Codable { var msg: SmsMessage }

public struct SmsSearchPayload: Codable, Equatable {
    public var query: String
    public var limit: Int
    public init(query: String, limit: Int = 50) {
        self.query = query
        self.limit = limit
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        query = try c.decode(String.self, forKey: .query)
        limit = try c.value(.limit, 50)
    }
}

struct SimsHeader: Codable { var sims: [SimInfo] }

// MARK: - Calls

public enum CallStates {
    public static let ringing = "ringing"
    public static let offhook = "offhook"
    public static let ended = "ended"
    public static let missed = "missed"
}

public enum CallActions {
    public static let decline = "decline"
    public static let silence = "silence"
}

public struct CallStatePayload: Codable, Equatable {
    public var id: String
    public var state: String
    public var number: String?
    public var name: String?
    public var ts: Int64
    public var reason: String?
    public init(id: String, state: String, number: String? = nil, name: String? = nil, ts: Int64, reason: String? = nil) {
        self.id = id
        self.state = state
        self.number = number
        self.name = name
        self.ts = ts
        self.reason = reason
    }

    /// Contact name, else the number, else "Unknown caller" (withheld numbers).
    public var caller: String { name ?? number ?? "Unknown caller" }
}

public struct CallActionPayload: Codable, Equatable {
    public var id: String
    public var action: String
    public init(id: String, action: String) {
        self.id = id
        self.action = action
    }
}

// MARK: - Now playing

public struct NowPlayingPayload: Codable, Equatable {
    public var active: Bool
    public var pkg: String
    public var app: String
    public var title: String
    public var artist: String
    public var album: String
    public var durationMs: Int64
    public var positionMs: Int64
    public var playing: Bool
    public var speed: Float
    public var canSeek: Bool
    public var canNext: Bool
    public var canPrev: Bool
    public var volume: Float?
    public var art: String?
    public var ts: Int64

    public init(active: Bool, pkg: String = "", app: String = "", title: String = "", artist: String = "", album: String = "",
                durationMs: Int64 = 0, positionMs: Int64 = 0, playing: Bool = false, speed: Float = 1, canSeek: Bool = false,
                canNext: Bool = false, canPrev: Bool = false, volume: Float? = nil, art: String? = nil, ts: Int64) {
        self.active = active
        self.pkg = pkg
        self.app = app
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.positionMs = positionMs
        self.playing = playing
        self.speed = speed
        self.canSeek = canSeek
        self.canNext = canNext
        self.canPrev = canPrev
        self.volume = volume
        self.art = art
        self.ts = ts
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        active = try c.value(.active, false)
        pkg = try c.value(.pkg, "")
        app = try c.value(.app, "")
        title = try c.value(.title, "")
        artist = try c.value(.artist, "")
        album = try c.value(.album, "")
        durationMs = try c.value(.durationMs, 0)
        positionMs = try c.value(.positionMs, 0)
        playing = try c.value(.playing, false)
        speed = try c.value(.speed, 1)
        canSeek = try c.value(.canSeek, false)
        canNext = try c.value(.canNext, false)
        canPrev = try c.value(.canPrev, false)
        volume = try c.decodeIfPresent(Float.self, forKey: .volume)
        art = try c.decodeIfPresent(String.self, forKey: .art)
        ts = try c.decode(Int64.self, forKey: .ts)
    }

    /// Position extrapolated to `now` (ms, same clock as the phone's `ts`, corrected by `skew`).
    public func position(at now: Int64, skew: Int64 = 0) -> Int64 {
        guard playing else { return positionMs }
        let elapsed = max(0, now - skew - ts)
        let p = positionMs + Int64(Double(elapsed) * Double(speed))
        return durationMs > 0 ? min(p, durationMs) : p
    }
}

public enum MediaCmds {
    public static let play = "play"
    public static let pause = "pause"
    public static let toggle = "toggle"
    public static let next = "next"
    public static let prev = "prev"
    public static let seek = "seek"
    public static let volume = "volume"
}

public struct MediaCmdPayload: Codable, Equatable {
    public var cmd: String
    public var pos: Int64?
    public var vol: Float?
    public init(cmd: String, pos: Int64? = nil, vol: Float? = nil) {
        self.cmd = cmd
        self.pos = pos
        self.vol = vol
    }
}

struct ArtHeader: Codable { var hash: String }

// MARK: - Proximity

public struct ProximityCfgPayload: Codable, Equatable {
    public var on: Bool
    public var intervalMs: Int
    public init(on: Bool, intervalMs: Int = 2000) {
        self.on = on
        self.intervalMs = intervalMs
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        on = try c.decode(Bool.self, forKey: .on)
        intervalMs = try c.value(.intervalMs, 2000)
    }
}

struct RssiHeader: Codable {
    var rssi: Int
    var ts: Int64
}
