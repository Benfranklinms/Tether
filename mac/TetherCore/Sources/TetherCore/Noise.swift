//
// Noise.swift — Noise_XX_25519_ChaChaPoly_SHA256
//
// Adapted from bitchat's NoiseProtocol.swift (public domain, permissionlesstech/bitchat):
// same CipherState / SymmetricState / HandshakeState structure and HKDF, trimmed to the XX
// pattern with an implicit (counter) nonce, since Tether's transports are ordered.
// Verified against the cacophony vectors in protocol/vectors/noise_xx.json.
//

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public enum NoiseError: Error, Equatable {
    case notOurTurn
    case truncated
    case invalidKey
    case authenticationFailed
    case nonceExhausted
    case incomplete
}

/// An X25519 key pair (raw 32-byte representations).
public struct KeyPair: Equatable {
    public let privateKey: Data
    public let publicKey: Data

    public init(privateKey: Data) throws {
        guard let k = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey) else {
            throw NoiseError.invalidKey
        }
        self.privateKey = k.rawRepresentation
        self.publicKey = k.publicKey.rawRepresentation
    }

    public static func generate() -> KeyPair {
        let k = Curve25519.KeyAgreement.PrivateKey()
        // swiftlint:disable:next force_try
        return try! KeyPair(privateKey: k.rawRepresentation)
    }

    public static let keyLength = 32
}

enum Primitives {
    static let hashLength = 32
    static let tagLength = 16

    static func dh(_ privateKey: Data, _ publicKey: Data) throws -> Data {
        guard publicKey.count == KeyPair.keyLength,
              let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey),
              let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey),
              let shared = try? priv.sharedSecretFromKeyAgreement(with: pub) else {
            throw NoiseError.invalidKey
        }
        let bytes = shared.withUnsafeBytes { Data($0) }
        // Reject low-order points (all-zero shared secret).
        guard bytes.contains(where: { $0 != 0 }) else { throw NoiseError.invalidKey }
        return bytes
    }

    static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    static func hmac(_ key: Data, _ parts: Data...) -> Data {
        var h = HMAC<SHA256>(key: SymmetricKey(data: key))
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    static func hkdf(_ chainingKey: Data, _ ikm: Data, outputs: Int) -> [Data] {
        let temp = hmac(chainingKey, ikm)
        var result: [Data] = []
        var prev = Data()
        for i in 1...outputs {
            prev = hmac(temp, prev, Data([UInt8(i)]))
            result.append(prev)
        }
        return result
    }

    static func nonce(_ n: UInt64) -> Data {
        var out = Data(count: 12)
        withUnsafeBytes(of: n.littleEndian) { out.replaceSubrange(4..<12, with: $0) }
        return out
    }

    static func encrypt(key: Data, n: UInt64, ad: Data, plaintext: Data) throws -> Data {
        let box = try ChaChaPoly.seal(
            plaintext, using: SymmetricKey(data: key), nonce: ChaChaPoly.Nonce(data: nonce(n)), authenticating: ad
        )
        var out = Data(capacity: plaintext.count + tagLength)
        out.append(box.ciphertext)
        out.append(box.tag)
        return out
    }

    static func decrypt(key: Data, n: UInt64, ad: Data, ciphertext: Data) throws -> Data {
        guard ciphertext.count >= tagLength else { throw NoiseError.truncated }
        do {
            let box = try ChaChaPoly.SealedBox(
                nonce: ChaChaPoly.Nonce(data: nonce(n)),
                ciphertext: ciphertext.prefix(ciphertext.count - tagLength),
                tag: ciphertext.suffix(tagLength)
            )
            return try ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: ad)
        } catch {
            throw NoiseError.authenticationFailed
        }
    }
}

/// Noise CipherState with an implicit 64-bit counter nonce.
public final class CipherState {
    private var key: Data?
    private var n: UInt64 = 0

    init(key: Data? = nil) { self.key = key }

    var hasKey: Bool { key != nil }

    func initializeKey(_ k: Data) {
        key = k
        n = 0
    }

    public func encrypt(_ plaintext: Data, ad: Data = Data()) throws -> Data {
        guard let k = key else { return plaintext }
        guard n != UInt64.max else { throw NoiseError.nonceExhausted }
        let out = try Primitives.encrypt(key: k, n: n, ad: ad, plaintext: plaintext)
        n += 1
        return out
    }

    /// The nonce only advances after a successful open, so a forged record can't desync us.
    public func decrypt(_ ciphertext: Data, ad: Data = Data()) throws -> Data {
        guard let k = key else { return ciphertext }
        guard n != UInt64.max else { throw NoiseError.nonceExhausted }
        let out = try Primitives.decrypt(key: k, n: n, ad: ad, ciphertext: ciphertext)
        n += 1
        return out
    }

    func destroy() {
        key = nil
    }
}

final class SymmetricState {
    private let cipher = CipherState()
    private var ck: Data
    private(set) var h: Data

    init(protocolName: String) {
        let name = Data(protocolName.utf8)
        if name.count <= Primitives.hashLength {
            h = name + Data(count: Primitives.hashLength - name.count)
        } else {
            h = Primitives.sha256(name)
        }
        ck = h
    }

    var hasKey: Bool { cipher.hasKey }

    func mixKey(_ ikm: Data) {
        let out = Primitives.hkdf(ck, ikm, outputs: 2)
        ck = out[0]
        cipher.initializeKey(out[1])
    }

    func mixHash(_ data: Data) {
        h = Primitives.sha256(h, data)
    }

    func encryptAndHash(_ plaintext: Data) throws -> Data {
        let c = try cipher.encrypt(plaintext, ad: h)
        mixHash(c)
        return c
    }

    func decryptAndHash(_ ciphertext: Data) throws -> Data {
        let p = try cipher.decrypt(ciphertext, ad: h)
        mixHash(ciphertext)
        return p
    }

    func split() -> (CipherState, CipherState) {
        let out = Primitives.hkdf(ck, Data(), outputs: 2)
        ck = Data(count: ck.count)
        cipher.destroy()
        return (CipherState(key: out[0]), CipherState(key: out[1]))
    }
}

public enum Role { case initiator, responder }

public struct HandshakeResult {
    public let send: CipherState
    public let receive: CipherState
    public let handshakeHash: Data
    public let remoteStatic: Data
}

/// Noise XX: `-> e`, `<- e, ee, s, es`, `-> s, se`.
public final class HandshakeState {
    public static let protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"

    private enum Token { case e, s, ee, es, se }
    private static let patterns: [[Token]] = [[.e], [.e, .ee, .s, .es], [.s, .se]]

    public let role: Role
    private let localStatic: KeyPair
    private let ss = SymmetricState(protocolName: HandshakeState.protocolName)
    private var fixedEphemeral: Data?
    private var e: KeyPair?
    private var re: Data?
    private var rs: Data?
    private var step = 0

    public init(role: Role, localStatic: KeyPair, prologue: Data, fixedEphemeral: Data? = nil) {
        self.role = role
        self.localStatic = localStatic
        self.fixedEphemeral = fixedEphemeral
        ss.mixHash(prologue)
    }

    public var isComplete: Bool { step >= Self.patterns.count }
    public var messageIndex: Int { step }
    public var isMyTurn: Bool { !isComplete && ((step % 2 == 0) == (role == .initiator)) }

    public func writeMessage(_ payload: Data = Data()) throws -> Data {
        guard isMyTurn else { throw NoiseError.notOurTurn }
        var out = Data()
        for token in Self.patterns[step] {
            switch token {
            case .e:
                let kp: KeyPair
                if let fixed = fixedEphemeral {
                    kp = try KeyPair(privateKey: fixed)
                    fixedEphemeral = nil
                } else {
                    kp = KeyPair.generate()
                }
                e = kp
                out.append(kp.publicKey)
                ss.mixHash(kp.publicKey)
            case .s:
                out.append(try ss.encryptAndHash(localStatic.publicKey))
            default:
                try dh(token)
            }
        }
        out.append(try ss.encryptAndHash(payload))
        step += 1
        return out
    }

    public func readMessage(_ message: Data) throws -> Data {
        guard !isComplete, !isMyTurn else { throw NoiseError.notOurTurn }
        var buf = Data(message)
        func take(_ n: Int) throws -> Data {
            guard buf.count >= n else { throw NoiseError.truncated }
            let d = Data(buf.prefix(n))
            buf = Data(buf.dropFirst(n))
            return d
        }
        for token in Self.patterns[step] {
            switch token {
            case .e:
                let pub = try take(KeyPair.keyLength)
                re = pub
                ss.mixHash(pub)
            case .s:
                let len = KeyPair.keyLength + (ss.hasKey ? Primitives.tagLength : 0)
                rs = try ss.decryptAndHash(try take(len))
            default:
                try dh(token)
            }
        }
        let payload = try ss.decryptAndHash(buf)
        step += 1
        return payload
    }

    public func split() throws -> HandshakeResult {
        guard isComplete, let remote = rs else { throw NoiseError.incomplete }
        let hash = ss.h
        let (c1, c2) = ss.split()
        if role == .initiator {
            return HandshakeResult(send: c1, receive: c2, handshakeHash: hash, remoteStatic: remote)
        }
        return HandshakeResult(send: c2, receive: c1, handshakeHash: hash, remoteStatic: remote)
    }

    private func dh(_ token: Token) throws {
        let initiator = role == .initiator
        func need(_ v: Data?) throws -> Data {
            guard let v else { throw NoiseError.invalidKey }
            return v
        }
        let shared: Data
        switch token {
        case .ee:
            shared = try Primitives.dh(try need(e?.privateKey), try need(re))
        case .es:
            shared = initiator
                ? try Primitives.dh(try need(e?.privateKey), try need(rs))
                : try Primitives.dh(localStatic.privateKey, try need(re))
        case .se:
            shared = initiator
                ? try Primitives.dh(localStatic.privateKey, try need(re))
                : try Primitives.dh(try need(e?.privateKey), try need(rs))
        default:
            throw NoiseError.invalidKey
        }
        ss.mixKey(shared)
    }
}
