import AppKit
import ApplicationServices
import Carbon.HIToolbox
import Foundation
import IOKit.pwr_mgt
import LocalAuthentication
import Security
import TetherCore
#if canImport(WidgetKit)
import WidgetKit
#endif

// MARK: - Keychain

/// Generic-password items in the login Keychain. Secrets are never logged.
enum Keychain {
    static let githubService = "in.benfranklin.tether.github-token"
    static let unlockService = "in.benfranklin.tether.unlock"

    static func read(_ service: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    @discardableResult
    static func write(_ service: String, _ value: String?) -> Bool {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        SecItemDelete(base as CFDictionary)
        guard let value, !value.isEmpty else { return true }
        var add = base
        add[kSecAttrAccount as String] = "tether"
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static var githubToken: String? { read(githubService) }
    static var unlockPassword: String? { read(unlockService) }
}

/// Touch ID or the Mac password before a sensitive change (proximity unlock).
enum OwnerAuth {
    static func confirm(_ reason: String, done: @escaping (Bool) -> Void) {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else { return done(false) }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, _ in
            DispatchQueue.main.async { done(ok) }
        }
    }
}

// MARK: - Accessibility

enum Accessibility {
    static var trusted: Bool { AXIsProcessTrusted() }

    /// Shows the system prompt (once) and opens the Accessibility pane.
    static func request() {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
        open("Privacy_Accessibility")
    }

    static func open(_ anchor: String) {
        if let u = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)") { NSWorkspace.shared.open(u) }
    }
}

// MARK: - Global shortcuts (Carbon hot keys: no Accessibility permission needed)

final class Hotkeys {
    static let shared = Hotkeys()
    private var refs: [EventHotKeyRef] = []
    private var actions: [UInt32: () -> Void] = [:]
    private var installed = false
    private(set) var failed: [String] = []

    func register(_ list: [(HotkeySpec, () -> Void)]) {
        refs.forEach { UnregisterEventHotKey($0) }
        refs.removeAll()
        actions.removeAll()
        failed.removeAll()
        install()
        for (i, (spec, action)) in list.enumerated() where spec.enabled && spec.isValid {
            let id = EventHotKeyID(signature: OSType(0x5445_5448), id: UInt32(i + 1))
            var ref: EventHotKeyRef?
            let status = RegisterEventHotKey(spec.keyCode, spec.modifiers, id, GetApplicationEventTarget(), 0, &ref)
            if status == noErr, let ref {
                refs.append(ref)
                actions[UInt32(i + 1)] = action
            } else {
                failed.append(spec.display)
            }
        }
        Log.info("hotkeys.registered", ["count": "\(refs.count)", "failed": failed.joined(separator: " ")])
    }

    fileprivate func fire(_ id: UInt32) { actions[id]?() }

    private func install() {
        guard !installed else { return }
        installed = true
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
            var hk = EventHotKeyID()
            let st = GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                                       nil, MemoryLayout<EventHotKeyID>.size, nil, &hk)
            guard st == noErr else { return st }
            let id = hk.id
            DispatchQueue.main.async { Hotkeys.shared.fire(id) }
            return noErr
        }, 1, &spec, nil, nil)
    }
}

// MARK: - Focus

/// Reads the Mac's Focus state where macOS allows it (the DoNotDisturb assertions file is
/// readable without Full Disk Access on some versions). nil = unknown; then Focus → phone works
/// through the Shortcuts automation URL tether://dnd?on=1 / ?on=0 instead.
enum FocusReader {
    static func macFocusOn() -> Bool? {
        let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/DoNotDisturb/DB/Assertions.json")
        guard let d = try? Data(contentsOf: url), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let data = o["data"] as? [[String: Any]] else { return nil }
        let records = data.first?["storeAssertionRecords"] as? [Any] ?? []
        return !records.isEmpty
    }
}

/// Runs a named shortcut with /usr/bin/shortcuts (macOS 12+). Missing shortcut → false.
enum Shortcuts {
    static func run(_ name: String, done: @escaping (Bool) -> Void) {
        DispatchQueue.global(qos: .utility).async {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            p.arguments = ["run", name]
            p.standardOutput = FileHandle.nullDevice
            p.standardError = FileHandle.nullDevice
            do {
                try p.run()
                p.waitUntilExit()
                let ok = p.terminationStatus == 0
                DispatchQueue.main.async { done(ok) }
            } catch {
                DispatchQueue.main.async { done(false) }
            }
        }
    }
}

// MARK: - Mac media during calls

/// Pauses what is playing on the Mac (MediaRemote, best effort) and lowers the output volume,
/// then restores both — but only what we changed: a volume the user moved meanwhile stays.
enum MacMedia {
    private static var original: Double?
    private static var ducked: Double?
    private static var paused = false

    private typealias SendCommand = @convention(c) (Int32, CFDictionary?) -> Bool
    private typealias IsPlaying = @convention(c) (DispatchQueue, @escaping (Bool) -> Void) -> Void

    private static let mediaRemote: UnsafeMutableRawPointer? =
        dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY)

    private static func command(_ c: Int32) -> Bool {
        guard let h = mediaRemote, let sym = dlsym(h, "MRMediaRemoteSendCommand") else { return false }
        return unsafeBitCast(sym, to: SendCommand.self)(c, nil)
    }

    private static func isPlaying(_ done: @escaping (Bool) -> Void) {
        guard let h = mediaRemote, let sym = dlsym(h, "MRMediaRemoteGetNowPlayingApplicationIsPlaying") else { return done(false) }
        unsafeBitCast(sym, to: IsPlaying.self)(DispatchQueue.main, done)
    }

    static func quiet(pause: Bool, duckTo: Double?) {
        if let to = duckTo, let v = volume() {
            original = v
            let target = min(v, v * to)
            ducked = target
            setVolume(target)
        }
        guard pause else { return }
        isPlaying { playing in
            guard playing else { return }
            paused = command(1) // kMRPause
            Log.info("callmedia.pause", ["ok": "\(paused)"])
        }
    }

    static func restore() {
        if let o = original, let d = ducked, let now = volume(), let v = CallMediaPolicy.restoreVolume(original: o, ducked: d, now: now) {
            setVolume(v)
        }
        original = nil
        ducked = nil
        if paused { _ = command(0) } // kMRPlay
        paused = false
    }

    /// Output volume 0…1 via AppleScript's volume settings (no permission needed).
    static func volume() -> Double? {
        var err: NSDictionary?
        let r = NSAppleScript(source: "output volume of (get volume settings)")?.executeAndReturnError(&err)
        guard err == nil, let r else { return nil }
        return Double(r.int32Value) / 100
    }

    static func setVolume(_ v: Double) {
        var err: NSDictionary?
        _ = NSAppleScript(source: "set volume output volume \(Int((v * 100).rounded()))")?.executeAndReturnError(&err)
    }
}

// MARK: - Proximity unlock

/// Wakes the display and types the Keychain password into the lock screen with synthetic key
/// events. Needs Accessibility; only called by AppModel.checkProximityUnlock when the screen is
/// locked, the paired phone is near over an authenticated link, and the user opted in.
enum ProximityUnlocker {
    static func unlock() {
        guard let password = Keychain.unlockPassword, Accessibility.trusted else { return }
        var assertion: IOPMAssertionID = 0
        IOPMAssertionDeclareUserActivity("Tether proximity unlock" as CFString, kIOPMUserActiveLocal, &assertion)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            let src = CGEventSource(stateID: .hidSystemState)
            for ch in password.utf16 {
                var c = ch
                let down = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: true)
                down?.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c)
                let up = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: false)
                up?.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c)
                down?.post(tap: .cghidEventTap)
                up?.post(tap: .cghidEventTap)
                usleep(12_000)
            }
            let ret = CGKeyCode(kVK_Return)
            CGEvent(keyboardEventSource: src, virtualKey: ret, keyDown: true)?.post(tap: .cghidEventTap)
            CGEvent(keyboardEventSource: src, virtualKey: ret, keyDown: false)?.post(tap: .cghidEventTap)
            IOPMAssertionRelease(assertion)
        }
    }
}

// MARK: - Widgets

/// Writes the snapshot the WidgetKit widgets read (battery, now playing) into the shared app
/// group container and asks WidgetKit to reload.
enum WidgetBridge {
    static let group = "group.in.benfranklin.tether"
    static let file = "widget.json"
    private static var last: WidgetSnapshot?

    static var url: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent(file)
    }

    static func publish(_ m: AppModel) {
        guard !m.demo else { return }
        var s = WidgetSnapshot(phoneName: m.peer?.name, connected: m.isConnected, battery: m.battery?.level,
                               charging: m.battery?.charging ?? false, songTitle: m.nowPlaying?.title,
                               songArtist: m.nowPlaying?.artist, playing: m.nowPlaying?.playing ?? false, updated: 0)
        let compare = s
        if var l = last {
            l.updated = 0
            if l == compare { return }
        }
        s.updated = AppModel.nowMs
        last = s
        guard let url, let data = s.encoded() else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
        #if canImport(WidgetKit)
        if #available(macOS 14.0, *) { WidgetCenter.shared.reloadAllTimelines() }
        #endif
    }
}

// MARK: - Services menu + share extension inbox

/// "Open on Phone" and "Send to Phone" in every app's Services menu (Info.plist NSServices).
final class ServiceProvider: NSObject {
    weak var model: AppModel?

    @objc func openOnPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let url = (pboard.readObjects(forClasses: [NSURL.self], options: nil) as? [URL])?.first { $0.scheme?.hasPrefix("http") == true }
        let text = pboard.string(forType: .string)
        DispatchQueue.main.async { self.model?.openOnPhone(url: url?.absoluteString, text: url == nil ? text : nil) }
    }

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        DispatchQueue.main.async {
            if !urls.isEmpty {
                self.model?.sendFiles(urls)
            } else if let t = pboard.string(forType: .string) {
                self.model?.engine?.sendClip(t, manual: true)
            }
        }
    }
}

/// Files the Finder Share extension dropped into the shared container (tether://inbox).
enum ShareInbox {
    static var dir: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: WidgetBridge.group)?.appendingPathComponent("Inbox")
    }

    static func drain(into m: AppModel) {
        guard let dir else { return }
        let fm = FileManager.default
        let items = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        guard !items.isEmpty else { return }
        m.sendFiles(items)
        // MacFiles.stage copies each file; clear the inbox after staging has had time to read them.
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { items.forEach { try? fm.removeItem(at: $0) } }
    }
}
