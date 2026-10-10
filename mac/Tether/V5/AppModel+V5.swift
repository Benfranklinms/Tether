import AppKit
import Combine
import Foundation
import TetherCore

extension ColorMode {
    /// nil follows the system.
    var nsAppearance: NSAppearance? {
        switch self {
        case .system: return nil
        case .dark: return NSAppearance(named: .darkAqua)
        case .light: return NSAppearance(named: .aqua)
        }
    }
}

/// 0.5.0: phone files and photos, one-time codes, Continue on Mac, open on phone, typing,
/// Focus/DND, phone setup status, clipboard images and history, battery alerts, call media.
extension AppModel {
    static let clipsFile = JSONFile<ClipHistory>("clips.json")

    func setUp5() {
        v5.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &v5Subs)
        if !demo { v5.clips = Self.clipsFile.read() ?? ClipHistory() }
        v5.update.tokenSet = Keychain.githubToken != nil
        v5.accessibilityTrusted = Accessibility.trusted
        v5.unlockReady = Keychain.unlockPassword != nil
        let t = Timer(timeInterval: 15, repeats: true) { [weak self] _ in self?.tick5() }
        RunLoop.main.add(t, forMode: .common)
        v5.expireTimer = t
        applyAppearance()
    }

    private func tick5() {
        if v5.continueCards.expire(now: Self.nowMs) { objectWillChange.send() }
        v5.accessibilityTrusted = Accessibility.trusted
    }

    func linkChanged5(wasConnected: Bool) {
        if isConnected && !wasConnected {
            if supports(Caps5.status) { engine?.send(.phoneStatusReq(ts: Self.nowMs)) }
            if supports(Caps5.dnd), settings.focusSync, let on = FocusReader.macFocusOn() {
                _ = v5.dndSync.local(on, now: Self.nowMs)
                engine?.send(.dndState(DndStatePayload(on: on, src: "mac", ts: Self.nowMs)))
            }
        }
        if !isConnected {
            v5.folder.loading = false
            v5.library.loading = false
            v5.dndSync.reset()
            if v5.callMedia.active { callMediaChanged(busy: false) }
        }
    }

    func settingsChanged5(_ old: MacSettings) {
        if old.colorMode != settings.colorMode || old.presence != settings.presence { applyAppearance() }
        if old.hotkeys != settings.hotkeys { registerHotkeys() }
        if old.clipHistory != settings.clipHistory, !settings.clipHistory { clearClipHistory() }
    }

    /// Dark / Light / System and Dock / menu bar for the whole app.
    func applyAppearance() {
        guard !demo || Launch.screenshotDir == nil else { return }
        NSApp.appearance = settings.colorMode.nsAppearance
        let policy: NSApplication.ActivationPolicy = settings.presence.showsDock ? .regular : .accessory
        if NSApp.activationPolicy() != policy { NSApp.setActivationPolicy(policy) }
        statusItemVisible?(settings.presence.showsMenuBar)
    }

    func registerHotkeys() {
        guard !demo else { return }
        Hotkeys.shared.register([
            (settings.hotkeys.sendClipboard, { [weak self] in self?.sendClipboardNow() }),
            (settings.hotkeys.sendFile, { [weak self] in self?.chooseFiles() }),
            (settings.hotkeys.openTether, { [weak self] in self?.windows?.show(.main) }),
        ])
    }

    /// Transfers shown in lists: clipboard images are not files the user sent or received.
    var visibleTransfers: [TransferRecord] { transfers.filter { $0.kind != FileKinds5.clip } }

    /// Revision-5 messages from the phone (Engine forwards what it doesn't handle itself).
    func receive5(_ message: Message) -> Bool {
        switch message {
        case .otp(let p): otpReceived(p)
        case .fsList(let p): fsList(p)
        case .fsGetResult(let p): fsGetResult(p)
        case .thumb(let p): thumb(p)
        case .photos(let p): photos(p)
        case .albums(let p): albums(p)
        case .continueCard(let c, let thumb): continueCard(c, thumb: thumb)
        case .openResult(let p): openResult(p)
        case .typeResult(let p): typeResult(p)
        case .dndState(let p): dndReceived(p)
        case .phoneStatus(let p): phoneStatusUpdate(p)
        default: return false
        }
        return true
    }

    // MARK: phone files

    func newRef() -> String { String(UUID().uuidString.prefix(8)).lowercased() }

    func openFolder(root: String, path: String = "") {
        guard supports(Caps5.fs) else {
            v5.folder = PhoneFolder(root: root, path: path, error: isConnected ? "Update Tether on your phone" : "Not connected")
            return
        }
        let ref = newRef()
        v5.folder = PhoneFolder(root: root, path: path, loading: true, ref: ref)
        engine?.send(.fsListReq(FsListReq(ref: ref, root: root, path: path))) { [weak self] in
            self?.v5.folder.loading = false
            self?.v5.folder.error = "Not connected"
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, self.v5.folder.ref == ref, self.v5.folder.loading else { return }
            self.v5.folder.loading = false
            self.v5.folder.error = "Your phone didn't answer"
        }
    }

    func loadMoreFiles() {
        let f = v5.folder
        guard f.more, !f.loading else { return }
        let ref = newRef()
        v5.folder.ref = ref
        v5.folder.loading = true
        engine?.send(.fsListReq(FsListReq(ref: ref, root: f.root, path: f.path, offset: f.entries.count)))
    }

    func fsList(_ p: FsListPayload) {
        guard p.ref == v5.folder.ref else { return }
        let appending = v5.folder.root == p.root && v5.folder.path == p.path && !v5.folder.entries.isEmpty && v5.folder.loading
            && p.entries.first.map { e in !v5.folder.entries.contains(e) } == true
        v5.folder.entries = appending ? v5.folder.entries + p.entries : p.entries
        v5.folder.total = p.total
        v5.folder.more = p.more
        v5.folder.needs = p.needs
        v5.folder.loading = false
        v5.folder.error = nil
        for e in p.entries.prefix(80) where e.isImage || e.isVideo {
            requestThumb(root: p.root, path: FsPathJoin.join(p.path, e.name))
        }
    }

    func thumbKey(root: String, path: String) -> String { "\(root)/\(path)" }

    func requestThumb(root: String, path: String, size: Int = 256) {
        let key = thumbKey(root: root, path: path) + (size > 256 ? "@\(size)" : "")
        guard v5.thumbs[key] == nil, !v5.thumbRequests.values.contains(key), supports(Caps5.fs) || supports(Caps5.photos) else { return }
        let ref = newRef()
        v5.thumbRequests[ref] = key
        engine?.send(.thumbReq(ThumbReq(ref: ref, root: root, path: path, size: size)))
    }

    func thumb(_ p: ThumbPayload) {
        guard let key = v5.thumbRequests.removeValue(forKey: p.ref) else { return }
        guard p.ok else { return }
        // Decode off the main thread; the cache notifies the one view showing this key.
        let data = p.data
        DispatchQueue.global(qos: .userInitiated).async {
            guard let img = ThumbCache.decode(data) else { return }
            DispatchQueue.main.async { ThumbCache.shared[key] = img }
        }
    }

    /// Ask the phone for a file. `folder` nil = the download folder.
    func fetchPhoneFile(root: String, path: String, name: String, folder: URL? = nil, open: Bool = false) {
        guard supports(root == FsRoots.photos ? Caps5.photos : Caps5.fs) else {
            return toast(isConnected ? "Update Tether on your phone" : "Not connected")
        }
        let ref = newRef()
        v5.gets[ref] = PhoneGet(name: name, folder: folder, open: open, preview: false)
        engine?.expectPhoneFile(name: name, folder: folder ?? settings.downloadURL)
        engine?.send(.fsGet(FsGetPayload(ref: ref, root: root, path: path))) { [weak self] in self?.toast("Not connected") }
        toast("Getting \(name)…")
    }

    func fsGetResult(_ p: FsGetResultPayload) {
        guard let g = v5.gets[p.ref] else { return }
        if !p.ok {
            v5.gets[p.ref] = nil
            toast(p.reason.map { "Couldn't get \(g.name): \($0)" } ?? "Couldn't get \(g.name)")
        } else if let actual = p.name, actual != g.name {
            v5.gets[p.ref]?.name = actual
            engine?.expectPhoneFile(name: actual, folder: g.folder ?? settings.downloadURL)
        }
    }

    /// A phone file (kind "fs") finished: reveal or open it as asked.
    func phoneFileArrived(_ r: TransferRecord) {
        guard let path = r.ref else { return }
        let match = v5.gets.first { $0.value.name == r.name }
        if let match {
            let g = match.value
            v5.gets[match.key] = nil
            let url = URL(fileURLWithPath: path)
            if g.open { NSWorkspace.shared.open(url) } else if g.folder == nil { toast("Saved \(r.name) to Downloads") }
        }
    }

    // MARK: photos

    func loadPhotos(more: Bool = false) {
        guard supports(Caps5.photos) else {
            v5.library.error = isConnected ? "Update Tether on your phone" : "Not connected"
            return
        }
        guard !v5.library.loading else { return }
        let ref = newRef()
        if !more { v5.library = PhoneLibrary() }
        v5.library.ref = ref
        v5.library.loading = true
        let albums = supports(Caps5.albums)
        let req = PhotosReq(ref: ref, limit: 90, before: more ? v5.library.before : nil,
                            album: albums ? v5.album : nil, filter: albums ? v5.photoFilter : nil)
        engine?.send(.photosReq(req)) { [weak self] in
            self?.v5.library.loading = false
            self?.v5.library.error = "Not connected"
        }
    }

    func photos(_ p: PhotosPayload) {
        guard p.ref == v5.library.ref else { return }
        let existing = Set(v5.library.items.map(\.id))
        v5.library.items += p.items.filter { !existing.contains($0.id) }
        v5.library.more = p.more
        v5.library.before = p.before ?? p.items.last?.date
        v5.library.needs = p.needs
        v5.library.loading = false
        v5.library.error = nil
        // 0.5.1: thumbnails are requested lazily by the tiles that appear on screen.
    }

    // MARK: one-time codes

    func otpReceived(_ p: OtpPayload) {
        v5.otp = p
        if settings.otpAutoCopy {
            receiveClip(p.code)
            addClip(text: p.code, fromPhone: true)
            toast("Code \(p.code) copied")
        }
        notifier.postLocal(id: "otp-\(p.ts)", title: "Code from \(p.app)",
                           body: settings.otpAutoCopy ? "\(p.code) · copied to the clipboard" : p.code)
        Log.info("otp.received", ["src": p.src, "copied": "\(settings.otpAutoCopy)"])
        let ts = p.ts
        DispatchQueue.main.asyncAfter(deadline: .now() + 300) { [weak self] in
            if self?.v5.otp?.ts == ts { self?.v5.otp = nil }
        }
    }

    func copyOtp() {
        guard let c = v5.otp?.code else { return }
        receiveClip(c)
        toast("Copied")
    }

    // MARK: Continue on Mac

    func continueCard(_ card: ContinuePayload, thumb: Data) {
        let fresh = v5.continueCards.add(card, thumb: thumb, now: Self.nowMs)
        guard fresh, settings.continueNotify else { return }
        notifier.postLocal(id: "continue-\(card.id)", title: "Continue on Mac", body: card.title)
    }

    func dismissContinue(_ id: String) { v5.continueCards.dismiss(id) }

    /// The card's main action: open the link / song / tab, or download the photo or file.
    func performContinue(_ c: ContinuePayload) {
        switch c.kind {
        case ContinueKinds.song:
            let title = c.title
            let url = SafeLinks.url(c.url)
                ?? (SongLinks.prefersSpotify(app: c.app) ? SongLinks.spotify(title: title, artist: c.subtitle)
                    : SongLinks.youTubeMusic(title: title, artist: c.subtitle))
            if let url, NSWorkspace.shared.open(url) { return }
            if let yt = SongLinks.youTubeMusic(title: title, artist: c.subtitle) { NSWorkspace.shared.open(yt) }
        case ContinueKinds.photo, ContinueKinds.download:
            if let root = c.root, let path = c.path {
                fetchPhoneFile(root: root, path: path, name: c.title, open: true)
            }
        default:
            if let url = SafeLinks.url(c.url) {
                NSWorkspace.shared.open(url)
            } else {
                receiveClip(c.subtitle ?? c.title)
                toast("Copied")
            }
        }
        v5.continueCards.dismiss(c.id)
    }

    // MARK: open on phone

    func openOnPhone(url: String? = nil, text: String? = nil) {
        guard supports(Caps5.openURL) else { return toast(isConnected ? "Update Tether on your phone" : "Not connected") }
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        var link = url
        var body = trimmed
        if link == nil, let t = trimmed, SafeLinks.isWebLink(t) {
            link = t
            body = nil
        }
        engine?.send(.openOnPhone(OpenOnPhonePayload(id: newRef(), url: link, text: body))) { [weak self] in
            self?.toast("Not connected")
        }
        toast(link != nil ? "Opening on your phone…" : "Sending to your phone…")
    }

    func openResult(_ p: OpenResultPayload) {
        if p.ok {
            toast(p.via == "notification" ? "Tap the Tether notification on your phone" : "Opened on your phone")
        } else {
            toast("Your phone couldn't open it\(p.reason.map { ": \($0)" } ?? "")")
        }
    }

    // MARK: typing (0.5.1: see AppModel+V51 typeOps / typeResult)

    // MARK: Focus / Do Not Disturb

    func dndReceived(_ p: DndStatePayload) {
        v5.phoneDnd = p
        guard settings.focusSync, v5.dndSync.remote(p.on, now: Self.nowMs) else { return }
        let name = p.on ? settings.focusOnShortcut : settings.focusOffShortcut
        guard !name.isEmpty else { return }
        Shortcuts.run(name) { ok in
            Log.info("focus.shortcut", ["on": "\(p.on)", "ok": "\(ok)"])
        }
    }

    /// Mac → phone (the Focus automation URL tether://dnd?on=1, the Home toggle, or a detected Focus change).
    func setPhoneDnd(_ on: Bool) {
        guard supports(Caps5.dnd) else { return toast(isConnected ? "Update Tether on your phone" : "Not connected") }
        _ = v5.dndSync.local(on, now: Self.nowMs)
        engine?.send(.dndState(DndStatePayload(on: on, src: "mac", ts: Self.nowMs)))
        v5.phoneDnd = DndStatePayload(on: on, src: "mac", ts: Self.nowMs, canSet: v5.phoneDnd?.canSet)
    }

    func macFocusChanged(_ on: Bool) {
        guard settings.focusSync, supports(Caps5.dnd), v5.dndSync.local(on, now: Self.nowMs) else { return }
        engine?.send(.dndState(DndStatePayload(on: on, src: "mac", ts: Self.nowMs)))
    }

    // MARK: phone setup status

    func phoneStatusUpdate(_ p: PhoneStatusPayload) { v5.phoneStatus = p }

    func refreshPhoneStatus() {
        guard supports(Caps5.status) else { return }
        engine?.send(.phoneStatusReq(ts: Self.nowMs))
    }

    // MARK: clipboard images and history

    static var clipsDir: URL { Paths.sub("clips") }

    func addClip(text: String? = nil, image: String? = nil, fromPhone: Bool) {
        guard settings.clipHistory else { return }
        let hash: String
        if let text { hash = "t:" + String(text.hashValue) } else { hash = "i:" + (image ?? UUID().uuidString) }
        let item = ClipItem(kind: text != nil ? .text : .image, text: text.map { String($0.prefix(4000)) }, image: image,
                            direction: fromPhone ? .fromPhone : .fromMac, ts: Self.nowMs, hash: hash)
        let dropped = v5.clips.add(item)
        for d in dropped { if let f = d.image { try? FileManager.default.removeItem(at: Self.clipsDir.appendingPathComponent(f)) } }
        saveClips()
    }

    func saveClips() {
        guard !demo else { return }
        let snap = v5.clips
        DispatchQueue.global(qos: .utility).async { Self.clipsFile.write(snap) }
    }

    func clearClipHistory() {
        for d in v5.clips.clear() {
            if let f = d.image { try? FileManager.default.removeItem(at: Self.clipsDir.appendingPathComponent(f)) }
        }
        saveClips()
    }

    func removeClip(_ id: String) {
        if let d = v5.clips.remove(id: id),
            let f = d.image { try? FileManager.default.removeItem(at: Self.clipsDir.appendingPathComponent(f)) }
        saveClips()
    }

    /// Put a history item back on the clipboard (it then syncs as a new copy if sending is on).
    func recopy(_ item: ClipItem) {
        if let t = item.text {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(t, forType: .string)
        } else if let f = item.image, let img = NSImage(contentsOf: Self.clipsDir.appendingPathComponent(f)) {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.writeObjects([img])
        }
        toast("Copied")
    }

    /// Mac copied an image: keep it and send it (kind "clip") when the phone supports it.
    func macCopiedImage(_ png: Data, ext: String = "png") {
        guard png.count <= 25 * 1_048_576 else { return }
        let name = "mac-\(Self.nowMs).\(ext)"
        let url = Self.clipsDir.appendingPathComponent(name)
        do { try png.write(to: url) } catch { return }
        addClip(image: name, fromPhone: false)
        guard settings.sendClipboard, settings.clipImages, supports(Caps5.clipImage) else { return }
        engine?.sendClipImage(url)
    }

    /// Phone sent a clipboard image (kind "clip"): put it on the Mac clipboard and in history.
    func clipImageReceived(_ r: TransferRecord) {
        guard let path = r.ref else { return }
        let url = URL(fileURLWithPath: path)
        defer { remove(r.id) }
        guard settings.receiveClipboard, settings.clipImages, let img = NSImage(contentsOf: url) else { return }
        clipboard.setImage(img)
        addClip(image: url.lastPathComponent, fromPhone: true)
        toast("Image copied from your phone")
    }

    // MARK: battery

    func batteryUpdate5(_ b: BatteryPayload) {
        if settings.chargedAlert && v5.charge.update(level: b.level, charging: b.charging) {
            notifier.postLocal(id: "charged", title: "\(peer?.name ?? "Phone") is fully charged", body: "\(b.level)%. You can unplug it.")
            Log.info("battery.charged_alert")
        }
        WidgetBridge.publish(self)
    }

    // MARK: calls and Mac media

    /// Pause / duck Mac audio while the phone rings or a call is up; restore afterwards.
    func callMediaChanged(busy: Bool) {
        guard settings.callMediaPause || settings.callDuck || v5.callMedia.active else { return }
        guard let action = v5.callMedia.phone(busy: busy) else { return }
        switch action {
        case .quiet(let to):
            MacMedia.quiet(pause: settings.callMediaPause, duckTo: settings.callDuck ? to : nil)
        case .restore:
            MacMedia.restore()
        }
    }

    // MARK: proximity unlock

    var proximityWanted: Bool { settings.proximityLock || settings.proximityUnlock }

    func checkProximityUnlock() {
        guard settings.proximityUnlock, !demo else { return }
        let near = proximityMonitor.state == .near
        let locked = ScreenLocker.isLocked()
        let ok = v5.unlockPolicy.evaluate(enabled: settings.proximityUnlock && Keychain.unlockPassword != nil && Accessibility.trusted,
                                          locked: locked, authenticated: isConnected, near: near, now: Self.nowMs)
        guard ok else { return }
        Log.info("unlock.attempt")
        ProximityUnlocker.unlock()
    }
}

/// Joins a phone folder path and a child name ("" root → "name").
enum FsPathJoin {
    static func join(_ base: String, _ name: String) -> String { base.isEmpty ? name : base + "/" + name }
    static func parent(_ path: String) -> String {
        guard let i = path.lastIndex(of: "/") else { return "" }
        return String(path[..<i])
    }
}

extension Engine {
    /// A clipboard image for the phone: staged like any file, with kind "clip".
    func sendClipImage(_ url: URL) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let rec = try? MacFiles.stage(url, now: Self.now(), kind: FileKinds5.clip) else { return }
            queue.async { self.sendStaged(rec) }
        }
    }

    /// Where a requested phone file (kind "fs") should land.
    /// An already staged record (clipboard images); call on `queue`.
    func sendStaged(_ rec: TransferRecord) { controller.sendFile(rec) }

    func expectPhoneFile(name: String, folder: URL) {
        let safe = TransferEngine.safeFileName(name)
        queue.async { self.files.fsDestinations[safe] = folder }
    }
}
