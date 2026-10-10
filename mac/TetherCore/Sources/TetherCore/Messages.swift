//
// Messages.swift — wire messages, mirrored in android/core/.../proto/Messages.kt.
// Format: u8 type | u32 headerLength (BE) | header (UTF-8 JSON) | body (raw bytes).
//

import Foundation

public enum TetherProtocol {
    public static let version = 1
    public static let prologue = "tether/1"
    public static let prologueWifi = "tether/1/wifi"
    public static let serviceUUID = "7E7E0001-5A1D-4C6B-9C0E-7465746865B1"
    public static let characteristicUUID = "7E7E0002-5A1D-4C6B-9C0E-7465746865B1"
    public static let bonjourType = "_tether._tcp"
    public static let maxClipBytes = 64 * 1024
    public static let maxClipBytesWifi = 1024 * 1024
    public static let maxTitleChars = 256
    public static let maxTextChars = 2048
    public static let fileChunk = 16 * 1024
    public static let fileChunkWifi = 64 * 1024
    public static let maxTcpRecord = 2 * 1024 * 1024
    public static let fileWarnBytes: Int64 = 20 * 1024 * 1024
    public static let fileMaxBytes: Int64 = 100 * 1024 * 1024
    public static let fileMaxBytesWifi: Int64 = 4 * 1024 * 1024 * 1024
}

public enum RecordKind {
    public static let handshake: UInt8 = 0x01
    public static let transport: UInt8 = 0x02
    public static let close: UInt8 = 0x03
}

public enum CloseReason: UInt8, Error {
    case normal = 0, untrusted, busy, protocolError, versionMismatch, pairingRejected, unpaired, wrongDevice, timeout, linkLost

    public static func of(_ code: UInt8) -> CloseReason { CloseReason(rawValue: code) ?? .protocolError }
}

public struct ProtocolError: Error, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
}

public struct Hello: Codable, Equatable {
    public var v: Int
    public var name: String
    public var platform: String
    public var app: String
    public var caps: [String]

    public init(name: String, platform: String, app: String, caps: [String] = Caps.v1) {
        self.v = TetherProtocol.version
        self.name = name
        self.platform = platform
        self.app = app
        self.caps = caps
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        v = try c.decodeIfPresent(Int.self, forKey: .v) ?? TetherProtocol.version
        name = try c.decode(String.self, forKey: .name)
        platform = try c.decode(String.self, forKey: .platform)
        app = try c.decode(String.self, forKey: .app)
        caps = try c.decodeIfPresent([String].self, forKey: .caps) ?? Caps.v1
    }
}

public struct NotifPayload: Codable, Equatable {
    public var key: String
    public var pkg: String
    public var app: String
    public var title: String
    public var text: String
    public var ts: Int64
    /// Buttons offered by the phone (0.2.0+); empty from older phones.
    public var actions: [NotifActionInfo]

    public init(key: String, pkg: String, app: String, title: String = "", text: String = "", ts: Int64,
                actions: [NotifActionInfo] = []) {
        self.key = key
        self.pkg = pkg
        self.app = app
        self.title = title
        self.text = text
        self.ts = ts
        self.actions = actions
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        pkg = try c.decode(String.self, forKey: .pkg)
        app = try c.decode(String.self, forKey: .app)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        ts = try c.decode(Int64.self, forKey: .ts)
        actions = try c.decodeIfPresent([NotifActionInfo].self, forKey: .actions) ?? []
    }
}

public struct FileOfferPayload: Codable, Equatable {
    public var id: String
    public var name: String
    public var size: Int64
    public var sha256: String
    public var mime: String
    /// "screenshot" / "photo" for automatic media sends (0.2.0+), nil for a user's file.
    public var kind: String?

    public init(id: String, name: String, size: Int64, sha256: String, mime: String = "application/octet-stream",
                kind: String? = nil) {
        self.id = id
        self.name = name
        self.size = size
        self.sha256 = sha256
        self.mime = mime
        self.kind = kind
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        size = try c.decode(Int64.self, forKey: .size)
        sha256 = try c.decode(String.self, forKey: .sha256)
        mime = try c.decodeIfPresent(String.self, forKey: .mime) ?? "application/octet-stream"
        kind = try c.decodeIfPresent(String.self, forKey: .kind)
    }
}

public struct WifiOfferPayload: Codable, Equatable {
    public var addrs: [String]
    public var port: Int
    public var token: String

    public init(addrs: [String], port: Int, token: String) {
        self.addrs = addrs
        self.port = port
        self.token = token
    }
}

public enum Message: Equatable {
    case ping(t: Int64)
    case pong(t: Int64)
    case pairConfirm(token: String?)
    case pairReject(reason: String)
    case unpair(reason: String)
    case keyRotate(key: String)
    case keyRotateAck(key: String)
    case clip(id: String, ts: Int64, text: String)
    case notif(NotifPayload)
    case notifRemoved(key: String)
    case notifDismiss(key: String)
    case fileOffer(FileOfferPayload)
    case fileAccept(id: String, offset: Int64)
    case fileReject(id: String, reason: String)
    case fileChunk(id: String, offset: Int64, data: Data)
    case fileAck(id: String, offset: Int64)
    case fileDone(id: String)
    case fileComplete(id: String, ok: Bool, reason: String?)
    case fileCancel(id: String, reason: String)
    case wifiOffer(WifiOfferPayload)
    case wifiRevoke(reason: String)
    case wifiBind(token: String)
    // revision 2 (0.2.0)
    case notifAction(NotifActionPayload)
    case notifActionResult(NotifActionResultPayload)
    case battery(BatteryPayload)
    case ring(timeoutS: Int)
    case ringStop(reason: String)
    case ringState(ringing: Bool, reason: String?)
    case screenStart(ScreenStartPayload)
    case screenState(ScreenStatePayload)
    case screenStop(reason: String)
    case videoFrame(VideoFramePayload)
    case screenInput(ScreenInputPayload)
    case videoFeedback(VideoFeedbackPayload)
    // revision 3 (0.3.0)
    case smsThreadsReq(SmsThreadsReq)
    case smsThreads(SmsThreadsPayload)
    case smsThreadReq(SmsThreadReq)
    case smsMessages(SmsMessagesPayload)
    case smsSend(SmsSendPayload)
    case smsSendResult(SmsSendResultPayload)
    case smsNew(SmsMessage)
    case smsSearch(SmsSearchPayload)
    case sims([SimInfo])
    case callState(CallStatePayload)
    case callAction(CallActionPayload)
    case nowPlaying(NowPlayingPayload)
    case mediaCmd(MediaCmdPayload)
    case nowPlayingArt(hash: String, data: Data)
    case rssi(rssi: Int, ts: Int64)
    case proximityCfg(ProximityCfgPayload)
    // revision 4 (0.4.0)
    case notifAccess(NotifAccessPayload)
    case callDial(CallDialPayload)
    case callDialResult(CallDialResultPayload)
    case callLive(CallLivePayload)
    case callLogReq(CallLogReq)
    case callLog(CallLogPayload)
    case contactSearch(ContactSearchPayload)
    case contacts(ContactsPayload)
    // revision 5 (0.5.0)
    case otp(OtpPayload)
    case fsListReq(FsListReq)
    case fsList(FsListPayload)
    case fsGet(FsGetPayload)
    case fsGetResult(FsGetResultPayload)
    case thumbReq(ThumbReq)
    case thumb(ThumbPayload)
    case photosReq(PhotosReq)
    case photos(PhotosPayload)
    case albumsReq(AlbumsReq)
    case albums(AlbumsPayload)
    case continueCard(ContinuePayload, thumb: Data)
    case openOnPhone(OpenOnPhonePayload)
    case openResult(OpenResultPayload)
    case typeText(TypeTextPayload)
    case typeResult(TypeResultPayload)
    case dndState(DndStatePayload)
    case phoneStatus(PhoneStatusPayload)
    case phoneStatusReq(ts: Int64)
    case unknown(type: UInt8)

    public var type: UInt8 {
        switch self {
        case .ping: return 0x10
        case .pong: return 0x11
        case .pairConfirm: return 0x20
        case .pairReject: return 0x21
        case .unpair: return 0x22
        case .keyRotate: return 0x23
        case .keyRotateAck: return 0x24
        case .clip: return 0x30
        case .notif: return 0x40
        case .notifRemoved: return 0x41
        case .notifDismiss: return 0x42
        case .fileOffer: return 0x50
        case .fileAccept: return 0x51
        case .fileReject: return 0x52
        case .fileChunk: return 0x53
        case .fileAck: return 0x54
        case .fileDone: return 0x55
        case .fileComplete: return 0x56
        case .fileCancel: return 0x57
        case .wifiOffer: return 0x60
        case .wifiRevoke: return 0x61
        case .wifiBind: return 0x62
        case .notifAction: return 0x43
        case .notifActionResult: return 0x44
        case .battery: return 0x70
        case .ring: return 0x71
        case .ringStop: return 0x72
        case .ringState: return 0x73
        case .screenStart: return 0x80
        case .screenState: return 0x81
        case .screenStop: return 0x82
        case .videoFrame: return 0x83
        case .screenInput: return 0x84
        case .videoFeedback: return 0x85
        case .smsThreadsReq: return 0x90
        case .smsThreads: return 0x91
        case .smsThreadReq: return 0x92
        case .smsMessages: return 0x93
        case .smsSend: return 0x94
        case .smsSendResult: return 0x95
        case .smsNew: return 0x96
        case .smsSearch: return 0x97
        case .sims: return 0x98
        case .callState: return 0xA0
        case .callAction: return 0xA1
        case .nowPlaying: return 0xB0
        case .mediaCmd: return 0xB1
        case .nowPlayingArt: return 0xB2
        case .rssi: return 0xD0
        case .proximityCfg: return 0xD1
        case .notifAccess: return 0x45
        case .callDial: return 0xA2
        case .callDialResult: return 0xA3
        case .callLive: return 0xA4
        case .callLogReq: return 0xA5
        case .callLog: return 0xA6
        case .contactSearch: return 0xA7
        case .contacts: return 0xA8
        case .otp: return 0x32
        case .fsListReq: return 0xC0
        case .fsList: return 0xC1
        case .fsGet: return 0xC2
        case .fsGetResult: return 0xC3
        case .thumbReq: return 0xC4
        case .thumb: return 0xC5
        case .photosReq: return 0xC6
        case .photos: return 0xC7
        case .albumsReq: return 0xCD
        case .albums: return 0xCE
        case .continueCard: return 0xC8
        case .openOnPhone: return 0xC9
        case .openResult: return 0xCA
        case .typeText: return 0xCB
        case .typeResult: return 0xCC
        case .dndState: return 0xE0
        case .phoneStatus: return 0xE1
        case .phoneStatusReq: return 0xE2
        case .unknown(let t): return t
        }
    }

    public var isFileMessage: Bool { (0x50...0x57).contains(type) }
}

// Header shapes (JSON keys must match the Kotlin side).
private struct THeader: Codable { var t: Int64 }
private struct TokenHeader: Codable { var token: String? }
private struct ReasonHeader: Codable {
    var reason: String?
}
private struct KeyHeader: Codable { var key: String }
private struct ClipHeader: Codable { var id: String; var ts: Int64 }
private struct IdHeader: Codable { var id: String }
private struct IdOffsetHeader: Codable { var id: String; var offset: Int64 }
private struct IdReasonHeader: Codable { var id: String; var reason: String? }
private struct CompleteHeader: Codable { var id: String; var ok: Bool; var reason: String? }
private struct RingHeader: Codable { var timeoutS: Int? }
private struct RingStateHeader: Codable { var ringing: Bool; var reason: String? }

public enum MessageCodec {
    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.withoutEscapingSlashes, .sortedKeys]
        return e
    }()
    private static let decoder = JSONDecoder()

    public static func encode(_ m: Message) throws -> Data {
        let header: Data
        var body = Data()
        switch m {
        case .ping(let t), .pong(let t): header = try encoder.encode(THeader(t: t))
        case .pairConfirm(let token): header = try encoder.encode(TokenHeader(token: token))
        case .pairReject(let r), .unpair(let r), .wifiRevoke(let r): header = try encoder.encode(ReasonHeader(reason: r))
        case .keyRotate(let k), .keyRotateAck(let k), .notifRemoved(let k), .notifDismiss(let k):
            header = try encoder.encode(KeyHeader(key: k))
        case .clip(let id, let ts, let text):
            header = try encoder.encode(ClipHeader(id: id, ts: ts))
            body = Data(text.utf8)
        case .notif(let n): header = try encoder.encode(n)
        case .fileOffer(let o): header = try encoder.encode(o)
        case .fileAccept(let id, let o), .fileAck(let id, let o): header = try encoder.encode(IdOffsetHeader(id: id, offset: o))
        case .fileReject(let id, let r), .fileCancel(let id, let r): header = try encoder.encode(IdReasonHeader(id: id, reason: r))
        case .fileChunk(let id, let o, let d):
            header = try encoder.encode(IdOffsetHeader(id: id, offset: o))
            body = d
        case .fileDone(let id): header = try encoder.encode(IdHeader(id: id))
        case .fileComplete(let id, let ok, let r): header = try encoder.encode(CompleteHeader(id: id, ok: ok, reason: r))
        case .wifiOffer(let w): header = try encoder.encode(w)
        case .wifiBind(let t): header = try encoder.encode(TokenHeader(token: t))
        case .notifAction(let p): header = try encoder.encode(p)
        case .notifActionResult(let p): header = try encoder.encode(p)
        case .battery(let p): header = try encoder.encode(p)
        case .ring(let t): header = try encoder.encode(RingHeader(timeoutS: t))
        case .ringStop(let r), .screenStop(let r): header = try encoder.encode(ReasonHeader(reason: r))
        case .ringState(let on, let r): header = try encoder.encode(RingStateHeader(ringing: on, reason: r))
        case .screenStart(let p): header = try encoder.encode(p)
        case .screenState(let p): header = try encoder.encode(p)
        case .videoFrame(let f):
            header = try encoder.encode(VideoHeader(src: f.src, seq: f.seq, pts: f.pts, key: f.key, config: f.config,
                                                    part: f.part, parts: f.parts))
            body = f.data
        case .screenInput(let p): header = try encoder.encode(p)
        case .videoFeedback(let p): header = try encoder.encode(p)
        case .smsThreadsReq(let p): header = try encoder.encode(p)
        case .smsThreads(let p): header = try encoder.encode(p)
        case .smsThreadReq(let p): header = try encoder.encode(p)
        case .smsMessages(let p): header = try encoder.encode(p)
        case .smsSend(let p): header = try encoder.encode(p)
        case .smsSendResult(let p): header = try encoder.encode(p)
        case .smsNew(let p): header = try encoder.encode(SmsNewHeader(msg: p))
        case .smsSearch(let p): header = try encoder.encode(p)
        case .sims(let p): header = try encoder.encode(SimsHeader(sims: p))
        case .callState(let p): header = try encoder.encode(p)
        case .callAction(let p): header = try encoder.encode(p)
        case .nowPlaying(let p): header = try encoder.encode(p)
        case .mediaCmd(let p): header = try encoder.encode(p)
        case .nowPlayingArt(let hash, let d):
            header = try encoder.encode(ArtHeader(hash: hash))
            body = d
        case .rssi(let r, let ts): header = try encoder.encode(RssiHeader(rssi: r, ts: ts))
        case .proximityCfg(let p): header = try encoder.encode(p)
        case .notifAccess(let p): header = try encoder.encode(p)
        case .callDial(let p): header = try encoder.encode(p)
        case .callDialResult(let p): header = try encoder.encode(p)
        case .callLive(let p): header = try encoder.encode(p)
        case .callLogReq(let p): header = try encoder.encode(p)
        case .callLog(let p): header = try encoder.encode(p)
        case .contactSearch(let p): header = try encoder.encode(p)
        case .contacts(let p): header = try encoder.encode(p)
        default:
            let r = try encodeRevision5(m)
            header = r.0
            body = r.1
        }
        var out = Data(capacity: 5 + header.count + body.count)
        out.append(m.type)
        out.appendU32(UInt32(header.count))
        out.append(header)
        out.append(body)
        return out
    }

    private static func encodeRevision5(_ m: Message) throws -> (Data, Data) {
        switch m {
        case .otp(let p): return (try encoder.encode(p), Data())
        case .fsListReq(let p): return (try encoder.encode(p), Data())
        case .fsList(let p): return (try encoder.encode(p), Data())
        case .fsGet(let p): return (try encoder.encode(p), Data())
        case .fsGetResult(let p): return (try encoder.encode(p), Data())
        case .thumbReq(let p): return (try encoder.encode(p), Data())
        case .thumb(let p): return (try encoder.encode(ThumbHeader(ref: p.ref, ok: p.ok, reason: p.reason)), p.data)
        case .photosReq(let p): return (try encoder.encode(p), Data())
        case .photos(let p): return (try encoder.encode(p), Data())
        case .albumsReq(let p): return (try encoder.encode(p), Data())
        case .albums(let p): return (try encoder.encode(p), Data())
        case .continueCard(let p, let thumb): return (try encoder.encode(p), thumb)
        case .openOnPhone(let p): return (try encoder.encode(p), Data())
        case .openResult(let p): return (try encoder.encode(p), Data())
        case .typeText(let p): return (try encoder.encode(p), Data())
        case .typeResult(let p): return (try encoder.encode(p), Data())
        case .dndState(let p): return (try encoder.encode(p), Data())
        case .phoneStatus(let p): return (try encoder.encode(p), Data())
        case .phoneStatusReq(let ts): return (try encoder.encode(StatusReqHeader(ts: ts)), Data())
        default: throw ProtocolError("cannot encode unknown message")
        }
    }

    public static func decode(_ data: Data) throws -> Message {
        let bytes = [UInt8](data)
        guard bytes.count >= 5 else { throw ProtocolError("message too short") }
        let type = bytes[0]
        let len = Int(bytes[1]) << 24 | Int(bytes[2]) << 16 | Int(bytes[3]) << 8 | Int(bytes[4])
        guard len >= 0, len <= bytes.count - 5 else { throw ProtocolError("bad header length") }
        let header = Data(bytes[5..<(5 + len)])
        let body = Data(bytes[(5 + len)...])
        do {
            return try decodeTyped(type, header, body)
        } catch let e as ProtocolError {
            throw e
        } catch {
            throw ProtocolError("bad header for type \(type): \(String(describing: error).prefix(240))")
        }
    }

    private static func decodeTyped(_ type: UInt8, _ h: Data, _ body: Data) throws -> Message {
        func d<T: Decodable>(_ t: T.Type) throws -> T { try decoder.decode(t, from: h) }
        switch type {
        case 0x10: return .ping(t: try d(THeader.self).t)
        case 0x11: return .pong(t: try d(THeader.self).t)
        case 0x20: return .pairConfirm(token: try d(TokenHeader.self).token)
        case 0x21: return .pairReject(reason: try d(ReasonHeader.self).reason ?? "user")
        case 0x22: return .unpair(reason: try d(ReasonHeader.self).reason ?? "user")
        case 0x23: return .keyRotate(key: try d(KeyHeader.self).key)
        case 0x24: return .keyRotateAck(key: try d(KeyHeader.self).key)
        case 0x30:
            guard body.count <= TetherProtocol.maxClipBytesWifi else { throw ProtocolError("clip too large") }
            let c = try d(ClipHeader.self)
            guard let text = String(bytes: body, encoding: .utf8) else { throw ProtocolError("clip is not UTF-8") }
            return .clip(id: c.id, ts: c.ts, text: text)
        case 0x40: return .notif(try d(NotifPayload.self))
        case 0x41: return .notifRemoved(key: try d(KeyHeader.self).key)
        case 0x42: return .notifDismiss(key: try d(KeyHeader.self).key)
        case 0x50: return .fileOffer(try d(FileOfferPayload.self))
        case 0x51: let x = try d(IdOffsetHeader.self); return .fileAccept(id: x.id, offset: x.offset)
        case 0x52: let x = try d(IdReasonHeader.self); return .fileReject(id: x.id, reason: x.reason ?? "unknown")
        case 0x53: let x = try d(IdOffsetHeader.self); return .fileChunk(id: x.id, offset: x.offset, data: body)
        case 0x54: let x = try d(IdOffsetHeader.self); return .fileAck(id: x.id, offset: x.offset)
        case 0x55: return .fileDone(id: try d(IdHeader.self).id)
        case 0x56: let x = try d(CompleteHeader.self); return .fileComplete(id: x.id, ok: x.ok, reason: x.reason)
        case 0x57: let x = try d(IdReasonHeader.self); return .fileCancel(id: x.id, reason: x.reason ?? "user")
        case 0x60: return .wifiOffer(try d(WifiOfferPayload.self))
        case 0x61: return .wifiRevoke(reason: try d(ReasonHeader.self).reason ?? "network")
        case 0x62:
            guard let t = try d(TokenHeader.self).token else { throw ProtocolError("missing token") }
            return .wifiBind(token: t)
        case 0x43: return .notifAction(try d(NotifActionPayload.self))
        case 0x44: return .notifActionResult(try d(NotifActionResultPayload.self))
        case 0x70: return .battery(try d(BatteryPayload.self))
        case 0x71: return .ring(timeoutS: try d(RingHeader.self).timeoutS ?? 60)
        case 0x72: return .ringStop(reason: try d(ReasonHeader.self).reason ?? "user")
        case 0x73: let x = try d(RingStateHeader.self); return .ringState(ringing: x.ringing, reason: x.reason)
        case 0x80: return .screenStart(try d(ScreenStartPayload.self))
        case 0x81: return .screenState(try d(ScreenStatePayload.self))
        case 0x82: return .screenStop(reason: try d(ReasonHeader.self).reason ?? "user")
        case 0x83:
            let h = try d(VideoHeader.self)
            let parts = h.parts ?? 1
            let part = h.part ?? 0
            guard parts >= 1, (0..<parts).contains(part) else { throw ProtocolError("bad frame part") }
            return .videoFrame(VideoFramePayload(src: h.src, seq: h.seq, pts: h.pts, key: h.key ?? false,
                                                 config: h.config ?? false, part: part, parts: parts, data: body))
        case 0x84: return .screenInput(try d(ScreenInputPayload.self))
        case 0x85: return .videoFeedback(try d(VideoFeedbackPayload.self))
        default: return try decodeRevision3(type, h, body)
        }
    }

    /// Largest NOW_PLAYING_ART body accepted (the phone sends ≤ 96 KiB JPEGs).
    public static let maxArtBytes = 256 * 1024

    private static func decodeRevision3(_ type: UInt8, _ h: Data, _ body: Data) throws -> Message {
        func d<T: Decodable>(_ t: T.Type) throws -> T { try decoder.decode(t, from: h) }
        switch type {
        case 0x90: return .smsThreadsReq(try d(SmsThreadsReq.self))
        case 0x91: return .smsThreads(try d(SmsThreadsPayload.self))
        case 0x92: return .smsThreadReq(try d(SmsThreadReq.self))
        case 0x93: return .smsMessages(try d(SmsMessagesPayload.self))
        case 0x94: return .smsSend(try d(SmsSendPayload.self))
        case 0x95: return .smsSendResult(try d(SmsSendResultPayload.self))
        case 0x96: return .smsNew(try d(SmsNewHeader.self).msg)
        case 0x97: return .smsSearch(try d(SmsSearchPayload.self))
        case 0x98: return .sims(try d(SimsHeader.self).sims)
        case 0xA0: return .callState(try d(CallStatePayload.self))
        case 0xA1: return .callAction(try d(CallActionPayload.self))
        case 0xB0: return .nowPlaying(try d(NowPlayingPayload.self))
        case 0xB1: return .mediaCmd(try d(MediaCmdPayload.self))
        case 0xB2:
            guard body.count <= maxArtBytes else { throw ProtocolError("artwork too large") }
            return .nowPlayingArt(hash: try d(ArtHeader.self).hash, data: body)
        case 0xD0: let x = try d(RssiHeader.self); return .rssi(rssi: x.rssi, ts: x.ts)
        case 0xD1: return .proximityCfg(try d(ProximityCfgPayload.self))
        default: return try decodeRevision4(type, h, body)
        }
    }

    private static func decodeRevision4(_ type: UInt8, _ h: Data, _ body: Data) throws -> Message {
        func d<T: Decodable>(_ t: T.Type) throws -> T { try decoder.decode(t, from: h) }
        switch type {
        case 0x45: return .notifAccess(try d(NotifAccessPayload.self))
        case 0xA2: return .callDial(try d(CallDialPayload.self))
        case 0xA3: return .callDialResult(try d(CallDialResultPayload.self))
        case 0xA4: return .callLive(try d(CallLivePayload.self))
        case 0xA5: return .callLogReq(try d(CallLogReq.self))
        case 0xA6: return .callLog(try d(CallLogPayload.self))
        case 0xA7: return .contactSearch(try d(ContactSearchPayload.self))
        case 0xA8: return .contacts(try d(ContactsPayload.self))
        default: return try decodeRevision5(type, h, body)
        }
    }

    /// Largest THUMB / CONTINUE body accepted (the phone sends ≤ 48 KiB JPEGs).
    public static let maxThumbBytes = 96 * 1024

    private static func decodeRevision5(_ type: UInt8, _ h: Data, _ body: Data) throws -> Message {
        func d<T: Decodable>(_ t: T.Type) throws -> T { try decoder.decode(t, from: h) }
        switch type {
        case 0x32: return .otp(try d(OtpPayload.self))
        case 0xC0: return .fsListReq(try d(FsListReq.self))
        case 0xC1: return .fsList(try d(FsListPayload.self))
        case 0xC2: return .fsGet(try d(FsGetPayload.self))
        case 0xC3: return .fsGetResult(try d(FsGetResultPayload.self))
        case 0xC4: return .thumbReq(try d(ThumbReq.self))
        case 0xC5:
            guard body.count <= maxThumbBytes else { throw ProtocolError("thumbnail too large") }
            let x = try d(ThumbHeader.self)
            return .thumb(ThumbPayload(ref: x.ref, ok: x.ok, data: body, reason: x.reason))
        case 0xC6: return .photosReq(try d(PhotosReq.self))
        case 0xC7: return .photos(try d(PhotosPayload.self))
        case 0xCD: return .albumsReq(try d(AlbumsReq.self))
        case 0xCE: return .albums(try d(AlbumsPayload.self))
        case 0xC8:
            guard body.count <= maxThumbBytes else { throw ProtocolError("thumbnail too large") }
            return .continueCard(try d(ContinuePayload.self), thumb: body)
        case 0xC9: return .openOnPhone(try d(OpenOnPhonePayload.self))
        case 0xCA: return .openResult(try d(OpenResultPayload.self))
        case 0xCB: return .typeText(try d(TypeTextPayload.self))
        case 0xCC: return .typeResult(try d(TypeResultPayload.self))
        case 0xE0: return .dndState(try d(DndStatePayload.self))
        case 0xE1: return .phoneStatus(try d(PhoneStatusPayload.self))
        case 0xE2: return .phoneStatusReq(ts: try d(StatusReqHeader.self).ts ?? 0)
        default: return .unknown(type: type)
        }
    }

    public static func encodeHello(_ h: Hello) throws -> Data { try encoder.encode(h) }

    public static func decodeHello(_ d: Data) throws -> Hello {
        do { return try decoder.decode(Hello.self, from: d) } catch { throw ProtocolError("bad hello") }
    }
}
