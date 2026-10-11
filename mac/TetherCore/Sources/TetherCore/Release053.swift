//
// Release053.swift — 0.5.3: pure rules behind the fixes for Ben's 0.5.2 device report. Mirrors
// android/core/.../features/Release053.kt rule for rule; shared vectors in protocol/vectors/.
//

import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

// MARK: - Friendly file kinds

/// "Word document", "PDF", "JPEG image" instead of raw MIME types.
public enum FileKinds {
    private static let byExt: [String: String] = [
        "pdf": "PDF",
        "doc": "Word document", "docx": "Word document", "dot": "Word template", "dotx": "Word template",
        "xls": "Excel spreadsheet", "xlsx": "Excel spreadsheet", "csv": "CSV spreadsheet",
        "ppt": "PowerPoint presentation", "pptx": "PowerPoint presentation",
        "pages": "Pages document", "numbers": "Numbers spreadsheet", "key": "Keynote presentation",
        "odt": "OpenDocument text", "ods": "OpenDocument spreadsheet", "odp": "OpenDocument presentation",
        "rtf": "Rich text document", "txt": "Plain text", "md": "Markdown text", "json": "JSON file",
        "xml": "XML file", "html": "Web page", "htm": "Web page", "epub": "eBook",
        "jpg": "JPEG image", "jpeg": "JPEG image", "png": "PNG image", "heic": "HEIC image", "heif": "HEIF image",
        "gif": "GIF image", "webp": "WebP image", "bmp": "BMP image", "tif": "TIFF image", "tiff": "TIFF image",
        "svg": "SVG image", "dng": "RAW image",
        "mp4": "MP4 video", "m4v": "MP4 video", "mov": "QuickTime video", "mkv": "MKV video", "webm": "WebM video",
        "3gp": "3GP video", "avi": "AVI video",
        "mp3": "MP3 audio", "m4a": "M4A audio", "aac": "AAC audio", "wav": "WAV audio", "flac": "FLAC audio",
        "ogg": "Ogg audio", "opus": "Opus audio",
        "zip": "ZIP archive", "rar": "RAR archive", "7z": "7-Zip archive", "gz": "GZip archive", "tar": "TAR archive",
        "apk": "Android app", "dmg": "Disk image", "iso": "Disk image", "pkg": "Installer package",
        "ics": "Calendar event", "vcf": "Contact card",
        "kt": "Kotlin source", "swift": "Swift source", "py": "Python script", "js": "JavaScript file",
        "java": "Java source", "c": "C source", "cpp": "C++ source", "sh": "Shell script",
    ]

    private static let byMime: [String: String] = [
        "application/pdf": "PDF",
        "application/msword": "Word document",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "Word document",
        "application/vnd.ms-excel": "Excel spreadsheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "Excel spreadsheet",
        "application/vnd.ms-powerpoint": "PowerPoint presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation": "PowerPoint presentation",
        "application/zip": "ZIP archive", "application/x-zip-compressed": "ZIP archive",
        "application/vnd.android.package-archive": "Android app", "application/json": "JSON file",
        "application/rtf": "Rich text document", "application/epub+zip": "eBook",
        "text/plain": "Plain text", "text/csv": "CSV spreadsheet", "text/html": "Web page", "text/markdown": "Markdown text",
        "image/jpeg": "JPEG image", "image/png": "PNG image", "image/heic": "HEIC image", "image/heif": "HEIF image",
        "image/gif": "GIF image", "image/webp": "WebP image", "image/svg+xml": "SVG image",
        "video/mp4": "MP4 video", "video/quicktime": "QuickTime video", "audio/mpeg": "MP3 audio", "audio/mp4": "M4A audio",
    ]

    private static let imageExts: Set<String> = ["jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp", "tif", "tiff", "dng"]

    public static func ext(_ name: String?) -> String {
        let n = (name ?? "").split(separator: "/", omittingEmptySubsequences: false).last.map(String.init) ?? ""
        guard let dot = n.lastIndex(of: "."), dot > n.startIndex, n.index(after: dot) < n.endIndex else { return "" }
        return n[n.index(after: dot)...].lowercased()
    }

    /// A short human kind. The extension wins (MIME is often application/octet-stream), then MIME, then a family.
    public static func describe(mime: String?, name: String?) -> String {
        let e = ext(name)
        if let k = byExt[e] { return k }
        let m = (mime ?? "").lowercased().split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
        if let k = byMime[m] { return k }
        if m.hasPrefix("image/") { return "Image" }
        if m.hasPrefix("video/") { return "Video" }
        if m.hasPrefix("audio/") { return "Audio" }
        if m.hasPrefix("text/") { return "Text document" }
        if !e.isEmpty && e.count <= 5 { return e.uppercased() + " file" }
        return "File"
    }

    /// Images we can thumbnail / put on the pasteboard as a picture (SVG excluded).
    public static func isImage(mime: String?, name: String?) -> Bool {
        if imageExts.contains(ext(name)) { return true }
        let m = (mime ?? "").lowercased()
        return m.hasPrefix("image/") && m != "image/svg+xml"
    }
}

// MARK: - Shelf search

public enum ShelfSearch {
    public static func fold(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX")).lowercased()
    }

    /// Words: runs of letters and digits ("forms.gle/x7" → forms, gle, x7).
    public static func words(_ s: String) -> [String] {
        fold(s).components(separatedBy: CharacterSet.letters.union(.decimalDigits).inverted).filter { !$0.isEmpty }
    }

    /// Every word of `query` must start a word of the title, content, file name, MIME or type (never
    /// the Wi-Fi password): "word" finds a Word document but not "password".
    public static func matches(_ item: ShelfItem, query: String) -> Bool {
        let wanted = words(query)
        if wanted.isEmpty { return true }
        let f = item.file
        let parts: [String?] = [
            item.title,
            item.kind != ShelfKinds.wifi && item.kind != ShelfKinds.file ? item.body : nil,
            f?.name, f?.mime, f.map { FileKinds.describe(mime: $0.mime, name: $0.name) },
            item.kind, kindWords(item.kind),
        ]
        let have = words(parts.compactMap { $0 }.joined(separator: " "))
        return wanted.allSatisfy { w in have.contains { $0.hasPrefix(w) } }
    }

    public static func filter(_ items: [ShelfItem], query: String) -> [ShelfItem] { items.filter { matches($0, query: query) } }

    private static func kindWords(_ kind: String) -> String {
        switch kind {
        case ShelfKinds.wifi: return "wi-fi wifi network password"
        case ShelfKinds.text: return "note text"
        case ShelfKinds.snippet: return "snippet code"
        default: return kind
        }
    }
}

// MARK: - Shelf rename

public enum ShelfRename {
    public static let max = ShelfDetect.titleMax

    /// The renamed item, or nil when `raw` is blank or nothing changes. A FILE item's file name
    /// follows the new title and keeps its extension ("Resume" on "cv.pdf" → "Resume.pdf").
    public static func rename(_ item: ShelfItem, to raw: String, now: Int64) -> ShelfItem? {
        let flat = raw.replacingOccurrences(of: "[\\r\\n\\t]+", with: " ", options: .regularExpression)
        let title = String(flat.trimmingCharacters(in: .whitespaces).prefix(max))
        guard !title.isEmpty else { return nil }
        var out = item
        if let f = item.file { out.file?.name = fileName(title: title, original: f.name) }
        guard title != item.title || out.file != item.file else { return nil }
        out.title = title
        out.updated = Swift.max(now, item.updated + 1)
        return out
    }

    public static func fileName(title: String, original: String) -> String {
        let e = FileKinds.ext(original)
        let base = TransferEngine.safeFileName(title.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "\\", with: "_"))
        if e.isEmpty || FileKinds.ext(base) == e { return base }
        return TransferEngine.safeFileName(base + "." + e)
    }
}

// MARK: - Shelf card text

/// Title and preview for a text-like Shelf card without saying the same thing twice. A snippet
/// whose title was taken from its own first line ("git rebase -i" for "git rebase -i HEAD~3")
/// shows that whole first line as the title and only the lines after it as the preview. A
/// renamed item (title not at the start of the body) is left alone. Mirrors Kotlin ShelfCardText.
public enum ShelfCardText {
    public struct Parts: Equatable {
        public let title: String
        public let preview: String
        public init(title: String, preview: String) {
            self.title = title
            self.preview = preview
        }
    }

    public static func split(title: String, body: String) -> Parts {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let lines = trimmed.components(separatedBy: "\n")
        let first = (lines.first ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !first.isEmpty, first.hasPrefix(t) else { return Parts(title: title, preview: trimmed) }
        let rest = lines.dropFirst().joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return Parts(title: first.count > t.count ? first : title, preview: rest)
    }
}

// MARK: - Shelf file cache

public enum ShelfCache {
    /// Files up to this size are copied to both devices.
    public static let maxBytes: Int64 = 100 * 1024 * 1024
    /// Over Bluetooth only small files are fetched in the background; the rest wait for Wi-Fi.
    public static let bleMax: Int64 = 2 * 1024 * 1024

    public enum State: Equatable { case local, fetching, remote, notDownloaded }

    public static func shouldFetch(_ file: ShelfFile, haveLocal: Bool, inFlight: Bool, connected: Bool, wifi: Bool) -> Bool {
        !haveLocal && !inFlight && connected && file.size >= 0 && file.size <= maxBytes && (wifi || file.size <= bleMax)
    }

    /// "Not downloaded yet" only when the copy is missing AND the other device isn't connected.
    public static func state(haveLocal: Bool, fetching: Bool, connected: Bool) -> State {
        if haveLocal { return .local }
        if fetching { return .fetching }
        return connected ? .remote : .notDownloaded
    }
}

/// Streaming AES-256-GCM for Shelf files at rest; same format as ChunkSeal in Release053.kt:
/// "TSF1" + 8-byte nonce prefix, then ≤64 KiB chunks sealed with nonce = prefix ‖ index (BE) and
/// AAD = 1 for the final chunk (the one with nothing after it), else 0; each + 16-byte tag.
public enum ChunkSeal {
    public static let chunk = 64 * 1024
    static let tag = 16
    static let magic = Data("TSF1".utf8)

    public struct Failure: Error { public let message: String }

    private static func nonce(_ prefix: Data, _ index: UInt32) throws -> AES.GCM.Nonce {
        var n = prefix
        n.append(contentsOf: [UInt8(index >> 24 & 0xff), UInt8(index >> 16 & 0xff), UInt8(index >> 8 & 0xff), UInt8(index & 0xff)])
        return try AES.GCM.Nonce(data: n)
    }

    /// `read(n)` returns up to n bytes (empty at the end); `write` takes each sealed piece.
    public static func encrypt(read: (Int) throws -> Data, write: (Data) throws -> Void, key: Data, prefix: Data? = nil) throws {
        let p = prefix ?? Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        guard key.count == 32, p.count == 8 else { throw Failure(message: "bad key") }
        let k = SymmetricKey(data: key)
        try write(magic + p)
        var index: UInt32 = 0
        var cur = try readFull(read, chunk)
        while true {
            let next = try readFull(read, chunk)
            let final = next.isEmpty
            let box = try AES.GCM.seal(cur, using: k, nonce: try nonce(p, index), authenticating: Data([final ? 1 : 0]))
            try write(box.ciphertext + box.tag)
            index += 1
            if final { break }
            cur = next
        }
    }

    public static func decrypt(read: (Int) throws -> Data, write: (Data) throws -> Void, key: Data) throws {
        let head = try readFull(read, 12)
        guard head.count == 12, head.prefix(4) == magic else { throw Failure(message: "not a sealed shelf file") }
        let p = Data(head.suffix(8))
        let k = SymmetricKey(data: key)
        var index: UInt32 = 0
        var pending = try readFull(read, chunk + tag)
        while true {
            guard pending.count >= tag else { throw Failure(message: "truncated") }
            let more = pending.count == chunk + tag ? try readFull(read, chunk + tag) : Data()
            let final = more.isEmpty
            let box = try AES.GCM.SealedBox(nonce: try nonce(p, index), ciphertext: pending.prefix(pending.count - tag),
                                            tag: pending.suffix(tag))
            try write(try AES.GCM.open(box, using: k, authenticating: Data([final ? 1 : 0])))
            index += 1
            if final {
                guard try read(1).isEmpty else { throw Failure(message: "trailing data") }
                break
            }
            pending = more
        }
    }

    public static func encrypt(_ plain: Data, key: Data, prefix: Data? = nil) throws -> Data {
        var src = plain[...]
        var out = Data()
        try encrypt(read: { n in let d = Data(src.prefix(n)); src = src.dropFirst(n); return d }, write: { out.append($0) },
                    key: key, prefix: prefix)
        return out
    }

    public static func decrypt(_ blob: Data, key: Data) throws -> Data {
        var src = blob[...]
        var out = Data()
        try decrypt(read: { n in let d = Data(src.prefix(n)); src = src.dropFirst(n); return d }, write: { out.append($0) }, key: key)
        return out
    }

    /// File to file, streaming (the source is never loaded whole).
    public static func encryptFile(_ src: URL, to dst: URL, key: Data) throws {
        let input = try FileHandle(forReadingFrom: src)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: dst.path, contents: nil)
        let output = try FileHandle(forWritingTo: dst)
        defer { try? output.close() }
        try encrypt(read: { input.readData(ofLength: $0) }, write: { output.write($0) }, key: key)
    }

    public static func decryptFile(_ src: URL, to dst: URL, key: Data) throws {
        let input = try FileHandle(forReadingFrom: src)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: dst.path, contents: nil)
        let output = try FileHandle(forWritingTo: dst)
        defer { try? output.close() }
        try decrypt(read: { input.readData(ofLength: $0) }, write: { output.write($0) }, key: key)
    }

    private static func readFull(_ read: (Int) throws -> Data, _ n: Int) throws -> Data {
        var d = Data()
        while d.count < n {
            let r = try read(n - d.count)
            if r.isEmpty { break }
            d.append(r)
        }
        return d
    }
}

// MARK: - Insert from Phone

public extension CaptureText {
    /// Card text for a request the phone answered: "open" with reason tap_phone means the phone
    /// couldn't open the camera by itself and posted a notification instead.
    static func line(kind: String, state: String, reason: String?) -> String {
        if state == CaptureStates.open, reason == CaptureReasons.tapPhone { return describe(CaptureReasons.tapPhone) }
        return status(kind: kind, state: state, reason: reason)
    }

    /// No answer at all within this long means the phone never got the request (or is too old).
    static let askTimeout: TimeInterval = 12
}

// MARK: - What's new

public enum WhatsNewPolicy {
    public enum Action: Equatable { case show, record, none }

    /// Builds before 0.5.2 never saved a seen version: a missing value on an app that was clearly
    /// used before (`usedBefore`: paired, settings on disk) is an update, not a fresh install.
    public static func decide(current: String, seen: String?, usedBefore: Bool) -> Action {
        guard let seen else { return usedBefore ? .show : .record }
        return Versions.compare(base(current), base(seen)) > 0 ? .show : .none
    }

    public static func base(_ v: String) -> String {
        String(v.split(separator: "-", maxSplits: 1).first ?? Substring(v)).components(separatedBy: "+")[0]
    }

    /// The `## <version>` section of CHANGELOG.md (bundled in both apps), without its heading.
    public static func section(_ changelog: String, version: String) -> String? {
        let want = base(version)
        let lines = changelog.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        guard let start = lines.firstIndex(where: {
            $0.hasPrefix("## ") && String($0.dropFirst(3)).trimmingCharacters(in: .whitespaces).split(separator: " ").first
                .map(String.init) == want
        }) else { return nil }
        let rest = lines[(start + 1)...]
        let end = rest.firstIndex { $0.hasPrefix("## ") || $0.hasPrefix("# ") } ?? rest.endIndex
        let text = rest[rest.startIndex..<end].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }
}

// MARK: - Transfers list

public enum TransferList {
    public static let homeMax = 3
    public static let collapseMs: Int64 = 24 * 3600 * 1000

    public enum Filter: String, CaseIterable { case all = "All", sent = "Sent", received = "Received" }

    public struct Section: Equatable {
        public let title: String
        public let items: [TransferRecord]
        public let collapsed: Bool
    }

    private static func touched(_ t: TransferRecord) -> Int64 { Swift.max(t.updatedAt, t.createdAt) }
    private static func newestFirst(_ l: [TransferRecord]) -> [TransferRecord] { l.sorted { touched($0) > touched($1) } }

    public static func stale(_ t: TransferRecord, now: Int64) -> Bool { t.status.isFinal && now - touched(t) > collapseMs }

    public static func home(_ list: [TransferRecord], now: Int64) -> [TransferRecord] {
        let live = list.filter { !stale($0, now: now) }
        return Array((newestFirst(live.filter { !$0.status.isFinal }) + newestFirst(live.filter { $0.status.isFinal })).prefix(homeMax))
    }

    public static func filter(_ list: [TransferRecord], _ f: Filter) -> [TransferRecord] {
        switch f {
        case .all: return list
        case .sent: return list.filter { $0.direction == .OUT }
        case .received: return list.filter { $0.direction == .IN }
        }
    }

    public static func sections(_ list: [TransferRecord], _ f: Filter, now: Int64, startOfToday: Int64) -> [Section] {
        let all = newestFirst(filter(list, f))
        let today = all.filter { touched($0) >= startOfToday || !$0.status.isFinal }
        let ids = Set(today.map(\.id))
        let earlier = all.filter { !ids.contains($0.id) }
        var out: [Section] = []
        if !today.isEmpty { out.append(Section(title: "Today", items: today, collapsed: false)) }
        if !earlier.isEmpty { out.append(Section(title: "Earlier", items: earlier, collapsed: earlier.allSatisfy { stale($0, now: now) })) }
        return out
    }
}

// MARK: - Continue on Mac: new photos

public enum MediaReady {
    public static func stable(pending: Bool, size: Int64, previousSize: Int64?) -> Bool { !pending && size > 0 && size == previousSize }

    /// A thumbnail that is one flat dark colour carries no picture (a frame captured before the
    /// file was written, or a FLAG_SECURE screen): show a placeholder instead. `luma` = 0…255 samples.
    public static func isBlank(_ luma: [Int]) -> Bool {
        guard let lo = luma.min(), let hi = luma.max() else { return true }
        let mean = Double(luma.reduce(0, +)) / Double(luma.count)
        return mean < 24 && hi - lo <= 12
    }
}
