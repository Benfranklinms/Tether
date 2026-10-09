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

    public init(name: String, platform: String, app: String, caps: [String] = ["clip", "notif", "file"]) {
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
        caps = try c.decodeIfPresent([String].self, forKey: .caps) ?? ["clip", "notif", "file"]
    }
}

public struct NotifPayload: Codable, Equatable {
    public var key: String
    public var pkg: String
    public var app: String
    public var title: String
    public var text: String
    public var ts: Int64

    public init(key: String, pkg: String, app: String, title: String = "", text: String = "", ts: Int64) {
        self.key = key
        self.pkg = pkg
        self.app = app
        self.title = title
        self.text = text
        self.ts = ts
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        pkg = try c.decode(String.self, forKey: .pkg)
        app = try c.decode(String.self, forKey: .app)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        ts = try c.decode(Int64.self, forKey: .ts)
    }
}

public struct FileOfferPayload: Codable, Equatable {
    public var id: String
    public var name: String
    public var size: Int64
    public var sha256: String
    public var mime: String

    public init(id: String, name: String, size: Int64, sha256: String, mime: String = "application/octet-stream") {
        self.id = id
        self.name = name
        self.size = size
        self.sha256 = sha256
        self.mime = mime
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        size = try c.decode(Int64.self, forKey: .size)
        sha256 = try c.decode(String.self, forKey: .sha256)
        mime = try c.decodeIfPresent(String.self, forKey: .mime) ?? "application/octet-stream"
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
        case .unknown: throw ProtocolError("cannot encode unknown message")
        }
        var out = Data(capacity: 5 + header.count + body.count)
        out.append(m.type)
        out.appendU32(UInt32(header.count))
        out.append(header)
        out.append(body)
        return out
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
            throw ProtocolError("bad header for type \(type)")
        }
    }

    // swiftlint:disable:next cyclomatic_complexity
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
            return .clip(id: c.id, ts: c.ts, text: String(decoding: body, as: UTF8.self))
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
        default: return .unknown(type: type)
        }
    }

    public static func encodeHello(_ h: Hello) throws -> Data { try encoder.encode(h) }

    public static func decodeHello(_ d: Data) throws -> Hello {
        do { return try decoder.decode(Hello.self, from: d) } catch { throw ProtocolError("bad hello") }
    }
}
