import AppKit
import Foundation
import ImageIO
import LocalAuthentication
import Security
import TetherCore

/// 0.5.4: the Shelf key in a 0600 file instead of the login Keychain.
///
/// Tether is ad-hoc signed, so each update has a new code signature and the Keychain item's access
/// list stopped matching: macOS asked for the Keychain password after every update (sometimes
/// several times, once per read). The key now lives in `Application Support/Tether/shelf.key`
/// (ShelfKeyFile: mode 0600 in a 0700 folder) and is read once per launch. The old Keychain key is
/// migrated on first launch: silently if macOS allows it, else with one last prompt; if that is
/// denied too, the old sealed Shelf is set aside, a new key starts an empty local Shelf, and the
/// phone's full sync brings the items back on the next connection (cached file copies are fetched again).
enum ShelfKeyStore {
    static let legacyService = "in.benfranklin.tether.shelf-key"
    static var url: URL { Paths.support.appendingPathComponent("shelf.key") }
    private static let lock = NSLock()
    private static var cached: Data?

    static func key() -> Data? {
        lock.lock()
        defer { lock.unlock() }
        if let k = cached { return k }
        let hadOld = FileManager.default.fileExists(atPath: AppModel.shelfURL.path)
        do {
            let (k, source) = try ShelfKeyFile.resolve(url: url, hadOldData: hadOld, legacy: legacyKey, newKey: { ShelfVault.newKey() })
            switch source {
            case .file: break
            case .migrated:
                Log.info("shelf.key_migrated")
                deleteLegacyQuietly()
            case .fresh(let lostOld):
                Log.info("shelf.key_new", ["lostOld": "\(lostOld)"])
                if lostOld { setAsideOldData() }
            }
            cached = k
            return k
        } catch {
            Log.error("shelf.key_failed", error)
            return nil
        }
    }

    /// The 0.5.2–0.5.3 key: first without any UI, then (if macOS insists) one interactive read.
    private static func legacyKey() -> Data? {
        guard Keychain.exists(legacyService) else { return nil }
        if let d = read(allowUI: false) { return d }
        Log.info("shelf.key_migration_needs_prompt")
        return read(allowUI: true)
    }

    private static func read(allowUI: Bool) -> Data? {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: legacyService,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        if !allowUI { q[kSecUseAuthenticationContext as String] = nonInteractive() }
        var out: CFTypeRef?
        // The legacy (file-based) login keychain asks through its own ACL dialog; switching user
        // interaction off makes that read fail quietly instead of prompting.
        if !allowUI { SecKeychainSetUserInteractionAllowed(false) }
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if !allowUI { SecKeychainSetUserInteractionAllowed(true) }
        guard status == errSecSuccess, let d = out as? Data, let s = String(data: d, encoding: .utf8),
              let key = Data(base64Encoded: s), key.count == ShelfKeyFile.length else {
            Log.info("shelf.key_legacy_read", ["status": "\(status)", "ui": "\(allowUI)"])
            return nil
        }
        return key
    }

    private static func deleteLegacyQuietly() {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: legacyService,
                                kSecUseAuthenticationContext as String: nonInteractive()]
        SecKeychainSetUserInteractionAllowed(false)
        SecItemDelete(q as CFDictionary)
        SecKeychainSetUserInteractionAllowed(true)
    }

    private static func nonInteractive() -> LAContext {
        let ctx = LAContext()
        ctx.interactionNotAllowed = true
        return ctx
    }

    private static func setAsideOldData() {
        let fm = FileManager.default
        let stamp = Int(Date().timeIntervalSince1970)
        try? fm.moveItem(at: AppModel.shelfURL, to: Paths.support.appendingPathComponent("shelf.sealed.unreadable-\(stamp)"))
        try? fm.removeItem(at: AppModel.shelfStore)
        try? fm.removeItem(at: AppModel.shelfThumbStore)
    }
}

/// "Keep offline" pins and last-open times (local to this Mac; never synced).
enum ShelfOfflineStore {
    static var url: URL { Paths.support.appendingPathComponent("shelf-offline.json") }
    static let capKey = "shelfOfflineCap"

    static func load() -> ShelfOffline.Pins {
        guard let d = try? Data(contentsOf: url), let p = try? JSONDecoder().decode(ShelfOffline.Pins.self, from: d) else {
            return ShelfOffline.Pins()
        }
        return p
    }

    static func save(_ p: ShelfOffline.Pins) {
        DispatchQueue.global(qos: .utility).async {
            if let d = try? JSONEncoder().encode(p) { try? d.write(to: url, options: [.atomic]) }
        }
    }

    static var cap: Int64 {
        get {
            let d = UserDefaults.standard
            return d.object(forKey: capKey) == nil ? ShelfOffline.macDefault : Int64(d.double(forKey: capKey))
        }
        set { UserDefaults.standard.set(Double(newValue), forKey: capKey) }
    }
}

extension AppModel {
    static var shelfThumbStore: URL { Paths.sub("ShelfThumbs") }
    static func shelfThumbBlob(_ id: String) -> URL { shelfThumbStore.appendingPathComponent("\(id).tsf") }

    // MARK: folders (0.5.4)

    @discardableResult
    func createShelfFolder(_ name: String) -> String? {
        let id = UUID().uuidString.lowercased()
        guard let new = v5.shelf.createFolder(id: id, name: name, now: Self.nowMs) else { return nil }
        commitShelf(new, sync: new.syncOf(folders: [id]))
        return id
    }

    @discardableResult
    func renameShelfFolder(_ id: String, to name: String) -> Bool {
        guard let new = v5.shelf.renameFolder(id, name: name, now: Self.nowMs) else { return false }
        commitShelf(new, sync: new.syncOf(folders: [id]))
        return true
    }

    /// Its items go back to the Shelf (never removed).
    func deleteShelfFolder(_ id: String) {
        guard v5.shelf.folders.contains(where: { $0.id == id }) else { return }
        let moved = Set(v5.shelf.items.filter { $0.folderId == id }.map(\.id))
        let new = v5.shelf.deleteFolder(id, now: Self.nowMs)
        if v5.shelfFolder == id { v5.shelfFolder = nil }
        commitShelf(new, sync: new.syncOf(items: moved, folders: [id]))
        v5.shelfPins = v5.shelfPins.pruned(liveItems: Set(new.items.map(\.id)), liveFolders: Set(new.folders.map(\.id)))
        ShelfOfflineStore.save(v5.shelfPins)
    }

    /// Into a folder (nil = loose). False when nothing changed (dropped onto the folder it is in).
    @discardableResult
    func moveShelfItem(_ itemId: String, toFolder folder: String?) -> Bool {
        guard let new = v5.shelf.moveTo(itemId, folder: folder, now: Self.nowMs) else { return false }
        commitShelf(new, sync: new.syncOf(items: [itemId]))
        let name = folder.flatMap { f in new.folders.first { $0.id == f }?.name }
        toast(name.map { "Moved to \($0)" } ?? "Moved to Shelf")
        return true
    }

    /// Reorder within the list the page shows (a folder's, or the loose items).
    func moveShelfWithin(_ id: String, to index: Int) {
        let list = v5.shelf.items(in: v5.shelfFolder)
        let new = v5.shelf.move(id, to: index, within: list, now: Self.nowMs)
        commitShelf(new, sync: new.syncOf(items: [id]))
    }

    // MARK: offline copies (0.5.4, this Mac only)

    func shelfKept(_ item: ShelfItem) -> Bool {
        ShelfOffline.pinned(item, folder: v5.shelf.folderOf(item), pins: v5.shelfPins)
    }

    func toggleKeepOffline(item id: String) {
        v5.shelfPins = v5.shelfPins.togglingItem(id)
        ShelfOfflineStore.save(v5.shelfPins)
        syncShelfFiles()
    }

    func toggleKeepOffline(folder id: String) {
        v5.shelfPins = v5.shelfPins.togglingFolder(id)
        ShelfOfflineStore.save(v5.shelfPins)
        syncShelfFiles()
    }

    var shelfOfflineCap: Int64 {
        get { ShelfOfflineStore.cap }
        set {
            ShelfOfflineStore.cap = newValue
            objectWillChange.send()
            syncShelfFiles()
        }
    }

    func markShelfOpened(_ id: String) {
        v5.shelfPins = v5.shelfPins.opening(id, now: Self.nowMs)
        ShelfOfflineStore.save(v5.shelfPins)
    }

    /// Settings › Shelf › Remove offline copies: copies from the phone go (items and thumbnails stay;
    /// files added on this Mac are the originals and stay too).
    func removeShelfOfflineCopies() {
        let ids = v5.shelf.items.filter { $0.file != nil && $0.file?.owner != "mac" && v5.shelfWants[$0.id] == nil }.map(\.id)
        DispatchQueue.global(qos: .utility).async { [weak self] in
            for id in ids { try? FileManager.default.removeItem(at: Self.shelfBlob(id)) }
            DispatchQueue.main.async { self?.refreshShelfLocal() }
        }
        Log.info("shelf.offline_removed", ["n": "\(ids.count)"])
    }

    /// Keeps copies within the cap (least recently opened go first, pinned and own never) and
    /// returns the ids the plan wants fetched.
    func planShelfOffline() -> [String] {
        let plan = ShelfOffline.plan(ShelfOffline.entries(v5.shelf, local: v5.shelfLocal, pins: v5.shelfPins, me: "mac"),
                                     cap: ShelfOfflineStore.cap)
        let evict = plan.evict.filter { v5.shelfWants[$0] == nil && v5.shelfInFlight[$0] == nil }
        if !evict.isEmpty {
            Log.info("shelf.evicted", ["n": "\(evict.count)"])
            v5.shelfLocal.subtract(evict)
            DispatchQueue.global(qos: .utility).async { [weak self] in
                for id in evict { try? FileManager.default.removeItem(at: Self.shelfBlob(id)) }
                DispatchQueue.main.async { self?.refreshShelfLocal() }
            }
        }
        return plan.fetch
    }

    /// Bytes held by offline copies, measured off the main thread.
    func measureShelfOffline() {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let fm = FileManager.default
            let files = (try? fm.contentsOfDirectory(at: Self.shelfStore, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            let used = files.filter { $0.pathExtension == "tsf" }
                .reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) }
            DispatchQueue.main.async { if self?.v5.shelfOfflineUsed != used { self?.v5.shelfOfflineUsed = used } }
        }
    }

    /// A small sealed thumbnail kept after the copy is evicted, so a photo keeps its picture.
    static func saveShelfThumb(_ cg: CGImage, id: String, key: Data) {
        let rep = NSBitmapImageRep(cgImage: cg)
        guard let jpeg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.8]),
              let sealed = try? ChunkSeal.encrypt(jpeg, key: key) else { return }
        try? sealed.write(to: shelfThumbBlob(id), options: [.atomic])
    }

    static func loadShelfThumb(id: String, key: Data) -> NSImage? {
        guard let blob = try? Data(contentsOf: shelfThumbBlob(id)), let jpeg = try? ChunkSeal.decrypt(blob, key: key) else { return nil }
        return NSImage(data: jpeg)
    }
}
