//
// Features7.swift — protocol revision 7 (app 0.5.3) payloads, mirrored in
// android/core/.../proto/Features7.kt: the Mac's battery and what is playing on it, for the
// phone's Home panels (MAC_STATUS / MAC_STATUS_REQ), and play/pause/skip from the phone
// (MAC_MEDIA_CMD). Gated on Hello.caps; only ever sent inside the paired, encrypted session.
//

import Foundation

public enum Caps7 {
    public static let macStatus = "macstatus"
    public static let all = [macStatus]
}

/// What is playing on the Mac; `active` false = nothing (or the Mac can't tell).
public struct MacMediaPayload: Codable, Equatable {
    public var active: Bool
    public var title: String
    public var artist: String
    public var app: String
    public var playing: Bool
    public var durationMs: Int64
    public var positionMs: Int64

    public init(active: Bool, title: String = "", artist: String = "", app: String = "", playing: Bool = false,
                durationMs: Int64 = 0, positionMs: Int64 = 0) {
        self.active = active
        self.title = title
        self.artist = artist
        self.app = app
        self.playing = playing
        self.durationMs = durationMs
        self.positionMs = positionMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        active = try c.decodeIfPresent(Bool.self, forKey: .active) ?? false
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        artist = try c.decodeIfPresent(String.self, forKey: .artist) ?? ""
        app = try c.decodeIfPresent(String.self, forKey: .app) ?? ""
        playing = try c.decodeIfPresent(Bool.self, forKey: .playing) ?? false
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        positionMs = try c.decodeIfPresent(Int64.self, forKey: .positionMs) ?? 0
    }
}

/// Mac -> phone: battery (nil on a Mac without one) and, when asked, what is playing.
public struct MacStatusPayload: Codable, Equatable {
    public var battery: Int?
    public var charging: Bool?
    public var media: MacMediaPayload?
    public var ts: Int64

    public init(battery: Int? = nil, charging: Bool? = nil, media: MacMediaPayload? = nil, ts: Int64) {
        self.battery = battery
        self.charging = charging
        self.media = media
        self.ts = ts
    }
}

/// Phone -> Mac: answer with MAC_STATUS now; `media` = include what is playing.
public struct MacStatusReq: Codable, Equatable {
    public var media: Bool
    public init(media: Bool = false) { self.media = media }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        media = try c.decodeIfPresent(Bool.self, forKey: .media) ?? false
    }
}

public enum MacMediaCmds {
    public static let toggle = "toggle"
    public static let next = "next"
    public static let prev = "prev"
    public static let all: Set<String> = [toggle, next, prev]
}

/// Phone -> Mac: control what is playing on the Mac. Unknown commands are ignored.
public struct MacMediaCmd: Codable, Equatable {
    public var cmd: String
    public init(cmd: String) { self.cmd = cmd }
}

/// When the Mac pushes its battery: on connect, then only when the level or charging changed.
public struct MacBatteryGate: Equatable {
    private var last: (Int, Bool)?
    public init() {}
    public mutating func shouldSend(level: Int?, charging: Bool?) -> Bool {
        guard let level, let charging else { return false }
        if let l = last, l.0 == level, l.1 == charging { return false }
        last = (level, charging)
        return true
    }
    public mutating func reset() { last = nil }
    public static func == (a: MacBatteryGate, b: MacBatteryGate) -> Bool {
        a.last?.0 == b.last?.0 && a.last?.1 == b.last?.1
    }
}
