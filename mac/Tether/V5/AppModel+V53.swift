import AppKit
import IOKit.ps
import TetherCore

/// 0.5.3 (protocol revision 7): the Mac's battery and what is playing on it, for the phone's
/// Home panels, and play/pause/skip from the phone.
extension AppModel {
    func linkChanged7(wasConnected: Bool) {
        let pusher = MacStatusPusher.shared
        if isConnected && !wasConnected && supports(Caps7.macStatus) {
            pusher.gate.reset()
            pushMacStatus(media: false, force: true)
            pusher.start { [weak self] in self?.pushMacStatus(media: false, force: false) }
        } else if !isConnected {
            pusher.stop()
        }
    }

    func receive7(_ message: Message) -> Bool {
        switch message {
        case .macStatusReq(let r): pushMacStatus(media: r.media, force: true)
        case .macMediaCmd(let c): macMediaCommand(c.cmd)
        default: return false
        }
        return true
    }

    /// Sends MAC_STATUS. Unforced pushes only go out when the battery changed.
    func pushMacStatus(media: Bool, force: Bool) {
        guard supports(Caps7.macStatus) else { return }
        let battery = MacBattery.read()
        let changed = MacStatusPusher.shared.gate.shouldSend(level: battery?.level, charging: battery?.charging)
        guard force || changed else { return }
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        guard media else {
            engine?.send(.macStatus(MacStatusPayload(battery: battery?.level, charging: battery?.charging, ts: ts)))
            return
        }
        MacNowPlaying.read { [weak self] np in
            self?.engine?.send(.macStatus(MacStatusPayload(battery: battery?.level, charging: battery?.charging,
                                                           media: np ?? MacMediaPayload(active: false), ts: ts)))
        }
    }

    private func macMediaCommand(_ cmd: String) {
        guard MacMediaCmds.all.contains(cmd) else { return }
        MacNowPlaying.command(cmd)
        Log.info("macmedia.cmd", ["cmd": cmd])
        // Let the player settle, then report the new state.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.pushMacStatus(media: true, force: true) }
    }
}

/// Polls the battery once a minute while linked; MAC_STATUS goes out only when it changed.
final class MacStatusPusher {
    static let shared = MacStatusPusher()
    var gate = MacBatteryGate()
    private var timer: Timer?

    func start(_ tick: @escaping () -> Void) {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { _ in tick() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        gate.reset()
    }
}

/// The internal battery from IOKit; nil on a Mac without one.
enum MacBattery {
    static func read() -> (level: Int, charging: Bool)? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for ps in list {
            guard let d = IOPSGetPowerSourceDescription(info, ps)?.takeUnretainedValue() as? [String: Any],
                  (d[kIOPSTypeKey] as? String) == kIOPSInternalBatteryType,
                  let cur = d[kIOPSCurrentCapacityKey] as? Int, let max = d[kIOPSMaxCapacityKey] as? Int, max > 0 else { continue }
            let level = Swift.min(100, Swift.max(0, cur * 100 / max))
            let charging = (d[kIOPSIsChargingKey] as? Bool) == true || (d[kIOPSPowerSourceStateKey] as? String) == kIOPSACPowerValue
            return (level, charging)
        }
        return nil
    }
}

/// What is playing on the Mac. MediaRemote first (works up to macOS 15.3); on newer systems,
/// where it answers nothing to other apps, Music or Spotify are asked over AppleScript — only
/// when already running, so nothing is ever launched.
enum MacNowPlaying {
    private typealias GetInfo = @convention(c) (DispatchQueue, @escaping (CFDictionary?) -> Void) -> Void
    private typealias SendCommand = @convention(c) (Int32, CFDictionary?) -> Bool
    private static let mediaRemote: UnsafeMutableRawPointer? =
        dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_LAZY)
    private static let scripts = DispatchQueue(label: "tether.nowplaying")
    /// The player that answered last ("spotify" / "music"), so commands go to the same place.
    private static var scripted: String?

    static func read(_ done: @escaping (MacMediaPayload?) -> Void) {
        guard let h = mediaRemote, let sym = dlsym(h, "MRMediaRemoteGetNowPlayingInfo") else { return readScripted(done) }
        unsafeBitCast(sym, to: GetInfo.self)(DispatchQueue.main) { dict in
            if let p = parse(dict as? [String: Any] ?? [:]) {
                scripted = nil
                done(p)
            } else {
                readScripted(done)
            }
        }
    }

    static func parse(_ d: [String: Any]) -> MacMediaPayload? {
        guard let title = d["kMRMediaRemoteNowPlayingInfoTitle"] as? String, !title.isEmpty else { return nil }
        let rate = d["kMRMediaRemoteNowPlayingInfoPlaybackRate"] as? Double ?? 0
        var elapsed = d["kMRMediaRemoteNowPlayingInfoElapsedTime"] as? Double ?? 0
        if rate > 0, let at = d["kMRMediaRemoteNowPlayingInfoTimestamp"] as? Date { elapsed += Date().timeIntervalSince(at) * rate }
        let duration = d["kMRMediaRemoteNowPlayingInfoDuration"] as? Double ?? 0
        return MacMediaPayload(active: true, title: title, artist: d["kMRMediaRemoteNowPlayingInfoArtist"] as? String ?? "",
                               playing: rate > 0, durationMs: Int64(duration * 1000), positionMs: Int64(Swift.max(0, elapsed) * 1000))
    }

    private struct Player {
        let id: String
        let bundle: String
        let app: String
        /// Spotify reports durations in milliseconds, Music in seconds.
        let msDuration: Bool
    }

    private static let players = [
        Player(id: "spotify", bundle: "com.spotify.client", app: "Spotify", msDuration: true),
        Player(id: "music", bundle: "com.apple.Music", app: "Music", msDuration: false),
    ]

    private static func running() -> [Player] {
        players.filter { !NSRunningApplication.runningApplications(withBundleIdentifier: $0.bundle).isEmpty }
    }

    private static func readScripted(_ done: @escaping (MacMediaPayload?) -> Void) {
        let candidates = running()
        guard !candidates.isEmpty else { return done(nil) }
        scripts.async {
            var best: (MacMediaPayload, String)?
            for p in candidates {
                let src = """
                tell application "\(p.app)"
                  if player state is stopped then return ""
                  return (name of current track) & "\\n" & (artist of current track) & "\\n" & (duration of current track) & "\\n" & \
                (player position) & "\\n" & (player state as string)
                end tell
                """
                var err: NSDictionary?
                guard let out = NSAppleScript(source: src)?.executeAndReturnError(&err).stringValue, !out.isEmpty else { continue }
                let f = out.components(separatedBy: "\n")
                guard f.count >= 5 else { continue }
                let duration = Double(f[2].replacingOccurrences(of: ",", with: ".")) ?? 0
                let position = Double(f[3].replacingOccurrences(of: ",", with: ".")) ?? 0
                let playing = f[4].contains("playing")
                let payload = MacMediaPayload(active: true, title: f[0], artist: f[1], app: p.app, playing: playing,
                                              durationMs: Int64(p.msDuration ? duration : duration * 1000),
                                              positionMs: Int64(position * 1000))
                if best == nil || (playing && best?.0.playing == false) { best = (payload, p.id) }
            }
            DispatchQueue.main.async {
                scripted = best?.1
                done(best?.0)
            }
        }
    }

    static func command(_ cmd: String) {
        if let id = scripted, let p = players.first(where: { $0.id == id }) {
            let verb = cmd == MacMediaCmds.next ? "next track" : (cmd == MacMediaCmds.prev ? "previous track" : "playpause")
            scripts.async {
                var err: NSDictionary?
                _ = NSAppleScript(source: "tell application \"\(p.app)\" to \(verb)")?.executeAndReturnError(&err)
            }
            return
        }
        // kMRTogglePlayPause = 2, kMRNextTrack = 4, kMRPreviousTrack = 5
        let code: Int32 = cmd == MacMediaCmds.next ? 4 : (cmd == MacMediaCmds.prev ? 5 : 2)
        guard let h = mediaRemote, let sym = dlsym(h, "MRMediaRemoteSendCommand") else { return }
        _ = unsafeBitCast(sym, to: SendCommand.self)(code, nil)
    }
}
