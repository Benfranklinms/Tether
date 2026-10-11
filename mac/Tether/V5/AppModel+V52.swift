import AppKit
import CoreWLAN
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// 0.5.2: video playback in Photos, Insert from Phone, Find my Mac, Instant Hotspot and Shelf.
extension AppModel {
    func setUp6() {
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil,
                                                          queue: .main) { [weak self] n in
            guard let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
                  app.bundleIdentifier != Bundle.main.bundleIdentifier else { return }
            self?.v5.lastOtherApp = app.processIdentifier
        }
        if !demo {
            loadShelf()
            setUpShelfFiles()
        }
        Self.cleanVideoCache()
    }

    func linkChanged6(wasConnected: Bool) {
        linkChanged7(wasConnected: wasConnected)
        if isConnected && !wasConnected && supports(Caps6.shelf) {
            engine?.send(.shelfSync(v5.shelf.snapshot()))
        }
        // 0.5.3: fetch missing Shelf files (and bigger ones once the Wi-Fi fast path is up).
        shelfLinkChanged()
        if !isConnected {
            if let c = v5.capture, c.state != CaptureStates.done, c.state != CaptureStates.failed {
                captureFailed("Your phone disconnected")
            }
            if v5.hotspot.state == "asking" || v5.hotspot.state == HotspotStates.starting {
                v5.hotspot.state = HotspotStates.failed
                v5.hotspot.reason = "Your phone disconnected"
            }
        }
    }

    func receive6(_ message: Message) -> Bool {
        switch message {
        case .captureResult(let p): captureResult(p)
        case .findMac(let p): findMacReceived(p)
        case .hotspotState(let p): hotspotStateReceived(p)
        case .shelfSync(let p): shelfSyncReceived(p)
        case .shelfFileReq(let p): shelfFileRequested(p)
        default: return receive7(message)
        }
        return true
    }

    // MARK: - Video playback (Photos viewer)

    static let videoCache: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("in.benfranklin.tether/Videos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /// Cached videos older than a week go (they're copies of what's on the phone).
    static func cleanVideoCache() {
        DispatchQueue.global(qos: .utility).async {
            let fm = FileManager.default
            let cutoff = Date().addingTimeInterval(-7 * 24 * 3600)
            for d in (try? fm.contentsOfDirectory(at: videoCache, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
                let mod = (try? d.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date()
                if mod < cutoff { try? fm.removeItem(at: d) }
            }
        }
    }

    private func videoFolder(_ id: String) -> URL {
        Self.videoCache.appendingPathComponent(VideoPlayback.folderName(for: id), isDirectory: true)
    }

    private func cachedVideo(_ item: PhotoItem) -> String? {
        let dir = videoFolder(item.id)
        let urls = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        let entries = urls.map { u in
            (name: u.lastPathComponent, size: Int64((try? u.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0))
        }
        return VideoPlayback.cachedFile(in: entries, expectedSize: item.size).map { dir.appendingPathComponent($0).path }
    }

    /// What the viewer should show for this video right now.
    func videoPlayback(_ item: PhotoItem) -> VideoPlayback {
        let req = v5.videoRequests[item.id]
        let record = req.flatMap { r in
            transfers.first {
                $0.direction == .IN && $0.kind == FileKinds5.fs && $0.name == TransferEngine.safeFileName(r.name)
                    && $0.createdAt >= r.since - 5_000
            }
        }
        let cached = record == nil ? cachedVideo(item) : nil
        return VideoPlayback.state(cachedPath: cached, record: record, requested: req != nil, error: v5.videoErrors[item.id])
    }

    /// Fetch a video to play it: FS_GET into the cache; the transfer engine picks the Wi-Fi fast
    /// path when it's up and falls back to Bluetooth (≤ 100 MB; bigger waits for Wi-Fi).
    func playVideo(_ item: PhotoItem, retry: Bool = false) {
        if !retry, v5.videoRequests[item.id] != nil || cachedVideo(item) != nil { return }
        guard supports(Caps5.photos) else {
            v5.videoErrors[item.id] = isConnected ? "Update Tether on your phone" : "Not connected"
            return
        }
        v5.videoErrors[item.id] = nil
        let folder = videoFolder(item.id)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let ref = newRef()
        v5.gets[ref] = PhoneGet(name: item.name, folder: folder, open: false, preview: true, videoId: item.id)
        v5.videoRequests[item.id] = VideoRequest(name: item.name, size: item.size, folder: folder, since: Self.nowMs)
        engine?.expectPhoneFile(name: item.name, folder: folder)
        engine?.send(.fsGet(FsGetPayload(ref: ref, root: FsRoots.photos, path: item.id))) { [weak self] in
            self?.v5.videoErrors[item.id] = "Not connected"
        }
        Log.info("video.fetch", ["size": "\(item.size)", "wifi": "\(onWifi)"])
    }

    /// FS_GET answer for a viewer fetch (called from fsGetResult).
    func videoGetResult(_ g: PhoneGet, _ p: FsGetResultPayload) {
        guard let id = g.videoId else { return }
        if !p.ok {
            v5.videoErrors[id] = p.reason.map { "Couldn't get the video: \($0)" } ?? "Couldn't get the video"
            v5.videoRequests[id] = nil
        } else if let actual = p.name {
            v5.videoRequests[id]?.name = actual
        }
    }

    /// A capture (Insert from Phone) or shelf file finished arriving.
    func kindedFileArrived(_ r: TransferRecord) {
        if r.kind == FileKinds6.capture { captureArrived(r) } else if r.kind == FileKinds6.shelf { shelfFileArrived(r) }
    }

    // MARK: - Insert from Phone

    func startCapture(_ kind: String) {
        guard supports(Caps6.capture) else { return toast(isConnected ? "Update Tether on your phone" : "Not connected") }
        let front = NSWorkspace.shared.frontmostApplication
        let pid = front?.bundleIdentifier == Bundle.main.bundleIdentifier ? v5.lastOtherApp : front?.processIdentifier
        let ref = newRef()
        v5.capture = CaptureSession(ref: ref, kind: kind, state: "asking", reason: nil, frontmostPid: pid, started: Self.nowMs)
        engine?.send(.captureReq(CaptureReq(ref: ref, kind: kind))) { [weak self] in self?.captureFailed("Not connected") }
        // 0.5.3: a phone that never answers is reported within seconds, not after 3 minutes.
        DispatchQueue.main.asyncAfter(deadline: .now() + CaptureText.askTimeout) { [weak self] in
            guard let self, let c = self.v5.capture, c.ref == ref, c.state == "asking" else { return }
            self.captureFailed(CaptureText.describe("timeout"))
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 180) { [weak self] in
            guard let self, let c = self.v5.capture, c.ref == ref, c.state == "asking" || c.state == CaptureStates.open else { return }
            self.captureFailed(CaptureText.describe("timeout"))
        }
    }

    func cancelCapture() { v5.capture = nil }

    private func captureFailed(_ reason: String) {
        v5.capture?.state = CaptureStates.failed
        v5.capture?.reason = reason
        let ref = v5.capture?.ref
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            if self?.v5.capture?.ref == ref { self?.v5.capture = nil }
        }
    }

    private func captureResult(_ p: CaptureResultPayload) {
        guard let c = v5.capture, c.ref == p.ref else { return }
        if p.state == CaptureStates.failed {
            captureFailed(CaptureText.describe(p.reason ?? "error:unknown"))
        } else if c.state != CaptureStates.done {
            v5.capture?.state = p.state
            // "open" + tap_phone: the phone posted a notification instead of opening the camera.
            v5.capture?.reason = p.state == CaptureStates.open ? p.reason : nil
        }
    }

    /// The photo / scan arrived (FILE_OFFER kind "capture"): paste it into the app the user was
    /// in (pasteboard + ⌘V through Accessibility), else save it to the Desktop.
    func captureArrived(_ r: TransferRecord) {
        guard let path = r.ref else { return }
        let url = URL(fileURLWithPath: path)
        let pid = v5.capture?.frontmostPid
        clipboard.setFile(url)
        let plan = CaptureText.insertPlan(accessibility: Accessibility.trusted, frontmostIsTether: pid == nil)
        if plan == .paste, let pid, let app = NSRunningApplication(processIdentifier: pid) {
            app.activate(options: [])
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                KeyPaste.commandV()
            }
            v5.capture?.state = CaptureStates.done
            v5.capture?.reason = "Inserted into \(app.localizedName ?? "the app")"
        } else {
            let desktop = FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask)[0]
            let dest = MacFiles.unique(desktop.appendingPathComponent(r.name))
            do {
                try FileManager.default.copyItem(at: url, to: dest)
                NSWorkspace.shared.activateFileViewerSelecting([dest])
                v5.capture?.state = CaptureStates.done
                v5.capture?.reason = Accessibility.trusted ? "Saved to the Desktop and copied"
                    : "Saved to the Desktop and copied · allow Accessibility to paste"
            } catch {
                captureFailed("Couldn't save to the Desktop")
            }
        }
        let ref = v5.capture?.ref
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            if self?.v5.capture?.ref == ref { self?.v5.capture = nil }
        }
        Log.info("capture.inserted", ["plan": plan == .paste ? "paste" : "desktop"])
    }

    // MARK: - Find my Mac

    private func findMacReceived(_ p: FindMacPayload) {
        // Only the paired phone can reach this: messages arrive inside the authenticated session.
        guard isConnected, peer != nil else { return }
        switch p.action {
        case FindActions.ring:
            v5.findMac = FindMacSession(id: p.id, started: Self.nowMs)
            FindMacAlarm.shared.start(model: self)
            engine?.send(.findMacState(FindMacStatePayload(id: p.id, ringing: true)))
        case FindActions.stop:
            FindMacAlarm.shared.stop()
            v5.findMac = nil
            engine?.send(.findMacState(FindMacStatePayload(id: p.id, ringing: false)))
        case FindActions.lock:
            FindMacAlarm.shared.stop()
            v5.findMac = nil
            let ok = demo ? true : ScreenLocker.lock()
            engine?.send(.findMacState(FindMacStatePayload(id: p.id, ringing: false, ok: ok, locked: ok, reason: ok ? nil : "lock_failed")))
        default:
            break
        }
    }

    /// "Found it" on the Mac.
    func foundMac() {
        let id = v5.findMac?.id
        FindMacAlarm.shared.stop()
        v5.findMac = nil
        if let id { engine?.send(.findMacState(FindMacStatePayload(id: id, ringing: false, by: "mac"))) }
    }

    // MARK: - Instant Hotspot

    func startHotspot() {
        guard supports(Caps6.hotspot) else { return toast(isConnected ? "Update Tether on your phone" : "Not connected") }
        if v5.hotspot.joined, let ssid = v5.hotspot.ssid, HotspotJoiner.currentSSID() == ssid { return toast("Already on \(ssid)") }
        let ref = newRef()
        v5.hotspot = HotspotSession(ref: ref, state: "asking")
        engine?.send(.hotspotReq(HotspotReq(ref: ref))) { [weak self] in
            self?.v5.hotspot.state = HotspotStates.failed
            self?.v5.hotspot.reason = "Not connected"
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in
            guard let self, self.v5.hotspot.ref == ref, self.v5.hotspot.state == "asking" || self.v5.hotspot.state == HotspotStates.starting
            else { return }
            self.v5.hotspot.state = HotspotStates.failed
            self.v5.hotspot.reason = HotspotText.describe(HotspotReasons.timeout)
        }
    }

    func dismissHotspot() { v5.hotspot = HotspotSession() }

    private func hotspotStateReceived(_ p: HotspotStatePayload) {
        Log.info("hotspot.state", ["state": p.state, "reason": p.reason ?? "-"])
        if let r = p.ref, let mine = v5.hotspot.ref, r != mine { return }
        v5.hotspot.state = p.state
        v5.hotspot.reason = p.reason.map { HotspotText.describe($0) }
        if p.state == HotspotStates.on, let ssid = p.ssid {
            v5.hotspot.ssid = ssid
            v5.hotspot.joining = true
            let psk = p.psk
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let err = HotspotJoiner.join(ssid: ssid, password: psk)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.v5.hotspot.joining = false
                    self.v5.hotspot.joined = err == nil
                    if let err {
                        self.v5.hotspot.state = HotspotStates.failed
                        self.v5.hotspot.reason = HotspotText.describe(err)
                    } else {
                        self.toast("Joined \(ssid)")
                    }
                }
            }
        }
    }

    // MARK: - Shelf

    private static let shelfKeyService = "in.benfranklin.tether.shelf-key"
    private static var shelfURL: URL { Paths.support.appendingPathComponent("shelf.sealed") }

    func shelfKey() -> Data? {
        if let s = Keychain.read(Self.shelfKeyService), let d = Data(base64Encoded: s) { return d }
        let k = ShelfVault.newKey()
        return Keychain.write(Self.shelfKeyService, k.base64EncodedString()) ? k : nil
    }

    func loadShelf() {
        guard let key = shelfKey(), let data = try? Data(contentsOf: Self.shelfURL) else { return }
        do {
            v5.shelf = try ShelfVault.open(data, key: key)
        } catch {
            Log.error("shelf.open_failed", error)
        }
    }

    private func saveShelf() {
        guard !demo, let key = shelfKey() else { return }
        let state = v5.shelf
        DispatchQueue.global(qos: .utility).async {
            do {
                try ShelfVault.seal(state, key: key).write(to: Self.shelfURL, options: [.atomic])
            } catch {
                Log.error("shelf.save_failed", error)
            }
        }
    }

    func commitShelf(_ new: ShelfState, sync: ShelfSyncPayload?) {
        let before = Set(v5.shelf.items.map(\.id))
        v5.shelf = new.pruned(now: Self.nowMs)
        saveShelf()
        if let sync, supports(Caps6.shelf) { engine?.send(.shelfSync(sync)) }
        shelfItemsChanged(removed: before.subtracting(v5.shelf.items.map(\.id)))
    }

    func addToShelf(text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let item = ShelfState.item(from: t, id: UUID().uuidString.lowercased(), from: "mac", now: Self.nowMs)
        let new = v5.shelf.add(item)
        commitShelf(new, sync: ShelfSyncPayload(items: new.items.filter { $0.id == item.id }))
    }

    func addToShelf(file url: URL) {
        let id = UUID().uuidString.lowercased()
        let key = shelfKey()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            // Hash while streaming, then keep only the encrypted copy (0.5.3: encrypted at rest).
            guard let key, let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
                  let sha = try? Self.sha256Hex(url) else {
                DispatchQueue.main.async { self?.toast("Couldn't add \(url.lastPathComponent)") }
                return
            }
            do {
                try Self.sealShelfFile(id: id, plain: url, key: key, move: false)
            } catch {
                Log.error("shelf.store_failed", error)
                DispatchQueue.main.async { self?.toast("Couldn't add \(url.lastPathComponent)") }
                return
            }
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            let file = ShelfFile(name: url.lastPathComponent, size: Int64(size), mime: mime, sha256: sha, owner: "mac")
            DispatchQueue.main.async {
                guard let self else { return }
                let item = ShelfState.fileItem(file, id: id, from: "mac", now: Self.nowMs)
                let new = self.v5.shelf.add(item)
                self.commitShelf(new, sync: ShelfSyncPayload(items: new.items.filter { $0.id == id }))
            }
        }
    }

    func removeFromShelf(_ id: String) {
        guard let item = v5.shelf.items.first(where: { $0.id == id }) else { return }
        let now = Self.nowMs
        v5.shelfUndo = item
        commitShelf(v5.shelf.remove(id, now: now), sync: ShelfSyncPayload(removed: [ShelfTomb(id: id, ts: now)]))
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            guard let self, self.v5.shelfUndo?.id == id else { return }
            self.v5.shelfUndo = nil
        }
    }

    func undoShelfRemove() {
        guard let item = v5.shelfUndo else { return }
        v5.shelfUndo = nil
        let new = v5.shelf.restore(item, now: Self.nowMs)
        commitShelf(new, sync: ShelfSyncPayload(items: new.items.filter { $0.id == item.id }))
    }

    func moveShelf(_ id: String, to index: Int) {
        let new = v5.shelf.move(id, to: index, now: Self.nowMs)
        commitShelf(new, sync: ShelfSyncPayload(items: new.items.filter { $0.id == id }))
    }

    /// The card's one action: Copy / Open / Map / open the file.
    func shelfPrimary(_ item: ShelfItem) {
        switch item.kind {
        case ShelfKinds.link:
            if let u = ShelfDetect.linkURL(item) { NSWorkspace.shared.open(u) }
        case ShelfKinds.address:
            if let u = ShelfDetect.mapsURL(item) { NSWorkspace.shared.open(u) }
        case ShelfKinds.file:
            withShelfFile(item, .open)
            return
        default:
            clipboard.set(ShelfDetect.copyText(item))
        }
        flashSuccess("shelf-\(item.id)")
    }

    /// Copy: text items copy their text; a file copies the file itself (0.5.3), so Finder pastes the
    /// file and image-aware apps paste the picture.
    func copyShelf(_ item: ShelfItem) {
        if item.kind == ShelfKinds.file {
            withShelfFile(item, .copy)
            return
        }
        clipboard.set(item.kind == ShelfKinds.link || item.kind == ShelfKinds.address
                      ? [item.title, item.body].filter { !$0.isEmpty }.joined(separator: "\n") : ShelfDetect.copyText(item))
        flashSuccess("shelf-copy-\(item.id)")
    }

    func openShelfFile(_ item: ShelfItem) { withShelfFile(item, .open) }

    private func shelfSyncReceived(_ p: ShelfSyncPayload) {
        let before = Set(v5.shelf.items.map(\.id))
        v5.shelf = v5.shelf.merge(p).pruned(now: Self.nowMs)
        saveShelf()
        shelfItemsChanged(removed: before.subtracting(v5.shelf.items.map(\.id)))
    }

    /// Pin a clipboard-history entry.
    func pinToShelf(_ text: String) {
        addToShelf(text: text)
        flashSuccess("pin-\(text.hashValue)")
        toast("Pinned to Shelf")
    }
}

extension Engine {
    /// A file for the phone with a FILE_OFFER kind (shelf files). `staged` runs once the file was
    /// copied into the outgoing staging area (the source may be deleted then).
    func sendKinded(_ url: URL, kind: String, staged: (() -> Void)? = nil) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let rec = try? MacFiles.stage(url, now: Self.now(), kind: kind)
            staged?()
            guard let rec else { return }
            queue.async { self.sendStaged(rec) }
        }
    }
}

/// ⌘V into the frontmost app (needs Accessibility).
enum KeyPaste {
    static func commandV() {
        let src = CGEventSource(stateID: .combinedSessionState)
        let down = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: true)
        let up = CGEvent(keyboardEventSource: src, virtualKey: 9, keyDown: false)
        down?.flags = .maskCommand
        up?.flags = .maskCommand
        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
    }
}

/// Joins the phone's hotspot: CoreWLAN first (scan + associate), `networksetup` as a fallback
/// (works even when Location access hides SSIDs from CoreWLAN scans on macOS 14+).
enum HotspotJoiner {
    static func currentSSID() -> String? { CWWiFiClient.shared().interface()?.ssid() }

    /// nil on success, else a reason for HotspotText.describe.
    static func join(ssid: String, password: String?) -> String? {
        guard let iface = CWWiFiClient.shared().interface() else { return "no_wifi" }
        if !iface.powerOn() { try? iface.setPower(true) }
        if iface.ssid() == ssid { return nil }
        // The hotspot may still be coming up: scan a few times.
        for attempt in 0..<4 {
            if let net = (try? iface.scanForNetworks(withName: ssid))?.first {
                do {
                    try iface.associate(to: net, password: password)
                    return nil
                } catch {
                    Log.warn("hotspot.associate_failed", ["attempt": "\(attempt)", "reason": "\((error as NSError).code)"])
                }
            }
            Thread.sleep(forTimeInterval: 2.5)
        }
        guard let name = iface.interfaceName else { return "join_failed" }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        p.arguments = ["-setairportnetwork", name, ssid] + (password.map { [$0] } ?? [])
        let out = Pipe()
        p.standardOutput = out
        p.standardError = out
        do {
            try p.run()
            p.waitUntilExit()
        } catch {
            return "join_failed"
        }
        let text = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        Thread.sleep(forTimeInterval: 2)
        if iface.ssid() == ssid || (p.terminationStatus == 0 && !text.lowercased().contains("could not")) { return nil }
        return "join_failed"
    }
}
