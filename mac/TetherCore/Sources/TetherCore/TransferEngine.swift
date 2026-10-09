//
// TransferEngine.swift — chunked, resumable, verified file transfer (mirrors TransferEngine.kt).
//

import Foundation

public enum Direction: String, Codable { case OUT, IN }
public enum Transport: String, Codable { case BLE, WIFI }

public enum TransferStatus: String, Codable {
    case QUEUED, OFFERED, ACTIVE, WAITING, VERIFYING, DONE, FAILED, CANCELLED

    public var isFinal: Bool { self == .DONE || self == .FAILED || self == .CANCELLED }
}

/// A way to send messages to the peer.
public struct Channel {
    public let transport: Transport
    public let send: (Message) -> Void

    public init(transport: Transport, send: @escaping (Message) -> Void) {
        self.transport = transport
        self.send = send
    }
}

/// Persisted state of one transfer (same JSON keys as the Kotlin TransferRecord).
public struct TransferRecord: Codable, Equatable, Identifiable {
    public var id: String
    public var direction: Direction
    public var name: String
    public var size: Int64
    public var sha256: String
    public var mime: String
    public var status: TransferStatus
    public var transferred: Int64 = 0
    public var error: String?
    public var createdAt: Int64
    public var updatedAt: Int64
    /// OUT: path of the staged copy. IN: final location once DONE.
    public var ref: String?
    public var retries: Int = 0
    public var via: Transport?

    public init(id: String, direction: Direction, name: String, size: Int64, sha256: String, mime: String,
                status: TransferStatus, transferred: Int64 = 0, error: String? = nil, createdAt: Int64,
                updatedAt: Int64? = nil, ref: String? = nil, retries: Int = 0, via: Transport? = nil) {
        self.id = id
        self.direction = direction
        self.name = name
        self.size = size
        self.sha256 = sha256
        self.mime = mime
        self.status = status
        self.transferred = transferred
        self.error = error
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.ref = ref
        self.retries = retries
        self.via = via
    }

    public var progress: Double { size <= 0 ? 1 : min(1, max(0, Double(transferred) / Double(size))) }
}

public struct IntegrityError: Error { public let message: String }

public protocol OutgoingFiles: AnyObject {
    func read(_ record: TransferRecord, offset: Int64, length: Int) throws -> Data
    func release(_ record: TransferRecord)
}

public protocol IncomingFiles: AnyObject {
    func partialLength(_ id: String) -> Int64
    func begin(_ record: TransferRecord) throws
    func append(_ id: String, _ data: Data) throws
    /// Verify against record.sha256 and move into place; throws IntegrityError on mismatch.
    func finish(_ record: TransferRecord) throws -> String
    func discard(_ id: String)
}

public protocol TransferListener: AnyObject {
    func transferUpdated(_ record: TransferRecord, important: Bool)
    func transferRemoved(_ id: String)
}

// swiftlint:disable:next type_body_length
public final class TransferEngine {
    public static let defaultWindow = 4
    public static let wifiWindow = 16
    public static let maxIntegrityRetries = 1
    public static let waitingForWifi = "Waiting for Wi-Fi (too large for Bluetooth)"

    private let outgoing: OutgoingFiles
    private let incoming: IncomingFiles
    private weak var listener: TransferListener?
    private let clock: () -> Int64
    private let maxIncoming: Int64
    private let maxOverBle: Int64

    private var order: [String] = []
    private var records: [String: TransferRecord] = [:]
    private var channel: Channel?
    private var activeOut: String?
    private var nextOffset: Int64 = 0
    private var acked: Int64 = 0
    private var doneSent = false
    private var received: [String: Int64] = [:]

    public init(outgoing: OutgoingFiles, incoming: IncomingFiles, listener: TransferListener,
                clock: @escaping () -> Int64, maxIncoming: Int64 = TetherProtocol.fileMaxBytesWifi,
                maxOverBle: Int64 = TetherProtocol.fileMaxBytes) {
        self.outgoing = outgoing
        self.incoming = incoming
        self.listener = listener
        self.clock = clock
        self.maxIncoming = maxIncoming
        self.maxOverBle = maxOverBle
    }

    public var all: [TransferRecord] { order.compactMap { records[$0] } }
    public subscript(id: String) -> TransferRecord? { records[id] }
    public var isConnected: Bool { channel != nil }
    public var transport: Transport? { channel?.transport }

    private var chunkSize: Int { channel?.transport == .WIFI ? TetherProtocol.fileChunkWifi : TetherProtocol.fileChunk }
    private var windowChunks: Int { channel?.transport == .WIFI ? Self.wifiWindow : Self.defaultWindow }

    public func restore(_ saved: [TransferRecord]) {
        for var r in saved {
            if [.OFFERED, .ACTIVE, .VERIFYING].contains(r.status) { r.status = .WAITING }
            if r.direction == .IN && !r.status.isFinal { r.transferred = incoming.partialLength(r.id) }
            if records[r.id] == nil { order.append(r.id) }
            records[r.id] = r
        }
    }

    public func enqueue(_ record: TransferRecord) {
        precondition(record.direction == .OUT)
        var r = record
        r.status = .QUEUED
        r.transferred = 0
        put(r, important: true)
        startNextOutgoing()
    }

    public func onConnected(_ channel: Channel) {
        self.channel = channel
        startNextOutgoing()
    }

    public func switchTo(_ channel: Channel) {
        onDisconnected()
        onConnected(channel)
    }

    public func onDisconnected() {
        channel = nil
        activeOut = nil
        for id in order {
            guard var r = records[id], [.OFFERED, .ACTIVE, .VERIFYING].contains(r.status) else { continue }
            r.status = .WAITING
            put(r, important: true)
        }
    }

    public func cancel(_ id: String) {
        guard let r = records[id], !r.status.isFinal else { return }
        channel?.send(.fileCancel(id: id, reason: "user"))
        finishCancelled(r, why: "Cancelled")
    }

    public func retry(_ id: String) {
        guard let r = records[id], r.direction == .OUT, r.status == .FAILED else { return }
        records[id] = nil
        order.removeAll { $0 == id }
        listener?.transferRemoved(id)
        var again = r
        again.id = UUID().uuidString.lowercased()
        again.status = .QUEUED
        again.transferred = 0
        again.error = nil
        again.retries = 0
        again.createdAt = clock()
        put(again, important: true)
        startNextOutgoing()
    }

    public func remove(_ id: String) {
        guard let r = records[id] else { return }
        if !r.status.isFinal { cancel(id) }
        if r.direction == .OUT { outgoing.release(r) }
        records[id] = nil
        order.removeAll { $0 == id }
        listener?.transferRemoved(id)
    }

    /// Route a file message that arrived on `from`. Returns false for non-file messages.
    @discardableResult
    public func handle(_ message: Message, from: Channel) -> Bool {
        switch message {
        case .fileOffer(let o): onOffer(o, from)
        case .fileAccept(let id, let offset): onAccept(id, offset)
        case .fileReject(let id, let reason): onReject(id, reason)
        case .fileChunk(let id, let offset, let data): onChunk(id, offset, data, from)
        case .fileAck(let id, let offset): onAck(id, offset)
        case .fileDone(let id): onDone(id, from)
        case .fileComplete(let id, let ok, let reason): onComplete(id, ok, reason)
        case .fileCancel(let id, let reason): onRemoteCancel(id, reason)
        default: return false
        }
        return true
    }

    // MARK: sender

    private func startNextOutgoing() {
        guard let ch = channel, activeOut == nil else { return }
        let pending = all.filter { $0.direction == .OUT && ($0.status == .WAITING || $0.status == .QUEUED) }
        let next = pending.first { ch.transport == .WIFI || $0.size <= maxOverBle }
        for var r in pending where r.id != next?.id && ch.transport == .BLE && r.size > maxOverBle
            && r.error != Self.waitingForWifi {
            r.status = .WAITING
            r.error = Self.waitingForWifi
            put(r, important: true)
        }
        guard var n = next else { return }
        activeOut = n.id
        nextOffset = 0
        acked = 0
        doneSent = false
        n.status = .OFFERED
        n.via = ch.transport
        n.error = nil
        put(n, important: true)
        ch.send(.fileOffer(FileOfferPayload(id: n.id, name: n.name, size: n.size, sha256: n.sha256, mime: n.mime)))
    }

    private func onAccept(_ id: String, _ offset: Int64) {
        guard var r = records[id], r.direction == .OUT, !r.status.isFinal, activeOut == id else { return }
        guard offset >= 0, offset <= r.size else { return failOut(r, "Peer asked for an invalid offset", notify: true) }
        nextOffset = offset
        acked = offset
        doneSent = false
        r.status = .ACTIVE
        r.transferred = offset
        put(r, important: true)
        pump()
    }

    private func onAck(_ id: String, _ offset: Int64) {
        guard var r = records[id], r.direction == .OUT, activeOut == id, !r.status.isFinal else { return }
        if offset > acked && offset <= nextOffset {
            acked = offset
            r.transferred = acked
            put(r, important: false)
        }
        pump()
    }

    private func pump() {
        guard let ch = channel, let id = activeOut, var r = records[id], r.status == .ACTIVE else { return }
        let window = Int64(chunkSize * windowChunks)
        while nextOffset < r.size && nextOffset - acked < window {
            let len = Int(min(Int64(chunkSize), r.size - nextOffset))
            let data: Data
            do {
                data = try outgoing.read(r, offset: nextOffset, length: len)
            } catch {
                ch.send(.fileCancel(id: id, reason: "source"))
                return failOut(r, "Couldn't read the file", notify: false)
            }
            guard data.count == len else {
                ch.send(.fileCancel(id: id, reason: "source"))
                return failOut(r, "The file changed while sending", notify: false)
            }
            ch.send(.fileChunk(id: id, offset: nextOffset, data: data))
            nextOffset += Int64(len)
        }
        if nextOffset >= r.size && !doneSent {
            doneSent = true
            r.status = .VERIFYING
            put(r, important: true)
            ch.send(.fileDone(id: id))
        }
    }

    private func onComplete(_ id: String, _ ok: Bool, _ reason: String?) {
        guard var r = records[id], r.direction == .OUT, !r.status.isFinal else { return }
        activeOut = nil
        if ok {
            outgoing.release(r)
            r.status = .DONE
            r.transferred = r.size
            r.error = nil
            put(r, important: true)
        } else if reason == "hash" && r.retries < Self.maxIntegrityRetries {
            r.status = .QUEUED
            r.transferred = 0
            r.retries += 1
            put(r, important: true)
        } else {
            failOut(r, "The other device couldn't verify the file", notify: false)
        }
        startNextOutgoing()
    }

    private func onReject(_ id: String, _ reason: String) {
        guard let r = records[id], r.direction == .OUT, !r.status.isFinal else { return }
        let why: String
        switch reason {
        case "too_large": why = "Too large for the other device"
        case "space": why = "Not enough space on the other device"
        default: why = "Declined (\(reason))"
        }
        if activeOut == id { activeOut = nil }
        failOut(r, why, notify: false)
        startNextOutgoing()
    }

    private func failOut(_ record: TransferRecord, _ why: String, notify: Bool) {
        if notify { channel?.send(.fileCancel(id: record.id, reason: "error")) }
        if activeOut == record.id { activeOut = nil }
        var r = record
        r.status = .FAILED
        r.error = why
        put(r, important: true)
        startNextOutgoing()
    }

    // MARK: receiver

    private static let shaPattern = try? NSRegularExpression(pattern: "^[0-9a-fA-F]{64}$")

    private func onOffer(_ m: FileOfferPayload, _ from: Channel) {
        let tx = from.send
        if var existing = records[m.id], existing.direction == .IN {
            switch existing.status {
            case .DONE: return tx(.fileComplete(id: m.id, ok: true, reason: nil))
            case .CANCELLED: return tx(.fileCancel(id: m.id, reason: "cancelled"))
            default: break
            }
            if existing.status != .FAILED && existing.size == m.size && existing.sha256.lowercased() == m.sha256.lowercased() {
                let have = min(incoming.partialLength(m.id), m.size)
                if have == 0 {
                    do { try incoming.begin(existing) } catch {
                        existing.status = .FAILED
                        existing.error = "Couldn't save the file"
                        put(existing, important: true)
                        return tx(.fileReject(id: m.id, reason: "space"))
                    }
                }
                received[m.id] = have
                existing.status = .ACTIVE
                existing.transferred = have
                existing.error = nil
                existing.via = from.transport
                put(existing, important: true)
                return tx(.fileAccept(id: m.id, offset: have))
            }
            incoming.discard(m.id)
        }
        let now = clock()
        var record = TransferRecord(
            id: m.id, direction: .IN, name: TransferEngine.safeFileName(m.name), size: m.size,
            sha256: m.sha256.lowercased(), mime: m.mime, status: .ACTIVE, createdAt: now, via: from.transport
        )
        let validSha = Self.shaPattern?.firstMatch(in: m.sha256, range: NSRange(m.sha256.startIndex..., in: m.sha256)) != nil
        if m.size < 0 || m.size > maxIncoming || !validSha {
            record.status = .FAILED
            record.error = "Too large (limit \(maxIncoming / 1_048_576) MB)"
            put(record, important: true)
            return tx(.fileReject(id: m.id, reason: m.size > maxIncoming ? "too_large" : "invalid"))
        }
        do {
            try incoming.begin(record)
        } catch {
            record.status = .FAILED
            record.error = "Couldn't save the file"
            put(record, important: true)
            return tx(.fileReject(id: m.id, reason: "space"))
        }
        received[m.id] = 0
        put(record, important: true)
        tx(.fileAccept(id: m.id, offset: 0))
    }

    private func onChunk(_ id: String, _ offset: Int64, _ data: Data, _ from: Channel) {
        let tx = from.send
        guard var r = records[id], r.direction == .IN, r.status == .ACTIVE else {
            if records[id] == nil { tx(.fileCancel(id: id, reason: "unknown")) }
            return
        }
        let have = received[id] ?? incoming.partialLength(id)
        let end = offset + Int64(data.count)
        if end > r.size { return failIn(r, "Peer sent more data than offered") }
        if offset > have { return tx(.fileAccept(id: id, offset: have)) }
        if end > have {
            do {
                let skip = Int(have - offset)
                try incoming.append(id, skip == 0 ? data : data.subdata(in: (data.startIndex + skip)..<data.endIndex))
                received[id] = end
            } catch {
                return failIn(r, "Couldn't write the file")
            }
        }
        let now = received[id] ?? have
        r.transferred = now
        put(r, important: false)
        tx(.fileAck(id: id, offset: now))
    }

    private func onDone(_ id: String, _ from: Channel) {
        let tx = from.send
        guard var r = records[id], r.direction == .IN, r.status == .ACTIVE else { return }
        let have = received[id] ?? incoming.partialLength(id)
        if have != r.size { return tx(.fileAccept(id: id, offset: have)) }
        r.status = .VERIFYING
        r.transferred = have
        put(r, important: true)
        do {
            let where_ = try incoming.finish(r)
            received[id] = nil
            r.status = .DONE
            r.transferred = r.size
            r.ref = where_
            r.error = nil
            put(r, important: true)
            tx(.fileComplete(id: id, ok: true, reason: nil))
        } catch let e as IntegrityError {
            incoming.discard(id)
            received[id] = nil
            r.status = .WAITING
            r.transferred = 0
            r.error = e.message
            put(r, important: true)
            tx(.fileComplete(id: id, ok: false, reason: "hash"))
        } catch {
            failIn(r, "Couldn't save the file")
        }
    }

    private func failIn(_ record: TransferRecord, _ why: String) {
        channel?.send(.fileCancel(id: record.id, reason: "error"))
        incoming.discard(record.id)
        received[record.id] = nil
        var r = record
        r.status = .FAILED
        r.error = why
        put(r, important: true)
    }

    // MARK: shared

    private func onRemoteCancel(_ id: String, _ reason: String) {
        guard let r = records[id], !r.status.isFinal else { return }
        let why = reason == "user" ? "Cancelled on the other device" : "Stopped by the other device (\(reason))"
        finishCancelled(r, why: why)
    }

    private func finishCancelled(_ record: TransferRecord, why: String) {
        if record.direction == .OUT {
            if activeOut == record.id { activeOut = nil }
            outgoing.release(record)
        } else {
            incoming.discard(record.id)
            received[record.id] = nil
        }
        var r = record
        r.status = .CANCELLED
        r.error = why
        put(r, important: true)
        startNextOutgoing()
    }

    private func put(_ record: TransferRecord, important: Bool) {
        var r = record
        r.updatedAt = clock()
        if records[r.id] == nil { order.append(r.id) }
        records[r.id] = r
        listener?.transferUpdated(r, important: important)
    }

    /// Strip paths, control and reserved characters; never empty, never hidden (mirrors Kotlin).
    public static func safeFileName(_ raw: String) -> String {
        let base = raw.split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? raw
        let base2 = base.split(separator: "\\", omittingEmptySubsequences: false).last.map(String.init) ?? base
        let unsafe = Set("/\\:*?\"<>|")
        var s = String(base2.unicodeScalars.map { sc -> Character in
            if sc.value < 0x20 || sc.value == 0x7f || unsafe.contains(Character(sc)) { return "_" }
            return Character(sc)
        })
        s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasPrefix(".") { s.removeFirst() }
        if s.count > 180 {
            var ext = ""
            if let dot = s.lastIndex(of: "."), dot > s.startIndex, s.distance(from: dot, to: s.endIndex) <= 16 {
                ext = String(s[dot...])
            }
            s = String(s.prefix(180 - ext.count)) + ext
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "file" : s
    }
}
