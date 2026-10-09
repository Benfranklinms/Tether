//
// Session.swift — one encrypted conversation over one link (mirrors Session.kt).
//

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// How a session may treat a peer whose static key isn't trusted yet.
public struct PairingContext {
    public var acceptNew: Bool
    public var expectedRemoteKey: Data?
    public var token: Data?

    public init(acceptNew: Bool, expectedRemoteKey: Data? = nil, token: Data? = nil) {
        self.acceptNew = acceptNew
        self.expectedRemoteKey = expectedRemoteKey
        self.token = token
    }

    public static let closed = PairingContext(acceptNew: false)
}

public enum SessionState { case idle, handshaking, verifying, established, closed }

public protocol SessionListener: AnyObject {
    func sessionOutput(_ record: Data)
    func sessionPairingRequest(sas: String, remote: Hello, remoteKey: Data, autoConfirmed: Bool)
    func sessionEstablished(remote: Hello, remoteKey: Data, newlyPaired: Bool)
    func sessionMessage(_ message: Message)
    func sessionClosed(reason: CloseReason, byRemote: Bool)
}

public final class Session {
    public private(set) var state: SessionState = .idle
    public private(set) var remoteHello: Hello?
    public private(set) var remoteKey: Data?
    public private(set) var sas: String?

    private let role: Role
    private let localHello: Hello
    private let isTrusted: (Data) -> Bool
    private let pairing: PairingContext
    private weak var listener: SessionListener?
    private let handshake: HandshakeState
    private var tx: CipherState?
    private var rx: CipherState?
    private var localConfirmed = false
    private var remoteConfirmed = false
    private var confirmSent = false

    public init(role: Role, localStatic: KeyPair, localHello: Hello, isTrusted: @escaping (Data) -> Bool,
                pairing: PairingContext, listener: SessionListener, fixedEphemeral: Data? = nil,
                prologue: String = TetherProtocol.prologue) {
        self.role = role
        self.localHello = localHello
        self.isTrusted = isTrusted
        self.pairing = pairing
        self.listener = listener
        self.handshake = HandshakeState(role: role, localStatic: localStatic, prologue: Data(prologue.utf8),
                                        fixedEphemeral: fixedEphemeral)
    }

    public func start() {
        precondition(state == .idle, "session already started")
        state = .handshaking
        if role == .initiator { guarded { try writeHandshake() } }
    }

    public func receive(_ record: Data) {
        if state == .closed { return }
        guard let kind = record.first else { return close(.protocolError) }
        guarded {
            switch kind {
            case RecordKind.handshake: try onHandshake(record)
            case RecordKind.transport: try onTransport(record)
            case RecordKind.close:
                let reason = record.count > 1 ? CloseReason.of(record[record.startIndex + 1]) : .normal
                finish(reason, byRemote: true)
            default: close(.protocolError)
            }
        }
    }

    public func send(_ message: Message) throws {
        guard state == .established else { throw ProtocolError("session not established") }
        try sendEncrypted(message)
    }

    public func confirmPairing() {
        guard state == .verifying else { return }
        localConfirmed = true
        guarded {
            try sendConfirmOnce(nil)
            maybeFinishPairing()
        }
    }

    public func rejectPairing() {
        guard state == .verifying else { return }
        try? sendEncrypted(.pairReject(reason: "user"))
        close(.pairingRejected)
    }

    public func close(_ reason: CloseReason = .normal) {
        if state == .closed { return }
        if state != .idle { listener?.sessionOutput(Data([RecordKind.close, reason.rawValue])) }
        finish(reason, byRemote: false)
    }

    public func linkLost() {
        if state != .closed { finish(.linkLost, byRemote: false) }
    }

    // MARK: - internals

    private func guarded(_ body: () throws -> Void) {
        do { try body() } catch let reason as CloseReason {
            close(reason)
        } catch {
            close(.protocolError)
        }
    }

    private func onHandshake(_ record: Data) throws {
        let bytes = [UInt8](record)
        guard state == .handshaking, bytes.count >= 2 else { throw CloseReason.protocolError }
        guard Int(bytes[1]) == handshake.messageIndex + 1 else { throw CloseReason.protocolError }
        let payload = try handshake.readMessage(Data(bytes[2...]))
        if !payload.isEmpty {
            let hello = try MessageCodec.decodeHello(payload)
            guard hello.v == TetherProtocol.version else { throw CloseReason.versionMismatch }
            remoteHello = hello
        }
        if handshake.isComplete { try onHandshakeComplete() } else { try writeHandshake() }
    }

    private func writeHandshake() throws {
        let payload = handshake.messageIndex == 0 ? Data() : try MessageCodec.encodeHello(localHello)
        let step = UInt8(handshake.messageIndex + 1)
        let msg = try handshake.writeMessage(payload)
        listener?.sessionOutput(Data([RecordKind.handshake, step]) + msg)
        if handshake.isComplete { try onHandshakeComplete() }
    }

    private func onHandshakeComplete() throws {
        let result = try handshake.split()
        tx = result.send
        rx = result.receive
        remoteKey = result.remoteStatic
        guard let hello = remoteHello else { throw CloseReason.protocolError }
        let key = result.remoteStatic
        if isTrusted(key) {
            state = .established
            listener?.sessionEstablished(remote: hello, remoteKey: key, newlyPaired: false)
            return
        }
        guard pairing.acceptNew else { return close(.untrusted) }
        if let expected = pairing.expectedRemoteKey, !constantTimeEquals(expected, key) {
            return close(.wrongDevice)
        }
        state = .verifying
        let code = Session.computeSas(result.handshakeHash)
        sas = code
        let auto = role == .initiator && pairing.expectedRemoteKey != nil
        listener?.sessionPairingRequest(sas: code, remote: hello, remoteKey: key, autoConfirmed: auto)
        if auto {
            localConfirmed = true
            try sendConfirmOnce(pairing.token)
        }
    }

    private func onTransport(_ record: Data) throws {
        guard let cipher = rx, state == .established || state == .verifying else { throw CloseReason.protocolError }
        let plain = try cipher.decrypt(Data(record.dropFirst()))
        let message = try MessageCodec.decode(plain)
        if state == .verifying { try onVerifying(message) } else { listener?.sessionMessage(message) }
    }

    private func onVerifying(_ message: Message) throws {
        switch message {
        case .pairConfirm(let token):
            remoteConfirmed = true
            if role == .responder && !localConfirmed && tokenMatches(token) {
                localConfirmed = true
                try sendConfirmOnce(nil)
            }
            maybeFinishPairing()
        case .pairReject:
            finish(.pairingRejected, byRemote: true)
        default:
            throw CloseReason.protocolError
        }
    }

    private func tokenMatches(_ token: String?) -> Bool {
        guard let mine = pairing.token, let t = token, let theirs = B64.decode(t) else { return false }
        return constantTimeEquals(mine, theirs)
    }

    private func sendConfirmOnce(_ token: Data?) throws {
        guard !confirmSent else { return }
        confirmSent = true
        try sendEncrypted(.pairConfirm(token: token.map(B64.encode)))
    }

    private func maybeFinishPairing() {
        guard state == .verifying, localConfirmed, remoteConfirmed, let hello = remoteHello, let key = remoteKey else {
            return
        }
        state = .established
        listener?.sessionEstablished(remote: hello, remoteKey: key, newlyPaired: true)
    }

    private func sendEncrypted(_ message: Message) throws {
        guard let cipher = tx else { throw ProtocolError("no transport cipher") }
        let ct = try cipher.encrypt(try MessageCodec.encode(message))
        listener?.sessionOutput(Data([RecordKind.transport]) + ct)
    }

    private func finish(_ reason: CloseReason, byRemote: Bool) {
        if state == .closed { return }
        state = .closed
        tx?.destroy()
        rx?.destroy()
        listener?.sessionClosed(reason: reason, byRemote: byRemote)
    }

    /// Six-digit short authentication string; identical on both sides of one handshake.
    public static func computeSas(_ handshakeHash: Data) -> String {
        var h = SHA256()
        h.update(data: Data("tether-sas/1".utf8))
        h.update(data: handshakeHash)
        let d = [UInt8](Data(h.finalize()))
        let v = UInt32(d[0]) << 24 | UInt32(d[1]) << 16 | UInt32(d[2]) << 8 | UInt32(d[3])
        let n = Int(v % 1_000_000)
        let s = String(n)
        return String(repeating: "0", count: 6 - s.count) + s
    }
}

/// The one device this install is paired with (mirrors Trust.kt; same JSON keys).
public struct PeerRecord: Codable, Equatable {
    public var name: String
    public var platform: String
    public var key: String
    public var pendingKey: String?
    public var pairedAt: Int64

    public init(name: String, platform: String, key: String, pendingKey: String? = nil, pairedAt: Int64) {
        self.name = name
        self.platform = platform
        self.key = key
        self.pendingKey = pendingKey
        self.pairedAt = pairedAt
    }

    public static func paired(name: String, platform: String, key: Data, now: Int64) -> PeerRecord {
        PeerRecord(name: name, platform: platform, key: B64.encode(key), pairedAt: now)
    }

    public func trusts(_ remote: Data) -> Bool {
        matches(key, remote) || (pendingKey.map { matches($0, remote) } ?? false)
    }

    public func afterHandshake(_ remote: Data, remoteName: String) -> PeerRecord {
        var copy = self
        copy.name = remoteName
        if let p = pendingKey, matches(p, remote) {
            copy.key = p
            copy.pendingKey = nil
        }
        return copy
    }

    public func withPendingKey(_ k: Data) -> PeerRecord {
        var copy = self
        copy.pendingKey = B64.encode(k)
        return copy
    }

    public var keyBytes: Data { B64.decode(key) ?? Data() }

    private func matches(_ stored: String, _ remote: Data) -> Bool {
        guard let b = B64.decode(stored) else { return false }
        return constantTimeEquals(b, remote)
    }
}

/// QR contents: `tether://pair?v=1&k=<key>&t=<token>&n=<name>` (mirrors PairingUri.kt).
public struct PairingURI: Equatable {
    public static let tokenLength = 16
    public let key: Data
    public let token: Data
    public let name: String

    public init(key: Data, token: Data, name: String) {
        self.key = key
        self.token = token
        self.name = name
    }

    public func encode() -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let n = name.addingPercentEncoding(withAllowedCharacters: allowed) ?? "Mac"
        return "tether://pair?v=1&k=\(B64.encode(key))&t=\(B64.encode(token))&n=\(n)"
    }

    public static func parse(_ raw: String) -> PairingURI? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard s.hasPrefix("tether://pair?") else { return nil }
        var params: [String: String] = [:]
        for part in s.dropFirst("tether://pair?".count).split(separator: "&") {
            guard let i = part.firstIndex(of: "="), i != part.startIndex else { continue }
            params[String(part[..<i])] = String(part[part.index(after: i)...])
        }
        guard params["v"] == "1",
              let k = params["k"].flatMap(B64.decode), k.count == KeyPair.keyLength,
              let t = params["t"].flatMap(B64.decode), t.count == tokenLength else { return nil }
        let name = (params["n"] ?? "").removingPercentEncoding ?? ""
        return PairingURI(key: k, token: t, name: name.trimmingCharacters(in: .whitespaces).isEmpty ? "Mac" : name)
    }
}
