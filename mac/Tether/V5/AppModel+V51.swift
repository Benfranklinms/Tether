import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

// 0.5.1 model glue: albums and multi-select, typing with acknowledgements, phone-side fixes,
// the Home setup row, success morphs on buttons, and drag providers for phone files.

extension SetupHomeGate {
    static let storeKey = "setupHomeGate"

    static func load() -> SetupHomeGate {
        guard let d = UserDefaults.standard.data(forKey: storeKey),
              let g = try? JSONDecoder().decode(SetupHomeGate.self, from: d) else { return SetupHomeGate() }
        return g
    }

    func save() {
        if let d = try? JSONEncoder().encode(self) { UserDefaults.standard.set(d, forKey: Self.storeKey) }
    }
}

extension AppModel {
    // MARK: albums and photos

    func loadAlbums() {
        guard supports(Caps5.albums) else { return }
        let ref = newRef()
        v5.albumsRef = ref
        engine?.send(.albumsReq(AlbumsReq(ref: ref)))
    }

    func albums(_ p: AlbumsPayload) {
        guard p.ref == v5.albumsRef else { return }
        withAnimation(.spring(response: 0.35, dampingFraction: 0.86)) { v5.albums = p.albums }
    }

    func selectAlbum(_ id: String) {
        guard (v5.album ?? AlbumKinds.all) != id else { return }
        v5.album = id == AlbumKinds.all ? nil : id
        reloadPhotos()
    }

    func setPhotoFilter(_ f: String?) {
        guard v5.photoFilter != f else { return }
        v5.photoFilter = f
        reloadPhotos()
    }

    private func reloadPhotos() {
        v5.photoSelection.clear()
        v5.library.loading = false
        loadPhotos()
    }

    func downloadSelectedPhotos() {
        let picked = v5.library.items.filter { v5.photoSelection.ids.contains($0.id) }
        for p in picked { fetchPhoneFile(root: FsRoots.photos, path: p.id, name: p.name) }
        toast(picked.count == 1 ? "Getting \(picked[0].name)…" : "Getting \(picked.count) items…")
        withAnimation { v5.photoSelection.clear() }
    }

    /// Drag to Finder. With several photos selected, Finder receives one folder holding all of them.
    func photoDragProvider(for item: PhotoItem) -> NSItemProvider {
        let selected = v5.photoSelection.ids
        guard selected.count > 1, selected.contains(item.id) else {
            return fileProvider(root: FsRoots.photos, path: item.id, name: item.name)
        }
        let items = v5.library.items.filter { selected.contains($0.id) }
        let provider = NSItemProvider()
        provider.suggestedName = "\(items.count) photos from \(peer?.name ?? "phone")"
        provider.registerFileRepresentation(forTypeIdentifier: UTType.folder.identifier, fileOptions: [], visibility: .all) { done in
            let folder = Paths.sub("drag").appendingPathComponent(UUID().uuidString)
                .appendingPathComponent(provider.suggestedName ?? "Photos")
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            DispatchQueue.main.async {
                for p in items { self.fetchPhoneFile(root: FsRoots.photos, path: p.id, name: p.name, folder: folder) }
            }
            let progress = Progress(totalUnitCount: Int64(items.count))
            DispatchQueue.global().async {
                let deadline = Date().addingTimeInterval(Double(60 + items.count * 20))
                while Date() < deadline {
                    let n = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).count) ?? 0
                    progress.completedUnitCount = Int64(n)
                    if n >= items.count { break }
                    Thread.sleep(forTimeInterval: 0.3)
                }
                done(folder, false, nil)
            }
            return progress
        }
        return provider
    }

    /// Drag a phone file to Finder: Finder gets a promise; the file is fetched when it lands.
    func phoneFileDragProvider(entry: FsEntry, root: String, path: String) -> NSItemProvider {
        guard !entry.dir else { return NSItemProvider() }
        return fileProvider(root: root, path: path, name: entry.name)
    }

    private func fileProvider(root: String, path: String, name: String) -> NSItemProvider {
        let provider = NSItemProvider()
        provider.suggestedName = name
        let type = UTType(filenameExtension: (name as NSString).pathExtension) ?? .data
        provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { done in
            let folder = Paths.sub("drag").appendingPathComponent(UUID().uuidString)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            DispatchQueue.main.async { self.fetchPhoneFile(root: root, path: path, name: name, folder: folder) }
            let progress = Progress(totalUnitCount: 1)
            DispatchQueue.global().async {
                let deadline = Date().addingTimeInterval(120)
                while Date() < deadline {
                    if let f = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil).first {
                        progress.completedUnitCount = 1
                        done(f, false, nil)
                        return
                    }
                    Thread.sleep(forTimeInterval: 0.25)
                }
                let err = NSError(domain: "Tether", code: 1, userInfo: [NSLocalizedDescriptionKey: "The phone didn't send the file"])
                done(nil, false, err)
            }
            return progress
        }
        return provider
    }

    /// A local image (clipboard history) into the thumbnail cache, read off the main thread.
    func loadLocalThumb(key: String, url: URL) {
        DispatchQueue.global(qos: .utility).async {
            guard let data = try? Data(contentsOf: url), let img = ThumbCache.decode(data) else { return }
            DispatchQueue.main.async { ThumbCache.shared[key] = img }
        }
    }

    // MARK: typing (acknowledged batches, visible state)

    func typeOps(_ ops: [TypeBatcher.Op]) {
        guard !ops.isEmpty else { return }
        guard supports(Caps5.type) else {
            v5.typeStatus = isConnected ? "Update Tether on your phone to type from the Mac." : "Not connected to your phone."
            return
        }
        for op in ops {
            v5.typeSeq += 1
            let p: TypeTextPayload
            switch op {
            case .text(let s):
                p = TypeTextPayload(seq: v5.typeSeq, text: s)
                v5.typeEcho = String((v5.typeEcho + s).suffix(80))
            case .key(let k):
                p = TypeTextPayload(seq: v5.typeSeq, key: k)
                if k == TypeKeys.backspace { v5.typeEcho = String(v5.typeEcho.dropLast()) }
                if k == TypeKeys.clear || k == TypeKeys.enter { v5.typeEcho = "" }
            }
            v5.typeTracker.sent(seq: p.seq, chars: p.text?.count ?? 0, now: Self.nowMs)
            engine?.send(.typeText(p)) { [weak self] in self?.v5.typeStatus = "Not connected to your phone." }
        }
        Log.info("type.sent", ["n": "\(ops.count)"])
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(TypeTracker.timeoutMs) + 100)) { [weak self] in
            guard let self, self.v5.typeTracker.timedOut(now: Self.nowMs) else { return }
            self.v5.typeStatus = TypeTracker.describe("timeout")
            Log.info("type.timeout")
        }
    }

    func typeResult(_ r: TypeResultPayload) {
        v5.typeTracker.answered(seq: r.seq)
        if r.ok {
            v5.typeStatus = nil
            v5.typeField = r.field
        } else {
            v5.typeStatus = r.reason == "binding" ? "Tether control is starting on your phone. Try again in a second." :
                TypeTracker.describe(r.reason)
            if r.reason == "no_service" || r.reason == "binding" { refreshPhoneStatus() }
            Log.info("type.failed", ["reason": r.reason ?? "?"])
        }
    }

    var typeAccess: TypeAccess { TypeAccess.from(v5.phoneStatus?.perms) }

    /// Opens the fix for one phone checklist row on the phone (its Settings screen, or a tap-to-open notification).
    func openPhoneSetup(_ id: String) {
        guard supports(Caps5.openURL) else { return toast(isConnected ? "Open Tether › Setup on your phone" : "Not connected") }
        engine?.send(.openOnPhone(OpenOnPhonePayload(id: newRef(), url: PhoneSetupLink.url(for: id)))) { [weak self] in
            self?.toast("Not connected")
        }
        toast("Opening the setting on your phone…")
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in self?.refreshPhoneStatus() }
    }

    // MARK: setup (Home row)

    var setupEntries: [SetupEntry] {
        setupItems.map { i in
            if i.id.hasPrefix("phone-"), let p = v5.phoneStatus?.perms.first(where: { "phone-\($0.id)" == i.id }) {
                return SetupPlan.phoneEntry(p)
            }
            return SetupEntry(id: i.id, title: i.title, unlocks: i.detail, ok: i.ok, required: !i.optional,
                              feature: i.optional ? "Mac" : "Essentials", fix: i.fix)
        }
    }

    var setupHomeVisible: Bool {
        let missing = Set(SetupPlan.missingRequired(setupEntries).map(\.id))
        return v5.setupGate.shows(missingRequired: missing)
    }

    func hideSetupRow() {
        let missing = Set(SetupPlan.missingRequired(setupEntries).map(\.id))
        v5.setupGate.hide(missingRequired: missing)
        v5.setupGate.save()
    }

    /// Forget a Hide once every required item is done (so a later revocation shows the row again).
    func refreshSetupGate() {
        let missing = Set(SetupPlan.missingRequired(setupEntries).map(\.id))
        let before = v5.setupGate
        v5.setupGate.update(missingRequired: missing)
        if before != v5.setupGate { v5.setupGate.save() }
    }

    func openSetupSettings() {
        v5.page = .settings
        windows?.show(.main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            NotificationCenter.default.post(name: .tetherOpenSettingsSection, object: "Setup")
        }
    }

    // MARK: clipboard (0.5.1: an image file copied in Finder sends the picture, not its name)

    func sendClipboardNow() {
        switch clipboard.current {
        case .image(let data, let ext):
            guard supports(Caps5.clipImage) else { return toast(isConnected ? "Update Tether on your phone" : "Not connected") }
            let url = Self.clipsDir.appendingPathComponent("mac-\(Self.nowMs).\(ext)")
            guard (try? data.write(to: url)) != nil else { return }
            engine?.sendClipImage(url)
            flashSuccess("clipboard")
            toast("Sending the image to your phone")
        case .text(let text):
            engine?.sendClip(text, manual: true)
            flashSuccess("clipboard")
        case .none:
            toast("Nothing to send: copy text or an image")
        }
    }

    // MARK: success morphs

    /// Marks a button as just succeeded (it shows a check for a moment).
    func flashSuccess(_ id: String) {
        v5.succeeded.insert(id)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { [weak self] in self?.v5.succeeded.remove(id) }
    }
}
