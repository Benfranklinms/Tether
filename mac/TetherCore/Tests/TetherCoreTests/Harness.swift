import Foundation
@testable import TetherCore
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

enum Vectors {
    static var dir: URL {
        // Tests/TetherCoreTests/Harness.swift -> repo root/protocol/vectors
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol/vectors")
    }

    static func load(_ name: String) throws -> Any {
        try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent(name)))
    }

    static func array(_ name: String) throws -> [[String: Any]] {
        try load(name) as? [[String: Any]] ?? []
    }
}

func hex(_ s: Any?) -> Data { Hex.decode(s as? String ?? "") ?? Data() }

/// Serial event loop driven by the tests.
final class Loop {
    private var q: [() -> Void] = []
    var now: Int64 = 1_000_000
    func execute(_ f: @escaping () -> Void) { q.append(f) }

    @discardableResult
    func drain(limit: Int = 5_000_000) -> Int {
        var n = 0
        while !q.isEmpty {
            let f = q.removeFirst()
            f()
            n += 1
            precondition(n <= limit, "loop did not settle")
        }
        return n
    }

    func step(_ n: Int) {
        for _ in 0..<n {
            guard !q.isEmpty else { return }
            q.removeFirst()()
        }
    }
}

final class MemIdentity: IdentityStore {
    var localKey = KeyPair.generate()
    var pendingLocalKey: KeyPair?
    var peer: PeerRecord?
}

final class MemFiles: OutgoingFiles, IncomingFiles {
    var sources: [String: Data] = [:]
    var partial: [String: Data] = [:]
    var finished: [String: Data] = [:]
    var released: Set<String> = []
    var corruptNextFinish = false
    var failReads = false

    struct IOErr: Error {}

    func read(_ record: TransferRecord, offset: Int64, length: Int) throws -> Data {
        if failReads { throw IOErr() }
        guard let src = sources[record.ref ?? record.id] else { throw IOErr() }
        return src.subdata(in: Int(offset)..<(Int(offset) + length))
    }

    func release(_ record: TransferRecord) { released.insert(record.id) }
    func partialLength(_ id: String) -> Int64 { Int64(partial[id]?.count ?? 0) }
    func begin(_ record: TransferRecord) throws { partial[record.id] = Data() }

    func append(_ id: String, _ data: Data) throws {
        guard partial[id] != nil else { throw IOErr() }
        partial[id]!.append(data)
    }

    func finish(_ record: TransferRecord) throws -> String {
        var bytes = partial[record.id] ?? Data()
        if corruptNextFinish && !bytes.isEmpty {
            corruptNextFinish = false
            bytes[bytes.startIndex] ^= 0xFF
        }
        guard sha256Hex(bytes) == record.sha256 else { throw IntegrityError(message: "hash mismatch") }
        finished[record.id] = bytes
        partial[record.id] = nil
        return "mem://\(record.name)"
    }

    func discard(_ id: String) { partial[id] = nil }

    func stage(_ name: String, _ bytes: Data, now: Int64) -> TransferRecord {
        let id = UUID().uuidString.lowercased()
        sources[id] = bytes
        return TransferRecord(id: id, direction: .OUT, name: name, size: Int64(bytes.count), sha256: sha256Hex(bytes),
                              mime: "application/octet-stream", status: .QUEUED, createdAt: now, ref: id)
    }
}

final class Side: ControllerListener {
    let name: String
    let role: Role
    let loop: Loop
    let identity = MemIdentity()
    let files = MemFiles()
    var states: [LinkState] = []
    var messages: [Message] = []
    var closes: [(CloseReason, Bool)] = []
    var transfers: [String: TransferRecord] = [:]
    var wifiOffers: [WifiOfferPayload] = []
    var unpairedByRemote: Bool?
    var rotated = 0
    /// What this side announces; Caps.v1 plays a 0.1.x build.
    var caps = Caps.current
    var controller: TetherController!

    init(_ name: String, _ role: Role, _ loop: Loop) {
        self.name = name
        self.role = role
        self.loop = loop
        controller = TetherController(
            role: role, identity: identity,
            localHello: { [unowned self] in
                Hello(name: name, platform: role == .initiator ? "android" : "macos", app: "test", caps: self.caps)
            },
            outgoing: files, incoming: files, listener: self, clock: { loop.now }
        )
    }

    var state: LinkState { controller.state }

    func controllerState(_ state: LinkState) { states.append(state) }
    func controllerMessage(_ message: Message) { messages.append(message) }
    func controllerTransfer(_ record: TransferRecord, important: Bool) { transfers[record.id] = record }
    func controllerTransferRemoved(_ id: String) { transfers[id] = nil }
    func controllerPaired(_ peer: PeerRecord) {}
    func controllerUnpaired(byRemote: Bool) { unpairedByRemote = byRemote }
    func controllerClosed(reason: CloseReason, byRemote: Bool, wifi: Bool) { closes.append((reason, byRemote)) }
    func controllerWifiOffer(_ offer: WifiOfferPayload) { wifiOffers.append(offer) }
    func controllerWifiRevoked() {}
    func controllerKeyRotated() { rotated += 1 }
    var undecodable: [(UInt8, String)] = []
    func controllerUndecodable(type: UInt8, reason: String) { undecodable.append((type, reason)) }
}

final class LoopbackBle {
    let loop: Loop
    let phone: Side
    let mac: Side
    let mtu: Int
    private(set) var up = false
    var fragmentsSent = 0
    var cutAfter: Int?
    /// Silently lose the next N fragments towards the Mac / the phone.
    var dropToMac = 0
    var dropToPhone = 0
    /// Largest fragment written towards the Mac / the phone.
    private(set) var maxToMac = 0
    private(set) var maxToPhone = 0
    private var generation = 0

    init(_ loop: Loop, _ phone: Side, _ mac: Side, mtu: Int = 185) {
        self.loop = loop
        self.phone = phone
        self.mac = mac
        self.mtu = mtu
    }

    func connect(phonePairing: PairingContext = .closed, macPairing: PairingContext = .closed) {
        up = true
        generation += 1
        let gen = generation
        mac.controller.bleConnected(maxFragment: mtu, writer: { f in self.deliver(gen, f, toMac: false) },
                                    pairing: macPairing)
        phone.controller.bleConnected(maxFragment: mtu, writer: { f in self.deliver(gen, f, toMac: true) },
                                      pairing: phonePairing)
    }

    private func deliver(_ gen: Int, _ fragment: Data, toMac: Bool) {
        guard up, gen == generation else { return }
        fragmentsSent += 1
        if toMac { maxToMac = max(maxToMac, fragment.count) } else { maxToPhone = max(maxToPhone, fragment.count) }
        if toMac && dropToMac > 0 {
            dropToMac -= 1
            return
        }
        if !toMac && dropToPhone > 0 {
            dropToPhone -= 1
            return
        }
        if let left = cutAfter {
            if left <= 0 {
                loop.execute { self.disconnect() }
                return
            }
            cutAfter = left - 1
        }
        loop.execute {
            guard self.up, gen == self.generation else { return }
            if toMac { self.mac.controller.bleFragment(fragment) } else { self.phone.controller.bleFragment(fragment) }
        }
    }

    func disconnect() {
        guard up else { return }
        up = false
        cutAfter = nil
        phone.controller.bleDisconnected()
        mac.controller.bleDisconnected()
    }
}

final class LoopbackWifi {
    let loop: Loop
    let phone: Side
    let mac: Side
    private(set) var up = false
    var cutAfter: Int?
    private var generation = 0

    init(_ loop: Loop, _ phone: Side, _ mac: Side) {
        self.loop = loop
        self.phone = phone
        self.mac = mac
    }

    func connect(token: String) {
        up = true
        generation += 1
        let gen = generation
        mac.controller.wifiConnected(writer: { r in self.deliver(gen, r, toMac: false) }, token: token)
        phone.controller.wifiConnected(writer: { r in self.deliver(gen, r, toMac: true) }, token: nil)
    }

    private func deliver(_ gen: Int, _ record: Data, toMac: Bool) {
        guard up, gen == generation else { return }
        if let left = cutAfter {
            if left <= 0 {
                loop.execute { self.disconnect() }
                return
            }
            cutAfter = left - 1
        }
        loop.execute {
            guard self.up, gen == self.generation else { return }
            if toMac { self.mac.controller.wifiRecord(record) } else { self.phone.controller.wifiRecord(record) }
        }
    }

    func disconnect() {
        guard up else { return }
        up = false
        cutAfter = nil
        phone.controller.wifiDisconnected()
        mac.controller.wifiDisconnected()
    }
}

func pairedSides(_ loop: Loop) -> (Side, Side) {
    let phone = Side("Pixel", .initiator, loop)
    let mac = Side("MacBook", .responder, loop)
    phone.identity.peer = PeerRecord.paired(name: "MacBook", platform: "macos", key: mac.identity.localKey.publicKey, now: 0)
    mac.identity.peer = PeerRecord.paired(name: "Pixel", platform: "android", key: phone.identity.localKey.publicKey, now: 0)
    return (phone, mac)
}

func randomData(_ n: Int, seed: UInt64) -> Data {
    var x = seed &* 6364136223846793005 &+ 1442695040888963407
    var d = Data(count: n)
    for i in 0..<n {
        x = x &* 6364136223846793005 &+ 1442695040888963407
        d[i] = UInt8(truncatingIfNeeded: x >> 33)
    }
    return d
}
