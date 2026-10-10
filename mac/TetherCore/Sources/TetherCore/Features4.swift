//
// Features4.swift — protocol revision 4 (app 0.4.0) payloads, mirrored in
// android/core/.../proto/Features4.kt: calling from the Mac, call control, call history,
// contact search, sensitive-notification status. Gated on Hello.caps.
//

import Foundation

public enum Caps4 {
    public static let dial = "dial"
    public static let callControl = "callctl"
    public static let callLog = "calllog"
    public static let notifAccess = "notifaccess"
    public static let all = [dial, callControl, callLog, notifAccess]
}

/// CALL_ACTION actions added in revision 4 (only sent to phones announcing `callctl`).
public enum CallActions4 {
    public static let answer = "answer"
    public static let end = "end"
    public static let speaker = "speaker"
    public static let mute = "mute"
    public static let hold = "hold"
}

// MARK: - Dialing

public struct CallDialPayload: Codable, Equatable {
    public var ref: String
    public var number: String
    public var sub: Int?
    public init(ref: String, number: String, sub: Int? = nil) {
        self.ref = ref
        self.number = number
        self.sub = sub
    }
}

public struct CallDialResultPayload: Codable, Equatable {
    public var ref: String
    public var ok: Bool
    public var reason: String?
    /// "telecom" (the phone placed it) or "notification" (tap on the phone to start it).
    public var via: String?
    public init(ref: String, ok: Bool, reason: String? = nil, via: String? = nil) {
        self.ref = ref
        self.ok = ok
        self.reason = reason
        self.via = via
    }
}

// MARK: - Live call

public enum CallDirs {
    public static let incoming = "in"
    public static let outgoing = "out"
}

public enum CallLiveStates {
    public static let ringing = "ringing"
    public static let dialing = "dialing"
    public static let active = "active"
    public static let ended = "ended"
}

public struct CallLivePayload: Codable, Equatable {
    public var id: String
    public var dir: String
    public var state: String
    public var number: String?
    public var name: String?
    public var since: Int64
    public var speaker: Bool
    public var muted: Bool
    public var held: Bool
    public var canAnswer: Bool
    public var canEnd: Bool
    public var canSpeaker: Bool
    public var canMute: Bool
    public var canHold: Bool
    public var sub: Int?
    public var ts: Int64
    public var reason: String?

    public init(id: String, dir: String, state: String, number: String? = nil, name: String? = nil, since: Int64 = 0,
                speaker: Bool = false, muted: Bool = false, held: Bool = false, canAnswer: Bool = false, canEnd: Bool = false,
                canSpeaker: Bool = false, canMute: Bool = false, canHold: Bool = false, sub: Int? = nil, ts: Int64,
                reason: String? = nil) {
        self.id = id
        self.dir = dir
        self.state = state
        self.number = number
        self.name = name
        self.since = since
        self.speaker = speaker
        self.muted = muted
        self.held = held
        self.canAnswer = canAnswer
        self.canEnd = canEnd
        self.canSpeaker = canSpeaker
        self.canMute = canMute
        self.canHold = canHold
        self.sub = sub
        self.ts = ts
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        dir = try c.value(.dir, CallDirs.incoming)
        state = try c.decode(String.self, forKey: .state)
        number = try c.decodeIfPresent(String.self, forKey: .number)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        since = try c.value(.since, 0)
        speaker = try c.value(.speaker, false)
        muted = try c.value(.muted, false)
        held = try c.value(.held, false)
        canAnswer = try c.value(.canAnswer, false)
        canEnd = try c.value(.canEnd, false)
        canSpeaker = try c.value(.canSpeaker, false)
        canMute = try c.value(.canMute, false)
        canHold = try c.value(.canHold, false)
        sub = try c.decodeIfPresent(Int.self, forKey: .sub)
        ts = try c.decode(Int64.self, forKey: .ts)
        reason = try c.decodeIfPresent(String.self, forKey: .reason)
    }

    /// Contact name, else the number, else a neutral label.
    public var caller: String { name ?? number ?? (dir == CallDirs.outgoing ? "Call in progress" : "Unknown caller") }
    public var isUp: Bool { state != CallLiveStates.ended }
    public var isOffhook: Bool { state == CallLiveStates.active || state == CallLiveStates.dialing }
}

// MARK: - Call history

public enum CallLogTypes {
    public static let incoming = "in"
    public static let outgoing = "out"
    public static let missed = "missed"
    public static let rejected = "rejected"
    public static let blocked = "blocked"
    public static let voicemail = "voicemail"
    public static let other = "other"
}

public struct CallLogEntry: Codable, Equatable, Hashable, Identifiable {
    public var id: Int64
    public var number: String
    public var name: String?
    public var type: String
    public var date: Int64
    public var dur: Int64
    public var sub: Int?
    public var isNew: Bool

    public init(id: Int64, number: String, name: String? = nil, type: String, date: Int64, dur: Int64 = 0, sub: Int? = nil,
                isNew: Bool = false) {
        self.id = id
        self.number = number
        self.name = name
        self.type = type
        self.date = date
        self.dur = dur
        self.sub = sub
        self.isNew = isNew
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        number = try c.value(.number, "")
        name = try c.decodeIfPresent(String.self, forKey: .name)
        type = try c.value(.type, CallLogTypes.other)
        date = try c.decode(Int64.self, forKey: .date)
        dur = try c.value(.dur, 0)
        sub = try c.decodeIfPresent(Int.self, forKey: .sub)
        isNew = try c.value(.isNew, false)
    }

    public var caller: String { name ?? (number.isEmpty ? "Unknown" : number) }
    public var isMissed: Bool { type == CallLogTypes.missed }
}

public struct CallLogReq: Codable, Equatable {
    public var limit: Int
    public var before: Int64?
    public init(limit: Int = 50, before: Int64? = nil) {
        self.limit = limit
        self.before = before
    }
}

public struct CallLogPayload: Codable, Equatable {
    public var calls: [CallLogEntry]
    public var more: Bool
    public var needs: String?
    public var before: Int64?
    public init(calls: [CallLogEntry], more: Bool = false, needs: String? = nil, before: Int64? = nil) {
        self.calls = calls
        self.more = more
        self.needs = needs
        self.before = before
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        calls = try c.value(.calls, [])
        more = try c.value(.more, false)
        needs = try c.decodeIfPresent(String.self, forKey: .needs)
        before = try c.decodeIfPresent(Int64.self, forKey: .before)
    }
}

// MARK: - Contacts

public struct ContactNumber: Codable, Equatable, Hashable {
    public var n: String
    public var label: String?
    public init(n: String, label: String? = nil) {
        self.n = n
        self.label = label
    }
}

public struct ContactInfo: Codable, Equatable, Hashable {
    public var name: String
    public var numbers: [ContactNumber]
    public init(name: String, numbers: [ContactNumber]) {
        self.name = name
        self.numbers = numbers
    }
}

public struct ContactSearchPayload: Codable, Equatable {
    public var query: String
    public var limit: Int
    public init(query: String, limit: Int = 20) {
        self.query = query
        self.limit = limit
    }
}

public struct ContactsPayload: Codable, Equatable {
    public var query: String
    public var contacts: [ContactInfo]
    public var needs: String?
    public init(query: String, contacts: [ContactInfo], needs: String? = nil) {
        self.query = query
        self.contacts = contacts
        self.needs = needs
    }
}

// MARK: - Sensitive notifications

public struct NotifAccessPayload: Codable, Equatable {
    public var redacting: Bool
    public var granted: Bool?
    public var sdk: Int
    public var oem: String
    public var ts: Int64
    public init(redacting: Bool, granted: Bool? = nil, sdk: Int, oem: String = "", ts: Int64) {
        self.redacting = redacting
        self.granted = granted
        self.sdk = sdk
        self.oem = oem
        self.ts = ts
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        redacting = try c.value(.redacting, false)
        granted = try c.decodeIfPresent(Bool.self, forKey: .granted)
        sdk = try c.value(.sdk, 0)
        oem = try c.value(.oem, "")
        ts = try c.value(.ts, 0)
    }

    /// The one-line fix, for the guide.
    public static func adbCommand(package: String = "in.benfranklin.tether") -> String {
        "adb shell cmd appops set --user 0 \(package) RECEIVE_SENSITIVE_NOTIFICATIONS allow"
    }
}
