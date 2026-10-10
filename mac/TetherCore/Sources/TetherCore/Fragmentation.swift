//
// Fragmentation.swift — records <-> BLE-sized fragments.
//
// Layout (big-endian), adapted from bitchat's fragment header for a single link:
//   u8 version (1) | u16 recordId | u16 index | u16 total | data
// See docs/PROTOCOL.md §2.
//

import Foundation

public enum Fragmentation {
    public static let version: UInt8 = 1
    public static let headerLength = 7
    public static let minFragment = 20
    public static let maxRecord = 128 * 1024
    public static let maxFragments = 0xFFFF
    /// Largest fragment ever put on the air: a GATT attribute value is at most 512 bytes and
    /// Android 13+ refuses longer writes even with MTU 517 (mirrors Fragmentation.kt).
    public static let maxBleFragment = 512
}

public enum FragmentError: Error { case fragmentTooSmall, recordTooLarge, tooManyFragments }

public final class Fragmenter {
    private var nextId: UInt16 = 0

    public init() {}

    public func fragment(_ record: Data, maxFragment: Int) throws -> [Data] {
        guard maxFragment >= Fragmentation.minFragment else { throw FragmentError.fragmentTooSmall }
        guard record.count <= Fragmentation.maxRecord else { throw FragmentError.recordTooLarge }
        let chunk = maxFragment - Fragmentation.headerLength
        let total = max(1, (record.count + chunk - 1) / chunk)
        guard total <= Fragmentation.maxFragments else { throw FragmentError.tooManyFragments }
        let id = nextId
        nextId &+= 1
        let bytes = [UInt8](record)
        return (0..<total).map { index in
            let start = index * chunk
            let end = min(bytes.count, start + chunk)
            var out = Data(capacity: Fragmentation.headerLength + end - start)
            out.append(Fragmentation.version)
            out.appendU16(id)
            out.appendU16(UInt16(index))
            out.appendU16(UInt16(total))
            out.append(contentsOf: bytes[start..<end])
            return out
        }
    }
}

public final class Reassembler {
    private let maxRecord: Int
    private var currentId: Int = -1
    private var expectedIndex = 0
    private var total = 0
    private var buffer = Data()
    public private(set) var dropped = 0

    public init(maxRecord: Int = Fragmentation.maxRecord) {
        self.maxRecord = maxRecord
    }

    /// Feed one fragment; returns a complete record or nil. Never throws: bad input is counted.
    public func accept(_ fragment: Data) -> Data? {
        let f = [UInt8](fragment)
        guard f.count >= Fragmentation.headerLength, f[0] == Fragmentation.version else { return drop() }
        let id = Int(f[1]) << 8 | Int(f[2])
        let index = Int(f[3]) << 8 | Int(f[4])
        let count = Int(f[5]) << 8 | Int(f[6])
        guard count > 0, index < count else { return drop() }
        if index == 0 {
            if currentId != -1 { dropped += 1 }
            reset()
            currentId = id
            total = count
        } else if id != currentId || index != expectedIndex || count != total {
            return drop()
        }
        let dataLen = f.count - Fragmentation.headerLength
        guard buffer.count + dataLen <= maxRecord else { return drop() }
        buffer.append(contentsOf: f[Fragmentation.headerLength...])
        expectedIndex = index + 1
        guard expectedIndex >= total else { return nil }
        let record = buffer
        reset()
        return record
    }

    public func reset() {
        currentId = -1
        expectedIndex = 0
        total = 0
        buffer = Data()
    }

    private func drop() -> Data? {
        dropped += 1
        reset()
        return nil
    }
}

extension Data {
    mutating func appendU16(_ v: UInt16) {
        append(UInt8(v >> 8))
        append(UInt8(v & 0xFF))
    }

    mutating func appendU32(_ v: UInt32) {
        append(UInt8((v >> 24) & 0xFF))
        append(UInt8((v >> 16) & 0xFF))
        append(UInt8((v >> 8) & 0xFF))
        append(UInt8(v & 0xFF))
    }
}
