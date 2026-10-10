import Foundation
import TetherCore
import UniformTypeIdentifiers

/// Outgoing files are copied into Application Support first so a transfer can resume after a
/// reconnect or relaunch even if the original moves. Incoming files are written to a private
/// partial file and moved into the download folder only after their SHA-256 matches.
final class MacFiles: OutgoingFiles, IncomingFiles {
    private let outDir = Paths.sub("outgoing")
    private let inDir = Paths.sub("incoming")
    private var readers: [String: FileHandle] = [:]
    private var writers: [String: FileHandle] = [:]
    var downloadFolder: () -> URL
    /// Where automatic screenshots/photos go (0.2.0); defaults to the download folder.
    var mediaFolder: (() -> URL)?
    /// Phone files the user dragged to a Finder folder (or chose a folder for): file name -> folder.
    var fsDestinations: [String: URL] = [:]

    struct IOError: Error, CustomStringConvertible {
        let description: String
    }

    init(downloadFolder: @escaping () -> URL) {
        self.downloadFolder = downloadFolder
    }

    // MARK: outgoing

    /// Copy `url` into staging, hashing as we go, and build a QUEUED outgoing record.
    static func stage(_ url: URL, now: Int64, kind: String? = nil) throws -> TransferRecord {
        let id = UUID().uuidString.lowercased()
        let target = Paths.sub("outgoing").appendingPathComponent(id)
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let input = try? FileHandle(forReadingFrom: url) else { throw IOError(description: "can't open file") }
        defer { try? input.close() }
        FileManager.default.createFile(atPath: target.path, contents: nil)
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        var sha = StreamingSHA256()
        var size: Int64 = 0
        while true {
            let chunk = input.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            sha.update(chunk)
            output.write(chunk)
            size += Int64(chunk.count)
        }
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        return TransferRecord(
            id: id, direction: .OUT, name: TransferEngine.safeFileName(url.lastPathComponent), size: size,
            sha256: sha.finalizeHex(), mime: mime, status: .QUEUED, createdAt: now, ref: target.path, kind: kind
        )
    }

    func read(_ record: TransferRecord, offset: Int64, length: Int) throws -> Data {
        guard let path = record.ref else { throw IOError(description: "no staged copy") }
        let h: FileHandle
        if let existing = readers[record.id] {
            h = existing
        } else {
            guard let opened = FileHandle(forReadingAtPath: path) else { throw IOError(description: "staged copy missing") }
            readers[record.id] = opened
            h = opened
        }
        try h.seek(toOffset: UInt64(offset))
        let data = h.readData(ofLength: length)
        guard data.count == length else { throw IOError(description: "short read") }
        return data
    }

    func release(_ record: TransferRecord) {
        try? readers.removeValue(forKey: record.id)?.close()
        if let path = record.ref, URL(fileURLWithPath: path).deletingLastPathComponent().standardized == outDir.standardized {
            try? FileManager.default.removeItem(atPath: path)
        }
    }

    // MARK: incoming

    private func partial(_ id: String) -> URL { inDir.appendingPathComponent("\(id).part") }

    func partialLength(_ id: String) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: partial(id).path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    func begin(_ record: TransferRecord) throws {
        let free = (try? inDir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage ?? Int64.max
        guard free > record.size + 50 * 1_048_576 else { throw IOError(description: "not enough space") }
        try? writers.removeValue(forKey: record.id)?.close()
        try? FileManager.default.removeItem(at: partial(record.id))
        guard FileManager.default.createFile(atPath: partial(record.id).path, contents: nil) else {
            throw IOError(description: "can't create file")
        }
    }

    func append(_ id: String, _ data: Data) throws {
        let h: FileHandle
        if let existing = writers[id] {
            h = existing
        } else {
            h = try FileHandle(forWritingTo: partial(id))
            h.seekToEndOfFile()
            writers[id] = h
        }
        h.write(data)
    }

    func finish(_ record: TransferRecord) throws -> String {
        if let h = writers.removeValue(forKey: record.id) {
            try? h.synchronize()
            try? h.close()
        }
        let src = partial(record.id)
        guard let input = FileHandle(forReadingAtPath: src.path) else { throw IOError(description: "partial missing") }
        var sha = StreamingSHA256()
        while true {
            let chunk = input.readData(ofLength: 1 << 20)
            if chunk.isEmpty { break }
            sha.update(chunk)
        }
        try? input.close()
        guard sha.finalizeHex() == record.sha256 else { throw IntegrityError(message: "checksum mismatch") }
        let folder: URL
        switch record.kind {
        case let k where PhoneKinds.isMedia(k): folder = mediaFolder?() ?? downloadFolder()
        case FileKinds5.clip: folder = Paths.sub("clips")
        case FileKinds5.fs: folder = fsDestinations.removeValue(forKey: record.name) ?? downloadFolder()
        default: folder = downloadFolder()
        }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let dest = Self.unique(folder.appendingPathComponent(record.name))
        try FileManager.default.moveItem(at: src, to: dest)
        Log.info("file.saved", ["size": "\(record.size)"])
        return dest.path
    }

    func discard(_ id: String) {
        try? writers.removeValue(forKey: id)?.close()
        try? FileManager.default.removeItem(at: partial(id))
    }

    /// Remove staging and partial files that no record refers to (older than a week).
    func sweep(live: Set<String>) {
        let cutoff = Date().addingTimeInterval(-7 * 24 * 3600)
        for dir in [inDir, outDir] {
            let fm = FileManager.default
            let items = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
            for url in items {
                let id = url.lastPathComponent.replacingOccurrences(of: ".part", with: "")
                let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
                if !live.contains(id) && modified < cutoff { try? FileManager.default.removeItem(at: url) }
            }
        }
    }

    static func unique(_ url: URL) -> URL {
        var candidate = url
        var n = 1
        let base = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            candidate = url.deletingLastPathComponent().appendingPathComponent(name)
            n += 1
        }
        return candidate
    }
}
