//
// ShelfKeyFile.swift — 0.5.4: where the Mac keeps the 256-bit key that seals the Shelf at rest.
//
// 0.5.2–0.5.3 kept it in the login Keychain. Tether is ad-hoc signed, so every update has a new
// code signature and the item's access list no longer matches: macOS asked for the Keychain
// password after every update. The key now lives in a file only this user can read
// (Application Support/Tether/shelf.key, mode 0600 in a 0700 folder), like the rest of
// Tether's state, so updates never prompt. The old Keychain key is migrated once.
//

import Foundation

public enum ShelfKeyFile {
    public static let length = 32

    public enum Source: Equatable {
        /// The key file was there.
        case file
        /// Copied from the old Keychain item (first launch of 0.5.4).
        case migrated
        /// A new key: nothing to migrate, or the old key couldn't be read (`lostOld`).
        case fresh(lostOld: Bool)
    }

    /// The key in `url`, or nil when the file is missing or damaged. Tightens loose permissions.
    public static func load(_ url: URL) -> Data? {
        guard let d = try? Data(contentsOf: url), d.count == length else { return nil }
        if let p = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.posixPermissions] as? NSNumber,
           p.intValue & 0o077 != 0 {
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        }
        return d
    }

    /// Writes `key` atomically with mode 0600 (its folder 0700).
    public static func store(_ key: Data, at url: URL) throws {
        let fm = FileManager.default
        let dir = url.deletingLastPathComponent()
        try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let tmp = dir.appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString).tmp")
        guard fm.createFile(atPath: tmp.path, contents: key, attributes: [.posixPermissions: 0o600]) else {
            throw ProtocolError("can't write \(url.lastPathComponent)")
        }
        if fm.fileExists(atPath: url.path) { try fm.removeItem(at: url) }
        try fm.moveItem(at: tmp, to: url)
    }

    /// The key file, else the migrated legacy key, else a new one (written to the file in both cases).
    /// `legacy` is called at most once. `hadOldData`: something was sealed with the old key.
    public static func resolve(url: URL, hadOldData: Bool, legacy: () -> Data?, newKey: () -> Data) throws -> (Data, Source) {
        if let k = load(url) { return (k, .file) }
        if let old = legacy(), old.count == length {
            try store(old, at: url)
            return (old, .migrated)
        }
        let k = newKey()
        try store(k, at: url)
        return (k, .fresh(lostOld: hadOldData))
    }
}
