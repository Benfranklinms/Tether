import AppKit
import CryptoKit
import Foundation
import ImageIO
import TetherCore
import UniformTypeIdentifiers

/// What to do with a Shelf file once its bytes are here.
enum ShelfWant: Equatable { case open, copy }

/// 0.5.3 Shelf files on the Mac: every Shelf file (≤100 MB) is kept on both devices so it opens
/// while the phone is away, encrypted at rest like the Shelf list itself (ChunkSeal, AES-256-GCM,
/// the Shelf key from the Keychain) in `Application Support/Tether/ShelfStore/<id>.tsf`. Plain
/// bytes only exist in `Caches/…/ShelfOpen/<id>/<name>` while a file is opened or copied; that
/// folder is emptied on launch (and on removal).
extension AppModel {
    static var shelfStore: URL { Paths.sub("ShelfStore") }

    static var shelfOpenRoot: URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("in.benfranklin.tether/ShelfOpen", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func shelfBlob(_ id: String) -> URL { shelfStore.appendingPathComponent("\(id).tsf") }

    func shelfHasLocal(_ id: String) -> Bool {
        let size = (try? Self.shelfBlob(id).resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        return size > 0
    }

    // MARK: lifecycle

    func setUpShelfFiles() {
        let key = shelfKey()
        let live = Set(v5.shelf.items.filter { $0.file != nil }.map(\.id))
        DispatchQueue.global(qos: .utility).async { [weak self] in
            try? FileManager.default.removeItem(at: Self.shelfOpenRoot)
            if let key { Self.migratePlainShelfFiles(key: key) }
            Self.pruneShelfStore(live: live)
            Self.pruneShelfThumbs(live: live)
            DispatchQueue.main.async {
                self?.refreshShelfLocal()
                self?.syncShelfFiles()
            }
        }
    }

    /// 0.5.2 kept Shelf files in plain text under `Application Support/Tether/Shelf/<id>/<name>`
    /// (and phone files in `Shelf-in`): encrypt each into the store, then delete the plain copies.
    static func migratePlainShelfFiles(key: Data) {
        let fm = FileManager.default
        let old = Paths.support.appendingPathComponent("Shelf", isDirectory: true)
        for dir in (try? fm.contentsOfDirectory(at: old, includingPropertiesForKeys: nil)) ?? [] {
            let id = dir.lastPathComponent
            if let plain = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?.first,
               !fm.fileExists(atPath: shelfBlob(id).path) {
                do {
                    try sealShelfFile(id: id, plain: plain, key: key, move: true)
                    Log.info("shelf.migrated")
                } catch {
                    Log.error("shelf.migrate_failed", error)
                    continue
                }
            }
            try? fm.removeItem(at: dir)
        }
        try? fm.removeItem(at: old)
    }

    static func pruneShelfThumbs(live: Set<String>) {
        let fm = FileManager.default
        let all = (try? fm.contentsOfDirectory(at: shelfThumbStore, includingPropertiesForKeys: nil)) ?? []
        for f in all where !live.contains(f.deletingPathExtension().lastPathComponent) { try? fm.removeItem(at: f) }
    }

    static func pruneShelfStore(live: Set<String>) {
        let fm = FileManager.default
        for f in (try? fm.contentsOfDirectory(at: shelfStore, includingPropertiesForKeys: nil)) ?? [] {
            let name = f.lastPathComponent
            let id = name.replacingOccurrences(of: ".tmp", with: "").replacingOccurrences(of: ".tsf", with: "")
            if !live.contains(id) || name.hasSuffix(".tmp") { try? fm.removeItem(at: f) }
        }
    }

    /// Streams `plain` into the encrypted store under `id`.
    static func sealShelfFile(id: String, plain: URL, key: Data, move: Bool) throws {
        let tmp = shelfStore.appendingPathComponent("\(id).tsf.tmp")
        try? FileManager.default.removeItem(at: tmp)
        try ChunkSeal.encryptFile(plain, to: tmp, key: key)
        let dest = shelfBlob(id)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        if move { try? FileManager.default.removeItem(at: plain) }
    }

    static func sha256Hex(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var sha = SHA256()
        while true {
            let d = h.readData(ofLength: 1 << 20)
            if d.isEmpty { break }
            sha.update(data: d)
        }
        return sha.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// The plain file for opening / copying, named as the item is called now (rename-aware).
    static func materializeShelfFile(_ item: ShelfItem, key: Data) -> URL? {
        guard let f = item.file else { return nil }
        let dir = shelfOpenRoot.appendingPathComponent(item.id, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let out = dir.appendingPathComponent(TransferEngine.safeFileName(f.name))
        do {
            try ChunkSeal.decryptFile(shelfBlob(item.id), to: out, key: key)
            return out
        } catch {
            Log.error("shelf.open_failed", error)
            try? FileManager.default.removeItem(at: out)
            return nil
        }
    }

    // MARK: state changes

    func refreshShelfLocal() {
        let ids = Set(v5.shelf.items.filter { $0.file != nil && shelfHasLocal($0.id) }.map(\.id))
        if ids != v5.shelfLocal { v5.shelfLocal = ids }
        measureShelfOffline()
    }

    /// After any change to the list: drop copies of removed items, then fetch what's missing.
    func shelfItemsChanged(removed: Set<String>) {
        for id in removed {
            try? FileManager.default.removeItem(at: Self.shelfBlob(id))
            try? FileManager.default.removeItem(at: Self.shelfThumbBlob(id))
            try? FileManager.default.removeItem(at: Self.shelfOpenRoot.appendingPathComponent(id, isDirectory: true))
            v5.shelfFetching.remove(id)
            v5.shelfInFlight[id] = nil
        }
        refreshShelfLocal()
        syncShelfFiles()
    }

    func shelfLinkChanged() {
        if !isConnected {
            v5.shelfInFlight = [:]
            v5.shelfFetching = []
            v5.shelfWants = [:]
        }
        syncShelfFiles()
    }

    /// 0.5.4: keep copies within the cap (LRU, pinned and own files never dropped), then ask the
    /// phone for the copies the plan keeps (≤100 MB; over Bluetooth only small ones).
    func syncShelfFiles() {
        guard !demo else { return }
        let fetch = planShelfOffline()
        guard isConnected, supports(Caps6.shelf) else { return }
        let now = Date()
        v5.shelfInFlight = v5.shelfInFlight.filter { now.timeIntervalSince($0.value) < 600 }
        let byId = Dictionary(v5.shelf.items.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for item in fetch.compactMap({ byId[$0] }) {
            guard let f = item.file else { continue }
            if ShelfCache.shouldFetch(f, haveLocal: false, inFlight: v5.shelfInFlight[item.id] != nil,
                                      connected: isConnected, wifi: onWifi) {
                requestShelfFile(item)
            }
        }
    }

    private func requestShelfFile(_ item: ShelfItem) {
        v5.shelfInFlight[item.id] = Date()
        engine?.send(.shelfFileReq(ShelfFileReq(id: item.id))) { [weak self] in
            self?.v5.shelfInFlight[item.id] = nil
            self?.v5.shelfFetching.remove(item.id)
        }
        Log.info("shelf.fetch", ["bytes": "\(item.file?.size ?? 0)", "wifi": "\(onWifi)"])
    }

    /// The card state: here, coming, available from the phone, or "Not downloaded yet".
    func shelfState(_ item: ShelfItem) -> ShelfCache.State {
        if demo { return .local }
        return ShelfCache.state(haveLocal: v5.shelfLocal.contains(item.id), fetching: v5.shelfFetching.contains(item.id),
                                connected: isConnected && supports(Caps6.shelf))
    }

    // MARK: open / copy

    func withShelfFile(_ item: ShelfItem, _ want: ShelfWant) {
        if shelfHasLocal(item.id) {
            perform(want, on: item)
            return
        }
        guard isConnected, supports(Caps6.shelf) else { return toast("\(ShelfOffline.away) · connect your phone to get \(item.title)") }
        v5.shelfWants[item.id] = want
        v5.shelfFetching.insert(item.id)
        requestShelfFile(item)
    }

    private func perform(_ want: ShelfWant, on item: ShelfItem) {
        guard let key = shelfKey() else { return toast("Couldn't read the Shelf key") }
        markShelfOpened(item.id)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let url = Self.materializeShelfFile(item, key: key)
            DispatchQueue.main.async {
                guard let self else { return }
                guard let url else { return self.toast("Couldn't open \(item.title)") }
                switch want {
                case .open:
                    NSWorkspace.shared.open(url)
                    self.flashSuccess("shelf-\(item.id)")
                case .copy:
                    self.clipboard.setShelfFile(url, isImage: FileKinds.isImage(mime: item.file?.mime, name: item.file?.name))
                    self.flashSuccess("shelf-copy-\(item.id)")
                    self.toast("Copied \(url.lastPathComponent)")
                }
            }
        }
    }

    /// A shelf file from the phone (FILE_OFFER kind "shelf") arrived: encrypt it into the store.
    func shelfFileArrived(_ r: TransferRecord) {
        guard let path = r.ref else { return }
        let src = URL(fileURLWithPath: path)
        guard let item = v5.shelf.items.first(where: { $0.file?.sha256 == r.sha256 })
                ?? v5.shelf.items.first(where: { $0.file?.name == r.name }), let key = shelfKey() else {
            try? FileManager.default.removeItem(at: src)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var ok = true
            do {
                try Self.sealShelfFile(id: item.id, plain: src, key: key, move: true)
            } catch {
                ok = false
                Log.error("shelf.store_failed", error)
                try? FileManager.default.removeItem(at: src)
            }
            DispatchQueue.main.async {
                guard let self else { return }
                self.v5.shelfInFlight[item.id] = nil
                self.v5.shelfFetching.remove(item.id)
                self.refreshShelfLocal()
                if ok, let want = self.v5.shelfWants.removeValue(forKey: item.id) { self.perform(want, on: item) }
            }
        }
    }

    /// The phone asked for a file: any device holding a copy serves it.
    func shelfFileRequested(_ p: ShelfFileReq) {
        guard let item = v5.shelf.items.first(where: { $0.id == p.id }), shelfHasLocal(item.id), let key = shelfKey() else { return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let url = Self.materializeShelfFile(item, key: key) else { return }
            self?.engine?.sendKinded(url, kind: FileKinds6.shelf) {
                try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
            }
        }
    }

    // MARK: rename

    /// Rename (0.5.3): the title, and for files the name used when opening / copying. Synced.
    @discardableResult
    func renameShelf(_ id: String, to title: String) -> Bool {
        guard let item = v5.shelf.items.first(where: { $0.id == id }),
              let renamed = ShelfRename.rename(item, to: title, now: Self.nowMs) else { return false }
        commitShelf(v5.shelf.update(renamed), sync: ShelfSyncPayload(items: [renamed]))
        Log.info("shelf.renamed", ["file": "\(item.file != nil)"])
        return true
    }

    // MARK: thumbnails

    /// A small picture of an image file, decoded from the encrypted copy off the main thread.
    func shelfThumbnail(_ item: ShelfItem, done: @escaping (NSImage?) -> Void) {
        if demo, let img = Demo5.shelfThumbs[item.id] { return done(img) }
        guard let f = item.file, FileKinds.isImage(mime: f.mime, name: f.name), let key = shelfKey() else {
            return done(nil)
        }
        let cacheKey = "shelf/\(item.id)/\(f.sha256)"
        if let hit = ShelfThumbs.cache.object(forKey: cacheKey as NSString) { return done(hit) }
        let local = shelfHasLocal(item.id)
        DispatchQueue.global(qos: .utility).async {
            // 0.5.4: an evicted copy keeps its saved (sealed) thumbnail.
            if !local || FileManager.default.fileExists(atPath: Self.shelfThumbBlob(item.id).path),
               let saved = Self.loadShelfThumb(id: item.id, key: key) {
                ShelfThumbs.cache.setObject(saved, forKey: cacheKey as NSString)
                DispatchQueue.main.async { done(saved) }
                return
            }
            guard local else { return DispatchQueue.main.async { done(nil) } }
            var img: NSImage?
            if let data = try? ChunkSeal.decrypt(Data(contentsOf: Self.shelfBlob(item.id)), key: key),
               let src = CGImageSourceCreateWithData(data as CFData, nil),
               let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [
                   kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 160,
                   kCGImageSourceCreateThumbnailWithTransform: true,
               ] as CFDictionary) {
                img = NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
                if let img { ShelfThumbs.cache.setObject(img, forKey: cacheKey as NSString) }
                Self.saveShelfThumb(cg, id: item.id, key: key)
            }
            DispatchQueue.main.async { done(img) }
        }
    }
}

enum ShelfThumbs {
    static let cache = NSCache<NSString, NSImage>()
}

extension ClipboardWatcher {
    /// A Shelf file on the pasteboard (0.5.3): the file URL (Finder pastes the file itself) and,
    /// for images, the picture in its own format plus TIFF (apps that take images paste it).
    func setShelfFile(_ url: URL, isImage: Bool) {
        let pb = NSPasteboard.general
        pb.clearContents()
        let item = NSPasteboardItem()
        item.setString(url.absoluteString, forType: .fileURL)
        if isImage, let data = try? Data(contentsOf: url) {
            if let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .image) {
                item.setData(data, forType: NSPasteboard.PasteboardType(type.identifier))
            }
            if let img = NSImage(data: data), let tiff = img.tiffRepresentation { item.setData(tiff, forType: .tiff) }
        }
        pb.writeObjects([item])
        markOwnWrite()
    }
}
