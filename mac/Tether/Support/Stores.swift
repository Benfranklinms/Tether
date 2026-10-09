import Foundation
import Security
import TetherCore

/// Where Tether keeps its files: ~/Library/Application Support/Tether.
enum Paths {
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Tether", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static func sub(_ name: String) -> URL {
        let dir = support.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static let logs: URL = {
        let base = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Logs/Tether", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static var defaultDownloads: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("Tether", isDirectory: true)
    }
}

/// Crash-safe JSON file: writes go to a temp file that is atomically renamed into place.
struct JSONFile<T: Codable> {
    let url: URL

    init(_ name: String) { url = Paths.support.appendingPathComponent(name) }

    func read() -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            Log.error("storage.corrupt", error, ["file": url.lastPathComponent])
            return nil
        }
    }

    func write(_ value: T) {
        do {
            let data = try JSONEncoder().encode(value)
            try data.write(to: url, options: [.atomic])
        } catch {
            Log.error("storage.write_failed", error, ["file": url.lastPathComponent])
        }
    }
}

/// Mac-side settings (the phone keeps its own).
struct MacSettings: Codable, Equatable {
    var sendClipboard = true
    var receiveClipboard = true
    var downloadFolder: String?
    var wifiFastPath = true
    var notifyOnReceive = true
    var notifications = MacNotificationSettings()
    // 0.2.0
    var appearance = AppearanceStyle.mono
    var notificationActions = true
    /// Screenshots and photos from the phone; nil = the file download folder.
    var mediaFolder: String?
    var mediaNotify = true
    var batteryInMenuBar = false
    var lowBatteryAlert = true
    var screenQuality = 1280
    var screenControl = true
    /// The menu-bar panel is torn off into a floating window (remembered across launches).
    var panelDetached = false
    var panelOnTop = true

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sendClipboard = try c.decodeIfPresent(Bool.self, forKey: .sendClipboard) ?? true
        receiveClipboard = try c.decodeIfPresent(Bool.self, forKey: .receiveClipboard) ?? true
        downloadFolder = try c.decodeIfPresent(String.self, forKey: .downloadFolder)
        wifiFastPath = try c.decodeIfPresent(Bool.self, forKey: .wifiFastPath) ?? true
        notifyOnReceive = try c.decodeIfPresent(Bool.self, forKey: .notifyOnReceive) ?? true
        notifications = try c.decodeIfPresent(MacNotificationSettings.self, forKey: .notifications) ?? MacNotificationSettings()
        appearance = (try? c.decodeIfPresent(AppearanceStyle.self, forKey: .appearance)) ?? .mono
        notificationActions = try c.decodeIfPresent(Bool.self, forKey: .notificationActions) ?? true
        mediaFolder = try c.decodeIfPresent(String.self, forKey: .mediaFolder)
        mediaNotify = try c.decodeIfPresent(Bool.self, forKey: .mediaNotify) ?? true
        batteryInMenuBar = try c.decodeIfPresent(Bool.self, forKey: .batteryInMenuBar) ?? false
        lowBatteryAlert = try c.decodeIfPresent(Bool.self, forKey: .lowBatteryAlert) ?? true
        screenQuality = try c.decodeIfPresent(Int.self, forKey: .screenQuality) ?? 1280
        screenControl = try c.decodeIfPresent(Bool.self, forKey: .screenControl) ?? true
        panelDetached = try c.decodeIfPresent(Bool.self, forKey: .panelDetached) ?? false
        panelOnTop = try c.decodeIfPresent(Bool.self, forKey: .panelOnTop) ?? true
    }

    var mediaURL: URL { mediaFolder.map { URL(fileURLWithPath: $0, isDirectory: true) } ?? downloadURL }

    var downloadURL: URL {
        downloadFolder.map { URL(fileURLWithPath: $0, isDirectory: true) } ?? Paths.defaultDownloads
    }
}

/// Identity (our static key, a pending rotated key, and the paired phone) in
/// ~/Library/Application Support/Tether/identity.json, readable only by this user (0600).
///
/// v0.1.0 kept it in the login Keychain. With an ad-hoc signature every update changes the
/// code hash, so macOS put up a blocking "Tether wants to use your confidential information"
/// prompt at launch after each update. The file store avoids that; an existing Keychain
/// item is migrated once without showing any prompt (if macOS would prompt, it is skipped).
final class FileIdentityStore: IdentityStore {
    private struct Blob: Codable {
        var key: String
        var pendingKey: String?
        var peer: PeerRecord?
    }

    private static let service = "in.benfranklin.tether"
    private static let account = "identity"
    private let url = Paths.support.appendingPathComponent("identity.json")
    private var blob: Blob
    private let lock = NSLock()

    init() {
        if let b = Self.decode(try? Data(contentsOf: url)) {
            blob = b
        } else if let b = Self.decode(Self.loadKeychainSilently()) {
            blob = b
            Log.info("identity.migrated_from_keychain")
            save()
        } else {
            blob = Blob(key: B64.encode(KeyPair.generate().privateKey))
            Log.info("identity.created")
            save()
        }
    }

    private static func decode(_ data: Data?) -> Blob? {
        guard let data, let b = try? JSONDecoder().decode(Blob.self, from: data),
              let k = B64.decode(b.key), (try? KeyPair(privateKey: k)) != nil else { return nil }
        return b
    }

    var localKey: KeyPair {
        get {
            lock.lock()
            defer { lock.unlock() }
            // The blob was validated on load, so this can't fail.
            // swiftlint:disable:next force_try
            return try! KeyPair(privateKey: B64.decode(blob.key) ?? Data())
        }
        set { mutate { $0.key = B64.encode(newValue.privateKey) } }
    }

    var pendingLocalKey: KeyPair? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return blob.pendingKey.flatMap(B64.decode).flatMap { try? KeyPair(privateKey: $0) }
        }
        set { mutate { $0.pendingKey = newValue.map { B64.encode($0.privateKey) } } }
    }

    var peer: PeerRecord? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return blob.peer
        }
        set { mutate { $0.peer = newValue } }
    }

    private func mutate(_ f: (inout Blob) -> Void) {
        lock.lock()
        f(&blob)
        lock.unlock()
        save()
    }

    private func save() {
        lock.lock()
        let data = try? JSONEncoder().encode(blob)
        lock.unlock()
        guard let data else { return }
        do {
            try data.write(to: url, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch {
            Log.error("identity.save_failed", error)
        }
    }

    /// Reads the v0.1.0 Keychain item without ever showing an access prompt.
    private static func loadKeychainSilently() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        SecKeychainSetUserInteractionAllowed(false)
        defer { SecKeychainSetUserInteractionAllowed(true) }
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        guard status == errSecSuccess else {
            if status != errSecItemNotFound { Log.info("identity.keychain_skipped", ["status": "\(status)"]) }
            return nil
        }
        return out as? Data
    }
}

/// Structured JSON-lines log in ~/Library/Logs/Tether, rotated at 512 KB x 3. Never logs
/// clipboard text, notification content or file names.
enum Log {
    private static let queue = DispatchQueue(label: "tether.log", qos: .utility)
    private static let maxBytes = 512 * 1024
    private static let keep = 3
    private static let file = Paths.logs.appendingPathComponent("tether.log")
    private static let iso = ISO8601DateFormatter()

    static func info(_ event: String, _ fields: [String: String] = [:]) { write("info", event, nil, fields) }
    static func warn(_ event: String, _ fields: [String: String] = [:]) { write("warn", event, nil, fields) }
    static func error(_ event: String, _ error: Error? = nil, _ fields: [String: String] = [:]) {
        write("error", event, error, fields)
    }

    private static func write(_ level: String, _ event: String, _ error: Error?, _ fields: [String: String]) {
        var obj: [String: String] = fields
        obj["ts"] = iso.string(from: Date())
        obj["level"] = level
        obj["event"] = event
        if let error { obj["error"] = String(describing: error).prefix(300).description }
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]),
              var line = String(data: data, encoding: .utf8) else { return }
        line += "\n"
        queue.async {
            let fm = FileManager.default
            if let size = (try? fm.attributesOfItem(atPath: file.path))?[.size] as? Int, size > maxBytes { rotate() }
            if !fm.fileExists(atPath: file.path) { fm.createFile(atPath: file.path, contents: nil) }
            guard let h = try? FileHandle(forWritingTo: file) else { return }
            h.seekToEndOfFile()
            h.write(Data(line.utf8))
            try? h.close()
        }
    }

    private static func rotate() {
        let fm = FileManager.default
        try? fm.removeItem(at: file.appendingPathExtension("\(keep)"))
        for i in stride(from: keep - 1, through: 1, by: -1) {
            try? fm.moveItem(at: file.appendingPathExtension("\(i)"), to: file.appendingPathExtension("\(i + 1)"))
        }
        try? fm.moveItem(at: file, to: file.appendingPathExtension("1"))
    }

    /// All log files, oldest first, concatenated under a header line.
    static func export(header: String) -> Data {
        queue.sync {}
        var out = Data((header + "\n").utf8)
        for i in stride(from: keep, through: 1, by: -1) {
            if let d = try? Data(contentsOf: file.appendingPathExtension("\(i)")) { out.append(d) }
        }
        if let d = try? Data(contentsOf: file) { out.append(d) }
        return out
    }
}
